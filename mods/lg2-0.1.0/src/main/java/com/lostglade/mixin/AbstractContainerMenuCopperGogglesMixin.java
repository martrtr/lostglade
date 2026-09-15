package com.lostglade.mixin;

import com.lostglade.server.CopperManGogglesSystem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuCopperGogglesMixin {
	@Inject(method = "clicked", at = @At("HEAD"), cancellable = true)
	private void lg2$handleCopperGogglesInventoryClick(
			int slotIndex,
			int button,
			ClickType clickType,
			Player player,
			CallbackInfo ci
	) {
		if (!(player instanceof ServerPlayer serverPlayer)) {
			return;
		}
		AbstractContainerMenu menu = (AbstractContainerMenu) (Object) this;
		if (CopperManGogglesSystem.handleInventoryModeClick(
				serverPlayer,
				menu,
				slotIndex,
				clickType,
				button
		)) {
			ci.cancel();
		}
	}
}
