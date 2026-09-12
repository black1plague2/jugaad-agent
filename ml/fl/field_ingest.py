"""
Pulls labelled FL samples off every adb-attached jugaad-agent debug build into
the offline training pipeline (v4 plan `plans/2026-09-12-v4-catalogue-config-
selfhealing-enrichment.md` Section 6):

    phone (files/fl/samples.jsonl, files/fl/shared_samples.jsonl)
      -> field_ingest.py (this file) --for-training
      -> ml/data/field/samples.jsonl (merged, deduped, labelled, 260-d, bench excluded)
      -> pretrain.py --include-field
      -> retrained heads baked into the next APK

For every device from `adb devices` (serials and `ip:port` entries alike),
pulls `files/fl/node.json` (for deviceId/name), `files/fl/samples.jsonl` and
`files/fl/shared_samples.jsonl` from the debuggable app via
`adb -s <dev> exec-out run-as com.jugaad.agent.debug cat files/fl/<name>`. A
missing file reads back as empty (or as the shell's own "No such file" text,
which fails JSON parsing and is silently skipped) -- both are normal. Keeps
only labelled samples (`label is not None`) with a 260-d `x`; tags each with
`device` (the adb identifier) and `origin` (the line's own, else the node's
deviceId, else the device identifier); dedupes by `id` across all devices,
first seen wins.

Without --for-training, everything pulled is written unfiltered to a dated
samples.<date>.DO-NOT-TRAIN.jsonl / summary.<date>.json -- safe for manual
review, never for pretrain.py. With --for-training, each sample's `assetId`
is looked up against files/assets/<assetId>/asset.json on its origin device;
samples belonging to an asset with `benchTest: true` (bench/test equipment,
see AssetRepositoryImpl) are dropped before samples.jsonl/summary.json are
written, and the number skipped is printed.

Usage:
    python field_ingest.py [--devices dev1,dev2] [--out DIR] [--dry-run] [--for-training]

adb path: $ADB_PATH env var, else `tools/android-sdk/platform-tools/adb(.exe)`
next to the repo root, else `adb` on PATH.
"""
import argparse
import json
import os
import pathlib
import subprocess
import time

PKG = "com.jugaad.agent.debug"
INPUT_DIM = 260
ADB_TIMEOUT = 20

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]  # .../jugaad-agent
DEFAULT_OUT = pathlib.Path(__file__).resolve().parents[1] / "data" / "field"


def default_adb():
    env = os.environ.get("ADB_PATH")
    if env:
        return env
    exe = "adb.exe" if os.name == "nt" else "adb"
    candidate = REPO_ROOT.parent / "tools" / "android-sdk" / "platform-tools" / exe
    return str(candidate) if candidate.exists() else "adb"


ADB = default_adb()


def run_adb(args, timeout=ADB_TIMEOUT):
    """Runs adb with args (list). Returns (returncode, stdout_bytes, stderr_bytes);
    never raises -- a missing adb binary or a hung device becomes a non-zero rc."""
    try:
        proc = subprocess.run([ADB] + args, capture_output=True, timeout=timeout)
        return proc.returncode, proc.stdout, proc.stderr
    except FileNotFoundError as e:
        return 127, b"", str(e).encode()
    except subprocess.TimeoutExpired:
        return 124, b"", f"timeout after {timeout}s".encode()


def list_devices():
    """Returns [(identifier, state), ...] from `adb devices` (serials and
    `ip:port` entries both come back as plain two-column lines)."""
    rc, out, err = run_adb(["devices"])
    if rc != 0:
        print(f"  adb devices failed (rc={rc}): {err.decode(errors='replace').strip()}")
        return []
    devices = []
    for line in out.decode(errors="replace").splitlines():
        line = line.strip()
        if not line or line.startswith("List of devices"):
            continue
        parts = line.split()
        if len(parts) >= 2:
            devices.append((parts[0], parts[1]))
    return devices


def pull_path(device, rel_path):
    """cat's an arbitrary files/<rel_path> from the debug app via run-as.
    Returns (text, None) on success, (None, reason) on failure -- device
    offline/unreachable (adb itself fails) or the app missing/not debuggable
    (run-as fails but adb still exits 0, so its "run-as: ..." stdout is
    treated as an error too). A missing *file* is not a failure: `cat` prints
    "No such file" text with rc=0, which callers fail to parse and skip."""
    rc, out, err = run_adb(["-s", device, "exec-out", "run-as", PKG, "cat", f"files/{rel_path}"])
    if rc != 0:
        return None, err.decode(errors="replace").strip() or f"adb exit {rc}"
    text = out.decode(errors="replace")
    if text.startswith("run-as:"):
        return None, text.strip()
    return text, None


