package com.lostglade.mixin.client;

import com.lostglade.client.maprender.YandexMapRenderWorld;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import net.minecraft.client.Camera;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps vanilla terrain/entities while removing transient atmosphere/effect passes from map scenes. */
@Mixin(LevelRenderer.class)
public abstract class YandexMapLevelEffectsMixin {
	@Shadow private ClientLevel level;

	@Inject(method = "addParticlesPass", at = @At("HEAD"), cancellable = true)
	private void lg2$skipMapParticles(FrameGraphBuilder graph, GpuBufferSlice fog, CallbackInfo ci) {
		if (this.level instanceof YandexMapRenderWorld) ci.cancel();
	}

	@Inject(method = "addCloudsPass", at = @At("HEAD"), cancellable = true)
	private void lg2$skipMapClouds(
			FrameGraphBuilder graph,
			CloudStatus status,
			Vec3 cameraPos,
			long gameTime,
			float partialTick,
			int cloudColor,
			float cloudHeight,
			CallbackInfo ci
	) {
		if (this.level instanceof YandexMapRenderWorld) ci.cancel();
	}

	@Inject(method = "addWeatherPass", at = @At("HEAD"), cancellable = true)
	private void lg2$skipMapWeather(FrameGraphBuilder graph, GpuBufferSlice fog, CallbackInfo ci) {
		if (this.level instanceof YandexMapRenderWorld) ci.cancel();
	}

	@Inject(method = "addSkyPass", at = @At("HEAD"), cancellable = true)
	private void lg2$skipMapSky(FrameGraphBuilder graph, Camera camera, GpuBufferSlice fog, CallbackInfo ci) {
		if (this.level instanceof YandexMapRenderWorld) ci.cancel();
	}
}
