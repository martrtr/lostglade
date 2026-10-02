package com.lostglade.server;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.ArrayList;
import java.util.List;

public final class RaceFixRegressionTest {
	public static void main(String[] args) throws Exception {
		waterSpeedMatchesFloor();
		packetsStayOrderedAndDirectional();
		overflowNeverLosesPackets();
		helmetsKeepTheirArmor();
		markDefenseTimerUsesWholeSeconds();
		manaUsesNativeBossBar();
		System.out.println("Race fixes: swimming, bidirectional latency, FIFO, lossless overflow and helmet attributes passed");
	}

	private static void manaUsesNativeBossBar() throws Exception {
		check(NecromancerStockSystem.manaBarProgress(50, 100) == 0.5F, "half mana not half bossbar");
		check(NecromancerStockSystem.manaBarProgress(0, 100) == 0, "empty mana not empty bossbar");
		check(NecromancerStockSystem.manaBarProgress(200, 100) == 1, "progress exceeds full bar");
		check(NecromancerStockSystem.manaBarProgress(-1, 100) == 0, "negative progress");
		check(NecromancerStockSystem.manaBarProgress(50, 0) == 0, "invalid maximum");
		var assets = java.nio.file.Path.of("src/main/resources/assets");
		var font = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(
				assets.resolve("lg2/font/necromancer_mana_bar.json"))).getAsJsonObject();
		var providers = font.getAsJsonArray("providers");
		check(providers.size() == 2, "mana must not use an atlas of progress glyphs");
		var bitmap = providers.get(0).getAsJsonObject();
		check(bitmap.getAsJsonArray("chars").size() == 1
				&& bitmap.getAsJsonArray("chars").get(0).getAsString().equals("\uea00"), "frame needs exactly one glyph");
		var frame = javax.imageio.ImageIO.read(assets.resolve("lg2/textures/font/necromancer_mana_bar.png").toFile());
		check(frame.getWidth() == 218 && frame.getHeight() == 66, "frame became a fill atlas");
		for (String name : List.of("white_background", "white_progress")) {
			var sprite = javax.imageio.ImageIO.read(assets.resolve("minecraft/textures/gui/sprites/boss_bar/" + name + ".png").toFile());
			check(sprite.getWidth() == 182 && sprite.getHeight() == 5, "wrong native bossbar dimensions");
		}
	}

	private static void markDefenseTimerUsesWholeSeconds() {
		check(ServerRaceSystem.formatMarkDefenseTimer(101).equals("6s"), "partial second must round up");
		check(ServerRaceSystem.formatMarkDefenseTimer(100).equals("5s"), "whole second changed");
		check(ServerRaceSystem.formatMarkDefenseTimer(1).equals("1s"), "active timer displayed zero");
		check(ServerRaceSystem.formatMarkDefenseTimer(0).equals("0s"), "expired timer not zero");
		check(ServerRaceSystem.formatMarkDefenseTimer(-20).equals("0s"), "negative timer");
	}

	private static void helmetsKeepTheirArmor() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		ItemStack helmet = new ItemStack(Items.DIAMOND_HELMET);
		helmet.setDamageValue(42);
		ItemStack visual = new ItemStack(Items.CARVED_PUMPKIN);
		CartelGasMaskVisualSystem.copyHelmetAttributes(helmet, visual);
		check(helmet.get(DataComponents.ATTRIBUTE_MODIFIERS).equals(visual.get(DataComponents.ATTRIBUTE_MODIFIERS)), "mask lost armor modifiers");
		check(helmet.is(Items.DIAMOND_HELMET) && helmet.getDamageValue() == 42, "visual rewrite changed real helmet");
		check(!visual.isEnchanted(), "mask was enchanted");
	}

	private static void waterSpeedMatchesFloor() {
		for (boolean sprint : new boolean[]{false, true}) {
			for (boolean dolphins : new boolean[]{false, true}) {
				for (double speed : new double[]{0.05, 0.1, 0.13, 0.25, 0.5}) {
					double attribute = KilkaWaterSpeed.swimmingAttribute(speed, sprint, dolphins);
					double swimDrag = dolphins ? 0.96 : ((sprint ? 0.9 : 0.8) + 0.54600006) * 0.5;
					double floorDrag = dolphins ? 0.96 : 0.54600006;
					double floor = 0, swimming = 0;
					for (int tick = 0; tick < 1000; tick++) {
						floor = (floor + speed) * floorDrag;
						swimming = (swimming + 0.02 + (attribute - 0.02) * 0.5) * swimDrag;
					}
					check(Math.abs((floor + speed) - (swimming + 0.02 + (attribute - 0.02) * 0.5)) < 1e-8,
							"swimming differs from floor, sprint=" + sprint + ", dolphins=" + dolphins);
				}
			}
		}
	}

	private static void packetsStayOrderedAndDirectional() {
		var queue = new OrderedPacketDelayQueue<String>(10);
		List<String> delivered = new ArrayList<>();
		queue.enqueue("player", true, 0, 10, () -> delivered.add("move"));
		queue.enqueue("player", true, 1, 2, () -> delivered.add("place"));
		queue.enqueue("player", false, 0, 2, () -> delivered.add("update"));
		queue.drainDue(2).forEach(entry -> entry.action().run());
		check(delivered.equals(List.of("update")), "outbound blocked by inbound");
		queue.drainDue(9).forEach(entry -> entry.action().run());
		check(delivered.size() == 1, "inbound released early");
		queue.drainDue(10).forEach(entry -> entry.action().run());
		check(delivered.equals(List.of("update", "move", "place")), "placement overtook movement");
		queue.enqueue("player", false, 10, 5, () -> delivered.add("ack"));
		check(queue.drainDue(14).isEmpty(), "reply bypassed outgoing latency");
		queue.drainOwner("player").forEach(entry -> entry.action().run());
		check(delivered.getLast().equals("ack") && queue.drainDue(100).isEmpty(), "end left stale packets");
	}

	private static void overflowNeverLosesPackets() {
		var queue = new OrderedPacketDelayQueue<String>(3);
		List<Integer> delivered = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			int sequence = i;
			queue.enqueue("player", true, 0, 5, () -> delivered.add(sequence)).forEach(entry -> entry.action().run());
		}
		queue.drainDue(5).forEach(entry -> entry.action().run());
		check(delivered.size() == 100, "overflow dropped packets");
		for (int i = 0; i < 100; i++) check(delivered.get(i) == i, "overflow reordered packets");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
