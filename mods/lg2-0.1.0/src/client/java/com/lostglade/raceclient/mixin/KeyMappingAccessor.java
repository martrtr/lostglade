package com.lostglade.raceclient.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the race client expose vanilla Quick Actions as the Lostglade race-menu binding. */
@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {
	@Accessor("key")
	InputConstants.Key lg2$getKey();

	@Mutable
	@Accessor("name")
	void lg2$setName(String name);

	@Mutable
	@Accessor("category")
	void lg2$setCategory(KeyMapping.Category category);
}
