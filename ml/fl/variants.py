"""
Variant table for the champion/challenger federated network.

Matches the table in "Model spec v2" (plans/2026-09-12-champion-challenger-network.md),
updated for v3's 260-d input (plans/2026-09-12-sensors-strategies-datasets.md
Decision 2/3: dims 256-259 are the four sensor deltas). All variants share the
input layout and the four signatures; only the hidden-layer architecture and
the training recipe vary. `noise` and `balanced`
reuse `base`'s graph (same architecture, weightCount, asset file) -- they get
their own weights at runtime because each is loaded into its own
tf.lite.Interpreter; the recipe (input noise / class-balanced batches) is
applied outside the graph, in numpy, so no new ops are needed.
"""
from dataclasses import dataclass
from typing import List

from head_model import compute_weight_count

ASSET_DIR = "models"


@dataclass(frozen=True)
class VariantSpec:
    id: str
    asset: str
    layers: List[int]
    weight_count: int
    epochs: int
    noise_sigma: float
    balanced: bool
    label: str
    description: str


def _asset(file_name: str) -> str:
    return f"{ASSET_DIR}/{file_name}"


_TABLE = [
    VariantSpec(
        id="base", asset=_asset("fl_head_base.tflite"), layers=[260, 64, 3],
        weight_count=16899, epochs=10, noise_sigma=0.0, balanced=False,
        label="Base",
        description="260 -> 64 -> 3, natural class mix, no noise. Champion at install.",
    ),
    VariantSpec(
        id="small", asset=_asset("fl_head_small.tflite"), layers=[260, 32, 3],
        weight_count=8451, epochs=10, noise_sigma=0.0, balanced=False,
        label="Small",
        description="260 -> 32 -> 3, half the hidden width of base.",
    ),
    VariantSpec(
        id="deep", asset=_asset("fl_head_deep.tflite"), layers=[260, 64, 32, 3],
        weight_count=18883, epochs=10, noise_sigma=0.0, balanced=False,
        label="Deep",
        description="260 -> 64 -> 32 -> 3, one extra hidden layer.",
    ),
    VariantSpec(
        id="noise", asset=_asset("fl_head_base.tflite"), layers=[260, 64, 3],
        weight_count=16899, epochs=10, noise_sigma=0.15, balanced=False,
        label="Noise",
        description=(
            "Same graph as base (separate interpreter/weights); Gaussian input "
            "noise sigma 0.15 added to x before each train step (training only)."
        ),
    ),
    VariantSpec(
        id="balanced", asset=_asset("fl_head_base.tflite"), layers=[260, 64, 3],
        weight_count=16899, epochs=10, noise_sigma=0.0, balanced=True,
        label="Balanced",
        description=(
            "Same graph as base (separate interpreter/weights); each batch samples "
            "uniformly per class, with replacement."
        ),
    ),
]

for _v in _TABLE:
    _computed = compute_weight_count(_v.layers)
    assert _computed == _v.weight_count, (
        f"variant '{_v.id}': computed weight count {_computed} != table value {_v.weight_count}"
    )

ALL: List[VariantSpec] = _TABLE
CHAMPION_DEFAULT = "base"
_BY_ID = {v.id: v for v in ALL}

challengers: List[VariantSpec] = [v for v in ALL if v.id != CHAMPION_DEFAULT]

# Distinct asset files that need exporting -- noise/balanced reuse base's.
ASSET_FILES: List[str] = sorted({v.asset for v in ALL})


def by_id(variant_id: str) -> VariantSpec:
    return _BY_ID[variant_id]


if __name__ == "__main__":
    for v in ALL:
        print(v.id, v.asset, v.layers, v.weight_count)
    print("distinct asset files:", ASSET_FILES)
