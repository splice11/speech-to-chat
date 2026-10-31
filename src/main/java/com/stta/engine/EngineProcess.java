package com.stta.engine;

import com.stta.STTAClient;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One whisper-server child process. Recognition runs out of the game's process so a
 * crash or out-of-memory in the engine can never take Minecraft down. The engine is
 * started with {@code --exit-on-stdin-close} and we hold its stdin open: if the game
 * dies, even by SIGKILL, the pipe closes and the engine exits and frees its VRAM.
 */
public class EngineProcess {
	private static final Pattern VULKAN_DEVICE = Pattern.compile("ggml_vulkan: 0 = (.+?) \\|");
	private static final Pattern METAL_DEVICE = Pattern.compile("ggml_metal_init: picking default device: (.+)");
	private static final Pattern GPU_IN_USE = Pattern.compile("whisper_backend_init_gpu: using ");

	private final String name;
	private final Path exe;
	private final List<String> args;
	private final Path log;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

	private volatile Process process;
	private volatile OutputStream stdin;
	private int port;

	public EngineProcess(String name, Path exe, List<String> args, Path log) {
		this.name = name;
		this.exe = exe;
		this.args = args;
		this.log = log;
	}

	/** Starts the engine if needed and waits until its model is loaded. Returns the base URL. */
	public synchronized String ensureRunning(Duration timeout) throws IOException, InterruptedException {
		if (process != null && process.isAlive()) {
			return url();
		}
		port = freePort();
		List<String> cmd = new ArrayList<>();
		cmd.add(exe.toString());
		cmd.addAll(List.of("--host", "127.0.0.1", "--port", Integer.toString(port), "--exit-on-stdin-close"));
		cmd.addAll(args);
		Files.createDirectories(log.getParent());
		ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile());
		// Gamers often have overlay/capture Vulkan layers enabled globally (MangoHud, OBS vkcapture,
		// vkBasalt, Steam overlay). Inside a windowless compute process they are useless and can
		// crash it: MangoHud segfaults the engine at startup. Turn implicit layers off for the engine.
		var env = pb.environment();
		env.put("VK_LOADER_LAYERS_DISABLE", "~implicit~");
		env.put("DISABLE_MANGOHUD", "1");
		env.put("MANGOHUD", "0");
		env.put("OBS_VKCAPTURE", "0");
		env.put("ENABLE_VKBASALT", "0");
		env.put("DISABLE_VKBASALT", "1");
		STTAClient.LOGGER.info("Starting {} engine: {}", name, String.join(" ", cmd));
		process = pb.start();
		stdin = process.getOutputStream();

		long deadline = System.nanoTime() + timeout.toNanos();
		while (System.nanoTime() < deadline) {
			if (!process.isAlive()) {
				throw new EngineException(name + " engine exited with code " + process.exitValue() + ": " + tail());
			}
			if (healthy()) {
				STTAClient.LOGGER.info("{} engine ready on port {} ({})", name, port, device());
				return url();
			}
			Thread.sleep(100);
		}
		stop();
		throw new EngineException(name + " engine did not become ready within " + timeout.toSeconds() + " s: " + tail());
	}

	private boolean healthy() {
		try {
			HttpRequest req = HttpRequest.newBuilder(URI.create(url() + "/health")).timeout(Duration.ofSeconds(2)).GET().build();
			return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
		} catch (IOException e) {
			return false;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	public boolean isRunning() {
		Process p = process;
		return p != null && p.isAlive();
	}

	public void stop() {
		Process process = this.process;
		OutputStream stdin = this.stdin;
		this.process = null;
		if (process == null) {
			return;
		}
		try {
			stdin.close();
		} catch (IOException ignored) {
		}
		process.destroy();
		try {
			if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
				process.destroyForcibly();
			}
		} catch (InterruptedException e) {
			process.destroyForcibly();
			Thread.currentThread().interrupt();
		}
	}

	public String url() {
		return "http://127.0.0.1:" + port;
	}

	/** What the engine is running on, read from its startup log: a GPU name or "CPU". */
	public String device() {
		String text = readLog();
		if (!GPU_IN_USE.matcher(text).find()) {
			return "CPU";
		}
		Matcher m = VULKAN_DEVICE.matcher(text);
		if (m.find()) {
			return m.group(1).trim();
		}
		m = METAL_DEVICE.matcher(text);
		if (m.find()) {
			return m.group(1).trim();
		}
		return "CPU";
	}

	public boolean onGpu() {
		return GPU_IN_USE.matcher(readLog()).find();
	}

	private String readLog() {
		try {
			return Files.readString(log);
		} catch (IOException e) {
			return "";
		}
	}

	private String tail() {
		String text = readLog().strip();
		return text.length() > 600 ? "…" + text.substring(text.length() - 600) : text;
	}

	private static int freePort() throws IOException {
		try (ServerSocket s = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
			return s.getLocalPort();
		}
	}

	public static class EngineException extends IOException {
		public EngineException(String message) {
			super(message);
		}
	}
}