def pull_file(device, name):
    """cat's files/fl/<name> -- see [pull_path]."""
    return pull_path(device, f"fl/{name}")


def is_bench_asset(device, asset_id, cache):
    """Returns True if files/assets/<asset_id>/asset.json on `device` has
    benchTest: true (per AssetRepositoryImpl.assetFile / JsonFileStore). A
    missing or unparseable asset.json is treated as not-bench -- absence of
    proof is not proof of bench equipment. Caches by (device, asset_id) since
    many samples share the same asset."""
    key = (device, asset_id)
    if key in cache:
        return cache[key]
    text, _err = pull_path(device, f"assets/{asset_id}/asset.json")
    bench = False
    if text:
        try:
            obj = json.loads(text)
            bench = bool(isinstance(obj, dict) and obj.get("benchTest"))
        except (json.JSONDecodeError, ValueError):
            bench = False
    cache[key] = bench
    return bench


def parse_node(text):
    """Returns (deviceId, name) from a node.json body, or (None, None) if it's
    missing/unparseable."""
    if not text:
        return None, None
    try:
        obj = json.loads(text)
    except (json.JSONDecodeError, ValueError):
        return None, None
    if not isinstance(obj, dict):
        return None, None
    return obj.get("deviceId"), obj.get("name")


def parse_samples(text, device, fallback_origin):
    """Yields labelled, 260-d sample dicts tagged with `device`/`origin` from a
    samples.jsonl or shared_samples.jsonl body. One bad line (truncated JSON,
    the "No such file" shell text, wrong-length x, no label, no id) is skipped,
    never aborts the rest of the file."""
    if not text:
        return
    for raw in text.splitlines():
        raw = raw.strip()
        if not raw:
            continue
        try:
            obj = json.loads(raw)
        except (json.JSONDecodeError, ValueError):
            continue
        if not isinstance(obj, dict):
            continue
        if obj.get("label") is None:
            continue
        x = obj.get("x")
        if not isinstance(x, list) or len(x) != INPUT_DIM:
            continue
        if not obj.get("id"):
            continue
        yield {
            "id": obj["id"],
            "assetId": obj.get("assetId"),
            "x": x,
            "label": obj.get("label"),
            "source": obj.get("source"),
            "ts": obj.get("ts"),
            "score": obj.get("score"),
            "abs": obj.get("abs"),
            "absSensors": obj.get("absSensors"),
            "origin": obj.get("origin") or fallback_origin,
            "machineTypeId": obj.get("machineTypeId"),
            "device": device,
        }


def pull_device(device, device_status):
    """Pulls node.json + both sample files from one device. Returns a list of
    sample dicts; records an "ok"/"skipped" line in device_status. Never raises
    -- an offline device or one lacking the app ends up skipped, not a crash."""
    node_text, _node_err = pull_file(device, "node.json")
    device_id, _name = parse_node(node_text)
    fallback_origin = device_id or device

    samples_text, samples_err = pull_file(device, "samples.jsonl")
    shared_text, shared_err = pull_file(device, "shared_samples.jsonl")

    if samples_text is None and shared_text is None:
        device_status[device] = f"skipped: {samples_err or shared_err or 'unknown error'}"
        return []

    out = list(parse_samples(samples_text or "", device, fallback_origin))
    out += list(parse_samples(shared_text or "", device, fallback_origin))
    device_status[device] = f"ok ({len(out)} labelled 260-d samples, deviceId={device_id or '?'})"
    return out


def build_summary(merged, device_status):
    per_device, per_label, per_origin, per_machine, per_source = {}, {}, {}, {}, {}
    for s in merged:
        per_device[s["device"]] = per_device.get(s["device"], 0) + 1
        per_label[str(s["label"])] = per_label.get(str(s["label"]), 0) + 1
        origin = s["origin"] or "unknown"
        per_origin[origin] = per_origin.get(origin, 0) + 1
        mt = s["machineTypeId"] or "unknown"
        per_machine[mt] = per_machine.get(mt, 0) + 1
        src = s["source"] or "unknown"
        per_source[src] = per_source.get(src, 0) + 1
    return {
        "pulled_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "total_unique": len(merged),
        "devices": device_status,
        "per_device": per_device,
        "per_label": per_label,
        "per_origin": per_origin,
        "per_machine_type_id": per_machine,
        "per_source": per_source,
    }


