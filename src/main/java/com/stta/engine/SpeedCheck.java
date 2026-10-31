package com.stta.engine;

import com.stta.STTAClient;
import com.stta.model.Models;
import com.stta.speech.WhisperServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;

/**
 * Times a real final-quality transcription of a bundled 5 s sentence on this PC. Guessing from
 * the GPU name is not enough: a GeForce GT 710 got the big model and needed ~80 s per sentence.
 */
public final class SpeedCheck {
	/** Final pass may take at most this fraction of the audio length (a 4 s sentence in ~2 s). */
	public static final double MAX_RTF = Double.parseDouble(System.getProperty("stta.debugMaxRtf", "0.5"));
	/** Give up early on hopeless setups instead of making the player wait a minute. */
	private static final long LIMIT_MS = 6000;
	/** CPU cost of each model relative to base, measured on a 5 s sentence (i7-10700K, 4 threads). */
	private static final double SMALL_VS_BASE = 3.3;
	private static final double TURBO_VS_BASE = 16.0;

	private SpeedCheck() {
	}

	/** Milliseconds for a final pass of the sample on that engine, or -1 when it took longer than the limit. */
	public static long time(String url, boolean cpu) throws IOException, InterruptedException {
		float[] audio = sample();
		long t0 = System.nanoTime();
		try {
			new WhisperServer().transcribeAt(url, audio, "", false, cpu, Duration.ofMillis(LIMIT_MS));
		} catch (HttpTimeoutException e) {
			return -1;
		}
		return (System.nanoTime() - t0) / 1_000_000;
	}

	public static double sampleSeconds() throws IOException {
		return sample().length / 16000.0;
	}

	public static boolean fastEnough(long ms) throws IOException {
		return ms >= 0 && ms <= MAX_RTF * sampleSeconds() * 1000;
	}

	/** A setup to switch to. */
	public record Choice(Models.Quality quality, boolean gpu) {
	}

	public static double costOf(Models.Quality q) {
		return cost(q);
	}

	/** Rough cost of each model relative to base (same device). */
	private static double cost(Models.Quality q) {
		return switch (q) {
			case ACCURATE -> TURBO_VS_BASE;
			case BALANCED -> SMALL_VS_BASE;
			case LIGHT -> 1.0;
		};
	}

	/**
	 * Picks the most accurate model that is estimated to be fast enough, on whichever of GPU and
	 * CPU is faster for it. {@code gpuMs} is the measured time of {@code current} on the GPU (-1 =
	 * over the limit, Long.MIN_VALUE = no GPU); {@code cpuBaseMs} the measured base model on the CPU.
	 * When nothing fits, the fastest option overall.
	 */
	public static Choice choose(Models.Quality current, long gpuMs, long cpuBaseMs) throws IOException {
		double budget = MAX_RTF * sampleSeconds() * 1000;
		boolean hasGpu = gpuMs != Long.MIN_VALUE;
		double gpu = gpuMs < 0 ? LIMIT_MS * 10.0 : gpuMs; // over the limit: assume very slow
		double cpuBase = cpuBaseMs < 0 ? LIMIT_MS * 10.0 : cpuBaseMs;
		Choice fastest = new Choice(Models.Quality.LIGHT, false);
		double fastestMs = Double.MAX_VALUE;
		for (Models.Quality q : Models.Quality.values()) {
			double onCpu = cpuBase * cost(q);
			double onGpu = hasGpu ? gpu * cost(q) / cost(current) : Double.MAX_VALUE;
			boolean useGpu = onGpu <= onCpu;
			double best = Math.min(onGpu, onCpu);
			if (best <= budget) {
				return new Choice(q, useGpu);
			}
			if (best < fastestMs) {
				fastestMs = best;
				fastest = new Choice(q, useGpu);
			}
		}
		return fastest;
	}

	private static float[] cached;

	private static synchronized float[] sample() throws IOException {
		if (cached == null) {
			try (InputStream in = SpeedCheck.class.getResourceAsStream("/calibration/sample.wav")) {
				if (in == null) {
					throw new IOException("calibration sample missing from the mod jar");
				}
				byte[] b = in.readAllBytes();
				ByteBuffer buf = ByteBuffer.wrap(b, 44, b.length - 44).order(ByteOrder.LITTLE_ENDIAN);
				float[] out = new float[(b.length - 44) / 2];
				for (int i = 0; i < out.length; i++) {
					out[i] = buf.getShort() / 32768f;
				}
				cached = out;
			}
		}
		return cached;
	}

	public static void log(String what, long ms) {
		STTAClient.LOGGER.info("Speed check {}: {}", what, ms < 0 ? "over " + LIMIT_MS + " ms" : ms + " ms");
	}
}
