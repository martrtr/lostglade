package com.lostglade.mixin.client;

import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BlockEntityRenderDispatcher.class)
public interface BlockEntityCameraStateAccessor {
	@Accessor("cameraPos") Vec3 lg2$getCameraPos();
	@Accessor("cameraPos") void lg2$setCameraPos(Vec3 position);
}
