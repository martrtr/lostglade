package com.lostglade.server;

import dev.rvbsm.fsit.api.event.PassedUseEntityCallback;
import dev.rvbsm.fsit.entity.RideEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;

final class FsitKilkaSalmonBridge {
	private FsitKilkaSalmonBridge() {
	}

	static InteractionResult interact(ServerPlayer rider, ServerLevel level, ServerPlayer owner) {
		return PassedUseEntityCallback.EVENT.invoker().interact(rider, level, owner);
	}

	static void attachSeatsToSalmon(ServerPlayer owner, Entity salmon) {
		if (salmon == null || !salmon.isAlive()) return;
		// FSit mounts the seat on the hidden player; viewers need it on the visible fish.
		for (Entity passenger : java.util.List.copyOf(owner.getPassengers())) {
			if (passenger instanceof RideEntity) {
				passenger.startRiding(salmon, true, true);
			}
		}
	}
}
