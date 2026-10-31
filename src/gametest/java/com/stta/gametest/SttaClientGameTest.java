package com.stta.gametest;

import com.stta.STTAClient;
import com.stta.engine.EngineManager;
import com.stta.gui.SetupScreen;
import com.stta.speech.VoiceInput;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;

/**
 * First-run flow end to end: the setup screen opens on the title screen, downloads the model
 * (or takes it from STTA_MODEL_CACHE), starts the bundled engine; then the talk key is held
 * while STTA_WAV plays in place of the microphone, and the chat the server received is checked.
 * Run: STTA_WAV=/path/16k-mono.wav [STTA_MODEL_CACHE=dir] ./gradlew runClientGameTest
 */
public class SttaClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		String wav = System.getenv("STTA_WAV");
		double seconds = Double.parseDouble(System.getenv().getOrDefault("STTA_HOLD", "8"));
		long shotMs = Long.parseLong(System.getenv().getOrDefault("STTA_SHOT_MS", "2000"));
		System.setProperty("stta.debugWav", wav);
		if (System.getenv("STTA_MAX_RTF") != null) {
			System.setProperty("stta.debugMaxRtf", System.getenv("STTA_MAX_RTF"));
		}
		if (System.getenv("STTA_DUMP") != null) {
			System.setProperty("stta.debugDumpDir", System.getenv("STTA_DUMP"));
		}
		List<String> chat = new CopyOnWriteArrayList<>();
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, type) -> chat.add(message.signedContent()));

		String cache = System.getenv("STTA_MODEL_CACHE");
		if (cache != null) {
			try (var files = Files.list(Path.of(cache))) {
				Path models = STTAClient.dataDir().resolve("models");
				Files.createDirectories(models);
				for (Path f : files.filter(f -> f.toString().endsWith(".bin")).toList()) {
					Files.copy(f, models.resolve(f.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				}
			} catch (java.io.IOException e) {
				throw new RuntimeException(e);
			}
		}

		// first launch: the setup screen should have opened over the title screen
		ctx.waitTicks(20);
		boolean offered = ctx.computeOnClient(mc -> mc.gui.screen() instanceof SetupScreen);
		System.out.println("STTA-TEST setup offered on first launch: " + offered);
		if (!offered) {
			ctx.runOnClient(mc -> mc.gui.setScreen(new SetupScreen(mc.gui.screen())));
		}
		ctx.waitTicks(5);
		ctx.takeScreenshot("setup_1_choose");
		long t0 = System.currentTimeMillis();
		ctx.clickScreenButton("stta.setup.start");
		long nextShot = 3000;
		while (true) {
			ctx.waitTick();
			SetupScreen s = ctx.computeOnClient(mc -> mc.gui.screen() instanceof SetupScreen ss ? ss : null);
			if (s == null || s.isReady() || s.hasFailed()) {
				System.out.println("STTA-TEST setup: " + (s == null ? "screen gone" : s.statusText())
					+ " after " + (System.currentTimeMillis() - t0) + " ms");
				if (s == null || s.hasFailed()) {
					ctx.takeScreenshot("setup_failed");
					throw new AssertionError("setup did not finish");
				}
				break;
			}
			if (System.currentTimeMillis() - t0 > nextShot) {
				ctx.takeScreenshot(String.format("setup_2_progress_%05d", System.currentTimeMillis() - t0));
				nextShot += 15000;
			}
			if (System.currentTimeMillis() - t0 > 900_000) {
				throw new AssertionError("setup timed out");
			}
		}
		ctx.takeScreenshot("setup_3_ready");
		System.out.println("STTA-TEST engine device: " + EngineManager.get().mainDevice());
		ctx.clickScreenButton("gui.done");

		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getServer().runCommand("time set noon");
			ctx.waitTicks(40);
			ctx.getInput().holdKey(VoiceInput.talkKey);
			long start = System.currentTimeMillis();
			long next = shotMs;
			while (System.currentTimeMillis() - start < seconds * 1000) {
				ctx.waitTick();
				long el = System.currentTimeMillis() - start;
				if (el >= next) {
					ctx.takeScreenshot(String.format("stta_live_%05d", el));
					next += shotMs;
				}
			}
			ctx.getInput().releaseKey(VoiceInput.talkKey);
			long released = System.currentTimeMillis();
			while (chat.isEmpty() && System.currentTimeMillis() - released < 30000) {
				ctx.waitTick();
			}
			System.out.println("STTA-TEST release->chat ms: " + (System.currentTimeMillis() - released));
			ctx.waitTicks(60);
			ctx.takeScreenshot("stta_sent");
			chat.forEach(m -> System.out.println("STTA-TEST chat: [" + m.length() + "] " + m));
			if (chat.isEmpty()) {
				throw new AssertionError("No chat message was sent");
			}
		}
		long engines = ProcessHandle.current().descendants().filter(p -> p.info().command().orElse("").endsWith("whisper-server")).count();
		System.out.println("STTA-TEST engine processes still running before shutdown: " + engines);
	}
}
