package com.stta.speech;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.stta.audio.Recorder;
import com.stta.config.STTAConfig;
import com.stta.engine.EngineManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Sends audio to the bundled whisper.cpp engine (see {@link EngineManager}). Recognition never
 * runs inside the game process, so a slow or crashing model cannot take Minecraft down with it.
 */
public class WhisperServer {
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

	/** Starts the engines in the background so the first key press doesn't wait for a model load. */
	public void warmUp() {
		EngineManager.get().warmUpAsync();
	}

	/**
	 * @param partial greedy and lenient (live preview); otherwise beam search with temperature fallback
	 */
	public String transcribe(float[] audio, String prompt, boolean partial) throws IOException, InterruptedException {
		STTAConfig config = STTAConfig.instance();
		// whisper-server keeps request params globally, so always send every one
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("response_format", "json");
		fields.put("language", config.effectiveLanguage());
		fields.put("translate", Boolean.toString(config.translate));
		// timestamps let whisper seek cleanly through recordings longer than its 30 s window
		fields.put("no_timestamps", Boolean.toString(partial));
		fields.put("temperature", "0.0");
		// The fallback re-decodes a repetitive segment up to 5 times: worth it once on release, but it
		// stalls the live preview for seconds. Partials are bounded by max_tokens and trimLoops instead.
		fields.put("temperature_inc", partial ? "0.0" : "0.2");
		fields.put("beam_size", partial ? "1" : Integer.toString(Math.max(1, config.finalBeamSize)));
		fields.put("best_of", partial ? "1" : "5");
		fields.put("no_speech_thold", "0.6");
		// Partials shrink the encoder to the clip (~5x faster); below ~384 frames turbo starts looping.
		// The final pass keeps the full 30 s context for accuracy.
		int ctx = partial ? Math.min(1500, Math.max(384, audio.length * 50 / Recorder.SAMPLE_RATE + 64)) : 0;
		fields.put("audio_ctx", Integer.toString(ctx));
		// Nobody says more than ~8 tokens a second; a partial past that is a loop. Caps how long a
		// runaway decode can stall the preview (our engine build accepts it; stock servers ignore it).
		int maxTokens = partial ? audio.length * 8 / Recorder.SAMPLE_RATE + 12 : 0;
		fields.put("max_tokens", Integer.toString(maxTokens));
		fields.put("prompt", prompt);

		String boundary = "stta" + UUID.randomUUID().toString().replace("-", "");
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		for (Map.Entry<String, String> f : fields.entrySet()) {
			body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + f.getKey() + "\"\r\n\r\n"
				+ f.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
		}
		body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\n"
			+ "Content-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
		body.writeBytes(wav(audio));
		body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

		String dump = System.getProperty("stta.debugDumpDir");
		if (dump != null && !partial) {
			java.nio.file.Path d = java.nio.file.Path.of(dump);
			java.nio.file.Files.createDirectories(d);
			String stamp = Long.toString(System.currentTimeMillis());
			java.nio.file.Files.write(d.resolve(stamp + ".wav"), wav(audio));
			java.nio.file.Files.writeString(d.resolve(stamp + ".txt"), fields.toString());
		}
		// Partials go to the small CPU preview engine (when the main one is on the GPU) so they
		// don't compete with the game for the GPU. Getting the URL may wait for a model load.
		String url = partial ? EngineManager.get().previewUrl() : EngineManager.get().mainUrl();
		HttpRequest req = HttpRequest.newBuilder(URI.create(url + "/inference"))
			.timeout(Duration.ofSeconds(partial ? 30 : 150))
			.header("Content-Type", "multipart/form-data; boundary=" + boundary)
			.POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
			.build();
		HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		if (res.statusCode() != 200) {
			throw new IOException("speech engine HTTP " + res.statusCode() + ": " + res.body());
		}
		JsonObject json = JsonParser.parseString(res.body()).getAsJsonObject();
		if (json.has("error")) {
			throw new IOException("speech engine: " + json.get("error").getAsString());
		}
		return json.has("text") ? json.get("text").getAsString() : "";
	}

	private static byte[] wav(float[] audio) {
		int dataBytes = audio.length * 2;
		ByteBuffer b = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
		b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + dataBytes).put("WAVE".getBytes(StandardCharsets.US_ASCII));
		b.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1)
			.putInt(Recorder.SAMPLE_RATE).putInt(Recorder.SAMPLE_RATE * 2).putShort((short) 2).putShort((short) 16);
		b.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(dataBytes);
		for (float s : audio) {
			b.putShort((short) Math.round(Math.max(-1f, Math.min(1f, s)) * 32767f));
		}
		return b.array();
	}
}
