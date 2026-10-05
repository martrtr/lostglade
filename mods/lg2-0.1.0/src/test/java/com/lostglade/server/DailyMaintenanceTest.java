package com.lostglade.server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

public final class DailyMaintenanceTest {
	public static void main(String[] args) throws Exception {
		checkSchedule();
		checkWarnings();
		checkArchiveAndRetention();
		System.out.println("Daily maintenance schedule, archive and retention checks passed");
	}

	private static void checkSchedule() {
		Instant before = Instant.parse("2026-10-05T23:59:59Z");
		ZonedDateTime restart = DailyMaintenanceSystem.nextRestart(before, 3);
		check(restart.toString().startsWith("2026-10-06T03:00+03:00"), "wrong Moscow restart time");
		check(restart.toInstant().minus(Duration.ofMinutes(30)).equals(Instant.parse("2026-10-05T23:30:00Z")), "wrong backup time");
		check(DailyMaintenanceSystem.nextRestart(Instant.parse("2026-10-06T00:00:00Z"), 3).getDayOfMonth() == 7,
				"restart at exactly 03:00 must be tomorrow");
		check(DailyMaintenanceSystem.nextRestart(before, -5).getHour() == 0, "negative hour was not clamped");
		check(DailyMaintenanceSystem.nextRestart(before, 42).getHour() == 23, "hour above 23 was not clamped");
	}

	private static void checkWarnings() {
		for (long seconds : new long[]{1800, 600, 180, 60, 30, 10, 5, 4, 3, 2, 1}) {
			check(DailyMaintenanceSystem.dueWarningSeconds(seconds * 1000 + 1, seconds * 1000) == seconds,
					"missing warning at " + seconds + " seconds");
		}
		check(DailyMaintenanceSystem.dueWarningSeconds(600_001, 599_999) == 600, "missed threshold between ticks");
		check(DailyMaintenanceSystem.dueWarningSeconds(600_000, 599_000) == -1, "duplicate warning");
		check(DailyMaintenanceSystem.dueWarningSeconds(1000, 0) == -1, "warning after stop time");
	}

	private static void checkArchiveAndRetention() throws Exception {
		Path root = Files.createTempDirectory("lg2-maintenance-test-");
		try {
			Path world = root.resolve("data/world");
			Path game = root.resolve("release");
			Path backupDir = root.resolve("data/backups");
			Files.createDirectories(world.resolve("region"));
			Files.createDirectories(game.resolve("config"));
			Files.createDirectories(root.resolve("data/config"));
			Files.createDirectories(backupDir);
			Files.writeString(world.resolve("level.dat"), "level");
			Files.writeString(world.resolve("region/r.0.0.mca"), "chunks");
			Files.writeString(game.resolve("config/lg2.json"), "settings");
			Files.writeString(root.resolve("data/config/lg2-auth.json"), "auth state");
			Files.writeString(game.resolve("server.properties"), "properties");
			Path oldBackup = backupDir.resolve("lg2-2026-09-20-03-msk.zip");
			Path stalePartial = backupDir.resolve("lg2-2026-09-20-03-msk.zip.partial");
			Path unrelated = backupDir.resolve("manual.zip");
			Files.writeString(oldBackup, "old");
			Files.writeString(stalePartial, "incomplete");
			Files.writeString(unrelated, "keep");
			FileTime oldTime = FileTime.from(Instant.now().minus(Duration.ofDays(8)));
			Files.setLastModifiedTime(oldBackup, oldTime);
			Files.setLastModifiedTime(stalePartial, oldTime);
			Files.setLastModifiedTime(unrelated, oldTime);

			ZonedDateTime restart = DailyMaintenanceSystem.nextRestart(Instant.parse("2026-10-05T23:00:00Z"), 3);
			Path archive = DailyMaintenanceSystem.writeBackup(world, game, backupDir, restart);
			check(Files.isRegularFile(archive), "backup archive missing");
			check(!Files.exists(oldBackup), "expired backup was not removed");
			check(!Files.exists(stalePartial), "stale partial backup was not removed");
			check(Files.exists(unrelated), "unrelated archive was removed");
			check(!Files.exists(backupDir.resolve(archive.getFileName() + ".partial")), "partial archive left behind");
			try (ZipFile zip = new ZipFile(archive.toFile())) {
				Set<String> entries = zip.stream().map(entry -> entry.getName()).collect(Collectors.toSet());
				for (String entry : new String[]{"world/level.dat", "world/region/r.0.0.mca",
						"config/lg2.json", "persistent-config/lg2-auth.json", "server.properties"}) {
					check(entries.contains(entry), "missing archive entry " + entry);
				}
			}
		} finally {
			try (var files = Files.walk(root)) {
				for (Path file : files.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
					Files.deleteIfExists(file);
				}
			}
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
