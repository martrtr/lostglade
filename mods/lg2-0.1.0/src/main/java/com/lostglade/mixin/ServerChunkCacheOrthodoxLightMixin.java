package com.lostglade.mixin;

import com.lostglade.server.OrthodoxLightSync;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheOrthodoxLightMixin {
	@Shadow
	public abstract Level getLevel();

	@Inject(method = "onLightUpdate", at = @At("TAIL"))
	private void lg2$syncOrthodoxLight(LightLayer layer, SectionPos section, CallbackInfo ci) {
		if (getLevel() instanceof ServerLevel level) OrthodoxLightSync.onLightUpdate(level, layer, section);
	}
}
