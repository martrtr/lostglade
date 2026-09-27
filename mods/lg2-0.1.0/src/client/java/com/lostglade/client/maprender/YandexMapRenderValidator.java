package com.lostglade.client.maprender;

import com.mojang.blaze3d.platform.NativeImage;

import java.util.HashMap;
import java.util.Map;

/** Conservative corruption detector. It never color-corrects or repairs a frame. */
public final class YandexMapRenderValidator {
	private YandexMapRenderValidator() {
	}

	public static ValidationResult validate(NativeImage image, int expectedPixels) {
		if (image == null) {
			return ValidationResult.reject("missing-image", 0.0D, 0.0D, 0.0D);
		}
		if (image.getWidth() != expectedPixels || image.getHeight() != expectedPixels) {
			return ValidationResult.reject("wrong-size", 0.0D, 0.0D, 0.0D);
		}
		int[] pixels = image.getPixelsABGR();
		if (pixels.length == 0) {
			return ValidationResult.reject("empty-image", 0.0D, 0.0D, 0.0D);
		}
		Map<Integer, Integer> bins = new HashMap<>();
		long opaqueCount = 0L;
		double sum = 0.0D;
		double sumSquares = 0.0D;
		int dominant = 0;
		for (int pixel : pixels) {
			int alpha = (pixel >>> 24) & 0xFF;
			if (alpha <= 1) {
				continue;
			}
			int red = pixel & 0xFF;
			int green = (pixel >>> 8) & 0xFF;
			int blue = (pixel >>> 16) & 0xFF;
			double brightness = (red + green + blue) / 3.0D;
			sum += brightness;
			sumSquares += brightness * brightness;
			opaqueCount++;
			int bin = ((red >>> 3) << 10) | ((green >>> 3) << 5) | (blue >>> 3);
			int count = bins.merge(bin, 1, Integer::sum);
			dominant = Math.max(dominant, count);
		}
		if (opaqueCount == 0L) {
			return ValidationResult.reject("fully-transparent", 0.0D, 0.0D, 1.0D);
		}
		double mean = sum / opaqueCount;
		double variance = Math.max(0.0D, sumSquares / opaqueCount - mean * mean);
		double dominantFraction = dominant / (double) opaqueCount;
		double transparentFraction = 1.0D - opaqueCount / (double) pixels.length;
		if (dominantFraction >= 0.9995D && variance < 0.35D) {
			return ValidationResult.reject("near-uniform-frame", mean, variance, transparentFraction);
		}
		if (dominantFraction >= 0.998D && mean <= 2.0D) {
			return ValidationResult.reject("near-black-frame", mean, variance, transparentFraction);
		}
		if (dominantFraction >= 0.998D && mean >= 253.0D) {
			return ValidationResult.reject("near-white-frame", mean, variance, transparentFraction);
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
		private static ValidationResult reject(String reason, double mean, double variance, double transparentFraction) {
			return new ValidationResult(false, reason, mean, variance, transparentFraction, 1.0D);
		}
	}
}
