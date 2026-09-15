package com.lostglade.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityCartelNoSaveMixin {
	private static final String CARTEL_SUMMON_TAG = "lg2.cartel_summon";
	private static final String CARTEL_LAWYER_TAG = "lg2.cartel_lawyer";
	private static final String ANCIENT_UKR_CREDITOR_TAG = "lg2.ancient_ukr_creditor";
	private static final String CARTEL_LAWYER_MARKER_NAME = "lg2_cartel_lawyer_marker";
	private static final String MILK_MOUSE_SILVERFISH_TAG = "lg2.milk_mouse_silverfish";
	private static final String KILKA_SHNYAGA_BEACON_DISPLAY_TAG = "lg2.kilka_sea_beacon_link";
	private static final String ORTHODOX_ANGEL_WINGS_TAG = "lg2.orthodox_angel_wings";
	private static final String ORTHODOX_DIVINE_LIGHT_WAVE_TAG = "lg2.orthodox_divine_light_wave";
	private static final String ANCIENT_UKR_COLLECTOR_TAG = "lg2.ancient_ukr_collector";

	@Inject(method = "shouldBeSaved", at = @At("HEAD"), cancellable = true)
	private void lg2$preventSavingCartelTemporaryEntities(CallbackInfoReturnable<Boolean> cir) {
		Entity self = (Entity) (Object) this;
		boolean markedLawyer = self.getTags().contains(CARTEL_LAWYER_TAG)
				|| self.hasCustomName() && CARTEL_LAWYER_MARKER_NAME.equals(self.getCustomName().getString());
		if (self.getTags().contains(CARTEL_SUMMON_TAG) || markedLawyer || self.getTags().contains(ANCIENT_UKR_CREDITOR_TAG)
				|| self.getTags().contains(MILK_MOUSE_SILVERFISH_TAG)
				|| self.getTags().contains(KILKA_SHNYAGA_BEACON_DISPLAY_TAG)
				|| self.getTags().contains(ORTHODOX_ANGEL_WINGS_TAG)
				|| self.getTags().contains(ORTHODOX_DIVINE_LIGHT_WAVE_TAG)
				|| self.getTags().contains(ANCIENT_UKR_COLLECTOR_TAG)) {
			cir.setReturnValue(false);
		}
	}
}
