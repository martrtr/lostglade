package com.lostglade.mixin.client;

import com.lostglade.client.KilkaClientForm;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.SalmonRenderState;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.fish.Salmon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(InventoryScreen.class)
public abstract class InventoryScreenKilkaMixin {
	@Unique private static ClientLevel lg2$inventoryLevel;
	@Unique private static Salmon lg2$inventorySalmon;

	@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
	private static void lg2$renderSalmonPreview(LivingEntity entity,
			CallbackInfoReturnable<EntityRenderState> cir) {
		Minecraft client = Minecraft.getInstance();
		if (!KilkaClientForm.isActive() || entity != client.player || client.level == null) {
			return;
		}
		if (lg2$inventorySalmon == null || lg2$inventoryLevel != client.level) {
			lg2$inventoryLevel = client.level;
			lg2$inventorySalmon = new Salmon(EntityType.SALMON, client.level);
		}
		lg2$inventorySalmon.setPos(entity.getX(), entity.getY(), entity.getZ());
		lg2$inventorySalmon.tickCount = entity.tickCount;
		SalmonRenderState state = (SalmonRenderState) client.getEntityRenderDispatcher()
				.extractEntity(lg2$inventorySalmon, 1.0F);
		state.isInWater = true;
		state.lightCoords = 15728880;
		state.shadowPieces.clear();
		state.outlineColor = 0;
		state.nameTag = null;
		cir.setReturnValue(state);
	}
}
