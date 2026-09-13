package com.jugaad.agent.domain.model

/** Which compute path produced the CNN result — surfaced as the demo "backend indicator". */
enum class InferenceBackend(val shortLabel: String, val longLabel: String) {
    NONE("--","Anomaly only"),
    CPU("CPU", "ExecuTorch · XNNPACK (CPU)"),
    NPU("NPU", "ExecuTorch · QNN (Hexagon NPU)"),
    LITERT("CPU", "LiteRT · federated head (CPU)");
}
