"""
Export a trained FaultCNN to ExecuTorch .pte files.

    python export_executorch.py --ckpt out/fault_cnn.pt --backend xnnpack \
        --out ../app/src/main/assets/models/fault_cnn_xnnpack.pte

    python export_executorch.py --ckpt out/fault_cnn.pt --backend qnn \
        --out ../app/src/main/assets/models/fault_cnn_qnn.pte

XNNPACK path uses only the core `executorch` package.
QNN path additionally requires the Qualcomm AI Engine Direct SDK (QAIRT) with
`QNN_SDK_ROOT` set; it lowers to the SM8850 HTP. See README.md.

The exported program takes a single input of shape (1, 1, 128, 128) float32,
already per-image standardised on-device (ExecuTorchFaultClassifier.standardise).
"""
import argparse
import torch

from model import FaultCNN, LABELS


def load_model(ckpt_path):
    ck = torch.load(ckpt_path, map_location="cpu")
    net = FaultCNN(len(ck.get("labels", LABELS)))
    net.load_state_dict(ck["state_dict"])
    net.eval()
    return net


def export_xnnpack(net, out_path):
    from executorch.exir import to_edge_transform_and_lower
    from executorch.backends.xnnpack.partition.xnnpack_partitioner import XnnpackPartitioner

    example = (torch.randn(1, 1, 128, 128),)
    exported = torch.export.export(net, example)
    lowered = to_edge_transform_and_lower(
        exported, partitioner=[XnnpackPartitioner()]
    ).to_executorch()
    with open(out_path, "wb") as f:
        f.write(lowered.buffer)
    print(f"wrote {out_path}  ({len(lowered.buffer)} bytes)  [XNNPACK / CPU]")


def export_qnn(net, out_path, soc_model="SM8850"):
    # Requires: pip install executorch  +  QAIRT SDK  +  QNN_SDK_ROOT env var.
    from executorch.exir import to_edge_transform_and_lower
    from executorch.backends.qualcomm.partition.qnn_partitioner import QnnPartitioner
    from executorch.backends.qualcomm.utils.utils import (
        generate_qnn_executorch_compiler_spec,
        get_soc_to_chipset_map,
    )
    from executorch.backends.qualcomm.serialization.qc_schema import QcomChipset

    example = (torch.randn(1, 1, 128, 128),)
    exported = torch.export.export(net, example)

    chipset = get_soc_to_chipset_map().get(soc_model, QcomChipset.SM8650)
    compiler_spec = generate_qnn_executorch_compiler_spec(
        soc_model=chipset,
        backend_options=None,   # defaults to HTP fp16
    )
    lowered = to_edge_transform_and_lower(
        exported, partitioner=[QnnPartitioner(compiler_spec)]
    ).to_executorch()
    with open(out_path, "wb") as f:
        f.write(lowered.buffer)
    print(f"wrote {out_path}  ({len(lowered.buffer)} bytes)  [QNN / HTP {soc_model}]")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ckpt", default="out/fault_cnn.pt")
    ap.add_argument("--backend", choices=["xnnpack", "qnn"], default="xnnpack")
    ap.add_argument("--soc", default="SM8850", help="QNN target SoC part number")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    net = load_model(args.ckpt)
    if args.backend == "xnnpack":
        export_xnnpack(net, args.out)
    else:
        export_qnn(net, args.out, args.soc)


if __name__ == "__main__":
    main()
