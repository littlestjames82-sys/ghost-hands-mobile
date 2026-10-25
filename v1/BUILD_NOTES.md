# Ghost Hands Mobile

This is the main Android app in the repository (manifest version `3.4.0`).
It combines a `WebView`, a goal-driven Java agent, a policy Governor, a
decision trail, and on-device inference through a JNI bridge to `llama.cpp`.
The Qwen2.5-1.5B Q4_K_M and Q5_K_M models are downloaded on demand; neither
model is bundled in the APK.

The app targets `arm64-v8a` and Android API 24+. Its manual build script
expects a separate Android toolchain and native `llama.cpp` build, plus
workspace-specific paths that need adapting for another machine. Refer to the
root [README](../README.md) for an overview and the build limitations.

`test/GovernorTest.java` is a host-side test harness for Android-free Java
components. It can be compiled and run with a JDK; the APK build itself is a
separate process.

Signing keys, passwords, and generated APKs are local build artifacts. Keep
them out of source control and use a private release keystore for distribution.
