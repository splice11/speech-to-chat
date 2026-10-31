package com.stta.config;

import com.stta.engine.EngineManager;
import com.stta.gui.SetupScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.isxander.yacl3.api.ButtonOption;
import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.OptionGroup;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.EnumControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.StringControllerBuilder;
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		// YACL is optional: without it Mod Menu opens the setup screen (model, microphone, GPU)
		if (!FabricLoader.getInstance().isModLoaded("yet_another_config_lib_v3")) {
			return SetupScreen::new;
		}
		return YaclScreen::create;
	}

	/** Separate class so YACL classes are only loaded when YACL is installed. */
	private static final class YaclScreen {
		static net.minecraft.client.gui.screens.Screen create(net.minecraft.client.gui.screens.Screen parent) {
			STTAConfig c = STTAConfig.instance();
			STTAConfig d = new STTAConfig();
			return YetAnotherConfigLib.createBuilder()
				.title(Component.translatable("config.stta.title"))
				.category(ConfigCategory.createBuilder()
					.name(Component.translatable("config.stta.general"))
					.option(ButtonOption.createBuilder()
						.name(Component.translatable("config.stta.open_setup"))
						.description(desc("config.stta.open_setup_description"))
						.action((screen, option) -> Minecraft.getInstance().gui.setScreen(new SetupScreen(screen)))
						.build())
					.group(OptionGroup.createBuilder()
						.name(Component.translatable("config.stta.chat_options"))
						.option(Option.<STTAConfig.ChatInputMode>createBuilder()
							.name(Component.translatable("config.stta.chat_input_mode"))
							.description(desc("config.stta.chat_input_mode_description"))
							.binding(d.inputMode, () -> c.inputMode, v -> c.inputMode = v)
							.controller(o -> EnumControllerBuilder.create(o).enumClass(STTAConfig.ChatInputMode.class)
								.formatValue(v -> Component.translatable("config.stta.chat_input_mode." + v.name().toLowerCase())))
							.build())
						.option(Option.<Boolean>createBuilder()
							.name(Component.translatable("config.stta.live_preview"))
							.description(desc("config.stta.live_preview_description"))
							.binding(d.livePreview, () -> c.livePreview, v -> c.livePreview = v)
							.controller(TickBoxControllerBuilder::create)
							.build())
						.option(Option.<String>createBuilder()
							.name(Component.translatable("config.stta.chat_prefix"))
							.description(desc("config.stta.chat_prefix_description"))
							.binding(d.chatPrefix, () -> c.chatPrefix, v -> c.chatPrefix = v)
							.controller(StringControllerBuilder::create)
							.build())
						.option(Option.<Integer>createBuilder()
							.name(Component.translatable("config.stta.max_seconds"))
							.description(desc("config.stta.max_seconds_description"))
							.binding(d.maxSeconds, () -> c.maxSeconds, v -> c.maxSeconds = v)
							.controller(o -> IntegerSliderControllerBuilder.create(o).range(10, 300).step(10)
								.formatValue(v -> Component.literal(v + " s")))
							.build())
						.option(Option.<Integer>createBuilder()
							.name(Component.translatable("config.stta.message_gap"))
							.description(desc("config.stta.message_gap_description"))
							.binding(d.messageGapMs, () -> c.messageGapMs, v -> c.messageGapMs = v)
							.controller(o -> IntegerSliderControllerBuilder.create(o).range(0, 5000).step(100)
								.formatValue(v -> Component.literal(v + " ms")))
							.build())
						.build())
					.group(OptionGroup.createBuilder()
						.name(Component.translatable("config.stta.model_options"))
						.option(Option.<String>createBuilder()
							.name(Component.translatable("config.stta.language"))
							.description(desc("config.stta.language_description"))
							.binding(d.language, () -> c.language, v -> c.language = v)
							.controller(StringControllerBuilder::create)
							.build())
						.option(Option.<Boolean>createBuilder()
							.name(Component.translatable("config.stta.translate"))
							.description(desc("config.stta.translate_description"))
							.binding(d.translate, () -> c.translate, v -> c.translate = v)
							.controller(TickBoxControllerBuilder::create)
							.build())
						.option(Option.<String>createBuilder()
							.name(Component.translatable("config.stta.prompt"))
							.description(desc("config.stta.prompt_description"))
							.binding(d.prompt, () -> c.prompt, v -> c.prompt = v)
							.controller(StringControllerBuilder::create)
							.build())
						.option(Option.<Integer>createBuilder()
							.name(Component.translatable("config.stta.beam"))
							.description(desc("config.stta.beam_description"))
							.binding(d.finalBeamSize, () -> c.finalBeamSize, v -> c.finalBeamSize = v)
							.controller(o -> IntegerSliderControllerBuilder.create(o).range(1, 8).step(1))
							.build())
						.build())
					.group(OptionGroup.createBuilder()
						.name(Component.translatable("config.stta.engine_options"))
						.option(Option.<Boolean>createBuilder()
							.name(Component.translatable("config.stta.preview_on_cpu"))
							.description(desc("config.stta.preview_on_cpu_description"))
							.binding(d.previewOnCpu, () -> c.previewOnCpu, v -> c.previewOnCpu = v)
							.controller(TickBoxControllerBuilder::create)
							.build())
						.option(Option.<Integer>createBuilder()
							.name(Component.translatable("config.stta.idle_minutes"))
							.description(desc("config.stta.idle_minutes_description"))
							.binding(d.idleMinutes, () -> c.idleMinutes, v -> c.idleMinutes = v)
							.controller(o -> IntegerSliderControllerBuilder.create(o).range(0, 60).step(1)
								.formatValue(v -> v == 0 ? Component.translatable("config.stta.never") : Component.literal(v + " min")))
							.build())
						.option(Option.<String>createBuilder()
							.name(Component.translatable("config.stta.external_server"))
							.description(desc("config.stta.external_server_description"))
							.binding(d.externalServerUrl, () -> c.externalServerUrl, v -> c.externalServerUrl = v)
							.controller(StringControllerBuilder::create)
							.build())
						.build())
					.build())
				.save(() -> {
					STTAConfig.save();
					// language, preview and server changes take effect on the next key press
					EngineManager.get().restart();
				})
				.build()
				.generateScreen(parent);
		}
	}

	private static OptionDescription desc(String key) {
		return OptionDescription.of(Component.translatable(key));
	}
}
