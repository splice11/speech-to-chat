package com.stta.model;

import com.stta.STTAClient;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Whisper models the mod knows how to fetch. Files come from the official whisper.cpp
 * repository on HuggingFace and are checked against these SHA-256 sums before use.
 */
public final class Models {
	private static final String BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/";

	public enum Quality {
		/** large-v3-turbo: best accuracy, wants a GPU. */
		ACCURATE,
		/** small: good accuracy, fine on a decent CPU or integrated GPU. */
		BALANCED,
		/** base: fastest, for slow machines. */
		LIGHT
	}

	public enum Model {
		TURBO("ggml-large-v3-turbo-q5_0.bin", 574041195L, "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2"),
		SMALL("ggml-small-q5_1.bin", 190085487L, "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb"),
		SMALL_EN("ggml-small.en-q5_1.bin", 190098681L, "bfdff4894dcb76bbf647d56263ea2a96645423f1669176f4844a1bf8e478ad30"),
		BASE("ggml-base-q5_1.bin", 59707625L, "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898"),
		BASE_EN("ggml-base.en-q5_1.bin", 59721011L, "4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f");

		public final String file;
		public final long size;
		public final String sha256;

		Model(String file, long size, String sha256) {
			this.file = file;
			this.size = size;
			this.sha256 = sha256;
		}

		public Path path() {
			return dir().resolve(file);
		}

		/** Present and the right size; the hash was checked when it was downloaded. */
		public boolean installed() {
			try {
				return Files.size(path()) == size;
			} catch (IOException e) {
				return false;
			}
		}
	}

	private Models() {
	}

	public static Path dir() {
		return STTAClient.dataDir().resolve("models");
	}

	/** The model for a quality level; English-only variants are a bit more accurate when English is all you speak. */
	public static Model forQuality(Quality quality, boolean english) {
		return switch (quality) {
			case ACCURATE -> Model.TURBO;
			case BALANCED -> english ? Model.SMALL_EN : Model.SMALL;
			case LIGHT -> english ? Model.BASE_EN : Model.BASE;
		};
	}

	/** Small fast model for the live preview on the CPU while the GPU does the final pass. */
	public static Model previewModel(boolean english) {
		return english ? Model.BASE_EN : Model.BASE;
	}

	/** Silero voice activity model, shipped inside the mod jar (MIT licensed, under 1 MB). */
	public static Path vadModel() throws IOException {
		Path target = dir().resolve("ggml-silero-v6.2.0.bin");
		if (!Files.exists(target)) {
			Files.createDirectories(dir());
			try (InputStream in = Models.class.getResourceAsStream("/models/ggml-silero-v6.2.0.bin")) {
				if (in == null) {
					throw new IOException("VAD model missing from the mod jar");
				}
				Path tmp = Files.createTempFile(dir(), "vad", ".part");
				Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
				Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
			}
		}
		return target;
	}

	public interface Progress {
		void update(long done, long total);
	}

	/**
	 * Downloads a model, resuming a previous partial download, and verifies its SHA-256.
	 * Checks {@code cancelled} between chunks.
	 */
	public static void download(Model model, Progress progress, java.util.function.BooleanSupplier cancelled)
		throws IOException, InterruptedException {
		Files.createDirectories(dir());
		Path part = dir().resolve(model.file + ".part");
		long have = Files.exists(part) ? Files.size(part) : 0;
		if (have > model.size) {
			Files.delete(part);
			have = 0;
		}
		HttpClient http = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NORMAL)
			.connectTimeout(Duration.ofSeconds(15))
			.build();
		if (have < model.size) {
			HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(BASE_URL + model.file))
				.header("User-Agent", "speech-to-text-accessibility-mod");
			if (have > 0) {
				req.header("Range", "bytes=" + have + "-");
			}
			HttpResponse<InputStream> res = http.send(req.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
			if (res.statusCode() == 200) {
				have = 0; // server ignored the range, start over
			} else if (res.statusCode() != 206) {
				throw new IOException("HTTP " + res.statusCode() + " downloading " + model.file);
			}
			STTAClient.LOGGER.info("Downloading {} ({} of {} bytes already here)", model.file, have, model.size);
			try (InputStream in = res.body();
				 OutputStream out = Files.newOutputStream(part, StandardOpenOption.CREATE,
					 have == 0 ? StandardOpenOption.TRUNCATE_EXISTING : StandardOpenOption.APPEND)) {
				byte[] buf = new byte[1 << 16];
				int n;
				while ((n = in.read(buf)) != -1) {
					if (cancelled.getAsBoolean()) {
						throw new InterruptedException("download cancelled");
					}
					out.write(buf, 0, n);
					have += n;
					progress.update(have, model.size);
				}
			}
		}
		if (!sha256(part).equals(model.sha256)) {
			Files.delete(part);
			throw new IOException(model.file + " failed its checksum; it was deleted, try again");
		}
		Files.move(part, model.path(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		STTAClient.LOGGER.info("Model {} installed", model.file);
	}

	private static String sha256(Path file) throws IOException {
		try (InputStream in = Files.newInputStream(file)) {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] buf = new byte[1 << 16];
			int n;
			while ((n = in.read(buf)) != -1) {
				md.update(buf, 0, n);
			}
			return HexFormat.of().formatHex(md.digest());
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
