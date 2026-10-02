package com.lostglade.mixin;

import com.lostglade.server.AccountAuthSystem;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Keeps pre-auth human sessions out of vanilla command selectors. */
@Mixin(EntitySelector.class)
public abstract class EntitySelectorAccountAuthMixin {
    @Inject(method = "findPlayers", at = @At("RETURN"), cancellable = true)
    private void lg2$hidePendingPlayers(CommandSourceStack source, CallbackInfoReturnable<List<ServerPlayer>> cir) {
        cir.setReturnValue(cir.getReturnValue().stream()
                .filter(player -> !AccountAuthSystem.isPendingAuthentication(player.getUUID()))
                .toList());
    }

    @Inject(method = "findEntities", at = @At("RETURN"), cancellable = true)
    private void lg2$hidePendingPlayersFromEntities(CommandSourceStack source, CallbackInfoReturnable<List<? extends Entity>> cir) {
        cir.setReturnValue(cir.getReturnValue().stream()
                .filter(entity -> !(entity instanceof ServerPlayer player)
                        || !AccountAuthSystem.isPendingAuthentication(player.getUUID()))
                .toList());
    }
}
