package com.stta.audio;

import com.stta.STTAClient;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import java.util.ArrayList;
import java.util.List;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;

/**
 * Captures 16 kHz mono float samples on its own thread into a growing buffer that
 * can be snapshotted while recording. Falls back to 48 kHz capture and decimates
 * when the sound backend refuses 16 kHz.
 */
public class Recorder {
	public static final int SAMPLE_RATE = 16000;

	private final Object lock = new Object();
	private float[] samples = new float[SAMPLE_RATE * 10];
	private int length;
	private volatile boolean running;
	private volatile float level;
	private Thread thread;

	public void start() throws LineUnavailableException {
		synchronized (lock) {
			length = 0;
		}
		level = 0;
		String debugWav = System.getProperty("stta.debugWav");
		if (debugWav != null) {
			running = true;
			thread = new Thread(() -> playFile(java.nio.file.Path.of(debugWav)), "stta-mic");
			thread.setDaemon(true);
			thread.start();
			return;
		}
		TargetDataLine line;
		int decimate;
		try {
			line = open(SAMPLE_RATE);
			decimate = 1;
		} catch (LineUnavailableException | IllegalArgumentException e) {
			line = open(48000);
			decimate = 3;
		}
		TargetDataLine mic = line;
		int factor = decimate;
		running = true;
		thread = new Thread(() -> capture(mic, factor), "stta-mic");
		thread.setDaemon(true);
		thread.start();
	}

	private static TargetDataLine open(int rate) throws LineUnavailableException {
		AudioFormat format = new AudioFormat(rate, 16, 1, true, false);
		DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
		TargetDataLine line = null;
		String wanted = com.stta.config.STTAConfig.instance().microphone;
		if (wanted != null && !wanted.isEmpty()) {
			for (Mixer.Info m : AudioSystem.getMixerInfo()) {
				Mixer mixer = AudioSystem.getMixer(m);
				if (m.getName().equals(wanted) && mixer.isLineSupported(info)) {
					line = (TargetDataLine) mixer.getLine(info);
					break;
				}
			}
			if (line == null) {
				STTAClient.LOGGER.warn("Microphone '{}' not found, using the default one", wanted);
			}
		}
		if (line == null) {
			if (!AudioSystem.isLineSupported(info)) {
				throw new LineUnavailableException("No microphone line for " + format);
			}
			line = (TargetDataLine) AudioSystem.getLine(info);
		}
		// ~40 ms buffer keeps the live preview responsive
		line.open(format, rate / 25 * 2);
		line.start();
		return line;
	}

	private void capture(TargetDataLine line, int decimate) {
		byte[] buf = new byte[640 * decimate];
		float[] chunk = new float[buf.length / 2 / decimate];
		try {
			while (running) {
				int read = line.read(buf, 0, buf.length);
				int frames = read / 2 / decimate;
				float peak = 0;
				for (int i = 0; i < frames; i++) {
					float sum = 0;
					for (int k = 0; k < decimate; k++) {
						int o = (i * decimate + k) * 2;
						sum += (short) ((buf[o] & 0xff) | (buf[o + 1] << 8)) / 32768f;
					}
					chunk[i] = sum / decimate;
					peak = Math.max(peak, Math.abs(chunk[i]));
				}
				level = Math.max(peak, level * 0.85f);
				append(chunk, frames);
			}
		} catch (Throwable t) {
			STTAClient.LOGGER.error("Microphone capture failed", t);
			running = false;
		} finally {
			line.stop();
			line.close();
		}
	}

	/** Test hook: streams a 16 kHz mono s16le WAV in real time as if it were the microphone, then silence. */
	private void playFile(java.nio.file.Path wav) {
		try {
			byte[] b = java.nio.file.Files.readAllBytes(wav);
			int frames = 320;
			float[] chunk = new float[frames];
			long t0 = System.nanoTime();
			for (int pos = 44, i = 0; running; pos += frames * 2, i++) {
				float peak = 0;
				for (int k = 0; k < frames; k++) {
					int o = pos + k * 2;
					chunk[k] = o + 1 < b.length ? (short) ((b[o] & 0xff) | (b[o + 1] << 8)) / 32768f : 0f;
					peak = Math.max(peak, Math.abs(chunk[k]));
				}
				level = Math.max(peak, level * 0.85f);
				append(chunk, frames);
				long due = t0 + (i + 1) * 20_000_000L;
				Thread.sleep(Math.max(0, (due - System.nanoTime()) / 1_000_000));
			}
		} catch (Exception e) {
			STTAClient.LOGGER.error("Debug audio failed", e);
			running = false;
		}
	}

	private void append(float[] chunk, int n) {
		synchronized (lock) {
			if (length + n > samples.length) {
				float[] grown = new float[Math.max(samples.length * 2, length + n)];
				System.arraycopy(samples, 0, grown, 0, length);
				samples = grown;
			}
			System.arraycopy(chunk, 0, samples, length, n);
			length += n;
		}
	}

	/** Stops capture and waits for the capture thread to drain. */
	public void stop() {
		running = false;
		Thread t = thread;
		if (t != null) {
			try {
				t.join(500);
			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}
		}
		level = 0;
	}

	public boolean isRunning() {
		return running;
	}

	public int length() {
		synchronized (lock) {
			return length;
		}
	}

	public float[] copy(int from, int to) {
		synchronized (lock) {
			to = Math.min(to, length);
			float[] out = new float[Math.max(0, to - from)];
			System.arraycopy(samples, from, out, 0, out.length);
			return out;
		}
	}

	/** Names of the devices that can record, for the microphone picker. */
	public static List<String> microphones() {
		AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
		DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
		List<String> names = new ArrayList<>();
		for (Mixer.Info m : AudioSystem.getMixerInfo()) {
			try {
				if (AudioSystem.getMixer(m).isLineSupported(info)) {
					names.add(m.getName());
				}
			} catch (Exception ignored) {
				// some backends throw for odd devices; just skip them
			}
		}
		return names;
	}

	/** Smoothed input peak, for the HUD meter. */
	public float level() {
		return level;
	}
}
