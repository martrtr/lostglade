package com.lostglade.server.maprender;

public record MapTileMetadata(
		String profileHash,
		String sourceFingerprint,
		String visualFingerprint,
		long renderedAtEpochMs,
		long renderedRevision,
		int pixelWidth,
		int pixelHeight,
		long existingChunkMask,
		String resultChecksum,
		String workerId
) {
	public boolean isCurrentFor(String expectedProfileHash, MapRenderProfile profile, MapTileSourceState source) {
		return expectedProfileHash != null
				&& expectedProfileHash.equals(this.profileHash)
				&& source != null
				&& source.sourceFingerprint().equals(this.sourceFingerprint)
				&& source.existingChunkMask() == this.existingChunkMask
				&& this.pixelWidth == profile.tilePixels()
				&& this.pixelHeight == profile.tilePixels()
				&& this.resultChecksum != null
				&& !this.resultChecksum.isBlank();
	}
}
