<h1 align="center">AiPaca</h1>

<p align="center">
  <strong>Private, on-device LLM inference for Android.</strong><br>
  GGUF models on Snapdragon, powered by <a href="https://github.com/ggml-org/llama.cpp">llama.cpp</a>.
</p>

<p align="center">
  <a href="README.ru.md">Русский</a> · <strong>English</strong>
</p>

<p align="center">
  <a href="https://developer.android.com"><img src="https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white" alt="Android 10+"></a>
  <a href="https://kotlinlang.org"><img src="https://img.shields.io/badge/Kotlin-2.2.21-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin 2.2.21"></a>
  <a href="https://developer.android.com/jetpack/compose"><img src="https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white" alt="Jetpack Compose"></a>
  <a href="https://github.com/ggml-org/llama.cpp"><img src="https://img.shields.io/badge/llama.cpp-local%20inference-111111" alt="llama.cpp"></a>
  <a href="app/src/test"><img src="https://img.shields.io/badge/tests-330%2B-brightgreen" alt="330+ tests"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0--or--later-blue" alt="GPL-3.0-or-later"></a>
</p>

AiPaca is a native Android app for running GGUF language models entirely on device. It wraps
`llama-server` from llama.cpp in a foreground service and adds model management, streaming chat,
resumable Hugging Face downloads, launch profiles, benchmarks, and an OpenAI-compatible HTTP API.
Inference is fully local: no cloud backend and no account required.

The app is optimized for Snapdragon 8 Gen 3 (SM8650 / Galaxy S24 Ultra), but generic `arm64-v8a`
profiles work on other devices.

## Features

| Area | Capability |
|---|---|
| **Models** | Import GGUF files through Android SAF, list and load/unload models, follow the engine log in real time. |
| **Local engine** | `llama-server` runs as a separate process with health checks and a foreground service that exposes a **Stop** action. |
| **Chat** | Streaming responses, stop/regenerate/copy, tokens per second from server timings, sampling settings, system prompt, chat history, and a collapsible **Thinking** section for reasoning models. |
| **Accelerators** | CPU, OpenCL GPU, and Hexagon NPU selection with per-device launch profiles and automatic CPU fallback when GPU/NPU startup fails. |
| **Launch profiles** | Eight bundled profiles for SM8650 and generic arm64 devices, plus a remote profile repository with ETag caching and automatic refresh every 24 hours on Wi-Fi. |
| **Hugging Face** | GGUF search, downloads over 1–8 connections, resumable parts (even after the app is killed), SHA-256 verification, Wi-Fi-only mode, WorkManager integration, and bookmarks. |
| **API server** | OpenAI-compatible endpoint with configurable port and API key, localhost/LAN access, QR code, and a `/v1/models` health check. |
| **Benchmarks** | In-app CPU/OpenCL/NPU runs (pp512/tg128 ×3), history, and export. |
| **UX** | English and Russian UI, light/dark/dynamic themes. |

## Requirements

| Component | Requirement |
|---|---|
| Device | `arm64-v8a`, Android 10 (API 29) or newer |
| RAM | Depends on model size; a 12 GB Snapdragon device comfortably runs 3–4B models at Q4 |
| Build | JDK 17, Android SDK 36, Docker (engine only), roughly 11 GB of free disk space |

Only `arm64-v8a` is supported.

## Quick start

```bash
# 1. Build the native engine (Docker; writes ~184 MB to app/src/main/jniLibs)
./scripts/build-engine.sh

# 2. Build the debug APK
./gradlew :app:assembleDebug

# 3. Install on a connected arm64 device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then, in the app:

1. Open **Models** → **Import GGUF** and pick a `.gguf` file.
2. Tap **Load** next to the model.
3. Start a new chat, or open **API server** to expose the OpenAI-compatible API on localhost or LAN.

> **Native libraries are not stored in git.** `app/src/main/jniLibs/` is excluded because it is
> ~184 MB of build output. The APK still builds without it, but the engine will not start.
> See [docs/BUILDING.md](docs/BUILDING.md).

The engine script builds the current llama.cpp `HEAD` and records the commit in
`scripts/engine-version.txt`. Pin or vendor the revision yourself if you need reproducible builds.

## Performance

S24 Ultra (SM8650), Qwen3-4B Q4_0, `pp128`/`tg64`, tokens per second:

| Backend | pp128 | tg64 | Notes |
|---|--:|--:|---|
| CPU (4 threads, `-C 0xF0`) | 70.9 | 16.6 | Runs in the app |
| OpenCL (Adreno 750) | 97.8 | 9.6 | Measured outside the app: the OpenCL platform is not exposed to `untrusted_app`, so GPU selection falls back to CPU |
| Hexagon NPU (HTP0) | 649.4 | 16.2 | Runs in the app; FastRPC libraries are staged automatically |

End-to-end chat from the app (Qwen3-4B Q4_0, context 4096, KV `q8_0`): CPU ≈ 15.0 t/s,
NPU ≈ 13.4 t/s. The NPU advantage is prefill (`pp` ≈ 1183 vs ≈ 71) and CPU offload
(RSS ≈ 0.4 GB vs ≈ 2.8 GB).

Benchmark from the app on a 0.6B BF16 model (warmed-up phone): pp512 13.33 ± 1.68,
tg128 8.46 ± 0.05 t/s. Numbers are device-specific and vary with thermals.

## Tech stack

| Layer | Stack |
|---|---|
| UI | Kotlin 2.2, Jetpack Compose, Material 3, Navigation Compose |
| Data | Room 2.7, DataStore, WorkManager |
| Networking | OkHttp, kotlinx.serialization |
| Native | llama.cpp / ggml with CPU, OpenCL, and Hexagon backends, built with the Snapdragon toolchain |
| Dependency injection | Manual `AppContainer` (no Hilt/Koin) |

## Project layout

```text
app/                     Android app (Kotlin, Compose, Room)
  src/main/jniLibs/      compiled llama.cpp libraries (not tracked)
  src/main/assets/       bundled launch profiles
