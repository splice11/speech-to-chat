package com.stta.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.stta.STTAClient;
import com.stta.audio.Recorder;
import com.stta.config.STTAConfig;
import com.stta.engine.EngineManager;
import com.stta.engine.Platform;
import com.stta.model.Models;
import com.stta.speech.VoiceInput;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import org.jspecify.annotations.Nullable;

/**
 * First-run setup: explains what happens, recommends a model for this machine, downloads it
 * (with the small preview model when a GPU will do the main work), then starts the engine
 * once so the player sees it work before they need it.
 */
public class SetupScreen extends Screen {
	private enum State { CHOOSING, DOWNLOADING, STARTING, READY, FAILED }

	private final @Nullable Screen parent;
	private final String gpuName;
	private Models.Quality quality;
	private String microphone;
	private boolean useGpu;

	private volatile State state = State.CHOOSING;
	private volatile String status = "";
	private volatile float progress;
	private volatile boolean cancelled;

	public SetupScreen(@Nullable Screen parent) {
		super(Component.translatable("stta.setup.title"));
		this.parent = parent;
		this.gpuName = gpuName();
		STTAConfig config = STTAConfig.instance();
		this.quality = config.setupDone ? config.quality : recommend(gpuName);
		this.microphone = config.microphone == null ? "" : config.microphone;
		this.useGpu = config.useGpu;
	}

