package com.lostglade.mixin;

import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(PlayerList.class)
public interface PlayerListAccountAuthAccessor {
	@Invoker("updateEntireScoreboard")
	void lg2$updateEntireScoreboard(ServerScoreboard scoreboard, ServerPlayer player);
}
