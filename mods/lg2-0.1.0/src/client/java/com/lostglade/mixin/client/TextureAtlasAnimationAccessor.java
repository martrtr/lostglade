package com.lostglade.mixin.client;

import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

@Mixin(TextureAtlas.class)
public interface TextureAtlasAnimationAccessor {
	@Accessor("animatedTexturesStates")
	List<SpriteContents.AnimationState> lg2$getAnimatedTextureStates();

	@Invoker("uploadAnimationFrames")
	void lg2$uploadAnimationFrames();
}
