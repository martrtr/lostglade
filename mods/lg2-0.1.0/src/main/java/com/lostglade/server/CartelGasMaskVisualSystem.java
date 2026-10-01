package com.lostglade.server;

import com.lostglade.item.ModItems;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Changes only client appearance; the real helmet remains equipped on the server. */
public final class CartelGasMaskVisualSystem {
	private static final int HEAD_MENU_SLOT = 5;
	private static final int HEAD_INVENTORY_SLOT = 39;

	private CartelGasMaskVisualSystem() {
	}

	public static Packet<?> rewrite(ServerPlayer receiver, Packet<?> packet) {
		if (receiver == null || packet == null) return packet;
		if (packet instanceof ClientboundBundlePacket bundle) {
			List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
			boolean changed = false;
			for (Packet<? super ClientGamePacketListener> nested : bundle.subPackets()) {
				if (nested instanceof ClientboundAddEntityPacket spawn) {
					packets.add(nested);
					ServerPlayer owner = maskedPlayer(receiver, spawn.getId());
					if (owner != null) {
						packets.add(headPacket(owner, mask(owner)));
						changed = true;
					}
					continue;
				}
				Packet<?> rewritten = rewrite(receiver, nested);
				@SuppressWarnings("unchecked")
				Packet<? super ClientGamePacketListener> gamePacket = (Packet<? super ClientGamePacketListener>) rewritten;
				packets.add(gamePacket);
				changed |= rewritten != nested;
			}
			return changed ? new ClientboundBundlePacket(packets) : packet;
		}
		if (packet instanceof ClientboundAddEntityPacket spawn) {
			ServerPlayer owner = maskedPlayer(receiver, spawn.getId());
			return owner == null ? packet : new ClientboundBundlePacket(List.of(spawn, headPacket(owner, mask(owner))));
		}
		if (packet instanceof ClientboundSetEquipmentPacket equipment) {
			ServerPlayer owner = maskedPlayer(receiver, equipment.getEntity());
			if (owner == null) return packet;
			List<Pair<EquipmentSlot, ItemStack>> slots = new ArrayList<>(equipment.getSlots().size() + 1);
			for (Pair<EquipmentSlot, ItemStack> entry : equipment.getSlots()) {
				if (entry.getFirst() != EquipmentSlot.HEAD) slots.add(entry);
			}
			slots.add(Pair.of(EquipmentSlot.HEAD, mask(owner)));
			return new ClientboundSetEquipmentPacket(equipment.getEntity(), slots);
		}
		if (!ServerRaceSystem.isCartelGasMaskVisualActive(receiver)) return packet;
		if (packet instanceof ClientboundSetPlayerInventoryPacket inventory && inventory.slot() == HEAD_INVENTORY_SLOT) {
			return new ClientboundSetPlayerInventoryPacket(inventory.slot(), mask(receiver));
		}
		if (packet instanceof ClientboundContainerSetSlotPacket slot
				&& slot.getContainerId() == receiver.inventoryMenu.containerId && slot.getSlot() == HEAD_MENU_SLOT) {
			return new ClientboundContainerSetSlotPacket(slot.getContainerId(), slot.getStateId(), slot.getSlot(), mask(receiver));
		}
		if (packet instanceof ClientboundContainerSetContentPacket content
				&& content.containerId() == receiver.inventoryMenu.containerId && content.items().size() > HEAD_MENU_SLOT) {
			List<ItemStack> items = new ArrayList<>(content.items());
			items.set(HEAD_MENU_SLOT, mask(receiver));
			return new ClientboundContainerSetContentPacket(content.containerId(), content.stateId(), items, content.carriedItem());
		}
		return packet;
	}

	private static ServerPlayer maskedPlayer(ServerPlayer receiver, int entityId) {
		if (!(receiver.level() instanceof ServerLevel level)
				|| !(level.getEntity(entityId) instanceof ServerPlayer owner)
				|| !ServerRaceSystem.isCartelGasMaskVisualActive(owner)
				|| ServerAbsoluteInvisibilitySystem.isActive(owner)
				|| ServerRaceSystem.isKilkaSalmonForm(owner)) return null;
		return owner;
	}

	private static ItemStack mask(ServerPlayer owner) {
		ItemStack visual = new ItemStack(ModItems.ANCIENT_UKR_GAS_MASK);
		ItemStack helmet = owner.getItemBySlot(EquipmentSlot.HEAD);
		copyHelmetAttributes(helmet, visual);
		return visual;
	}

	static void copyHelmetAttributes(ItemStack helmet, ItemStack visual) {
		var attributes = helmet.get(DataComponents.ATTRIBUTE_MODIFIERS);
		// The client also recalculates armor when equipment changes; retain those modifiers.
		if (!helmet.isEmpty() && !helmet.isBroken() && attributes != null) {
			visual.set(DataComponents.ATTRIBUTE_MODIFIERS, attributes);
		}
	}

	private static ClientboundSetEquipmentPacket headPacket(ServerPlayer owner, ItemStack head) {
		return new ClientboundSetEquipmentPacket(owner.getId(), List.of(Pair.of(EquipmentSlot.HEAD, head)));
	}

	public static void sync(ServerPlayer player) {
		if (player == null || player.connection == null || !(player.level() instanceof ServerLevel level)) return;
		ClientboundSetEquipmentPacket equipment = headPacket(player, player.getItemBySlot(EquipmentSlot.HEAD).copy());
		level.getChunkSource().chunkMap.sendToTrackingPlayers(player, equipment);
		player.connection.send(equipment);
		player.connection.send(player.getInventory().createInventoryUpdatePacket(HEAD_INVENTORY_SLOT));
		player.connection.send(new ClientboundContainerSetSlotPacket(player.inventoryMenu.containerId,
				player.inventoryMenu.getStateId(), HEAD_MENU_SLOT, player.getItemBySlot(EquipmentSlot.HEAD)));
	}
}
