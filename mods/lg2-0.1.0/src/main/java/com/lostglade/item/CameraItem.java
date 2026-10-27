package com.lostglade.item;

import com.lostglade.Lg2;
import com.lostglade.block.CameraBlock;
import com.lostglade.server.CameraCaptureSystem;
import com.lostglade.server.DroneSystem;
import eu.pb4.polymer.core.api.item.PolymerBlockItem;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import xyz.nucleoid.packettweaker.PacketContext;

public final class CameraItem extends PolymerBlockItem {
	private static final Identifier MODEL_ID = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "camera");
	private static final Identifier PLACED_MODEL_ID = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "camera_placed");
	private static final String DISPLAY_ROOT_TAG = "lg2_camera_display";
	private static final String DISPLAY_ONLY_TAG = "display_only";

	public CameraItem(CameraBlock block, Item.Properties settings) {
		super(block, settings, Items.STICK, true);
	}

	@Override
	public Component getName(ItemStack stack) {
		return Component.literal("Camera");
	}

	@Override
	public Item getPolymerItem(ItemStack stack, PacketContext context) {
		return Items.STICK;
	}

	@Override
	public Identifier getPolymerItemModel(ItemStack itemStack, PacketContext context) {
		if (!PolymerResourcePackUtils.hasMainPack(context)) {
			return null;
		}
		return isPlacedDisplayStack(itemStack) ? PLACED_MODEL_ID : MODEL_ID;
	}

	@Override
	public void modifyBasePolymerItemStack(ItemStack out, ItemStack original, PacketContext context) {
		CameraBlock.applyFallbackName(out, context);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (hand != InteractionHand.MAIN_HAND) {
			return InteractionResult.PASS;
		}
		if (player instanceof ServerPlayer serverPlayer && DroneSystem.isCameraBlockedByDroneControl(serverPlayer)) {
			return InteractionResult.PASS;
		}
		if (player instanceof ServerPlayer serverPlayer) {
			CameraCaptureSystem.suppressNextCameraSwing(serverPlayer, hand);
		}
		// In the air there is nothing to place, so RMB is always the shutter.
		// Shift is only needed when RMB targets a block and would otherwise place
		// the camera item.
		if (player instanceof ServerPlayer serverPlayer) {
			return CameraCaptureSystem.tryCapture(serverPlayer, player.getItemInHand(hand))
					? InteractionResult.SUCCESS
					: InteractionResult.FAIL;
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		if (context.getHand() != InteractionHand.MAIN_HAND) {
			return InteractionResult.PASS;
		}
		Player player = context.getPlayer();
		if (player instanceof ServerPlayer serverPlayer && DroneSystem.isCameraBlockedByDroneControl(serverPlayer)) {
			return InteractionResult.PASS;
		}
		if (player instanceof ServerPlayer serverPlayer) {
			CameraCaptureSystem.suppressNextCameraSwing(serverPlayer, context.getHand());
		}
		// A placed camera owns RMB itself: it turns its lens towards the player.
		// Do not let the held camera item consume this as a placement/capture
		// action; that was the behaviour in 535c4abb and keeps both the placement
		// origin and the subsequent aim calculation on the camera block.
		if (context.getLevel().getBlockState(context.getClickedPos()).getBlock() instanceof CameraBlock) {
			return InteractionResult.PASS;
		}
		if (player != null && player.isShiftKeyDown()) {
			if (player instanceof ServerPlayer serverPlayer) {
				return CameraCaptureSystem.tryCapture(serverPlayer, player.getItemInHand(context.getHand()))
						? InteractionResult.SUCCESS
						: InteractionResult.FAIL;
			}
			return InteractionResult.SUCCESS;
		}
		return super.useOn(context);
	}

	@Override
	public boolean canDestroyBlock(ItemStack stack, BlockState state, Level level, BlockPos pos, net.minecraft.world.entity.LivingEntity miningEntity) {
		return false;
	}

	/**
	 * The item-display entity is not an ordinary inventory camera.  Give it a
	 * display-only marker so its fixed transform can stay compatible with the
	 * placed-camera pose without changing any first- or third-person transforms.
	 */
	public static ItemStack createDisplayStack() {
		ItemStack stack = new ItemStack(ModItems.CAMERA);
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
			var displayTag = tag.getCompoundOrEmpty(DISPLAY_ROOT_TAG);
			displayTag.putBoolean(DISPLAY_ONLY_TAG, true);
			tag.put(DISPLAY_ROOT_TAG, displayTag);
		});
		return stack;
	}

	private static boolean isPlacedDisplayStack(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return false;
		}
		CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
		return customData != null
				&& customData.copyTag().getCompoundOrEmpty(DISPLAY_ROOT_TAG).getBooleanOr(DISPLAY_ONLY_TAG, false);
	}

}
