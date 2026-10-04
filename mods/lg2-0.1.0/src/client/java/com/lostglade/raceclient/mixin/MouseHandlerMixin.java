package com.lostglade.raceclient.mixin;

import com.lostglade.raceclient.RaceClientControls;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Inject(method = "onButton", at = @At("HEAD"))
	private void lg2RaceClient$trackMenuMouseButton(long window, MouseButtonInfo button, int action, CallbackInfo callbackInfo) {
		RaceClientControls.onMouseButton(button.button(), action);
	}
}
