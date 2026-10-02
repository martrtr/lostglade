package com.lostglade.mixin;

import com.lostglade.server.AccountAuthSystem;
import com.lostglade.server.RendererBotPresenceSystem;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.config.PrepareSpawnTask;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Builds human ServerPlayer instances in the private auth dimension from the start.
 * The real spawn target is retained separately and used only after authentication.
 */
@Mixin(targets = "net.minecraft.server.network.config.PrepareSpawnTask$Ready")
public abstract class PrepareSpawnTaskAccountAuthMixin {
    @Shadow @Final @Mutable private ServerLevel spawnLevel;
    @Shadow @Final @Mutable private Vec3 spawnPosition;
    @Shadow @Final @Mutable private Vec2 spawnAngle;

    @Unique private ServerLevel lg2$realSpawnLevel;
    @Unique private Vec3 lg2$realSpawnPosition;
    @Unique private Vec2 lg2$realSpawnAngle;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void lg2$prepareAuthenticationSpawn(
            PrepareSpawnTask outer,
            ServerLevel originalLevel,
            Vec3 originalPosition,
            Vec2 originalAngle,
            CallbackInfo ci
    ) {
        this.lg2$realSpawnLevel = originalLevel;
        this.lg2$realSpawnPosition = originalPosition;
        this.lg2$realSpawnAngle = originalAngle;

        ServerLevel authLevel = originalLevel.getServer().getLevel(AccountAuthSystem.AUTH_LIMBO_LEVEL);
        if (authLevel != null) {
            this.spawnLevel = authLevel;
            this.spawnPosition = AccountAuthSystem.authenticationSpawnPosition();
            this.spawnAngle = new Vec2(0.0F, 0.0F);
        }
    }

    @Inject(method = "spawn", at = @At("HEAD"))
    private void lg2$selectSpawnForConnection(
            Connection connection,
            CommonListenerCookie cookie,
            CallbackInfoReturnable<net.minecraft.server.level.ServerPlayer> cir
    ) {
        if (RendererBotPresenceSystem.isRendererBotConnection(cookie.gameProfile().name(), connection)) {
            this.spawnLevel = this.lg2$realSpawnLevel;
            this.spawnPosition = this.lg2$realSpawnPosition;
            this.spawnAngle = this.lg2$realSpawnAngle;
            return;
        }

        AccountAuthSystem.stageAuthenticationReturnTarget(
                connection,
                this.lg2$realSpawnLevel,
                this.lg2$realSpawnPosition,
                this.lg2$realSpawnAngle
        );
    }

    @Redirect(
            method = "spawn",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;waitForEntities(Lnet/minecraft/world/level/ChunkPos;I)V"
            )
    )
    private void lg2$deferRealWorldSpawnWarmup(
            ServerLevel level,
            ChunkPos chunkPos,
            int radius,
            Connection connection,
            CommonListenerCookie cookie
    ) {
        if (RendererBotPresenceSystem.isRendererBotConnection(cookie.gameProfile().name(), connection)) {
            level.waitForEntities(chunkPos, radius);
        }
    }
}
