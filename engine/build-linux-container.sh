#!/usr/bin/env bash
# Builds the Linux engines inside Ubuntu 22.04 (glibc 2.35), the same base as CI, so the binary
# runs on older distros and inside Flatpak runtimes. Ubuntu 22.04 has no glslc and old Vulkan
# headers, so the shader compiler and headers come from LunarG's Vulkan SDK (cached in engine/.cache).
# Usage: engine/build-linux-container.sh [variants...]   (default: cpu vulkan)
set -euo pipefail
cd "$(dirname "$0")/.."
VARIANTS="${*:-cpu vulkan}"
SDK_VERSION=1.4.363.0
SDK_TAR="engine/.cache/vulkansdk-linux-$SDK_VERSION.tar.xz"
if [[ ! -f "$SDK_TAR" ]]; then
	mkdir -p engine/.cache
	curl -fL -o "$SDK_TAR.part" "https://sdk.lunarg.com/sdk/download/$SDK_VERSION/linux/vulkansdk-linux-x86_64-$SDK_VERSION.tar.xz"
	mv "$SDK_TAR.part" "$SDK_TAR"
fi

podman run --rm -v "$PWD:/src:Z" -w /src docker.io/library/ubuntu:22.04 bash -c "
	set -e
	export DEBIAN_FRONTEND=noninteractive
	apt-get update -qq
	apt-get install -y -qq build-essential cmake ninja-build git xz-utils ca-certificates >/dev/null
	mkdir -p /opt/vulkan && tar -xJf $SDK_TAR -C /opt/vulkan
	export VULKAN_SDK=/opt/vulkan/$SDK_VERSION/x86_64
	export PATH=\$VULKAN_SDK/bin:\$PATH
	export Vulkan_LIBRARY=\$VULKAN_SDK/lib/VulkanLoader/lib/libvulkan.so
	git config --global --add safe.directory '*'
	for v in $VARIANTS; do
		if [ \$v = vulkan ]; then
			engine/build.sh \$v -DVulkan_LIBRARY=\$Vulkan_LIBRARY -DVulkan_INCLUDE_DIR=\$VULKAN_SDK/include
		else
			engine/build.sh \$v
		fi
	done
"
