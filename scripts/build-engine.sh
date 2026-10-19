#!/usr/bin/env bash
set -euo pipefail
trap 'echo "ERROR: build-engine.sh failed at line $LINENO" >&2' ERR

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
ENGINE_DIR="$PROJECT_ROOT/build/engine"
LLAMA_DIR="$ENGINE_DIR/llama.cpp"
IMAGE="ghcr.io/snapdragon-toolchain/arm64-android:v0.7"
PRESET="arm64-android-snapdragon-release"
BUILD_DIR="$LLAMA_DIR/build-snapdragon"
PKG_DIR="$LLAMA_DIR/pkg-android/llama.cpp"
STAGE_DIR="$ENGINE_DIR/stage/arm64-v8a"
JNI_DIR="$PROJECT_ROOT/app/src/main/jniLibs/arm64-v8a"
JOBS="${JOBS:-6}"

echo "== [1/6] environment checks =="
docker version --format '{{.Server.Version}}'
df -h / | tail -1
avail_kb="$(df --output=avail -k / | tail -1 | tr -d ' ')"
if [ "$avail_kb" -lt $((8 * 1024 * 1024)) ]; then
  echo "ERROR: less than 8GB free on /"
  exit 1
fi

echo "== [2/6] source clone + version =="
if [ ! -d "$LLAMA_DIR/.git" ]; then
  mkdir -p "$ENGINE_DIR"
  git clone --depth 1 https://github.com/ggml-org/llama.cpp "$LLAMA_DIR"
else
  echo "llama.cpp already present, using as-is (no fetch/reset)"
fi
HEAD_SHA="$(git -C "$LLAMA_DIR" rev-parse HEAD)"
{
  echo "llama.cpp HEAD: $HEAD_SHA"
  echo "build date:     $(date -Iseconds)"
  echo "image:          $IMAGE"
  echo "preset:         $PRESET"
} > "$ENGINE_DIR/version.txt"
cp "$ENGINE_DIR/version.txt" "$SCRIPT_DIR/engine-version.txt"
echo "HEAD: $HEAD_SHA"

echo "== [3/6] presets + image pull =="
if [ ! -f "$LLAMA_DIR/CMakeUserPresets.json" ]; then
  cp "$LLAMA_DIR/docs/backend/snapdragon/CMakeUserPresets.json" "$LLAMA_DIR/CMakeUserPresets.json"
fi
if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "pulling $IMAGE ..."
  docker pull --quiet "$IMAGE"
else
  echo "image already pulled"
fi

echo "== [4/6] docker cmake configure + build + install =="
docker run --rm \
  --platform linux/amd64 \
  --volume "$LLAMA_DIR:/workspace" \
  --workdir /workspace \
  -u "$(id -u):$(id -g)" \
  "$IMAGE" \
  bash -c '
set -e
cmake --preset arm64-android-snapdragon-release -B /workspace/build-snapdragon
cmake --build /workspace/build-snapdragon -j '"$JOBS"'
cmake --install /workspace/build-snapdragon --prefix /workspace/pkg-android/llama.cpp
'

echo "== [5/6] stage artifacts =="
rm -rf "$STAGE_DIR"
mkdir -p "$STAGE_DIR"

if [ ! -f "$PKG_DIR/bin/llama-server" ]; then
  echo "ERROR: llama-server not built"
  exit 1
fi
cp "$PKG_DIR/bin/llama-server" "$STAGE_DIR/libllama-server.so"
if [ -f "$PKG_DIR/bin/llama-bench" ]; then
  cp "$PKG_DIR/bin/llama-bench" "$STAGE_DIR/libllama-bench.so"
else
  echo "WARNING: llama-bench not built"
fi

shopt -s nullglob
libs=("$PKG_DIR"/lib/lib*.so)
shopt -u nullglob
if [ "${#libs[@]}" -eq 0 ]; then
  echo "ERROR: no shared libs in $PKG_DIR/lib"
  exit 1
