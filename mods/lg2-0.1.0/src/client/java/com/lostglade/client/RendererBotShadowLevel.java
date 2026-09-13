package com.lostglade.client;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.AABB;

/** A server-fed scene, never a second simulation of the local player's input or physics. */
public final class RendererBotShadowLevel extends ClientLevel {
	private final LevelRenderer sceneRenderer;
	private ParticleEngine sceneParticles;
	private int sceneViewDistance;
	private boolean tickDayTime;
	private net.minecraft.client.Camera camera;
	private final Map<UUID, PlayerInfo> playerProfiles = new HashMap<>();
	public RendererBotShadowLevel(ClientPacketListener connection, ClientLevelData data,
			ResourceKey<Level> dimension, Holder<DimensionType> dimensionType,
			int viewDistance, int simulationDistance, LevelRenderer renderer,
			boolean debug, long seed, int seaLevel) {
		super(connection, data, dimension, dimensionType, viewDistance, simulationDistance,
				renderer, debug, seed, seaLevel);
		this.sceneRenderer = renderer;
		this.sceneViewDistance = viewDistance;
	}

	public LevelRenderer sceneRenderer() { return sceneRenderer; }
	public ParticleEngine sceneParticles() { return sceneParticles; }
	public void setSceneParticles(ParticleEngine particles) { sceneParticles = particles; }
	public int sceneViewDistance() { return sceneViewDistance; }
	public void setSceneViewDistance(int distance) { sceneViewDistance = distance; }
	public boolean ticksDayTime() { return tickDayTime; }
	public Map<UUID, PlayerInfo> playerProfiles() { return playerProfiles; }
	public net.minecraft.client.Camera camera() { return camera; }
	public void setCamera(net.minecraft.client.Camera camera) { this.camera = camera; }

	public void addPlayer(net.minecraft.network.protocol.game.ClientboundAddEntityPacket packet) {
		PlayerInfo info = playerProfiles.get(packet.getUUID());
		if (info == null) {
			throw new IllegalStateException("Camera player profile missing: " + packet.getUUID());
		}
		RemotePlayer player = new RemotePlayer(this, info.getProfile()) {
			@Override
			protected PlayerInfo getPlayerInfo() {
				return playerProfiles.get(getUUID());
			}
		};
		player.recreateFromPacket(packet);
		addEntity(player);
	}

	@Override
	public void setTimeFromServer(long gameTime, long dayTime, boolean tickDayTime) {
		super.setTimeFromServer(gameTime, dayTime, tickDayTime);
		this.tickDayTime = tickDayTime;
		environmentAttributes().invalidateTickCache();
	}

	@Override
	public void queueLightUpdate(Runnable update) {
		// Vanilla's queued callbacks capture the shared connection, not its level.
		// Restore scene ownership when they eventually run, even on a later frame.
		super.queueLightUpdate(() -> RendererBotShadowWorldManager.runForLevel(this, update));
	}

	@Override
	public void addEntity(Entity entity) {
		// IDs and UUIDs may match the live world; object identity and ownership must not.
		if (entity instanceof LocalPlayer || entity.level() != this) {
			throw new IllegalArgumentException("A camera scene cannot own a gameplay entity");
		}
		super.addEntity(entity);
	}

	@Override
	public List<Entity> getPushableEntities(Entity entity, AABB bounds) {
		// Vanilla returns Minecraft.player here, even when it belongs to ANOTHER level.
		// Remote entities are server authoritative: no client-side collision response.
		return List.of();
	}

	@Override
	public boolean shouldTickDeath(Entity entity) {
		return hasChunk(entity.chunkPosition().x, entity.chunkPosition().z);
	}
}
