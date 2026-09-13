package com.lostglade.mixin.client;

import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(GameRenderer.class)
public interface GameRendererRenderLevelInvoker {
	@Accessor("fogRenderer")
	FogRenderer lg2$getFogRenderer();

	@Accessor("guiRenderState")
	GuiRenderState lg2$getGuiRenderState();

	@Accessor("guiRenderer")
	GuiRenderer lg2$getGuiRenderer();

	@Invoker("getDarkenWorldAmount")
	float lg2$getDarkenWorldAmount(float partialTick);
}
