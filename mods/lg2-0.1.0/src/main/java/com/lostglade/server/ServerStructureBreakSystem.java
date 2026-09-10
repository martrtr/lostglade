package com.lostglade.server;

import com.lostglade.block.ModBlocks;
import com.lostglade.item.ModItems;
import com.lostglade.util.ItemDisplayHitboxHelper;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class ServerStructureBreakSystem {
	public static final int STRUCTURE_HALF_WIDTH = 2;
	public static final int STRUCTURE_HALF_DEPTH = 1;
	public static final int STRUCTURE_HEIGHT = 3;

	private static final int BREAK_ANIMATION_TICKS = 20;
	private static final BlockParticleOption BREAK_PARTICLE =
			new BlockParticleOption(ParticleTypes.BLOCK, Blocks.IRON_BLOCK.defaultBlockState());

	private static final String DISPLAY_ROOT_TAG = "lg2_server_display";
	private static final String DISPLAY_ANCHOR_PREFIX = "lg2_anchor:";
	private static final String DISPLAY_AXIS_PREFIX = "lg2_axis:";
	private static final String DISPLAY_FACING_PREFIX = "lg2_facing:";

	private static final Map<StructureKey, ActiveBreakSession> ACTIVE_BREAKS = new HashMap<>();
	private static final Set<GuardedBlockPos> INTERNAL_REMOVAL_POSITIONS = new HashSet<>();
	private static int nextCrackIdBase = 420_000;

	private ServerStructureBreakSystem() {
	}

	public static void register() {
		AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
			if (world.isClientSide()) {
				return InteractionResult.PASS;
			}
			if (!(player instanceof ServerPlayer serverPlayer)) {
				return InteractionResult.PASS;
			}
			if (serverPlayer.isSpectator() || hand != InteractionHand.MAIN_HAND) {
				return InteractionResult.PASS;
			}
			if (!serverPlayer.getMainHandItem().is(ModItems.SPECIAL_PICKAXE)) {
				return InteractionResult.PASS;
			}
			if (!(world instanceof ServerLevel level) || !level.getBlockState(pos).is(ModBlocks.SERVER)) {
				return InteractionResult.PASS;
			}

			Optional<ResolvedStructure> resolved = resolveStructure(level, pos);
			if (resolved.isEmpty()) {
				return InteractionResult.SUCCESS;
			}

			tryStartBreak(level, resolved.get());
			return InteractionResult.SUCCESS;
		});

		ServerTickEvents.END_SERVER_TICK.register(ServerStructureBreakSystem::tickBreakSessions);
		ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
			if (entity instanceof Display.ItemDisplay display && display.getTags().contains(DISPLAY_ROOT_TAG)) {
				ItemDisplayHitboxHelper.clear(display);
				if (world instanceof ServerLevel level) {
					reconcileLoadedStructureDisplay(level, display);
				}
				return;
			}
			if (!(entity instanceof ItemEntity itemEntity)) {
				return;
			}
			if (!itemEntity.getItem().is(ModBlocks.SERVER_ITEM)) {
				return;
			}
			hardenServerItemEntity(itemEntity);
		});
	}

	public static List<BlockPos> getStructurePositions(BlockPos anchor, Direction.Axis axis) {
		List<BlockPos> positions = new ArrayList<>(45);

		for (int dy = 0; dy < STRUCTURE_HEIGHT; dy++) {
			for (int depth = -STRUCTURE_HALF_DEPTH; depth <= STRUCTURE_HALF_DEPTH; depth++) {
				for (int width = -STRUCTURE_HALF_WIDTH; width <= STRUCTURE_HALF_WIDTH; width++) {
					BlockPos pos = axis == Direction.Axis.X
							? anchor.offset(depth, dy, width)
							: anchor.offset(width, dy, depth);
					positions.add(pos);
				}
			}
		}

		return positions;
	}

	public static void applyStructureDisplayTags(Display.ItemDisplay display, BlockPos anchor, Direction.Axis axis) {
		if (display == null || anchor == null || axis == null) {
			return;
		}
		display.getTags().removeIf(tag -> tag.equals(DISPLAY_ROOT_TAG)
				|| tag.startsWith(DISPLAY_ANCHOR_PREFIX)
				|| tag.startsWith(DISPLAY_AXIS_PREFIX));
		display.addTag(DISPLAY_ROOT_TAG);
		display.addTag(DISPLAY_ANCHOR_PREFIX + anchor.getX() + "," + anchor.getY() + "," + anchor.getZ());
		display.addTag(DISPLAY_AXIS_PREFIX + (axis == Direction.Axis.X ? "x" : "z"));
	}

	public static void setStructureDisplayFacing(Display.ItemDisplay display, Direction facing) {
		if (display == null || facing == null || !facing.getAxis().isHorizontal()) {
			return;
		}
		display.getTags().removeIf(tag -> tag.startsWith(DISPLAY_FACING_PREFIX));
		display.addTag(DISPLAY_FACING_PREFIX + facing.getSerializedName());
	}

	public static Direction resolveStructureDisplayFacing(Display.ItemDisplay display, Direction fallback) {
		if (display != null) {
			for (String tag : display.getTags()) {
				if (!tag.startsWith(DISPLAY_FACING_PREFIX)) {
					continue;
				}
				Direction direction = Direction.byName(tag.substring(DISPLAY_FACING_PREFIX.length()));
				if (direction != null && direction.getAxis().isHorizontal()) {
					return direction;
				}
			}
			Direction derived = Direction.fromYRot(display.getYRot());
			if (derived.getAxis().isHorizontal()) {
				return derived;
			}
		}
		return fallback != null && fallback.getAxis().isHorizontal() ? fallback : Direction.NORTH;
	}

	private static void reconcileLoadedStructureDisplay(ServerLevel level, Display.ItemDisplay display) {
		Optional<BlockPos> anchor = parseAnchorTag(display);
		Optional<Direction.Axis> axis = parseAxisTag(display);
		if (anchor.isEmpty() || axis.isEmpty()) {
			return;
		}
		Display.ItemDisplay keeper = resolveSingleStructureDisplay(level, anchor.get(), axis.get());
		if (keeper != null && !keeper.isRemoved()) {
			setStructureDisplayFacing(keeper, resolveStructureDisplayFacing(keeper, axis.get() == Direction.Axis.X ? Direction.EAST : Direction.NORTH));
		}
	}

	public static boolean isServerStructureDisplay(Entity entity) {
		return entity.getTags().contains(DISPLAY_ROOT_TAG);
	}

	public static Optional<BlockPos> getServerStructureDisplayAnchor(Entity entity) {
		return parseAnchorTag(entity);
	}

	public static void pruneStructureDisplays(ServerLevel level) {
		if (level == null) {
			return;
		}
		Map<DisplayStructureKey, Display.ItemDisplay> keepers = new LinkedHashMap<>();
		for (Entity entity : level.getAllEntities()) {
			if (!(entity instanceof Display.ItemDisplay itemDisplay) || !itemDisplay.getTags().contains(DISPLAY_ROOT_TAG)) {
				continue;
			}
			ItemDisplayHitboxHelper.clear(itemDisplay);
			Optional<BlockPos> taggedAnchor = parseAnchorTag(itemDisplay);
			Optional<Direction.Axis> taggedAxis = parseAxisTag(itemDisplay);
			if (taggedAnchor.isEmpty()
					|| taggedAxis.isEmpty()
					|| !isWholeStructurePresent(level, getStructurePositions(taggedAnchor.get(), taggedAxis.get()))) {
				Optional<DisplayStructureKey> structureAtDisplay = resolveStructureAtDisplayPosition(level, itemDisplay);
				if (structureAtDisplay.isEmpty()) {
					itemDisplay.discard();
					continue;
				}
				taggedAnchor = Optional.of(structureAtDisplay.get().anchor());
				taggedAxis = Optional.of(structureAtDisplay.get().axis());
				applyStructureDisplayTags(itemDisplay, taggedAnchor.get(), taggedAxis.get());
			}

			if (taggedAnchor.isEmpty() || taggedAxis.isEmpty()) {
				itemDisplay.discard();
				continue;
			}
			DisplayStructureKey key = new DisplayStructureKey(taggedAnchor.get().immutable(), taggedAxis.get());
			Display.ItemDisplay previous = keepers.putIfAbsent(key, itemDisplay);
			if (previous != null && previous != itemDisplay) {
				itemDisplay.discard();
			}
		}
	}

	public static Display.ItemDisplay resolveSingleStructureDisplay(ServerLevel level, BlockPos anchor, Direction.Axis axis) {
		if (level == null || anchor == null || axis == null) {
			return null;
		}
		AABB searchBox = new AABB(
				anchor.getX() - 8.0D,
				level.getMinY(),
				anchor.getZ() - 8.0D,
				anchor.getX() + 9.0D,
				level.getMaxY(),
				anchor.getZ() + 9.0D
		);
		double expectedX = anchor.getX() + 0.5D;
		double expectedY = anchor.getY() + 1.5D;
		double expectedZ = anchor.getZ() + 0.5D;
		Display.ItemDisplay keeper = null;
		List<Display.ItemDisplay> displays = level.getEntities(
				EntityType.ITEM_DISPLAY,
				searchBox,
				display -> display.getTags().contains(DISPLAY_ROOT_TAG)
		);
		for (Display.ItemDisplay itemDisplay : displays) {
			ItemDisplayHitboxHelper.clear(itemDisplay);
			Optional<BlockPos> taggedAnchor = parseAnchorTag(itemDisplay);
			Optional<Direction.Axis> taggedAxis = parseAxisTag(itemDisplay);
			boolean exactMatch = taggedAnchor.isPresent()
					&& taggedAxis.isPresent()
					&& taggedAnchor.get().equals(anchor)
					&& taggedAxis.get() == axis;
			if (exactMatch) {
				if (keeper == null) {
					keeper = itemDisplay;
				} else if (!hasFacingTag(keeper) && hasFacingTag(itemDisplay)) {
					keeper.discard();
					keeper = itemDisplay;
				} else {
					itemDisplay.discard();
				}
			}
		}

		for (Display.ItemDisplay itemDisplay : displays) {
			if (itemDisplay == keeper || itemDisplay.isRemoved()) {
				continue;
			}
			if (itemDisplay.distanceToSqr(expectedX, expectedY, expectedZ) <= 1.0D) {
				if (keeper == null) {
					keeper = itemDisplay;
					applyStructureDisplayTags(keeper, anchor, axis);
				} else {
					itemDisplay.discard();
				}
				continue;
			}

			Optional<BlockPos> taggedAnchor = parseAnchorTag(itemDisplay);
			boolean sameColumn = taggedAnchor.isPresent()
					&& taggedAnchor.get().getX() == anchor.getX()
					&& taggedAnchor.get().getZ() == anchor.getZ();
			boolean orphanedNearby = taggedAnchor.isEmpty()
					&& itemDisplay.distanceToSqr(expectedX, expectedY, expectedZ) <= 16.0D;
			if (sameColumn || orphanedNearby) {
				itemDisplay.discard();
			}
		}
		return keeper;
	}

	private static boolean hasFacingTag(Display.ItemDisplay display) {
		return display != null && display.getTags().stream().anyMatch(tag -> tag.startsWith(DISPLAY_FACING_PREFIX));
	}

	private static Optional<DisplayStructureKey> resolveStructureAtDisplayPosition(ServerLevel level, Display.ItemDisplay display) {
		if (level == null || display == null) {
			return Optional.empty();
		}
		BlockPos anchor = BlockPos.containing(display.getX(), display.getY() - 1.5D, display.getZ());
		for (Direction.Axis axis : List.of(Direction.Axis.X, Direction.Axis.Z)) {
			if (isWholeStructurePresent(level, getStructurePositions(anchor, axis))) {
				return Optional.of(new DisplayStructureKey(anchor.immutable(), axis));
			}
		}
		return Optional.empty();
	}

	public static boolean isInternalStructureRemoval(ServerLevel level, BlockPos pos) {
		return INTERNAL_REMOVAL_POSITIONS.contains(new GuardedBlockPos(level.dimension(), pos.immutable()));
	}

	public static void clearStructureSilently(ServerLevel level, BlockPos anchor, Direction.Axis axis) {
		if (level == null || anchor == null || axis == null) {
			return;
		}
		List<BlockPos> positions = getStructurePositions(anchor, axis);
		removeStructureDisplays(level, anchor, axis);
		ServerStabilitySystem.onServerStructureRemoved(level, anchor);
		SeasonStartSystem.onServerStructureRemoved(level, anchor);
		markInternalRemoval(level, positions, true);
		try {
			for (BlockPos pos : positions) {
				if (level.getBlockState(pos).is(ModBlocks.SERVER)) {
					level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
				}
			}
		} finally {
			markInternalRemoval(level, positions, false);
		}
	}

	public static void onStructureBlockRemoved(ServerLevel level, BlockPos removedPos) {
		if (isInternalStructureRemoval(level, removedPos)) {
			return;
		}

		Optional<ResolvedStructure> resolved = resolveBrokenStructure(level, removedPos);
		if (resolved.isEmpty()) {
			return;
		}

		ResolvedStructure structure = resolved.get();
		StructureKey key = new StructureKey(level.dimension(), structure.anchor());
		ActiveBreakSession existingSession = ACTIVE_BREAKS.remove(key);
		if (existingSession != null) {
			clearCracks(level, existingSession.positions(), existingSession.crackIdBase());
		}

		removeStructureDisplays(level, structure.anchor(), structure.axis());
		destroyStructureAndDropCenter(level, structure.anchor(), structure.positions());
	}

	private static void tryStartBreak(ServerLevel level, ResolvedStructure structure) {
		StructureKey key = new StructureKey(level.dimension(), structure.anchor());
		if (ACTIVE_BREAKS.containsKey(key)) {
			return;
		}

		int crackIdBase = allocateCrackIdBase(structure.positions().size());
		ACTIVE_BREAKS.put(key, new ActiveBreakSession(key, structure.positions(), crackIdBase, structure.axis()));
	}

	private static void tickBreakSessions(MinecraftServer server) {
		Iterator<Map.Entry<StructureKey, ActiveBreakSession>> iterator = ACTIVE_BREAKS.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<StructureKey, ActiveBreakSession> entry = iterator.next();
			ActiveBreakSession session = entry.getValue();
			ServerLevel level = server.getLevel(session.key().dimension());
			if (level == null) {
				iterator.remove();
				continue;
			}

			session.incrementAge();
			int stage = Math.min(9, (session.age() * 10) / BREAK_ANIMATION_TICKS);
			sendCrackStage(level, session.positions(), session.crackIdBase(), stage);
			sendFallbackBreakParticles(level, session.positions(), stage);

			if (session.age() < BREAK_ANIMATION_TICKS) {
				continue;
			}

			clearCracks(level, session.positions(), session.crackIdBase());
			removeStructureDisplays(level, session.key().anchor(), session.axis());
			destroyStructureAndDropCenter(level, session.key().anchor(), session.positions());
			iterator.remove();
		}
	}

	private static void sendCrackStage(ServerLevel level, List<BlockPos> positions, int crackIdBase, int stage) {
		for (int i = 0; i < positions.size(); i++) {
			level.destroyBlockProgress(crackIdBase + i, positions.get(i), stage);
		}
	}

	private static void clearCracks(ServerLevel level, List<BlockPos> positions, int crackIdBase) {
		for (int i = 0; i < positions.size(); i++) {
			level.destroyBlockProgress(crackIdBase + i, positions.get(i), -1);
		}
	}

	private static void sendFallbackBreakParticles(ServerLevel level, List<BlockPos> positions, int stage) {
		if ((stage & 1) != 0) {
			return;
		}

		for (BlockPos pos : positions) {
			level.sendParticles(
					BREAK_PARTICLE,
					pos.getX() + 0.5D,
					pos.getY() + 0.5D,
					pos.getZ() + 0.5D,
					2,
					0.25D,
					0.25D,
					0.25D,
					0.01D
			);
		}
	}

	private static void destroyStructureAndDropCenter(ServerLevel level, BlockPos anchor, List<BlockPos> positions) {
		ServerStabilitySystem.onServerStructureRemoved(level, anchor);
		SeasonStartSystem.onServerStructureRemoved(level, anchor);
		markInternalRemoval(level, positions, true);
		try {
			for (BlockPos pos : positions) {
				if (level.getBlockState(pos).is(ModBlocks.SERVER)) {
					level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
				}
			}
		} finally {
			markInternalRemoval(level, positions, false);
		}

		level.playSound(
				null,
				anchor,
				SoundEvents.ENDER_DRAGON_DEATH,
				SoundSource.AMBIENT,
				1.2F,
				1.0F
		);

		ItemEntity drop = new ItemEntity(
				level,
				anchor.getX() + 0.5D,
				anchor.getY() + 0.5D,
				anchor.getZ() + 0.5D,
				new ItemStack(ModBlocks.SERVER_ITEM)
		);
		drop.setDefaultPickUpDelay();
		hardenServerItemEntity(drop);
		level.addFreshEntity(drop);
	}

	private static void hardenServerItemEntity(ItemEntity itemEntity) {
		itemEntity.setInvulnerable(true);
		if (!isCommandGiveFakeItem(itemEntity)) {
			itemEntity.setUnlimitedLifetime();
		}
	}

	private static boolean isCommandGiveFakeItem(ItemEntity itemEntity) {
		return itemEntity.hasPickUpDelay() && itemEntity.getAge() >= 5999;
	}

	private static Optional<ResolvedStructure> resolveStructure(ServerLevel level, BlockPos hitPos) {
		Optional<ResolvedStructure> byDisplay = resolveByDisplayTags(level, hitPos);
		if (byDisplay.isPresent()) {
			return byDisplay;
		}

		return resolveByGeometryFallback(level, hitPos);
	}

	private static Optional<ResolvedStructure> resolveBrokenStructure(ServerLevel level, BlockPos brokenPos) {
		Optional<ResolvedStructure> byDisplay = resolveBrokenByDisplayTags(level, brokenPos);
		if (byDisplay.isPresent()) {
			return byDisplay;
		}

		return resolveBrokenByGeometry(level, brokenPos);
	}

	private static Optional<ResolvedStructure> resolveByDisplayTags(ServerLevel level, BlockPos hitPos) {
		AABB searchBox = new AABB(hitPos).inflate(8.0D, 6.0D, 8.0D);
		List<Display.ItemDisplay> displays = level.getEntities(
				EntityType.ITEM_DISPLAY,
				searchBox,
				display -> display.getTags().contains(DISPLAY_ROOT_TAG)
		);

		for (Display.ItemDisplay display : displays) {
			Optional<BlockPos> anchor = parseAnchorTag(display);
			Optional<Direction.Axis> axis = parseAxisTag(display);
			if (anchor.isEmpty() || axis.isEmpty()) {
				continue;
			}

			List<BlockPos> positions = getStructurePositions(anchor.get(), axis.get());
			if (!positions.contains(hitPos) || !isWholeStructurePresent(level, positions)) {
				continue;
			}

			return Optional.of(new ResolvedStructure(anchor.get(), axis.get(), positions));
		}

		return Optional.empty();
	}

	private static Optional<ResolvedStructure> resolveByGeometryFallback(ServerLevel level, BlockPos hitPos) {
		Set<CandidateAnchor> checkedAnchors = new HashSet<>();
		for (Direction.Axis axis : List.of(Direction.Axis.Z, Direction.Axis.X)) {
			for (int dy = 0; dy < STRUCTURE_HEIGHT; dy++) {
				for (int depth = -STRUCTURE_HALF_DEPTH; depth <= STRUCTURE_HALF_DEPTH; depth++) {
					for (int width = -STRUCTURE_HALF_WIDTH; width <= STRUCTURE_HALF_WIDTH; width++) {
						BlockPos anchor = axis == Direction.Axis.X
								? hitPos.offset(-depth, -dy, -width)
								: hitPos.offset(-width, -dy, -depth);

						if (!checkedAnchors.add(new CandidateAnchor(anchor, axis))) {
							continue;
						}

						List<BlockPos> positions = getStructurePositions(anchor, axis);
						if (positions.contains(hitPos) && isWholeStructurePresent(level, positions)) {
							return Optional.of(new ResolvedStructure(anchor, axis, positions));
						}
					}
				}
			}
		}

		return Optional.empty();
	}

	private static Optional<ResolvedStructure> resolveBrokenByDisplayTags(ServerLevel level, BlockPos brokenPos) {
		AABB searchBox = new AABB(brokenPos).inflate(8.0D, 6.0D, 8.0D);
		List<Display.ItemDisplay> displays = level.getEntities(
				EntityType.ITEM_DISPLAY,
				searchBox,
				display -> display.getTags().contains(DISPLAY_ROOT_TAG)
		);

		for (Display.ItemDisplay display : displays) {
			Optional<BlockPos> anchor = parseAnchorTag(display);
			Optional<Direction.Axis> axis = parseAxisTag(display);
			if (anchor.isEmpty() || axis.isEmpty()) {
				continue;
			}

			List<BlockPos> positions = getStructurePositions(anchor.get(), axis.get());
			if (!positions.contains(brokenPos) || !containsAnyStructureBlock(level, positions)) {
				continue;
			}

			return Optional.of(new ResolvedStructure(anchor.get(), axis.get(), positions));
		}

		return Optional.empty();
	}

	private static Optional<ResolvedStructure> resolveBrokenByGeometry(ServerLevel level, BlockPos brokenPos) {
		Set<CandidateAnchor> checkedAnchors = new HashSet<>();
		for (Direction.Axis axis : List.of(Direction.Axis.Z, Direction.Axis.X)) {
			for (int dy = 0; dy < STRUCTURE_HEIGHT; dy++) {
				for (int depth = -STRUCTURE_HALF_DEPTH; depth <= STRUCTURE_HALF_DEPTH; depth++) {
					for (int width = -STRUCTURE_HALF_WIDTH; width <= STRUCTURE_HALF_WIDTH; width++) {
						BlockPos anchor = axis == Direction.Axis.X
								? brokenPos.offset(-depth, -dy, -width)
								: brokenPos.offset(-width, -dy, -depth);

						if (!checkedAnchors.add(new CandidateAnchor(anchor, axis))) {
							continue;
						}

						List<BlockPos> positions = getStructurePositions(anchor, axis);
						if (!positions.contains(brokenPos) || !isSingleMissingStructureBlock(level, positions)) {
							continue;
						}

						return Optional.of(new ResolvedStructure(anchor, axis, positions));
					}
				}
			}
		}

		return Optional.empty();
	}

	private static boolean isWholeStructurePresent(ServerLevel level, List<BlockPos> positions) {
		for (BlockPos pos : positions) {
			if (!level.getBlockState(pos).is(ModBlocks.SERVER)) {
				return false;
			}
		}
		return true;
	}

	private static boolean containsAnyStructureBlock(ServerLevel level, List<BlockPos> positions) {
		for (BlockPos pos : positions) {
			if (level.getBlockState(pos).is(ModBlocks.SERVER)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isSingleMissingStructureBlock(ServerLevel level, List<BlockPos> positions) {
		int missing = 0;
		for (BlockPos pos : positions) {
			if (level.getBlockState(pos).is(ModBlocks.SERVER)) {
				continue;
			}

			missing++;
			if (missing > 1) {
				return false;
			}
		}
		return missing == 1;
	}

	private static void markInternalRemoval(ServerLevel level, List<BlockPos> positions, boolean removing) {
		for (BlockPos pos : positions) {
			GuardedBlockPos key = new GuardedBlockPos(level.dimension(), pos.immutable());
			if (removing) {
				INTERNAL_REMOVAL_POSITIONS.add(key);
			} else {
				INTERNAL_REMOVAL_POSITIONS.remove(key);
			}
		}
	}

	private static void removeStructureDisplays(ServerLevel level, BlockPos anchor, Direction.Axis axis) {
		AABB searchBox = new AABB(anchor).inflate(8.0D, 6.0D, 8.0D);
		List<Display.ItemDisplay> displays = level.getEntities(
				EntityType.ITEM_DISPLAY,
				searchBox,
				display -> display.getTags().contains(DISPLAY_ROOT_TAG)
		);

		for (Display.ItemDisplay itemDisplay : displays) {
			Optional<BlockPos> taggedAnchor = parseAnchorTag(itemDisplay);
			Optional<Direction.Axis> taggedAxis = parseAxisTag(itemDisplay);
			if (taggedAnchor.isPresent() && taggedAxis.isPresent()
					&& taggedAnchor.get().equals(anchor)
					&& taggedAxis.get() == axis) {
				itemDisplay.discard();
			}
		}
	}

	private static Optional<BlockPos> parseAnchorTag(Entity display) {
		for (String tag : display.getTags()) {
			if (!tag.startsWith(DISPLAY_ANCHOR_PREFIX)) {
				continue;
			}

			String payload = tag.substring(DISPLAY_ANCHOR_PREFIX.length());
			String[] parts = payload.split(",");
			if (parts.length != 3) {
				return Optional.empty();
			}

			try {
				int x = Integer.parseInt(parts[0]);
				int y = Integer.parseInt(parts[1]);
				int z = Integer.parseInt(parts[2]);
				return Optional.of(new BlockPos(x, y, z));
			} catch (NumberFormatException ignored) {
				return Optional.empty();
			}
		}

		return Optional.empty();
	}

	private static Optional<Direction.Axis> parseAxisTag(Entity display) {
		for (String tag : display.getTags()) {
			if (!tag.startsWith(DISPLAY_AXIS_PREFIX)) {
				continue;
			}

			String payload = tag.substring(DISPLAY_AXIS_PREFIX.length());
			if ("x".equals(payload)) {
				return Optional.of(Direction.Axis.X);
			}
			if ("z".equals(payload)) {
				return Optional.of(Direction.Axis.Z);
			}
			return Optional.empty();
		}

		return Optional.empty();
	}

	private static int allocateCrackIdBase(int expectedPositions) {
		int base = nextCrackIdBase;
		nextCrackIdBase += Math.max(1, expectedPositions + 1);
		if (nextCrackIdBase > Integer.MAX_VALUE - 10_000) {
			nextCrackIdBase = 420_000;
		}
		return base;
	}

	private record StructureKey(ResourceKey<Level> dimension, BlockPos anchor) {
	}

	private record CandidateAnchor(BlockPos anchor, Direction.Axis axis) {
	}

	private record GuardedBlockPos(ResourceKey<Level> dimension, BlockPos pos) {
	}

	private record ResolvedStructure(BlockPos anchor, Direction.Axis axis, List<BlockPos> positions) {
	}

	private static final class ActiveBreakSession {
		private final StructureKey key;
		private final List<BlockPos> positions;
		private final int crackIdBase;
		private final Direction.Axis axis;
		private int ageTicks;

		private ActiveBreakSession(StructureKey key, List<BlockPos> positions, int crackIdBase, Direction.Axis axis) {
			this.key = key;
			this.positions = positions;
			this.crackIdBase = crackIdBase;
			this.axis = axis;
			this.ageTicks = 0;
		}

		private void incrementAge() {
			this.ageTicks++;
		}

		private int age() {
			return this.ageTicks;
		}

		private StructureKey key() {
			return this.key;
		}

		private List<BlockPos> positions() {
			return this.positions;
		}

		private int crackIdBase() {
			return this.crackIdBase;
		}

		private Direction.Axis axis() {
			return this.axis;
		}
	}

	private record DisplayStructureKey(BlockPos anchor, Direction.Axis axis) {
	}
}