	@Override
	protected void init() {
		int cx = width / 2;
		int w = 300;
		int y = 34;
		MultiLineTextWidget intro = new MultiLineTextWidget(Component.translatable("stta.setup.intro"), font)
			.setMaxWidth(w).setCentered(true);
		intro.setPosition(cx - intro.getWidth() / 2, y);
		addRenderableWidget(intro);
		y += intro.getHeight() + 8;

		MultiLineTextWidget gpu = new MultiLineTextWidget(
			Component.translatable("stta.setup.gpu", gpuName).withStyle(ChatFormatting.GRAY), font).setMaxWidth(w).setCentered(true);
		gpu.setPosition(cx - gpu.getWidth() / 2, y);
		addRenderableWidget(gpu);
		y += gpu.getHeight() + 8;

		boolean editable = state == State.CHOOSING || state == State.FAILED || state == State.READY;
		Models.Quality recommended = recommend(gpuName);
		CycleButton<Models.Quality> qualityButton = CycleButton.<Models.Quality>builder(q -> qualityLabel(q, q == recommended), quality)
			.withValues(Models.Quality.values())
			.withTooltip(q -> Tooltip.create(Component.translatable("stta.quality." + q.name().toLowerCase(Locale.ROOT) + ".desc")))
			.create(cx - w / 2, y, w, 20, Component.translatable("stta.setup.quality"), (b, v) -> quality = v);
		qualityButton.active = editable;
		addRenderableWidget(qualityButton);
		y += 24;

		List<String> mics = new ArrayList<>();
		mics.add("");
		mics.addAll(Recorder.microphones());
		if (!mics.contains(microphone)) {
			microphone = "";
		}
		CycleButton<String> micButton = CycleButton.<String>builder(
				m -> m.isEmpty() ? Component.translatable("stta.setup.mic_default") : Component.literal(shorten(m, 34)), microphone)
			.withValues(mics)
			.create(cx - w / 2, y, w, 20, Component.translatable("stta.setup.mic"), (b, v) -> microphone = v);
		micButton.active = editable;
		addRenderableWidget(micButton);
		y += 24;

		CycleButton<Boolean> gpuButton = CycleButton.onOffBuilder(useGpu)
			.create(cx - w / 2, y, w, 20, Component.translatable("stta.setup.use_gpu"), (b, v) -> useGpu = v);
		gpuButton.active = editable;
		addRenderableWidget(gpuButton);
		y += 30;

		int bw = (w - 6) / 2;
		if (state == State.READY) {
			addRenderableWidget(Button.builder(Component.translatable("stta.setup.apply"), b -> start())
				.bounds(cx - w / 2, y, bw, 20).build());
			addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
				.bounds(cx - w / 2 + bw + 6, y, bw, 20).build());
		} else if (state == State.DOWNLOADING || state == State.STARTING) {
			addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> cancelled = true)
				.bounds(cx - bw / 2, y, bw, 20).build());
		} else {
			addRenderableWidget(Button.builder(Component.translatable("stta.setup.start"), b -> start())
				.tooltip(Tooltip.create(Component.translatable("stta.setup.download_size", sizeLabel())))
				.bounds(cx - w / 2, y, bw, 20).build());
			addRenderableWidget(Button.builder(Component.translatable("stta.setup.later"), b -> onClose())
				.bounds(cx - w / 2 + bw + 6, y, bw, 20).build());
		}
	}

	/** Downloads what's missing, then starts the engine once to prove it works on this machine. */
	private void start() {
		STTAConfig config = STTAConfig.instance();
		config.quality = quality;
		config.microphone = microphone;
		config.useGpu = useGpu;
		STTAConfig.save();
		cancelled = false;
		setState(State.DOWNLOADING);
		Thread t = new Thread(() -> {
			try {
				boolean english = config.englishOnly();
				List<Models.Model> needed = new ArrayList<>();
				needed.add(Models.forQuality(quality, english));
				if (useGpu && config.previewOnCpu) {
					needed.add(Models.previewModel(english));
				}
				long total = needed.stream().filter(m -> !m.installed()).mapToLong(m -> m.size).sum();
				long[] done = {0};
				for (Models.Model m : needed.stream().distinct().toList()) {
					if (m.installed()) {
						continue;
					}
					long before = done[0];
					status = Component.translatable("stta.setup.downloading", m.file).getString();
					Models.download(m, (have, size) -> {
						done[0] = before + have;
						progress = total == 0 ? 1 : (float) done[0] / total;
					}, () -> cancelled);
				}
				setState(State.STARTING);
				status = Component.translatable("stta.setup.starting").getString();
				EngineManager.get().restart();
				config.setupDone = true;
				STTAConfig.save();
				EngineManager.get().previewUrl();
				String device = EngineManager.get().mainDevice();
				status = Component.translatable("stta.setup.ready", device == null ? "?" : device,
					VoiceInput.talkKey.getTranslatedKeyMessage()).getString();
				setState(State.READY);
			} catch (InterruptedException e) {
				status = Component.translatable("stta.setup.cancelled").getString();
				setState(State.CHOOSING);
			} catch (Exception e) {
				STTAClient.LOGGER.error("Setup failed", e);
				status = Component.translatable("stta.setup.failed", String.valueOf(e.getMessage())).getString();
				setState(State.FAILED);
			}
		}, "stta-setup");
		t.setDaemon(true);
		t.start();
	}

	private void setState(State s) {
		state = s;
		minecraft.execute(() -> rebuildWidgets());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		g.centeredText(font, title, width / 2, 14, 0xFFFFFFFF);
		int y = height - 46;
		if (!status.isEmpty()) {
			int color = state == State.FAILED ? 0xFFFF6060 : state == State.READY ? 0xFF80FF80 : 0xFFE0E0E0;
			List<net.minecraft.util.FormattedCharSequence> lines = font.split(Component.literal(status), 320);
			int ly = y - (lines.size() - 1) * 10;
			for (var line : lines) {
				g.centeredText(font, line, width / 2, ly, color);
				ly += 10;
			}
		}
		if (state == State.DOWNLOADING) {
			int w = 300;
			int x0 = (width - w) / 2;
			int by = y + 14;
			g.fill(x0, by, x0 + w, by + 6, ARGB.color(160, 0, 0, 0));
			g.fill(x0, by, x0 + Math.round(w * progress), by + 6, 0xFF55CC55);
			g.centeredText(font, String.format(Locale.ROOT, "%d%%", Math.round(progress * 100)), width / 2, by + 9, 0xFFAAAAAA);
		}
	}

	public boolean isReady() {
		return state == State.READY;
	}

	public boolean hasFailed() {
		return state == State.FAILED;
	}

	public String statusText() {
		return status;
	}

	@Override
	public void onClose() {
		cancelled = true;
		minecraft.gui.setScreen(parent);
	}

	private String sizeLabel() {
		STTAConfig config = STTAConfig.instance();
		boolean english = config.englishOnly();
		long bytes = 0;
		Models.Model main = Models.forQuality(quality, english);
		if (!main.installed()) {
			bytes += main.size;
		}
		Models.Model preview = Models.previewModel(english);
		if (useGpu && config.previewOnCpu && preview != main && !preview.installed()) {
			bytes += preview.size;
		}
		return bytes == 0 ? Component.translatable("stta.setup.installed").getString() : (bytes / 1_000_000) + " MB";
	}

	private static Component qualityLabel(Models.Quality q, boolean recommended) {
		Component c = Component.translatable("stta.quality." + q.name().toLowerCase(Locale.ROOT));
		return recommended ? Component.empty().append(c).append(Component.literal(" ✔").withStyle(ChatFormatting.GREEN)) : c;
	}

	private static String shorten(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max - 1) + "…";
	}

	private static String gpuName() {
		try {
			return RenderSystem.getDevice().getDeviceInfo().name();
		} catch (Throwable t) {
			return "?";
		}
	}

	/** Best model for this machine: the big one with a real graphics card, smaller ones without. */
	static Models.Quality recommend(String gpu) {
		String g = gpu.toLowerCase(Locale.ROOT);
		if (Platform.isMac()) {
			return "aarch64".equals(System.getProperty("os.arch")) ? Models.Quality.ACCURATE : Models.Quality.BALANCED;
		}
		if (g.contains("llvmpipe") || g.contains("softpipe") || g.contains("swiftshader") || g.contains("basic render")) {
			return Models.Quality.LIGHT;
		}
		if (g.contains("geforce") || g.contains("rtx") || g.contains("quadro") || g.contains("radeon rx")
			|| g.contains("radeon pro") || g.contains(" arc") || g.matches(".*\\brx \\d{3,4}.*")) {
			return Models.Quality.ACCURATE;
		}
		return Models.Quality.BALANCED;
	}
}
