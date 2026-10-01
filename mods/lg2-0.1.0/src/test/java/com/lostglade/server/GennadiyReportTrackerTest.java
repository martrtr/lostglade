package com.lostglade.server;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.List;
import java.util.UUID;

public final class GennadiyReportTrackerTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var state = new GennadiyReportTracker.State();
        ItemStack report = report(state.epoch);
        UUID id = GennadiyReportTracker.rawId(report);
        require(!GennadiyReportTracker.purgeStack(report.copy(), state), "Active REPORT was deleted");
        state.revoked.add(UUID.randomUUID());
        require(!GennadiyReportTracker.purgeStack(report.copy(), state), "Unrelated retirement deleted active REPORT");
        state.revoked.add(id);
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        ItemStack bundle = new ItemStack(Items.BUNDLE);
        bundle.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(List.of(report, new ItemStack(Items.DIAMOND, 3))));
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.STONE, 9), bundle)));
        ItemStack snapshot = box.copy();
        require(GennadiyReportTracker.purgeStack(box, state), "Nested REPORT survived retirement");
        var items = box.get(DataComponents.CONTAINER).stream().toList();
        require(items.get(0).is(Items.STONE) && items.get(0).getCount() == 9, "Other shulker slots changed");
        var contents = items.get(1).get(DataComponents.BUNDLE_CONTENTS);
        require(contents.size() == 1 && contents.getItemUnsafe(0).is(Items.DIAMOND)
                && contents.getItemUnsafe(0).getCount() == 3, "Other bundle items changed");
        require(snapshot.get(DataComponents.CONTAINER).stream().toList().get(1)
                .get(DataComponents.BUNDLE_CONTENTS).size() == 2, "Purging mutated shared immutable component contents");

        // The NBT path is used for non-Container storage; strict bundle decoding must still succeed.
        Tag encoded = ItemStack.CODEC.encodeStart(NbtOps.INSTANCE, snapshot).getOrThrow();
        require(GennadiyReportTracker.purgeTag(encoded, state), "NBT REPORT survived retirement");
        ItemStack decoded = ItemStack.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();
        require(decoded.get(DataComponents.CONTAINER).stream().toList().get(1)
                .get(DataComponents.BUNDLE_CONTENTS).size() == 1, "Purging invalidated a strict bundle codec");

        state.revoked.clear();
        state.epoch = UUID.randomUUID();
        state.acceptLegacy = false;
        require(GennadiyReportTracker.purgeStack(report.copy(), state), "Old epoch survived annihilation");
        ItemStack legacy = report(null);
        require(GennadiyReportTracker.purgeStack(legacy.copy(), state), "Legacy REPORT survived annihilation");
        require(!GennadiyReportTracker.purgeStack(report(state.epoch), state), "New REPORT was rejected after clear");
        var roundTrip = new com.google.gson.Gson().fromJson(new com.google.gson.Gson().toJson(state), GennadiyReportTracker.State.class);
        require(roundTrip.epoch.equals(state.epoch) && !roundTrip.acceptLegacy, "Revocation did not survive restart");
        System.out.println("REPORT: identity, nested purge, preserved contents, strict NBT codecs and persistent annihilation passed");
    }

    private static ItemStack report(UUID epoch) {
        ItemStack item = new ItemStack(Items.STICK);
        CompoundTag marker = new CompoundTag();
        marker.putString("id", UUID.randomUUID().toString());
        if (epoch != null) marker.putString("tracking_epoch", epoch.toString());
        CompoundTag data = new CompoundTag();
        data.put(GennadiyReportTracker.MARKER, marker);
        item.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        return item;
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
