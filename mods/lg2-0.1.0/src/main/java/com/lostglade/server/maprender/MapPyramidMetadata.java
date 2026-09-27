package com.lostglade.server.maprender;

public record MapPyramidMetadata(
		String profileHash,
		int level,
		long tileX,
		long tileZ,
		String childSignature,
		long builtAtEpochMs,
		int pixelWidth,
		int pixelHeight,
		String resultChecksum
) {
}
