package com.stta.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.stta.STTAClient;
import com.stta.model.Models;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

public class STTAConfig {
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("speech-to-chat.json");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static STTAConfig instance;

	public enum ChatInputMode { Direct, Draft }

	/** Set once the first-run setup has installed a model. */
	public boolean setupDone = false;
	public Models.Quality quality = Models.Quality.ACCURATE;
	/** Use the GPU (Vulkan / Metal) when there is one. */
	public boolean useGpu = true;
	/** With a GPU, run the live preview on a small CPU model so it doesn't compete with the game for the GPU. */
	public boolean previewOnCpu = true;
	/** Stop the speech engine after this many idle minutes to give its memory back; 0 = never. */
	public int idleMinutes = 10;

	/** Direct sends on key release; Draft opens the chat box with the text. */
	public ChatInputMode inputMode = ChatInputMode.Direct;
	/** Whisper language code ("en", "nl", ...), "auto", or "game" to follow Minecraft's language. */
	public String language = "game";
	public boolean translate = false;
	/** Steers spelling and punctuation of the final pass. */
	public String prompt = "Hey, wanna go to the Nether? I found diamonds near the village. Watch out for that creeper!";
	/** Microphone (Java Sound mixer name); empty = system default. */
	public String microphone = "";
	/** Put in front of every spoken message, e.g. "🔊 ". */
	public String chatPrefix = "";
	public boolean livePreview = true;
	/** Recording stops by itself after this long. */
	public int maxSeconds = 120;
	/** Final pass beam size; partials are always greedy. */
	public int finalBeamSize = 5;
	/** Pause between the parts of a long message, so servers don't kick for spam. */
	public int messageGapMs = 1200;
	/** Advanced: use your own whisper-server instead of the bundled engine; empty = bundled. */
	public String externalServerUrl = "";

	/** The whisper language code actually used. */
	public String effectiveLanguage() {
		String lang = language == null ? "game" : language.trim().toLowerCase(Locale.ROOT);
		if (lang.isEmpty() || lang.equals("game")) {
			Minecraft mc = Minecraft.getInstance();
			String code = mc != null && mc.options != null ? mc.options.languageCode : "en_us";
			lang = code.contains("_") ? code.substring(0, code.indexOf('_')) : code;
		}
		return lang;
	}

	/** English-only models are slightly better at English; they can't do anything else. */
	public boolean englishOnly() {
		return effectiveLanguage().equals("en") && !translate;
	}

	public static STTAConfig instance() {
		if (instance == null) {
			instance = new STTAConfig();
			if (Files.exists(PATH)) {
				try {
					STTAConfig loaded = GSON.fromJson(Files.readString(PATH), STTAConfig.class);
					if (loaded != null) {
						instance = loaded;
					}
				} catch (Exception e) {
					STTAClient.LOGGER.warn("Could not read {}, using defaults", PATH, e);
				}
			}
			save();
		}
		return instance;
	}

	public static void save() {
		try {
			Files.writeString(PATH, GSON.toJson(instance));
		} catch (IOException e) {
			STTAClient.LOGGER.warn("Could not write {}", PATH, e);
		}
	}
}
