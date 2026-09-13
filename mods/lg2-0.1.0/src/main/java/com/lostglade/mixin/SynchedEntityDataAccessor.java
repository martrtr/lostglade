package com.lostglade.mixin;

import net.minecraft.network.syncher.SynchedEntityData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SynchedEntityData.class)
public interface SynchedEntityDataAccessor {
	@Accessor("itemsById")
	SynchedEntityData.DataItem<?>[] lg2$getItemsById();

	@Accessor("isDirty")
	void lg2$setDirty(boolean dirty);
}