scripts/                 engine build script and version info
profiles-repo/           example GitHub profile repository
docs/                    architecture, profiles, and build documentation
.github/workflows/       Android CI, engine build and releases
```

## Testing and CI

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Unit tests are pure JVM tests and do not need an emulator, Robolectric, or a device.

GitHub Actions runs the same check in [`.github/workflows/android.yml`](.github/workflows/android.yml)
and uploads the debug APK as an artifact. [`engine.yml`](.github/workflows/engine.yml) builds the
native engine in Docker and uploads `app/src/main/jniLibs` as a separate artifact.

Because native libraries are not tracked, a runnable APK needs the engine artifact (or a local
`./scripts/build-engine.sh` run) staged into `app/src/main/jniLibs/arm64-v8a`.

## Releases

Every push to `main` runs [`release.yml`](.github/workflows/release.yml): it builds the engine at
the llama.cpp revision pinned in [`scripts/engine-ref.txt`](scripts/engine-ref.txt) (cached until
the revision changes), runs the unit tests, builds a signed release APK and publishes it as a
GitHub release `vX.Y.Z`.

Versions are computed by [`scripts/next-version.sh`](scripts/next-version.sh): the patch number
is bumped by default, `#minor` or `#major` in a commit message bumps that part instead.
`versionCode` is the number of commits on `main`.

## Documentation

| Document | Contents |
|---|---|
| [Architecture](docs/ARCHITECTURE.md) | Module map and the flow from model to chat |
| [Profiles](docs/PROFILES.md) | Profile format, matcher, and publishing your own profile repository |
| [Building](docs/BUILDING.md) | Prerequisites, engine/app builds, and `LD_LIBRARY_PATH` details |
| [AGENTS.md](AGENTS.md) | Repository conventions and practical notes for AI agents |

Detailed documentation is currently written in Russian.

## Known limitations

- **NPU (Hexagon) is experimental.** The app stages FastRPC libraries (`libcdsprpc`, `libOpenCL`,
  `libvmem`, and others) from the vendor partition into its `filesDir` and starts the engine with
  `--device HTP0`. This works end-to-end on the S24U (SM8650), including from a regular
  `untrusted_app` process. Compatibility with other devices is not guaranteed; on failure the app
  automatically falls back to the CPU profile. NPU profiles are considered by the matcher only when
  NPU is selected in settings.
- **OpenCL is unavailable inside the app on the S24U.** The driver does not enumerate platforms in
  the `untrusted_app` context (`ggml_opencl: platform IDs not available`), so selecting GPU falls
  back to CPU. OpenCL measurements are only possible outside the app.
- **Vendor binaries are not redistributed.** Qualcomm HTP skel libraries and other proprietary
  artifacts are not published. Engine libraries are built locally and excluded from git.
- **Trademarks.** AiPaca is not a Qualcomm product. Snapdragon, Hexagon, and Adreno are trademarks
  of Qualcomm Technologies, Inc.

## Contributing

Bug reports and pull requests are welcome. For larger changes, please open an issue first so the
approach can be discussed. Run the full check above before submitting a PR.

## Acknowledgements

- [llama.cpp and ggml](https://github.com/ggml-org/llama.cpp) for the inference engine
- [Snapdragon toolchain](https://github.com/snapdragon-toolchain) for the Android native build
  environment

## License

AiPaca is free software: you can redistribute it and/or modify it under the terms of the
[GNU General Public License v3.0 or later](LICENSE).

Copyright (C) 2026 The AiPaca Authors.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
[GNU GPL v3](LICENSE) for details.
