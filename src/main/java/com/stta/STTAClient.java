package com.stta;

import com.stta.config.STTAConfig;
import com.stta.engine.EngineManager;
import com.stta.gui.SetupScreen;
import com.stta.gui.SpeechHud;
import com.stta.speech.VoiceInput;
import java.nio.file.Path;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class STTAClient implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("speech-to-chat");
	private static boolean setupOffered;

	/** Models, engine binaries and engine logs live here, inside the game directory. */
	public static Path dataDir() {
		return FabricLoader.getInstance().getGameDir().resolve("speech-to-chat");
	}

	@Override
	public void onInitializeClient() {
		STTAConfig.instance();
		SpeechHud hud = new SpeechHud();
		HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath("stta", "preview"), hud);
		new VoiceInput(hud).register();

		// engines are child processes; never leave one running after the game
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> EngineManager.get().stopAll());
		Runtime.getRuntime().addShutdownHook(new Thread(() -> EngineManager.get().stopAll(), "stta-engine-shutdown"));

		// first launch: offer setup once, on the title screen
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (screen instanceof TitleScreen && !setupOffered && !STTAConfig.instance().setupDone) {
				setupOffered = true;
				client.execute(() -> client.gui.setScreen(new SetupScreen(screen)));
			}
		});
		LOGGER.info("Speech to Chat ready");
	}
}