def print_table(summary):
    print(f"\npulled at {summary['pulled_at']} -- {summary['total_unique']} unique labelled samples")

    print("\ndevices:")
    for dev, status in summary["devices"].items():
        print(f"  {dev:24s} {status}")

    def _table(title, d):
        print(f"\n{title}:")
        if not d:
            print("  (none)")
            return
        for k, v in sorted(d.items(), key=lambda kv: (-kv[1], kv[0])):
            print(f"  {k:24s} {v}")

    _table("per label", summary["per_label"])
    _table("per origin", summary["per_origin"])
    _table("per machine type", summary["per_machine_type_id"])
    _table("per source", summary["per_source"])
    _table("per device (unique samples attributed)", summary["per_device"])


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--devices", help="comma-separated adb identifiers to restrict to (default: all from `adb devices`)")
    ap.add_argument("--out", default=str(DEFAULT_OUT), help="output dir (default: ml/data/field)")
    ap.add_argument("--dry-run", action="store_true", help="pull and print the summary but do not write files")
    ap.add_argument(
        "--for-training",
        action="store_true",
        help=(
            "write samples.jsonl/summary.json for pretrain.py, dropping samples whose "
            "asset is flagged benchTest (pulled from files/assets/<assetId>/asset.json). "
            "Omit this flag to write a dated samples.<date>.DO-NOT-TRAIN.jsonl / "
            "summary.<date>.json dump of everything pulled, unfiltered."
        ),
    )
    args = ap.parse_args()

    all_devices = list_devices()
    if args.devices:
        wanted = {d.strip() for d in args.devices.split(",")}
        targets = [(d, s) for d, s in all_devices if d in wanted]
        for missing in wanted - {d for d, _ in all_devices}:
            print(f"  requested device {missing!r} not seen by `adb devices` -- skipping")
    else:
        targets = all_devices

    if not targets:
        print("no adb devices found (check `adb devices` / --devices filter)")

    device_status = {}
    merged = {}  # id -> sample, first seen (in adb-devices order) wins
    for device, state in targets:
        if state != "device":
            device_status[device] = f"skipped: adb state = {state!r}"
            print(f"  {device}: skipped (adb state {state!r})")
            continue
        print(f"  {device}: pulling node.json + samples.jsonl + shared_samples.jsonl ...")
        try:
            samples = pull_device(device, device_status)
        except Exception as e:  # a single device's failure must never kill the run
            device_status[device] = f"skipped: unexpected error: {e}"
            print(f"  {device}: skipped (unexpected error: {e})")
            continue
        for s in samples:
            if s["id"] not in merged:
                merged[s["id"]] = s

    merged_list = list(merged.values())

    skipped_bench = 0
    if args.for_training:
        cache = {}
        write_list = []
        for s in merged_list:
            asset_id = s.get("assetId")
            if asset_id and is_bench_asset(s["device"], asset_id, cache):
                skipped_bench += 1
                continue
            write_list.append(s)
    else:
        write_list = merged_list

    summary = build_summary(write_list, device_status)
    print_table(summary)
    if args.for_training:
        print(f"\nskipped {skipped_bench} sample(s) from bench/test equipment")

    if args.dry_run:
        print("\n--dry-run: not writing files")
        return

    out_dir = pathlib.Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    if args.for_training:
        samples_path = out_dir / "samples.jsonl"
        summary_path = out_dir / "summary.json"
    else:
        date_str = time.strftime("%Y-%m-%d")
        samples_path = out_dir / f"samples.{date_str}.DO-NOT-TRAIN.jsonl"
        summary_path = out_dir / f"summary.{date_str}.json"

    with samples_path.open("w", encoding="utf-8") as f:
        for s in write_list:
            f.write(json.dumps(s) + "\n")
    with summary_path.open("w", encoding="utf-8") as f:
        json.dump(summary, f, indent=2)

    print(f"\nwrote {samples_path} ({len(write_list)} samples)")
    print(f"wrote {summary_path}")


if __name__ == "__main__":
    main()
