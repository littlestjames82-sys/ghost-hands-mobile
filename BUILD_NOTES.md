# Ghost Hands Proof

This directory contains the early on-device inference proof app, not the
main Ghost Hands Mobile app. It downloads a Qwen2.5-1.5B GGUF model to
app-private storage, verifies the download, and runs a small repeatable
benchmark through `llama.cpp`.

The proof app targets `arm64-v8a` and Android API 24+. Its build uses the
repository's `native/build-llama.sh` and `build-apk.sh` scripts, which
require a separately installed Android toolchain and `llama.cpp` checkout.
See the root [README](README.md) for the repository overview and build caveats.

Signing keys, credentials, and generated APKs are local build artifacts and
must not be committed. Use a private release keystore for distribution.
