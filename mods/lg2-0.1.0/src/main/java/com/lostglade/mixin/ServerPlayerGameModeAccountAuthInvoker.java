package com.lostglade.mixin;

import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Pre-play auth setup must not broadcast PlayerInfo before ServerPlayer.connection exists. */
@Mixin(ServerPlayerGameMode.class)
public interface ServerPlayerGameModeAccountAuthInvoker {
	@Invoker("setGameModeForPlayer")
	void lg2$setGameModeForPlayer(GameType gameType, GameType previousGameType);
}
