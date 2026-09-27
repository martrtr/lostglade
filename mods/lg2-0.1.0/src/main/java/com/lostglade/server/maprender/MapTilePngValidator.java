package com.lostglade.server.maprender;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

/** Server-side independent validation before a worker result can enter the persistent tile store. */
public final class MapTilePngValidator {
	private static final byte[] PNG_SIGNATURE = new byte[]{
			(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
	};

	private MapTilePngValidator() {
	}

	public static ValidationResult validate(byte[] pngBytes, int expectedPixels) {
		if (pngBytes == null || pngBytes.length < 33) {
			return ValidationResult.reject("png-too-small", 0.0D, 0.0D, 1.0D, 1.0D);
		}
		for (int i = 0; i < PNG_SIGNATURE.length; i++) {
			if (pngBytes[i] != PNG_SIGNATURE[i]) {
				return ValidationResult.reject("invalid-png-signature", 0.0D, 0.0D, 1.0D, 1.0D);
			}
		}
		ByteBuffer header = ByteBuffer.wrap(pngBytes).order(ByteOrder.BIG_ENDIAN);
		if (header.getInt(8) != 13
				|| pngBytes[12] != 'I'
				|| pngBytes[13] != 'H'
				|| pngBytes[14] != 'D'
				|| pngBytes[15] != 'R') {
			return ValidationResult.reject("invalid-ihdr", 0.0D, 0.0D, 1.0D, 1.0D);
		}
		int width = header.getInt(16);
		int height = header.getInt(20);
		if (width != expectedPixels || height != expectedPixels) {
			return ValidationResult.reject("wrong-size", 0.0D, 0.0D, 1.0D, 1.0D);
		}

		final BufferedImage image;
		try {
			image = ImageIO.read(new ByteArrayInputStream(pngBytes));
		} catch (IOException exception) {
			return ValidationResult.reject("png-decode-failed", 0.0D, 0.0D, 1.0D, 1.0D);
		}
		if (image == null || image.getWidth() != expectedPixels || image.getHeight() != expectedPixels) {
			return ValidationResult.reject("decoded-size-mismatch", 0.0D, 0.0D, 1.0D, 1.0D);
		}

		Map<Integer, Integer> bins = new HashMap<>();
		long opaqueCount = 0L;
		double sum = 0.0D;
		double sumSquares = 0.0D;
		int dominant = 0;
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int argb = image.getRGB(x, y);
				int alpha = (argb >>> 24) & 0xFF;
				if (alpha <= 1) {
					continue;
				}
				int red = (argb >>> 16) & 0xFF;
				int green = (argb >>> 8) & 0xFF;
				int blue = argb & 0xFF;
				double brightness = (red + green + blue) / 3.0D;
				sum += brightness;
				sumSquares += brightness * brightness;
				opaqueCount++;
				int bin = ((red >>> 3) << 10) | ((green >>> 3) << 5) | (blue >>> 3);
				dominant = Math.max(dominant, bins.merge(bin, 1, Integer::sum));
			}
		}
		long pixelCount = (long) width * height;
		if (opaqueCount == 0L) {
			return ValidationResult.reject("fully-transparent", 0.0D, 0.0D, 1.0D, 1.0D);
		}
		double mean = sum / opaqueCount;
		double variance = Math.max(0.0D, sumSquares / opaqueCount - mean * mean);
		double dominantFraction = dominant / (double) opaqueCount;
		double transparentFraction = 1.0D - opaqueCount / (double) pixelCount;
		if (dominantFraction >= 0.9995D && variance < 0.35D) {
			return ValidationResult.reject("near-uniform-frame", mean, variance, transparentFraction, dominantFraction);
		}
		if (dominantFraction >= 0.998D && mean <= 2.0D) {
			return ValidationResult.reject("near-black-frame", mean, variance, transparentFraction, dominantFraction);
		}
		if (dominantFraction >= 0.998D && mean >= 253.0D) {
			return ValidationResult.reject("near-white-frame", mean, variance, transparentFraction, dominantFraction);
		}
		return new ValidationResult(true, "ok", mean, variance, transparentFraction, dominantFraction);
	}

	public record ValidationResult(
			boolean accepted,
			String reason,
			double meanBrightness,
			double brightnessVariance,
			double transparentFraction,
			double dominantColorFraction
	) {
		private static ValidationResult reject(
				String reason,
				double meanBrightness,
				double brightnessVariance,
				double transparentFraction,
				double dominantColorFraction
		) {
			return new ValidationResult(false, reason, meanBrightness, brightnessVariance, transparentFraction, dominantColorFraction);
		}
	}
}
