package com.lostglade.client.maprender;

import com.lostglade.mixin.client.SpriteAnimationStateAccessor;
import com.lostglade.mixin.client.TextureAtlasAnimationAccessor;
import com.lostglade.mixin.client.TextureManagerTexturesAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Temporarily presents the first configured frame of every animated atlas sprite
 * to one isolated Yandex Maps render. The live client animation clock is restored
 * immediately afterwards, so volunteer workers never globally pause or reset their
 * normal gameplay textures.
 */
final class YandexMapTextureAnimationFreeze implements AutoCloseable {
	private final List<AtlasSnapshot> atlases = new ArrayList<>();
	private boolean closed;

	private YandexMapTextureAnimationFreeze() {
	}

	static YandexMapTextureAnimationFreeze freezeFirstFrames(Minecraft client) {
		YandexMapTextureAnimationFreeze freeze = new YandexMapTextureAnimationFreeze();
		try {
			Set<TextureAtlas> seen = Collections.newSetFromMap(new IdentityHashMap<>());
			for (AbstractTexture texture : ((TextureManagerTexturesAccessor) client.getTextureManager()).lg2$getTexturesByPath().values()) {
				if (!(texture instanceof TextureAtlas atlas) || !seen.add(atlas)) continue;
				TextureAtlasAnimationAccessor atlasAccess = (TextureAtlasAnimationAccessor) atlas;
				List<SpriteContents.AnimationState> states = atlasAccess.lg2$getAnimatedTextureStates();
				if (states == null || states.isEmpty()) continue;

				List<StateSnapshot> snapshots = new ArrayList<>(states.size());
				for (SpriteContents.AnimationState state : states) {
					SpriteAnimationStateAccessor access = (SpriteAnimationStateAccessor) state;
					snapshots.add(new StateSnapshot(state, access.lg2$getFrame(), access.lg2$getSubFrame(), access.lg2$isDirty()));
					access.lg2$setFrame(0);
					access.lg2$setSubFrame(0);
					access.lg2$setDirty(true);
				}
				AtlasSnapshot snapshot = new AtlasSnapshot(atlasAccess, List.copyOf(snapshots));
				freeze.atlases.add(snapshot);
				atlasAccess.lg2$uploadAnimationFrames();
			}
			return freeze;
		} catch (Throwable failure) {
			try {
				freeze.close();
			} catch (Throwable restoreFailure) {
				failure.addSuppressed(restoreFailure);
			}
			if (failure instanceof RuntimeException runtime) throw runtime;
			if (failure instanceof Error error) throw error;
			throw new IllegalStateException("failed to freeze texture animation for Yandex Maps render", failure);
		}
	}

	@Override
	public void close() {
		if (this.closed) return;
		this.closed = true;
		Throwable firstFailure = null;
		for (int atlasIndex = this.atlases.size() - 1; atlasIndex >= 0; atlasIndex--) {
			AtlasSnapshot atlas = this.atlases.get(atlasIndex);
			try {
				// Force one redraw at each original phase. Afterwards restore the exact
				// dirty flags too, so this scoped render does not perturb the next tick.
				for (StateSnapshot snapshot : atlas.states()) {
					SpriteAnimationStateAccessor access = (SpriteAnimationStateAccessor) snapshot.state();
					access.lg2$setFrame(snapshot.frame());
					access.lg2$setSubFrame(snapshot.subFrame());
					access.lg2$setDirty(true);
				}
				atlas.atlas().lg2$uploadAnimationFrames();
				for (StateSnapshot snapshot : atlas.states()) {
					((SpriteAnimationStateAccessor) snapshot.state()).lg2$setDirty(snapshot.dirty());
				}
			} catch (Throwable failure) {
				if (firstFailure == null) firstFailure = failure;
				else firstFailure.addSuppressed(failure);
			}
		}
		if (firstFailure != null) {
			if (firstFailure instanceof RuntimeException runtime) throw runtime;
			if (firstFailure instanceof Error error) throw error;
			throw new IllegalStateException("failed to restore texture animation state after Yandex Maps render", firstFailure);
		}
	}

	private record AtlasSnapshot(TextureAtlasAnimationAccessor atlas, List<StateSnapshot> states) {
	}

	private record StateSnapshot(SpriteContents.AnimationState state, int frame, int subFrame, boolean dirty) {
	}
}
