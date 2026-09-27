package com.lostglade.mixin.client;

import com.lostglade.client.maprender.YandexMapRenderClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

@Mixin(Minecraft.class)
public abstract class MinecraftYandexMapResourceReloadMixin {
	@Inject(method = "reloadResourcePacks()Ljava/util/concurrent/CompletableFuture;", at = @At("RETURN"))
	private void lg2$refreshMapRenderProfileAfterResourcesReloaded(
			CallbackInfoReturnable<CompletableFuture<Void>> callback
	) {
		Minecraft client = (Minecraft) (Object) this;
		CompletableFuture<Void> reload = callback.getReturnValue();
		if (reload != null) {
			reload.thenRun(() -> client.execute(YandexMapRenderClient::onResourcesReloaded));
		}
	}
}
