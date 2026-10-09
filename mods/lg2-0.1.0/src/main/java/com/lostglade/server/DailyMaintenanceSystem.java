package com.lostglade.server;

import com.lostglade.Lg2;
import com.lostglade.config.Lg2Config;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class DailyMaintenanceSystem {
	static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
	private static final long[] WARN_AT_SECONDS = {1800, 600, 180, 60, 30, 10, 5, 4, 3, 2, 1};
	private static final Set<String> SERVER_FILES = Set.of(
			"server.properties", "whitelist.json", "ops.json", "banned-players.json",
			"banned-ips.json", "usercache.json", "RELEASE_COMMIT");
	private static final String BACKUP_PREFIX = "lg2-";
	private static final Pattern BACKUP_FILE = Pattern.compile(
			"lg2-(?:\\d{4}-\\d{2}-\\d{2}-\\d{2}-msk|manual-\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-msk-[0-9a-f]{32})\\.zip");
	private static final DateTimeFormatter MANUAL_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");
	private static final Duration RETENTION = Duration.ofDays(7);

	private static Schedule schedule;
	private static volatile boolean backupRunning;
	private static boolean backupDelayAnnounced;
	private static boolean restartRequested;
	private static long previousRemainingMillis;

	private DailyMaintenanceSystem() {
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			Instant now = Instant.now();
			schedule = new Schedule(nextRestart(now, Lg2Config.get().restartHourMsk));
			previousRemainingMillis = Duration.between(now, schedule.restart().toInstant()).toMillis();
			backupRunning = false;
			backupDelayAnnounced = false;
			restartRequested = false;
			Lg2.LOGGER.info("Daily restart scheduled for {} (Moscow); backup 30 minutes earlier", schedule.restart());
		});
		ServerTickEvents.END_SERVER_TICK.register(DailyMaintenanceSystem::tick);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> schedule = null);
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
				Commands.literal("restart")
						.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
						.then(Commands.argument("minutes", IntegerArgumentType.integer(1))
								.executes(context -> startManualRestart(context.getSource(),
										IntegerArgumentType.getInteger(context, "minutes"))))));
	}

	private static int startManualRestart(CommandSourceStack source, int minutes) {
		if (schedule == null || restartRequested) {
			source.sendFailure(Component.literal("Перезапуск уже выполняется."));
			return 0;
		}
		if (backupRunning) {
			source.sendFailure(Component.literal("Дождитесь завершения текущего бекапа."));
			return 0;
		}
		MinecraftServer server = source.getServer();
		ZonedDateTime restart = manualRestart(Instant.now(), minutes);
		if (!startBackup(server, restart, true)) {
			source.sendFailure(Component.literal("Не удалось запустить бекап; перезапуск не назначен."));
			return 0;
		}
		schedule = new Schedule(restart, restart.toInstant(), true);
		previousRemainingMillis = Duration.between(Instant.now(), restart.toInstant()).toMillis();
		backupDelayAnnounced = false;
		long initialSeconds = (long) minutes * 60;
		if (initialSeconds == 1800 || initialSeconds == 600 || initialSeconds == 180 || initialSeconds == 60) {
			sendWarning(server, initialSeconds);
		} else {
			sendTitle(server, "Перезапуск через " + minutes + " " + minuteWord(minutes), 60);
		}
		source.sendSuccess(() -> Component.literal("Бекап запущен; перезапуск через "
				+ minutes + " " + minuteWord(minutes) + "."), true);
		Lg2.LOGGER.info("Manual restart scheduled for {} (Moscow); backup started immediately", restart);
		return 1;
	}

	static ZonedDateTime manualRestart(Instant now, int minutes) {
		if (minutes < 1) {
			throw new IllegalArgumentException("Restart delay must be positive");
		}
		return now.atZone(MOSCOW).plusMinutes(minutes);
	}

	private static String minuteWord(int minutes) {
		int lastTwo = minutes % 100;
		if (lastTwo >= 11 && lastTwo <= 14) {
			return "минут";
		}
		return switch (minutes % 10) {
			case 1 -> "минуту";
			case 2, 3, 4 -> "минуты";
			default -> "минут";
		};
	}

	static ZonedDateTime nextRestart(Instant now, int hour) {
		ZonedDateTime local = now.atZone(MOSCOW);
		ZonedDateTime next = local.toLocalDate().atStartOfDay(MOSCOW).plusHours(Math.max(0, Math.min(23, hour)));
		return next.toInstant().isAfter(now) ? next : next.plusDays(1);
	}

	static long dueWarningSeconds(long previousMillis, long currentMillis) {
		if (currentMillis <= 0) {
			return -1;
		}
		long due = -1;
		for (long seconds : WARN_AT_SECONDS) {
			long thresholdMillis = seconds * 1000;
			if (previousMillis > thresholdMillis && currentMillis <= thresholdMillis) {
				due = seconds;
			}
		}
		return due;
	}

	private static void tick(MinecraftServer server) {
		if (schedule == null || restartRequested) {
			return;
		}
		Instant now = Instant.now();
		long remainingMillis = Duration.between(now, schedule.restart().toInstant()).toMillis();
		long warning = dueWarningSeconds(previousRemainingMillis, remainingMillis);
		if (warning > 0) {
			sendWarning(server, warning);
		}
		previousRemainingMillis = remainingMillis;

		if (!schedule.backupStarted() && !now.isBefore(schedule.backupAt())) {
			schedule = schedule.withBackupStarted();
			startBackup(server, schedule.restart(), false);
		}
		if (remainingMillis <= 0 && backupRunning && !backupDelayAnnounced) {
			backupDelayAnnounced = true;
			sendTitle(server, "Ожидание завершения резервной копии", 100);
		}
		if (remainingMillis <= 0 && !backupRunning) {
			restartRequested = true;
			Lg2.LOGGER.info("Scheduled Moscow restart: stopping server for service manager restart");
			server.halt(false);
		}
	}

	private static void sendWarning(MinecraftServer server, long seconds) {
		String remaining = switch ((int) seconds) {
			case 1800 -> "30 минут";
			case 600 -> "10 минут";
			case 180 -> "3 минуты";
			case 60 -> "1 минуту";
			case 30 -> "30 секунд";
			case 10 -> "10 секунд";
			case 5 -> "5 секунд";
			case 4 -> "4 секунды";
			case 3 -> "3 секунды";
			case 2 -> "2 секунды";
			default -> "1 секунду";
		};
		sendTitle(server, "Перезапуск через " + remaining, seconds <= 5 ? 20 : 60);
	}

	private static void sendTitle(MinecraftServer server, String text, int stayTicks) {
		Component message = Component.literal(text).withStyle(ChatFormatting.RED);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player.connection != null) {
				player.connection.send(new ClientboundSetTitlesAnimationPacket(0, stayTicks, 5));
				player.connection.send(new ClientboundSetTitleTextPacket(message));
			}
		}
	}

	private static boolean startBackup(MinecraftServer server, ZonedDateTime restart, boolean manual) {
		try {
			server.saveEverything(false, true, true);
			Path world = server.getWorldPath(LevelResource.ROOT).toRealPath();
			Path gameDir = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
			Path backupDir = world.getParent().resolve("backups");
			backupRunning = true;
			Thread worker = new Thread(() -> {
				try {
					Path archive = writeBackup(world, gameDir, backupDir, restart, manual);
					Lg2.LOGGER.info("{} backup completed: {}", manual ? "Manual" : "Daily", archive);
				} catch (Exception e) {
					Lg2.LOGGER.error("{} backup failed; scheduled restart will still proceed", manual ? "Manual" : "Daily", e);
				} finally {
					backupRunning = false;
				}
			}, "LG2-daily-backup");
			worker.setDaemon(true);
			worker.start();
			return true;
		} catch (Exception e) {
			Lg2.LOGGER.error("Could not start {} backup", manual ? "manual" : "daily", e);
			return false;
		}
	}

	static Path writeBackup(Path world, Path gameDir, Path backupDir, ZonedDateTime restart) throws IOException {
		return writeBackup(world, gameDir, backupDir, restart, false);
	}

	static Path writeBackup(Path world, Path gameDir, Path backupDir, ZonedDateTime restart, boolean manual) throws IOException {
		Files.createDirectories(backupDir);
		boolean posix = Files.getFileStore(backupDir).supportsFileAttributeView("posix");
		if (posix) {
			Files.setPosixFilePermissions(backupDir, Set.of(
					PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
		}
		pruneStalePartials(backupDir, Instant.now());
		String name = manual
				? BACKUP_PREFIX + "manual-" + MANUAL_STAMP.format(restart) + "-msk-"
						+ UUID.randomUUID().toString().replace("-", "") + ".zip"
				: BACKUP_PREFIX + restart.toLocalDate() + "-" + String.format("%02d", restart.getHour()) + "-msk.zip";
		Path archive = backupDir.resolve(name);
		if (!Files.exists(archive)) {
			Path partial = backupDir.resolve(name + ".partial");
			Files.deleteIfExists(partial);
			try {
				try (var output = Files.newOutputStream(partial);
						ZipOutputStream zip = new ZipOutputStream(output)) {
					if (posix) {
						Files.setPosixFilePermissions(partial, Set.of(
								PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
					}
					zip.setLevel(Deflater.BEST_SPEED);
					addTree(zip, world, "world/");
					Path releaseConfig = gameDir.resolve("config");
					addTree(zip, releaseConfig, "config/");
					Path persistentConfig = world.getParent().resolve("config");
					if (Files.isDirectory(persistentConfig)
							&& (!Files.isDirectory(releaseConfig) || !Files.isSameFile(releaseConfig, persistentConfig))) {
						addTree(zip, persistentConfig, "persistent-config/");
					}
					for (String file : SERVER_FILES) {
						addFile(zip, gameDir.resolve(file), file);
					}
				}
				try {
					Files.move(partial, archive, StandardCopyOption.ATOMIC_MOVE);
				} catch (java.nio.file.AtomicMoveNotSupportedException e) {
					Files.move(partial, archive);
				}
			} finally {
				Files.deleteIfExists(partial);
			}
		}
		pruneOldBackups(backupDir, Instant.now());
		return archive;
	}

	private static void addTree(ZipOutputStream zip, Path root, String prefix) throws IOException {
		if (!Files.isDirectory(root)) {
			return;
		}
		Files.walkFileTree(root, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
				if (attributes.isRegularFile() && !Files.isSymbolicLink(file)) {
					addFile(zip, file, prefix + root.relativize(file).toString().replace('\\', '/'));
				}
				return FileVisitResult.CONTINUE;
			}
		});
	}

	private static void addFile(ZipOutputStream zip, Path file, String name) throws IOException {
		if (!Files.isRegularFile(file)) {
			return;
		}
		zip.putNextEntry(new ZipEntry(name));
		Files.copy(file, zip);
		zip.closeEntry();
	}

	static void pruneOldBackups(Path backupDir, Instant now) throws IOException {
		Instant cutoff = now.minus(RETENTION);
		try (var files = Files.list(backupDir)) {
			for (Path file : files.toList()) {
				String name = file.getFileName().toString();
				if (BACKUP_FILE.matcher(name).matches()
						&& Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
						&& Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) {
					Files.delete(file);
				}
			}
		}
	}

	private static void pruneStalePartials(Path backupDir, Instant now) throws IOException {
		Instant cutoff = now.minus(Duration.ofHours(6));
		try (var files = Files.list(backupDir)) {
			for (Path file : files.toList()) {
				String name = file.getFileName().toString();
				if (name.endsWith(".partial")
						&& BACKUP_FILE.matcher(name.substring(0, name.length() - ".partial".length())).matches()
						&& Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
						&& Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) {
					Files.delete(file);
				}
			}
		}
	}

	private record Schedule(ZonedDateTime restart, Instant backupAt, boolean backupStarted) {
		Schedule(ZonedDateTime restart) {
			this(restart, restart.toInstant().minus(Duration.ofMinutes(30)), false);
		}

		Schedule withBackupStarted() {
			return new Schedule(restart, backupAt, true);
		}
	}
}