fi
cp "${libs[@]}" "$STAGE_DIR/"
echo "installed libs:"
ls -1 "$PKG_DIR/lib"

need_cxx=0
for f in "$STAGE_DIR"/*.so; do
  if readelf -d "$f" 2>/dev/null | grep -q 'libc++_shared.so'; then
    need_cxx=1
    break
  fi
done
if [ "$need_cxx" -eq 1 ] && [ ! -f "$STAGE_DIR/libc++_shared.so" ]; then
  echo "libc++_shared.so is required, extracting from image NDK sysroot"
  docker run --rm --platform linux/amd64 -u "$(id -u):$(id -g)" \
    --volume "$STAGE_DIR:/out" --entrypoint /bin/bash "$IMAGE" \
    -c 'src="$(find /opt -path "*aarch64-linux-android/libc++_shared.so" 2>/dev/null | head -n1)"; if [ -n "$src" ]; then cp "$src" /out/libc++_shared.so; else echo "libc++_shared.so not found in image"; fi'
fi

if command -v patchelf >/dev/null 2>&1; then
  for f in "$STAGE_DIR"/*.so; do
    if readelf -h "$f" 2>/dev/null | grep -q AArch64; then
      patchelf --set-rpath '$ORIGIN' "$f"
    fi
  done
else
  echo "NOTE: patchelf not found - binaries rely on LD_LIBRARY_PATH at runtime"
fi

echo "searching image for QnnHtpV75Skel ..."
docker run --rm --platform linux/amd64 -u "$(id -u):$(id -g)" \
  --volume "$STAGE_DIR:/out" --entrypoint /bin/bash "$IMAGE" \
  -c 'skel="$(find / -name libQnnHtpV75Skel.so 2>/dev/null | head -n1)"; if [ -n "$skel" ]; then cp "$skel" /out/; echo "copied: $skel"; else echo "libQnnHtpV75Skel.so not present in image"; fi'

echo "== [6/6] verify + copy to jniLibs =="
fail=0
for f in "$STAGE_DIR"/*.so; do
  desc="$(file -b "$f")"
  case "$desc" in
    *"ELF 64-bit"*aarch64*) ;;
    *"QUALCOMM DSP6"*)
      case "$(basename "$f")" in
        libggml-htp-v*.so) ;;
        *) echo "BAD: $(basename "$f"): $desc"; fail=1 ;;
      esac
      ;;
    *) echo "BAD: $(basename "$f"): $desc"; fail=1 ;;
  esac
done

rp_server="$(readelf -d "$STAGE_DIR/libllama-server.so" | grep -E '\((RUNPATH|RPATH)\)' || true)"
echo "libllama-server.so rpath: $rp_server"
case "$rp_server" in *'$ORIGIN'*) ;; *) echo "WARNING: libllama-server.so has no \$ORIGIN rpath (app provides LD_LIBRARY_PATH)"; ;; esac
if [ -f "$STAGE_DIR/libllama-bench.so" ]; then
  rp_bench="$(readelf -d "$STAGE_DIR/libllama-bench.so" | grep -E '\((RUNPATH|RPATH)\)' || true)"
  echo "libllama-bench.so rpath:  $rp_bench"
  case "$rp_bench" in *'$ORIGIN'*) ;; *) echo "WARNING: libllama-bench.so has no \$ORIGIN rpath (app provides LD_LIBRARY_PATH)"; ;; esac
fi

if [ "$fail" -ne 0 ]; then
  echo "VERIFICATION FAILED - not copying anything to jniLibs"
  exit 1
fi

rm -rf "$JNI_DIR"
mkdir -p "$JNI_DIR"
cp -a "$STAGE_DIR"/. "$JNI_DIR"/

echo "artifacts in jniLibs:"
ls -la "$JNI_DIR"
du -sh "$JNI_DIR" "$ENGINE_DIR"
df -h / | tail -1
echo "DONE"
