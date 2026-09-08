package com.lostglade.server;

import me.neznamy.tab.shared.chat.component.TabComponent;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

final class ServerTabPlaceholders {
	private static final ZoneId MOSCOW_ZONE = ZoneId.of("Europe/Moscow");
	private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
	private static final int TAB_LOGO_GLYPHS_BASE = 0xF100;
	private static final int TAB_LOGO_FRAME_COUNT = 48;
	private static final int TAB_LOGO_FRAME_TICKS = 2;
	private static final int TPS_SAMPLE_INTERVAL_TICKS = 20;
	private static final long NANOS_PER_SECOND = 1_000_000_000L;
	private static final long MAX_TPS_SAMPLE_NANOS = 5_000_000_000L;
	private static final BigDecimal TPS_SAMPLE_BASE = BigDecimal.valueOf(NANOS_PER_SECOND)
			.multiply(BigDecimal.valueOf(TPS_SAMPLE_INTERVAL_TICKS));
	private static final RollingTpsAverage TPS_ONE_MINUTE = new RollingTpsAverage(60);
	private static long previousTpsSampleNanos = Long.MIN_VALUE;

	private ServerTabPlaceholders() {
	}

	static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			resetTpsMeasurement();
			refreshAllHeaders(server);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> resetTpsMeasurement());
		ServerTickEvents.START_SERVER_TICK.register(ServerTabPlaceholders::recordTickStart);
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if ((server.getTickCount() % TAB_LOGO_FRAME_TICKS) == 0) {
				refreshAllHeaders(server);
			}
		});
		ServerTabIntegration.registerPlayerLoadHandler(player -> {
			MinecraftServer server = player == null || player.level() == null ? null : player.level().getServer();
			if (server != null) {
				refreshHeader(server, player);
			}
		});
	}

	private static void refreshAllHeaders(MinecraftServer server) {
		if (server == null) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			refreshHeader(server, player);
		}
	}

	private static void refreshHeader(MinecraftServer server, ServerPlayer player) {
		if (server == null || player == null) {
			return;
		}

		TabComponent header = TabComponent.fromColoredText(buildHeaderText(server));
		TabComponent footer = TabComponent.fromColoredText(buildFooterText(player));
		applyNoShadow(header);
		applyNoShadow(footer);
		ServerTabIntegration.setHeaderFooter(player, header, footer);
	}

	private static String buildHeaderText(MinecraftServer server) {
		String dateTime = toSmallFont(DATE_TIME_FORMATTER.format(ZonedDateTime.now(MOSCOW_ZONE)));
		return "\n"
				+ "§f" + tabLogoGlyph(server) + "\n\n"
				+ "§7" + dateTime;
	}

	private static String buildFooterText(ServerPlayer player) {
		return "\n§a\uED81 §7" + formatPing(player)
				+ " §8• §a\uED82 §7" + formatTps();
	}

	private static String tabLogoGlyph(MinecraftServer server) {
		long tick = server == null ? 0L : server.getTickCount();
		int frame = (int) Math.floorMod(tick / TAB_LOGO_FRAME_TICKS, TAB_LOGO_FRAME_COUNT);
		return String.valueOf((char) (TAB_LOGO_GLYPHS_BASE + frame));
	}

	private static String formatPing(ServerPlayer player) {
		if (player == null || player.connection == null) {
			return "-";
		}
		int latencyMillis = player.connection.latency();
		return latencyMillis < 0 ? "-" : latencyMillis + " мс";
	}

	/**
	 * Uses the same one-minute, time-weighted rolling TPS calculation as TabTPS:
	 * samples are taken every 20 ticks, so brief lag spikes are represented without
	 * the noisy per-tick averaging that used to be used here.
	 */
	private static String formatTps() {
		double tps = Math.min(20.0D, TPS_ONE_MINUTE.average());
		return String.format(Locale.ROOT, "%.2f", tps);
	}

	private static void recordTickStart(MinecraftServer server) {
		if (server == null || server.getTickCount() % TPS_SAMPLE_INTERVAL_TICKS != 0) {
			return;
		}

		long now = System.nanoTime();
		long previous = previousTpsSampleNanos;
		previousTpsSampleNanos = now;
		if (previous == Long.MIN_VALUE) {
			return;
		}

		long elapsedNanos = now - previous;
		if (elapsedNanos <= 0L || elapsedNanos > MAX_TPS_SAMPLE_NANOS) {
			resetTpsMeasurement();
			previousTpsSampleNanos = now;
			return;
		}

		BigDecimal currentTps = TPS_SAMPLE_BASE.divide(BigDecimal.valueOf(elapsedNanos), 30, RoundingMode.HALF_UP);
		TPS_ONE_MINUTE.add(currentTps, elapsedNanos);
	}

	private static void resetTpsMeasurement() {
		previousTpsSampleNanos = Long.MIN_VALUE;
		TPS_ONE_MINUTE.reset();
	}

	private static void applyNoShadow(TabComponent component) {
		if (component == null) {
			return;
		}
		component.getModifier().setShadowColor(0x00000000);
		for (TabComponent extra : component.getExtra()) {
			applyNoShadow(extra);
		}
	}

	private static String toSmallFont(String value) {
		StringBuilder builder = new StringBuilder(value.length());
		for (int index = 0; index < value.length(); index++) {
			builder.append(mapSmallFontChar(value.charAt(index)));
		}
		return builder.toString();
	}

	private static char mapSmallFontChar(char character) {
		return switch (character) {
			case '0' -> '\uED90';
			case '1' -> '\uED91';
			case '2' -> '\uED92';
			case '3' -> '\uED93';
			case '4' -> '\uED94';
			case '5' -> '\uED95';
			case '6' -> '\uED96';
			case '7' -> '\uED97';
			case '8' -> '\uED98';
			case '9' -> '\uED99';
			case '.' -> '\uED9A';
			case ':' -> '\uED9B';
			case '/' -> '\uED9C';
			case '-' -> '\uED9D';
			default -> character;
		};
	}

	/** Minimal embedded form of TabTPS' MIT-licensed time-weighted rolling average. */
	private static final class RollingTpsAverage {
		private final BigDecimal[] samples;
		private final long[] durations;
		private long totalDurationNanos;
		private BigDecimal weightedTotal;
		private int nextIndex;

		private RollingTpsAverage(int size) {
			this.samples = new BigDecimal[size];
			this.durations = new long[size];
			this.weightedTotal = BigDecimal.ZERO;
			reset();
		}

		private void reset() {
			totalDurationNanos = samples.length * NANOS_PER_SECOND;
			weightedTotal = BigDecimal.valueOf(20L)
					.multiply(BigDecimal.valueOf(NANOS_PER_SECOND))
					.multiply(BigDecimal.valueOf(samples.length));
			for (int index = 0; index < samples.length; index++) {
				samples[index] = BigDecimal.valueOf(20L);
				durations[index] = NANOS_PER_SECOND;
			}
			nextIndex = 0;
		}

		private void add(BigDecimal tps, long durationNanos) {
			totalDurationNanos -= durations[nextIndex];
			weightedTotal = weightedTotal.subtract(samples[nextIndex].multiply(BigDecimal.valueOf(durations[nextIndex])));
			samples[nextIndex] = tps;
			durations[nextIndex] = durationNanos;
			totalDurationNanos += durationNanos;
			weightedTotal = weightedTotal.add(tps.multiply(BigDecimal.valueOf(durationNanos)));
			nextIndex = (nextIndex + 1) % samples.length;
		}

		private double average() {
			return weightedTotal.divide(BigDecimal.valueOf(totalDurationNanos), 30, RoundingMode.HALF_UP).doubleValue();
		}
	}
}
