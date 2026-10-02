package com.lostglade.raceclient.mixin;

import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the race client present vanilla Quick Actions as its own key binding. */
@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {
	@Mutable
	@Accessor("name")
	void lg2$setName(String name);

	@Mutable
	@Accessor("category")
	void lg2$setCategory(KeyMapping.Category category);
}
