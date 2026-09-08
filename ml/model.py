"""
Small depthwise-separable CNN for fault classification.

Input : (B, 1, 128, 128)  per-image standardised log-mel
Output: (B, 3)             logits over [Healthy, Rotor Imbalance, Airflow Obstruction]

~60k parameters, GAP head, no BatchNorm in the deployable path (folded at export)
so the XNNPACK / QNN .pte stays tiny and fast (<1 s CPU target).
"""
import torch
import torch.nn as nn

LABELS = ["Healthy", "Rotor Imbalance", "Airflow Obstruction"]


class SeparableBlock(nn.Module):
    def __init__(self, cin, cout, stride=2):
        super().__init__()
        self.dw = nn.Conv2d(cin, cin, 3, stride=stride, padding=1, groups=cin, bias=False)
        self.pw = nn.Conv2d(cin, cout, 1, bias=False)
        self.bn = nn.BatchNorm2d(cout)
        self.act = nn.ReLU(inplace=True)

    def forward(self, x):
        return self.act(self.bn(self.pw(self.dw(x))))


class FaultCNN(nn.Module):
    def __init__(self, n_classes=3):
        super().__init__()
        self.stem = nn.Sequential(
            nn.Conv2d(1, 16, 3, stride=2, padding=1, bias=False),   # 128 -> 64
            nn.BatchNorm2d(16),
            nn.ReLU(inplace=True),
        )
        self.blocks = nn.Sequential(
            SeparableBlock(16, 32),    # 64 -> 32
            SeparableBlock(32, 48),    # 32 -> 16
            SeparableBlock(48, 64),    # 16 -> 8
            SeparableBlock(64, 96),    # 8  -> 4
        )
        self.head = nn.Linear(96, n_classes)

    def forward(self, x):
        x = self.stem(x)
        x = self.blocks(x)
        x = x.mean(dim=(2, 3))          # global average pool
        return self.head(x)


if __name__ == "__main__":
    m = FaultCNN()
    n = sum(p.numel() for p in m.parameters())
    y = m(torch.randn(2, 1, 128, 128))
    print("params:", n, "output:", tuple(y.shape))
