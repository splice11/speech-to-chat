package com.stta.engine;

import java.util.Locale;

/** Which bundled engine build fits this machine. */
public final class Platform {
	private Platform() {
	}

	/** e.g. linux-x64, windows-x64, macos-arm64; null when no engine is bundled for this machine. */
	public static String id() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
		String a = switch (arch) {
			case "amd64", "x86_64" -> "x64";
			case "aarch64", "arm64" -> "arm64";
			default -> null;
		};
		if (a == null) {
			return null;
		}
		if (os.startsWith("windows")) {
			return a.equals("x64") ? "windows-x64" : null;
		}
		if (os.startsWith("mac")) {
			return "macos-" + a;
		}
		if (os.startsWith("linux")) {
			return a.equals("x64") ? "linux-x64" : null;
		}
		return null;
	}

	public static boolean isMac() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac");
	}

	public static String exeName() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows") ? "whisper-server.exe" : "whisper-server";
	}

	/** GPU build for this platform: Metal on macOS, Vulkan elsewhere (any vendor). */
	public static String gpuVariant() {
		return isMac() ? "metal" : "vulkan";
	}
}
