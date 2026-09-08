# Drop model files here

The app is **fully functional without any of these** (anomaly score + template advice).
Add them to light up the optional paths, then flip the matching flag in
`gradle.properties` and rebuild.

| File | Enables | Flag |
|------|---------|------|
| `fault_cnn_xnnpack.pte` | CNN fault class on CPU | `jugaad.executorch.enabled=true` |
| `fault_cnn_qnn.pte` | CNN fault class on Snapdragon HTP / NPU | same flag |
| `gemma3-1b-it-int4.task` | On-device natural-language advice | `jugaad.gemma.enabled=true` |

`ModelInstaller` copies whatever is present here into `filesDir/models/` on first
launch (ExecuTorch / MediaPipe need a real file path, not an asset stream).

See `../../../../ml/README.md` for how to train and export the `.pte` files and
where to get the Gemma `.task` bundle.

These binaries are git-ignored on purpose — keep them out of version control.
