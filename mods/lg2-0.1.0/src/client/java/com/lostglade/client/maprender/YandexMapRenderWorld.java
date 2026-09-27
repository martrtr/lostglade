package com.lostglade.client.maprender;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.AABB;

import java.util.List;

/** Immutable-input client world owned by one Yandex map render job. */
public final class YandexMapRenderWorld extends ClientLevel {
	private final LevelRenderer sceneRenderer;
	private ParticleEngine sceneParticles;
	private net.minecraft.client.Camera camera;

	YandexMapRenderWorld(
			ClientPacketListener connection,
			ClientLevelData data,
			ResourceKey<Level> dimension,
			Holder<DimensionType> dimensionType,
			int viewDistance,
			LevelRenderer renderer,
			long seed,
			int seaLevel
	) {
		super(connection, data, dimension, dimensionType, viewDistance, 2, renderer, false, seed, seaLevel);
		this.sceneRenderer = renderer;
		setTimeFromServer(0L, 6000L, false);
		getLevelData().setRaining(false);
		setRainLevel(0.0F);
		setThunderLevel(0.0F);
		environmentAttributes().invalidateTickCache();
	}

	public LevelRenderer sceneRenderer() {
		return this.sceneRenderer;
	}

	public ParticleEngine sceneParticles() {
		return this.sceneParticles;
	}

	void setSceneParticles(ParticleEngine sceneParticles) {
		this.sceneParticles = sceneParticles;
	}

	public net.minecraft.client.Camera camera() {
		return this.camera;
	}

	void setCamera(net.minecraft.client.Camera camera) {
		this.camera = camera;
	}

	@Override
	public void setTimeFromServer(long gameTime, long dayTime, boolean tickDayTime) {
		// Map scenes deliberately ignore server time. Midday is the canonical
		// environment and is combined with the saved vanilla SKY/BLOCK light data.
		super.setTimeFromServer(0L, 6000L, false);
		environmentAttributes().invalidateTickCache();
	}

	@Override
	public void queueLightUpdate(Runnable update) {
		super.queueLightUpdate(() -> {
			try (var ignored = YandexMapRenderContext.enterWorld(this)) {
				update.run();
			}
		});
	}

	@Override
	public void addEntity(Entity entity) {
		if (!(entity instanceof Display.ItemDisplay) || entity.level() != this) {
			throw new IllegalArgumentException("Yandex map scene accepts only its own ItemDisplay entities");
		}
		super.addEntity(entity);
	}

	@Override
	public List<Entity> getPushableEntities(Entity entity, AABB bounds) {
		return List.of();
	}

	@Override
	public boolean shouldTickDeath(Entity entity) {
		return false;
	}
}
