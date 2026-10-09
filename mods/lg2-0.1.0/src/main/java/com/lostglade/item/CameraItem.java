package com.lostglade.item;

import com.lostglade.Lg2;
import com.lostglade.block.CameraBlock;
import com.lostglade.server.CameraCaptureSystem;
import com.lostglade.server.DroneSystem;
import eu.pb4.polymer.core.api.item.PolymerBlockItem;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import xyz.nucleoid.packettweaker.PacketContext;

public final class CameraItem extends PolymerBlockItem {
	private static final Identifier MODEL_ID = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "camera");

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
		return MODEL_ID;
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
		if (context.getLevel().getBlockState(context.getClickedPos()).getBlock() instanceof CameraBlock) {
			if (player == null || !player.isShiftKeyDown()) {
				return InteractionResult.PASS;
			}
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

	/** The placed camera uses the same canonical 3D item model as the handheld one. */
	public static ItemStack createDisplayStack() {
		return new ItemStack(ModItems.CAMERA);
	}

}
