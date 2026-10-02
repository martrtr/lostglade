package com.lostglade.mixin;

import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ServerPlayer.class)
public interface ServerPlayerAccountAuthAccessor {
	@Invoker("updatePlayerAttributes")
	void lg2$updatePlayerAttributes();
}
