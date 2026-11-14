# Local Maven Repository (`local-repo`)

This directory provides a local Maven repository for binary dependencies that are not hosted on Maven Central/JitPack and are too large to store directly in Git (`*.aar` files are excluded via `.gitignore`).

## Sherpa-ONNX (`com.k2fsa.sherpa:sherpa-onnx:2.0.1`)

- **Automatic bootstrap**: `settings.gradle.kts` automatically downloads and SHA-256 verifies `sherpa-onnx-2.0.1.aar` from the official `k2-fsa/sherpa-onnx` GitHub release if it is missing when Gradle initializes.
- **Manual / offline setup**:

```bash
mkdir -p local-repo/com/k2fsa/sherpa/sherpa-onnx/2.0.1
curl -L -f -o local-repo/com/k2fsa/sherpa/sherpa-onnx/2.0.1/sherpa-onnx-2.0.1.aar \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar
echo "a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7  local-repo/com/k2fsa/sherpa/sherpa-onnx/2.0.1/sherpa-onnx-2.0.1.aar" | sha256sum -c -
```
