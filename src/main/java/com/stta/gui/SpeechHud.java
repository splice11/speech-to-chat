package com.stta.gui;

import com.stta.text.ChatText;
import java.util.List;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;

/**
 * Chat-styled box centred above the hotbar. While talking it grows with the live
 * transcript: words that held still across two updates are white, the still-changing
 * tail is grey.
 */
public class SpeechHud implements HudElement {
	private enum Phase { HIDDEN, LISTENING, FINISHING, SENT, NOTICE }

	private static final int MAX_WIDTH = 320;
	private static final int MAX_LINES = 8;
	private static final int PAD = 3;
	/** Bottom edge sits above hearts, armour and air bubbles. */
	private static final int BOTTOM_OFFSET = 62;
	private static final long SENT_SHOW_MS = 1400;
	private static final long NOTICE_SHOW_MS = 3000;
	private static final long FADE_MS = 400;

	private record State(Phase phase, String stable, String tentative, Component notice, long since) {
	}

	private volatile State state = new State(Phase.HIDDEN, "", "", null, 0);
	private volatile Supplier<Float> level = () -> 0f;
	private String[] lastPartialWords = new String[0];

	public synchronized void listening(Supplier<Float> level) {
		this.level = level;
		lastPartialWords = new String[0];
		state = new State(Phase.LISTENING, "", "", null, now());
	}

	/** Called with each live result; {@code committed} is final, {@code partial} may still change. */
	public synchronized void preview(String committed, String partial) {
		String[] words = partial.isEmpty() ? new String[0] : partial.split(" ");
		int same = 0;
		// whisper flips punctuation and case between updates; only a changed word counts as unsettled
		while (same < words.length && same < lastPartialWords.length
			&& ChatText.key(words[same]).equals(ChatText.key(lastPartialWords[same]))) {
			same++;
		}
		lastPartialWords = words;
		String stable = join(committed, String.join(" ", List.of(words).subList(0, same)));
		String tentative = String.join(" ", List.of(words).subList(same, words.length));
		state = new State(Phase.LISTENING, stable, tentative, null, state.since);
	}

	public synchronized void finishing() {
		State s = state;
		state = new State(Phase.FINISHING, join(s.stable, s.tentative), "", null, now());
	}

	public void sent(String text) {
		state = new State(Phase.SENT, text, "", null, now());
	}

	public void blank() {
		notice(Component.translatable("msg.stta.blank").withStyle(ChatFormatting.GRAY));
	}

	public void error(String key) {
		notice(Component.translatable(key).withStyle(ChatFormatting.RED));
	}

	public void clear() {
		state = new State(Phase.HIDDEN, "", "", null, now());
	}

	private void notice(Component c) {
		state = new State(Phase.NOTICE, "", "", c, now());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		State s = state;
		if (s.phase == Phase.HIDDEN) {
			return;
		}
		long age = now() - s.since;
		float alpha = switch (s.phase) {
			case SENT -> fade(age, SENT_SHOW_MS);
			case NOTICE -> fade(age, NOTICE_SHOW_MS);
			default -> 1f;
		};
		if (alpha <= 0.02f) {
			return;
		}

		MutableComponent text = Component.empty();
		switch (s.phase) {
			case LISTENING -> {
				// pulsing red dot like a recording light
				float pulse = 0.55f + 0.45f * (float) Math.sin(age / 180.0);
				text.append(Component.literal("● ").withColor(ARGB.color(255, 255, (int) (60 * pulse), (int) (60 * pulse))));
				if (s.stable.isEmpty() && s.tentative.isEmpty()) {
					text.append(Component.translatable("msg.stta.listening").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
				} else {
					text.append(Component.literal(s.stable));
					if (!s.tentative.isEmpty()) {
						text.append(Component.literal((s.stable.isEmpty() ? "" : " ") + s.tentative).withStyle(ChatFormatting.GRAY));
					}
				}
			}
			case FINISHING -> {
				String dots = ".".repeat(1 + (int) (age / 250 % 3));
				text.append(Component.literal(s.stable.isEmpty() ? dots : s.stable + " ").withStyle(ChatFormatting.WHITE));
				if (!s.stable.isEmpty()) {
					text.append(Component.literal(dots).withStyle(ChatFormatting.GRAY));
				}
			}
			case SENT -> text.append(Component.literal("✔ ").withStyle(ChatFormatting.GREEN)).append(Component.literal(s.stable));
			case NOTICE -> text.append(s.notice);
			default -> {
			}
		}

		Font font = mc.font;
		int maxWidth = Math.min(MAX_WIDTH, g.guiWidth() - 40);
		List<FormattedCharSequence> lines = font.split(text, maxWidth - 2 * PAD);
		if (lines.size() > MAX_LINES) {
			lines = lines.subList(lines.size() - MAX_LINES, lines.size());
		}
		int textWidth = 0;
		for (FormattedCharSequence line : lines) {
			textWidth = Math.max(textWidth, font.width(line));
		}
		int boxW = textWidth + 2 * PAD;
		int boxH = lines.size() * font.lineHeight + 2 * PAD - 1;
		int x0 = (g.guiWidth() - boxW) / 2;
		int y1 = g.guiHeight() - BOTTOM_OFFSET;
		int y0 = y1 - boxH;

		// same backdrop as vanilla chat lines (text background opacity 0.5)
		g.fill(x0, y0, x0 + boxW, y1, ARGB.color((int) (127 * alpha), 0, 0, 0));
		if (s.phase == Phase.LISTENING) {
			int meter = Math.round(Math.min(1f, level.get() * 3f) * boxW);
			g.fill(x0, y1, x0 + meter, y1 + 1, ARGB.color((int) (160 * alpha), 255, 255, 255));
		}
		int color = ARGB.color((int) (255 * alpha), 255, 255, 255);
		int y = y0 + PAD;
		for (FormattedCharSequence line : lines) {
			g.text(font, line, x0 + PAD, y, color, true);
			y += font.lineHeight;
		}
	}

	private static float fade(long age, long show) {
		return age < show ? 1f : Math.max(0f, 1f - (age - show) / (float) FADE_MS);
	}

	private static String join(String a, String b) {
		return a.isEmpty() ? b : b.isEmpty() ? a : a + " " + b;
	}

	private static long now() {
		return System.currentTimeMillis();
	}
}
