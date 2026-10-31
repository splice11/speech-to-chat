package com.stta.speech;

import com.mojang.blaze3d.platform.InputConstants;
import com.stta.STTAClient;
import com.stta.audio.Recorder;
import com.stta.config.STTAConfig;
import com.stta.engine.EngineManager;
import com.stta.engine.EngineProcess;
import com.stta.gui.SetupScreen;
import com.stta.gui.SpeechHud;
import com.stta.text.ChatText;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import javax.sound.sampled.LineUnavailableException;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.resources.Identifier;

/**
 * Hold the key to talk. While held, the audio so far is re-transcribed several times
 * a second for the live preview; on release the whole recording gets one careful pass
 * and is sent to chat. For the preview only, long speech is frozen in chunks of at most
 * {@link #CHUNK_SECONDS} so each update stays inside whisper's 30 s window and stays fast.
 * The final pass never uses those cuts: whisper drops or repeats words at a blind cut,
 * while its own timestamp seeking over the full recording does not.
 */
public class VoiceInput {
	private static final int SR = Recorder.SAMPLE_RATE;
	private static final int CHUNK_SECONDS = 24;
	/** Keep listening briefly after release so the last word is not clipped. */
	private static final long RELEASE_TAIL_MS = 250;
	private static final int MIN_PARTIAL_STEP = SR / 5;
	private static final int MIN_SPEECH = SR * 3 / 10;
	/** Live updates slower than this, three times running, switch the preview off for that message. */
	private static final long MAX_PREVIEW_MS = 2000;
	private static final java.util.concurrent.ScheduledExecutorService SENDER = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "stta-chat-sender");
		t.setDaemon(true);
		return t;
	});
	/** Loudest 50 ms frame must exceed this RMS, otherwise it was silence and whisper would hallucinate. */
	private static final float SILENCE_RMS = 0.004f;

	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("stta", "listening"));
	public static KeyMapping talkKey;

	private final Recorder recorder = new Recorder();
	private final WhisperServer server = new WhisperServer();
	private final SpeechHud hud;
	private final ExecutorService previews = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "stta-preview");
		t.setDaemon(true);
		return t;
	});
	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "stta-worker");
		t.setDaemon(true);
		return t;
	});

	private volatile boolean busy;
	private boolean warnedSlow;
	private volatile long releasedAt;
	private long startedAt;
	private boolean wasDown;

	public VoiceInput(SpeechHud hud) {
		this.hud = hud;
	}

	public void register() {
		talkKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.stta.hold", InputConstants.Type.KEYBOARD, InputConstants.KEY_V, CATEGORY));
		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> server.warmUp());
	}

	private void tick(Minecraft client) {
		boolean down = talkKey.isDown() && client.player != null;
		if (down && !wasDown && client.gui.screen() == null && !busy) {
			if (!STTAConfig.instance().setupDone) {
				client.gui.setScreen(new SetupScreen(null));
			} else {
				start();
			}
		} else if (!down && wasDown && busy && releasedAt == 0) {
			releasedAt = System.currentTimeMillis();
		}
		if (busy && releasedAt == 0 && System.currentTimeMillis() - startedAt > STTAConfig.instance().maxSeconds * 1000L) {
			releasedAt = System.currentTimeMillis();
		}
		wasDown = down;
	}

	private void start() {
		busy = true;
		releasedAt = 0;
		startedAt = System.currentTimeMillis();
		hud.listening(recorder::level);
		server.warmUp();
		worker.submit(() -> {
			try {
				session();
			} catch (LineUnavailableException e) {
				STTAClient.LOGGER.error("No microphone", e);
				hud.error("msg.stta.no_mic");
			} catch (EngineManager.NotSetUpException e) {
				hud.clear();
				Minecraft mc = Minecraft.getInstance();
				mc.execute(() -> mc.gui.setScreen(new SetupScreen(null)));
			} catch (EngineProcess.EngineException e) {
				STTAClient.LOGGER.error("Speech engine failed to start", e);
				hud.error("msg.stta.engine_failed");
			} catch (ConnectException | HttpConnectTimeoutException e) {
				STTAClient.LOGGER.error("Speech engine unreachable", e);
				hud.error("msg.stta.no_server");
			} catch (HttpTimeoutException e) {
				STTAClient.LOGGER.error("Speech server timed out", e);
				hud.error("msg.stta.timeout");
			} catch (Throwable t) {
				STTAClient.LOGGER.error("Speech recognition failed", t);
				hud.error("msg.stta.failed");
			} finally {
				recorder.stop();
				busy = false;
			}
		});
	}

	private void session() throws Exception {
		STTAConfig config = STTAConfig.instance();
		recorder.start();

		StringBuilder committed = new StringBuilder(); // preview only
		int commitStart = 0;
		int lastPartialEnd = 0;
		// Previews run on their own thread so letting go of the key never waits for one: the final
		// pass starts at once (preview and final usually run on different engines, CPU and GPU).
		// Only one preview is in flight at a time, which also paces them to what this machine
		// manages; on a machine too slow to keep up, the preview switches off for this message.
		boolean preview = config.livePreview;
		int slow = 0;
		Future<String> pending = null;
		long pendingStart = 0;
		int pendingCut = -1; // >= 0: the pending job freezes the preview up to this sample
		while (true) {
			long rel = releasedAt;
			if (rel != 0 && System.currentTimeMillis() - rel >= RELEASE_TAIL_MS) {
				break;
			}
			if (!recorder.isRunning()) {
				throw new LineUnavailableException("Microphone stopped delivering audio");
			}
			if (pending != null && pending.isDone()) {
				String text = result(pending);
				long ms = (System.nanoTime() - pendingStart) / 1_000_000;
				pending = null;
				if (pendingCut >= 0) {
					append(committed, text);
					commitStart = pendingCut;
					pendingCut = -1;
					hud.preview(committed.toString(), "");
				} else {
					hud.preview(committed.toString(), text);
					slow = ms > MAX_PREVIEW_MS ? slow + 1 : 0;
					if (slow >= 3) {
						STTAClient.LOGGER.info("Live preview too slow on this machine ({} ms per update), pausing it for this message", ms);
						preview = false;
						hud.preview(committed.toString(), "…");
					}
				}
			}
			int end = recorder.length();
			if (pending == null && end - commitStart > CHUNK_SECONDS * SR) {
				int cut = quietestPoint(commitStart, end);
				float[] audio = recorder.copy(commitStart, cut);
				pendingCut = cut;
				pendingStart = System.nanoTime();
				pending = previews.submit(() -> ChatText.trimLoops(ChatText.clean(server.transcribe(audio, "", true)), 3));
			} else if (pending == null && preview && end - lastPartialEnd >= MIN_PARTIAL_STEP && end - commitStart >= MIN_SPEECH) {
				float[] audio = recorder.copy(commitStart, end);
				lastPartialEnd = end;
				pendingStart = System.nanoTime();
				pending = previews.submit(() -> loud(audio) ? ChatText.trimLoops(ChatText.clean(server.transcribe(audio, "", true)), 3) : "");
			} else {
				Thread.sleep(20);
			}
		}
		if (pending != null) {
			pending.cancel(true);
		}
		recorder.stop();
		hud.finishing();

		float[] all = recorder.copy(0, recorder.length());
		long t0 = System.currentTimeMillis();
		String text = all.length >= MIN_SPEECH && loud(all) ? ChatText.trimLoops(ChatText.clean(server.transcribe(all, prompt(), false)), 4) : "";
		warnIfSlow(System.currentTimeMillis() - t0, all.length * 1000L / SR);
		if (text.isEmpty()) {
			hud.blank();
			return;
		}
		deliver(text);
	}

	private void deliver(String text) {
		STTAConfig config = STTAConfig.instance();
		Minecraft mc = Minecraft.getInstance();
		if (config.inputMode == STTAConfig.ChatInputMode.Draft) {
			hud.clear();
			mc.execute(() -> mc.gui.setScreen(new ChatScreen(text, false)));
			return;
		}
		String prefix = config.chatPrefix == null ? "" : config.chatPrefix;
		hud.sent(text);
		// long speech becomes several messages; space them out so servers don't kick for spam
		java.util.List<String> parts = ChatText.split(text, ChatText.MAX_MESSAGE - prefix.length());
		for (int i = 0; i < parts.size(); i++) {
			String part = prefix + parts.get(i);
			Runnable send = () -> mc.execute(() -> {
				if (mc.player != null) {
					mc.player.connection.sendChat(part);
				}
			});
			if (i == 0) {
				send.run();
			} else {
				SENDER.schedule(send, (long) i * Math.max(0, config.messageGapMs), java.util.concurrent.TimeUnit.MILLISECONDS);
			}
		}
	}

	/**
	 * The vocabulary hint, for the final pass only. Live partials get no prompt: on cut-off audio
	 * whisper copies hint sentences. Player names are deliberately left out: with a name in the
	 * prompt, large-v3-turbo answered a clear 5 s sentence with just "Player0." five times.
	 */
	private static String prompt() {
		return STTAConfig.instance().prompt;
	}

	/**
	 * Once per session, tell the player (in their own chat only) when recognition is far slower
	 * than it should be, e.g. a weak GPU or an overloaded CPU, and where to fix it.
	 */
	private void warnIfSlow(long ms, long audioMs) {
		if (warnedSlow || ms < Math.max(8000, 2 * audioMs)) {
			return;
		}
		warnedSlow = true;
		STTAClient.LOGGER.warn("Final transcription took {} ms for {} ms of speech", ms, audioMs);
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.player != null) {
				mc.player.sendSystemMessage(Component.translatable("msg.stta.slow", ms / 1000).withStyle(ChatFormatting.GOLD));
			}
		});
	}

	/** A finished preview job's text; a failure there is reported like any other. */
	private static String result(Future<String> f) throws Exception {
		try {
			return f.get();
		} catch (ExecutionException e) {
			throw e.getCause() instanceof Exception ex ? ex : e;
		}
	}

	private static void append(StringBuilder sb, String text) {
		if (!text.isEmpty()) {
			if (sb.length() > 0) {
				sb.append(' ');
			}
			sb.append(text);
		}
	}

	/** The quietest 100 ms in the last 5 s of the chunk, so a chunk cut lands between words. */
	private int quietestPoint(int from, int end) {
		int frame = SR / 10;
		int searchFrom = Math.max(from + SR, end - 5 * SR);
		float[] a = recorder.copy(searchFrom, end - frame);
		int best = a.length / 2;
		double bestEnergy = Double.MAX_VALUE;
		for (int i = 0; i + frame <= a.length; i += frame / 2) {
			double e = 0;
			for (int k = i; k < i + frame; k++) {
				e += a[k] * a[k];
			}
			if (e < bestEnergy) {
				bestEnergy = e;
				best = i + frame / 2;
			}
		}
		return searchFrom + best;
	}

	private static boolean loud(float[] audio) {
		int frame = SR / 20;
		for (int i = 0; i + frame <= audio.length; i += frame) {
			double e = 0;
			for (int k = i; k < i + frame; k++) {
				e += audio[k] * audio[k];
			}
			if (Math.sqrt(e / frame) > SILENCE_RMS) {
				return true;
			}
		}
		return false;
	}
}
