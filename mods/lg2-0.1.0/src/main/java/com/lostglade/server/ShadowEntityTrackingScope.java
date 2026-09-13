package com.lostglade.server;

import com.lostglade.mixin.SynchedEntityDataAccessor;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;

/** Preserve gameplay's dirty flags while a secondary packet tracker reads an entity. */
final class ShadowEntityTrackingScope implements AutoCloseable {
	private final Entity entity;
	private final boolean needsSync;
	private final boolean hurtMarked;
	private final boolean dataDirty;
	private final SynchedEntityData.DataItem<?>[] data;
	private final BitSet dirtyItems = new BitSet();
	private final Set<AttributeInstance> attributes;

	ShadowEntityTrackingScope(Entity entity) {
		this.entity = entity;
		needsSync = entity.needsSync;
		hurtMarked = entity.hurtMarked;
		dataDirty = entity.getEntityData().isDirty();
		data = ((SynchedEntityDataAccessor) entity.getEntityData()).lg2$getItemsById();
		for (int i = 0; i < data.length; i++) {
			if (data[i] != null && data[i].isDirty()) dirtyItems.set(i);
		}
		attributes = entity instanceof LivingEntity living
				? new HashSet<>(living.getAttributes().getAttributesToSync()) : Set.of();
	}

	@Override
	public void close() {
		entity.needsSync = needsSync;
		entity.hurtMarked = hurtMarked;
		for (int i = 0; i < data.length; i++) {
			if (data[i] != null) data[i].setDirty(dirtyItems.get(i));
		}
		((SynchedEntityDataAccessor) entity.getEntityData()).lg2$setDirty(dataDirty);
		if (entity instanceof LivingEntity living) {
			var pending = living.getAttributes().getAttributesToSync();
			pending.clear();
			pending.addAll(attributes);
		}
	}
}
