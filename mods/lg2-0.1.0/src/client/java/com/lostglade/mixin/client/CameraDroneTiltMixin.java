package com.lostglade.mixin.client;

import com.lostglade.client.DroneCameraTilt;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Ports the camera-roll part of fpv_expirience into the LG2 client mod. */
@Mixin(Camera.class)
public abstract class CameraDroneTiltMixin {
	@Inject(method = "setRotation", at = @At("TAIL"))
	private void lg2$applyDroneBank(float yaw, float pitch, CallbackInfo callbackInfo) {
		Camera camera = (Camera) (Object) this;
		float bankRadians = DroneCameraTilt.bankRadians(camera.entity());
		DroneCameraTilt.applyPlayerBank(camera, bankRadians);
	}
}
