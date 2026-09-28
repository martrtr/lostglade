package com.lostglade.mixin.client;

import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SpriteContents.AnimationState.class)
public interface SpriteAnimationStateAccessor {
	@Accessor("frame")
	int lg2$getFrame();

	@Accessor("frame")
	void lg2$setFrame(int frame);

	@Accessor("subFrame")
	int lg2$getSubFrame();

	@Accessor("subFrame")
	void lg2$setSubFrame(int subFrame);

	@Accessor("isDirty")
	boolean lg2$isDirty();

	@Accessor("isDirty")
	void lg2$setDirty(boolean dirty);
}
