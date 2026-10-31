package com.stta.engine;

import com.stta.STTAClient;
import com.stta.config.STTAConfig;
import com.stta.model.Models;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Runs the bundled whisper.cpp engines: a main one (GPU when available) for the text that
 * gets sent, and with a GPU also a small CPU one for the live preview. Engines start on
 * demand, fall back from GPU to CPU if the GPU build can't start, and stop when idle.
 */
public final class EngineManager {
	private static final EngineManager INSTANCE = new EngineManager();
	private static final Duration START_TIMEOUT = Duration.ofSeconds(120);

	private final ScheduledExecutorService janitor = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "stta-engine-idle");
		t.setDaemon(true);
		return t;
	});

	private volatile EngineProcess main;
	private volatile EngineProcess preview;
	private volatile String device;
	/** The GPU build failed to start this session; stay on the CPU build. */
	private boolean gpuFailed;
	private volatile long lastUse;
	private volatile String status = "";

	private EngineManager() {
		janitor.scheduleAtFixedRate(this::stopIfIdle, 1, 1, TimeUnit.MINUTES);
	}

	public static EngineManager get() {
		return INSTANCE;
	}

	/** Base URL for the final pass; starts the engine if needed. */
	public synchronized String mainUrl() throws IOException, InterruptedException {
		lastUse = System.currentTimeMillis();
		String external = STTAConfig.instance().externalServerUrl;
		if (external != null && !external.isBlank()) {
			return trimSlash(external.trim());
		}
		if (main == null || !main.isRunning()) {
			main = startMain();
			device = main.device();
		}
		return main.ensureRunning(START_TIMEOUT);
	}

	/** Base URL for live partials: the CPU preview engine when the main one is on the GPU, else the main one. */
	public synchronized String previewUrl() throws IOException, InterruptedException {
		STTAConfig config = STTAConfig.instance();
		String mainUrl = mainUrl();
		boolean external = config.externalServerUrl != null && !config.externalServerUrl.isBlank();
		Models.Model model = Models.previewModel(config.englishOnly());
		if (external || !config.previewOnCpu || main == null || !main.onGpu() || !model.installed()) {
			return mainUrl;
		}
		try {
			if (preview == null || !preview.isRunning()) {
				preview = new EngineProcess("preview", cpuExe(), args(model, false, previewThreads()), logFile("preview"));
			}
			return preview.ensureRunning(Duration.ofSeconds(30));
		} catch (IOException e) {
			STTAClient.LOGGER.warn("Preview engine unavailable, using the main engine for previews", e);
			preview = null;
			return mainUrl;
		}
	}

	private EngineProcess startMain() throws IOException, InterruptedException {
		STTAConfig config = STTAConfig.instance();
		Models.Model model = Models.forQuality(config.quality, config.englishOnly());
		if (!model.installed()) {
			throw new NotSetUpException();
		}
		if (config.useGpu && !gpuFailed) {
			EngineProcess gpu = new EngineProcess("main", install(Platform.gpuVariant()), args(model, true, gpuThreads()), logFile("main"));
			try {
				status = "Loading speech model…";
				gpu.ensureRunning(START_TIMEOUT);
				status = "";
				return gpu;
			} catch (IOException e) {
				// no Vulkan loader, broken driver, out of VRAM, ...: the CPU build always works
				STTAClient.LOGGER.warn("GPU speech engine failed, falling back to CPU", e);
				gpu.stop();
				gpuFailed = true;
			}
		}
		status = "Loading speech model…";
		EngineProcess cpu = new EngineProcess("main", cpuExe(), args(model, false, cpuThreads()), logFile("main"));
		cpu.ensureRunning(START_TIMEOUT);
		status = "";
		return cpu;
	}

	private static List<String> args(Models.Model model, boolean gpu, int threads) throws IOException {
		STTAConfig config = STTAConfig.instance();
		List<String> a = new ArrayList<>(List.of(
			"-m", model.path().toString(),
			"-l", config.effectiveLanguage(),
			"-t", Integer.toString(threads),
			"--vad", "-vm", Models.vadModel().toString(), "-vp", "200",
			"-sns", "-nt"));
		if (!gpu) {
			a.add("-ng");
		}
		return a;
	}

	// ggml's thread pool busy-waits between steps: more engine threads than free cores makes them
	// starve each other (4 threads on a 2-core Windows machine never finished a 1 s job). Leave
	// half the cores to the game and never ask for more than exist.
	private static int cores() {
		return Math.max(1, Runtime.getRuntime().availableProcessors());
	}

	private static int cpuThreads() {
		return Math.max(1, Math.min(8, cores() / 2));
	}

	/** With the GPU doing the work, the CPU threads only feed it. */
	private static int gpuThreads() {
		return Math.max(1, Math.min(4, cores() / 2));
	}

	private static int previewThreads() {
		return Math.max(1, Math.min(4, cores() / 4));
	}

	/** CPU engine: its own small build on Windows/Linux; on macOS the Metal build run with -ng. */
	private static Path cpuExe() throws IOException {
		return install(Platform.isMac() ? "metal" : "cpu");
	}

	/** Extracts the bundled engine binary for this platform (once per mod version). */
	private static Path install(String variant) throws IOException {
		String platform = Platform.id();
		if (platform == null) {
			throw new EngineProcess.EngineException("No speech engine is bundled for "
				+ System.getProperty("os.name") + " " + System.getProperty("os.arch"));
		}
		String version = FabricLoader.getInstance().getModContainer("speech-to-chat")
			.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");
		Path target = STTAClient.dataDir().resolve("engine").resolve(version).resolve(platform).resolve(variant).resolve(Platform.exeName());
		if (Files.exists(target)) {
			return target;
		}
		String resource = "/natives/" + platform + "/" + variant + "/" + Platform.exeName();
		try (InputStream in = EngineManager.class.getResourceAsStream(resource)) {
			if (in == null) {
				throw new EngineProcess.EngineException("Speech engine " + resource + " is missing from the mod jar");
			}
			Files.createDirectories(target.getParent());
			Path tmp = Files.createTempFile(target.getParent(), "engine", ".part");
			Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
			tmp.toFile().setExecutable(true, true);
			Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
		}
		return target;
	}

	private static Path logFile(String name) {
		return STTAClient.dataDir().resolve("logs").resolve("engine-" + name + ".log");
	}

	private static String trimSlash(String url) {
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}

	/** Starts the engines in the background so the first key press doesn't wait for a model load. */
	public void warmUpAsync() {
		if (!STTAConfig.instance().setupDone) {
			return;
		}
		Thread t = new Thread(() -> {
			try {
				previewUrl();
			} catch (Exception e) {
				STTAClient.LOGGER.warn("Speech engine warm-up failed", e);
			}
		}, "stta-engine-warmup");
		t.setDaemon(true);
		t.start();
	}

	/** Device the main engine runs on ("AMD Radeon RX 6700", "CPU", ...), or null when not running. Never blocks. */
	public String mainDevice() {
		EngineProcess m = main;
		return m != null && m.isRunning() ? device : null;
	}

	public boolean mainOnGpu() {
		EngineProcess m = main;
		return m != null && m.isRunning() && m.onGpu();
	}

	/** URL of the running CPU preview engine, or null when previews share the main engine. */
	public String previewEngineUrl() {
		EngineProcess p = preview;
		return p != null && p.isRunning() ? p.url() : null;
	}

	public String status() {
		return status;
	}

	/** Applies changed settings (model, language, GPU) by restarting on next use. */
	public synchronized void restart() {
		stopAll();
		gpuFailed = false;
	}

	/** Not synchronized: game shutdown must never wait behind an engine that is still loading. */
	public void stopAll() {
		EngineProcess m = main;
		EngineProcess p = preview;
		main = null;
		preview = null;
		if (m != null) {
			m.stop();
		}
		if (p != null) {
			p.stop();
		}
	}

	private synchronized void stopIfIdle() {
		int minutes = STTAConfig.instance().idleMinutes;
		if (minutes > 0 && main != null && System.currentTimeMillis() - lastUse > minutes * 60_000L) {
			STTAClient.LOGGER.info("Speech engine idle for {} min, stopping it to free memory", minutes);
			stopAll();
		}
	}

	/** No model installed yet: the setup screen has to run first. */
	public static class NotSetUpException extends IOException {
		public NotSetUpException() {
			super("Speech model not installed");
		}
	}
}
