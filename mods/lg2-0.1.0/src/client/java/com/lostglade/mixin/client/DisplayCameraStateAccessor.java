package com.lostglade.mixin.client;

import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Display.class)
public interface DisplayCameraStateAccessor {
	@Accessor("updateRenderState") boolean lg2$needsRenderStateUpdate();
	@Accessor("updateRenderState") void lg2$setNeedsRenderStateUpdate(boolean update);
	@Accessor("renderState") void lg2$setRenderState(Display.RenderState state);
	@Invoker("createFreshRenderState") Display.RenderState lg2$createFreshRenderState();
	@Invoker("updateRenderSubState") void lg2$updateRenderSubState(boolean interpolate, float progress);
}
