package com.lostglade.mixin.client;

import com.lostglade.client.KilkaClientForm;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerKilkaFovMixin {
	@Unique private static final Identifier LG2_SALMON_AQUATIC_SPEED = Identifier.fromNamespaceAndPath("lg2", "kilka_salmon_aquatic_speed");
	@Unique private static final Identifier LG2_SALMON_LAND_SPEED = Identifier.fromNamespaceAndPath("lg2", "kilka_salmon_land_speed");

	@Inject(method = "getFieldOfViewModifier", at = @At("RETURN"), cancellable = true)
	private void lg2$keepHumanFovInSalmonForm(boolean firstPerson, float effectScale,
			CallbackInfoReturnable<Float> cir) {
		AbstractClientPlayer player = (AbstractClientPlayer) (Object) this;
		if (!KilkaClientForm.isActive() || player != Minecraft.getInstance().player
				|| firstPerson && player.isScoping()) {
			return;
		}
		AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
		float walkingSpeed = player.getAbilities().getWalkingSpeed();
		if (speed == null || walkingSpeed <= 0.0F) {
			return;
		}
		double currentSpeed = speed.getValue();
		double humanSpeed = currentSpeed;
		AttributeModifier aquatic = speed.getModifier(LG2_SALMON_AQUATIC_SPEED);
		if (aquatic != null && aquatic.operation() == AttributeModifier.Operation.ADD_VALUE) {
			humanSpeed -= aquatic.amount();
		}
		AttributeModifier land = speed.getModifier(LG2_SALMON_LAND_SPEED);
		if (land != null && land.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL
				&& 1.0D + land.amount() > 0.0D) {
			humanSpeed /= 1.0D + land.amount();
		}
		float bowFactor = 1.0F;
		if (player.isUsingItem() && player.getUseItem().is(Items.BOW)) {
			float draw = Math.min((float) player.getTicksUsingItem() / 20.0F, 1.0F);
			bowFactor -= Mth.square(draw) * 0.15F;
		}
		float flightFactor = player.getAbilities().flying ? 1.1F : 1.0F;
		float speedDifference = (float) (humanSpeed / 0.1D - currentSpeed / walkingSpeed);
		cir.setReturnValue(cir.getReturnValueF() + effectScale * flightFactor * bowFactor * speedDifference * 0.5F);
	}
}
