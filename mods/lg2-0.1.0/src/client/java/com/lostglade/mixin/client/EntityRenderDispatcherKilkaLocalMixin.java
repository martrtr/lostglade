package com.lostglade.mixin.client;

import com.lostglade.client.KilkaClientForm;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.SalmonRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.fish.Salmon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherKilkaLocalMixin {
	@Unique private Salmon lg2$localSalmon;
	@Unique private ClientLevel lg2$salmonLevel;

	@Inject(method = "extractEntity", at = @At("HEAD"), cancellable = true)
	private void lg2$renderLocalPlayerAsSalmon(Entity entity, float partialTick,
			CallbackInfoReturnable<EntityRenderState> cir) {
		Minecraft client = Minecraft.getInstance();
		if (!KilkaClientForm.isActive() || entity != client.player || client.level == null) {
			return;
		}
		if (lg2$localSalmon == null || lg2$salmonLevel != client.level) {
			lg2$salmonLevel = client.level;
			lg2$localSalmon = new Salmon(EntityType.SALMON, client.level);
		}
		Salmon salmon = lg2$localSalmon;
		salmon.setPos(entity.getX(), entity.getY(), entity.getZ());
		salmon.xo = entity.xo;
		salmon.yo = entity.yo;
		salmon.zo = entity.zo;
		salmon.setYRot(entity.getYRot());
		salmon.setXRot(entity.getXRot());
		salmon.setYBodyRot(entity.getYRot());
		salmon.setYHeadRot(entity.getYRot());
		salmon.yRotO = entity.yRotO;
		salmon.xRotO = entity.xRotO;
		salmon.yBodyRotO = entity.yRotO;
		salmon.setDeltaMovement(entity.getDeltaMovement());
		salmon.setOnGround(entity.onGround());
		if (entity instanceof LivingEntity living) {
			salmon.hurtTime = living.hurtTime;
			salmon.hurtDuration = living.hurtDuration;
		}
		salmon.tickCount = entity.tickCount;
		SalmonRenderState state = (SalmonRenderState) ((EntityRenderDispatcher) (Object) this)
				.extractEntity(salmon, partialTick);
		state.isInWater = entity.isInWater();
		state.x = Mth.lerp(partialTick, entity.xo, entity.getX());
		state.y = Mth.lerp(partialTick, entity.yo, entity.getY());
		state.z = Mth.lerp(partialTick, entity.zo, entity.getZ());
		state.isInvisible = false;
		state.isInvisibleToPlayer = false;
		state.nameTag = null;
		cir.setReturnValue(state);
	}
}
