package com.lostglade.server;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Real vanilla Display caches; the proxy substitutes only the generated mixin accessors. */
public final class FrozenSceneDisplayStateTest {
	public static void main(String[] args) throws Exception {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		var item = new Display.ItemDisplay(EntityType.ITEM_DISPLAY, null);
		item.setItemStack(new ItemStack(Items.DIAMOND));
		require(item.itemRenderState() == null, "unticked display should reproduce the missing visual");
		prepare(item);
		require(item.itemRenderState().itemStack().is(Items.DIAMOND), "item snapshot was not materialized");
		Object cached = item.renderState();
		prepare(item);
		require(item.renderState() == cached, "unchanged snapshots must not rebuild every frame");
		item.setItemStack(new ItemStack(Items.EMERALD));
		prepare(item);
		require(item.itemRenderState().itemStack().is(Items.EMERALD), "later video metadata retained the previous item");

		var block = new Display.BlockDisplay(EntityType.BLOCK_DISPLAY, null);
		block.setBlockState(Blocks.DIAMOND_BLOCK.defaultBlockState());
		prepare(block);
		require(block.blockRenderState().blockState().is(Blocks.DIAMOND_BLOCK), "block display remained empty");
		var text = new Display.TextDisplay(EntityType.TEXT_DISPLAY, null);
		text.setText(Component.literal("Frozen camera"));
		prepare(text);
		require(text.textRenderState().text().getString().equals("Frozen camera"), "text display remained empty");
		System.out.println("Frozen display snapshot checks passed");
	}

	private static void prepare(Display display) throws Exception {
		Class<?> access = Class.forName("com.lostglade.mixin.client.DisplayCameraStateAccessor");
		var position = display.position();
		int ticks = display.tickCount;
		if (access.isInstance(display)) {
			Class.forName("com.lostglade.client.FrozenSceneDisplayState").getMethod("prepare", Display.class).invoke(null, display);
			require(display.renderState() != null, "mixin did not materialize visual state");
			require(display.tickCount == ticks && display.position().equals(position), "mixin advanced the captured entity");
			return;
		}
		Field dirty = Display.class.getDeclaredField("updateRenderState");
		Field render = Display.class.getDeclaredField("renderState");
		Method fresh = Display.class.getDeclaredMethod("createFreshRenderState");
		Method sub = Display.class.getDeclaredMethod("updateRenderSubState", boolean.class, float.class);
		for (var member : new java.lang.reflect.AccessibleObject[]{dirty, render, fresh, sub}) member.setAccessible(true);
		Object bridge = Proxy.newProxyInstance(access.getClassLoader(), new Class<?>[]{access}, (proxy, method, args) -> switch (method.getName()) {
			case "lg2$needsRenderStateUpdate" -> dirty.getBoolean(display);
			case "lg2$setNeedsRenderStateUpdate" -> { dirty.setBoolean(display, (boolean) args[0]); yield null; }
			case "lg2$setRenderState" -> { render.set(display, args[0]); yield null; }
			case "lg2$createFreshRenderState" -> fresh.invoke(display);
			case "lg2$updateRenderSubState" -> sub.invoke(display, args);
			default -> throw new UnsupportedOperationException(method.getName());
		});
		Method prepare = Class.forName("com.lostglade.client.FrozenSceneDisplayState").getDeclaredMethod("prepare", Display.class, access);
		prepare.setAccessible(true);
		prepare.invoke(null, display, bridge);
		require(display.renderState() != null, "base visual state remained null");
		require(display.tickCount == ticks && display.position().equals(position), "preparing a visual advanced the captured entity");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
