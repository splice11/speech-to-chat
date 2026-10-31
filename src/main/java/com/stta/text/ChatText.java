package com.stta.text;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import net.minecraft.util.StringUtil;

public final class ChatText {
	public static final int MAX_MESSAGE = 256;

	// [BLANK_AUDIO], (laughs), *music*, ♪ ... — non-speech annotations whisper emits
	private static final Pattern ANNOTATION = Pattern.compile("\\[[^\\]]*]|\\([^)]*\\)|\\*[^*]*\\*|[♪♫]+");
	private static final Pattern SPACES = Pattern.compile("\\s+");

	private ChatText() {
	}

	/** Strips whisper annotations and anything the server would kick us for. */
	public static String clean(String raw) {
		String s = ANNOTATION.matcher(raw).replaceAll(" ");
		s = StringUtil.filterText(s);
		s = SPACES.matcher(s).replaceAll(" ").trim();
		// "..." alone or stray punctuation is not a message
		return s.chars().anyMatch(Character::isLetterOrDigit) ? s : "";
	}

	/**
	 * Cuts whisper's repetition loops ("I've always... I've always... I've always...") back to the
	 * first occurrence. A phrase of 2-3 words repeated {@code minRepeats} times in a row counts as a
	 * loop; single words need one more repeat so a real "no, no, no" survives, and phrases of 4+
	 * words one fewer, since people rarely say a whole clause twice in a row.
	 */
	public static String trimLoops(String text, int minRepeats) {
		String[] words = text.split(" ");
		String[] keys = new String[words.length];
		for (int i = 0; i < words.length; i++) {
			keys[i] = key(words[i]);
		}
		for (int i = 0; i < words.length; i++) {
			for (int n = 1; n <= 8; n++) {
				int need = n == 1 ? minRepeats + 1 : n >= 4 ? Math.max(2, minRepeats - 1) : minRepeats;
				if (i + n * need > words.length) {
					break;
				}
				int reps = 1;
				while (reps < need && same(keys, i, i + n * reps, n)) {
					reps++;
				}
				if (reps == need) {
					return String.join(" ", java.util.Arrays.copyOfRange(words, 0, i + n));
				}
			}
		}
		return text;
	}

	/** A word without case and punctuation, so "time," and "Time." compare equal. */
	public static String key(String word) {
		String k = word.toLowerCase().replaceAll("[^\\p{L}\\p{N}]", "");
		return k.isEmpty() ? word : k;
	}

	private static boolean same(String[] keys, int a, int b, int n) {
		for (int k = 0; k < n; k++) {
			if (!keys[a + k].equals(keys[b + k])) {
				return false;
			}
		}
		return true;
	}

	/** Splits at word boundaries into chat-sized messages. */
	public static List<String> split(String text, int max) {
		List<String> parts = new ArrayList<>();
		String rest = text;
		while (rest.length() > max) {
			int cut = rest.lastIndexOf(' ', max);
			if (cut <= 0) {
				cut = max;
			}
			parts.add(rest.substring(0, cut).trim());
			rest = rest.substring(cut).trim();
		}
		if (!rest.isEmpty()) {
			parts.add(rest);
		}
		return parts;
	}
}
