package com.lostglade.server.glitch;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class OfflineEnderChestOwners {
	private OfflineEnderChestOwners() {
	}

	static List<UUID> list(Path playerDataDir, Set<UUID> online) throws IOException {
		List<UUID> owners = new ArrayList<>();
		if (!Files.isDirectory(playerDataDir)) {
			return owners;
		}
		try (DirectoryStream<Path> files = Files.newDirectoryStream(playerDataDir, "*.dat")) {
			for (Path file : files) {
				String name = file.getFileName().toString();
				try {
					UUID owner = UUID.fromString(name.substring(0, name.length() - 4));
					if (!online.contains(owner) && Files.isRegularFile(file)) {
						owners.add(owner);
					}
				} catch (IllegalArgumentException ignored) {
					// Ignore non-player data files.
				}
			}
		}
		return owners;
	}
}
