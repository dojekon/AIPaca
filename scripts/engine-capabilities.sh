#!/usr/bin/env bash
set -euo pipefail

LLAMA_DIR="${1:?usage: engine-capabilities.sh <llama.cpp dir> <output json>}"
OUT="${2:?usage: engine-capabilities.sh <llama.cpp dir> <output json>}"

ARCH_SRC="$LLAMA_DIR/src/llama-arch.cpp"
HEX_DIR="$LLAMA_DIR/ggml/src/ggml-hexagon"

archs="$(grep -oE '\{ *LLM_ARCH_[A-Z0-9_]+, *"[^"]+" *\}' "$ARCH_SRC" \
  | sed -E 's/.*"([^"]+)".*/\1/' | grep -v '^(unknown)$' | sort -u)"
if [ -z "$archs" ]; then
  echo "ERROR: no architectures found in $ARCH_SRC" >&2
  exit 1
fi

npu_types=""
if [ -d "$HEX_DIR" ]; then
  npu_types="$(awk '
    /^static bool (ggml_hexagon_supported_mul_mat|htp_matmul_check_types)\(/ { inside = 1 }
    inside && /^}/ { inside = 0 }
    inside { while (match($0, /case GGML_TYPE_[A-Z0-9_]+:/)) { print substr($0, RSTART + 15, RLENGTH - 16); $0 = substr($0, RSTART + RLENGTH) } }
  ' "$HEX_DIR"/*.cpp | sort -u)"
  if [ -z "$npu_types" ]; then
    echo "ERROR: Hexagon matmul weight types not found in $HEX_DIR (did upstream rename the check?)" >&2
    exit 1
  fi
fi

revision="$(git -C "$LLAMA_DIR" rev-parse HEAD 2>/dev/null || echo unknown)"

to_json_array() {
  if [ -z "$1" ]; then printf '[]'; return; fi
  printf '%s\n' "$1" | awk 'BEGIN { printf "[" } { printf "%s\"%s\"", (NR > 1 ? ", " : ""), $0 } END { printf "]" }'
}

mkdir -p "$(dirname "$OUT")"
{
  printf '{\n'
  printf '  "revision": "%s",\n' "$revision"
  printf '  "architectures": %s,\n' "$(to_json_array "$archs")"
  printf '  "npuWeightTypes": %s\n' "$(to_json_array "$npu_types")"
  printf '}\n'
} > "$OUT"

echo "capabilities: $(printf '%s\n' "$archs" | wc -l) architectures, npu types: $(printf '%s' "$npu_types" | tr '\n' ' ')"
