package com.lostglade.server.maprender;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public record MapRenderProfile(
		int version,
		int tileBlocks,
		int tilePixels,
		int haloChunks
) {
	/** Canonical high-detail source: one 16x16 chunk rendered to 256x256 = 16 px/block. */
	public static final MapRenderProfile CURRENT = new MapRenderProfile(6, 16, 256, 1);

	public MapRenderProfile {
		if (version <= 0) {
			throw new IllegalArgumentException("version must be positive");
		}
		if (tileBlocks <= 0 || tileBlocks % 16 != 0) {
			throw new IllegalArgumentException("tileBlocks must be a positive multiple of 16");
		}
		if (tilePixels <= 0 || tilePixels % tileBlocks != 0) {
			throw new IllegalArgumentException("tilePixels must be a positive integral multiple of tileBlocks");
		}
		if (haloChunks < 0) {
			throw new IllegalArgumentException("haloChunks must not be negative");
		}
	}

	public int tileChunks() {
		return this.tileBlocks / 16;
	}

	public int pixelsPerBlock() {
		return this.tilePixels / this.tileBlocks;
	}

	/** Hash of the render contract only. Resource-pack identity is added in Phase 2. */
	public String contractHash() {
		String contract = "yandex-map-render:v" + this.version
				+ ":blocks=" + this.tileBlocks
				+ ":pixels=" + this.tilePixels
				+ ":halo=" + this.haloChunks;
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(contract.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}
}
