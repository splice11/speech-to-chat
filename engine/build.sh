#!/usr/bin/env bash
# Builds the bundled speech engine (whisper.cpp's whisper-server) for this machine's platform.
#   engine/build.sh <variant> [extra cmake args...]      variant: vulkan | cpu | metal
# Output: engine/out/<platform>/<variant>/whisper-server
# Portable on purpose: no -march=native, static ggml/libstdc++, no OpenMP runtime to depend on.
set -euo pipefail
cd "$(dirname "$0")"
VARIANT="${1:?usage: build.sh vulkan|cpu|metal [cmake args]}"
shift
EXTRA=("$@")

case "$(uname -s)-$(uname -m)" in
	Linux-x86_64) PLATFORM=linux-x64 ;;
	Linux-aarch64) PLATFORM=linux-arm64 ;;
	Darwin-arm64) PLATFORM=macos-arm64 ;;
	Darwin-x86_64) PLATFORM=macos-x64 ;;
	*) echo "unsupported platform $(uname -s)-$(uname -m)" >&2; exit 1 ;;
esac

# our patches on top of the pinned upstream commit (idempotent)
for p in patches/*.patch; do
	if git -C whisper.cpp apply --check "../$p" 2>/dev/null; then
		git -C whisper.cpp apply "../$p"
	fi
done

ARGS=(
	-DCMAKE_BUILD_TYPE=Release
	-DBUILD_SHARED_LIBS=OFF
	-DGGML_NATIVE=OFF
	-DGGML_OPENMP=OFF
	-DWHISPER_SDL2=OFF
	-DWHISPER_BUILD_TESTS=OFF
	-DWHISPER_BUILD_EXAMPLES=ON
)
case "$VARIANT" in
	vulkan)
		ARGS+=(-DGGML_VULKAN=ON)
		;;
	metal) ARGS+=(-DGGML_METAL=ON -DGGML_METAL_EMBED_LIBRARY=ON) ;;
	cpu) ARGS+=(-DGGML_METAL=OFF) ;;
	*) echo "unknown variant $VARIANT" >&2; exit 1 ;;
esac
if [[ "$PLATFORM" == linux-* ]]; then
	ARGS+=("-DCMAKE_EXE_LINKER_FLAGS=-static-libgcc -static-libstdc++")
fi

BUILD="build/$PLATFORM-$VARIANT"
cmake -S whisper.cpp -B "$BUILD" -G Ninja "${ARGS[@]}" "${EXTRA[@]}"
cmake --build "$BUILD" --target whisper-server -j "$(getconf _NPROCESSORS_ONLN)"

OUT="out/$PLATFORM/$VARIANT"
mkdir -p "$OUT"
cp "$BUILD/bin/whisper-server" "$OUT/"
strip "$OUT/whisper-server" 2>/dev/null || true
ls -l "$OUT/whisper-server"
