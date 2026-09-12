"""
Resume-safe downloader for the two MAFAULDA subsets used by mafaulda_ingest.py.
Separate from mafaulda_ingest.py so the download (minutes, network-bound) can
run standalone / be retried without re-touching the ingest logic.

Usage: python mafaulda_download.py
"""
import pathlib
import sys
import time
import urllib.request

DATA_DIR = pathlib.Path(__file__).resolve().parents[1] / "data" / "mafaulda"

FILES = {
    "normal.zip": (
        "https://www02.smt.ufrj.br/~offshore/mfs/database/mafaulda/normal.zip",
        325_307_137,
    ),
    "imbalance.zip": (
        "https://www02.smt.ufrj.br/~offshore/mfs/database/mafaulda/imbalance.zip",
        2_212_894_986,
    ),
}

CHUNK = 1024 * 1024
PROGRESS_EVERY = 50 * 1024 * 1024


def download(name, url, expected_size):
    out_path = DATA_DIR / name
    DATA_DIR.mkdir(parents=True, exist_ok=True)

    existing = out_path.stat().st_size if out_path.exists() else 0
    if existing >= expected_size:
        print(f"{name}: already complete ({existing} bytes), skipping")
        return out_path

    headers = {}
    mode = "wb"
    if existing > 0:
        headers["Range"] = f"bytes={existing}-"
        mode = "ab"
        print(f"{name}: resuming from {existing} bytes")

    req = urllib.request.Request(url, headers=headers)
    t0 = time.time()
    next_report = existing + PROGRESS_EVERY
    try:
        with urllib.request.urlopen(req, timeout=60) as resp, open(out_path, mode) as f:
            downloaded = existing
            while True:
                chunk = resp.read(CHUNK)
                if not chunk:
                    break
                f.write(chunk)
                downloaded += len(chunk)
                if downloaded >= next_report:
                    elapsed = time.time() - t0
                    mb = downloaded / 1e6
                    rate = (downloaded - existing) / 1e6 / max(elapsed, 1e-6)
                    print(f"{name}: {mb:.0f} MB downloaded ({rate:.1f} MB/s)")
                    next_report += PROGRESS_EVERY
    except Exception as e:
        print(f"{name}: download interrupted at {out_path.stat().st_size if out_path.exists() else 0} bytes: {e}")
        raise

    final_size = out_path.stat().st_size
    print(f"{name}: done, {final_size} bytes (expected {expected_size})")
    return out_path


def main():
    for name, (url, size) in FILES.items():
        try:
            download(name, url, size)
        except Exception as e:
            print(f"{name}: FAILED ({e}); will need retry")
    print("all downloads attempted")


if __name__ == "__main__":
    sys.exit(main())
