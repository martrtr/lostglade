package com.lostglade.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.lostglade.Lg2;
import com.lostglade.block.ModBlocks;
import com.lostglade.block.ServerBlock;
import com.lostglade.config.SeasonStartConfig;
import com.lostglade.item.ModItems;
import com.lostglade.util.ItemDisplayHitboxHelper;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.math.Transformation;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTrackedWaypointPacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Brightness;
import net.minecraft.util.Mth;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import static com.lostglade.config.SeasonStartConfig.get;

public final class SeasonStartSystem {
	private static final Gson STATE_GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String STATE_FILE_NAME = "lg2-season-start-state.json";
	private static final String WORLD_REVEAL_SNAPSHOT_FILE_NAME = "lg2-season-start-world-snapshot.dat";
	private static final int WORLD_REVEAL_SNAPSHOT_MAGIC = 0x4C473253;
	private static final int WORLD_REVEAL_SNAPSHOT_VERSION = 1;
	private static final Set<Relative> ABSOLUTE_TELEPORT = EnumSet.noneOf(Relative.class);
	private static final int DISSOLVE_BATCH_BLOCKS = 96;
	// The vertical columns are deliberately wider batches: they only cover the
	// already-reserved startup footprint and must be finished before the scene
	// opens to players.
	private static final int VERTICAL_SCENE_BATCH_BLOCKS = 4_096;
	// A one-chunk exterior buffer keeps the physical shell out of the client's
	// edge-culling zone. The startup biome turns this buffer into black fog, so
	// it cannot reveal the ordinary world behind the shell.
	private static final int STARTUP_CHUNK_TRACKING_GUARD_RING = 1;
	private static final int STARTUP_BIOME_CHUNKS_PER_TICK = 16;
	private static final long STARTUP_BIOME_RESYNC_TICKS = 20L * 5L;
	private static final ResourceKey<Biome> STARTUP_VOID_BIOME_KEY = ResourceKey.create(
			Registries.BIOME,
			Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "season_start_void")
	);
	private static final List<ResourceKey<Biome>> STARTUP_REVEAL_BIOME_KEYS = List.of(
			STARTUP_VOID_BIOME_KEY,
			ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "season_start_dawn_1")),
			ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "season_start_dawn_2")),
			ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "season_start_dawn_3")),
			ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "season_start_dawn_4")),
			ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "season_start_dawn_5"))
	);
	private static final long WAITING_START_INITIAL_PROMPT_TICKS = 20L * 3L;
	private static final long WAITING_START_REPEAT_TICKS = 20L * 15L;
	private static final ManagedInfiniteEffect INTRO_BLINDNESS = new ManagedInfiniteEffect(
			MobEffects.BLINDNESS,
			false,
			false,
			false
	);
	private static final long INTRO_IDLE_TRIGGER_TICKS = 20L * 9L;
	private static final long INTRO_IDLE_REPEAT_TICKS = 20L * 10L;
	private static final long INTRO_SPIN_REPEAT_TICKS = 20L * 10L;
	private static final long INTRO_JUMP_REPEAT_TICKS = 20L * 4L;
	private static final long INTRO_AIR_PUNCH_REPEAT_TICKS = 20L * 5L;
	private static final long INTRO_TARGET_REACTION_REPEAT_TICKS = 20L * 9L;
	// EXIT signs are deliberately a rare bit of atmosphere in the blind private
	// tutorial. They are not navigation: every sign is short-lived, private to
	// one player and faces a random direction. A player receives a fixed small
	// budget for the whole scene, never a repeating stream of signs.
	private static final long PERSONAL_EXIT_SIGN_MIN_INTERVAL_TICKS = 20L * 18L;
	private static final long PERSONAL_EXIT_SIGN_INTERVAL_VARIATION_TICKS = 20L * 16L;
	private static final long PERSONAL_EXIT_SIGN_LIFETIME_TICKS = 20L * 6L;
	private static final long GUIDANCE_EVALUATE_TICKS = 4L;
	private static final long GUIDANCE_MIN_VOICE_GAP_TICKS = 20L;
	private static final long GUIDANCE_CATEGORY_COOLDOWN_TICKS = 10L;
	private static final long GUIDANCE_CORRECTION_COOLDOWN_TICKS = 8L;
	private static final long GUIDANCE_FORWARD_COOLDOWN_TICKS = 16L;
	private static final long GUIDANCE_STALL_COOLDOWN_TICKS = 24L;
	private static final long GUIDANCE_TURN_RECOVERY_MIN_DELAY_TICKS = 20L;
	private static final float GUIDANCE_TURN_RECOVERY_WRONG_YAW_DEGREES = 8.0F;
	private static final long GUIDANCE_RECOVER_REACTION_WINDOW_TICKS = 20L * 4L;
	private static final long GUIDANCE_STALL_AFTER_ALIGNMENT_TICKS = 20L;
	private static final long GUIDANCE_STALL_AFTER_TURN_TICKS = 18L;
	private static final long GUIDANCE_STALL_SEMANTIC_COOLDOWN_TICKS = 20L * 10L;
	private static final long GUIDANCE_CORRECTION_SEMANTIC_COOLDOWN_TICKS = 20L * 8L;
	private static final long GUIDANCE_DIRECTION_SEMANTIC_COOLDOWN_TICKS = 20L * 4L;
	private static final long GUIDANCE_PROGRESS_SEMANTIC_COOLDOWN_TICKS = 20L * 5L;
	private static final long ROUTE_STALL_AFTER_TICKS = 20L * 2L;
	private static final long ROUTE_TURN_REPEAT_TICKS = 20L * 3L;
	private static final long ROUTE_WRONG_WAY_GRACE_TICKS = 20L + 4L;
	private static final long ROUTE_WRONG_WAY_REPEAT_TICKS = 20L * 4L;
	private static final long ROUTE_PROGRESS_CUE_TICKS = 20L * 2L;
	private static final long ROUTE_CONFUSION_MAX_TICKS = 20L * 60L * 3L;
	private static final double ROUTE_WAYPOINT_REACHED_DISTANCE = 1.25D;
	private static final double ROUTE_MOVEMENT_SQR = 0.045D * 0.045D;
	private static final double ROUTE_PROGRESS_AWAY = 0.12D;
	private static final double ROUTE_PROGRESS_TOWARD = -0.09D;
	private static final int BARRIER_FLOOR_DEPTH = 5;
	private static final double INTRO_ACTIVITY_MOVE_SQR = 0.04D * 0.04D;
	// The score decays every tick, so 200 could only be reached by an almost
	// instantaneous 180-degree snap. A normal fast look-around now reaches this
	// threshold too, while the ten-second cooldown keeps it from becoming noise.
	private static final double INTRO_SPIN_TRIGGER_SCORE = 45.0D;
	private static final double INTRO_PICK_REACH = 5.0D;
	private static final double INTRO_TARGET_VERTICAL_GUIDANCE_DISTANCE = 4.8D;
	private static final double INTRO_TARGET_VERTICAL_YAW_WINDOW = 14.0D;
	private static final double INTRO_TARGET_VERTICAL_PITCH_THRESHOLD = 24.0D;
	private static final float INTRO_ACTIVITY_YAW_DEGREES = 6.0F;
	private static final float INTRO_ACTIVITY_PITCH_DEGREES = 4.0F;
	private static final double GUIDANCE_LOCK_ANGLE = 6.0D;
	private static final double GUIDANCE_MICRO_ANGLE = 12.0D;
	private static final double GUIDANCE_MEDIUM_ANGLE = 26.0D;
	private static final double GUIDANCE_HARD_ANGLE = 62.0D;
	private static final double GUIDANCE_TURN_AROUND_ANGLE = 140.0D;
	// These distances are measured from the outer edge of the complete 5x3x3 server model.
	private static final double GUIDANCE_CLOSE_APPROACH_DISTANCE = 4.0D;
	private static final double GUIDANCE_QUIET_DISTANCE = 2.05D;
	private static final double GUIDANCE_DROP_DISTANCE = 1.35D;
	private static final double GUIDANCE_SERVER_SIGHT_DISTANCE = 4.0D;
	// Once a target is close enough to be plainly visible in the startup room,
	// directions are no longer useful. The player needs a contextual nudge, not
	// another left/right command while the ore or server is already nearby.
	private static final double GUIDANCE_TARGET_VISIBLE_DISTANCE = 7.5D;
	private static final double GUIDANCE_SERVER_VISIBLE_DISTANCE = 9.5D;
	private static final double GUIDANCE_TARGET_VISIBLE_VERTICAL_DISTANCE = 6.0D;
	// A "returned without the coin" line only makes sense after the player has
	// actually gone out to search for it, not in the moment the coin is thrown.
	private static final double GUIDANCE_SERVER_RETURN_ARM_DISTANCE = 7.0D;
	private static final double GUIDANCE_PASSED_SERVER_DISTANCE = 5.0D;
	private static final double GUIDANCE_PROGRESS_AWAY = 0.16D;
	private static final double GUIDANCE_PROGRESS_TOWARD = -0.14D;
	private static final double GUIDANCE_STALL_DELTA = 0.05D;
	// A rejected first offering should read as a deliberate throw, not as a normal item drop.
	private static final double GUIDED_OFFERING_ESCAPE_SPEED_MIN = 0.30D;
	private static final double GUIDED_OFFERING_ESCAPE_SPEED_MAX = 0.82D;
	private static final double GUIDED_OFFERING_ESCAPE_TARGET_DISTANCE = 0.55D;
	private static final double GUIDED_OFFERING_ESCAPE_THROW_DISTANCE = 16.0D;
	private static final double GUIDED_OFFERING_ESCAPE_BOUNDARY_MARGIN = 1.25D;
	private static final double GUIDED_OFFERING_RECOVERY_DISTANCE = 1.2D;
	// After the second recovery the coin is no longer a scripted throw. Give a normal
	// vanilla toss a forgiving target near the server rather than requiring pixel precision.
	private static final double GUIDED_FINAL_OFFERING_RADIUS = 1.55D;
	private static final long GUIDED_OFFERING_ESCAPE_DELAY_TICKS = 5L;
	private static final int GUIDED_OFFERING_MAX_ESCAPES = 2;
	private static final int NO_LOCKED_GUIDED_BITCOIN_SLOT = -1;
	private static final double GUIDANCE_RECOVER_WORSEN_THRESHOLD = 8.0D;
	private static final int STARTUP_CLEAR_WEATHER_TICKS = Integer.MAX_VALUE;
	private static final String[] WAITING_START_PROMPT_TRIGGERS = {
			"player_waiting_start_prompt_01",
			"player_waiting_start_prompt_02",
			"player_waiting_start_prompt_03"
	};
	private static final String[] INTRO_TARGET_LOCK_TRIGGERS = {
			"intro_target_locked_01",
			"intro_target_locked_02",
			"intro_target_locked_03"
	};
	private static final String[] INTRO_TARGET_STARE_TRIGGERS = {
			"intro_target_stare_01",
			"intro_target_stare_02"
	};
	private static final String[] INTRO_TARGET_LOOK_UP_TRIGGERS = {
			"intro_target_look_up_01",
			"intro_target_look_up_02"
	};
	private static final String[] INTRO_TARGET_LOOK_DOWN_TRIGGERS = {
			"intro_target_look_down_01",
			"intro_target_look_down_02"
	};
	private static final String[] INTRO_GUIDE_WRONG_WAY_TRIGGERS = {
			"intro_guide_wrong_way_01",
			"intro_guide_wrong_way_02",
			"intro_guide_wrong_way_03",
			"intro_guide_wrong_way_04",
			"intro_guide_wrong_way_05",
			"intro_guide_wrong_way_06",
			"intro_guide_wrong_way_07",
			"intro_guide_wrong_way_08"
	};
	private static final String[] INTRO_GUIDE_ROUTE_START_TRIGGERS = {
			"intro_guide_route_start_01",
			"intro_guide_route_start_02",
			"intro_guide_route_start_03"
	};
	private static final String[] INTRO_ROUTE_ARRIVED_TRIGGERS = {
			"intro_route_arrived_01",
			"intro_route_arrived_02",
			"intro_route_arrived_03"
	};
	private static final String[] GUIDE_TURN_LEFT_HARD_TRIGGERS = {
			"guide_turn_left_hard_01",
			"guide_turn_left_hard_02",
			"guide_turn_left_hard_03"
	};
	private static final String[] GUIDE_TURN_LEFT_MEDIUM_TRIGGERS = {
			"guide_turn_left_medium_01",
			"guide_turn_left_medium_02",
			"guide_turn_left_medium_03"
	};
	private static final String[] GUIDE_TURN_LEFT_SOFT_TRIGGERS = {
			"guide_turn_left_soft_01",
			"guide_turn_left_soft_02",
			"guide_turn_left_soft_03"
	};
	private static final String[] GUIDE_TURN_RIGHT_HARD_TRIGGERS = {
			"guide_turn_right_hard_01",
			"guide_turn_right_hard_02",
			"guide_turn_right_hard_03"
	};
	private static final String[] GUIDE_TURN_RIGHT_MEDIUM_TRIGGERS = {
			"guide_turn_right_medium_01",
			"guide_turn_right_medium_02",
			"guide_turn_right_medium_03"
	};
	private static final String[] GUIDE_TURN_RIGHT_SOFT_TRIGGERS = {
			"guide_turn_right_soft_01",
			"guide_turn_right_soft_02",
			"guide_turn_right_soft_03"
	};
	private static final String[] GUIDE_TURN_LEFT_RECOVER_TRIGGERS = {
			"guide_turn_left_recover_01",
			"guide_turn_left_recover_02",
			"guide_turn_left_recover_03"
	};
	private static final String[] GUIDE_TURN_RIGHT_RECOVER_TRIGGERS = {
			"guide_turn_right_recover_01",
			"guide_turn_right_recover_02",
			"guide_turn_right_recover_03"
	};
	private static final String[] GUIDE_TURN_AROUND_LEFT_TRIGGERS = {
			"guide_turn_around_left_01",
			"guide_turn_around_left_02",
			"guide_turn_around_left_03"
	};
	private static final String[] GUIDE_TURN_AROUND_RIGHT_TRIGGERS = {
			"guide_turn_around_right_01",
			"guide_turn_around_right_02",
			"guide_turn_around_right_03"
	};
	private static final String[] GUIDE_LOCKED_ON_TRIGGERS = {
			"guide_locked_on_01",
			"guide_locked_on_02",
			"guide_locked_on_03",
			"guide_locked_on_04"
	};
	private static final String[] GUIDE_HEADING_LOST_TRIGGERS = {
			"guide_heading_lost_01",
			"guide_heading_lost_02",
			"guide_heading_lost_03"
	};
	private static final String[] GUIDE_SERVER_IN_SIGHT_TRIGGERS = {
			"guide_server_in_sight_01",
			"guide_server_in_sight_02",
			"guide_server_in_sight_03"
	};
	private static final String[] GUIDE_CLOSE_PRESENCE_TRIGGERS = {
			"guide_close_presence_01",
			"guide_close_presence_02",
			"guide_close_presence_03"
	};
	private static final String[] GUIDE_FORWARD_FAR_TRIGGERS = {
			"guide_forward_far_01",
			"guide_forward_far_02",
			"guide_forward_far_03"
	};
	private static final String[] GUIDE_FORWARD_MID_TRIGGERS = {
			"guide_forward_mid_01",
			"guide_forward_mid_02",
			"guide_forward_mid_03"
	};
	private static final String[] GUIDE_FORWARD_NEAR_TRIGGERS = {
			"guide_forward_near_01",
			"guide_forward_near_02",
			"guide_forward_near_03"
	};
	private static final String[] GUIDE_FORWARD_CLOSE_TRIGGERS = {
			"guide_forward_close_01",
			"guide_forward_close_02",
			"guide_forward_close_03"
	};
	private static final String[] GUIDE_DROP_COIN_TRIGGERS = {
			"guide_drop_coin_01",
			"guide_drop_coin_02",
			"guide_drop_coin_03",
			"guide_drop_coin_04"
	};
	private static final String[] INTRO_GUIDED_BITCOIN_LOST_TRIGGERS = {
			"intro_guided_bitcoin_lost_01"
	};
	private static final String[] INTRO_GUIDED_BITCOIN_LOST_AGAIN_TRIGGERS = {
			"intro_guided_bitcoin_lost_again"
	};
	private static final String[] INTRO_GUIDED_BITCOIN_RECOVERED_TRIGGERS = {
			"intro_guided_bitcoin_recovered"
	};
	private static final String[] GUIDE_WRONG_WAY_TRIGGERS = {
			"guide_wrong_way_01",
			"guide_wrong_way_02",
			"guide_wrong_way_03",
			"guide_wrong_way_04",
			"guide_wrong_way_05",
			"guide_wrong_way_06",
			"guide_wrong_way_07",
			"guide_wrong_way_08",
			"guide_wrong_way_09",
			"guide_wrong_way_10",
			"guide_wrong_way_11",
			"guide_wrong_way_12"
	};
	private static final String[] GUIDE_STALL_ALIGNED_TRIGGERS = {
			"guide_stall_aligned_01",
			"guide_stall_aligned_02",
			"guide_stall_aligned_03"
	};
	private static final String[] GUIDE_STALL_MISALIGNED_TRIGGERS = {
			"guide_stall_misaligned_01",
			"guide_stall_misaligned_02",
			"guide_stall_misaligned_03"
	};
	private static final String[] GUIDE_PASSED_SERVER_TRIGGERS = {
			"guide_passed_server_01",
			"guide_passed_server_02",
			"guide_passed_server_03",
			"guide_passed_server_04",
			"guide_passed_server_05",
			"guide_passed_server_06",
			"guide_passed_server_07",
			"guide_passed_server_08"
	};
	private static final String[] GUIDE_ROUTE_REVERSE_LEFT_TRIGGERS = {
			"guide_route_reverse_left_01",
			"guide_route_reverse_left_02",
			"guide_route_reverse_left_03"
	};
	private static final String[] GUIDE_ROUTE_REVERSE_RIGHT_TRIGGERS = {
			"guide_route_reverse_right_01",
			"guide_route_reverse_right_02",
			"guide_route_reverse_right_03"
	};
	private static final String[] GUIDE_ROUTE_RETURN_PROGRESS_TRIGGERS = {
			"guide_route_return_progress_01",
			"guide_route_return_progress_02",
			"guide_route_return_progress_03",
			"guide_route_return_progress_04"
	};
	private static final String[] GUIDE_ROUTE_RESUME_LEFT_TRIGGERS = {
			"guide_route_resume_left_01",
			"guide_route_resume_left_02",
			"guide_route_resume_left_03",
			"guide_route_resume_left_04"
	};
	private static final String[] GUIDE_ROUTE_RESUME_RIGHT_TRIGGERS = {
			"guide_route_resume_right_01",
			"guide_route_resume_right_02",
			"guide_route_resume_right_03",
			"guide_route_resume_right_04"
	};
	private static final String[] GUIDE_ROUTE_RESUME_FORWARD_TRIGGERS = {
			"guide_route_resume_forward_01",
			"guide_route_resume_forward_02",
			"guide_route_resume_forward_03"
	};
	// At the fastest expected rate (10 players x 10 bitcoins/minute), 1,700
	// offerings keep the shared launch running for 17 minutes.
	private static final int SHARED_LAUNCH_REQUIRED_BITCOINS = 1_700;
	private static final int SHARED_ACTIVE_ORES_PER_PLAYER = 2;
	private static final int SHARED_LAUNCH_EXTRA_BITCOINS = 10;
	private static final int SHARED_LAUNCH_SUPPLY_VERSION = 1;
	private static final int SHARED_ORE_MIN_Y_OFFSET = 1;
	private static final int SHARED_ORE_MAX_Y_OFFSET = 4;
	private static final double SHARED_ORE_SERVER_BUFFER = 4.0D;
	private static final double SHARED_FEED_RADIUS = 0.35D;
	private static final double SHARED_FEED_PLAYER_MATCH_DISTANCE = 8.0D;
	private static final long SHARED_FINISH_DELAY_TICKS = 20L * 2L;
	private static final long MENU_PRICE_REACTION_COOLDOWN_TICKS = 20L * 3L;
	private static final int SHARED_LAUNCH_SERVER_POWER_PERCENT = 45;
	private static final int MENU_EXPLANATION_UNLOCK_PERCENT = 65;
	private static final int SHARED_LAUNCH_RACE_CONTROLS_PERCENT = 85;
	private static final long MENU_EXPLANATION_DELAY_TICKS = 0L;
	private static final String STARTUP_WORLDGEN_DISPLAY_TAG = "lg2_season_start_display";
	private static final String PERSONAL_EXIT_SIGN_DISPLAY_TAG = "lg2_season_start_exit_sign";
	private static final String PERSONAL_EXIT_SIGN_OWNER_TAG_PREFIX = "lg2_season_start_exit_sign_owner:";
	private static final int STARTUP_WORLDGEN_FRAME_COUNT = 35;
	private static final float STARTUP_WORLDGEN_VIEW_RANGE = 160.0F;
	private static final float STARTUP_WORLDGEN_MARGIN_BLOCKS = 8.0F;
	private static final float STARTUP_WORLDGEN_DISPLAY_SCALE = 2.0F / 3.0F;
	private static final float STARTUP_WORLDGEN_THICKNESS_SCALE = 0.25F;
	private static final double STARTUP_WORLDGEN_Y_OFFSET_FROM_OUTER_FLOOR = 1.25D;
	private static final int SCENE_BLOCK_SET_FLAGS = 2 | 16 | 32;
	private static final BlockState STARTUP_LIGHT_STATE = Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 15);
	// Building the scene touches a large cube of terrain. World access must stay on
	// the server thread, but every pass is deliberately small enough to keep clients alive.
	private static final int SCENE_BUILD_BATCH_BLOCKS = 1_024;
	private static final int SCENE_SNAPSHOT_BATCH_BLOCKS = 1_024;
	private static final int EXISTING_SERVER_SCAN_RADIUS = 48;
	private static final int EXISTING_SERVER_SCAN_VERTICAL_MARGIN = 24;
	private static final int LEGACY_FLOATING_SERVER_REPAIR_MIN_GAP = 24;
	private static final int LEGACY_FLOATING_SERVER_REPAIR_MIN_DROP = 12;
	private static final int WORLD_REVEAL_BOUNDARY_RESTORE_RADIUS = 4;
	private static final int SCENE_PHYSICS_FREEZE_RADIUS = 4;
	private static final Brightness STARTUP_WORLDGEN_BRIGHTNESS = Brightness.FULL_BRIGHT;
	private static final Identifier WORLD_REVEAL_EARTHQUAKE_SOUND_ID = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "season_start_earthquake");
	private static final Holder<SoundEvent> WORLD_REVEAL_EARTHQUAKE_SOUND = Holder.direct(SoundEvent.createVariableRangeEvent(WORLD_REVEAL_EARTHQUAKE_SOUND_ID));
	private static final float WORLD_REVEAL_EARTHQUAKE_VOLUME = 7.5F;
	private static final long WORLD_REVEAL_CRACKING_DURATION_TICKS = 20L * 30L;
	// Vanilla renders Darkness as max(0, cos(remainingTicks * PI / 40)) * 0.45.
	// This single instance begins at a zero crossing before the earthquake audio.
	// Its final peak hides the last terrain swap; it is removed at the following
	// zero crossing, so the player never sees a black-frame cut.
	private static final long WORLD_REVEAL_DARKNESS_EFFECT_DURATION_TICKS = 20L * 41L;
	private static final long WORLD_REVEAL_DARKNESS_FINAL_PEAK_OFFSET_TICKS = 20L * 37L;
	private static final long WORLD_REVEAL_DARKNESS_CLEAR_OFFSET_TICKS = 20L * 38L;
	private static final long WORLD_REVEAL_BLACKOUT_DURATION_TICKS = 80L;
	private static final long WORLD_REVEAL_BLACKOUT_REPOSITION_TICKS = 40L;
	private static final long WORLD_REVEAL_CRACK_START_BUFFER_TICKS = 4L;
	private static final double WORLD_REVEAL_VISIBLE_TARGET_PROGRESS = 1.0D;
	private static final int WORLD_REVEAL_VISIBLE_PROTECTION_RADIUS = 2;
	private static final int WORLD_REVEAL_VISIBLE_PROTECTION_HEIGHT = 4;
	private static final int WORLD_REVEAL_CRACK_PARTICLE_LOOKAHEAD_EPISODES = 5;
	private static final double WORLD_REVEAL_CRACK_SAMPLE_STEP = 0.42D;
	private static final double WORLD_REVEAL_PRIMARY_CRACK_DISPLACEMENT = 5.4D;
	private static final double WORLD_REVEAL_BRANCH_CRACK_DISPLACEMENT = 3.1D;
	private static final double WORLD_REVEAL_MAX_TERRAIN_GROWTH_RADIUS = 3.6D;
	private static final long WORLD_REVEAL_POST_START_MORNING_TIME = 1000L;
	private static final int WORLD_REVEAL_EPISODE_MAX_REVEAL_POSITIONS = 56;
	private static final int WORLD_REVEAL_EPISODE_MAX_PARTICLE_POINTS = 18;
	private static final int WORLD_REVEAL_MAX_BURSTS_PER_TICK = 2;
	private static final int WORLD_REVEAL_BLACKOUT_REVEAL_EPISODES_PER_TICK = 6;
	private static final int WORLD_REVEAL_SETTLE_REVEAL_EPISODES_PER_TICK = 12;
	private static final int WORLD_REVEAL_RELOCATE_DEFERRED_BATCH = 36;
	private static final int WORLD_REVEAL_SETTLE_DEFERRED_BATCH = 128;
	private static final DustParticleOptions WORLD_REVEAL_CRACK_CORE_PARTICLE = new DustParticleOptions(0x09090C, 1.18F);
	private static final DustParticleOptions WORLD_REVEAL_CRACK_EDGE_PARTICLE = new DustParticleOptions(0x2F3138, 0.78F);
	private static final long WORLD_REVEAL_RELOCATE_MIN_TICKS = 20L;
	private static final long WORLD_REVEAL_SETTLE_MIN_TICKS = 20L;
	private static final double WORLD_REVEAL_FINAL_TARGET_OFFSET = 1.02D;
	// Synced to the densest short-impact section of `Downloads/zemletryasenie-2.mp3` (7.1s-37.1s).
	private static final WorldRevealBurst[] WORLD_REVEAL_CRACK_BURSTS = {
			burst(7, 0.445F, 14, 0.545F),
			burst(18, 0.495F, 15, 0.586F),
			burst(26, 0.510F, 15, 0.598F),
			burst(37, 0.545F, 16, 0.627F),
			burst(44, 0.960F, 23, 0.968F),
			burst(50, 0.598F, 17, 0.670F),
			burst(56, 0.662F, 18, 0.723F),
			burst(65, 0.586F, 17, 0.661F),
			burst(74, 1.000F, 24, 1.000F),
			burst(83, 0.964F, 23, 0.970F),
			burst(91, 0.866F, 22, 0.890F),
			burst(100, 0.500F, 15, 0.590F),
			burst(108, 0.671F, 18, 0.730F),
			burst(119, 0.892F, 22, 0.912F),
			burst(129, 0.912F, 22, 0.928F),
			burst(136, 0.610F, 17, 0.681F),
			burst(144, 0.548F, 16, 0.630F),
			burst(155, 1.000F, 24, 1.000F),
			burst(164, 0.863F, 22, 0.888F),
			burst(172, 0.528F, 16, 0.613F),
			burst(180, 0.611F, 17, 0.681F),
			burst(186, 0.647F, 18, 0.711F),
			burst(195, 0.487F, 15, 0.579F),
			burst(207, 0.628F, 17, 0.695F),
			burst(214, 0.428F, 14, 0.531F),
			burst(221, 0.625F, 17, 0.693F),
			burst(241, 0.493F, 15, 0.584F),
			burst(249, 0.663F, 18, 0.723F),
			burst(256, 0.462F, 14, 0.559F),
			burst(271, 0.667F, 18, 0.727F),
			burst(279, 0.961F, 23, 0.968F),
			burst(288, 0.640F, 18, 0.704F),
			burst(298, 0.446F, 14, 0.546F),
			burst(305, 0.605F, 17, 0.676F),
			burst(315, 0.635F, 17, 0.701F),
			burst(321, 0.754F, 20, 0.799F),
			burst(333, 0.755F, 20, 0.799F),
			burst(341, 0.601F, 17, 0.673F),
			burst(352, 0.643F, 18, 0.707F),
			burst(366, 0.603F, 17, 0.675F),
			burst(390, 0.769F, 20, 0.811F),
			burst(397, 0.524F, 15, 0.610F),
			burst(403, 0.581F, 16, 0.656F),
			burst(411, 0.795F, 20, 0.832F),
			burst(424, 0.512F, 15, 0.600F),
			burst(430, 0.748F, 19, 0.793F),
			burst(438, 0.446F, 14, 0.546F),
			burst(444, 0.626F, 17, 0.694F),
			burst(451, 1.000F, 24, 1.000F),
			burst(458, 0.815F, 21, 0.849F),
			burst(473, 0.677F, 18, 0.735F),
			burst(485, 0.610F, 17, 0.680F),
			burst(496, 0.454F, 14, 0.552F),
			burst(509, 0.621F, 17, 0.689F),
			burst(521, 0.481F, 15, 0.574F),
			burst(527, 0.624F, 17, 0.692F),
			burst(537, 0.571F, 16, 0.648F),
			burst(554, 0.454F, 14, 0.552F),
			burst(560, 0.578F, 16, 0.654F),
			burst(573, 0.579F, 16, 0.655F),
			burst(586, 0.660F, 18, 0.721F),
			burst(597, 0.618F, 17, 0.687F)
	};
	private static final float WORLD_REVEAL_TOTAL_BURST_WEIGHT = computeWorldRevealTotalBurstWeight();

	private static final Map<UUID, PlayerSceneState> PLAYER_STATES = new LinkedHashMap<>();
	private static final Set<UUID> LEGACY_INTRO_TOOL_PURGED_PLAYERS = new HashSet<>();
	private static final Map<UUID, Long> GUIDED_OFFERING_VISIBLE_SINCE_TICKS = new HashMap<>();
	private static final Set<PlayerVisibilityPair> HIDDEN_PLAYER_PROFILE_PAIRS = new HashSet<>();
	private static final List<BlockPos> SHELL_DISSOLVE_ORDER = new ArrayList<>();
	private static final List<TerrainPlacement> WORLD_REVEAL_TERRAIN = new ArrayList<>();
	private static final List<BlockPos> WORLD_REVEAL_BARRIER_COLLISION = new ArrayList<>();
	private static final List<WorldRevealEpisode> WORLD_REVEAL_EPISODES = new ArrayList<>();
	private static final List<CompoundTag> WORLD_REVEAL_ENTITY_SNAPSHOTS = new ArrayList<>();
	private static final Map<Long, Integer> WORLD_REVEAL_SURFACE_Y = new LinkedHashMap<>();
	private static final Map<Long, BlockState> WORLD_REVEAL_TARGET_STATES = new HashMap<>();
	private static final Map<Long, BlockState> WORLD_REVEAL_BOUNDARY_TARGET_STATES = new HashMap<>();
	private static final Map<UUID, Vec3> WORLD_REVEAL_SAFE_TARGETS = new LinkedHashMap<>();
	private static final Set<Long> WORLD_REVEAL_REQUIRED_POSITIONS = new LinkedHashSet<>();
	private static final Set<Long> WORLD_REVEAL_DEFERRED_POSITIONS = new LinkedHashSet<>();
	private static final Set<Long> WORLD_REVEAL_REVEALED_POSITIONS = new HashSet<>();
	// Terrain that a player has already mined during the reveal belongs to the
	// player now; later reveal batches must never put the snapshot back there.
	private static final Set<Long> WORLD_REVEAL_PLAYER_MINED_POSITIONS = new HashSet<>();
	// Physics only propagates out of an actual player break. Animation placement
	// itself must not make sand fall or water start flowing across the reveal.
	private static final Set<Long> WORLD_REVEAL_PLAYER_PHYSICS_POSITIONS = new HashSet<>();
	private static final Set<BlockPos> SHARED_BITCOIN_POSITIONS = new LinkedHashSet<>();
	private static final Set<UUID> SCENE_BUILD_FLOATING_PLAYERS = new HashSet<>();
	private static final Map<UUID, StartupBiomeOverride> STARTUP_BIOME_OVERRIDES = new HashMap<>();
	private static final Map<StartupBiomePayloadKey, byte[]> STARTUP_BIOME_PAYLOAD_CACHE = new HashMap<>();
	private static CompletableFuture<WorldRevealPlan> worldRevealPlanFuture = null;
	private static CompletableFuture<PersistedWorldRevealSnapshot> worldRevealSnapshotLoadFuture = null;
	private static WorldRevealSnapshotLoadTask worldRevealSnapshotLoadTask = null;
	private static boolean worldRevealSnapshotLoadAttempted = false;
	private static boolean worldRevealPlanReady = false;
	private static boolean stateLoaded = false;
	private static boolean stateDirty = false;
	private static boolean bootstrapComplete = false;
	private static volatile boolean active = false;
	private static volatile boolean completed = false;
	private static boolean sceneBoundaryPhysicsFrozen = false;
	private static volatile boolean shellDissolving = false;
	private static volatile boolean worldRevealActive = false;
	private static boolean worldRevealRecoveryPending = false;
	private static boolean worldRevealBarriersPlaced = false;
	private static boolean scenePrepared = false;
	private static SceneBuildTask sceneBuildTask = null;
	private static int dissolveCursor = 0;
	private static long lastSharedLaunchProgressTick = Long.MIN_VALUE;
	private static long pendingSharedFinishTick = Long.MIN_VALUE;
	private static long pendingMenuExplanationTick = Long.MIN_VALUE;
	private static long worldRevealPhaseStartTick = Long.MIN_VALUE;
	private static long worldRevealCrackStartTick = Long.MIN_VALUE;
	private static long worldRevealCrackNotBeforeTick = Long.MIN_VALUE;
	private static long worldRevealMusicEndTick = Long.MIN_VALUE;
	private static long worldRevealDarknessClearTick = Long.MIN_VALUE;
	private static long worldRevealCompletionTick = Long.MIN_VALUE;
	private static int worldRevealDarknessPulseCount = 0;
	private static boolean worldRevealGameplayReleased = false;
	private static int sharedLaunchCollectedBitcoins = 0;
	private static int sharedLaunchRequiredBitcoins = 0;
	private static int sharedLaunchBitcoinSpawned = 0;
	private static int sharedLaunchBitcoinSupplyVersion = 0;
	private static boolean sharedLaunchBitcoinPositionIndexLoaded = false;
	private static boolean sharedLaunchIntroTriggered = false;
	private static boolean sharedLaunchServerPowerNarrationTriggered = false;
	private static boolean sharedLaunchRaceControlsTriggered = false;
	private static boolean menuExplanationActive = false;
	private static int startupWorldgenFrameIndex = Integer.MIN_VALUE;
	private static int worldRevealVisibleEpisodeCursor = 0;
	private static int worldRevealBurstCursor = 0;
	private static float worldRevealBurstWeightProgress = 0.0F;
	private static boolean worldRevealEarthquakeSoundStarted = false;
	private static boolean worldRevealDarknessRepositioned = false;
	private static WorldRevealPhase worldRevealPhase = WorldRevealPhase.NONE;
	private static ServerBossEvent sharedLaunchBossBar = null;
	private static Difficulty difficultyBeforeSeasonStart = null;
	private static PristineWorldFreezeState pristineWorldFreeze = null;
	private static volatile BlockPos serverAnchor = null;
	private static Direction.Axis serverStructureAxis = Direction.Axis.Z;

	private SeasonStartSystem() {
	}

	public static void register() {
		stateLoaded = false;
		stateDirty = false;
		bootstrapComplete = false;
		active = false;
		completed = false;
		sceneBoundaryPhysicsFrozen = false;
		shellDissolving = false;
		worldRevealActive = false;
		worldRevealRecoveryPending = false;
		worldRevealBarriersPlaced = false;
		scenePrepared = false;
		sceneBuildTask = null;
		dissolveCursor = 0;
		lastSharedLaunchProgressTick = Long.MIN_VALUE;
		pendingSharedFinishTick = Long.MIN_VALUE;
		pendingMenuExplanationTick = Long.MIN_VALUE;
		worldRevealPhaseStartTick = Long.MIN_VALUE;
		worldRevealCrackStartTick = Long.MIN_VALUE;
		worldRevealCrackNotBeforeTick = Long.MIN_VALUE;
		worldRevealMusicEndTick = Long.MIN_VALUE;
		worldRevealDarknessClearTick = Long.MIN_VALUE;
		worldRevealCompletionTick = Long.MIN_VALUE;
		worldRevealDarknessPulseCount = 0;
		worldRevealGameplayReleased = false;
		sharedLaunchCollectedBitcoins = 0;
		sharedLaunchRequiredBitcoins = 0;
		sharedLaunchBitcoinSpawned = 0;
		sharedLaunchBitcoinSupplyVersion = SHARED_LAUNCH_SUPPLY_VERSION;
		sharedLaunchBitcoinPositionIndexLoaded = false;
		sharedLaunchIntroTriggered = false;
		sharedLaunchServerPowerNarrationTriggered = false;
		sharedLaunchRaceControlsTriggered = false;
		menuExplanationActive = false;
		startupWorldgenFrameIndex = Integer.MIN_VALUE;
		worldRevealVisibleEpisodeCursor = 0;
		worldRevealBurstCursor = 0;
		worldRevealBurstWeightProgress = 0.0F;
		worldRevealEarthquakeSoundStarted = false;
		worldRevealDarknessRepositioned = false;
		worldRevealPhase = WorldRevealPhase.NONE;
		sharedLaunchBossBar = null;
		difficultyBeforeSeasonStart = null;
		pristineWorldFreeze = null;
		serverAnchor = null;
		serverStructureAxis = Direction.Axis.Z;
		PLAYER_STATES.clear();
		LEGACY_INTRO_TOOL_PURGED_PLAYERS.clear();
		GUIDED_OFFERING_VISIBLE_SINCE_TICKS.clear();
		HIDDEN_PLAYER_PROFILE_PAIRS.clear();
		SHELL_DISSOLVE_ORDER.clear();
		WORLD_REVEAL_TERRAIN.clear();
		WORLD_REVEAL_BARRIER_COLLISION.clear();
		WORLD_REVEAL_EPISODES.clear();
		WORLD_REVEAL_ENTITY_SNAPSHOTS.clear();
		WORLD_REVEAL_SURFACE_Y.clear();
		WORLD_REVEAL_TARGET_STATES.clear();
		WORLD_REVEAL_BOUNDARY_TARGET_STATES.clear();
		WORLD_REVEAL_SAFE_TARGETS.clear();
		WORLD_REVEAL_REQUIRED_POSITIONS.clear();
		WORLD_REVEAL_DEFERRED_POSITIONS.clear();
		WORLD_REVEAL_REVEALED_POSITIONS.clear();
		WORLD_REVEAL_PLAYER_MINED_POSITIONS.clear();
		WORLD_REVEAL_PLAYER_PHYSICS_POSITIONS.clear();
		STARTUP_BIOME_OVERRIDES.clear();
		STARTUP_BIOME_PAYLOAD_CACHE.clear();
		worldRevealPlanFuture = null;
		worldRevealSnapshotLoadFuture = null;
		worldRevealSnapshotLoadTask = null;
		worldRevealSnapshotLoadAttempted = false;
		worldRevealPlanReady = false;
		SHARED_BITCOIN_POSITIONS.clear();
		SCENE_BUILD_FLOATING_PLAYERS.clear();

		ServerLifecycleEvents.SERVER_STARTED.register(SeasonStartSystem::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(SeasonStartSystem::onServerStopping);
		ServerTickEvents.START_SERVER_TICK.register(SeasonStartSystem::preTickServer);
		ServerTickEvents.END_SERVER_TICK.register(SeasonStartSystem::tickServer);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
				server.execute(() -> onPlayerJoined(server, (ServerPlayer) handler.player)));
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> onPlayerJoined(newPlayer.level().getServer(), newPlayer));
		ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(SeasonStartSystem::onAllowChatMessage);

		PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
			if (!(world instanceof ServerLevel level) || !(player instanceof ServerPlayer serverPlayer)) {
				return true;
			}
			return onBeforeBlockBreak(level, serverPlayer, pos);
		});
		PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
			if (!(world instanceof ServerLevel level) || pos == null) {
				return;
			}
			if (worldRevealActive) {
				rememberWorldRevealPlayerMine(level, pos);
			}
			if (state != null && isSharedBitcoinBlock(state)) {
				restoreStartupLight(level, pos);
			}
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(
						Commands.literal("seasonstart")
								.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
								.executes(SeasonStartSystem::toggleSeasonStart)
								.then(Commands.literal("status")
										.executes(SeasonStartSystem::printStatus))
								.then(Commands.literal("skip")
										.then(Commands.argument("player", EntityArgument.player())
												.executes(SeasonStartSystem::skipPersonalStage)))
				)
		);
	}

	public static boolean isStartParticipant(ServerPlayer player) {
		return isSeasonStartEligiblePlayer(player)
				&& PLAYER_STATES.containsKey(player.getUUID())
				&& (active || shellDissolving || worldRevealActive);
	}

	/** Camera renderer bots must never receive tutorial state, inventory changes, or narration. */
	public static boolean isSeasonStartEligiblePlayer(ServerPlayer player) {
		return player != null && !RendererBotPresenceSystem.isRendererBot(player);
	}

	public static boolean isInSharedPhase(ServerPlayer player) {
		PlayerSceneState state = player == null ? null : PLAYER_STATES.get(player.getUUID());
		return state != null && state.phase == PlayerPhase.SHARED;
	}

	/** Shared narration is available only after personal onboarding; live progress lines are coalesced. */
	public static boolean canReceiveSharedNarration(ServerPlayer player) {
		return isSeasonStartEligiblePlayer(player) && (!active || isInSharedPhase(player));
	}

	public static boolean isLiveVoiceControlled(ServerPlayer player) {
		return active && isSeasonStartEligiblePlayer(player) && PLAYER_STATES.containsKey(player.getUUID());
	}

	public static boolean canRelayLiveVoice(ServerPlayer player) {
		PlayerSceneState state = player == null ? null : PLAYER_STATES.get(player.getUUID());
		return active && isSeasonStartEligiblePlayer(player) && state != null && state.phase == PlayerPhase.SHARED;
	}

	/** Bitcoin mined during the launch is a progress resource, never an XP source. */
	public static boolean isExperienceSuppressed(ServerLevel level) {
		return active && level != null && Level.OVERWORLD.equals(level.dimension());
	}

	public static boolean canHearLiveVoice(ServerPlayer sender, ServerPlayer receiver) {
		if (!active || sender == null || receiver == null || sender == receiver) {
			return false;
		}
		PlayerSceneState senderState = PLAYER_STATES.get(sender.getUUID());
		PlayerSceneState receiverState = PLAYER_STATES.get(receiver.getUUID());
		return senderState != null
				&& receiverState != null
				&& senderState.phase == PlayerPhase.SHARED
				&& receiverState.phase == PlayerPhase.SHARED
				&& sender.level().dimension().equals(receiver.level().dimension());
	}

	/**
	 * Keeps every private intro physically separate from every other player until its light cue ends.
	 * This is intentionally server-side: clients never begin tracking the hidden player in the first place.
	 */
	public static boolean shouldSuppressEntityTracking(ServerPlayer receiver, Entity entity) {
		if (receiver == null || entity == null || entity == receiver || !active) {
			return false;
		}
		if (entity.getTags().contains(PERSONAL_EXIT_SIGN_DISPLAY_TAG)) {
			return !entity.getTags().contains(personalExitSignOwnerTag(receiver.getUUID()));
		}
		if (entity instanceof ServerPlayer subject) {
			return shouldHidePlayerFrom(receiver, subject);
		}
		if (!(entity instanceof ItemEntity itemEntity)) {
			return false;
		}
		Entity owner = itemEntity.getOwner();
		if (isInPrivateIntroPhase(receiver)) {
			// A guided player's own offering is the one deliberate exception: it is
			// visible only to its owner while it travels into the server.
			return owner != receiver || !isGuidedBitcoinOffering(itemEntity);
		}
		return owner instanceof ServerPlayer ownerPlayer && isInPrivateIntroPhase(ownerPlayer);
	}

	public static boolean shouldHidePlayerFrom(ServerPlayer receiver, ServerPlayer subject) {
		return receiver != null
				&& subject != null
				&& receiver != subject
				&& active
				&& (isInPrivateIntroPhase(receiver) || isInPrivateIntroPhase(subject));
	}

	public static boolean shouldBlockEntityInteraction(ServerPlayer actor, Entity target) {
		if (actor == null || target == null || !active) {
			return false;
		}
		if (target.getTags().contains(PERSONAL_EXIT_SIGN_DISPLAY_TAG)) {
			return true;
		}
		if (target instanceof ServerPlayer targetPlayer) {
			return shouldHidePlayerFrom(actor, targetPlayer);
		}
		return target instanceof ItemEntity && isInPrivateIntroPhase(actor);
	}

	public static boolean shouldBlockItemPickup(ServerPlayer player, ItemEntity itemEntity) {
		if (player == null || itemEntity == null || !active) {
			return false;
		}
		if (isInPrivateIntroPhase(player)) {
			PlayerSceneState state = PLAYER_STATES.get(player.getUUID());
			if (itemEntity.getOwner() == player
					&& state != null
					&& state.phase == PlayerPhase.GUIDED_TO_SERVER
					&& state.guidedBitcoinEscapeCount >= GUIDED_OFFERING_MAX_ESCAPES
					&& state.escapedGuidedOfferingId == null
					&& itemEntity.getItem().is(ModItems.BITCOIN)) {
				return false;
			}
			return true;
		}
		Entity owner = itemEntity.getOwner();
		return owner instanceof ServerPlayer ownerPlayer && isInPrivateIntroPhase(ownerPlayer);
	}

	/** Prevents a player who has already lost the tutorial coin twice from dropping it a third time. */
	public static boolean shouldBlockLockedGuidedBitcoinDrop(ServerPlayer player, ItemStack dropped) {
		if (player == null || dropped == null || dropped.isEmpty() || !dropped.is(ModItems.BITCOIN) || !active) {
			return false;
		}
		PlayerSceneState state = PLAYER_STATES.get(player.getUUID());
		if (state == null || state.phase != PlayerPhase.GUIDED_TO_SERVER
				|| state.lockedGuidedBitcoinSlot == NO_LOCKED_GUIDED_BITCOIN_SLOT) {
			return false;
		}
		keepLockedGuidedBitcoinInSlot(player, state, dropped);
		return true;
	}

	public static boolean shouldBlockEntityPush(Entity first, Entity second) {
		return first instanceof ServerPlayer firstPlayer
				&& second instanceof ServerPlayer secondPlayer
				&& shouldHidePlayerFrom(firstPlayer, secondPlayer);
	}

	public static boolean shouldOverrideStabilityHud() {
		return (active || worldRevealActive) && !completed;
	}

	public static float getStartupHudProgress() {
		if (sharedLaunchRequiredBitcoins <= 0) {
			return 0.0F;
		}
		return Mth.clamp((float) sharedLaunchCollectedBitcoins / (float) sharedLaunchRequiredBitcoins, 0.0F, 1.0F);
	}

	public static boolean shouldSuspendStabilitySystem() {
		return (active || worldRevealActive) && !completed;
	}

	/**
	 * The startup cube is transient presentation geometry, not terrain.  A map
	 * capture made while it exists would cache the black shell at world centre
	 * and, because new tiles have strict priority, hold the whole map queue
	 * there.  The check works from immutable chunk coordinates so the async MCA
	 * inventory can defer the same area safely.
	 */
	static boolean shouldDeferYandexMapChunk(ResourceKey<Level> dimension, ChunkPos chunkPos) {
		BlockPos anchor = serverAnchor;
		if (dimension == null
				|| chunkPos == null
				|| !Level.OVERWORLD.equals(dimension)
				|| completed
				|| !(active || shellDissolving || worldRevealActive)
				|| anchor == null) {
			return false;
		}
		BoxGeometry cube = computeOuterBoxGeometry(anchor);
		return chunkPos.getMaxBlockX() >= cube.minX
				&& chunkPos.getMinBlockX() <= cube.maxX
				&& chunkPos.getMaxBlockZ() >= cube.minZ
				&& chunkPos.getMinBlockZ() <= cube.maxZ;
	}

	public static boolean shouldFreezeSceneBoundaryPhysics(Level level, BlockPos pos) {
		if (!isInsideFrozenScenePhysicsArea(level, pos)) {
			return false;
		}
		return !WORLD_REVEAL_PLAYER_PHYSICS_POSITIONS.contains(pos.asLong());
	}

	/** Allows neighbour updates caused by a player break, but not by reveal placement. */
	public static boolean shouldFreezeSceneBoundaryPhysics(Level level, BlockPos pos, BlockPos neighborPos) {
		if (!isInsideFrozenScenePhysicsArea(level, pos)) {
			return false;
		}
		boolean playerTriggered = WORLD_REVEAL_PLAYER_PHYSICS_POSITIONS.contains(pos.asLong())
				|| (neighborPos != null && WORLD_REVEAL_PLAYER_PHYSICS_POSITIONS.contains(neighborPos.asLong()));
		if (!playerTriggered) {
			return true;
		}
		allowWorldRevealPlayerPhysics(level, pos);
		allowWorldRevealPlayerPhysics(level, neighborPos);
		return false;
	}

	/** Lets a fluid spread naturally only after its source was exposed by a player action. */
	public static void propagateWorldRevealPlayerPhysics(ServerLevel level, BlockPos pos) {
		if (level == null || pos == null || !WORLD_REVEAL_PLAYER_PHYSICS_POSITIONS.contains(pos.asLong())) {
			return;
		}
		for (Direction direction : Direction.values()) {
			allowWorldRevealPlayerPhysics(level, pos.relative(direction));
		}
	}

	private static boolean isInsideFrozenScenePhysicsArea(Level level, BlockPos pos) {
		if (!sceneBoundaryPhysicsFrozen || level == null || pos == null || serverAnchor == null
				|| !(level instanceof ServerLevel serverLevel) || !Level.OVERWORLD.equals(level.dimension())) {
			return false;
		}
		BoxGeometry outerGeometry = computeOuterBoxGeometry(resolveServerAnchor(serverLevel));
		return pos.getX() >= outerGeometry.minX - SCENE_PHYSICS_FREEZE_RADIUS
				&& pos.getX() <= outerGeometry.maxX + SCENE_PHYSICS_FREEZE_RADIUS
				&& pos.getY() >= outerGeometry.floorY - SCENE_PHYSICS_FREEZE_RADIUS
				&& pos.getY() <= outerGeometry.roofY + SCENE_PHYSICS_FREEZE_RADIUS
				&& pos.getZ() >= outerGeometry.minZ - SCENE_PHYSICS_FREEZE_RADIUS
				&& pos.getZ() <= outerGeometry.maxZ + SCENE_PHYSICS_FREEZE_RADIUS;
	}

	private static void allowWorldRevealPlayerPhysics(Level level, BlockPos pos) {
		if (pos != null && isInsideFrozenScenePhysicsArea(level, pos)) {
			WORLD_REVEAL_PLAYER_PHYSICS_POSITIONS.add(pos.asLong());
		}
	}

	/** The progression UI unlocks halfway through startup, while the shared launch keeps running. */
	public static boolean isServerMenuAvailable(ServerPlayer player) {
		if (!active) {
			return true;
		}
		PlayerSceneState state = player == null ? null : PLAYER_STATES.get(player.getUUID());
		return menuExplanationActive && state != null && state.phase == PlayerPhase.SHARED;
	}

	public static void onServerMenuRequestedTooEarly(ServerPlayer player) {
		// Before the menu phase begins, interacting with the server is intentionally silent.
	}

	/**
	 * While the startup box is intact, clients inside it only receive the chunks
	 * intersecting the startup box. This is a client-view boundary; the server's
	 * normal chunk tickets remain untouched so scene generation and the finale
	 * cannot stall waiting for a player ticket.
	 */
	public static boolean shouldUseStartupChunkTracking(ServerPlayer player) {
		if (!active
				|| completed
				|| worldRevealActive
				|| player == null
				|| !(player.level() instanceof ServerLevel level)
				|| !Level.OVERWORLD.equals(level.dimension())
				|| serverAnchor == null
				|| !isSeasonStartEligiblePlayer(player)) {
			return false;
		}
		return isInsideFootprint(computeOuterBoxGeometry(resolveServerAnchor(level)), player.blockPosition());
	}

	public static ChunkTrackingView createStartupChunkTrackingView(ServerPlayer player) {
		if (player == null || !(player.level() instanceof ServerLevel level) || serverAnchor == null) {
			return ChunkTrackingView.EMPTY;
		}
		BoxGeometry box = computeOuterBoxGeometry(resolveServerAnchor(level));
		Set<Long> chunks = collectStartupChunkKeys(box, STARTUP_CHUNK_TRACKING_GUARD_RING);
		return chunks.isEmpty() ? ChunkTrackingView.EMPTY : new StartupChunkTrackingView(chunks);
	}

	private static Set<Long> collectStartupChunkKeys(BoxGeometry box, int exteriorRing) {
		if (box == null) {
			return Set.of();
		}
		Set<Long> chunks = new LinkedHashSet<>();
		int ring = Math.max(0, exteriorRing);
		int minChunkX = Math.floorDiv(box.minX, 16) - ring;
		int maxChunkX = Math.floorDiv(box.maxX, 16) + ring;
		int minChunkZ = Math.floorDiv(box.minZ, 16) - ring;
		int maxChunkZ = Math.floorDiv(box.maxZ, 16) + ring;
		for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
			for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
				chunks.add(new ChunkPos(chunkX, chunkZ).toLong());
			}
		}
		return chunks;
	}

	public static int resolveStartupMenuPrice(ServerPlayer player, String upgradeId, int configuredPrice) {
		if (!active || !menuExplanationActive || player == null) {
			return Math.max(0, configuredPrice);
		}
		return ServerRaceSystem.isSeasonStartRaceAbility(player, upgradeId) ? 1 : 9_999;
	}

	/** Start-race abilities are usable immediately after their buttons are purchased. */
	public static boolean isSeasonStartRaceTestingPending(ServerPlayer player) {
		return false;
	}

	public static void onServerUpgradeScreenOpened(ServerPlayer player, String screenId) {
		if (!isServerMenuAvailable(player) || screenId == null || screenId.isBlank()) {
			return;
		}
		PlayerSceneState state = PLAYER_STATES.get(player.getUUID());
		MinecraftServer server = player.level().getServer();
		if (state == null || server == null) {
			return;
		}
		long nowTick = player.level().getGameTime();
		state.menuNarrationMuted = true;
		if (!state.menuOpened) {
			state.menuOpened = true;
			SeasonStartVoiceSystem.clearPlayerChannel(player);
			SeasonStartVoiceSystem.fireTrigger(server, "player_menu_opened", player);
		}

		MenuSection section = MenuSection.byScreenId(screenId);
		state.activeMenuSection = section.id;
		if (section == MenuSection.ROOT || !state.seenMenuSections.add(section.id)) {
			stateDirty = true;
			return;
		}
		SeasonStartVoiceSystem.clearPlayerChannel(player);
		SeasonStartVoiceSystem.fireTrigger(server, section.openTrigger, player);
		if (section == MenuSection.RACES) {
			state.raceMenuReached = true;
		}
		stateDirty = true;
	}

	public static void onServerUpgradePressed(ServerPlayer player, String screenId, String upgradeId) {
		if (!isServerMenuAvailable(player)) {
			return;
		}
		PlayerSceneState state = PLAYER_STATES.get(player.getUUID());
		MinecraftServer server = player.level().getServer();
		if (state == null || server == null) {
			return;
		}
		long nowTick = player.level().getGameTime();
		String itemExplanationTrigger = resolveMenuItemExplanationTrigger(upgradeId);
		if (itemExplanationTrigger != null) {
			String explanationKey = "menu_item:" + upgradeId;
			if (state.seenMenuSections.add(explanationKey)) {
				SeasonStartVoiceSystem.clearPlayerChannel(player);
				SeasonStartVoiceSystem.fireTrigger(server, itemExplanationTrigger, player);
			}
		} else if (!ServerRaceSystem.isSeasonStartRaceAbility(player, upgradeId)
				&& nowTick >= state.nextMenuPriceReactionTick) {
			state.nextMenuPriceReactionTick = nowTick + MENU_PRICE_REACTION_COOLDOWN_TICKS;
			SeasonStartVoiceSystem.clearPlayerChannel(player);
			SeasonStartVoiceSystem.fireTrigger(server, "player_menu_price_limit", player);
		}
		stateDirty = true;
	}

	/** Called only after the upgrade transaction succeeded, never merely after pressing its button. */
	public static void onServerUpgradePurchased(ServerPlayer player, String upgradeId) {
		if (!isServerMenuAvailable(player) || player == null || !ServerRaceSystem.isSeasonStartRaceAbility(player, upgradeId)) {
			return;
		}
		PlayerSceneState state = PLAYER_STATES.get(player.getUUID());
		MinecraftServer server = player.level().getServer();
		if (state == null || server == null || state.racePurchaseExplained) {
			return;
		}
		state.racePurchaseExplained = true;
		SeasonStartVoiceSystem.clearPlayerChannel(player);
		SeasonStartVoiceSystem.fireTrigger(server, "player_menu_race_purchase", player);
		stateDirty = true;
	}

	public static void onServerUpgradeButtonClicked(ServerPlayer player, String screenId, String buttonId) {
		if (!isServerMenuAvailable(player) || player == null || screenId == null || buttonId == null) {
			return;
		}
		if (!"main".equals(screenId)) {
			return;
		}
		String reactionKey;
		String trigger;
		if ("info".equals(buttonId)) {
			reactionKey = "info";
			trigger = "player_menu_info";
		} else {
			return;
		}
		PlayerSceneState state = PLAYER_STATES.get(player.getUUID());
		MinecraftServer server = player.level().getServer();
		if (state == null || server == null || !state.seenMenuSections.add(reactionKey)) {
			return;
		}
		SeasonStartVoiceSystem.clearPlayerChannel(player);
		SeasonStartVoiceSystem.fireTrigger(server, trigger, player);
		stateDirty = true;
	}

	public static void onServerUpgradeMenuClosed(ServerPlayer player, String screenId) {
		if (!isServerMenuAvailable(player) || player == null || screenId == null || screenId.isBlank()) {
			return;
		}
		PlayerSceneState state = PLAYER_STATES.get(player.getUUID());
		MinecraftServer server = player.level().getServer();
		if (state == null || server == null) {
			return;
		}
		state.menuNarrationMuted = false;
		if (!state.seenMenuSections.add("menu_closed")) {
			stateDirty = true;
			return;
		}
		SeasonStartVoiceSystem.clearPlayerChannel(player);
		SeasonStartVoiceSystem.fireTrigger(server, "player_menu_closed", player);
		stateDirty = true;
	}

	public static boolean shouldSuppressOutgoingPacket(ServerPlayer receiver, Packet<?> packet) {
		if (receiver == null || packet == null) {
			return false;
		}
		if (packet instanceof ClientboundPlayerChatPacket playerChatPacket) {
			ServerPlayer sender = receiver.level() instanceof ServerLevel level
					? level.getServer().getPlayerList().getPlayer(playerChatPacket.sender())
					: null;
			return isInPrivateIntroPhase(receiver) || shouldHidePlayerFrom(receiver, sender);
		}
		if (packet instanceof ClientboundDisguisedChatPacket && isInPrivateIntroPhase(receiver)) {
			return true;
		}
		if (packet instanceof ClientboundTrackedWaypointPacket waypointPacket
				&& shouldSuppressPlayerLocator(receiver, waypointPacket)) {
			return true;
		}
		if (packet instanceof ClientboundBlockUpdatePacket blockUpdatePacket
				&& shouldSuppressPrivateIntroBlockUpdate(receiver, blockUpdatePacket.getPos())) {
			return true;
		}
		if (shouldSuppressTrackedEntityPacket(receiver, packet)) {
			return true;
		}
		if (isInPrivateIntroPhase(receiver)) {
			return packet instanceof ClientboundSoundPacket
					|| packet instanceof ClientboundSoundEntityPacket
					|| packet instanceof ClientboundStopSoundPacket
					|| packet instanceof ClientboundLevelParticlesPacket;
		}
		if (packet instanceof ClientboundSoundEntityPacket soundEntityPacket) {
			return shouldSuppressEntityTracking(receiver, resolveEntity(receiver, soundEntityPacket.getId()));
		}
		if (packet instanceof ClientboundSoundPacket soundPacket) {
			return isPrivateIntroParticipantNear(receiver, soundPacket.getX(), soundPacket.getY(), soundPacket.getZ(), 5.0D);
		}
		if (packet instanceof ClientboundLevelParticlesPacket particlesPacket) {
			return isPrivateIntroParticipantNear(receiver, particlesPacket.getX(), particlesPacket.getY(), particlesPacket.getZ(), 3.0D);
		}
		return false;
	}

	/**
	 * Entity spawn packets are often bundled by the server. Filter each nested packet instead of
	 * cancelling the whole bundle, otherwise one hidden item can delay unrelated world updates.
	 */
	public static Packet<?> filterOutgoingPacket(ServerPlayer receiver, Packet<?> packet) {
		if (receiver == null || packet == null) {
			return packet;
		}
		if (packet instanceof ClientboundBundlePacket bundlePacket) {
			List<Packet<? super ClientGamePacketListener>> visiblePackets = new ArrayList<>();
			boolean changed = false;
			for (Packet<? super ClientGamePacketListener> bundledPacket : bundlePacket.subPackets()) {
				Packet<?> filteredPacket = filterOutgoingPacket(receiver, bundledPacket);
				if (filteredPacket == null) {
					changed = true;
					continue;
				}
				if (filteredPacket != bundledPacket) {
					changed = true;
				}
				@SuppressWarnings("unchecked")
				Packet<? super ClientGamePacketListener> gamePacket = (Packet<? super ClientGamePacketListener>) filteredPacket;
				visiblePackets.add(gamePacket);
			}
			if (!changed) {
				return packet;
			}
			return visiblePackets.isEmpty() ? null : new ClientboundBundlePacket(visiblePackets);
		}
		return shouldSuppressOutgoingPacket(receiver, packet) ? null : packet;
	}

	private static boolean shouldSuppressPlayerLocator(ServerPlayer receiver, ClientboundTrackedWaypointPacket packet) {
		if (receiver == null || packet == null || packet.waypoint() == null || receiver.level().getServer() == null) {
			return false;
		}
		if ("UNTRACK".equals(String.valueOf(packet.operation()))) {
			return false;
		}
		UUID targetId = packet.waypoint().id().left().orElse(null);
		if (targetId == null || targetId.equals(receiver.getUUID())) {
			return false;
		}
		ServerPlayer target = receiver.level().getServer().getPlayerList().getPlayer(targetId);
		return shouldHidePlayerFrom(receiver, target);
	}

	private static boolean shouldSuppressPrivateIntroBlockUpdate(ServerPlayer receiver, BlockPos pos) {
		if (receiver == null || pos == null || serverAnchor == null || !active) {
			return false;
		}
		PlayerSceneState receiverState = PLAYER_STATES.get(receiver.getUUID());
		if (SHARED_BITCOIN_POSITIONS.contains(pos)
				&& receiverState != null
				&& receiverState.phase != PlayerPhase.SHARED) {
			return true;
		}
		BoxGeometry barrierGeometry = computeBarrierGeometry(serverAnchor);
		MinecraftServer server = receiver.level().getServer();
		for (Map.Entry<UUID, PlayerSceneState> entry : PLAYER_STATES.entrySet()) {
			PlayerSceneState state = entry.getValue();
			if (state == null) {
				continue;
			}
			SlotDefinition slot = resolveSlotDefinition(barrierGeometry, state.slotIndex);
			if (slot == null || (!slot.orePos.equals(pos) && !slot.oreSupportPos.equals(pos))) {
				continue;
			}
			ServerPlayer owner = server.getPlayerList().getPlayer(entry.getKey());
			return owner != null && owner != receiver && shouldHidePlayerFrom(receiver, owner);
		}
		return false;
	}

	/**
	 * Chunk data can arrive after a block-update filter has run, for example when a player
	 * reconnects while somebody else is already in the shared phase. Explicitly correcting
	 * that player's client view keeps future-scene bitcoin blocks out of the private scene.
	 */
	private static void syncBitcoinVisibilityForPlayer(ServerLevel level, ServerPlayer player, PlayerSceneState playerState) {
		if (level == null || player == null || playerState == null || serverAnchor == null) {
			return;
		}
		if (playerState.phase == PlayerPhase.SHARED) {
			for (BlockPos pos : SHARED_BITCOIN_POSITIONS) {
				player.connection.send(new ClientboundBlockUpdatePacket(pos, level.getBlockState(pos)));
			}
			return;
		}
		for (BlockPos pos : SHARED_BITCOIN_POSITIONS) {
			player.connection.send(new ClientboundBlockUpdatePacket(pos, Blocks.AIR.defaultBlockState()));
		}
		BoxGeometry geometry = computeBarrierGeometry(serverAnchor);
		for (Map.Entry<UUID, PlayerSceneState> entry : PLAYER_STATES.entrySet()) {
			PlayerSceneState state = entry.getValue();
			if (state == null) {
				continue;
			}
			SlotDefinition slot = resolveSlotDefinition(geometry, state.slotIndex);
			if (slot == null) {
				continue;
			}
			boolean ownVisibleIntroOre = entry.getKey().equals(player.getUUID())
					&& playerState.phase == PlayerPhase.ISOLATED
					&& playerState.introOreRevealed
					&& !playerState.minedIntroBitcoin;
			player.connection.send(new ClientboundBlockUpdatePacket(
					slot.orePos,
					ownVisibleIntroOre ? level.getBlockState(slot.orePos) : Blocks.AIR.defaultBlockState()
			));
		}
	}

	private static boolean shouldSuppressTrackedEntityPacket(ServerPlayer receiver, Packet<?> packet) {
		if (packet instanceof ClientboundAddEntityPacket addEntityPacket) {
			return shouldSuppressEntityTracking(receiver, resolveEntity(receiver, addEntityPacket.getId()));
		}
		if (packet instanceof ClientboundMoveEntityPacket moveEntityPacket) {
			return shouldSuppressEntityTracking(receiver, moveEntityPacket.getEntity((ServerLevel) receiver.level()));
		}
		if (packet instanceof ClientboundTeleportEntityPacket teleportEntityPacket) {
			return shouldSuppressEntityTracking(receiver, resolveEntity(receiver, teleportEntityPacket.id()));
		}
		if (packet instanceof ClientboundSetEntityDataPacket entityDataPacket) {
			return shouldSuppressEntityTracking(receiver, resolveEntity(receiver, entityDataPacket.id()));
		}
		if (packet instanceof ClientboundSetEquipmentPacket equipmentPacket) {
			return shouldSuppressEntityTracking(receiver, resolveEntity(receiver, equipmentPacket.getEntity()));
		}
		if (packet instanceof ClientboundSetEntityMotionPacket motionPacket) {
			return shouldSuppressEntityTracking(receiver, resolveEntity(receiver, motionPacket.getId()));
		}
		if (packet instanceof ClientboundAnimatePacket animatePacket) {
			return shouldSuppressEntityTracking(receiver, resolveEntity(receiver, animatePacket.getId()));
		}
		if (packet instanceof ClientboundEntityEventPacket entityEventPacket) {
			return shouldSuppressEntityTracking(receiver, entityEventPacket.getEntity((ServerLevel) receiver.level()));
		}
		if (packet instanceof ClientboundSetEntityLinkPacket linkPacket) {
			return shouldSuppressEntityTracking(receiver, resolveEntity(receiver, linkPacket.getSourceId()))
					|| shouldSuppressEntityTracking(receiver, resolveEntity(receiver, linkPacket.getDestId()));
		}
		if (packet instanceof ClientboundSetPassengersPacket passengersPacket) {
			if (shouldSuppressEntityTracking(receiver, resolveEntity(receiver, passengersPacket.getVehicle()))) {
				return true;
			}
			for (int passengerId : passengersPacket.getPassengers()) {
				if (shouldSuppressEntityTracking(receiver, resolveEntity(receiver, passengerId))) {
					return true;
				}
			}
			return false;
		}
		if (packet instanceof ClientboundTakeItemEntityPacket takeItemPacket) {
			return shouldSuppressEntityTracking(receiver, resolveEntity(receiver, takeItemPacket.getItemId()))
					|| shouldSuppressEntityTracking(receiver, resolveEntity(receiver, takeItemPacket.getPlayerId()));
		}
		return false;
	}

	private static Entity resolveEntity(ServerPlayer receiver, int entityId) {
		if (receiver == null || !(receiver.level() instanceof ServerLevel level)) {
			return null;
		}
		return level.getEntity(entityId);
	}

	private static boolean isPrivateIntroParticipantNear(ServerPlayer receiver, double x, double y, double z, double radius) {
		if (receiver == null || !(receiver.level() instanceof ServerLevel level)) {
			return false;
		}
		double radiusSquared = radius * radius;
		for (ServerPlayer candidate : level.players()) {
			if (candidate != receiver && isInPrivateIntroPhase(candidate)
					&& candidate.distanceToSqr(x, y, z) <= radiusSquared) {
				return true;
			}
		}
		return false;
	}

	public static Vec3 resolveServerVoiceOrigin(MinecraftServer server, ServerPlayer focusPlayer) {
		ServerLevel level = server == null ? null : server.overworld();
		if (level == null) {
			return focusPlayer == null ? null : focusPlayer.position();
		}
		BlockPos anchor = resolveServerAnchor(level);
		return anchor == null ? null : new Vec3(anchor.getX() + 0.5D, anchor.getY() + 1.3D, anchor.getZ() + 0.5D);
	}

	public static void onServerFed(ServerLevel level, BlockPos fedServerPos) {
		if (!active || level == null || fedServerPos == null || serverAnchor == null || !serverAnchor.closerThan(fedServerPos, 3.0D)) {
			return;
		}
		ServerPlayer matched = null;
		double bestDistance = Double.MAX_VALUE;
		for (ServerPlayer player : level.players()) {
			PlayerSceneState state = PLAYER_STATES.get(player.getUUID());
			if (state == null || state.phase != PlayerPhase.GUIDED_TO_SERVER) {
				continue;
			}
			double distance = player.distanceToSqr(fedServerPos.getX() + 0.5D, fedServerPos.getY() + 0.5D, fedServerPos.getZ() + 0.5D);
			if (distance < bestDistance) {
				bestDistance = distance;
				matched = player;
			}
		}
		if (matched != null && bestDistance <= 64.0D) {
			transitionPlayerAfterFirstPayment(level.getServer(), matched);
			return;
		}
		if (countSharedPlayers() > 0) {
			incrementSharedLaunchProgress(level.getServer(), 1);
		}
	}

	public static void onPlayerAnimate(ServerPlayer player) {
		if (!active || player == null || !(player.level() instanceof ServerLevel level) || serverAnchor == null) {
			return;
		}
		PlayerSceneState state = PLAYER_STATES.get(player.getUUID());
		if (state == null || state.phase != PlayerPhase.ISOLATED || state.minedIntroBitcoin) {
			return;
		}
		SlotDefinition slot = resolveSlotDefinition(computeBarrierGeometry(resolveServerAnchor(level)), state.slotIndex);
		if (slot == null) {
			return;
		}

		long nowTick = level.getGameTime();
		state.lastActivityTick = nowTick;
		if (nowTick < state.guidanceNarrationGateTick) {
			updateObservationBaseline(player, state);
			return;
		}
		if (isLookingAtIntroOre(player, slot)) {
			if (shouldFireIntroTargetReaction(state, nowTick)) {
				fireIntroTargetReaction(level.getServer(), player, state, nowTick);
			}
			updateObservationBaseline(player, state);
			return;
		}
		if (nowTick < state.nextAirPunchReactionTick) {
			updateObservationBaseline(player, state);
			return;
		}

		state.nextAirPunchReactionTick = nowTick + INTRO_AIR_PUNCH_REPEAT_TICKS;
		updateObservationBaseline(player, state);
		SeasonStartVoiceSystem.fireTrigger(level.getServer(), "intro_phase1_air_punch", player);
	}

	private static void onServerStarted(MinecraftServer server) {
		loadState(server);
		clearPersonalExitSigns(server == null ? null : server.overworld());
		beginWorldRevealSnapshotLoad(server);
		sceneBoundaryPhysicsFrozen = active || worldRevealActive;
		releaseSceneBuildFlight(server);
		ensureBootstrap(server);
		removeRendererBotSceneStates(server);
		if (worldRevealActive) {
			beginWorldRevealRecovery(server);
		}
		applyStartDifficultyPolicy(server);
		if (completed && !active) {
			removeSceneShellNow(server.overworld());
		}
		if (!active && !worldRevealActive && server.overworld() != null) {
			clearStartupWorldgenDisplay(server.overworld());
		}
		if (active) {
			rebuildActiveScene(server);
		}
		if (serverAnchor != null && server.overworld() != null) {
			ServerBlock.ensureServerStructureDisplay(server.overworld(), serverAnchor, serverStructureAxis);
		}
	}

	private static void onServerStopping(MinecraftServer server) {
		saveState(server);
	}

	private static void removeRendererBotSceneStates(MinecraftServer server) {
		if (server == null) {
			return;
		}
		boolean changed = false;
		for (Iterator<Map.Entry<UUID, PlayerSceneState>> iterator = PLAYER_STATES.entrySet().iterator(); iterator.hasNext(); ) {
			Map.Entry<UUID, PlayerSceneState> entry = iterator.next();
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (!RendererBotPresenceSystem.isRendererBot(player)) {
				continue;
			}
			iterator.remove();
			ServerRaceSystem.clearSeasonStartRace(server, player);
			changed = true;
		}
		if (changed) {
			HIDDEN_PLAYER_PROFILE_PAIRS.removeIf(pair -> !PLAYER_STATES.containsKey(pair.viewerId) || !PLAYER_STATES.containsKey(pair.subjectId));
			stateDirty = true;
		}
	}

	private static void preTickServer(MinecraftServer server) {
		if (server == null || !active) {
			return;
		}
		ServerLevel overworld = server.overworld();
		if (overworld == null || serverAnchor == null || !scenePrepared) {
			return;
		}
		tickStartupOfferings(server, overworld);
	}

	private static void tickServer(MinecraftServer server) {
		if (server == null) {
			return;
		}
		if (!tickWorldRevealSnapshotLoad(server)) {
			return;
		}
		if ((active || worldRevealActive) && !worldRevealGameplayReleased && server.overworld() != null) {
			enforceStartupEnvironment(server.overworld());
		}
		if (active) {
			ServerLevel overworld = server.overworld();
			if (overworld != null) {
				if (!tickSceneBuild(server, overworld)) {
					return;
				}
				clearSceneMobs(overworld);
				for (ServerPlayer player : server.getPlayerList().getPlayers()) {
					if (!isSeasonStartEligiblePlayer(player)) {
						continue;
					}
					PlayerSceneState state = PLAYER_STATES.get(player.getUUID());
					if (state == null) {
						continue;
					}
					tickPlayerState(server, overworld, player, state);
				}
				tickSharedLaunch(server);
			}
		}
		if (worldRevealActive) {
			if (worldRevealRecoveryPending && !tickWorldRevealRecovery(server)) {
				return;
			}
			tickWorldReveal(server);
		}
		tickStartupBiomeOverrides(server);
		if (shellDissolving) {
			tickShellDissolve(server);
		}
	}

	private static void onPlayerJoined(MinecraftServer server, ServerPlayer player) {
		if (server == null || player == null) {
			return;
		}
		if (!isSeasonStartEligiblePlayer(player)) {
			PLAYER_STATES.remove(player.getUUID());
			ServerRaceSystem.clearSeasonStartRace(server, player);
			return;
		}
		if (active) {
			if (scenePrepared) {
				assignOrRestorePlayer(server, player, true);
				syncPrivatePlayerProfiles(server);
			} else if (player.level() instanceof ServerLevel level) {
				protectPlayersDuringSceneBuild(level, List.of(player));
			}
		} else if (worldRevealActive) {
			applyFreeState(player);
		} else if (shellDissolving) {
			applyFreeState(player);
		}
	}

	private static boolean onBeforeBlockBreak(ServerLevel level, ServerPlayer player, BlockPos pos) {
		if (level == null || player == null || serverAnchor == null || !Level.OVERWORLD.equals(level.dimension())) {
			return true;
		}
		if (worldRevealActive) {
			// The collapse is the beginning of normal gameplay. Let players mine the
			// terrain as soon as it is visible; only the actual server core remains protected.
			allowWorldRevealPlayerPhysics(level, pos);
			for (Direction direction : Direction.values()) {
				allowWorldRevealPlayerPhysics(level, pos.relative(direction));
			}
			return !isServerStructureFootprint(pos);
		}
		if (!active) {
			return true;
		}
		PlayerSceneState state = PLAYER_STATES.get(player.getUUID());
		if (state == null) {
			return true;
		}
		if (state.phase == PlayerPhase.WAITING_START) {
			return false;
		}
		if (SHARED_BITCOIN_POSITIONS.contains(pos)) {
			if (state.phase != PlayerPhase.SHARED) {
				return false;
			}
			return true;
		}
		SlotDefinition slot = resolveSlotDefinition(computeBarrierGeometry(resolveServerAnchor(level)), state.slotIndex);
		if (slot != null && slot.orePos.equals(pos) && !state.minedIntroBitcoin) {
			if (!state.introOreRevealed) {
				return false;
			}
			handleIntroOreBroken(level, player, pos, state);
			return false;
		}
		if (isProtectedSceneBlock(level, pos)) {
			return false;
		}
		return true;
	}

	private static void rememberWorldRevealPlayerMine(ServerLevel level, BlockPos pos) {
		if (level == null || pos == null || serverAnchor == null || isServerStructureFootprint(pos)) {
			return;
		}
		BoxGeometry outerGeometry = computeOuterBoxGeometry(resolveServerAnchor(level));
		if (!isInsideFootprint(outerGeometry, pos)) {
			return;
		}
		long key = pos.asLong();
		WORLD_REVEAL_PLAYER_MINED_POSITIONS.add(key);
		WORLD_REVEAL_REVEALED_POSITIONS.add(key);
		WORLD_REVEAL_DEFERRED_POSITIONS.remove(key);
	}

	private static boolean onAllowChatMessage(PlayerChatMessage message, ServerPlayer sender, ChatType.Bound params) {
		if (!active || message == null || sender == null || params == null) {
			return true;
		}
		PlayerSceneState state = PLAYER_STATES.get(sender.getUUID());
		if (state == null || state.phase == PlayerPhase.SHARED || state.phase == PlayerPhase.FREE) {
			return true;
		}
		MinecraftServer server = sender.level().getServer();
		if (server == null) {
			return false;
		}
		if (state.phase != PlayerPhase.WAITING_START) {
			return false;
		}

		// Any message proves that the player can hear the server. Keep it unmodified and begin.
		beginIntroAfterChatStart(server, sender, state);
		return true;
	}

	private static int toggleSeasonStart(CommandContext<CommandSourceStack> context) {
		MinecraftServer server = context.getSource().getServer();
		if (active) {
			if (sceneBuildTask != null) {
				context.getSource().sendFailure(Component.literal("Стартовая сцена ещё собирается. Дождитесь её появления."));
				return 0;
			}
			finishSeasonStart(server);
			context.getSource().sendSuccess(() -> Component.literal("Старт сезона завершён."), true);
		} else {
			startSeasonStart(server, false);
			context.getSource().sendSuccess(() -> Component.literal("Старт сезона запущен."), true);
		}
		return 1;
	}

	private static int printStatus(CommandContext<CommandSourceStack> context) {
		String anchorText = serverAnchor == null ? "none" : serverAnchor.getX() + ", " + serverAnchor.getY() + ", " + serverAnchor.getZ();
		context.getSource().sendSuccess(
				() -> Component.literal(
						"bootstrap=" + bootstrapComplete
								+ ", active=" + active
								+ ", completed=" + completed
								+ ", dissolving=" + shellDissolving
								+ ", players=" + PLAYER_STATES.size()
								+ ", shared=" + countSharedPlayers()
								+ ", launch=" + sharedLaunchCollectedBitcoins + "/" + Math.max(0, sharedLaunchRequiredBitcoins)
								+ ", ores=" + SHARED_BITCOIN_POSITIONS.size()
								+ ", anchor=" + anchorText
				),
				false
		);
		return 1;
	}

	/** Admin escape hatch for a player who cannot or should not complete the private tutorial. */
	private static int skipPersonalStage(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		MinecraftServer server = context.getSource().getServer();
		ServerPlayer player = EntityArgument.getPlayer(context, "player");
		if (!active || server == null) {
			context.getSource().sendFailure(Component.literal("Персональный этап можно пропустить только во время старта сезона."));
			return 0;
		}
		PlayerSceneState state = PLAYER_STATES.get(player.getUUID());
		if (state == null) {
			context.getSource().sendFailure(Component.literal("Игрок не участвует в стартовой сцене."));
			return 0;
		}
		if (state.phase == PlayerPhase.SHARED) {
			context.getSource().sendSuccess(() -> Component.literal(player.getName().getString() + " уже находится в общей фазе."), false);
			return 1;
		}

		ServerLevel level = server.overworld();
		if (level != null) {
			SlotDefinition slot = resolveSlotDefinition(computeBarrierGeometry(resolveServerAnchor(level)), state.slotIndex);
			if (slot != null) {
				clearIntroOre(level, slot);
			}
		}
		SeasonStartVoiceSystem.clearPlayerChannel(player);
		removeLegacyIntroTool(player);
		state.minedIntroBitcoin = true;
		state.poweredServer = true;
		state.sharedVisionRestored = true;
		state.pendingSharedPeersLine = false;
		state.restoreVisionTick = Long.MAX_VALUE;
		state.nextGuidanceTick = Long.MAX_VALUE;
		state.nextGuidanceVoiceTick = Long.MAX_VALUE;
		state.nextGuidanceEarliestTick = Long.MAX_VALUE;
		state.phase = PlayerPhase.SHARED;
		applySharedPlayerState(player);
		syncBitcoinVisibilityForPlayer(server.overworld(), player, state);
		syncPrivatePlayerProfiles(server);
		refreshSharedPlayerEntityTracking(server);
		onPlayerEnteredSharedPhase(server);
		stateDirty = true;
		context.getSource().sendSuccess(() -> Component.literal("Персональный этап игрока " + player.getName().getString() + " пропущен."), true);
		return 1;
	}

	private static void ensureBootstrap(MinecraftServer server) {
		ServerLevel overworld = server == null ? null : server.overworld();
		if (overworld == null) {
			return;
		}
		// The normal path must not scan every loaded entity just to start another
		// scene around the same, already verified server structure.
		if (bootstrapComplete && serverAnchor != null && isWholeServerStructurePresent(overworld, serverAnchor, serverStructureAxis)) {
			ServerBlock.ensureServerStructureDisplay(overworld, serverAnchor, serverStructureAxis);
			setDefaultSpawn(overworld);
			return;
		}
		ServerStructureBreakSystem.pruneStructureDisplays(overworld);
		BlockPos anchor = resolveServerAnchor(overworld);
		BlockPos bootstrapAnchor = resolveBootstrapAnchor(overworld);
		if (anchor == null) {
			anchor = bootstrapAnchor;
			rememberServerStructure(anchor, Direction.Axis.Z);
		}
		Direction.Axis anchorAxis = resolveKnownServerStructureAxis(overworld, anchor);
		if (!isServerStructurePresent(overworld, anchor)) {
			ResolvedServerStructure existingStructure = discoverExistingServerStructure(overworld, anchor, bootstrapAnchor);
			if (existingStructure != null) {
				anchor = existingStructure.anchor().immutable();
				anchorAxis = existingStructure.axis();
				rememberServerStructure(anchor, anchorAxis);
			}
		}
		ResolvedServerStructure anchoredStructure = resolveServerStructurePlacement(overworld, anchor);
		if (anchoredStructure != null && shouldRepairFloatingBootstrapServer(overworld, anchoredStructure, bootstrapAnchor)) {
			Direction.Axis repairAxis = anchoredStructure.axis();
			ServerStructureBreakSystem.clearStructureSilently(overworld, anchoredStructure.anchor(), repairAxis);
			anchor = bootstrapAnchor;
			anchorAxis = repairAxis;
			ServerBlock.placeServerStructure(overworld, anchor, repairAxis == Direction.Axis.X ? Direction.EAST : Direction.NORTH);
			rememberServerStructure(anchor, anchorAxis);
		}
		if (!isServerStructurePresent(overworld, anchor)) {
			ServerBlock.placeServerStructure(overworld, anchor, Direction.NORTH);
			rememberServerStructure(anchor, Direction.Axis.Z);
		} else if (anchorAxis != null) {
			rememberServerStructure(anchor, anchorAxis);
		}
		reconcileStackedServerDuplicates(overworld);
		BlockPos resolvedAnchor = resolveServerAnchor(overworld);
		Direction.Axis resolvedAxis = resolveKnownServerStructureAxis(overworld, resolvedAnchor);
		ServerBlock.ensureServerStructureDisplay(
				overworld,
				resolvedAnchor == null ? anchor : resolvedAnchor,
				resolvedAxis == null ? serverStructureAxis : resolvedAxis
		);
		setDefaultSpawn(overworld);
		if (!bootstrapComplete) {
			bootstrapComplete = true;
			stateDirty = true;
			if (get().autoStartOnFirstLaunch && !completed) {
				startSeasonStart(server, true);
			}
		}
	}

	private static void applyStartDifficultyPolicy(MinecraftServer server) {
		if (server == null) {
			return;
		}
		if ((active || worldRevealActive) && !completed) {
			enforcePeacefulDifficulty(server);
			enforcePristineWorldFreeze(server.overworld());
			return;
		}
		restoreSeasonStartDifficulty(server);
		restorePristineWorldFreeze(server, server.overworld());
	}

	private static void enforcePeacefulDifficulty(MinecraftServer server) {
		if (server == null) {
			return;
		}
		if (difficultyBeforeSeasonStart == null && server.overworld() != null) {
			difficultyBeforeSeasonStart = server.overworld().getDifficulty();
			stateDirty = true;
		}
		if (server.overworld() != null && server.overworld().getDifficulty() != Difficulty.PEACEFUL) {
			server.setDifficulty(Difficulty.PEACEFUL, true);
		}
	}

	private static void restoreSeasonStartDifficulty(MinecraftServer server) {
		if (server == null) {
			return;
		}
		Difficulty configuredDifficulty = resolveConfiguredDifficulty(server);
		Difficulty targetDifficulty = configuredDifficulty == null ? difficultyBeforeSeasonStart : configuredDifficulty;
		if (targetDifficulty != null && server.overworld() != null && server.overworld().getDifficulty() != targetDifficulty) {
			server.setDifficulty(targetDifficulty, true);
		}
		difficultyBeforeSeasonStart = null;
		stateDirty = true;
	}

	private static Difficulty resolveConfiguredDifficulty(MinecraftServer server) {
		if (server instanceof DedicatedServer dedicatedServer && dedicatedServer.getProperties() != null) {
			Object configured = dedicatedServer.getProperties().difficulty.get();
			if (configured instanceof Difficulty difficulty) {
				return difficulty;
			}
		}
		return null;
	}

	private static void rebuildActiveScene(MinecraftServer server) {
		ServerLevel overworld = server == null ? null : server.overworld();
		if (overworld == null || isWorldRevealSnapshotLoading()) {
			return;
		}
		ensureSceneBuilt(overworld);
	}

	private static void startSeasonStart(MinecraftServer server, boolean automatic) {
		if (server == null) {
			return;
		}
		ServerLevel overworld = server.overworld();
		if (overworld == null) {
			return;
		}
		ensureBootstrap(server);
		// A new season always begins at the first morning of day zero.  Keep this
		// separate from gameTime: the latter is the server's tick clock and drives
		// the start animation, while dayTime is what vanilla's day counter displays.
		resetSeasonStartClock(overworld);
		enforcePristineWorldFreeze(overworld);
		active = true;
		completed = false;
		sceneBoundaryPhysicsFrozen = true;
		shellDissolving = false;
		worldRevealActive = false;
		worldRevealRecoveryPending = false;
		worldRevealBarriersPlaced = false;
		scenePrepared = false;
		sceneBuildTask = null;
		dissolveCursor = 0;
		lastSharedLaunchProgressTick = Long.MIN_VALUE;
		pendingSharedFinishTick = Long.MIN_VALUE;
		pendingMenuExplanationTick = Long.MIN_VALUE;
		worldRevealPhaseStartTick = Long.MIN_VALUE;
		worldRevealCrackStartTick = Long.MIN_VALUE;
		worldRevealCrackNotBeforeTick = Long.MIN_VALUE;
		worldRevealMusicEndTick = Long.MIN_VALUE;
		worldRevealDarknessClearTick = Long.MIN_VALUE;
		worldRevealCompletionTick = Long.MIN_VALUE;
		worldRevealDarknessPulseCount = 0;
		worldRevealGameplayReleased = false;
		sharedLaunchCollectedBitcoins = 0;
		sharedLaunchRequiredBitcoins = 0;
		sharedLaunchBitcoinSpawned = 0;
		sharedLaunchBitcoinSupplyVersion = SHARED_LAUNCH_SUPPLY_VERSION;
		sharedLaunchBitcoinPositionIndexLoaded = false;
		sharedLaunchIntroTriggered = false;
		sharedLaunchServerPowerNarrationTriggered = false;
		sharedLaunchRaceControlsTriggered = false;
		menuExplanationActive = false;
		startupWorldgenFrameIndex = Integer.MIN_VALUE;
		worldRevealVisibleEpisodeCursor = 0;
		worldRevealBurstCursor = 0;
		worldRevealBurstWeightProgress = 0.0F;
		worldRevealEarthquakeSoundStarted = false;
		worldRevealDarknessRepositioned = false;
		worldRevealPhase = WorldRevealPhase.NONE;
		clearSharedLaunchBossBar();
		if (difficultyBeforeSeasonStart == null && overworld != null) {
			difficultyBeforeSeasonStart = overworld.getDifficulty();
		}
		PLAYER_STATES.clear();
		LEGACY_INTRO_TOOL_PURGED_PLAYERS.clear();
		GUIDED_OFFERING_VISIBLE_SINCE_TICKS.clear();
		SHELL_DISSOLVE_ORDER.clear();
		WORLD_REVEAL_TERRAIN.clear();
		WORLD_REVEAL_BARRIER_COLLISION.clear();
		WORLD_REVEAL_EPISODES.clear();
		WORLD_REVEAL_ENTITY_SNAPSHOTS.clear();
		WORLD_REVEAL_SURFACE_Y.clear();
		WORLD_REVEAL_TARGET_STATES.clear();
		WORLD_REVEAL_BOUNDARY_TARGET_STATES.clear();
		WORLD_REVEAL_SAFE_TARGETS.clear();
		WORLD_REVEAL_REQUIRED_POSITIONS.clear();
		WORLD_REVEAL_DEFERRED_POSITIONS.clear();
		WORLD_REVEAL_REVEALED_POSITIONS.clear();
		WORLD_REVEAL_PLAYER_MINED_POSITIONS.clear();
		WORLD_REVEAL_PLAYER_PHYSICS_POSITIONS.clear();
		STARTUP_BIOME_OVERRIDES.clear();
		STARTUP_BIOME_PAYLOAD_CACHE.clear();
		worldRevealPlanFuture = null;
		worldRevealSnapshotLoadFuture = null;
		worldRevealSnapshotLoadTask = null;
		worldRevealSnapshotLoadAttempted = false;
		worldRevealPlanReady = false;
		deleteWorldRevealSnapshot(server);
		SHARED_BITCOIN_POSITIONS.clear();
		releaseSceneBuildFlight(server);
		SeasonStartVoiceSystem.resetSceneState();
		stopWorldRevealEarthquakeSound(overworld);
		enforcePeacefulDifficulty(server);
		// Also terminates any ability sessions from a previous race, including XP-draining ones.
		ServerRaceSystem.beginSeasonStartRaces(server, get().startupRaceId);
		ensureSceneBuilt(overworld);
		stateDirty = true;
		// Persist the phase before the asynchronous snapshot starts changing terrain.
		saveState(server);
	}

	private static void finishSeasonStart(MinecraftServer server) {
		if (server == null) {
			return;
		}
		active = false;
		completed = false;
		// Reveal placement must not trigger terrain physics. Player breaks explicitly
		// open a local physics chain through the guarded methods above.
		sceneBoundaryPhysicsFrozen = true;
		shellDissolving = false;
		worldRevealActive = true;
		worldRevealRecoveryPending = false;
		worldRevealBarriersPlaced = false;
		scenePrepared = false;
		sceneBuildTask = null;
		startupWorldgenFrameIndex = Integer.MIN_VALUE;
		dissolveCursor = 0;
		lastSharedLaunchProgressTick = Long.MIN_VALUE;
		pendingSharedFinishTick = Long.MIN_VALUE;
		pendingMenuExplanationTick = Long.MIN_VALUE;
		menuExplanationActive = false;
		worldRevealPhaseStartTick = Long.MIN_VALUE;
		worldRevealCrackStartTick = Long.MIN_VALUE;
		worldRevealCrackNotBeforeTick = Long.MIN_VALUE;
		worldRevealMusicEndTick = Long.MIN_VALUE;
		worldRevealDarknessClearTick = Long.MIN_VALUE;
		worldRevealCompletionTick = Long.MIN_VALUE;
		worldRevealDarknessPulseCount = 0;
		worldRevealGameplayReleased = false;
		worldRevealVisibleEpisodeCursor = 0;
		worldRevealBurstCursor = 0;
		worldRevealBurstWeightProgress = 0.0F;
		worldRevealEarthquakeSoundStarted = false;
		worldRevealDarknessRepositioned = false;
		worldRevealPhase = WorldRevealPhase.CRACKING;
		SeasonStartVoiceSystem.clearSharedLaunchProgressNarration();
		clearSharedLaunchBossBar();
		ServerRaceSystem.endSeasonStartRaces(server);
		releaseSceneBuildFlight(server);
		ServerLevel overworld = server.overworld();
		if (overworld != null) {
			stopWorldRevealEarthquakeSound(overworld);
			clearStartupWorldgenDisplay(overworld);
			clearSharedBitcoins(overworld, true);
			SHELL_DISSOLVE_ORDER.clear();
			WORLD_REVEAL_EPISODES.clear();
			WORLD_REVEAL_REQUIRED_POSITIONS.clear();
			WORLD_REVEAL_DEFERRED_POSITIONS.clear();
			WORLD_REVEAL_REVEALED_POSITIONS.clear();
			WORLD_REVEAL_PLAYER_MINED_POSITIONS.clear();
			WORLD_REVEAL_PLAYER_PHYSICS_POSITIONS.clear();
			worldRevealPlanReady = false;
			WORLD_REVEAL_SAFE_TARGETS.clear();
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				if (isSeasonStartEligiblePlayer(player)) {
					applyFreeState(player);
				}
			}
			syncPrivatePlayerProfiles(server);
			SeasonStartVoiceSystem.resetSceneState();
			SeasonStartVoiceSystem.fireTrigger(server, "season_finished", null);
			worldRevealCrackNotBeforeTick = overworld.getGameTime()
					+ resolveTriggerSequenceDurationTicks("season_finished")
					+ WORLD_REVEAL_CRACK_START_BUFFER_TICKS;
		}
		stateDirty = true;
		saveState(server);
	}

	private static void assignOrRestorePlayer(MinecraftServer server, ServerPlayer player, boolean announceIntro) {
		if (server == null || !isSeasonStartEligiblePlayer(player) || !active || !scenePrepared) {
			return;
		}
		ServerRaceSystem.assignSeasonStartRace(player, get().startupRaceId);
		ServerLevel overworld = server.overworld();
		if (overworld == null) {
			return;
		}
		BoxGeometry geometry = computeBarrierGeometry(resolveServerAnchor(overworld));
		PlayerSceneState state = PLAYER_STATES.get(player.getUUID());
		boolean created = false;
		if (state == null) {
			state = new PlayerSceneState();
			state.slotIndex = allocateSlotIndex(geometry);
			state.phase = PlayerPhase.WAITING_START;
			PLAYER_STATES.put(player.getUUID(), state);
			created = true;
			stateDirty = true;
		}
		SlotDefinition slot = resolveSlotDefinition(geometry, state.slotIndex);
		if (slot == null) {
			return;
		}
		// Reconnecting is the one time a private-scene presentation must be
		// re-applied. During normal ticks it is left alone: the tutorial does not
		// need to keep rewriting the player's game mode and effects every 50 ms.
		state.seasonStartPresentationApplied = false;
		teleportPlayer(player, overworld, slot.spawnPos, slot.yaw);
		if (state.phase == PlayerPhase.WAITING_START) {
			ensureWaitingStartPlayerState(player, state, slot);
		} else if (state.phase == PlayerPhase.ISOLATED || state.phase == PlayerPhase.GUIDED_TO_SERVER) {
			ensureIntroPlayerState(player, state, slot);
			if (state.phase == PlayerPhase.GUIDED_TO_SERVER) {
				restoreGuidedBitcoinAfterReconnect(player, state);
			}
		} else if (state.phase == PlayerPhase.RESTORING) {
			ensureRestoringPlayerState(player, state);
		} else {
			applyStateForPhase(player, state);
		}
		primeObservationState(player, state);
		// The private ore is materialised only when the server actually starts guiding
		// this player to it. Clearing stale blocks here also prevents another player's
		// unfinished scene from becoming interactable after a reconnect.
		if (!state.introOreRevealed || state.minedIntroBitcoin) {
			clearIntroOre(overworld, slot);
		} else if (state.phase == PlayerPhase.ISOLATED) {
			placeIntroOre(overworld, slot);
		}
		syncBitcoinVisibilityForPlayer(overworld, player, state);
		if ((created || announceIntro) && state.phase == PlayerPhase.WAITING_START && state.nextStartPromptTick <= 0L) {
			state.nextStartPromptTick = overworld.getGameTime() + WAITING_START_INITIAL_PROMPT_TICKS;
		}
	}

	private static void tickPlayerState(MinecraftServer server, ServerLevel level, ServerPlayer player, PlayerSceneState state) {
		BoxGeometry geometry = computeBarrierGeometry(resolveServerAnchor(level));
		SlotDefinition slot = resolveSlotDefinition(geometry, state.slotIndex);
		if (slot == null) {
			return;
		}
		if (state.phase != PlayerPhase.ISOLATED && state.phase != PlayerPhase.GUIDED_TO_SERVER) {
			clearPersonalExitSign(level, state);
		}

		if (state.phase == PlayerPhase.ISOLATED) {
			ensureIntroPlayerState(player, state, slot);
			tickPersonalExitSign(level, player, state);
			tickIsolatedPhaseReactions(server, player, state, slot);
			tickIntroOreGuidance(server, player, state, slot);
			return;
		}

		if (state.phase == PlayerPhase.WAITING_START) {
			ensureWaitingStartPlayerState(player, state, slot);
			tickWaitingStart(server, player, state);
			return;
		}

		if (state.phase == PlayerPhase.GUIDED_TO_SERVER) {
			ensureGuidedPlayerState(player, state, slot);
			tickPersonalExitSign(level, player, state);
			tickGuidance(server, player, state);
			return;
		}

		if (state.phase == PlayerPhase.RESTORING) {
			ensureRestoringPlayerState(player, state);
			tickRestoringPhase(server, player, state);
			return;
		}

		// Shared/free presentation is applied when entering the phase (and on
		// reconnect in assignOrRestorePlayer), not continuously from this tick.
	}

	private static void tickWaitingStart(MinecraftServer server, ServerPlayer player, PlayerSceneState state) {
		if (server == null || player == null || state == null || player.level() == null) {
			return;
		}
		long nowTick = player.level().getGameTime();
		if (state.nextStartPromptTick <= 0L) {
			state.nextStartPromptTick = nowTick + WAITING_START_INITIAL_PROMPT_TICKS;
			return;
		}
		if (nowTick < state.nextStartPromptTick) {
			return;
		}
		fireRoundRobinTrigger(server, player, state, WAITING_START_PROMPT_TRIGGERS);
		state.nextStartPromptTick = nowTick + WAITING_START_REPEAT_TICKS;
	}

	private static void tickPersonalExitSign(ServerLevel level, ServerPlayer player, PlayerSceneState state) {
		if (level == null || player == null || state == null) {
			return;
		}
		if (state.personalExitSignsRemaining <= 0) {
			return;
		}
		long nowTick = level.getGameTime();
		if (!player.hasEffect(MobEffects.BLINDNESS)) {
			clearPersonalExitSign(level, state);
			return;
		}
		if (state.personalExitSignId != null && nowTick >= state.personalExitSignExpiresAtTick) {
			clearPersonalExitSign(level, state);
		}
		if (state.personalExitSignId != null || nowTick < state.nextPersonalExitSignTick) {
			return;
		}

		Random random = new Random(player.getUUID().getMostSignificantBits()
				^ player.getUUID().getLeastSignificantBits() ^ nowTick);
		// Keep the apparition in the current field of view, but do not derive its
		// facing or its position from the route target. It is explicitly a false
		// exit sign, never a second navigation system.
		float placementYaw = player.getYRot() + (random.nextFloat() - 0.5F) * 60.0F;
		double placementRadians = Math.toRadians(placementYaw);
		double distance = 4.5D + random.nextDouble() * 2.5D;
		double x = player.getX() - Math.sin(placementRadians) * distance;
		double z = player.getZ() + Math.cos(placementRadians) * distance;
		double y = resolvePersonalExitSignFloorY(level, x, z, player.getY());
		Display.ItemDisplay sign = new Display.ItemDisplay(EntityType.ITEM_DISPLAY, level);
		sign.addTag(PERSONAL_EXIT_SIGN_DISPLAY_TAG);
		sign.addTag(personalExitSignOwnerTag(player.getUUID()));
		sign.setPos(x, y, z);
		sign.setYRot(random.nextFloat() * 360.0F);
		sign.setXRot(0.0F);
		sign.setYHeadRot(sign.getYRot());
		sign.setYBodyRot(sign.getYRot());
		sign.setItemStack(new ItemStack(ModBlocks.EXIT_SIGN_ITEM));
		sign.setItemTransform(ItemDisplayContext.FIXED);
		sign.setBillboardConstraints(Display.BillboardConstraints.FIXED);
		sign.setTransformation(new Transformation(
				new Vector3f(), new Quaternionf(), new Vector3f(1.75F, 1.75F, 1.75F), new Quaternionf()
		));
		sign.setBrightnessOverride(Brightness.FULL_BRIGHT);
		sign.setNoGravity(true);
		sign.setInvulnerable(true);
		sign.setSilent(true);
		sign.setShadowRadius(0.0F);
		sign.setShadowStrength(0.0F);
		sign.setViewRange(1.0F);
		ItemDisplayHitboxHelper.clear(sign);
		level.addFreshEntity(sign);
		state.personalExitSignId = sign.getUUID();
		state.personalExitSignExpiresAtTick = nowTick + PERSONAL_EXIT_SIGN_LIFETIME_TICKS;
		state.personalExitSignsRemaining--;
		state.nextPersonalExitSignTick = state.personalExitSignExpiresAtTick
				+ PERSONAL_EXIT_SIGN_MIN_INTERVAL_TICKS
				+ random.nextInt((int) PERSONAL_EXIT_SIGN_INTERVAL_VARIATION_TICKS + 1);
	}

	private static int rollPersonalExitSignBudget(UUID playerId, long sceneStartedAtTick) {
		if (playerId == null) {
			return 0;
		}
		Random random = new Random(
				playerId.getMostSignificantBits()
						^ playerId.getLeastSignificantBits()
						^ Long.rotateLeft(sceneStartedAtTick, 19)
		);
		int roll = random.nextInt(100);
		// Most players see no false exit at all. One is unusual; two is a very
		// small chance, so signs remain an incidental discovery rather than a
		// navigation mechanic.
		if (roll < 80) {
			return 0;
		}
		return roll < 97 ? 1 : 2;
	}

	private static double resolvePersonalExitSignFloorY(ServerLevel level, double x, double z, double playerY) {
		if (level == null) {
			return playerY;
		}
		int blockX = Mth.floor(x);
		int blockZ = Mth.floor(z);
		int highestY = Math.min(level.getMaxY() - 1, Mth.floor(playerY));
		int lowestY = Math.max(level.getMinY(), highestY - 8);
		for (int y = highestY; y >= lowestY; y--) {
			if (level.getBlockState(new BlockPos(blockX, y, blockZ)).blocksMotion()) {
				return y + 0.9D;
			}
		}
		return Math.max(level.getMinY() + 0.9D, Math.min(level.getMaxY() - 0.1D, playerY));
	}

	private static void clearPersonalExitSign(ServerLevel level, PlayerSceneState state) {
		if (state == null) {
			return;
		}
		if (level != null && state.personalExitSignId != null) {
			Entity existing = level.getEntity(state.personalExitSignId);
			if (existing != null && existing.getTags().contains(PERSONAL_EXIT_SIGN_DISPLAY_TAG)) {
				existing.discard();
			}
		}
		state.personalExitSignId = null;
		state.personalExitSignExpiresAtTick = Long.MIN_VALUE;
	}

	private static void clearPersonalExitSigns(ServerLevel level) {
		if (level == null) {
			return;
		}
		for (Entity entity : level.getAllEntities()) {
			if (entity.getTags().contains(PERSONAL_EXIT_SIGN_DISPLAY_TAG)) {
				entity.discard();
			}
		}
	}

	private static String personalExitSignOwnerTag(UUID playerId) {
		return PERSONAL_EXIT_SIGN_OWNER_TAG_PREFIX + playerId;
	}

	private static void tickGuidance(MinecraftServer server, ServerPlayer player, PlayerSceneState state) {
		if (server == null || player == null || state == null || player.level() == null || serverAnchor == null) {
			return;
		}
		long nowTick = player.level().getGameTime();
		tickLockedGuidedBitcoinSlot(server, player, state, nowTick);
		if (tickLostGuidedBitcoinRecovery(server, player, state, nowTick)) {
			return;
		}
		// Mining the first ore starts an explanatory sequence.  Walking, turning or
		// reaching the server does not make that explanation obsolete, so none of
		// the reactive navigation branches may replace it before it has finished.
		if (nowTick < state.guidanceNarrationGateTick) {
			return;
		}
		GuidanceSnapshot serverSnapshot = resolveGuidanceSnapshot(player);
		if (serverSnapshot == null || !hasBitcoin(player)) {
			return;
		}
		boolean lookingAtServerStructure = isLookingAtServerStructure(player);
		boolean seesServer = serverSnapshot.horizontalDistance <= GUIDANCE_SERVER_SIGHT_DISTANCE && lookingAtServerStructure;
		if (serverSnapshot.horizontalDistance <= GUIDANCE_DROP_DISTANCE) {
			if (!"guide_drop_coin".equals(state.lastGuidanceStateKey)) {
				interruptAndFastForwardPlayerNarration(player, state, nowTick);
			}
			resetGuidanceRoute(state);
			if (!"guide_drop_coin".equals(state.lastGuidanceStateKey)) {
				fireGuidanceInstruction(server, player, state,
						new GuidanceInstruction("guide_drop_coin", "guide_drop_coin", GUIDE_DROP_COIN_TRIGGERS, GUIDANCE_FORWARD_COOLDOWN_TICKS),
						serverSnapshot, nowTick);
			}
			return;
		}
		if (isServerWithinGuidanceVisibility(serverSnapshot)) {
			beginVisibleGuidanceTarget(player, state, "server", nowTick);
			if (lookingAtServerStructure && !state.announcedServerSight) {
				fireGuidanceInstruction(server, player, state,
						new GuidanceInstruction("guide_server_in_sight", "guide_server_in_sight", GUIDE_SERVER_IN_SIGHT_TRIGGERS, GUIDANCE_FORWARD_COOLDOWN_TICKS),
						serverSnapshot, nowTick);
			} else if (hasVisibleGuidanceTargetStalled(player, state, nowTick)) {
				fireGuidanceInstruction(server, player, state,
						new GuidanceInstruction("guide_close_presence", "guide_close_presence", GUIDE_CLOSE_PRESENCE_TRIGGERS, GUIDANCE_STALL_COOLDOWN_TICKS),
						serverSnapshot, nowTick);
			}
			return;
		}
		clearVisibleGuidanceTarget(state);
		// Seeing the actual objective invalidates a spoken direction immediately.
		// Do this before the usual narration gate: a discovered server is more useful
		// than finishing an instruction for a route the player no longer needs.
		if ((seesServer || lookingAtServerStructure) && !state.announcedServerSight) {
			interruptAndFastForwardPlayerNarration(player, state, nowTick);
			fireGuidanceInstruction(server, player, state,
					new GuidanceInstruction("guide_server_in_sight", "guide_server_in_sight", GUIDE_SERVER_IN_SIGHT_TRIGGERS, GUIDANCE_FORWARD_COOLDOWN_TICKS),
					serverSnapshot, nowTick);
			return;
		}
		if (nowTick < state.guidanceNarrationGateTick) {
			return;
		}
		if (nowTick < state.nextGuidanceTick) {
			return;
		}
		state.nextGuidanceTick = nowTick + GUIDANCE_EVALUATE_TICKS;
		if (seesServer && !state.announcedServerSight) {
			fireGuidanceInstruction(server, player, state,
					new GuidanceInstruction("guide_server_in_sight", "guide_server_in_sight", GUIDE_SERVER_IN_SIGHT_TRIGGERS, GUIDANCE_FORWARD_COOLDOWN_TICKS),
					serverSnapshot, nowTick);
		}
		if (state.guidanceRouteFinished) {
			tickLooseServerApproach(server, player, state, serverSnapshot, nowTick);
			return;
		}
		BoxGeometry barrier = computeBarrierGeometry(resolveServerAnchor(player.level()));
		tickConfusedRoute(server, player, state, GuidanceRouteKind.SERVER, resolveServerRouteDestination(player), barrier, nowTick);
	}

	private static boolean isCloseAndLookingAtServer(ServerPlayer player, double distance) {
		GuidanceSnapshot snapshot = resolveGuidanceSnapshot(player);
		return snapshot != null && snapshot.horizontalDistance <= distance && isLookingAtServerStructure(player);
	}

	private static boolean isServerWithinGuidanceVisibility(GuidanceSnapshot snapshot) {
		return snapshot != null && snapshot.horizontalDistance <= GUIDANCE_SERVER_VISIBLE_DISTANCE;
	}

	private static boolean isIntroOreWithinGuidanceVisibility(ServerPlayer player, SlotDefinition slot, GuidanceSnapshot snapshot) {
		if (player == null || slot == null || snapshot == null || snapshot.horizontalDistance > GUIDANCE_TARGET_VISIBLE_DISTANCE) {
			return false;
		}
		return Math.abs(player.getEyeY() - centerOf(slot.orePos).y) <= GUIDANCE_TARGET_VISIBLE_VERTICAL_DISTANCE;
	}

	/**
	 * Target proximity is a separate conversational state from navigation. Entering
	 * it cancels an obsolete direction once; keeping it active must not repeatedly
	 * reset the voice cooldown on every four-tick guidance pass.
	 */
	private static void beginVisibleGuidanceTarget(ServerPlayer player, PlayerSceneState state, String targetKey, long nowTick) {
		if (player == null || state == null || targetKey == null || targetKey.isBlank()) {
			return;
		}
		if (targetKey.equals(state.visibleGuidanceTargetKey)) {
			return;
		}
		state.visibleGuidanceTargetKey = targetKey;
		state.visibleGuidanceLastPlayerX = player.getX();
		state.visibleGuidanceLastPlayerZ = player.getZ();
		state.visibleGuidanceLastMoveTick = nowTick;
		resetGuidanceRoute(state);
		// A route line becomes factually wrong as soon as the objective is in the
		// player's immediate vicinity. Do not make them wait for it to finish.
		interruptAndFastForwardPlayerNarration(player, state, nowTick);
	}

	private static void clearVisibleGuidanceTarget(PlayerSceneState state) {
		if (state == null || state.visibleGuidanceTargetKey.isBlank()) {
			return;
		}
		state.visibleGuidanceTargetKey = "";
		state.visibleGuidanceLastMoveTick = Long.MIN_VALUE;
	}

	private static boolean hasVisibleGuidanceTargetStalled(ServerPlayer player, PlayerSceneState state, long nowTick) {
		if (player == null || state == null || state.visibleGuidanceTargetKey.isBlank()) {
			return false;
		}
		double dx = player.getX() - state.visibleGuidanceLastPlayerX;
		double dz = player.getZ() - state.visibleGuidanceLastPlayerZ;
		if (dx * dx + dz * dz >= ROUTE_MOVEMENT_SQR) {
			state.visibleGuidanceLastPlayerX = player.getX();
			state.visibleGuidanceLastPlayerZ = player.getZ();
			state.visibleGuidanceLastMoveTick = nowTick;
			return false;
		}
		return nowTick - state.visibleGuidanceLastMoveTick >= ROUTE_STALL_AFTER_TICKS;
	}

	private static void tickLockedGuidedBitcoinSlot(MinecraftServer server, ServerPlayer player, PlayerSceneState state, long nowTick) {
		if (server == null || player == null || state == null || state.lockedGuidedBitcoinSlot == NO_LOCKED_GUIDED_BITCOIN_SLOT) {
			return;
		}
		keepLockedGuidedBitcoinInSlot(player, state, null);
		if (!isCloseAndLookingAtServer(player, GUIDANCE_DROP_DISTANCE)) {
			return;
		}
		state.lockedGuidedBitcoinSlot = NO_LOCKED_GUIDED_BITCOIN_SLOT;
		stateDirty = true;
		SeasonStartVoiceSystem.clearPlayerChannel(player);
		state.guidanceNarrationGateTick = nowTick + resolveTriggerSequenceDurationTicks("intro_guided_bitcoin_slot_unlocked");
		SeasonStartVoiceSystem.fireTrigger(server, "intro_guided_bitcoin_slot_unlocked", player);
	}

	private static void tickIntroOreGuidance(MinecraftServer server, ServerPlayer player, PlayerSceneState state, SlotDefinition slot) {
		if (server == null || player == null || state == null || slot == null || player.level() == null || state.minedIntroBitcoin) {
			return;
		}
		long nowTick = player.level().getGameTime();
		if (nowTick < state.guidanceNarrationGateTick) {
			return;
		}
		if (!state.introOreRevealed) {
			state.introOreRevealed = true;
			placeIntroOre((ServerLevel) player.level(), slot);
			stateDirty = true;
		}
		if (nowTick < state.nextGuidanceTick) {
			return;
		}
		state.nextGuidanceTick = nowTick + GUIDANCE_EVALUATE_TICKS;
		GuidanceSnapshot snapshot = resolveGuidanceSnapshot(player, centerOf(slot.orePos));
		if (snapshot == null) {
			return;
		}
		boolean lookingAtOre = isLookingAtIntroOre(player, slot);
		if (isIntroOreWithinGuidanceVisibility(player, slot, snapshot)) {
			beginVisibleGuidanceTarget(player, state, "intro_ore", nowTick);
			if (lookingAtOre) {
				if (!state.introTargetLocked) {
					fireIntroTargetReaction(server, player, state, nowTick);
				}
				completeGuidanceRoute(state);
				return;
			}
			if (hasVisibleGuidanceTargetStalled(player, state, nowTick)) {
				VerticalAimHint visibleAimHint = resolveIntroVerticalAimHint(player, slot, snapshot, false);
				GuidanceInstruction visibleTargetInstruction = visibleAimHint == VerticalAimHint.UP
						? new GuidanceInstruction("intro_target_look_up", "intro_target_look_up", INTRO_TARGET_LOOK_UP_TRIGGERS, GUIDANCE_STALL_COOLDOWN_TICKS)
						: visibleAimHint == VerticalAimHint.DOWN
						? new GuidanceInstruction("intro_target_look_down", "intro_target_look_down", INTRO_TARGET_LOOK_DOWN_TRIGGERS, GUIDANCE_STALL_COOLDOWN_TICKS)
						: new GuidanceInstruction("intro_target_stare", "intro_target_stare", INTRO_TARGET_STARE_TRIGGERS, GUIDANCE_STALL_COOLDOWN_TICKS);
				fireGuidanceInstruction(server, player, state, visibleTargetInstruction, snapshot, nowTick);
			}
			return;
		}
		clearVisibleGuidanceTarget(state);
		if (lookingAtOre) {
			if (!state.introTargetLocked) {
				interruptAndFastForwardPlayerNarration(player, state, nowTick);
				fireIntroTargetReaction(server, player, state, nowTick);
			}
			completeGuidanceRoute(state);
			return;
		}
		if (!state.guidanceRouteFinished) {
			BoxGeometry barrier = computeBarrierGeometry(resolveServerAnchor(player.level()));
			tickConfusedRoute(server, player, state, GuidanceRouteKind.INTRO_ORE, centerOf(slot.orePos), barrier, nowTick);
			return;
		}
		if (!state.guidanceRouteArrivalAnnounced) {
			if (fireGuidanceInstruction(server, player, state,
					new GuidanceInstruction("intro_route_arrived", "intro_route_arrived", INTRO_ROUTE_ARRIVED_TRIGGERS, GUIDANCE_FORWARD_COOLDOWN_TICKS),
					snapshot, nowTick)) {
				state.guidanceRouteArrivalAnnounced = true;
			}
			return;
		}
		VerticalAimHint verticalAimHint = resolveIntroVerticalAimHint(player, slot, snapshot, lookingAtOre);
		GuidanceInstruction instruction = resolveFinalIntroSearchInstruction(snapshot, verticalAimHint, state, nowTick);
		if (instruction == null) {
			return;
		}
		fireRouteGuidanceInstruction(server, player, state, instruction, snapshot, nowTick);
	}

	/**
	 * The route intentionally contains false waypoints. The server commits to each
	 * instruction while the player is walking and only discovers a bad segment
	 * after the player has actually reached it.
	 */
	private static void tickConfusedRoute(
			MinecraftServer server,
			ServerPlayer player,
			PlayerSceneState state,
			GuidanceRouteKind routeKind,
			Vec3 destination,
			BoxGeometry barrier,
			long nowTick
	) {
		if (server == null || player == null || state == null || destination == null) {
			return;
		}
		ensureGuidanceRoute(state, player, routeKind, destination, nowTick);
		if (!state.guidanceRouteDirect
				&& state.guidanceRouteStartedTick != Long.MIN_VALUE
				&& nowTick - state.guidanceRouteStartedTick >= ROUTE_CONFUSION_MAX_TICKS) {
			state.guidanceRouteDirect = true;
			state.guidanceRouteLegIndex = 0;
			state.guidanceRouteLegAnnounced = false;
			state.guidanceRouteLastDistance = Double.NaN;
			state.guidanceRouteLegStartedTick = nowTick;
			resetRouteInstructionCadence(state, nowTick);
			interruptAndFastForwardPlayerNarration(player, state, nowTick);
		}
		GuidanceRoute route = buildGuidanceRoute(state, barrier);
		if (route.waypoints().isEmpty()) {
			state.guidanceRouteFinished = true;
			return;
		}
		int routeLeg = Mth.clamp(state.guidanceRouteLegIndex, 0, route.waypoints().size() - 1);
		Vec3 waypoint = route.waypoints().get(routeLeg);
		GuidanceSnapshot snapshot = resolveGuidanceSnapshot(player, waypoint);
		if (snapshot == null) {
			return;
		}

		boolean moving = updateRouteMovementState(player, state, nowTick);
		if (snapshot.horizontalDistance <= ROUTE_WAYPOINT_REACHED_DISTANCE) {
			advanceGuidanceRoute(server, player, state, routeKind, route, nowTick);
			return;
		}

		if (!state.guidanceRouteLegAnnounced) {
			GuidanceInstruction opening = resolveRouteLegOpening(routeKind, routeLeg, snapshot);
			if (opening != null && fireRouteGuidanceInstruction(server, player, state, opening, snapshot, nowTick)) {
				state.guidanceRouteLegAnnounced = true;
			}
			return;
		}
		double distanceDelta = Double.isFinite(state.guidanceRouteLastDistance)
				? snapshot.horizontalDistance - state.guidanceRouteLastDistance
				: 0.0D;
		state.guidanceRouteLastDistance = snapshot.horizontalDistance;
		GuidanceInstruction instruction = moving
				? resolveRouteMovementInstruction(routeKind, snapshot, state, distanceDelta, nowTick)
				: resolveRouteStallInstruction(snapshot, state, nowTick);
		// Direction changes are not emergencies.  Let the current, still-valid line
		// finish instead of making the narrator talk over himself every time a player
		// twitches the camera.
		if (nowTick < state.nextGuidanceEarliestTick) {
			return;
		}
		if (instruction != null) {
			fireRouteGuidanceInstruction(server, player, state, instruction, snapshot, nowTick);
		}
	}

	private static void ensureGuidanceRoute(
			PlayerSceneState state,
			ServerPlayer player,
			GuidanceRouteKind routeKind,
			Vec3 destination,
			long nowTick
	) {
		if (state.guidanceRouteKind == routeKind && Double.isFinite(state.guidanceRouteOriginX)
				&& Double.isFinite(state.guidanceRouteDestinationX)) {
			return;
		}
		state.guidanceRouteKind = routeKind;
		state.guidanceRouteOriginX = player.getX();
		state.guidanceRouteOriginZ = player.getZ();
		state.guidanceRouteDestinationX = destination.x;
		state.guidanceRouteDestinationZ = destination.z;
		state.guidanceRouteSide = ((player.getUUID().hashCode() ^ routeKind.ordinal()) & 1) == 0 ? 1.0D : -1.0D;
		state.guidanceRouteLegIndex = 0;
		state.guidanceRouteLegAnnounced = false;
		state.guidanceRouteFinished = false;
		state.guidanceRouteArrivalAnnounced = false;
		state.guidanceRouteLastDistance = Double.NaN;
		state.guidanceRouteLastPlayerX = player.getX();
		state.guidanceRouteLastPlayerZ = player.getZ();
		state.guidanceRouteLastMoveTick = nowTick;
		state.guidanceRouteLegStartedTick = nowTick;
		state.guidanceRouteStartedTick = nowTick;
		state.guidanceRouteDirect = false;
		resetRouteInstructionCadence(state, nowTick);
		state.guidanceRouteStarted = true;
	}

	private static GuidanceRoute buildGuidanceRoute(PlayerSceneState state, BoxGeometry barrier) {
		if (state == null || !Double.isFinite(state.guidanceRouteOriginX) || !Double.isFinite(state.guidanceRouteDestinationX)) {
			return new GuidanceRoute(List.of());
		}
		Vec3 origin = new Vec3(state.guidanceRouteOriginX, 0.0D, state.guidanceRouteOriginZ);
		Vec3 destination = new Vec3(state.guidanceRouteDestinationX, 0.0D, state.guidanceRouteDestinationZ);
		if (state.guidanceRouteDirect) {
			return new GuidanceRoute(List.of(clampRoutePoint(destination, barrier)));
		}
		Vec3 delta = destination.subtract(origin);
		double distance = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
		if (distance <= 1.0E-4D) {
			return new GuidanceRoute(List.of(clampRoutePoint(destination, barrier)));
		}
		Vec3 forward = new Vec3(delta.x / distance, 0.0D, delta.z / distance);
		Vec3 side = new Vec3(-forward.z * state.guidanceRouteSide, 0.0D, forward.x * state.guidanceRouteSide);
		double firstForward = Math.min(3.4D, Math.max(2.3D, distance * 0.36D));
		double sideways = Math.min(3.2D, Math.max(1.8D, distance * 0.28D));
		Vec3 falseExit = clampRoutePoint(origin.add(forward.scale(firstForward)).add(side.scale(sideways)), barrier);
		Vec3 returnPoint = clampRoutePoint(origin.add(forward.scale(Math.min(1.2D, distance * 0.16D))).add(side.scale(sideways)), barrier);
		Vec3 correctionPoint = clampRoutePoint(
				origin.add(forward.scale(Math.min(Math.max(3.0D, distance * 0.68D), Math.max(3.0D, distance - 1.5D))))
						.add(side.scale(-sideways * 0.62D)),
				barrier
		);
		return new GuidanceRoute(List.of(falseExit, returnPoint, correctionPoint, clampRoutePoint(destination, barrier)));
	}

	private static Vec3 clampRoutePoint(Vec3 point, BoxGeometry barrier) {
		if (point == null || barrier == null) {
			return point == null ? Vec3.ZERO : point;
		}
		return new Vec3(
				Mth.clamp(point.x, barrier.minX + 1.75D, barrier.maxX - 0.75D),
				point.y,
				Mth.clamp(point.z, barrier.minZ + 1.75D, barrier.maxZ - 0.75D)
		);
	}

	private static boolean updateRouteMovementState(ServerPlayer player, PlayerSceneState state, long nowTick) {
		double dx = player.getX() - state.guidanceRouteLastPlayerX;
		double dz = player.getZ() - state.guidanceRouteLastPlayerZ;
		boolean moved = dx * dx + dz * dz >= ROUTE_MOVEMENT_SQR;
		if (moved) {
			state.guidanceRouteLastPlayerX = player.getX();
			state.guidanceRouteLastPlayerZ = player.getZ();
			state.guidanceRouteLastMoveTick = nowTick;
		}
		return moved;
	}

	private static void advanceGuidanceRoute(
			MinecraftServer server,
			ServerPlayer player,
			PlayerSceneState state,
			GuidanceRouteKind routeKind,
			GuidanceRoute route,
			long nowTick
	) {
		if (state.guidanceRouteLegIndex < route.waypoints().size() - 1) {
			state.guidanceRouteLegIndex++;
			state.guidanceRouteLegAnnounced = false;
			state.guidanceRouteLastDistance = Double.NaN;
			state.guidanceRouteLegStartedTick = nowTick;
			resetRouteInstructionCadence(state, nowTick);
			return;
		}
		state.guidanceRouteFinished = true;
		state.guidanceRouteLegAnnounced = true;
		if (routeKind == GuidanceRouteKind.INTRO_ORE && !state.guidanceRouteArrivalAnnounced) {
			GuidanceSnapshot snapshot = resolveGuidanceSnapshot(player, route.waypoints().get(route.waypoints().size() - 1));
			if (snapshot != null && fireGuidanceInstruction(server, player, state,
					new GuidanceInstruction("intro_route_arrived", "intro_route_arrived", INTRO_ROUTE_ARRIVED_TRIGGERS, GUIDANCE_FORWARD_COOLDOWN_TICKS),
					snapshot, nowTick)) {
				state.guidanceRouteArrivalAnnounced = true;
			}
		}
	}

	private static GuidanceInstruction resolveRouteLegOpening(GuidanceRouteKind routeKind, int routeLeg, GuidanceSnapshot snapshot) {
		return switch (routeLeg) {
			case 0 -> routeKind == GuidanceRouteKind.INTRO_ORE
					? new GuidanceInstruction("intro_guide_route_start", "intro_guide_route_start", INTRO_GUIDE_ROUTE_START_TRIGGERS, GUIDANCE_FORWARD_COOLDOWN_TICKS)
					: new GuidanceInstruction("guide_forward_far", "guide_forward_far", GUIDE_FORWARD_FAR_TRIGGERS, GUIDANCE_FORWARD_COOLDOWN_TICKS);
			case 1 -> resolveRouteReverseInstruction(snapshot);
			case 2 -> resolveRouteResumeInstruction(snapshot);
			default -> new GuidanceInstruction("guide_forward_near", "guide_forward_near", GUIDE_FORWARD_NEAR_TRIGGERS, GUIDANCE_FORWARD_COOLDOWN_TICKS);
		};
	}

	private static GuidanceInstruction resolveRouteReverseInstruction(GuidanceSnapshot snapshot) {
		if (snapshot == null) {
			return new GuidanceInstruction("guide_passed_server", "guide_passed_server", GUIDE_PASSED_SERVER_TRIGGERS, GUIDANCE_CATEGORY_COOLDOWN_TICKS);
		}
		return snapshot.deltaYaw < 0.0D
				? new GuidanceInstruction("guide_route_reverse_left", "guide_route_reverse_left", GUIDE_ROUTE_REVERSE_LEFT_TRIGGERS, GUIDANCE_CATEGORY_COOLDOWN_TICKS)
				: new GuidanceInstruction("guide_route_reverse_right", "guide_route_reverse_right", GUIDE_ROUTE_REVERSE_RIGHT_TRIGGERS, GUIDANCE_CATEGORY_COOLDOWN_TICKS);
	}

	private static GuidanceInstruction resolveRouteResumeInstruction(GuidanceSnapshot snapshot) {
		if (snapshot == null || Math.abs(snapshot.deltaYaw) < GUIDANCE_MICRO_ANGLE) {
			return new GuidanceInstruction("guide_route_resume_forward", "guide_route_resume_forward", GUIDE_ROUTE_RESUME_FORWARD_TRIGGERS, GUIDANCE_FORWARD_COOLDOWN_TICKS);
		}
		return snapshot.deltaYaw < 0.0D
				? new GuidanceInstruction("guide_route_resume_left", "guide_route_resume_left", GUIDE_ROUTE_RESUME_LEFT_TRIGGERS, GUIDANCE_CORRECTION_COOLDOWN_TICKS)
				: new GuidanceInstruction("guide_route_resume_right", "guide_route_resume_right", GUIDE_ROUTE_RESUME_RIGHT_TRIGGERS, GUIDANCE_CORRECTION_COOLDOWN_TICKS);
	}

	private static GuidanceInstruction resolveRouteForwardInstruction(GuidanceSnapshot snapshot) {
		return switch (snapshot.distanceBucket) {
			case 2 -> new GuidanceInstruction("guide_forward_close", "guide_forward_close", GUIDE_FORWARD_CLOSE_TRIGGERS, GUIDANCE_FORWARD_COOLDOWN_TICKS);
			case 4 -> new GuidanceInstruction("guide_forward_near", "guide_forward_near", GUIDE_FORWARD_NEAR_TRIGGERS, GUIDANCE_FORWARD_COOLDOWN_TICKS);
			case 7 -> new GuidanceInstruction("guide_forward_mid", "guide_forward_mid", GUIDE_FORWARD_MID_TRIGGERS, GUIDANCE_FORWARD_COOLDOWN_TICKS);
			default -> new GuidanceInstruction("guide_forward_far", "guide_forward_far", GUIDE_FORWARD_FAR_TRIGGERS, GUIDANCE_FORWARD_COOLDOWN_TICKS);
		};
	}

	private static GuidanceInstruction resolveRouteMovementInstruction(
			GuidanceRouteKind routeKind,
			GuidanceSnapshot snapshot,
			PlayerSceneState state,
			double distanceDelta,
			long nowTick
	) {
		if (snapshot == null || state == null) {
			return null;
		}
		GuidanceInstruction turn = resolveRouteTurnInstruction(snapshot, state, nowTick);
		if (isTurnRecoveryInstruction(turn)) {
			return turn;
		}
		// Do not bury route comments behind another left/right prompt. A player who keeps
		// increasing the distance after a direction has had time to land should hear the
		// server call it out, but never more often than once every four seconds.
		if (distanceDelta >= ROUTE_PROGRESS_AWAY
				&& nowTick >= state.guidanceRouteNextWrongWayTick
				&& ((state.guidanceRouteDirectionIssued
						&& nowTick - state.guidanceRouteLastTurnCueTick >= ROUTE_WRONG_WAY_GRACE_TICKS)
						|| (!isNormalTurnInstruction(turn)
						&& nowTick - state.guidanceRouteLegStartedTick >= ROUTE_WRONG_WAY_GRACE_TICKS))) {
			return routeKind == GuidanceRouteKind.INTRO_ORE
					? new GuidanceInstruction("intro_guide_wrong_way", "intro_guide_wrong_way", INTRO_GUIDE_WRONG_WAY_TRIGGERS, GUIDANCE_CATEGORY_COOLDOWN_TICKS)
					: new GuidanceInstruction("guide_wrong_way", "guide_wrong_way", GUIDE_WRONG_WAY_TRIGGERS, GUIDANCE_CATEGORY_COOLDOWN_TICKS);
		}
		// A moving player needs a usable direction before criticism. After a correction, force a
		// fresh direction again; only sustained refusal earns another route warning.
		if (isNormalTurnInstruction(turn) && (!state.guidanceRouteDirectionIssued
				|| nowTick >= state.guidanceRouteNextTurnCueTick)) {
			return turn;
		}
		if (distanceDelta <= ROUTE_PROGRESS_TOWARD && nowTick >= state.guidanceRouteNextProgressCueTick) {
			if (state.guidanceRouteLegIndex == 1) {
				return new GuidanceInstruction(
						"guide_route_return_progress",
						"guide_route_return_progress",
						GUIDE_ROUTE_RETURN_PROGRESS_TRIGGERS,
						GUIDANCE_FORWARD_COOLDOWN_TICKS
				);
			}
			return resolveRouteForwardInstruction(snapshot);
		}
		return null;
	}

	private static GuidanceInstruction resolveRouteStallInstruction(
			GuidanceSnapshot snapshot,
			PlayerSceneState state,
			long nowTick
	) {
		if (snapshot == null || state == null || nowTick - state.guidanceRouteLegStartedTick < ROUTE_STALL_AFTER_TICKS) {
			return null;
		}
		return snapshot.aligned
				? new GuidanceInstruction("guide_stall_aligned", "guide_stall_aligned", GUIDE_STALL_ALIGNED_TRIGGERS, GUIDANCE_STALL_COOLDOWN_TICKS)
				: new GuidanceInstruction("guide_stall_misaligned", "guide_stall_misaligned", GUIDE_STALL_MISALIGNED_TRIGGERS, GUIDANCE_STALL_COOLDOWN_TICKS);
	}

	private static boolean fireRouteGuidanceInstruction(
			MinecraftServer server,
			ServerPlayer player,
			PlayerSceneState state,
			GuidanceInstruction instruction,
			GuidanceSnapshot snapshot,
			long nowTick
	) {
		if (!fireGuidanceInstruction(server, player, state, instruction, snapshot, nowTick)) {
			return false;
		}
		if (isNormalTurnInstruction(instruction) || isTurnRecoveryInstruction(instruction)) {
			state.guidanceRouteLastTurnCueTick = nowTick;
			state.guidanceRouteNextTurnCueTick = nowTick + ROUTE_TURN_REPEAT_TICKS;
			state.guidanceRouteNextProgressCueTick = nowTick + GUIDANCE_MIN_VOICE_GAP_TICKS;
			state.guidanceRouteDirectionIssued = true;
		} else if ("guide_wrong_way".equals(instruction.stateKey) || "intro_guide_wrong_way".equals(instruction.stateKey)) {
			state.guidanceRouteDirectionIssued = false;
			state.guidanceRouteNextWrongWayTick = nowTick + ROUTE_WRONG_WAY_REPEAT_TICKS;
		} else if (isRouteForwardInstruction(instruction)) {
			state.guidanceRouteNextProgressCueTick = nowTick + ROUTE_PROGRESS_CUE_TICKS;
		}
		return true;
	}

	private static void resetRouteInstructionCadence(PlayerSceneState state, long nowTick) {
		if (state == null) {
			return;
		}
		state.guidanceRouteLastTurnCueTick = nowTick;
		state.guidanceRouteNextTurnCueTick = nowTick;
		state.guidanceRouteNextProgressCueTick = nowTick;
		state.guidanceRouteNextWrongWayTick = nowTick;
		state.guidanceRouteDirectionIssued = false;
	}

	private static boolean isNormalTurnInstruction(GuidanceInstruction instruction) {
		if (instruction == null || instruction.stateKey == null) {
			return false;
		}
		return (instruction.stateKey.startsWith("guide_turn_") && !instruction.stateKey.endsWith("_recover"))
				|| "guide_route_resume_left".equals(instruction.stateKey)
				|| "guide_route_resume_right".equals(instruction.stateKey)
				|| "guide_route_reverse_left".equals(instruction.stateKey)
				|| "guide_route_reverse_right".equals(instruction.stateKey);
	}

	private static boolean isTurnRecoveryInstruction(GuidanceInstruction instruction) {
		return instruction != null
				&& ("guide_turn_left_recover".equals(instruction.stateKey)
				|| "guide_turn_right_recover".equals(instruction.stateKey));
	}

	private static boolean isRouteForwardInstruction(GuidanceInstruction instruction) {
		return instruction != null
				&& instruction.stateKey != null
				&& (instruction.stateKey.startsWith("guide_forward_")
				|| "guide_route_resume_forward".equals(instruction.stateKey));
	}

	private static boolean isUrgentRouteCorrection(GuidanceInstruction instruction) {
		if (instruction == null || instruction.stateKey == null) {
			return false;
		}
		return isTurnRecoveryInstruction(instruction);
	}

	private static GuidanceInstruction resolveRouteTurnInstruction(GuidanceSnapshot snapshot, PlayerSceneState state, long nowTick) {
		if (snapshot == null || state == null) {
			return null;
		}
		double absYaw = Math.abs(snapshot.deltaYaw);
		TurnHintDirection direction = snapshot.deltaYaw < 0.0D ? TurnHintDirection.LEFT : TurnHintDirection.RIGHT;
		GuidanceInstruction recover = resolveTurnRecoverInstruction(state, direction, snapshot, absYaw, nowTick);
		if (recover != null) {
			return recover;
		}
		if (absYaw >= GUIDANCE_TURN_AROUND_ANGLE) {
			return direction == TurnHintDirection.LEFT
					? new GuidanceInstruction("guide_turn_around_left", "guide_turn_around_left", GUIDE_TURN_AROUND_LEFT_TRIGGERS, GUIDANCE_CATEGORY_COOLDOWN_TICKS)
					: new GuidanceInstruction("guide_turn_around_right", "guide_turn_around_right", GUIDE_TURN_AROUND_RIGHT_TRIGGERS, GUIDANCE_CATEGORY_COOLDOWN_TICKS);
		}
		if (absYaw >= GUIDANCE_HARD_ANGLE) {
			return direction == TurnHintDirection.LEFT
					? new GuidanceInstruction("guide_turn_left_hard", "guide_turn_left_hard", GUIDE_TURN_LEFT_HARD_TRIGGERS, GUIDANCE_CATEGORY_COOLDOWN_TICKS)
					: new GuidanceInstruction("guide_turn_right_hard", "guide_turn_right_hard", GUIDE_TURN_RIGHT_HARD_TRIGGERS, GUIDANCE_CATEGORY_COOLDOWN_TICKS);
		}
		if (absYaw >= GUIDANCE_MEDIUM_ANGLE) {
			return direction == TurnHintDirection.LEFT
					? new GuidanceInstruction("guide_turn_left_medium", "guide_turn_left_medium", GUIDE_TURN_LEFT_MEDIUM_TRIGGERS, GUIDANCE_CORRECTION_COOLDOWN_TICKS)
					: new GuidanceInstruction("guide_turn_right_medium", "guide_turn_right_medium", GUIDE_TURN_RIGHT_MEDIUM_TRIGGERS, GUIDANCE_CORRECTION_COOLDOWN_TICKS);
		}
		if (absYaw >= GUIDANCE_MICRO_ANGLE) {
			return direction == TurnHintDirection.LEFT
					? new GuidanceInstruction("guide_turn_left_soft", "guide_turn_left_soft", GUIDE_TURN_LEFT_SOFT_TRIGGERS, GUIDANCE_CORRECTION_COOLDOWN_TICKS)
					: new GuidanceInstruction("guide_turn_right_soft", "guide_turn_right_soft", GUIDE_TURN_RIGHT_SOFT_TRIGGERS, GUIDANCE_CORRECTION_COOLDOWN_TICKS);
		}
		return null;
	}

	private static GuidanceInstruction resolveFinalIntroSearchInstruction(
			GuidanceSnapshot snapshot,
			VerticalAimHint verticalAimHint,
			PlayerSceneState state,
			long nowTick
	) {
		if (verticalAimHint == VerticalAimHint.UP) {
			return new GuidanceInstruction("intro_target_look_up", "intro_target_look_up", INTRO_TARGET_LOOK_UP_TRIGGERS, GUIDANCE_STALL_COOLDOWN_TICKS);
		}
		if (verticalAimHint == VerticalAimHint.DOWN) {
			return new GuidanceInstruction("intro_target_look_down", "intro_target_look_down", INTRO_TARGET_LOOK_DOWN_TRIGGERS, GUIDANCE_STALL_COOLDOWN_TICKS);
		}
		return resolveThrottledRouteTurnInstruction(snapshot, state, nowTick);
	}

	private static void tickLooseServerApproach(
			MinecraftServer server,
			ServerPlayer player,
			PlayerSceneState state,
			GuidanceSnapshot snapshot,
			long nowTick
	) {
		if (snapshot.horizontalDistance <= GUIDANCE_SERVER_SIGHT_DISTANCE && !state.announcedServerSight) {
			fireGuidanceInstruction(server, player, state,
					new GuidanceInstruction("guide_server_in_sight", "guide_server_in_sight", GUIDE_SERVER_IN_SIGHT_TRIGGERS, GUIDANCE_FORWARD_COOLDOWN_TICKS),
					snapshot, nowTick);
			return;
		}
		boolean moving = updateRouteMovementState(player, state, nowTick);
		if (!moving && nowTick - state.guidanceRouteLastMoveTick >= ROUTE_STALL_AFTER_TICKS) {
			GuidanceInstruction stall = resolveRouteStallInstruction(snapshot, state, nowTick);
			if (stall != null) {
				fireRouteGuidanceInstruction(server, player, state, stall, snapshot, nowTick);
			}
			return;
		}
		GuidanceInstruction turn = resolveThrottledRouteTurnInstruction(snapshot, state, nowTick);
		if (turn == null) {
			turn = resolveRouteForwardInstruction(snapshot);
		}
		fireRouteGuidanceInstruction(server, player, state, turn, snapshot, nowTick);
	}

	private static GuidanceInstruction resolveThrottledRouteTurnInstruction(
			GuidanceSnapshot snapshot,
			PlayerSceneState state,
			long nowTick
	) {
		GuidanceInstruction instruction = resolveRouteTurnInstruction(snapshot, state, nowTick);
		if (isNormalTurnInstruction(instruction) && nowTick < state.guidanceRouteNextTurnCueTick) {
			return null;
		}
		return instruction;
	}

	private static Vec3 resolveServerRouteDestination(ServerPlayer player) {
		ServerStructureBounds bounds = resolveServerStructureBounds();
		if (player == null || bounds == null) {
			return serverAnchor == null ? Vec3.ZERO : new Vec3(serverAnchor.getX() + 0.5D, 0.0D, serverAnchor.getZ() + 0.5D);
		}
		double centerX = (bounds.minX + bounds.maxX) * 0.5D;
		double centerZ = (bounds.minZ + bounds.maxZ) * 0.5D;
		double dx = player.getX() - centerX;
		double dz = player.getZ() - centerZ;
		double length = Math.sqrt(dx * dx + dz * dz);
		if (length <= 1.0E-4D) {
			dx = 0.0D;
			dz = 1.0D;
			length = 1.0D;
		}
		double outerRadius = Math.max(bounds.maxX - bounds.minX, bounds.maxZ - bounds.minZ) * 0.5D + 1.9D;
		return new Vec3(centerX + dx / length * outerRadius, 0.0D, centerZ + dz / length * outerRadius);
	}

	private static boolean fireGuidanceInstruction(
			MinecraftServer server,
			ServerPlayer player,
			PlayerSceneState state,
			GuidanceInstruction instruction,
			GuidanceSnapshot snapshot,
			long nowTick
	) {
		if (instruction == null || nowTick < state.nextGuidanceEarliestTick
				|| (instruction.stateKey.equals(state.lastGuidanceStateKey) && nowTick < state.nextGuidanceVoiceTick)) {
			return false;
		}
		String semanticKey = resolveGuidanceSemanticKey(instruction);
		if (nowTick < state.guidanceSemanticNextAllowedTicks.getOrDefault(semanticKey, Long.MIN_VALUE)) {
			return false;
		}
		fireTriggerCycle(server, player, state, instruction.groupKey, instruction.triggers);
		state.lastGuidanceStateKey = instruction.stateKey;
		state.lastGuidanceDistanceBucket = snapshot.distanceBucket;
		state.lastGuidanceAbsYaw = Math.abs(snapshot.deltaYaw);
		state.wasGuidanceAligned = snapshot.aligned;
		state.wasGuidanceClose = snapshot.horizontalDistance <= GUIDANCE_CLOSE_APPROACH_DISTANCE;
		if ("guide_server_in_sight".equals(instruction.stateKey)) {
			state.announcedServerSight = true;
		}
		applyGuidanceInstructionState(state, instruction, snapshot, nowTick);
		long narrationLockTicks = resolveGuidanceNarrationLockTicks(instruction.triggers);
		state.nextGuidanceVoiceTick = nowTick + Math.max(instruction.cooldownTicks, narrationLockTicks);
		state.nextGuidanceEarliestTick = nowTick + narrationLockTicks;
		state.guidanceRouteInterruptAfterTick = nowTick + Math.max(20L, narrationLockTicks / 2L);
		state.guidanceSemanticNextAllowedTicks.put(
				semanticKey,
				nowTick + Math.max(narrationLockTicks, resolveGuidanceSemanticCooldownTicks(semanticKey))
		);
		return true;
	}

	/**
	 * Several mechanical states describe the same thought (for example, a soft and
	 * hard left turn).  Keeping their cooldown together makes the voice react to
	 * meaningful changes in play instead of narrating every sensor fluctuation.
	 */
	private static String resolveGuidanceSemanticKey(GuidanceInstruction instruction) {
		String key = instruction == null || instruction.stateKey == null ? "guidance" : instruction.stateKey;
		if (key.startsWith("guide_stall_") || key.startsWith("intro_target_") || key.equals("guide_close_presence")) {
			return "stall";
		}
		if (key.contains("wrong_way") || key.contains("passed_server") || key.contains("route_reverse")) {
			return "course_correction";
		}
		if (key.contains("turn_left") || key.contains("resume_left")) {
			return "turn_left";
		}
		if (key.contains("turn_right") || key.contains("resume_right")) {
			return "turn_right";
		}
		if (key.contains("turn_around")) {
			return "turn_around";
		}
		if (key.startsWith("guide_forward_") || key.contains("route_return_progress") || key.contains("resume_forward")) {
			return "forward_progress";
		}
		if (key.equals("guide_locked_on") || key.equals("guide_heading_lost")) {
			return "heading_status";
		}
		return key;
	}

	private static long resolveGuidanceSemanticCooldownTicks(String semanticKey) {
		return switch (semanticKey) {
			case "stall" -> GUIDANCE_STALL_SEMANTIC_COOLDOWN_TICKS;
			case "course_correction" -> GUIDANCE_CORRECTION_SEMANTIC_COOLDOWN_TICKS;
			case "turn_left", "turn_right", "turn_around", "heading_status" -> GUIDANCE_DIRECTION_SEMANTIC_COOLDOWN_TICKS;
			case "forward_progress" -> GUIDANCE_PROGRESS_SEMANTIC_COOLDOWN_TICKS;
			default -> GUIDANCE_MIN_VOICE_GAP_TICKS;
		};
	}

	private static void completeGuidanceRoute(PlayerSceneState state) {
		if (state == null) {
			return;
		}
		state.guidanceRouteFinished = true;
		state.guidanceRouteLegAnnounced = true;
	}

	private static void resetGuidanceRoute(PlayerSceneState state) {
		if (state == null) {
			return;
		}
		state.guidanceRouteKind = GuidanceRouteKind.NONE;
		state.guidanceRouteLegIndex = 0;
		state.guidanceRouteLegAnnounced = false;
		state.guidanceRouteFinished = false;
		state.guidanceRouteArrivalAnnounced = false;
		state.guidanceRouteOriginX = Double.NaN;
		state.guidanceRouteOriginZ = Double.NaN;
		state.guidanceRouteDestinationX = Double.NaN;
		state.guidanceRouteDestinationZ = Double.NaN;
		state.guidanceRouteLastDistance = Double.NaN;
		state.guidanceRouteStartedTick = Long.MIN_VALUE;
		state.guidanceRouteDirect = false;
		state.guidanceRouteInterruptAfterTick = Long.MIN_VALUE;
		state.guidanceRouteLastTurnCueTick = 0L;
		state.guidanceRouteNextTurnCueTick = 0L;
		state.guidanceRouteNextProgressCueTick = 0L;
		state.guidanceRouteNextWrongWayTick = 0L;
		state.guidanceRouteDirectionIssued = false;
	}

	private static void tickIsolatedPhaseReactions(MinecraftServer server, ServerPlayer player, PlayerSceneState state, SlotDefinition slot) {
		if (server == null || player == null || state == null || slot == null || player.level() == null) {
			return;
		}
		long nowTick = player.level().getGameTime();
		if (state.lastObservationTick == Long.MIN_VALUE) {
			primeObservationState(player, state);
			return;
		}
		boolean lookingAtOre = isLookingAtIntroOre(player, slot);
		if (nowTick < state.guidanceNarrationGateTick) {
			state.lastActivityTick = nowTick;
			// The opening script is intentionally non-interruptible. In particular,
			// merely looking at the ore used to clear the channel just before the
			// "break it" line was heard.
			updateObservationBaseline(player, state);
			return;
		}

		double horizontalMoveSqr = horizontalDistanceSqr(player.position(), new Vec3(state.lastObservedX, player.getY(), state.lastObservedZ));
		double verticalMove = Math.abs(player.getY() - state.lastObservedY);
		float yawDelta = Math.abs(Mth.wrapDegrees(player.getYRot() - state.lastYaw));
		float pitchDelta = Math.abs(player.getXRot() - state.lastPitch);
		boolean moved = horizontalMoveSqr > INTRO_ACTIVITY_MOVE_SQR || verticalMove > 0.12D;
		boolean looked = yawDelta >= INTRO_ACTIVITY_YAW_DEGREES || pitchDelta >= INTRO_ACTIVITY_PITCH_DEGREES;

		if (moved || looked) {
			state.lastActivityTick = nowTick;
		}

		if (lookingAtOre && shouldFireIntroTargetReaction(state, nowTick)) {
			if (!state.introTargetLocked) {
				interruptAndFastForwardPlayerNarration(player, state, nowTick);
			}
			fireIntroTargetReaction(server, player, state, nowTick);
		}
		announceServerBeforeIntroBitcoin(server, player, state, slot, nowTick);

		state.spinScore = Math.max(
				0.0D,
				state.spinScore * 0.72D + yawDelta + pitchDelta * 0.45D - (moved ? 8.0D : 0.0D)
		);

		if (state.lastOnGround && !player.onGround() && player.getDeltaMovement().y > 0.18D && nowTick >= state.nextJumpReactionTick) {
			state.nextJumpReactionTick = nowTick + INTRO_JUMP_REPEAT_TICKS;
			SeasonStartVoiceSystem.fireTrigger(server, "intro_phase1_jump", player);
		}

		if (state.spinScore >= INTRO_SPIN_TRIGGER_SCORE && nowTick >= state.nextSpinReactionTick) {
			state.nextSpinReactionTick = nowTick + INTRO_SPIN_REPEAT_TICKS;
			state.spinScore = 0.0D;
			SeasonStartVoiceSystem.fireTrigger(server, "intro_phase1_spin", player);
		}

		double leaveDistanceSqr = horizontalDistanceSqr(player.position(), slot.spawnPos);
		double oreDistanceSqr = horizontalDistanceSqr(player.position(), centerOf(slot.orePos));
		boolean leftIntroTaskArea = leaveDistanceSqr >= 12.0D * 12.0D && oreDistanceSqr >= 7.0D * 7.0D;
		// This is an edge-triggered reaction: standing at the far wall is not a new
		// escape attempt every few seconds. Once the player stops, the idle cue owns
		// the conversation and has its deliberate ten-second cadence.
		if (leftIntroTaskArea && !state.leftIntroTaskArea) {
			SeasonStartVoiceSystem.fireTrigger(server, "intro_phase1_leave_attempt", player);
		}
		state.leftIntroTaskArea = leftIntroTaskArea;

		if (nowTick - state.lastActivityTick >= INTRO_IDLE_TRIGGER_TICKS && nowTick >= state.nextIdleReactionTick) {
			state.nextIdleReactionTick = nowTick + INTRO_IDLE_REPEAT_TICKS;
			SeasonStartVoiceSystem.fireTrigger(server, "intro_phase1_idle", player);
		}

		updateObservationBaseline(player, state);
	}

	private static void announceServerBeforeIntroBitcoin(
			MinecraftServer server,
			ServerPlayer player,
			PlayerSceneState state,
			SlotDefinition slot,
			long nowTick
	) {
		if (server == null || player == null || state == null || slot == null
				|| state.announcedServerBeforeBitcoin
				|| !isCloseAndLookingAtServer(player, GUIDANCE_SERVER_SIGHT_DISTANCE)) {
			return;
		}
		state.announcedServerBeforeBitcoin = true;
		// The player has already found the wrong objective. Do not leave the ore
		// hidden behind the remainder of narration that was just cancelled.
		state.guidanceNarrationGateTick = nowTick;
		state.nextGuidanceTick = nowTick;
		state.nextGuidanceVoiceTick = nowTick;
		state.nextGuidanceEarliestTick = nowTick;
		if (!state.introOreRevealed && player.level() instanceof ServerLevel level) {
			state.introOreRevealed = true;
			placeIntroOre(level, slot);
		}
		resetGuidanceRoute(state);
		stateDirty = true;
		SeasonStartVoiceSystem.clearPlayerChannel(player);
		SeasonStartVoiceSystem.fireTrigger(server, "intro_server_before_bitcoin", player);
	}

	private static GuidanceSnapshot resolveGuidanceSnapshot(ServerPlayer player) {
		if (player == null || serverAnchor == null) {
			return null;
		}
		ServerStructureBounds bounds = resolveServerStructureBounds();
		if (bounds == null) {
			return resolveGuidanceSnapshot(player, new Vec3(serverAnchor.getX() + 0.5D, player.getY(), serverAnchor.getZ() + 0.5D));
		}
		Vec3 playerPos = player.position();
		double targetX = Mth.clamp(playerPos.x, bounds.minX, bounds.maxX);
		double targetZ = Mth.clamp(playerPos.z, bounds.minZ, bounds.maxZ);
		return resolveGuidanceSnapshot(player, new Vec3(targetX, player.getY(), targetZ));
	}

	private static GuidanceSnapshot resolveGuidanceSnapshot(ServerPlayer player, Vec3 target) {
		if (player == null || target == null) {
			return null;
		}
		Vec3 playerPos = player.position();
		Vec3 toTarget = target.subtract(playerPos);
		double horizontalDistance = Math.sqrt(toTarget.x * toTarget.x + toTarget.z * toTarget.z);
		if (horizontalDistance <= 1.0E-4D) {
			return new GuidanceSnapshot(0.0D, 0.0D, true, 0, player.getYRot());
		}
		float targetYaw = (float) (Math.atan2(-toTarget.x, toTarget.z) * Mth.RAD_TO_DEG);
		double deltaYaw = Mth.wrapDegrees(targetYaw - player.getYRot());
		boolean aligned = Math.abs(deltaYaw) <= GUIDANCE_LOCK_ANGLE;
		return new GuidanceSnapshot(horizontalDistance, deltaYaw, aligned, guidanceDistanceBucket(horizontalDistance), player.getYRot());
	}

	private static GuidanceInstruction resolveGuidanceInstruction(
			GuidanceSnapshot snapshot,
			double distanceDelta,
			boolean hasBitcoin,
			boolean seesServer,
			PlayerSceneState state,
			long nowTick
	) {
		if (snapshot == null || state == null) {
			return null;
		}
		double absYaw = Math.abs(snapshot.deltaYaw);
		boolean quietZone = snapshot.horizontalDistance <= GUIDANCE_QUIET_DISTANCE;
		boolean inDropZone = snapshot.horizontalDistance <= GUIDANCE_DROP_DISTANCE;

		if (hasBitcoin && state.wasGuidanceClose && snapshot.horizontalDistance >= GUIDANCE_PASSED_SERVER_DISTANCE
				&& distanceDelta >= GUIDANCE_PROGRESS_AWAY) {
			return new GuidanceInstruction("guide_passed_server", "guide_passed_server", GUIDE_PASSED_SERVER_TRIGGERS, GUIDANCE_CATEGORY_COOLDOWN_TICKS);
		}
		if (hasBitcoin && inDropZone) {
			return new GuidanceInstruction("guide_drop_coin", "guide_drop_coin", GUIDE_DROP_COIN_TRIGGERS, GUIDANCE_FORWARD_COOLDOWN_TICKS);
		}
		if (hasBitcoin && quietZone) {
			if (!state.announcedServerSight && (snapshot.aligned || seesServer)) {
				return new GuidanceInstruction("guide_server_in_sight", "guide_server_in_sight", GUIDE_SERVER_IN_SIGHT_TRIGGERS, GUIDANCE_FORWARD_COOLDOWN_TICKS);
			}
			if (state.wasGuidanceAligned && absYaw >= GUIDANCE_MICRO_ANGLE) {
				return new GuidanceInstruction("guide_heading_lost", "guide_heading_lost", GUIDE_HEADING_LOST_TRIGGERS, GUIDANCE_CORRECTION_COOLDOWN_TICKS);
			}
			if (absYaw >= GUIDANCE_MEDIUM_ANGLE) {
				return new GuidanceInstruction("guide_close_presence", "guide_close_presence", GUIDE_CLOSE_PRESENCE_TRIGGERS, GUIDANCE_STALL_COOLDOWN_TICKS);
			}
			return null;
		}
		if (hasBitcoin && seesServer && !state.announcedServerSight) {
			return new GuidanceInstruction("guide_server_in_sight", "guide_server_in_sight", GUIDE_SERVER_IN_SIGHT_TRIGGERS, GUIDANCE_FORWARD_COOLDOWN_TICKS);
		}

		GuidanceInstruction turnInstruction = resolveTurnInstruction(snapshot, absYaw, state, nowTick);
		if (turnInstruction != null) {
			return turnInstruction;
		}
		if (distanceDelta >= GUIDANCE_PROGRESS_AWAY) {
			return new GuidanceInstruction("guide_wrong_way", "guide_wrong_way", GUIDE_WRONG_WAY_TRIGGERS, GUIDANCE_CATEGORY_COOLDOWN_TICKS);
		}
		if (!state.wasGuidanceAligned && snapshot.aligned) {
			return new GuidanceInstruction("guide_locked_on", "guide_locked_on", GUIDE_LOCKED_ON_TRIGGERS, GUIDANCE_FORWARD_COOLDOWN_TICKS);
		}
		if (Math.abs(distanceDelta) < GUIDANCE_STALL_DELTA) {
			GuidanceInstruction stallInstruction = resolveStallInstruction(snapshot, state, nowTick);
			if (stallInstruction != null) {
				return stallInstruction;
			}
		}
		if (snapshot.distanceBucket != state.lastGuidanceDistanceBucket || distanceDelta <= GUIDANCE_PROGRESS_TOWARD) {
			return switch (snapshot.distanceBucket) {
				case 2 -> new GuidanceInstruction("guide_forward_close", "guide_forward_close", GUIDE_FORWARD_CLOSE_TRIGGERS, GUIDANCE_FORWARD_COOLDOWN_TICKS);
				case 4 -> new GuidanceInstruction("guide_forward_near", "guide_forward_near", GUIDE_FORWARD_NEAR_TRIGGERS, GUIDANCE_FORWARD_COOLDOWN_TICKS);
				case 7 -> new GuidanceInstruction("guide_forward_mid", "guide_forward_mid", GUIDE_FORWARD_MID_TRIGGERS, GUIDANCE_FORWARD_COOLDOWN_TICKS);
				default -> new GuidanceInstruction("guide_forward_far", "guide_forward_far", GUIDE_FORWARD_FAR_TRIGGERS, GUIDANCE_FORWARD_COOLDOWN_TICKS);
			};
		}
		return null;
	}

	private static GuidanceInstruction resolveIntroGuidanceInstruction(
			GuidanceSnapshot snapshot,
			double distanceDelta,
			boolean lookingAtOre,
			VerticalAimHint verticalAimHint,
			PlayerSceneState state,
			long nowTick
	) {
		if (snapshot == null || state == null || lookingAtOre) {
			return null;
		}
		if (verticalAimHint == VerticalAimHint.UP) {
			return new GuidanceInstruction("intro_target_look_up", "intro_target_look_up", INTRO_TARGET_LOOK_UP_TRIGGERS, GUIDANCE_STALL_COOLDOWN_TICKS);
		}
		if (verticalAimHint == VerticalAimHint.DOWN) {
			return new GuidanceInstruction("intro_target_look_down", "intro_target_look_down", INTRO_TARGET_LOOK_DOWN_TRIGGERS, GUIDANCE_STALL_COOLDOWN_TICKS);
		}
		double absYaw = Math.abs(snapshot.deltaYaw);
		GuidanceInstruction turnInstruction = resolveTurnInstruction(snapshot, absYaw, state, nowTick);
		if (turnInstruction != null) {
			return turnInstruction;
		}
		if (distanceDelta >= GUIDANCE_PROGRESS_AWAY) {
			return new GuidanceInstruction("intro_guide_wrong_way", "intro_guide_wrong_way", INTRO_GUIDE_WRONG_WAY_TRIGGERS, GUIDANCE_CATEGORY_COOLDOWN_TICKS);
		}
		if (!state.wasGuidanceAligned && snapshot.aligned) {
			return new GuidanceInstruction("guide_locked_on", "guide_locked_on", GUIDE_LOCKED_ON_TRIGGERS, GUIDANCE_FORWARD_COOLDOWN_TICKS);
		}
		if (Math.abs(distanceDelta) < GUIDANCE_STALL_DELTA) {
			GuidanceInstruction stallInstruction = resolveStallInstruction(snapshot, state, nowTick);
			if (stallInstruction != null) {
				return stallInstruction;
			}
		}
		if (snapshot.distanceBucket != state.lastGuidanceDistanceBucket || distanceDelta <= GUIDANCE_PROGRESS_TOWARD) {
			return switch (snapshot.distanceBucket) {
				case 2 -> new GuidanceInstruction("guide_forward_close", "guide_forward_close", GUIDE_FORWARD_CLOSE_TRIGGERS, GUIDANCE_FORWARD_COOLDOWN_TICKS);
				case 4 -> new GuidanceInstruction("guide_forward_near", "guide_forward_near", GUIDE_FORWARD_NEAR_TRIGGERS, GUIDANCE_FORWARD_COOLDOWN_TICKS);
				case 7 -> new GuidanceInstruction("guide_forward_mid", "guide_forward_mid", GUIDE_FORWARD_MID_TRIGGERS, GUIDANCE_FORWARD_COOLDOWN_TICKS);
				default -> new GuidanceInstruction("guide_forward_far", "guide_forward_far", GUIDE_FORWARD_FAR_TRIGGERS, GUIDANCE_FORWARD_COOLDOWN_TICKS);
			};
		}
		return null;
	}

	private static GuidanceInstruction resolveTurnInstruction(
			GuidanceSnapshot snapshot,
			double absYaw,
			PlayerSceneState state,
			long nowTick
	) {
		if (snapshot == null || state == null) {
			return null;
		}
		TurnHintDirection desiredDirection = snapshot.deltaYaw < 0.0D ? TurnHintDirection.LEFT : TurnHintDirection.RIGHT;
		GuidanceInstruction recoverInstruction = resolveTurnRecoverInstruction(state, desiredDirection, snapshot, absYaw, nowTick);
		if (recoverInstruction != null) {
			return recoverInstruction;
		}
		if (absYaw >= GUIDANCE_TURN_AROUND_ANGLE) {
			return snapshot.deltaYaw < 0.0D
					? new GuidanceInstruction("guide_turn_around_left", "guide_turn_around_left", GUIDE_TURN_AROUND_LEFT_TRIGGERS, GUIDANCE_CATEGORY_COOLDOWN_TICKS)
					: new GuidanceInstruction("guide_turn_around_right", "guide_turn_around_right", GUIDE_TURN_AROUND_RIGHT_TRIGGERS, GUIDANCE_CATEGORY_COOLDOWN_TICKS);
		}
		if (state.wasGuidanceAligned && absYaw >= GUIDANCE_MICRO_ANGLE) {
			return new GuidanceInstruction("guide_heading_lost", "guide_heading_lost", GUIDE_HEADING_LOST_TRIGGERS, GUIDANCE_CORRECTION_COOLDOWN_TICKS);
		}
		if (absYaw >= GUIDANCE_HARD_ANGLE) {
			return snapshot.deltaYaw < 0.0D
					? new GuidanceInstruction("guide_turn_left_hard", "guide_turn_left_hard", GUIDE_TURN_LEFT_HARD_TRIGGERS, GUIDANCE_CATEGORY_COOLDOWN_TICKS)
					: new GuidanceInstruction("guide_turn_right_hard", "guide_turn_right_hard", GUIDE_TURN_RIGHT_HARD_TRIGGERS, GUIDANCE_CATEGORY_COOLDOWN_TICKS);
		}
		if (absYaw >= GUIDANCE_MEDIUM_ANGLE) {
			return snapshot.deltaYaw < 0.0D
					? new GuidanceInstruction("guide_turn_left_medium", "guide_turn_left_medium", GUIDE_TURN_LEFT_MEDIUM_TRIGGERS, GUIDANCE_CORRECTION_COOLDOWN_TICKS)
					: new GuidanceInstruction("guide_turn_right_medium", "guide_turn_right_medium", GUIDE_TURN_RIGHT_MEDIUM_TRIGGERS, GUIDANCE_CORRECTION_COOLDOWN_TICKS);
		}
		if (absYaw >= GUIDANCE_MICRO_ANGLE) {
			return snapshot.deltaYaw < 0.0D
					? new GuidanceInstruction("guide_turn_left_soft", "guide_turn_left_soft", GUIDE_TURN_LEFT_SOFT_TRIGGERS, GUIDANCE_CORRECTION_COOLDOWN_TICKS)
					: new GuidanceInstruction("guide_turn_right_soft", "guide_turn_right_soft", GUIDE_TURN_RIGHT_SOFT_TRIGGERS, GUIDANCE_CORRECTION_COOLDOWN_TICKS);
		}
		return null;
	}

	private static GuidanceInstruction resolveTurnRecoverInstruction(
			PlayerSceneState state,
			TurnHintDirection desiredDirection,
			GuidanceSnapshot snapshot,
			double absYaw,
			long nowTick
	) {
		TurnHintDirection recoverContextDirection = state == null
				? TurnHintDirection.NONE
				: resolveRecoverContextDirection(state.lastGuidanceStateKey);
		if (state == null || snapshot == null
				|| desiredDirection == TurnHintDirection.NONE
				|| recoverContextDirection != desiredDirection
				|| state.lastGuidanceTurnDirection != desiredDirection
				|| state.lastGuidanceTurnRecoverUsed
				|| !Double.isFinite(state.lastGuidanceTurnAbsYaw)
				|| nowTick < state.turnRecoveryAllowedTick
				|| nowTick - state.lastGuidanceTurnTick > GUIDANCE_RECOVER_REACTION_WINDOW_TICKS
				|| absYaw < state.lastGuidanceTurnAbsYaw + GUIDANCE_RECOVER_WORSEN_THRESHOLD
				|| !hasTurnedOppositeToGuidance(state, desiredDirection, snapshot.playerYaw)) {
			return null;
		}
		return desiredDirection == TurnHintDirection.LEFT
				? new GuidanceInstruction("guide_turn_left_recover", "guide_turn_left_recover", GUIDE_TURN_LEFT_RECOVER_TRIGGERS, GUIDANCE_CORRECTION_COOLDOWN_TICKS)
				: new GuidanceInstruction("guide_turn_right_recover", "guide_turn_right_recover", GUIDE_TURN_RIGHT_RECOVER_TRIGGERS, GUIDANCE_CORRECTION_COOLDOWN_TICKS);
	}

	private static boolean hasTurnedOppositeToGuidance(
			PlayerSceneState state,
			TurnHintDirection desiredDirection,
			float currentYaw
	) {
		if (state == null || desiredDirection == TurnHintDirection.NONE || !Float.isFinite(state.lastGuidanceTurnPlayerYaw)) {
			return false;
		}
		float yawDelta = Mth.wrapDegrees(currentYaw - state.lastGuidanceTurnPlayerYaw);
		return desiredDirection == TurnHintDirection.LEFT
				? yawDelta >= GUIDANCE_TURN_RECOVERY_WRONG_YAW_DEGREES
				: yawDelta <= -GUIDANCE_TURN_RECOVERY_WRONG_YAW_DEGREES;
	}

	private static VerticalAimHint resolveIntroVerticalAimHint(
			ServerPlayer player,
			SlotDefinition slot,
			GuidanceSnapshot snapshot,
			boolean lookingAtOre
	) {
		if (player == null || slot == null || snapshot == null || lookingAtOre) {
			return VerticalAimHint.NONE;
		}
		if (snapshot.horizontalDistance > INTRO_TARGET_VERTICAL_GUIDANCE_DISTANCE
				|| Math.abs(snapshot.deltaYaw) > INTRO_TARGET_VERTICAL_YAW_WINDOW) {
			return VerticalAimHint.NONE;
		}
		Vec3 eyePos = player.getEyePosition();
		Vec3 toTarget = centerOf(slot.orePos).subtract(eyePos);
		double horizontalDistance = Math.sqrt(toTarget.x * toTarget.x + toTarget.z * toTarget.z);
		if (horizontalDistance <= 1.0E-4D) {
			return VerticalAimHint.NONE;
		}
		double targetPitch = -(Math.atan2(toTarget.y, horizontalDistance) * Mth.RAD_TO_DEG);
		double pitchDelta = Mth.wrapDegrees(targetPitch - player.getXRot());
		if (pitchDelta >= INTRO_TARGET_VERTICAL_PITCH_THRESHOLD) {
			return VerticalAimHint.DOWN;
		}
		if (pitchDelta <= -INTRO_TARGET_VERTICAL_PITCH_THRESHOLD) {
			return VerticalAimHint.UP;
		}
		return VerticalAimHint.NONE;
	}

	private static GuidanceInstruction resolveStallInstruction(
			GuidanceSnapshot snapshot,
			PlayerSceneState state,
			long nowTick
	) {
		if (snapshot == null || state == null) {
			return null;
		}
		if (snapshot.aligned) {
			if (nowTick - state.lastGuidanceAlignedTick < GUIDANCE_STALL_AFTER_ALIGNMENT_TICKS) {
				return null;
			}
			return new GuidanceInstruction("guide_stall_aligned", "guide_stall_aligned", GUIDE_STALL_ALIGNED_TRIGGERS, GUIDANCE_STALL_COOLDOWN_TICKS);
		}
		if (nowTick - state.lastGuidanceTurnTick < GUIDANCE_STALL_AFTER_TURN_TICKS) {
			return null;
		}
		return new GuidanceInstruction("guide_stall_misaligned", "guide_stall_misaligned", GUIDE_STALL_MISALIGNED_TRIGGERS, GUIDANCE_STALL_COOLDOWN_TICKS);
	}

	private static int guidanceDistanceBucket(double distance) {
		if (distance <= 2.6D) {
			return 2;
		}
		if (distance <= 4.7D) {
			return 4;
		}
		if (distance <= 7.5D) {
			return 7;
		}
		return 10;
	}

	private static void applyGuidanceInstructionState(
			PlayerSceneState state,
			GuidanceInstruction instruction,
			GuidanceSnapshot snapshot,
			long nowTick
	) {
		if (state == null || instruction == null || snapshot == null) {
			return;
		}
		double absYaw = Math.abs(snapshot.deltaYaw);
		state.guidanceRouteStarted = true;
		updateGuidanceTurnContext(state, instruction, snapshot, absYaw, nowTick);
		if ("guide_locked_on".equals(instruction.stateKey)) {
			state.guidanceAlignedEver = true;
			state.lastGuidanceAlignedTick = nowTick;
			return;
		}
		if ("guide_heading_lost".equals(instruction.stateKey)
				|| "guide_wrong_way".equals(instruction.stateKey)
				|| "intro_guide_wrong_way".equals(instruction.stateKey)
				|| "guide_passed_server".equals(instruction.stateKey)
				|| "guide_close_presence".equals(instruction.stateKey)) {
			state.guidanceMistakeCount++;
			return;
		}
		if ("guide_turn_left_recover".equals(instruction.stateKey)) {
			state.guidanceMistakeCount++;
			return;
		}
		if ("guide_turn_right_recover".equals(instruction.stateKey)) {
			state.guidanceMistakeCount++;
			return;
		}
	}

	private static TurnHintDirection resolveTurnDirectionFromStateKey(String stateKey) {
		if (stateKey == null || stateKey.isBlank()) {
			return TurnHintDirection.NONE;
		}
		if (stateKey.contains("_left")) {
			return TurnHintDirection.LEFT;
		}
		if (stateKey.contains("_right")) {
			return TurnHintDirection.RIGHT;
		}
		return TurnHintDirection.NONE;
	}

	private static TurnHintDirection resolveRecoverContextDirection(String stateKey) {
		if (stateKey == null || stateKey.isBlank()) {
			return TurnHintDirection.NONE;
		}
		return switch (stateKey) {
			case "guide_turn_left_soft",
					"guide_turn_left_medium",
					"guide_turn_left_hard",
					"guide_turn_around_left",
					"guide_route_resume_left",
					"guide_route_reverse_left" -> TurnHintDirection.LEFT;
			case "guide_turn_right_soft",
					"guide_turn_right_medium",
					"guide_turn_right_hard",
					"guide_turn_around_right",
					"guide_route_resume_right",
					"guide_route_reverse_right" -> TurnHintDirection.RIGHT;
			default -> TurnHintDirection.NONE;
		};
	}

	private static void updateGuidanceTurnContext(
			PlayerSceneState state,
			GuidanceInstruction instruction,
			GuidanceSnapshot snapshot,
			double absYaw,
			long nowTick
	) {
		if (state == null || instruction == null || snapshot == null) {
			return;
		}
		if ("guide_turn_left_recover".equals(instruction.stateKey)) {
			state.lastGuidanceTurnDirection = TurnHintDirection.LEFT;
			state.lastGuidanceTurnAbsYaw = absYaw;
			state.lastGuidanceTurnTick = nowTick;
			state.lastGuidanceTurnRecoverUsed = true;
			return;
		}
		if ("guide_turn_right_recover".equals(instruction.stateKey)) {
			state.lastGuidanceTurnDirection = TurnHintDirection.RIGHT;
			state.lastGuidanceTurnAbsYaw = absYaw;
			state.lastGuidanceTurnTick = nowTick;
			state.lastGuidanceTurnRecoverUsed = true;
			return;
		}
		TurnHintDirection followUpDirection = resolveRecoverContextDirection(instruction.stateKey);
		if (followUpDirection != TurnHintDirection.NONE) {
			state.lastGuidanceTurnDirection = followUpDirection;
			state.lastGuidanceTurnAbsYaw = absYaw;
			state.lastGuidanceTurnPlayerYaw = snapshot.playerYaw;
			state.lastGuidanceTurnTick = nowTick;
			state.turnRecoveryAllowedTick = nowTick + resolveTurnRecoveryDelayTicks(instruction.triggers);
			state.lastGuidanceTurnRecoverUsed = false;
			return;
		}
		state.lastGuidanceTurnDirection = TurnHintDirection.NONE;
		state.lastGuidanceTurnAbsYaw = Double.NaN;
		state.lastGuidanceTurnPlayerYaw = Float.NaN;
		state.lastGuidanceTurnTick = Long.MIN_VALUE;
		state.turnRecoveryAllowedTick = Long.MIN_VALUE;
		state.lastGuidanceTurnRecoverUsed = false;
	}

	private static long resolveTurnRecoveryDelayTicks(String[] triggers) {
		long narrationTicks = resolveGuidanceNarrationLockTicks(triggers);
		return Math.max(GUIDANCE_TURN_RECOVERY_MIN_DELAY_TICKS, (narrationTicks + 1L) / 2L);
	}

	private static ServerStructureBounds resolveServerStructureBounds() {
		if (serverAnchor == null) {
			return null;
		}
		List<BlockPos> positions = ServerStructureBreakSystem.getStructurePositions(serverAnchor, serverStructureAxis);
		if (positions.isEmpty()) {
			return null;
		}
		double minX = Double.POSITIVE_INFINITY;
		double maxX = Double.NEGATIVE_INFINITY;
		double minZ = Double.POSITIVE_INFINITY;
		double maxZ = Double.NEGATIVE_INFINITY;
		for (BlockPos pos : positions) {
			minX = Math.min(minX, pos.getX());
			maxX = Math.max(maxX, pos.getX() + 1.0D);
			minZ = Math.min(minZ, pos.getZ());
			maxZ = Math.max(maxZ, pos.getZ() + 1.0D);
		}
		return new ServerStructureBounds(minX, maxX, minZ, maxZ);
	}

	private static long resolveTriggerSequenceDurationTicks(String trigger) {
		if (trigger == null || trigger.isBlank()) {
			return 0L;
		}
		long maxTicks = 0L;
		Map<String, Long> channelEndTicks = new HashMap<>();
		for (SeasonStartConfig.VoiceCue cue : SeasonStartConfig.get().cues) {
			if (cue == null || !trigger.equals(cue.trigger)) {
				continue;
			}
			String channelKey = cue.channel == null || cue.channel.isBlank() ? "global" : cue.channel;
			long scheduledTick = Math.max(0L, cue.delayTicks);
			long startTick = Math.max(scheduledTick, channelEndTicks.getOrDefault(channelKey, 0L));
			long endTick = startTick + Math.max(0L, cue.durationTicks);
			channelEndTicks.put(channelKey, endTick);
			maxTicks = Math.max(maxTicks, endTick);
		}
		return maxTicks;
	}

	private static long resolveGuidanceNarrationLockTicks(String[] triggers) {
		if (triggers == null || triggers.length == 0) {
			return GUIDANCE_MIN_VOICE_GAP_TICKS;
		}
		long maxTicks = GUIDANCE_MIN_VOICE_GAP_TICKS;
		for (String trigger : triggers) {
			maxTicks = Math.max(maxTicks, resolveTriggerSequenceDurationTicks(trigger));
		}
		return maxTicks;
	}

	private static boolean shouldBypassGuidanceNarrationLock(GuidanceInstruction instruction) {
		if (instruction == null || instruction.stateKey == null || instruction.stateKey.isBlank()) {
			return false;
		}
		return "guide_wrong_way".equals(instruction.stateKey)
				|| "guide_passed_server".equals(instruction.stateKey)
				|| "intro_guide_wrong_way".equals(instruction.stateKey)
				|| "guide_turn_left_recover".equals(instruction.stateKey)
				|| "guide_turn_right_recover".equals(instruction.stateKey);
	}

	private static long resolveCueEndTickById(String cueId) {
		if (cueId == null || cueId.isBlank()) {
			return 0L;
		}
		for (SeasonStartConfig.VoiceCue cue : SeasonStartConfig.get().cues) {
			if (cue != null && cueId.equals(cue.id)) {
				return (long) cue.delayTicks + cue.durationTicks;
			}
		}
		return 0L;
	}

	private static void transitionPlayerAfterFirstPayment(MinecraftServer server, ServerPlayer player) {
		if (server == null || player == null || serverAnchor == null) {
			return;
		}
		SeasonStartVoiceSystem.clearPlayerChannel(player);
		PlayerSceneState state = PLAYER_STATES.get(player.getUUID());
		if (state == null || state.phase == PlayerPhase.SHARED || state.phase == PlayerPhase.RESTORING) {
			return;
		}
		long nowTick = player.level() == null ? 0L : player.level().getGameTime();
		state.phase = PlayerPhase.RESTORING;
		clearPersonalExitSign(player.level() instanceof ServerLevel level ? level : null, state);
		state.poweredServer = true;
		state.nextGuidanceTick = Long.MAX_VALUE;
		state.nextGuidanceVoiceTick = Long.MAX_VALUE;
		state.nextGuidanceEarliestTick = Long.MAX_VALUE;
		state.lastGuidanceStateKey = "";
		state.restoreVisionTick = nowTick + resolveCueEndTickById("phase3_restore_vision");
		state.sharedVisionRestored = false;
		state.pendingSharedPeersLine = countSharedPlayers() > 0;
		stateDirty = true;

		ensureRestoringPlayerState(player, state);
		SeasonStartVoiceSystem.fireTrigger(server, "player_powered_server", player);
	}

	private static void handleIntroOreBroken(ServerLevel level, ServerPlayer player, BlockPos pos, PlayerSceneState state) {
		SeasonStartVoiceSystem.clearPlayerChannel(player);
		restoreStartupLight(level, pos);
		if (isStartupShellBlock(level.getBlockState(pos.below()))) {
			level.setBlock(pos.below(), Blocks.AIR.defaultBlockState(), 3);
		}
		level.sendParticles(ParticleTypes.CRIT, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, 16, 0.3D, 0.3D, 0.3D, 0.02D);
		level.playSound(null, pos, SoundEvents.STONE_BREAK, SoundSource.BLOCKS, 1.0F, 0.85F);
		if (!hasBitcoin(player)) {
			giveOrDrop(player, new ItemStack(ModItems.BITCOIN));
		}
		state.minedIntroBitcoin = true;
		state.introOreRevealed = false;
		state.phase = PlayerPhase.GUIDED_TO_SERVER;
		state.nextGuidanceTick = 0L;
		state.nextGuidanceVoiceTick = level.getGameTime() + 28L;
		state.nextGuidanceEarliestTick = level.getGameTime() + 28L;
		state.guidanceNarrationGateTick = level.getGameTime() + resolveTriggerSequenceDurationTicks("player_mined_intro_bitcoin");
		state.lastGuidanceStateKey = "";
		state.lastGuidanceDistance = Double.NaN;
		state.lastGuidanceAbsYaw = Double.NaN;
		state.lastGuidanceDistanceBucket = Integer.MIN_VALUE;
		state.wasGuidanceAligned = false;
		state.wasGuidanceClose = false;
		state.lastGuidanceAlignedTick = Long.MIN_VALUE;
		state.guidanceRouteStarted = true;
		state.guidanceAlignedEver = false;
		state.guidanceMistakeCount = 0;
		state.lastGuidanceTurnDirection = TurnHintDirection.NONE;
		state.lastGuidanceTurnAbsYaw = Double.NaN;
		state.lastGuidanceTurnPlayerYaw = Float.NaN;
		state.lastGuidanceTurnTick = Long.MIN_VALUE;
		state.turnRecoveryAllowedTick = Long.MIN_VALUE;
		state.lastGuidanceTurnRecoverUsed = false;
		state.announcedServerSight = false;
		state.announcedServerBeforeBitcoin = false;
		state.announcedServerWithoutBitcoin = false;
		state.wasAtServerWithoutBitcoin = false;
		state.leftServerWhileRecoveringBitcoin = false;
		state.serverWithoutBitcoinVisitCount = 0;
		state.guidedBitcoinEscapeCount = 0;
		state.lockedGuidedBitcoinSlot = NO_LOCKED_GUIDED_BITCOIN_SLOT;
		state.guidanceQuietZoneActive = false;
		state.guidanceCueCycles.clear();
		state.guidanceCueBags.clear();
		state.lastGuidanceTriggerByGroup.clear();
		state.guidanceSemanticNextAllowedTicks.clear();
		state.visibleGuidanceTargetKey = "";
		state.visibleGuidanceLastPlayerX = player.getX();
		state.visibleGuidanceLastPlayerZ = player.getZ();
		state.visibleGuidanceLastMoveTick = player.level().getGameTime();
		state.leftIntroTaskArea = false;
		resetGuidanceRoute(state);
		state.spinScore = 0.0D;
		state.introTargetLocked = false;
		state.nextIntroTargetReactionTick = 0L;
		stateDirty = true;
		SeasonStartVoiceSystem.fireTrigger(level.getServer(), "player_mined_intro_bitcoin", player);
	}

	private static void tickRestoringPhase(MinecraftServer server, ServerPlayer player, PlayerSceneState state) {
		if (server == null || player == null || state == null || player.level() == null) {
			return;
		}
		long nowTick = player.level().getGameTime();
		if (state.sharedVisionRestored || nowTick < state.restoreVisionTick) {
			return;
		}
		INTRO_BLINDNESS.clear(player);
		state.sharedVisionRestored = true;
		state.phase = PlayerPhase.SHARED;
		clearPersonalExitSign(player.level() instanceof ServerLevel level ? level : null, state);
		state.restoreVisionTick = Long.MAX_VALUE;
		stateDirty = true;
		applySharedPlayerState(player);
		syncBitcoinVisibilityForPlayer(player.level() instanceof ServerLevel level ? level : null, player, state);
		syncPrivatePlayerProfiles(server);
		refreshSharedPlayerEntityTracking(server);
		spawnLightOnlineParticles(server.overworld());
		if (state.pendingSharedPeersLine) {
			SeasonStartVoiceSystem.fireTrigger(server, "player_powered_server_others_visible", player);
			state.pendingSharedPeersLine = false;
		}
		onPlayerEnteredSharedPhase(server);
	}

	private static void onPlayerEnteredSharedPhase(MinecraftServer server) {
		ServerLevel overworld = server == null ? null : server.overworld();
		if (server == null || overworld == null) {
			return;
		}
		if (sharedLaunchRequiredBitcoins <= 0) {
			sharedLaunchRequiredBitcoins = SHARED_LAUNCH_REQUIRED_BITCOINS;
		}
		if (!sharedLaunchIntroTriggered) {
			sharedLaunchIntroTriggered = true;
			SeasonStartVoiceSystem.fireTrigger(server, "first_player_shared_phase", null);
		}
		lastSharedLaunchProgressTick = overworld.getGameTime();
		ensureSharedBitcoinPopulation(overworld);
		refreshSharedLaunchBossBar(server);
		stateDirty = true;
	}

	private static int countSharedPlayers() {
		int count = 0;
		for (PlayerSceneState state : PLAYER_STATES.values()) {
			if (state != null && state.phase == PlayerPhase.SHARED) {
				count++;
			}
		}
		return count;
	}

	private static void tickSharedLaunch(MinecraftServer server) {
		ServerLevel overworld = server == null ? null : server.overworld();
		if (server == null || overworld == null) {
			return;
		}
		ensureStartupWorldgenDisplay(overworld);
		refreshSharedLaunchBossBar(server);
		long nowTick = overworld.getGameTime();
		if (pendingSharedFinishTick != Long.MIN_VALUE && nowTick >= pendingSharedFinishTick) {
			pendingSharedFinishTick = Long.MIN_VALUE;
			finishSeasonStart(server);
			return;
		}
		if (countSharedPlayers() <= 0) {
			return;
		}
		ensureSharedServerPowerNarration(server);
		ensureSharedRaceControlsNarration(server);
		if (!menuExplanationActive && sharedLaunchRequiredBitcoins > 0 && getSharedLaunchPercent() >= MENU_EXPLANATION_UNLOCK_PERCENT
				&& pendingMenuExplanationTick == Long.MIN_VALUE) {
			pendingMenuExplanationTick = nowTick + MENU_EXPLANATION_DELAY_TICKS;
		}
		if (!menuExplanationActive && pendingMenuExplanationTick != Long.MIN_VALUE && nowTick >= pendingMenuExplanationTick) {
			beginMenuExplanationPhase(server);
		}
		ensureSharedBitcoinPopulation(overworld);
	}

	private static void beginMenuExplanationPhase(MinecraftServer server) {
		ServerLevel overworld = server == null ? null : server.overworld();
		if (server == null || overworld == null || menuExplanationActive) {
			return;
		}
		menuExplanationActive = true;
		pendingMenuExplanationTick = Long.MIN_VALUE;
		long nowTick = overworld.getGameTime();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!isSeasonStartEligiblePlayer(player)) {
				continue;
			}
			PlayerSceneState state = PLAYER_STATES.get(player.getUUID());
			if (state == null || state.phase != PlayerPhase.SHARED) {
				continue;
			}
			state.menuOpened = false;
			state.menuNarrationMuted = false;
			state.raceMenuReached = false;
			state.racePurchaseExplained = false;
			state.raceMenuReminderExplained = false;
			state.seenMenuSections.clear();
			state.activeMenuSection = MenuSection.ROOT.id;
			if (!state.menuRaceAllowanceGranted) {
				giveOrDrop(player, new ItemStack(ModItems.BITCOIN, 4));
				state.menuRaceAllowanceGranted = true;
			}
			state.nextMenuPriceReactionTick = nowTick;
			SeasonStartVoiceSystem.fireTrigger(server, "player_menu_phase_started", player);
		}
		stateDirty = true;
	}

	/**
	 * Applies a client-only biome to the startup chunk window. This hides the
	 * world in the small chunk buffer around the physical shell without changing
	 * terrain, and therefore cannot affect the reveal or the saved world.
	 */
	private static void tickStartupBiomeOverrides(MinecraftServer server) {
		if (server == null) {
			return;
		}
		ServerLevel level = server.overworld();
		if (level == null) {
			return;
		}
		long nowTick = level.getGameTime();
		if (active && serverAnchor != null) {
			BoxGeometry box = computeOuterBoxGeometry(resolveServerAnchor(level));
			List<ChunkPos> chunks = chunkPositions(collectStartupChunkKeys(box, STARTUP_CHUNK_TRACKING_GUARD_RING));
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				if (!shouldUseStartupChunkTracking(player)) {
					continue;
				}
				StartupBiomeOverride state = STARTUP_BIOME_OVERRIDES.computeIfAbsent(
						player.getUUID(),
						ignored -> new StartupBiomeOverride(level.dimension(), chunks, level.getSectionsCount())
				);
				state.updateWindow(level.dimension(), chunks, level.getSectionsCount());
				tickStartupBiomeOverride(player, level, state, 0, nowTick);
			}
		}

		if (worldRevealActive) {
			int stage = resolveStartupBiomeRevealStage(nowTick);
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				StartupBiomeOverride state = STARTUP_BIOME_OVERRIDES.get(player.getUUID());
				if (state != null && level.dimension().equals(state.dimension)) {
					tickStartupBiomeOverride(player, level, state, stage, nowTick);
				}
			}
		}

		if (active || worldRevealActive) {
			return;
		}
		for (Iterator<Map.Entry<UUID, StartupBiomeOverride>> iterator = STARTUP_BIOME_OVERRIDES.entrySet().iterator(); iterator.hasNext(); ) {
			Map.Entry<UUID, StartupBiomeOverride> entry = iterator.next();
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player != null && player.level() == level) {
				restoreStartupBiomes(player, level, entry.getValue());
			}
			iterator.remove();
		}
	}

	private static int resolveStartupBiomeRevealStage(long nowTick) {
		if (worldRevealCrackStartTick == Long.MIN_VALUE) {
			return 0;
		}
		// The crack animation is the fade.  Later finale phases keep the final
		// light stage until the genuine chunk biomes are restored on completion.
		if (worldRevealPhase != WorldRevealPhase.CRACKING) {
			return STARTUP_REVEAL_BIOME_KEYS.size() - 1;
		}
		long elapsed = Math.max(0L, nowTick - worldRevealCrackStartTick);
		return Mth.clamp(
				(int) (elapsed * (STARTUP_REVEAL_BIOME_KEYS.size() - 1) / Math.max(1L, WORLD_REVEAL_CRACKING_DURATION_TICKS)),
				0,
				STARTUP_REVEAL_BIOME_KEYS.size() - 1
		);
	}

	private static void tickStartupBiomeOverride(
			ServerPlayer player,
			ServerLevel level,
			StartupBiomeOverride state,
			int stage,
			long nowTick
	) {
		if (player == null || player.connection == null || state == null || state.chunks.isEmpty()) {
			return;
		}
		if (stage != state.appliedStage || nowTick >= state.nextResyncTick) {
			state.appliedStage = stage;
			state.nextChunkIndex = 0;
			state.nextResyncTick = nowTick + STARTUP_BIOME_RESYNC_TICKS;
		}
		if (state.nextChunkIndex >= state.chunks.size()) {
			return;
		}
		byte[] payload = startupBiomePayload(level, state.sectionCount, STARTUP_REVEAL_BIOME_KEYS.get(stage));
		if (payload.length == 0) {
			return;
		}
		int endIndex = Math.min(state.chunks.size(), state.nextChunkIndex + STARTUP_BIOME_CHUNKS_PER_TICK);
		List<ClientboundChunksBiomesPacket.ChunkBiomeData> data = new ArrayList<>(endIndex - state.nextChunkIndex);
		for (int index = state.nextChunkIndex; index < endIndex; index++) {
			data.add(new ClientboundChunksBiomesPacket.ChunkBiomeData(state.chunks.get(index), payload));
		}
		state.nextChunkIndex = endIndex;
		player.connection.send(new ClientboundChunksBiomesPacket(data));
	}

	private static byte[] startupBiomePayload(ServerLevel level, int sectionCount, ResourceKey<Biome> biomeKey) {
		if (level == null || sectionCount <= 0 || biomeKey == null) {
			return new byte[0];
		}
		StartupBiomePayloadKey key = new StartupBiomePayloadKey(sectionCount, biomeKey);
		byte[] cached = STARTUP_BIOME_PAYLOAD_CACHE.get(key);
		if (cached != null) {
			return cached;
		}
		Holder<Biome> biome = level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(biomeKey);
		PalettedContainerFactory factory = PalettedContainerFactory.create(level.registryAccess());
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
		try {
			int biomeSize = 1 << LevelChunkSection.BIOME_CONTAINER_BITS;
			for (int section = 0; section < sectionCount; section++) {
				PalettedContainer<Holder<Biome>> biomes = factory.createForBiomes();
				for (int x = 0; x < biomeSize; x++) {
					for (int y = 0; y < biomeSize; y++) {
						for (int z = 0; z < biomeSize; z++) {
							biomes.set(x, y, z, biome);
						}
					}
				}
				biomes.write(buffer);
			}
			byte[] payload = new byte[buffer.readableBytes()];
			buffer.getBytes(0, payload);
			STARTUP_BIOME_PAYLOAD_CACHE.put(key, payload);
			return payload;
		} finally {
			buffer.release();
		}
	}

	private static void restoreStartupBiomes(ServerPlayer player, ServerLevel level, StartupBiomeOverride state) {
		List<LevelChunk> chunks = new ArrayList<>();
		for (ChunkPos pos : state.chunks) {
			LevelChunk chunk = level.getChunkSource().chunkMap.getChunkToSend(pos.toLong());
			if (chunk == null) {
				chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
			}
			if (chunk != null) {
				chunks.add(chunk);
			}
		}
		if (!chunks.isEmpty()) {
			player.connection.send(ClientboundChunksBiomesPacket.forChunks(chunks));
		}
	}

	private static List<ChunkPos> chunkPositions(Set<Long> chunks) {
		if (chunks == null || chunks.isEmpty()) {
			return List.of();
		}
		List<ChunkPos> positions = new ArrayList<>(chunks.size());
		for (long chunk : chunks) {
			positions.add(new ChunkPos(chunk));
		}
		return positions;
	}

	private static void tickShellDissolve(MinecraftServer server) {
		ServerLevel overworld = server == null ? null : server.overworld();
		if (overworld == null || SHELL_DISSOLVE_ORDER.isEmpty()) {
			shellDissolving = false;
			return;
		}
		for (int i = 0; i < DISSOLVE_BATCH_BLOCKS && dissolveCursor < SHELL_DISSOLVE_ORDER.size(); i++, dissolveCursor++) {
			BlockPos pos = SHELL_DISSOLVE_ORDER.get(dissolveCursor);
			BlockState state = overworld.getBlockState(pos);
			if (!isStartupShellBlock(state) && !state.is(Blocks.BARRIER)) {
				continue;
			}
			if (isStartupShellBlock(state)) {
				overworld.sendParticles(ParticleTypes.SMOKE, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, 4, 0.2D, 0.2D, 0.2D, 0.01D);
			}
			overworld.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
		}
		if (dissolveCursor >= SHELL_DISSOLVE_ORDER.size()) {
			shellDissolving = false;
			SHELL_DISSOLVE_ORDER.clear();
			dissolveCursor = 0;
			PLAYER_STATES.clear();
			SHARED_BITCOIN_POSITIONS.clear();
			clearSharedLaunchBossBar();
			stateDirty = true;
		}
	}

	private static void tickWorldReveal(MinecraftServer server) {
		ServerLevel overworld = server == null ? null : server.overworld();
		if (server == null || overworld == null || worldRevealRecoveryPending) {
			return;
		}
		long nowTick = overworld.getGameTime();
		if (!prepareWorldReveal(overworld)) {
			return;
		}
		if (WORLD_REVEAL_SURFACE_Y.isEmpty()) {
			beginWorldRevealRecovery(server);
			return;
		}
		if (worldRevealPhaseStartTick == Long.MIN_VALUE) {
			worldRevealPhaseStartTick = nowTick;
		}
		switch (worldRevealPhase) {
			case CRACKING -> tickWorldRevealCracking(server, overworld, nowTick);
			case BLACKOUT_FADE -> tickWorldRevealBlackoutFade(server, overworld, nowTick);
			case RELOCATE -> tickWorldRevealRelocate(server, overworld, nowTick);
			case SETTLE -> tickWorldRevealSettle(server, overworld, nowTick);
			default -> beginWorldRevealSettlePhase(overworld, nowTick);
		}
	}

	private static void tickWorldRevealAudioPrelude(MinecraftServer server, ServerLevel level, long nowTick) {
		if (server == null || level == null) {
			return;
		}
		if (worldRevealCrackStartTick == Long.MIN_VALUE) {
			if (worldRevealCrackNotBeforeTick != Long.MIN_VALUE && nowTick < worldRevealCrackNotBeforeTick) {
				return;
			}
			worldRevealCrackStartTick = nowTick;
			worldRevealCrackNotBeforeTick = Long.MIN_VALUE;
			// Apply Darkness before the first earthquake packet reaches clients.
			startWorldRevealDarknessSequence(server, level, nowTick);
		}
		if (nowTick < worldRevealCrackStartTick) {
			return;
		}
		if (!worldRevealEarthquakeSoundStarted) {
			startWorldRevealEarthquakeSound(level);
			worldRevealEarthquakeSoundStarted = true;
			stateDirty = true;
		}
		maybeStartWorldRevealMusic(level, nowTick, nowTick - worldRevealCrackStartTick);
	}

	private static boolean prepareWorldReveal(ServerLevel level) {
		if (level == null || serverAnchor == null) {
			return false;
		}
		ensureWorldRevealSnapshotIndexes();
		if (WORLD_REVEAL_SURFACE_Y.isEmpty() || WORLD_REVEAL_TARGET_STATES.isEmpty()) {
			// A missing in-memory snapshot after a reload used to trigger a full
			// cube scan here. Rebuild it through the bounded recovery task instead.
			beginWorldRevealRecovery(level.getServer());
			return false;
		}
		if (shouldRebuildWorldRevealPlan() || (WORLD_REVEAL_EPISODES.isEmpty() && worldRevealPlanFuture == null)) {
			beginWorldRevealPlanPreparation(level);
		}
		if (!installPreparedWorldRevealPlan()) {
			return false;
		}
		if (worldRevealPhase == WorldRevealPhase.RELOCATE || worldRevealPhase == WorldRevealPhase.SETTLE) {
			refreshWorldRevealTargets(level);
		}
		return true;
	}

	private static void ensureWorldRevealSnapshotIndexes() {
		if (serverAnchor == null || WORLD_REVEAL_TERRAIN.isEmpty()) {
			return;
		}
		if (!WORLD_REVEAL_TARGET_STATES.isEmpty() && !WORLD_REVEAL_SURFACE_Y.isEmpty()) {
			return;
		}
		rebuildWorldRevealSnapshotIndexesFromTerrain();
	}

	private static boolean shouldRebuildWorldRevealPlan() {
		return !WORLD_REVEAL_EPISODES.isEmpty()
				&& !WORLD_REVEAL_TARGET_STATES.isEmpty()
				&& !WORLD_REVEAL_REQUIRED_POSITIONS.containsAll(WORLD_REVEAL_TARGET_STATES.keySet());
	}

	private static void resetWorldRevealPlanState() {
		WORLD_REVEAL_EPISODES.clear();
		WORLD_REVEAL_REQUIRED_POSITIONS.clear();
		WORLD_REVEAL_DEFERRED_POSITIONS.clear();
		WORLD_REVEAL_REVEALED_POSITIONS.clear();
		worldRevealVisibleEpisodeCursor = 0;
		worldRevealBurstCursor = 0;
		worldRevealBurstWeightProgress = 0.0F;
		stateDirty = true;
	}

	private static void ensureSceneSnapshot(ServerLevel level) {
		if (level == null || serverAnchor == null) {
			return;
		}
		ensureWorldRevealSnapshotIndexes();
		if (!WORLD_REVEAL_TERRAIN.isEmpty() && !WORLD_REVEAL_TARGET_STATES.isEmpty() && !WORLD_REVEAL_SURFACE_Y.isEmpty()) {
			return;
		}
		if (isSceneShellAlreadyBuilt(level)) {
			buildSceneSnapshotFromGenerator(level);
			return;
		}
		captureSceneSnapshotFromWorld(level);
	}

	private static boolean isSceneShellAlreadyBuilt(ServerLevel level) {
		if (level == null || serverAnchor == null) {
			return false;
		}
		BoxGeometry outerGeometry = computeOuterBoxGeometry(resolveServerAnchor(level));
		BoxGeometry barrierGeometry = computeBarrierGeometry(resolveServerAnchor(level));
		return isStartupShellBlock(level.getBlockState(new BlockPos(outerGeometry.minX, outerGeometry.floorY, outerGeometry.minZ)))
				|| isStartupShellBlock(level.getBlockState(new BlockPos(outerGeometry.minX, outerGeometry.roofY, outerGeometry.minZ)))
				|| level.getBlockState(new BlockPos(barrierGeometry.minX, barrierGeometry.floorY, barrierGeometry.minZ)).is(Blocks.BARRIER);
	}

	/**
	 * A restart must not turn an intact scene into a new construction job. A
	 * handful of floor, wall and roof sentinels is enough to distinguish the
	 * current box from an interrupted or legacy one without scanning the cube.
	 */
	private static boolean isCurrentSceneShellIntact(ServerLevel level, BoxGeometry outer, BoxGeometry barrier) {
		if (level == null || outer == null || barrier == null) {
			return false;
		}
		return isStartupShellBlock(level.getBlockState(new BlockPos(outer.minX, outer.floorY, outer.minZ)))
				&& isStartupShellBlock(level.getBlockState(new BlockPos(outer.maxX, outer.floorY, outer.maxZ)))
				&& isStartupShellBlock(level.getBlockState(new BlockPos(outer.minX, outer.roofY, outer.maxZ)))
				&& isStartupShellBlock(level.getBlockState(new BlockPos(outer.maxX, outer.roofY, outer.minZ)))
				&& isStartupShellBlock(level.getBlockState(new BlockPos(outer.minX, (outer.floorY + outer.roofY) / 2, (outer.minZ + outer.maxZ) / 2)))
				&& isStartupShellBlock(level.getBlockState(new BlockPos(outer.maxX, (outer.floorY + outer.roofY) / 2, (outer.minZ + outer.maxZ) / 2)))
				&& isStartupShellBlock(level.getBlockState(new BlockPos((outer.minX + outer.maxX) / 2, outer.roofY, (outer.minZ + outer.maxZ) / 2)))
				&& isBlock(level, barrier.minX, barrier.floorY, barrier.minZ, Blocks.BARRIER)
				&& isBlock(level, barrier.maxX, barrier.floorY, barrier.maxZ, Blocks.BARRIER)
				&& isBlock(level, barrier.minX, barrier.roofY, barrier.maxZ, Blocks.BARRIER)
				&& isBlock(level, barrier.maxX, barrier.roofY, barrier.minZ, Blocks.BARRIER);
	}

	private static boolean isBlock(ServerLevel level, int x, int y, int z, Block block) {
		return level.getBlockState(new BlockPos(x, y, z)).is(block);
	}

	/** Includes legacy black-concrete boxes so an interrupted old startup can still recover safely. */
	private static boolean isStartupShellBlock(BlockState state) {
		return state != null && (state.is(ModBlocks.STARTUP_VOID) || state.is(Blocks.BLACK_CONCRETE));
	}

	private static void captureSceneSnapshotFromWorld(ServerLevel level) {
		captureSceneSnapshot(level, false);
	}

	private static void buildSceneSnapshotFromGenerator(ServerLevel level) {
		captureSceneSnapshot(level, true);
	}

	private static void captureSceneSnapshot(ServerLevel level, boolean generatorFallback) {
		BlockPos anchor = resolveServerAnchor(level);
		if (level == null || anchor == null) {
			return;
		}
		BoxGeometry outerGeometry = computeOuterBoxGeometry(anchor);
		BoxGeometry barrierGeometry = computeBarrierGeometry(anchor);
		WORLD_REVEAL_TERRAIN.clear();
		WORLD_REVEAL_BARRIER_COLLISION.clear();
		WORLD_REVEAL_SURFACE_Y.clear();
		WORLD_REVEAL_TARGET_STATES.clear();
		WORLD_REVEAL_BOUNDARY_TARGET_STATES.clear();
		WORLD_REVEAL_SAFE_TARGETS.clear();
		captureBoundaryRestoreSnapshot(level, outerGeometry);

		ChunkGenerator generator = generatorFallback ? level.getChunkSource().getGenerator() : null;
		RandomState randomState = generatorFallback ? level.getChunkSource().randomState() : null;

		for (int x = outerGeometry.minX; x <= outerGeometry.maxX; x++) {
			for (int z = outerGeometry.minZ; z <= outerGeometry.maxZ; z++) {
				NoiseColumn column = generatorFallback ? generator.getBaseColumn(x, z, level, randomState) : null;
				int topSolidY = Integer.MIN_VALUE;
				int topFilledY = Integer.MIN_VALUE;
				boolean insideBarrier = x >= barrierGeometry.minX + 1
						&& x <= barrierGeometry.maxX - 1
						&& z >= barrierGeometry.minZ + 1
						&& z <= barrierGeometry.maxZ - 1;

				for (int y = outerGeometry.floorY; y <= outerGeometry.roofY; y++) {
					BlockPos pos = new BlockPos(x, y, z);
					if (isServerStructureFootprint(pos)) {
						continue;
					}
					BlockState state = generatorFallback ? column.getBlock(y) : level.getBlockState(pos);
					if (shouldIgnoreSceneSnapshotState(pos, state, outerGeometry, barrierGeometry)) {
						continue;
					}
					if (state == null || state.isAir()) {
						continue;
					}
					WORLD_REVEAL_TERRAIN.add(new TerrainPlacement(pos.immutable(), state));
					WORLD_REVEAL_TARGET_STATES.put(pos.asLong(), state);
					topFilledY = y;
					if (insideBarrier && isWorldRevealCollisionState(state)) {
						WORLD_REVEAL_BARRIER_COLLISION.add(pos.immutable());
						topSolidY = y;
					}
				}

				if (insideBarrier) {
					int fallbackSurface = barrierGeometry.floorY;
					int surfaceY = topSolidY != Integer.MIN_VALUE
							? topSolidY
							: (topFilledY != Integer.MIN_VALUE ? topFilledY : fallbackSurface);
					WORLD_REVEAL_SURFACE_Y.put(surfaceColumnKey(x, z), surfaceY);
				}
			}
		}
	}

	private static void captureBoundaryRestoreSnapshot(ServerLevel level, BoxGeometry outerGeometry) {
		if (level == null || outerGeometry == null) {
			return;
		}
		for (int x = outerGeometry.minX - WORLD_REVEAL_BOUNDARY_RESTORE_RADIUS; x <= outerGeometry.maxX + WORLD_REVEAL_BOUNDARY_RESTORE_RADIUS; x++) {
			for (int z = outerGeometry.minZ - WORLD_REVEAL_BOUNDARY_RESTORE_RADIUS; z <= outerGeometry.maxZ + WORLD_REVEAL_BOUNDARY_RESTORE_RADIUS; z++) {
				for (int y = outerGeometry.floorY - WORLD_REVEAL_BOUNDARY_RESTORE_RADIUS; y <= outerGeometry.roofY + WORLD_REVEAL_BOUNDARY_RESTORE_RADIUS; y++) {
					if (x >= outerGeometry.minX && x <= outerGeometry.maxX
							&& y >= outerGeometry.floorY && y <= outerGeometry.roofY
							&& z >= outerGeometry.minZ && z <= outerGeometry.maxZ) {
						continue;
					}
					BlockPos pos = new BlockPos(x, y, z);
					BlockState state = level.getBlockState(pos);
					if (state == null || state.isAir()) {
						continue;
					}
					WORLD_REVEAL_BOUNDARY_TARGET_STATES.put(pos.asLong(), state);
				}
			}
		}
	}

	private static void captureSceneEntitySnapshot(ServerLevel level, BoxGeometry outerGeometry) {
		if (level == null || outerGeometry == null) {
			return;
		}
		WORLD_REVEAL_ENTITY_SNAPSHOTS.clear();
		AABB sceneBounds = new AABB(
				outerGeometry.minX,
				outerGeometry.floorY,
				outerGeometry.minZ,
				outerGeometry.maxX + 1.0D,
				outerGeometry.roofY + 1.0D,
				outerGeometry.maxZ + 1.0D
		);
		for (Entity entity : level.getEntities((Entity) null, sceneBounds, SeasonStartSystem::shouldSnapshotSceneEntity)) {
			if (entity == null || entity.isRemoved()) {
				continue;
			}
			TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
			if (!entity.saveAsPassenger(output)) {
				continue;
			}
			CompoundTag tag = output.buildResult();
			if (tag == null || tag.isEmpty()) {
				continue;
			}
			WORLD_REVEAL_ENTITY_SNAPSHOTS.add(tag.copy());
			entity.discard();
		}
	}

	private static boolean shouldSnapshotSceneEntity(Entity entity) {
		if (entity == null || !entity.isAlive() || entity.isRemoved() || entity instanceof ServerPlayer || entity.isPassenger()) {
			return false;
		}
		if (entity instanceof ExperienceOrb) {
			return false;
		}
		if (entity.getTags().contains(STARTUP_WORLDGEN_DISPLAY_TAG) || ServerStructureBreakSystem.isServerStructureDisplay(entity)) {
			return false;
		}
		return true;
	}

	private static void rebuildWorldRevealSnapshotIndexesFromTerrain() {
		if (serverAnchor == null || WORLD_REVEAL_TERRAIN.isEmpty()) {
			return;
		}
		BoxGeometry outerGeometry = computeOuterBoxGeometry(serverAnchor);
		BoxGeometry barrierGeometry = computeBarrierGeometry(serverAnchor);
		WORLD_REVEAL_BARRIER_COLLISION.clear();
		WORLD_REVEAL_SURFACE_Y.clear();
		WORLD_REVEAL_TARGET_STATES.clear();
		WORLD_REVEAL_SAFE_TARGETS.clear();
		Map<Long, Integer> topSolidByColumn = new HashMap<>();
		Map<Long, Integer> topFilledByColumn = new HashMap<>();
		for (TerrainPlacement placement : WORLD_REVEAL_TERRAIN) {
			if (placement == null || placement.pos() == null || placement.state() == null || placement.state().isAir()) {
				continue;
			}
			BlockPos pos = placement.pos();
			BlockState state = placement.state();
			// Blocks above and below the sealed box are retained for the final
			// restoration, but are never part of the visible terrain reveal.
			if (!isInsideBoxVolume(outerGeometry, pos)) {
				continue;
			}
			WORLD_REVEAL_TARGET_STATES.put(pos.asLong(), state);
			boolean insideBarrier = pos.getX() >= barrierGeometry.minX + 1
					&& pos.getX() <= barrierGeometry.maxX - 1
					&& pos.getZ() >= barrierGeometry.minZ + 1
					&& pos.getZ() <= barrierGeometry.maxZ - 1;
			if (!insideBarrier) {
				continue;
			}
			long columnKey = surfaceColumnKey(pos.getX(), pos.getZ());
			topFilledByColumn.merge(columnKey, pos.getY(), Math::max);
			if (isWorldRevealCollisionState(state)) {
				WORLD_REVEAL_BARRIER_COLLISION.add(pos.immutable());
				topSolidByColumn.merge(columnKey, pos.getY(), Math::max);
			}
		}
		for (int x = barrierGeometry.minX + 1; x <= barrierGeometry.maxX - 1; x++) {
			for (int z = barrierGeometry.minZ + 1; z <= barrierGeometry.maxZ - 1; z++) {
				long columnKey = surfaceColumnKey(x, z);
				int topSolidY = topSolidByColumn.getOrDefault(columnKey, Integer.MIN_VALUE);
				int topFilledY = topFilledByColumn.getOrDefault(columnKey, Integer.MIN_VALUE);
				int fallbackSurface = barrierGeometry.floorY;
				int surfaceY = topSolidY != Integer.MIN_VALUE
						? topSolidY
						: (topFilledY != Integer.MIN_VALUE ? topFilledY : fallbackSurface);
				WORLD_REVEAL_SURFACE_Y.put(columnKey, surfaceY);
			}
		}
	}

	private static boolean shouldIgnoreSceneSnapshotState(BlockPos pos, BlockState state, BoxGeometry outerGeometry, BoxGeometry barrierGeometry) {
		if (pos == null || state == null) {
			return false;
		}
		// Startup lighting is scene infrastructure, never terrain to restore later.
		if (state.is(Blocks.LIGHT)) {
			return true;
		}
		boolean blackShell = isStartupShellBlock(state)
				&& outerGeometry != null
				&& (pos.getY() == outerGeometry.floorY
						|| pos.getY() == outerGeometry.roofY
						|| pos.getX() == outerGeometry.minX
						|| pos.getX() == outerGeometry.maxX
						|| pos.getZ() == outerGeometry.minZ
						|| pos.getZ() == outerGeometry.maxZ);
		if (blackShell) {
			return true;
		}
		if (!state.is(Blocks.BARRIER) || barrierGeometry == null) {
			return false;
		}
		boolean barrierFloor = pos.getY() <= barrierGeometry.floorY && pos.getY() >= barrierGeometry.floorY - (BARRIER_FLOOR_DEPTH - 1)
				&& pos.getX() >= barrierGeometry.minX && pos.getX() <= barrierGeometry.maxX
				&& pos.getZ() >= barrierGeometry.minZ && pos.getZ() <= barrierGeometry.maxZ;
		boolean barrierWallOrRoof = pos.getY() >= barrierGeometry.floorY + 1 && pos.getY() <= barrierGeometry.roofY
				&& ((pos.getX() == barrierGeometry.minX || pos.getX() == barrierGeometry.maxX
				|| pos.getZ() == barrierGeometry.minZ || pos.getZ() == barrierGeometry.maxZ)
				|| pos.getY() == barrierGeometry.roofY)
				&& pos.getX() >= barrierGeometry.minX && pos.getX() <= barrierGeometry.maxX
				&& pos.getZ() >= barrierGeometry.minZ && pos.getZ() <= barrierGeometry.maxZ;
		return barrierFloor || barrierWallOrRoof;
	}

	private static boolean isWorldRevealCollisionState(BlockState state) {
		return state != null && !state.isAir() && (state.blocksMotion() || !state.getFluidState().isEmpty());
	}

	private static boolean isInsideBoxVolume(BoxGeometry geometry, BlockPos pos) {
		return geometry != null && pos != null
				&& pos.getX() >= geometry.minX && pos.getX() <= geometry.maxX
				&& pos.getY() >= geometry.floorY && pos.getY() <= geometry.roofY
				&& pos.getZ() >= geometry.minZ && pos.getZ() <= geometry.maxZ;
	}

	/**
	 * The reveal plan is pure data. Build it from a frozen scene snapshot on a
	 * worker thread so reaching 100% can never monopolise the server tick.
	 */
	private static void beginWorldRevealPlanPreparation(ServerLevel level) {
		if (level == null || serverAnchor == null) {
			return;
		}
		BlockPos anchor = resolveServerAnchor(level);
		if (anchor == null) {
			return;
		}
		BoxGeometry outer = computeOuterBoxGeometry(anchor);
		Set<Long> structureFootprint = new HashSet<>();
		for (BlockPos pos : ServerStructureBreakSystem.getStructurePositions(anchor, serverStructureAxis)) {
			structureFootprint.add(pos.asLong());
		}
		List<TerrainPlacement> terrainSnapshot = WORLD_REVEAL_TERRAIN.stream()
				.filter(placement -> placement != null && isInsideBoxVolume(outer, placement.pos()))
				.toList();
		Map<Long, Integer> surfaceSnapshot = Map.copyOf(WORLD_REVEAL_SURFACE_Y);
		Set<Long> footprintSnapshot = Set.copyOf(structureFootprint);
		long randomSeed = level.getSeed() ^ anchor.asLong() ^ 0x6C6732777265616CL;
		resetWorldRevealPlanState();
		worldRevealPlanReady = false;
		worldRevealPlanFuture = CompletableFuture.supplyAsync(() -> buildWorldRevealPlan(
				buildWorldRevealPlanInput(anchor.immutable(), outer, randomSeed, footprintSnapshot, terrainSnapshot, surfaceSnapshot)
		));
	}

	private static WorldRevealPlanInput buildWorldRevealPlanInput(
			BlockPos anchor,
			BoxGeometry outer,
			long randomSeed,
			Set<Long> structureFootprint,
			List<TerrainPlacement> terrainSnapshot,
			Map<Long, Integer> surfaceSnapshot
	) {
		BoxGeometry barrier = computeBarrierGeometry(anchor);
		Set<Long> shellCandidates = new LinkedHashSet<>();
		for (BlockPos pos : collectBlackShellBlocks(outer)) {
			if (!structureFootprint.contains(pos.asLong())) {
				shellCandidates.add(pos.asLong());
			}
		}
		for (BlockPos pos : collectBarrierShellBlocks(barrier)) {
			if (!structureFootprint.contains(pos.asLong())) {
				shellCandidates.add(pos.asLong());
			}
		}
		Map<Long, BlockState> terrainTargets = new HashMap<>();
		for (TerrainPlacement placement : terrainSnapshot) {
			if (placement == null || placement.pos() == null || placement.state() == null || placement.state().isAir()) {
				continue;
			}
			terrainTargets.put(placement.pos().asLong(), placement.state());
		}
		Set<Long> requiredPositions = new LinkedHashSet<>(shellCandidates);
		for (Map.Entry<Long, BlockState> entry : terrainTargets.entrySet()) {
			if (!structureFootprint.contains(entry.getKey())) {
				requiredPositions.add(entry.getKey());
			}
		}
		return new WorldRevealPlanInput(
				anchor,
				outer,
				randomSeed,
				structureFootprint,
				Set.copyOf(shellCandidates),
				Map.copyOf(terrainTargets),
				surfaceSnapshot,
				Set.copyOf(requiredPositions)
		);
	}

	private static boolean installPreparedWorldRevealPlan() {
		if (worldRevealPlanReady) {
			return true;
		}
		if (worldRevealPlanFuture == null || !worldRevealPlanFuture.isDone()) {
			return false;
		}
		WorldRevealPlan plan;
		try {
			plan = worldRevealPlanFuture.join();
		} catch (RuntimeException exception) {
			Lg2.LOGGER.error("Failed to prepare season-start world reveal plan", exception);
			worldRevealPlanFuture = null;
			return false;
		}
		WORLD_REVEAL_EPISODES.clear();
		WORLD_REVEAL_REQUIRED_POSITIONS.clear();
		WORLD_REVEAL_DEFERRED_POSITIONS.clear();
		WORLD_REVEAL_REVEALED_POSITIONS.clear();
		if (plan != null) {
			WORLD_REVEAL_EPISODES.addAll(plan.episodes());
			WORLD_REVEAL_REQUIRED_POSITIONS.addAll(plan.requiredPositions());
		}
		if (WORLD_REVEAL_EPISODES.isEmpty() && !WORLD_REVEAL_REQUIRED_POSITIONS.isEmpty()) {
			WORLD_REVEAL_EPISODES.addAll(buildFallbackWorldRevealEpisodes(WORLD_REVEAL_REQUIRED_POSITIONS));
		}
		worldRevealPlanReady = true;
		stateDirty = true;
		return true;
	}

	private static WorldRevealPlan buildWorldRevealPlan(WorldRevealPlanInput input) {
		if (input == null || (input.shellCandidates().isEmpty() && input.terrainTargets().isEmpty())) {
			return WorldRevealPlan.empty();
		}
		Random random = new Random(input.randomSeed());
		List<BlockPos> terrainAnchors = collectWorldRevealTerrainAnchors(input, random, 56);
		if (terrainAnchors.isEmpty()) {
			terrainAnchors.add(input.anchor());
		}

		Map<Integer, LinkedHashSet<Long>> revealByRound = new TreeMap<>();
		Map<Integer, List<Vec3>> particlesByRound = new TreeMap<>();
		List<WorldRevealSeed> seeds = new ArrayList<>();
		for (int face = 0; face < 6; face++) {
			int primaryCrackCount = switch (face) {
				case 4 -> 6;
				case 5 -> 4;
				default -> 4;
			};
			for (int primaryIndex = 0; primaryIndex < primaryCrackCount; primaryIndex++) {
				BlockPos start = worldRevealShellPointForFace(input.outerGeometry(), face, random);
				BlockPos pivot = createWorldRevealFacePivot(input.outerGeometry(), face, random);
				for (int attempt = 0; attempt < 5 && start.distManhattan(pivot) < 6; attempt++) {
					pivot = createWorldRevealFacePivot(input.outerGeometry(), face, random);
				}
				BlockPos entry = createWorldRevealFaceEntry(input.outerGeometry(), pivot, face, random);
				BlockPos target = terrainAnchors.get(random.nextInt(terrainAnchors.size()));
				int startRound = face * 8 + primaryIndex * 2 + random.nextInt(4);
				WorldRevealFaceConstraint constraint = new WorldRevealFaceConstraint(
						faceAxis(face), facePlaneCenterCoordinate(input.outerGeometry(), face)
				);
				List<Vec3> mainPolyline = new ArrayList<>();
				appendWorldRevealLightningSegment(mainPolyline, blockCenter(start), blockCenter(pivot), input.outerGeometry(), constraint, random, 5, WORLD_REVEAL_PRIMARY_CRACK_DISPLACEMENT);
				appendWorldRevealLightningSegment(mainPolyline, mainPolyline.get(mainPolyline.size() - 1), blockCenter(entry), input.outerGeometry(), null, random, 4, WORLD_REVEAL_PRIMARY_CRACK_DISPLACEMENT * 0.52D);
				appendWorldRevealLightningSegment(mainPolyline, mainPolyline.get(mainPolyline.size() - 1), blockCenter(target), input.outerGeometry(), null, random, 5, WORLD_REVEAL_PRIMARY_CRACK_DISPLACEMENT * 0.70D);
				stampWorldRevealCrack(revealByRound, particlesByRound, seeds, mainPolyline, startRound, 54 + random.nextInt(24),
						(face == 4 ? 2.45D : 1.95D) + random.nextDouble() * 0.75D,
						input.shellCandidates(), input.terrainTargets(), input.structureFootprint());

				int branchCount = 2 + random.nextInt(3);
				for (int branchIndex = 0; branchIndex < branchCount && mainPolyline.size() >= 4; branchIndex++) {
					int minIndex = Math.max(1, mainPolyline.size() / 4);
					int maxIndex = Math.max(minIndex + 1, (mainPolyline.size() * 4) / 5);
					Vec3 branchSource = mainPolyline.get(nextIntInclusive(random, minIndex, maxIndex));
					BlockPos branchTarget = terrainAnchors.get(random.nextInt(terrainAnchors.size()));
					List<Vec3> branchPolyline = new ArrayList<>();
					appendWorldRevealLightningSegment(branchPolyline, branchSource, blockCenter(branchTarget), input.outerGeometry(), null, random, 4, WORLD_REVEAL_BRANCH_CRACK_DISPLACEMENT);
					stampWorldRevealCrack(revealByRound, particlesByRound, seeds, branchPolyline, startRound + 8 + random.nextInt(14),
							24 + random.nextInt(18), 1.55D + random.nextDouble() * 0.45D,
							input.shellCandidates(), input.terrainTargets(), input.structureFootprint());
				}
			}
		}
		backfillWorldRevealCoverage(
				revealByRound,
				particlesByRound,
				selectWorldRevealCoverageSeeds(seeds, random, 64),
				input.shellCandidates(),
				input.terrainTargets(),
				input.requiredPositions(),
				random,
				input.anchor(),
				input.structureFootprint()
		);
		return new WorldRevealPlan(input.requiredPositions(), splitWorldRevealEpisodes(revealByRound, particlesByRound));
	}

	private static List<WorldRevealSeed> selectWorldRevealCoverageSeeds(List<WorldRevealSeed> seeds, Random random, int limit) {
		if (seeds == null || seeds.isEmpty() || limit <= 0 || seeds.size() <= limit) {
			return seeds == null ? List.of() : new ArrayList<>(seeds);
		}
		List<WorldRevealSeed> selected = new ArrayList<>(limit);
		for (int index = 0; index < seeds.size(); index++) {
			WorldRevealSeed seed = seeds.get(index);
			if (index < limit) {
				selected.add(seed);
				continue;
			}
			int replacement = random.nextInt(index + 1);
			if (replacement < limit) {
				selected.set(replacement, seed);
			}
		}
		return selected;
	}

	private static List<WorldRevealEpisode> splitWorldRevealEpisodes(
			Map<Integer, LinkedHashSet<Long>> revealByRound,
			Map<Integer, List<Vec3>> particlesByRound
	) {
		List<WorldRevealEpisode> episodes = new ArrayList<>();
		for (Map.Entry<Integer, LinkedHashSet<Long>> entry : revealByRound.entrySet()) {
			LinkedHashSet<Long> roundPositions = entry.getValue();
			List<Vec3> crackPoints = particlesByRound.get(entry.getKey());
			if ((roundPositions == null || roundPositions.isEmpty()) && (crackPoints == null || crackPoints.isEmpty())) {
				continue;
			}
			List<BlockPos> revealPositions = new ArrayList<>(roundPositions == null ? 0 : roundPositions.size());
			if (roundPositions != null) {
				for (long key : roundPositions) {
					revealPositions.add(BlockPos.of(key));
				}
			}
			List<Vec3> particlePoints = crackPoints == null ? List.of() : new ArrayList<>(crackPoints);
			int chunkCount = Math.max(1, Math.max(
					(int) Math.ceil(revealPositions.size() / (double) WORLD_REVEAL_EPISODE_MAX_REVEAL_POSITIONS),
					(int) Math.ceil(particlePoints.size() / (double) WORLD_REVEAL_EPISODE_MAX_PARTICLE_POINTS)
			));
			for (int chunkIndex = 0; chunkIndex < chunkCount; chunkIndex++) {
				int revealStart = (int) Math.floor(revealPositions.size() * (double) chunkIndex / chunkCount);
				int revealEnd = (int) Math.floor(revealPositions.size() * (double) (chunkIndex + 1) / chunkCount);
				int particleStart = (int) Math.floor(particlePoints.size() * (double) chunkIndex / chunkCount);
				int particleEnd = (int) Math.floor(particlePoints.size() * (double) (chunkIndex + 1) / chunkCount);
				List<BlockPos> revealChunk = revealStart >= revealEnd ? List.of() : new ArrayList<>(revealPositions.subList(revealStart, revealEnd));
				List<Vec3> particleChunk = particleStart >= particleEnd ? List.of() : new ArrayList<>(particlePoints.subList(particleStart, particleEnd));
				if (!revealChunk.isEmpty() || !particleChunk.isEmpty()) {
					episodes.add(new WorldRevealEpisode(revealChunk, particleChunk));
				}
			}
		}
		return episodes;
	}

	private static List<WorldRevealEpisode> buildFallbackWorldRevealEpisodes(Set<Long> requiredPositions) {
		List<WorldRevealEpisode> episodes = new ArrayList<>();
		if (requiredPositions == null || requiredPositions.isEmpty()) {
			return episodes;
		}
		List<BlockPos> ordered = new ArrayList<>(requiredPositions.size());
		for (long key : requiredPositions) {
			ordered.add(BlockPos.of(key));
		}
		ordered.sort((left, right) -> {
			int byY = Integer.compare(left.getY(), right.getY());
			if (byY != 0) {
				return byY;
			}
			int byX = Integer.compare(left.getX(), right.getX());
			if (byX != 0) {
				return byX;
			}
			return Integer.compare(left.getZ(), right.getZ());
		});
		for (int start = 0; start < ordered.size(); start += WORLD_REVEAL_EPISODE_MAX_REVEAL_POSITIONS) {
			int end = Math.min(ordered.size(), start + WORLD_REVEAL_EPISODE_MAX_REVEAL_POSITIONS);
			List<BlockPos> revealChunk = new ArrayList<>(ordered.subList(start, end));
			List<Vec3> particleChunk = new ArrayList<>();
			for (int index = 0; index < revealChunk.size(); index += 4) {
				particleChunk.add(blockCenter(revealChunk.get(index)));
			}
			episodes.add(new WorldRevealEpisode(revealChunk, particleChunk));
		}
		return episodes;
	}


	private static Random randomForWorldReveal(ServerLevel level) {
		long seed = level == null || serverAnchor == null
				? 0x6C6732777265616CL
				: level.getSeed() ^ serverAnchor.asLong() ^ 0x6C6732777265616CL;
		return new Random(seed);
	}

	private static List<BlockPos> collectWorldRevealTerrainAnchors(WorldRevealPlanInput input, Random random, int limit) {
		List<BlockPos> anchors = new ArrayList<>();
		if (input == null || random == null || limit <= 0) {
			return anchors;
		}
		List<BlockPos> surfaceCandidates = new ArrayList<>();
		for (Map.Entry<Long, Integer> entry : input.surfaceY().entrySet()) {
			BlockPos column = BlockPos.of(entry.getKey());
			BlockPos anchor = resolveWorldRevealSurfaceAnchor(input, column.getX(), column.getZ(), entry.getValue());
			if (anchor != null) {
				surfaceCandidates.add(anchor);
			}
		}
		if (surfaceCandidates.isEmpty()) {
			return anchors;
		}
		int seen = 0;
		for (BlockPos candidate : surfaceCandidates) {
			if (candidate == null) {
				continue;
			}
			seen++;
			if (anchors.size() < limit) {
				anchors.add(candidate.immutable());
				continue;
			}
			int replacementIndex = random.nextInt(seen);
			if (replacementIndex < limit) {
				anchors.set(replacementIndex, candidate.immutable());
			}
		}
		return anchors;
	}

	private static BlockPos resolveWorldRevealSurfaceAnchor(WorldRevealPlanInput input, int x, int z, int surfaceY) {
		if (input == null) {
			return null;
		}
		BoxGeometry outerGeometry = input.outerGeometry();
		int minY = outerGeometry.floorY;
		int maxY = outerGeometry.roofY;
		for (int y = maxY; y >= minY; y--) {
			BlockPos pos = new BlockPos(x, y, z);
			BlockState state = input.terrainTargets().get(pos.asLong());
			if (state == null || state.isAir() || input.structureFootprint().contains(pos.asLong())) {
				continue;
			}
			if (hasWorldRevealExposedFace(input.terrainTargets(), pos)) {
				return pos.immutable();
			}
		}
		for (int y = Mth.clamp(surfaceY, minY, maxY); y >= minY; y--) {
			BlockPos pos = new BlockPos(x, y, z);
			BlockState state = input.terrainTargets().get(pos.asLong());
			if (state != null && !state.isAir() && !input.structureFootprint().contains(pos.asLong())) {
				return pos.immutable();
			}
		}
		return null;
	}

	private static boolean hasWorldRevealExposedFace(Map<Long, BlockState> terrainTargets, BlockPos pos) {
		if (terrainTargets == null || pos == null) {
			return false;
		}
		for (Direction direction : Direction.values()) {
			BlockPos adjacent = pos.relative(direction);
			BlockState adjacentState = terrainTargets.get(adjacent.asLong());
			if (adjacentState == null || adjacentState.isAir()) {
				return true;
			}
		}
		return false;
	}

	private static void stampWorldRevealCrack(
			Map<Integer, LinkedHashSet<Long>> revealByRound,
			Map<Integer, List<Vec3>> particlesByRound,
			List<WorldRevealSeed> seeds,
			List<Vec3> polyline,
			int startRound,
			int roundSpan,
			double baseFillRadius,
			Set<Long> shellCandidates,
			Map<Long, BlockState> terrainTargets,
			Set<Long> structureFootprint
	) {
		if (revealByRound == null
				|| particlesByRound == null
				|| seeds == null
				|| polyline == null
				|| polyline.size() < 2
				|| shellCandidates == null
				|| terrainTargets == null) {
			return;
		}
		List<Vec3> samples = sampleWorldRevealPolyline(polyline, WORLD_REVEAL_CRACK_SAMPLE_STEP);
		if (samples.isEmpty()) {
			return;
		}
		for (int sampleIndex = 0; sampleIndex < samples.size(); sampleIndex++) {
			Vec3 sample = samples.get(sampleIndex);
			double progress = samples.size() <= 1 ? 1.0D : (double) sampleIndex / (double) (samples.size() - 1);
			int round = startRound + (int) Math.floor(progress * roundSpan);
			particlesByRound.computeIfAbsent(round, ignored -> new ArrayList<>()).add(sample);
			BlockPos linePos = BlockPos.containing(sample);
			addWorldRevealRevealCell(revealByRound, round, linePos, shellCandidates, terrainTargets, structureFootprint);
			double crackRadius = progress < 0.05D
					? 0.0D
					: Math.min(2.15D, 0.42D + baseFillRadius * 0.34D + progress * 0.88D);
			double fillRadius = progress < 0.08D
					? 0.0D
					: Math.min(
							WORLD_REVEAL_MAX_TERRAIN_GROWTH_RADIUS,
							baseFillRadius * (0.45D + progress * 1.15D)
					);
			seeds.add(new WorldRevealSeed(sample, round, crackRadius, fillRadius));
			if (crackRadius > 0.01D || fillRadius > 0.01D) {
				growWorldRevealCellsAroundSample(revealByRound, particlesByRound, round, sample, crackRadius, fillRadius, shellCandidates, terrainTargets, structureFootprint);
			}
		}
	}

	private static void addWorldRevealRevealCell(
			Map<Integer, LinkedHashSet<Long>> revealByRound,
			int round,
			BlockPos pos,
			Set<Long> shellCandidates,
			Map<Long, BlockState> terrainTargets,
			Set<Long> structureFootprint
	) {
		if (revealByRound == null || pos == null || shellCandidates == null || terrainTargets == null
				|| (structureFootprint != null && structureFootprint.contains(pos.asLong()))) {
			return;
		}
		long key = pos.asLong();
		BlockState targetState = terrainTargets.get(key);
		if (!shellCandidates.contains(key) && (targetState == null || targetState.isAir())) {
			return;
		}
		revealByRound.computeIfAbsent(round, ignored -> new LinkedHashSet<>()).add(key);
	}

	private static void growWorldRevealCellsAroundSample(
			Map<Integer, LinkedHashSet<Long>> revealByRound,
			Map<Integer, List<Vec3>> particlesByRound,
			int round,
			Vec3 sample,
			double crackRadius,
			double fillRadius,
			Set<Long> shellCandidates,
			Map<Long, BlockState> terrainTargets,
			Set<Long> structureFootprint
	) {
		if (revealByRound == null
				|| particlesByRound == null
				|| sample == null
				|| terrainTargets == null
				|| (crackRadius <= 0.0D && fillRadius <= 0.0D)) {
			return;
		}
		BlockPos origin = BlockPos.containing(sample);
		double maxRadius = Math.max(crackRadius, fillRadius);
		int radiusBlocks = Mth.ceil(maxRadius + 0.35D);
		for (int dx = -radiusBlocks; dx <= radiusBlocks; dx++) {
			for (int dy = -radiusBlocks; dy <= radiusBlocks; dy++) {
				for (int dz = -radiusBlocks; dz <= radiusBlocks; dz++) {
					BlockPos candidate = origin.offset(dx, dy, dz);
					if (structureFootprint != null && structureFootprint.contains(candidate.asLong())) {
						continue;
					}
					long key = candidate.asLong();
					boolean shellCandidate = shellCandidates != null && shellCandidates.contains(key);
					BlockState targetState = terrainTargets.get(key);
					boolean terrainCandidate = targetState != null && !targetState.isAir();
					if (!shellCandidate && !terrainCandidate) {
						continue;
					}
					Vec3 candidateCenter = blockCenter(candidate);
					double distance = candidateCenter.distanceTo(sample);
					if (shellCandidate && crackRadius > 0.0D && distance <= crackRadius + 0.26D) {
						int delay = (int) Math.floor(distance * 1.55D + Math.abs(dy) * 0.18D);
						addWorldRevealRevealCell(revealByRound, round + delay, candidate, shellCandidates, terrainTargets, structureFootprint);
						if (((dx + dz) & 1) == 0) {
							particlesByRound.computeIfAbsent(round + delay, ignored -> new ArrayList<>()).add(candidateCenter);
						}
					}
					if (terrainCandidate && fillRadius > 0.0D && distance <= fillRadius + 0.42D) {
						int delay = (int) Math.floor(distance * 2.05D + Math.max(0, dy) * 0.25D);
						addWorldRevealRevealCell(revealByRound, round + delay, candidate, shellCandidates, terrainTargets, structureFootprint);
						if (((dx + dy + dz) & 1) == 0 && distance <= fillRadius * 0.72D) {
							particlesByRound.computeIfAbsent(round + delay, ignored -> new ArrayList<>()).add(candidateCenter);
						}
					}
				}
			}
		}
	}

	private static void backfillWorldRevealCoverage(
			Map<Integer, LinkedHashSet<Long>> revealByRound,
			Map<Integer, List<Vec3>> particlesByRound,
			List<WorldRevealSeed> seeds,
			Set<Long> shellCandidates,
			Map<Long, BlockState> terrainTargets,
			Set<Long> requiredPositions,
			Random random,
			BlockPos fallbackAnchor,
			Set<Long> structureFootprint
	) {
		if (revealByRound == null
				|| particlesByRound == null
				|| terrainTargets == null
				|| requiredPositions == null
				|| requiredPositions.isEmpty()) {
			return;
		}
		if (seeds == null || seeds.isEmpty()) {
			BlockPos anchor = fallbackAnchor == null ? BlockPos.ZERO : fallbackAnchor;
			seeds = new ArrayList<>(List.of(new WorldRevealSeed(blockCenter(anchor), 0, 0.9D, 1.2D)));
		}
		Set<Long> scheduled = new HashSet<>();
		for (LinkedHashSet<Long> positions : revealByRound.values()) {
			if (positions != null) {
				scheduled.addAll(positions);
			}
		}
		for (long key : requiredPositions) {
			if (scheduled.contains(key)) {
				continue;
			}
			BlockPos pos = BlockPos.of(key);
			if (structureFootprint != null && structureFootprint.contains(pos.asLong())) {
				continue;
			}
			BlockState targetState = terrainTargets.get(key);
			boolean terrain = targetState != null && !targetState.isAir();
			WorldRevealSeed seed = findNearestWorldRevealSeed(seeds, pos, terrain);
			if (seed == null) {
				continue;
			}
			Vec3 center = blockCenter(pos);
			double distance = center.distanceTo(seed.point());
			double influenceRadius = terrain
					? Math.max(0.68D, seed.terrainRadius())
					: Math.max(0.38D, seed.crackRadius());
			double uncovered = Math.max(0.0D, distance - influenceRadius);
			int jitter = random == null ? 0 : random.nextInt(terrain ? 4 : 3);
			int delay = terrain
					? (int) Math.floor(uncovered * 3.65D + Math.max(0.0D, center.y - seed.point().y) * 0.35D)
					: (int) Math.floor(uncovered * 2.75D + Math.abs(center.y - seed.point().y) * 0.12D);
			int round = seed.round() + jitter + delay;
			addWorldRevealRevealCell(revealByRound, round, pos, shellCandidates, terrainTargets, structureFootprint);
			scheduled.add(key);
			if (((pos.getX() * 31 + pos.getY() * 17 + pos.getZ() * 13) & (terrain ? 1 : 3)) == 0) {
				particlesByRound.computeIfAbsent(round, ignored -> new ArrayList<>()).add(center);
			}
		}
	}

	private static WorldRevealSeed findNearestWorldRevealSeed(List<WorldRevealSeed> seeds, BlockPos pos, boolean terrain) {
		if (seeds == null || seeds.isEmpty() || pos == null) {
			return null;
		}
		Vec3 center = blockCenter(pos);
		WorldRevealSeed best = null;
		double bestScore = Double.MAX_VALUE;
		for (WorldRevealSeed seed : seeds) {
			if (seed == null) {
				continue;
			}
			double effectiveRadius = terrain ? Math.max(0.68D, seed.terrainRadius()) : Math.max(0.38D, seed.crackRadius());
			double score = Math.max(0.0D, center.distanceTo(seed.point()) - effectiveRadius) + seed.round() * 0.038D;
			if (score < bestScore) {
				bestScore = score;
				best = seed;
			}
		}
		return best;
	}

	private static List<Vec3> sampleWorldRevealPolyline(List<Vec3> polyline, double stepSize) {
		List<Vec3> samples = new ArrayList<>();
		if (polyline == null || polyline.isEmpty()) {
			return samples;
		}
		samples.add(polyline.get(0));
		for (int index = 1; index < polyline.size(); index++) {
			Vec3 from = polyline.get(index - 1);
			Vec3 to = polyline.get(index);
			Vec3 delta = to.subtract(from);
			double length = delta.length();
			if (length <= 1.0E-6D) {
				continue;
			}
			int steps = Math.max(1, (int) Math.ceil(length / Math.max(0.1D, stepSize)));
			for (int step = 1; step <= steps; step++) {
				double progress = (double) step / (double) steps;
				samples.add(from.add(delta.scale(progress)));
			}
		}
		return samples;
	}

	private static void appendWorldRevealLightningSegment(
			List<Vec3> polyline,
			Vec3 start,
			Vec3 end,
			BoxGeometry geometry,
			WorldRevealFaceConstraint faceConstraint,
			Random random,
			int depth,
			double displacement
	) {
		if (polyline == null || start == null || end == null || geometry == null || random == null) {
			return;
		}
		List<Vec3> segment = new ArrayList<>();
		segment.add(clampWorldRevealPoint(start, geometry, faceConstraint));
		subdivideWorldRevealLightning(
				segment,
				clampWorldRevealPoint(start, geometry, faceConstraint),
				clampWorldRevealPoint(end, geometry, faceConstraint),
				geometry,
				faceConstraint,
				random,
				depth,
				displacement
		);
		if (polyline.isEmpty()) {
			polyline.addAll(segment);
			return;
		}
		for (int index = 1; index < segment.size(); index++) {
			Vec3 point = segment.get(index);
			if (polyline.get(polyline.size() - 1).distanceToSqr(point) > 1.0E-6D) {
				polyline.add(point);
			}
		}
	}

	private static void subdivideWorldRevealLightning(
			List<Vec3> out,
			Vec3 start,
			Vec3 end,
			BoxGeometry geometry,
			WorldRevealFaceConstraint faceConstraint,
			Random random,
			int depth,
			double displacement
	) {
		if (out == null || start == null || end == null || geometry == null || random == null) {
			return;
		}
		if (depth <= 0 || start.distanceTo(end) <= 1.15D) {
			out.add(clampWorldRevealPoint(end, geometry, faceConstraint));
			return;
		}
		Vec3 midpoint = start.add(end).scale(0.5D);
		Vec3 offsetDirection = faceConstraint == null
				? randomPerpendicularDirection(end.subtract(start), random)
				: randomFaceOffsetDirection(end.subtract(start), faceConstraint.axis(), random);
		double offsetScale = displacement * (0.6D + random.nextDouble() * 0.75D);
		Vec3 offsetMidpoint = midpoint.add(offsetDirection.scale(offsetScale));
		Vec3 clampedMidpoint = clampWorldRevealPoint(offsetMidpoint, geometry, faceConstraint);
		subdivideWorldRevealLightning(out, start, clampedMidpoint, geometry, faceConstraint, random, depth - 1, displacement * 0.56D);
		subdivideWorldRevealLightning(out, clampedMidpoint, end, geometry, faceConstraint, random, depth - 1, displacement * 0.56D);
	}

	private static Vec3 randomPerpendicularDirection(Vec3 segment, Random random) {
		Vec3 direction = normalizeOrFallback(segment, new Vec3(1.0D, 0.0D, 0.0D));
		Vec3 randomSeed = normalizeOrFallback(
				new Vec3(random.nextDouble() - 0.5D, random.nextDouble() - 0.5D, random.nextDouble() - 0.5D),
				new Vec3(0.0D, 1.0D, 0.0D)
		);
		Vec3 perpendicularA = direction.cross(randomSeed);
		if (perpendicularA.lengthSqr() <= 1.0E-6D) {
			perpendicularA = direction.cross(Math.abs(direction.y) < 0.9D ? new Vec3(0.0D, 1.0D, 0.0D) : new Vec3(1.0D, 0.0D, 0.0D));
		}
		perpendicularA = normalizeOrFallback(perpendicularA, new Vec3(0.0D, 1.0D, 0.0D));
		Vec3 perpendicularB = normalizeOrFallback(direction.cross(perpendicularA), new Vec3(0.0D, 0.0D, 1.0D));
		double angle = random.nextDouble() * (Math.PI * 2.0D);
		return normalizeOrFallback(
				perpendicularA.scale(Math.cos(angle)).add(perpendicularB.scale(Math.sin(angle))),
				perpendicularA
		);
	}

	private static Vec3 randomFaceOffsetDirection(Vec3 segment, Direction.Axis axis, Random random) {
		Vec3 planar = switch (axis) {
			case X -> new Vec3(0.0D, segment.y, segment.z);
			case Y -> new Vec3(segment.x, 0.0D, segment.z);
			case Z -> new Vec3(segment.x, segment.y, 0.0D);
		};
		Vec3 tangent = normalizeOrFallback(planar, fallbackPlanarDirection(axis));
		Vec3 perpendicular = switch (axis) {
			case X -> new Vec3(0.0D, -tangent.z, tangent.y);
			case Y -> new Vec3(-tangent.z, 0.0D, tangent.x);
			case Z -> new Vec3(-tangent.y, tangent.x, 0.0D);
		};
		perpendicular = normalizeOrFallback(perpendicular, fallbackPlanarPerpendicular(axis));
		double along = (random.nextDouble() - 0.5D) * 0.45D;
		double across = random.nextBoolean() ? 1.0D : -1.0D;
		return normalizeOrFallback(perpendicular.scale(across).add(tangent.scale(along)), perpendicular);
	}

	private static Vec3 fallbackPlanarDirection(Direction.Axis axis) {
		return switch (axis) {
			case X -> new Vec3(0.0D, 1.0D, 0.0D);
			case Y -> new Vec3(1.0D, 0.0D, 0.0D);
			case Z -> new Vec3(1.0D, 0.0D, 0.0D);
		};
	}

	private static Vec3 fallbackPlanarPerpendicular(Direction.Axis axis) {
		return switch (axis) {
			case X -> new Vec3(0.0D, 0.0D, 1.0D);
			case Y -> new Vec3(0.0D, 0.0D, 1.0D);
			case Z -> new Vec3(0.0D, 1.0D, 0.0D);
		};
	}

	private static Vec3 normalizeOrFallback(Vec3 vector, Vec3 fallback) {
		if (vector != null && vector.lengthSqr() > 1.0E-6D) {
			return vector.normalize();
		}
		return fallback == null || fallback.lengthSqr() <= 1.0E-6D ? Vec3.ZERO : fallback.normalize();
	}

	private static Vec3 clampWorldRevealPoint(Vec3 point, BoxGeometry geometry, WorldRevealFaceConstraint faceConstraint) {
		if (point == null || geometry == null) {
			return Vec3.ZERO;
		}
		double minX = geometry.minX + 0.15D;
		double maxX = geometry.maxX + 0.85D;
		double minY = geometry.floorY + 0.15D;
		double maxY = geometry.roofY + 0.85D;
		double minZ = geometry.minZ + 0.15D;
		double maxZ = geometry.maxZ + 0.85D;
		double x = Mth.clamp(point.x, minX, maxX);
		double y = Mth.clamp(point.y, minY, maxY);
		double z = Mth.clamp(point.z, minZ, maxZ);
		if (faceConstraint != null) {
			switch (faceConstraint.axis()) {
				case X -> x = faceConstraint.value();
				case Y -> y = faceConstraint.value();
				case Z -> z = faceConstraint.value();
			}
		}
		return new Vec3(x, y, z);
	}

	private static BlockPos clampToGeometry(BlockPos pos, BoxGeometry geometry) {
		if (pos == null || geometry == null) {
			return BlockPos.ZERO;
		}
		return new BlockPos(
				Mth.clamp(pos.getX(), geometry.minX, geometry.maxX),
				Mth.clamp(pos.getY(), geometry.floorY, geometry.roofY),
				Mth.clamp(pos.getZ(), geometry.minZ, geometry.maxZ)
		);
	}

	private static BlockPos createWorldRevealFacePivot(BoxGeometry geometry, int face, Random random) {
		if (geometry == null || random == null) {
			return BlockPos.ZERO;
		}
		int centerX = (geometry.minX + geometry.maxX) / 2;
		int centerY = (geometry.floorY + geometry.roofY) / 2;
		int centerZ = (geometry.minZ + geometry.maxZ) / 2;
		int spreadX = Math.max(3, (geometry.maxX - geometry.minX) / 3);
		int spreadY = Math.max(3, (geometry.roofY - geometry.floorY) / 3);
		int spreadZ = Math.max(3, (geometry.maxZ - geometry.minZ) / 3);
		return switch (face) {
			case 0 -> new BlockPos(
					geometry.minX,
					nextIntInclusive(random, centerY - spreadY, centerY + spreadY),
					nextIntInclusive(random, centerZ - spreadZ, centerZ + spreadZ)
			);
			case 1 -> new BlockPos(
					geometry.maxX,
					nextIntInclusive(random, centerY - spreadY, centerY + spreadY),
					nextIntInclusive(random, centerZ - spreadZ, centerZ + spreadZ)
			);
			case 2 -> new BlockPos(
					nextIntInclusive(random, centerX - spreadX, centerX + spreadX),
					nextIntInclusive(random, centerY - spreadY, centerY + spreadY),
					geometry.minZ
			);
			case 3 -> new BlockPos(
					nextIntInclusive(random, centerX - spreadX, centerX + spreadX),
					nextIntInclusive(random, centerY - spreadY, centerY + spreadY),
					geometry.maxZ
			);
			case 4 -> new BlockPos(
					nextIntInclusive(random, centerX - spreadX, centerX + spreadX),
					geometry.floorY,
					nextIntInclusive(random, centerZ - spreadZ, centerZ + spreadZ)
			);
			default -> new BlockPos(
					nextIntInclusive(random, centerX - spreadX, centerX + spreadX),
					geometry.roofY,
					nextIntInclusive(random, centerZ - spreadZ, centerZ + spreadZ)
			);
		};
	}

	private static BlockPos createWorldRevealFaceEntry(BoxGeometry geometry, BlockPos pivot, int face, Random random) {
		if (geometry == null || pivot == null) {
			return BlockPos.ZERO;
		}
		Direction inward = switch (face) {
			case 0 -> Direction.EAST;
			case 1 -> Direction.WEST;
			case 2 -> Direction.SOUTH;
			case 3 -> Direction.NORTH;
			case 4 -> Direction.UP;
			default -> Direction.DOWN;
		};
		int depth = face >= 4 ? 4 + (random == null ? 0 : random.nextInt(4)) : 5 + (random == null ? 0 : random.nextInt(6));
		BlockPos current = pivot;
		for (int step = 0; step < depth; step++) {
			current = clampToGeometry(current.relative(inward), geometry);
		}
		return current;
	}

	private static Direction.Axis faceAxis(int face) {
		return switch (face) {
			case 0, 1 -> Direction.Axis.X;
			case 2, 3 -> Direction.Axis.Z;
			default -> Direction.Axis.Y;
		};
	}

	private static double facePlaneCenterCoordinate(BoxGeometry geometry, int face) {
		return switch (face) {
			case 0 -> geometry.minX + 0.5D;
			case 1 -> geometry.maxX + 0.5D;
			case 2 -> geometry.minZ + 0.5D;
			case 3 -> geometry.maxZ + 0.5D;
			case 4 -> geometry.floorY + 0.5D;
			default -> geometry.roofY + 0.5D;
		};
	}

	private static Vec3 blockCenter(BlockPos pos) {
		return pos == null ? Vec3.ZERO : new Vec3(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
	}

	private static int nextIntInclusive(Random random, int min, int max) {
		if (random == null || max <= min) {
			return min;
		}
		return min + random.nextInt(max - min + 1);
	}

	private static BlockPos worldRevealShellPointForFace(BoxGeometry geometry, int face, Random random) {
		if (geometry == null || random == null) {
			return BlockPos.ZERO;
		}
		int x = nextIntInclusive(random, geometry.minX, geometry.maxX);
		int y = nextIntInclusive(random, geometry.floorY, geometry.roofY);
		int z = nextIntInclusive(random, geometry.minZ, geometry.maxZ);
		return switch (face) {
			case 0 -> new BlockPos(geometry.minX, y, z);
			case 1 -> new BlockPos(geometry.maxX, y, z);
			case 2 -> new BlockPos(x, y, geometry.minZ);
			case 3 -> new BlockPos(x, y, geometry.maxZ);
			case 4 -> new BlockPos(x, geometry.floorY, z);
			default -> new BlockPos(x, geometry.roofY, z);
		};
	}

	private static void tickWorldRevealCracking(MinecraftServer server, ServerLevel level, long nowTick) {
		if (server == null || level == null) {
			return;
		}
		// This runs only after the asynchronous reveal plan is installed.
		// Starting it here prevents the 30-second timeline from expiring while
		// there is still nothing available to animate.
		tickWorldRevealAudioPrelude(server, level, nowTick);
		if (worldRevealCrackStartTick == Long.MIN_VALUE || nowTick < worldRevealCrackStartTick) {
			return;
		}
		long elapsedTicks = nowTick - worldRevealCrackStartTick;
		if (WORLD_REVEAL_EPISODES.isEmpty()) {
			finalizeWorldRevealCracking(server, level);
			return;
		}
		int visibleThreshold = resolveWorldRevealVisibleEpisodeThreshold();
		int processedBursts = 0;
		while (processedBursts < WORLD_REVEAL_MAX_BURSTS_PER_TICK
				&& worldRevealBurstCursor < WORLD_REVEAL_CRACK_BURSTS.length
				&& elapsedTicks >= WORLD_REVEAL_CRACK_BURSTS[worldRevealBurstCursor].offsetTicks()) {
			WorldRevealBurst burst = WORLD_REVEAL_CRACK_BURSTS[worldRevealBurstCursor];
			int previousCursor = worldRevealVisibleEpisodeCursor;
			worldRevealBurstWeightProgress += burst.growthWeight();
			int targetCursor = resolveWorldRevealBurstTargetCursor(visibleThreshold, elapsedTicks, worldRevealBurstWeightProgress);
			if (targetCursor <= worldRevealVisibleEpisodeCursor && worldRevealVisibleEpisodeCursor < visibleThreshold) {
				targetCursor = worldRevealVisibleEpisodeCursor + 1;
			}
			while (worldRevealVisibleEpisodeCursor < targetCursor && worldRevealVisibleEpisodeCursor < visibleThreshold) {
				revealWorldRevealEpisode(level, WORLD_REVEAL_EPISODES.get(worldRevealVisibleEpisodeCursor));
				worldRevealVisibleEpisodeCursor++;
			}
			emitWorldRevealBurstParticles(level, previousCursor, worldRevealVisibleEpisodeCursor, burst.particleBudget(), burst.impactStrength());
			worldRevealBurstCursor++;
			processedBursts++;
			stateDirty = true;
		}
		if (elapsedTicks < WORLD_REVEAL_CRACKING_DURATION_TICKS) {
			return;
		}
		if (worldRevealVisibleEpisodeCursor > 0) {
			emitWorldRevealBurstParticles(level, Math.max(0, worldRevealVisibleEpisodeCursor - 2), worldRevealVisibleEpisodeCursor, 28, 1.0F);
		}
		if (elapsedTicks >= WORLD_REVEAL_CRACKING_DURATION_TICKS) {
			finalizeWorldRevealCracking(server, level);
		}
	}

	private static void tickWorldRevealDarknessPulse(MinecraftServer server, ServerLevel level, long nowTick) {
		if (server == null || level == null) {
			return;
		}
		if (worldRevealDarknessClearTick != Long.MIN_VALUE && nowTick >= worldRevealDarknessClearTick) {
			// This is the zero crossing of the vanilla curve, so clearing here cannot
			// flash a black frame or start the following pulse.
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				if (isSeasonStartEligiblePlayer(player) && player.level() == level) {
					player.removeEffect(MobEffects.DARKNESS);
				}
			}
			worldRevealDarknessClearTick = Long.MIN_VALUE;
			stateDirty = true;
		}
	}

	private static void startWorldRevealDarknessSequence(MinecraftServer server, ServerLevel level, long nowTick) {
		if (server == null || level == null || worldRevealDarknessPulseCount != 0) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (isSeasonStartEligiblePlayer(player) && player.level() == level) {
				// No icon or particles: this is a visual beat, not a gameplay debuff.
				player.addEffect(new MobEffectInstance(
						MobEffects.DARKNESS,
						(int) WORLD_REVEAL_DARKNESS_EFFECT_DURATION_TICKS,
						0,
						true,
						false,
						false
				));
			}
		}
		worldRevealDarknessPulseCount = 1;
		worldRevealCompletionTick = nowTick + WORLD_REVEAL_DARKNESS_FINAL_PEAK_OFFSET_TICKS;
		worldRevealDarknessClearTick = nowTick + WORLD_REVEAL_DARKNESS_CLEAR_OFFSET_TICKS;
		stateDirty = true;
	}

	private static void maybeStartWorldRevealMusic(ServerLevel level, long nowTick, long elapsedTicks) {
		if (level == null || worldRevealMusicEndTick != Long.MIN_VALUE) {
			return;
		}
		long musicDurationTicks = ServerStabilitySystem.getStartupFeedMusicDurationTicks();
		// The music is the final clock of the reveal: its last beat coincides with
		// the Darkness zero-crossing, rather than leaving the player in a silent
		// dark screen after Bitcoin Millionaire has already ended.
		long startOffset = Math.max(0L, WORLD_REVEAL_DARKNESS_CLEAR_OFFSET_TICKS - musicDurationTicks);
		if (elapsedTicks < startOffset) {
			return;
		}
		long actualDurationTicks = ServerStabilitySystem.playFeedMusicForStartup(level, resolveServerAnchor(level));
		worldRevealMusicEndTick = nowTick + Math.max(1L, actualDurationTicks);
		stateDirty = true;
	}

	private static void finalizeWorldRevealCracking(MinecraftServer server, ServerLevel level) {
		if (server == null || level == null) {
			return;
		}
		beginWorldRevealBlackoutFade(server, level, level.getGameTime());
	}

	private static int resolveWorldRevealVisibleEpisodeThreshold() {
		if (WORLD_REVEAL_EPISODES.isEmpty()) {
			return 0;
		}
		return Mth.clamp((int) Math.ceil(WORLD_REVEAL_EPISODES.size() * WORLD_REVEAL_VISIBLE_TARGET_PROGRESS), 1, WORLD_REVEAL_EPISODES.size());
	}

	private static int resolveWorldRevealBurstTargetCursor(int visibleThreshold, long elapsedTicks, float burstWeightProgress) {
		if (visibleThreshold <= 0) {
			return 0;
		}
		float timeRatio = WORLD_REVEAL_CRACKING_DURATION_TICKS <= 0L
				? 1.0F
				: Mth.clamp((float) elapsedTicks / (float) WORLD_REVEAL_CRACKING_DURATION_TICKS, 0.0F, 1.0F);
		float ratio = WORLD_REVEAL_TOTAL_BURST_WEIGHT <= 0.0F
				? timeRatio
				: Mth.clamp(burstWeightProgress / WORLD_REVEAL_TOTAL_BURST_WEIGHT, 0.0F, 1.0F);
		ratio = Mth.clamp(ratio * 0.28F + timeRatio * 0.72F, 0.0F, 1.0F);
		return Mth.clamp((int) Math.round(visibleThreshold * ratio), 0, visibleThreshold);
	}

	private static void revealWorldRevealEpisode(ServerLevel level, WorldRevealEpisode episode) {
		revealWorldRevealEpisode(level, episode, false);
	}

	private static void revealWorldRevealEpisode(ServerLevel level, WorldRevealEpisode episode, boolean ignoreProtection) {
		if (level == null || episode == null || episode.revealPositions().isEmpty()) {
			return;
		}
		for (BlockPos pos : episode.revealPositions()) {
			if (!revealWorldRevealPosition(level, pos, ignoreProtection) && !ignoreProtection && pos != null) {
				WORLD_REVEAL_DEFERRED_POSITIONS.add(pos.asLong());
			}
		}
	}

	private static boolean revealWorldRevealPosition(ServerLevel level, BlockPos pos, boolean ignoreProtection) {
		if (level == null || pos == null || isServerStructureFootprint(pos)) {
			return true;
		}
		long key = pos.asLong();
		if (WORLD_REVEAL_PLAYER_MINED_POSITIONS.contains(key)) {
			WORLD_REVEAL_REVEALED_POSITIONS.add(key);
			WORLD_REVEAL_DEFERRED_POSITIONS.remove(key);
			return true;
		}
		if (WORLD_REVEAL_REVEALED_POSITIONS.contains(key)) {
			WORLD_REVEAL_DEFERRED_POSITIONS.remove(key);
			return true;
		}
		if (!ignoreProtection && isProtectedDuringVisibleReveal(level, pos)) {
			return false;
		}
		BlockState targetState = WORLD_REVEAL_TARGET_STATES.get(key);
		BlockState currentState = level.getBlockState(pos);
		boolean shellBlock = isStartupShellBlock(currentState) || currentState.is(Blocks.BARRIER);
		if (targetState == null && !shellBlock) {
			WORLD_REVEAL_REVEALED_POSITIONS.add(key);
			WORLD_REVEAL_DEFERRED_POSITIONS.remove(key);
			return true;
		}
		if (shellBlock) {
			// The crack sheds the actual shell material rather than generic white steam.
			BlockState shellFragments = Blocks.BLACK_CONCRETE.defaultBlockState();
			emitWorldRevealBlockFragments(level, pos, shellFragments, 18, 0.24D);
		}
		if (targetState != null && !targetState.isAir()) {
			setSceneBlockSilently(level, pos, targetState);
			// Terrain grows through the fissure as its own material fragments.
			emitWorldRevealBlockFragments(level, pos, targetState, 20, 0.22D);
		} else if (shellBlock) {
			setSceneBlockSilently(level, pos, Blocks.AIR.defaultBlockState());
		}
		WORLD_REVEAL_REVEALED_POSITIONS.add(key);
		WORLD_REVEAL_DEFERRED_POSITIONS.remove(key);
		return true;
	}

	private static void emitWorldRevealBlockFragments(
			ServerLevel level,
			BlockPos pos,
			BlockState state,
			int count,
			double spread
	) {
		if (level == null || pos == null || state == null || state.isAir() || count <= 0) {
			return;
		}
		BlockParticleOption fragments = new BlockParticleOption(ParticleTypes.BLOCK, state);
		for (ServerPlayer player : level.players()) {
			if (!isSeasonStartEligiblePlayer(player)) {
				continue;
			}
			// The room is larger than the normal particle packet radius. Send the
			// material fragments directly so every participant sees nearby terrain grow.
			level.sendParticles(
					player,
					fragments,
					true,
					false,
					pos.getX() + 0.5D,
					pos.getY() + 0.5D,
					pos.getZ() + 0.5D,
					count,
					spread,
					spread,
					spread,
					0.04D
			);
		}
	}

	private static void revealWorldRevealEpisodeBatch(ServerLevel level, int maxEpisodes, boolean ignoreProtection) {
		if (level == null || maxEpisodes <= 0) {
			return;
		}
		int processed = 0;
		while (worldRevealVisibleEpisodeCursor < WORLD_REVEAL_EPISODES.size() && processed < maxEpisodes) {
			revealWorldRevealEpisode(level, WORLD_REVEAL_EPISODES.get(worldRevealVisibleEpisodeCursor), ignoreProtection);
			worldRevealVisibleEpisodeCursor++;
			processed++;
		}
	}

	private static void revealWorldRevealDeferredBatch(ServerLevel level, int maxPositions, boolean ignoreProtection) {
		if (level == null || maxPositions <= 0 || WORLD_REVEAL_DEFERRED_POSITIONS.isEmpty()) {
			return;
		}
		List<Long> retry = new ArrayList<>();
		Iterator<Long> iterator = WORLD_REVEAL_DEFERRED_POSITIONS.iterator();
		int processed = 0;
		while (iterator.hasNext() && processed < maxPositions) {
			long key = iterator.next();
			iterator.remove();
			if (!revealWorldRevealPosition(level, BlockPos.of(key), ignoreProtection)) {
				retry.add(key);
			}
			processed++;
		}
		WORLD_REVEAL_DEFERRED_POSITIONS.addAll(retry);
	}

	private static boolean isWorldRevealCoverageComplete() {
		return worldRevealVisibleEpisodeCursor >= WORLD_REVEAL_EPISODES.size()
				&& WORLD_REVEAL_DEFERRED_POSITIONS.isEmpty()
				&& WORLD_REVEAL_REVEALED_POSITIONS.containsAll(WORLD_REVEAL_REQUIRED_POSITIONS);
	}

	private static void emitWorldRevealBurstParticles(
			ServerLevel level,
			int fromEpisode,
			int toEpisode,
			int particleBudget,
			float impactStrength
	) {
		if (level == null || particleBudget <= 0 || WORLD_REVEAL_EPISODES.isEmpty()) {
			return;
		}
		int sampleStart = Mth.clamp(Math.max(0, fromEpisode - 1), 0, WORLD_REVEAL_EPISODES.size() - 1);
		int sampleEndExclusive = Mth.clamp(
				Math.max(sampleStart + 1, toEpisode + WORLD_REVEAL_CRACK_PARTICLE_LOOKAHEAD_EPISODES),
				sampleStart + 1,
				WORLD_REVEAL_EPISODES.size()
		);
		int window = sampleEndExclusive - sampleStart;
		for (int i = 0; i < particleBudget; i++) {
			int episodeIndex = sampleStart + Math.floorMod(i * 5 + worldRevealBurstCursor * 3, window);
			WorldRevealEpisode episode = WORLD_REVEAL_EPISODES.get(episodeIndex);
			if (episode == null || episode.crackPoints().isEmpty()) {
				continue;
			}
			List<Vec3> crackPoints = episode.crackPoints();
			Vec3 particleSpot = crackPoints.get(Math.floorMod(i * 11 + episodeIndex * 7 + toEpisode * 13, crackPoints.size()));
			level.sendParticles(WORLD_REVEAL_CRACK_CORE_PARTICLE, particleSpot.x, particleSpot.y, particleSpot.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
			if ((i & 1) == 0 || impactStrength >= 0.8F) {
				level.sendParticles(
						WORLD_REVEAL_CRACK_EDGE_PARTICLE,
						particleSpot.x,
						particleSpot.y,
						particleSpot.z,
						1,
						0.018D,
						0.018D,
						0.018D,
						0.0D
				);
			}
		}
	}

	private static void startWorldRevealEarthquakeSound(ServerLevel level) {
		if (level == null || serverAnchor == null) {
			return;
		}
		BoxGeometry outerGeometry = computeOuterBoxGeometry(resolveServerAnchor(level));
		for (ServerPlayer player : level.players()) {
			boolean participant = PLAYER_STATES.containsKey(player.getUUID());
			if (!isSeasonStartEligiblePlayer(player)
					|| player.connection == null
					|| (!participant && !isInsideFootprint(outerGeometry, player.blockPosition()))) {
				continue;
			}
			player.connection.send(new ClientboundStopSoundPacket(WORLD_REVEAL_EARTHQUAKE_SOUND_ID, SoundSource.AMBIENT));
			player.connection.send(
					new ClientboundSoundEntityPacket(
							WORLD_REVEAL_EARTHQUAKE_SOUND,
							SoundSource.AMBIENT,
							player,
							WORLD_REVEAL_EARTHQUAKE_VOLUME,
							1.0F,
							level.random.nextLong()
					)
			);
		}
	}

	private static void stopWorldRevealEarthquakeSound(ServerLevel level) {
		if (level == null) {
			return;
		}
		for (ServerPlayer player : level.players()) {
			if (!isSeasonStartEligiblePlayer(player) || player.connection == null) {
				continue;
			}
			player.connection.send(new ClientboundStopSoundPacket(WORLD_REVEAL_EARTHQUAKE_SOUND_ID, SoundSource.AMBIENT));
		}
	}

	private static boolean isProtectedDuringVisibleReveal(ServerLevel level, BlockPos pos) {
		if (level == null || pos == null || serverAnchor == null) {
			return false;
		}
		BoxGeometry outerGeometry = computeOuterBoxGeometry(resolveServerAnchor(level));
		for (ServerPlayer player : level.players()) {
			if (!isSeasonStartEligiblePlayer(player) || !isInsideFootprint(outerGeometry, player.blockPosition())) {
				continue;
			}
			BlockPos playerPos = player.blockPosition();
			if (Math.abs(pos.getX() - playerPos.getX()) <= WORLD_REVEAL_VISIBLE_PROTECTION_RADIUS
					&& Math.abs(pos.getZ() - playerPos.getZ()) <= WORLD_REVEAL_VISIBLE_PROTECTION_RADIUS
					&& pos.getY() >= playerPos.getY() - 1
					&& pos.getY() <= playerPos.getY() + WORLD_REVEAL_VISIBLE_PROTECTION_HEIGHT) {
				return true;
			}
		}
		return false;
	}

	private static void beginWorldRevealBlackoutFade(MinecraftServer server, ServerLevel level, long nowTick) {
		if (server == null || level == null || worldRevealPhase == WorldRevealPhase.BLACKOUT_FADE
				|| worldRevealPhase == WorldRevealPhase.RELOCATE
				|| worldRevealPhase == WorldRevealPhase.SETTLE) {
			return;
		}
		stopWorldRevealEarthquakeSound(level);
		worldRevealEarthquakeSoundStarted = false;
		worldRevealDarknessRepositioned = false;
		worldRevealPhase = WorldRevealPhase.BLACKOUT_FADE;
		worldRevealPhaseStartTick = nowTick;
		clearStartupWorldgenDisplay(level);
		stateDirty = true;
	}

	private static void tickWorldRevealBlackoutFade(MinecraftServer server, ServerLevel level, long nowTick) {
		if (server == null || level == null) {
			return;
		}
		// A recovery/restart can enter this phase between effect ticks. Retain the
		// same mathematical end condition instead of cutting a dark screen abruptly.
		tickWorldRevealDarknessPulse(server, level, nowTick);
		revealWorldRevealEpisodeBatch(level, WORLD_REVEAL_BLACKOUT_REVEAL_EPISODES_PER_TICK, false);
		revealWorldRevealDeferredBatch(level, WORLD_REVEAL_RELOCATE_DEFERRED_BATCH, false);
		if (nowTick - worldRevealPhaseStartTick < WORLD_REVEAL_BLACKOUT_DURATION_TICKS) {
			return;
		}
		beginWorldRevealSettlePhase(level, nowTick);
	}

	private static float computeWorldRevealTotalBurstWeight() {
		float total = 0.0F;
		for (WorldRevealBurst burst : WORLD_REVEAL_CRACK_BURSTS) {
			if (burst != null) {
				total += Math.max(0.0F, burst.growthWeight());
			}
		}
		return total;
	}

	private static WorldRevealBurst burst(int offsetTicks, float growthWeight, int particleBudget, float impactStrength) {
		return new WorldRevealBurst(offsetTicks, growthWeight, particleBudget, impactStrength);
	}

	private static void tickWorldRevealRelocate(MinecraftServer server, ServerLevel level, long nowTick) {
		beginWorldRevealSettlePhase(level, nowTick);
	}

	private static void beginWorldRevealSettlePhase(ServerLevel level, long nowTick) {
		if (level == null) {
			return;
		}
		revealWorldRevealDeferredBatch(level, WORLD_REVEAL_SETTLE_DEFERRED_BATCH, true);
		worldRevealBarriersPlaced = false;
		worldRevealPhase = WorldRevealPhase.SETTLE;
		worldRevealPhaseStartTick = nowTick;
		stateDirty = true;
	}

	private static void tickWorldRevealSettle(MinecraftServer server, ServerLevel level, long nowTick) {
		tickWorldRevealDarknessPulse(server, level, nowTick);
		revealWorldRevealEpisodeBatch(level, WORLD_REVEAL_SETTLE_REVEAL_EPISODES_PER_TICK, true);
		revealWorldRevealDeferredBatch(level, WORLD_REVEAL_SETTLE_DEFERRED_BATCH, true);
		if (nowTick - worldRevealPhaseStartTick < WORLD_REVEAL_SETTLE_MIN_TICKS) {
			return;
		}
		if (worldRevealMusicEndTick != Long.MIN_VALUE && nowTick < worldRevealMusicEndTick) {
			return;
		}
		if (!worldRevealGameplayReleased
				&& worldRevealDarknessPulseCount == 1
				&& worldRevealCompletionTick != Long.MIN_VALUE
				&& nowTick >= worldRevealCompletionTick) {
			// The last frame of the construction is intentionally hidden at the peak
			// of Darkness. When the effect fades, the player sees only the finished
			// world and its already-updated time of day.
			materializeWorldRevealTerrainInDarkness(level);
			releaseWorldRevealGameplay(server, level);
			return;
		}
		if (!isWorldRevealCoverageComplete()) {
			return;
		}
		if (!worldRevealGameplayReleased) {
			if (worldRevealDarknessPulseCount == 1
					&& worldRevealCompletionTick != Long.MIN_VALUE
					&& nowTick < worldRevealCompletionTick) {
				return;
			}
			releaseWorldRevealGameplay(server, level);
			return;
		}
		if (worldRevealDarknessClearTick != Long.MIN_VALUE) {
			return;
		}
		completeWorldReveal(server, level);
	}

	private static void teleportWorldRevealPlayersToSafeTargets(MinecraftServer server, ServerLevel level) {
		if (server == null || level == null || serverAnchor == null) {
			return;
		}
		BoxGeometry outerGeometry = computeOuterBoxGeometry(resolveServerAnchor(level));
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!isSeasonStartEligiblePlayer(player) || player.level() != level || !isInsideFootprint(outerGeometry, player.blockPosition())) {
				continue;
			}
			Vec3 target = WORLD_REVEAL_SAFE_TARGETS.get(player.getUUID());
			if (target == null) {
				continue;
			}
			teleportPlayer(player, level, target, player.getYRot());
			player.setDeltaMovement(Vec3.ZERO);
			player.fallDistance = 0.0F;
			player.hurtMarked = true;
		}
	}

	private static void refreshWorldRevealTargets(ServerLevel level) {
		if (level == null || serverAnchor == null) {
			return;
		}
		BoxGeometry barrierGeometry = computeBarrierGeometry(resolveServerAnchor(level));
		BoxGeometry outerGeometry = computeOuterBoxGeometry(resolveServerAnchor(level));
		WORLD_REVEAL_SAFE_TARGETS.entrySet().removeIf(entry -> level.getServer() == null
				|| level.getServer().getPlayerList().getPlayer(entry.getKey()) == null
				|| level.getServer().getPlayerList().getPlayer(entry.getKey()).level() != level);
		for (ServerPlayer player : level.players()) {
			if (!isSeasonStartEligiblePlayer(player) || !isInsideFootprint(outerGeometry, player.blockPosition())) {
				continue;
			}
			WORLD_REVEAL_SAFE_TARGETS.computeIfAbsent(player.getUUID(), ignored -> resolveNearestWorldRevealTarget(player, barrierGeometry));
		}
	}

	private static Vec3 resolveNearestWorldRevealTarget(ServerPlayer player, BoxGeometry barrierGeometry) {
		if (player == null || barrierGeometry == null) {
			return Vec3.ZERO;
		}
		int startX = Mth.clamp(player.blockPosition().getX(), barrierGeometry.minX + 1, barrierGeometry.maxX - 1);
		int startZ = Mth.clamp(player.blockPosition().getZ(), barrierGeometry.minZ + 1, barrierGeometry.maxZ - 1);
		double bestScore = Double.MAX_VALUE;
		Vec3 best = null;
		int maxRadius = Math.max(barrierGeometry.maxX - barrierGeometry.minX, barrierGeometry.maxZ - barrierGeometry.minZ);
		for (int radius = 0; radius <= maxRadius; radius++) {
			boolean foundAtRadius = false;
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					if (radius > 0 && Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
						continue;
					}
					int x = startX + dx;
					int z = startZ + dz;
					if (x < barrierGeometry.minX + 1 || x > barrierGeometry.maxX - 1 || z < barrierGeometry.minZ + 1 || z > barrierGeometry.maxZ - 1) {
						continue;
					}
					int surfaceY = WORLD_REVEAL_SURFACE_Y.getOrDefault(surfaceColumnKey(x, z), barrierGeometry.floorY);
					if (surfaceY + 2 >= barrierGeometry.roofY) {
						continue;
					}
					double score = dx * dx + dz * dz + Math.abs((surfaceY + WORLD_REVEAL_FINAL_TARGET_OFFSET) - player.getY()) * 0.2D;
					if (best == null || score < bestScore) {
						bestScore = score;
						best = new Vec3(x + 0.5D, surfaceY + WORLD_REVEAL_FINAL_TARGET_OFFSET, z + 0.5D);
						foundAtRadius = true;
					}
				}
			}
			if (foundAtRadius && best != null) {
				break;
			}
		}
		if (best != null) {
			return best;
		}
		return new Vec3(startX + 0.5D, barrierGeometry.floorY + WORLD_REVEAL_FINAL_TARGET_OFFSET, startZ + 0.5D);
	}

	private static void clearBarrierInteriorForWorldReveal(ServerLevel level, BoxGeometry geometry) {
		if (level == null || geometry == null) {
			return;
		}
		for (int x = geometry.minX + 1; x <= geometry.maxX - 1; x++) {
			for (int z = geometry.minZ + 1; z <= geometry.maxZ - 1; z++) {
				for (int y = geometry.floorY - (BARRIER_FLOOR_DEPTH - 1); y <= geometry.roofY - 1; y++) {
					BlockPos pos = new BlockPos(x, y, z);
					BlockState state = level.getBlockState(pos);
					if (state.is(Blocks.BARRIER) || state.is(Blocks.LIGHT)) {
						setSceneBlockSilently(level, pos, Blocks.AIR.defaultBlockState());
					}
				}
			}
		}
	}

	private static void materializeWorldRevealBarrierTerrain(ServerLevel level) {
		if (level == null) {
			return;
		}
		for (BlockPos pos : WORLD_REVEAL_BARRIER_COLLISION) {
			if (pos == null || isServerStructureFootprint(pos) || level.getBlockState(pos).is(ModBlocks.SERVER)) {
				continue;
			}
			setSceneBlockSilently(level, pos, Blocks.BARRIER.defaultBlockState());
		}
	}

	/**
	 * A restarted reveal resumes from the exact sidecar snapshot captured before
	 * the black cube existed. Only worlds made with older versions lack it; they
	 * retain the bounded base-terrain fallback as a last-resort migration path.
	 */
	private static void beginWorldRevealRecovery(MinecraftServer server) {
		ServerLevel level = server == null ? null : server.overworld();
		if (server == null || level == null || !worldRevealActive) {
			return;
		}
		BlockPos anchor = resolveServerAnchor(level);
		if (anchor == null) {
			return;
		}
		serverAnchor = anchor.immutable();
		worldRevealRecoveryPending = true;
		scenePrepared = false;
		worldRevealPlanFuture = null;
		worldRevealPlanReady = false;
		worldRevealPhase = WorldRevealPhase.SETTLE;
		worldRevealPhaseStartTick = Long.MIN_VALUE;
		worldRevealCrackStartTick = Long.MIN_VALUE;
		worldRevealCrackNotBeforeTick = Long.MIN_VALUE;
		worldRevealMusicEndTick = Long.MIN_VALUE;
		worldRevealDarknessClearTick = Long.MIN_VALUE;
		worldRevealCompletionTick = Long.MIN_VALUE;
		worldRevealDarknessPulseCount = 0;
		worldRevealGameplayReleased = false;
		worldRevealEarthquakeSoundStarted = false;
		worldRevealDarknessRepositioned = false;
		if (isWorldRevealSnapshotLoading() || hasWorldRevealSnapshot()) {
			stateDirty = true;
			return;
		}
		Lg2.LOGGER.warn("Season-start reveal has no exact terrain snapshot; falling back to legacy base-terrain recovery.");
		if (sceneBuildTask == null || !sceneBuildTask.anchor.equals(anchor)
				|| sceneBuildTask.mode != SceneBuildMode.WORLD_REVEAL_RECOVERY) {
			sceneBuildTask = createSceneBuildTask(level, anchor, SceneBuildMode.WORLD_REVEAL_RECOVERY);
		}
		stateDirty = true;
	}

	private static boolean tickWorldRevealRecovery(MinecraftServer server) {
		ServerLevel level = server == null ? null : server.overworld();
		if (server == null || level == null) {
			return false;
		}
		if (isWorldRevealSnapshotLoading()) {
			return false;
		}
		if (hasWorldRevealSnapshot()) {
			finishWorldRevealRecovery(level);
			return true;
		}
		if (sceneBuildTask == null) {
			beginWorldRevealRecovery(server);
		}
		return tickSceneBuildTask(server, level);
	}

	private static void finishWorldRevealRecovery(ServerLevel level) {
		if (level == null) {
			return;
		}
		worldRevealRecoveryPending = false;
		worldRevealPhase = WorldRevealPhase.SETTLE;
		worldRevealPhaseStartTick = level.getGameTime();
		worldRevealVisibleEpisodeCursor = 0;
		worldRevealBurstCursor = 0;
		worldRevealBurstWeightProgress = 0.0F;
		sceneBuildTask = null;
		beginWorldRevealPlanPreparation(level);
		stateDirty = true;
	}

	private static void forceCompleteWorldReveal(MinecraftServer server) {
		beginWorldRevealRecovery(server);
	}

	private static void completeWorldReveal(MinecraftServer server, ServerLevel level) {
		if (server == null || level == null) {
			return;
		}
		if (!worldRevealGameplayReleased) {
			releaseWorldRevealGameplay(server, level);
		}
		worldRevealActive = false;
		worldRevealRecoveryPending = false;
		worldRevealBarriersPlaced = false;
		worldRevealPhaseStartTick = Long.MIN_VALUE;
		worldRevealCrackStartTick = Long.MIN_VALUE;
		worldRevealCrackNotBeforeTick = Long.MIN_VALUE;
		worldRevealMusicEndTick = Long.MIN_VALUE;
		worldRevealDarknessClearTick = Long.MIN_VALUE;
		worldRevealCompletionTick = Long.MIN_VALUE;
		worldRevealDarknessPulseCount = 0;
		worldRevealGameplayReleased = false;
		worldRevealVisibleEpisodeCursor = 0;
		worldRevealBurstCursor = 0;
		worldRevealBurstWeightProgress = 0.0F;
		worldRevealEarthquakeSoundStarted = false;
		worldRevealDarknessRepositioned = false;
		worldRevealPhase = WorldRevealPhase.NONE;
		completed = true;
		shellDissolving = false;
		scenePrepared = false;
		stopWorldRevealEarthquakeSound(level);
		PLAYER_STATES.clear();
		SHARED_BITCOIN_POSITIONS.clear();
		WORLD_REVEAL_TERRAIN.clear();
		WORLD_REVEAL_BARRIER_COLLISION.clear();
		WORLD_REVEAL_EPISODES.clear();
		WORLD_REVEAL_ENTITY_SNAPSHOTS.clear();
		WORLD_REVEAL_SURFACE_Y.clear();
		WORLD_REVEAL_TARGET_STATES.clear();
		WORLD_REVEAL_BOUNDARY_TARGET_STATES.clear();
		WORLD_REVEAL_SAFE_TARGETS.clear();
		WORLD_REVEAL_REQUIRED_POSITIONS.clear();
		WORLD_REVEAL_DEFERRED_POSITIONS.clear();
		WORLD_REVEAL_REVEALED_POSITIONS.clear();
		WORLD_REVEAL_PLAYER_MINED_POSITIONS.clear();
		WORLD_REVEAL_PLAYER_PHYSICS_POSITIONS.clear();
		worldRevealPlanFuture = null;
		worldRevealPlanReady = false;
		deleteWorldRevealSnapshot(server);
		clearSharedLaunchBossBar();
		stateDirty = true;
		saveState(server);
	}

	private static void releaseWorldRevealGameplay(MinecraftServer server, ServerLevel level) {
		if (server == null || level == null || worldRevealGameplayReleased) {
			return;
		}
		clearStartupWorldgenDisplay(level);
		restorePostStartMorning(level);
		restorePristineWorldFreeze(server, level);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!isSeasonStartEligiblePlayer(player)) {
				continue;
			}
			// Keep the second Darkness pulse until its mathematical zero crossing.
			applyFreeState(player, false);
			player.fallDistance = 0.0F;
		}
		if (serverAnchor != null) {
			ServerBlock.ensureServerStructureDisplay(level, serverAnchor, serverStructureAxis);
		}
		restoreSceneEntities(level);
		sceneBoundaryPhysicsFrozen = false;
		worldRevealBarriersPlaced = false;
		completed = true;
		restoreSeasonStartDifficulty(server);
		worldRevealGameplayReleased = true;
		stateDirty = true;
	}

	private static void materializeCompletedWorldRevealTerrain(ServerLevel level) {
		BlockPos anchor = resolveServerAnchor(level);
		if (level == null || anchor == null) {
			return;
		}
		clearStartupWorldgenDisplay(level);
		removeSceneShellNow(level);
		for (TerrainPlacement placement : WORLD_REVEAL_TERRAIN) {
			if (placement == null || placement.pos() == null || placement.state() == null
					|| isServerStructureFootprint(placement.pos())
					|| WORLD_REVEAL_PLAYER_MINED_POSITIONS.contains(placement.pos().asLong())) {
				continue;
			}
			setSceneBlockSilently(level, placement.pos(), placement.state());
		}
		restoreWorldRevealBoundaryStates(level);
		refreshWorldRevealBoundaryPhysics(level, anchor);
		ServerBlock.ensureServerStructureDisplay(level, anchor, serverStructureAxis);
		sceneBoundaryPhysicsFrozen = false;
		scenePrepared = false;
	}

	private static void materializeWorldRevealTerrainInDarkness(ServerLevel level) {
		if (level == null) {
			return;
		}
		materializeCompletedWorldRevealTerrain(level);
		worldRevealVisibleEpisodeCursor = WORLD_REVEAL_EPISODES.size();
		WORLD_REVEAL_DEFERRED_POSITIONS.clear();
		WORLD_REVEAL_REVEALED_POSITIONS.addAll(WORLD_REVEAL_REQUIRED_POSITIONS);
	}

	private static void restoreWorldRevealBoundaryStates(ServerLevel level) {
		if (level == null || WORLD_REVEAL_BOUNDARY_TARGET_STATES.isEmpty()) {
			return;
		}
		for (Map.Entry<Long, BlockState> entry : WORLD_REVEAL_BOUNDARY_TARGET_STATES.entrySet()) {
			BlockState state = entry.getValue();
			if (state == null || state.isAir() || WORLD_REVEAL_PLAYER_MINED_POSITIONS.contains(entry.getKey())) {
				continue;
			}
			BlockPos pos = BlockPos.of(entry.getKey());
			if (isServerStructureFootprint(pos)) {
				continue;
			}
			setSceneBlockSilently(level, pos, state);
		}
	}

	private static void restoreSceneEntities(ServerLevel level) {
		if (level == null || WORLD_REVEAL_ENTITY_SNAPSHOTS.isEmpty()) {
			return;
		}
		for (CompoundTag tag : new ArrayList<>(WORLD_REVEAL_ENTITY_SNAPSHOTS)) {
			if (tag == null || tag.isEmpty()) {
				continue;
			}
			Entity restored = EntityType.loadEntityRecursive(tag.copy(), level, EntitySpawnReason.LOAD, entity -> entity);
			if (restored == null || !shouldSnapshotSceneEntity(restored)) {
				continue;
			}
			level.addFreshEntity(restored);
		}
	}

	private static void refreshWorldRevealBoundaryPhysics(ServerLevel level, BlockPos anchor) {
		if (level == null || anchor == null) {
			return;
		}
		BoxGeometry outerGeometry = computeOuterBoxGeometry(anchor);
		Set<BlockPos> refreshPositions = new LinkedHashSet<>();
		for (BlockPos pos : collectBlackShellBlocks(outerGeometry)) {
			if (pos == null) {
				continue;
			}
			refreshPositions.add(pos.immutable());
			for (Direction direction : Direction.values()) {
				for (int step = 1; step <= WORLD_REVEAL_BOUNDARY_RESTORE_RADIUS; step++) {
					refreshPositions.add(pos.relative(direction, step).immutable());
				}
			}
		}
		for (long key : WORLD_REVEAL_BOUNDARY_TARGET_STATES.keySet()) {
			BlockPos pos = BlockPos.of(key);
			refreshPositions.add(pos);
			for (Direction direction : Direction.values()) {
				refreshPositions.add(pos.relative(direction).immutable());
			}
		}
		for (BlockPos pos : refreshPositions) {
			refreshWorldRevealBoundaryState(level, pos);
		}
	}

	private static void refreshWorldRevealBoundaryState(ServerLevel level, BlockPos pos) {
		if (level == null || pos == null) {
			return;
		}
		BlockState state = level.getBlockState(pos);
		level.updateNeighborsAt(pos, state.getBlock());
		FluidState fluidState = state.getFluidState();
		if (!fluidState.isEmpty()) {
			level.scheduleTick(pos, fluidState.getType(), fluidState.getType().getTickDelay(level));
		}
		if (state.getBlock() instanceof FallingBlock) {
			level.scheduleTick(pos, state.getBlock(), 2);
		}
	}

	private static void restorePostStartMorning(ServerLevel level) {
		if (level == null) {
			return;
		}
		long currentDayTime = Math.max(0L, level.getDayTime());
		long dayBase = (currentDayTime / 24000L) * 24000L;
		long morning = dayBase + WORLD_REVEAL_POST_START_MORNING_TIME;
		// A sealed start scene is held exactly at 1000.  That is already the
		// intended morning of day zero, not the following day.
		if (currentDayTime > morning) {
			morning += 24000L;
		}
		level.setDayTime(morning);
		level.setWeatherParameters(12000, 0, false, false);
		level.setRainLevel(0.0F);
		level.setThunderLevel(0.0F);
	}

	/** Resets the player-visible calendar without resetting the server tick clock. */
	private static void resetSeasonStartClock(ServerLevel level) {
		if (level == null) {
			return;
		}
		level.setDayTime(WORLD_REVEAL_POST_START_MORNING_TIME);
		forceStartupClearWeather(level);
	}

	/**
	 * Keeps the pregenerated overworld pristine throughout the sealed intro and
	 * reveal. The animation still uses the server tick clock; only the vanilla
	 * rules that can mutate ordinary terrain are frozen and later restored.
	 */
	private static void enforcePristineWorldFreeze(ServerLevel level) {
		if (level == null || level.getServer() == null) {
			return;
		}
		GameRules rules = level.getGameRules();
		MinecraftServer server = level.getServer();
		if (pristineWorldFreeze == null) {
			pristineWorldFreeze = new PristineWorldFreezeState(
					rules.get(GameRules.ADVANCE_TIME),
					rules.get(GameRules.ADVANCE_WEATHER),
					rules.get(GameRules.SPAWN_MOBS),
					rules.get(GameRules.MOB_GRIEFING),
					rules.get(GameRules.RANDOM_TICK_SPEED),
					rules.get(GameRules.PROJECTILES_CAN_BREAK_BLOCKS),
					rules.get(GameRules.TNT_EXPLODES),
					rules.get(GameRules.SPREAD_VINES),
					rules.get(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER)
			);
			stateDirty = true;
		}
		if (rules.get(GameRules.ADVANCE_TIME)) rules.set(GameRules.ADVANCE_TIME, false, server);
		if (rules.get(GameRules.ADVANCE_WEATHER)) rules.set(GameRules.ADVANCE_WEATHER, false, server);
		if (rules.get(GameRules.SPAWN_MOBS)) rules.set(GameRules.SPAWN_MOBS, false, server);
		if (rules.get(GameRules.MOB_GRIEFING)) rules.set(GameRules.MOB_GRIEFING, false, server);
		if (rules.get(GameRules.RANDOM_TICK_SPEED) != 0) rules.set(GameRules.RANDOM_TICK_SPEED, 0, server);
		if (rules.get(GameRules.PROJECTILES_CAN_BREAK_BLOCKS)) rules.set(GameRules.PROJECTILES_CAN_BREAK_BLOCKS, false, server);
		if (rules.get(GameRules.TNT_EXPLODES)) rules.set(GameRules.TNT_EXPLODES, false, server);
		if (rules.get(GameRules.SPREAD_VINES)) rules.set(GameRules.SPREAD_VINES, false, server);
		if (rules.get(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER) != 0) {
			rules.set(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0, server);
		}
	}

	private static void restorePristineWorldFreeze(MinecraftServer server, ServerLevel level) {
		if (server == null || level == null || pristineWorldFreeze == null) {
			return;
		}
		GameRules rules = level.getGameRules();
		PristineWorldFreezeState saved = pristineWorldFreeze;
		rules.set(GameRules.ADVANCE_TIME, saved.advanceTime(), server);
		rules.set(GameRules.ADVANCE_WEATHER, saved.advanceWeather(), server);
		rules.set(GameRules.SPAWN_MOBS, saved.spawnMobs(), server);
		rules.set(GameRules.MOB_GRIEFING, saved.mobGriefing(), server);
		rules.set(GameRules.RANDOM_TICK_SPEED, saved.randomTickSpeed(), server);
		rules.set(GameRules.PROJECTILES_CAN_BREAK_BLOCKS, saved.projectilesCanBreakBlocks(), server);
		rules.set(GameRules.TNT_EXPLODES, saved.tntExplodes(), server);
		rules.set(GameRules.SPREAD_VINES, saved.spreadVines(), server);
		rules.set(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, saved.fireSpreadRadius(), server);
		pristineWorldFreeze = null;
		stateDirty = true;
	}

	/** Keeps the sealed start scene in a stable clear morning until the reveal ends. */
	private static void enforceStartupEnvironment(ServerLevel level) {
		if (level == null) {
			return;
		}
		enforcePristineWorldFreeze(level);
		long currentDayTime = Math.max(0L, level.getDayTime());
		long lockedMorning = (currentDayTime / 24000L) * 24000L + WORLD_REVEAL_POST_START_MORNING_TIME;
		if (level.getDayTime() != lockedMorning) {
			level.setDayTime(lockedMorning);
		}
		if (level.isRaining() || level.isThundering()
				|| level.getRainLevel(1.0F) > 0.0F || level.getThunderLevel(1.0F) > 0.0F) {
			forceStartupClearWeather(level);
		}
	}

	private static void forceStartupClearWeather(ServerLevel level) {
		if (level == null) {
			return;
		}
		level.setWeatherParameters(STARTUP_CLEAR_WEATHER_TICKS, 0, false, false);
		level.setRainLevel(0.0F);
		level.setThunderLevel(0.0F);
	}

	private static void ensureSceneBuilt(ServerLevel level) {
		if (level == null || scenePrepared || isWorldRevealSnapshotLoading()) {
			return;
		}
		BlockPos anchor = resolveServerAnchor(level);
		if (anchor == null) {
			return;
		}
		if (sceneBuildTask == null || !sceneBuildTask.anchor.equals(anchor)) {
			sceneBuildTask = createSceneBuildTask(level, anchor, SceneBuildMode.STARTUP);
		}
	}

	private static void protectPlayersDuringSceneBuild(ServerLevel level, Iterable<ServerPlayer> players) {
		if (level == null || players == null || serverAnchor == null) {
			return;
		}
		BoxGeometry outerGeometry = computeOuterBoxGeometry(resolveServerAnchor(level));
		for (ServerPlayer player : players) {
			if (!isSeasonStartEligiblePlayer(player)
					|| player.level() != level
					|| !isInsideFootprint(outerGeometry, player.blockPosition())
					|| player.isNoGravity()) {
				continue;
			}
			player.setNoGravity(true);
			player.setDeltaMovement(Vec3.ZERO);
			player.fallDistance = 0.0F;
			SCENE_BUILD_FLOATING_PLAYERS.add(player.getUUID());
		}
	}

	private static void releaseSceneBuildFlight(MinecraftServer server) {
		if (server == null || SCENE_BUILD_FLOATING_PLAYERS.isEmpty()) {
			return;
		}
		for (UUID playerId : new ArrayList<>(SCENE_BUILD_FLOATING_PLAYERS)) {
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			if (player != null) {
				player.setNoGravity(false);
				player.fallDistance = 0.0F;
			}
		}
		SCENE_BUILD_FLOATING_PLAYERS.clear();
	}

	private static SceneBuildTask createSceneBuildTask(ServerLevel level, BlockPos anchor, SceneBuildMode mode) {
		BoxGeometry outer = computeOuterBoxGeometry(anchor);
		BoxGeometry barrier = computeBarrierGeometry(anchor);
		BoxGeometry verticalBelow = new BoxGeometry(
				outer.minX, outer.maxX, outer.minZ, outer.maxZ,
				level.getMinY(), outer.floorY - 1
		);
		BoxGeometry verticalAbove = new BoxGeometry(
				outer.minX, outer.maxX, outer.minZ, outer.maxZ,
				outer.roofY + 1, level.getMaxY() - 1
		);
		BoxGeometry boundary = new BoxGeometry(
				outer.minX - WORLD_REVEAL_BOUNDARY_RESTORE_RADIUS,
				outer.maxX + WORLD_REVEAL_BOUNDARY_RESTORE_RADIUS,
				outer.minZ - WORLD_REVEAL_BOUNDARY_RESTORE_RADIUS,
				outer.maxZ + WORLD_REVEAL_BOUNDARY_RESTORE_RADIUS,
				outer.floorY - WORLD_REVEAL_BOUNDARY_RESTORE_RADIUS,
				outer.roofY + WORLD_REVEAL_BOUNDARY_RESTORE_RADIUS
		);
		int legacyFloorY = (anchor.getY() - 1) - BARRIER_FLOOR_DEPTH;
		int legacyRoofY = legacyFloorY + (Math.max(get().boxHalfWidth, get().boxHalfDepth) * 2 + 1) - 1;
		BoxGeometry stale = new BoxGeometry(
				Math.min(outer.minX, barrier.minX) - 1,
				Math.max(outer.maxX, barrier.maxX) + 1,
				Math.min(outer.minZ, barrier.minZ) - 1,
				Math.max(outer.maxZ, barrier.maxZ) + 1,
				Math.min(outer.floorY, Math.min(barrier.floorY - (BARRIER_FLOOR_DEPTH - 1), legacyFloorY)),
				Math.max(outer.roofY, Math.max(barrier.roofY, legacyRoofY)) + 1
		);
		Set<Long> structureFootprint = new HashSet<>();
		for (BlockPos pos : ServerStructureBreakSystem.getStructurePositions(anchor, serverStructureAxis)) {
			structureFootprint.add(pos.asLong());
		}
		boolean hadExistingShell = isSceneShellAlreadyBuilt(level);
		boolean reuseExistingShell = mode == SceneBuildMode.WORLD_REVEAL_RECOVERY
				|| (hadExistingShell && isCurrentSceneShellIntact(level, outer, barrier));
		boolean reusePersistedSnapshot = hasWorldRevealSnapshot();
		boolean captureRestorationData = mode == SceneBuildMode.STARTUP && !reuseExistingShell && !reusePersistedSnapshot;
		if (captureRestorationData) {
			WORLD_REVEAL_TERRAIN.clear();
			WORLD_REVEAL_BARRIER_COLLISION.clear();
			WORLD_REVEAL_SURFACE_Y.clear();
			WORLD_REVEAL_TARGET_STATES.clear();
			WORLD_REVEAL_BOUNDARY_TARGET_STATES.clear();
			WORLD_REVEAL_SAFE_TARGETS.clear();
		}
		if (captureRestorationData || (reusePersistedSnapshot && !reuseExistingShell)) {
			protectPlayersDuringSceneBuild(level, level.players());
		}
		return new SceneBuildTask(
				anchor.immutable(), outer, barrier, boundary, stale, verticalBelow, verticalAbove,
				mode, mode == SceneBuildMode.WORLD_REVEAL_RECOVERY || hadExistingShell,
				reuseExistingShell, captureRestorationData, reusePersistedSnapshot, structureFootprint
		);
	}

	private static boolean tickSceneBuild(MinecraftServer server, ServerLevel level) {
		if (scenePrepared) {
			return true;
		}
		ensureSceneBuilt(level);
		return tickSceneBuildTask(server, level);
	}

	private static boolean tickSceneBuildTask(MinecraftServer server, ServerLevel level) {
		SceneBuildTask task = sceneBuildTask;
		if (task == null) {
			return false;
		}
		if (task.phase == SceneBuildPhase.SNAPSHOT_PERSIST && !tickSceneSnapshotPersistence(server, task)) {
			return false;
		}
		SceneBuildPhase budgetPhase = task.phase;
		int remainingBudget = switch (budgetPhase) {
			case SNAPSHOT -> SCENE_SNAPSHOT_BATCH_BLOCKS;
			case VERTICAL_SNAPSHOT_BELOW, VERTICAL_SNAPSHOT_ABOVE,
					VERTICAL_CLEAR_BELOW, VERTICAL_CLEAR_ABOVE -> VERTICAL_SCENE_BATCH_BLOCKS;
			default -> SCENE_BUILD_BATCH_BLOCKS;
		};
		while (remainingBudget > 0 && task.phase == budgetPhase && task.phase != SceneBuildPhase.FINALIZE) {
			long total = resolveSceneBuildPhaseTotal(level, task);
			if (task.cursor >= total) {
				advanceSceneBuildPhase(server, task);
				continue;
			}
			BlockPos pos = task.phase == SceneBuildPhase.BUILD_BARRIER
					? task.barrierShellBlocks.get((int) task.cursor++)
					: task.phase == SceneBuildPhase.BUILD_LIGHTS
							? task.startupLightBlocks.get((int) task.cursor++)
							: task.phase == SceneBuildPhase.ENTITY_SNAPSHOT
									? null
									: positionAtColumnMajor(task.currentGeometry(), task.cursor++);
			switch (task.phase) {
				case BOUNDARY_SNAPSHOT -> captureBoundaryRestoreSnapshotCell(level, task, pos);
				case ENTITY_SNAPSHOT -> captureSceneEntitySnapshotCell(level, task.sceneEntityCandidates.get((int) task.cursor++));
				case SNAPSHOT -> captureSceneSnapshotCell(level, task, pos);
				case VERTICAL_SNAPSHOT_BELOW, VERTICAL_SNAPSHOT_ABOVE -> captureVerticalSceneSnapshotCell(level, task, pos);
				case VERTICAL_CLEAR_BELOW, VERTICAL_CLEAR_ABOVE -> clearVerticalSceneBlock(level, pos);
				case CLEAR_STALE -> clearStaleSceneBlock(level, pos);
				case BUILD_OUTER -> buildOuterSceneBlock(level, task, pos);
				case BUILD_BARRIER -> buildBarrierSceneBlock(level, task, pos);
				case BUILD_LIGHTS -> buildStartupLightBlock(level, task, pos);
				default -> {
				}
			}
			remainingBudget--;
		}
		if (task.phase != SceneBuildPhase.FINALIZE) {
			return false;
		}
		finishSceneBuild(server, level, task);
		return true;
	}

	private static long resolveSceneBuildPhaseTotal(ServerLevel level, SceneBuildTask task) {
		return switch (task.phase) {
			case BUILD_BARRIER -> task.barrierShellBlocks.size();
			case BUILD_LIGHTS -> task.startupLightBlocks.size();
			case ENTITY_SNAPSHOT -> {
				if (task.sceneEntityCandidates == null) {
					task.sceneEntityCandidates = collectSceneEntityCandidates(level, task.outer);
				}
				yield task.sceneEntityCandidates.size();
			}
			default -> volumeOf(task.currentGeometry());
		};
	}

	private static void captureSceneSnapshotCell(ServerLevel level, SceneBuildTask task, BlockPos pos) {
		if (task.structureFootprint.contains(pos.asLong())) {
			return;
		}
		BlockState state;
		if (task.generatorFallback) {
			if (task.noiseColumn == null || task.noiseColumnX != pos.getX() || task.noiseColumnZ != pos.getZ()) {
				task.noiseColumn = level.getChunkSource().getGenerator().getBaseColumn(
						pos.getX(), pos.getZ(), level, level.getChunkSource().randomState()
				);
				task.noiseColumnX = pos.getX();
				task.noiseColumnZ = pos.getZ();
			}
			state = task.noiseColumn.getBlock(pos.getY());
		} else {
			state = level.getBlockState(pos);
		}
		if (shouldIgnoreSceneSnapshotState(pos, state, task.outer, task.barrier) || state == null || state.isAir()) {
			return;
		}
		WORLD_REVEAL_TERRAIN.add(new TerrainPlacement(pos.immutable(), state));
		if (task.snapshotAccumulator != null) {
			task.snapshotAccumulator.addTerrain(pos, state);
		}
		WORLD_REVEAL_TARGET_STATES.put(pos.asLong(), state);
		if (!isInsideBarrierInterior(task.barrier, pos)) {
			return;
		}
		long columnKey = surfaceColumnKey(pos.getX(), pos.getZ());
		WORLD_REVEAL_SURFACE_Y.put(columnKey, pos.getY());
		if (isWorldRevealCollisionState(state)) {
			WORLD_REVEAL_BARRIER_COLLISION.add(pos.immutable());
			task.topSolidSurfaceY.put(columnKey, pos.getY());
		}
	}

	private static void captureVerticalSceneSnapshotCell(ServerLevel level, SceneBuildTask task, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (state == null || state.isAir()) {
			return;
		}
		WORLD_REVEAL_TERRAIN.add(new TerrainPlacement(pos.immutable(), state));
		if (task.snapshotAccumulator != null) {
			task.snapshotAccumulator.addTerrain(pos, state);
		}
	}

	private static void captureBoundaryRestoreSnapshotCell(ServerLevel level, SceneBuildTask task, BlockPos pos) {
		if (level == null || task == null || pos == null
				|| (pos.getX() >= task.outer.minX && pos.getX() <= task.outer.maxX
				&& pos.getY() >= task.outer.floorY && pos.getY() <= task.outer.roofY
				&& pos.getZ() >= task.outer.minZ && pos.getZ() <= task.outer.maxZ)) {
			return;
		}
		BlockState state = level.getBlockState(pos);
		if (state != null && !state.isAir()) {
			WORLD_REVEAL_BOUNDARY_TARGET_STATES.put(pos.asLong(), state);
			if (task.snapshotAccumulator != null) {
				task.snapshotAccumulator.addBoundary(pos, state);
			}
		}
	}

	private static List<Entity> collectSceneEntityCandidates(ServerLevel level, BoxGeometry outerGeometry) {
		if (level == null || outerGeometry == null) {
			return List.of();
		}
		AABB sceneBounds = new AABB(
				outerGeometry.minX,
				outerGeometry.floorY,
				outerGeometry.minZ,
				outerGeometry.maxX + 1.0D,
				outerGeometry.roofY + 1.0D,
				outerGeometry.maxZ + 1.0D
		);
		return new ArrayList<>(level.getEntities((Entity) null, sceneBounds, SeasonStartSystem::shouldSnapshotSceneEntity));
	}

	private static void captureSceneEntitySnapshotCell(ServerLevel level, Entity entity) {
		if (level == null || entity == null || entity.isRemoved() || !shouldSnapshotSceneEntity(entity)) {
			return;
		}
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
		if (!entity.saveAsPassenger(output)) {
			return;
		}
		CompoundTag tag = output.buildResult();
		if (tag == null || tag.isEmpty()) {
			return;
		}
		WORLD_REVEAL_ENTITY_SNAPSHOTS.add(tag.copy());
		entity.discard();
	}

	private static void clearStaleSceneBlock(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (isStartupShellBlock(state) || state.is(Blocks.BARRIER) || state.is(Blocks.LIGHT)) {
			setSceneBlockSilently(level, pos, Blocks.AIR.defaultBlockState());
		}
	}

	private static void clearVerticalSceneBlock(ServerLevel level, BlockPos pos) {
		if (!level.getBlockState(pos).isAir()) {
			setSceneBlockSilently(level, pos, Blocks.AIR.defaultBlockState());
		}
	}

	private static void buildOuterSceneBlock(ServerLevel level, SceneBuildTask task, BlockPos pos) {
		boolean outerShell = pos.getY() == task.outer.floorY
				|| pos.getY() == task.outer.roofY
				|| pos.getX() == task.outer.minX
				|| pos.getX() == task.outer.maxX
				|| pos.getZ() == task.outer.minZ
				|| pos.getZ() == task.outer.maxZ;
		if (outerShell) {
			setSceneBlockSilently(level, pos, ModBlocks.STARTUP_VOID.defaultBlockState());
			return;
		}
		if (!task.structureFootprint.contains(pos.asLong()) && !level.getBlockState(pos).isAir()) {
			setSceneBlockSilently(level, pos, Blocks.AIR.defaultBlockState());
		}
	}

	private static void buildBarrierSceneBlock(ServerLevel level, SceneBuildTask task, BlockPos pos) {
		setSceneBlockSilently(level, pos, Blocks.BARRIER.defaultBlockState());
	}

	private static void buildStartupLightBlock(ServerLevel level, SceneBuildTask task, BlockPos pos) {
		if (task.structureFootprint.contains(pos.asLong())) {
			return;
		}
		BlockState state = level.getBlockState(pos);
		if (state.isAir() || state.is(Blocks.LIGHT)) {
			setSceneBlockSilently(level, pos, STARTUP_LIGHT_STATE);
		}
	}

	private static boolean tickSceneSnapshotPersistence(MinecraftServer server, SceneBuildTask task) {
		if (task.snapshotWriteFuture == null) {
			writeWorldRevealSnapshotAsync(server, task);
			if (task.snapshotWriteFuture == null) {
				Lg2.LOGGER.error("Season-start scene snapshot could not be scheduled; keeping the world untouched.");
				return false;
			}
		}
		if (!task.snapshotWriteFuture.isDone()) {
			return false;
		}
		try {
			task.snapshotWriteFuture.join();
		} catch (RuntimeException exception) {
			Lg2.LOGGER.error("Season-start scene snapshot write failed; retrying before the scene can modify terrain.", exception);
			task.snapshotWriteFuture = null;
			return false;
		}
		task.snapshotWriteFuture = null;
		Lg2.LOGGER.info("Saved exact season-start terrain snapshot ({} blocks, {} boundary blocks).",
				task.snapshotAccumulator.terrain.size(), task.snapshotAccumulator.boundary.size());
		task.phase = task.mode == SceneBuildMode.WORLD_REVEAL_RECOVERY
				? SceneBuildPhase.FINALIZE
				: task.reuseExistingShell ? SceneBuildPhase.BUILD_LIGHTS : SceneBuildPhase.VERTICAL_CLEAR_BELOW;
		task.cursor = 0L;
		return true;
	}

	private static void advanceSceneBuildPhase(MinecraftServer server, SceneBuildTask task) {
		if (task.phase == SceneBuildPhase.VERTICAL_SNAPSHOT_ABOVE) {
			finalizeSceneSnapshot(task);
			if (task.snapshotAccumulator != null) {
				writeWorldRevealSnapshotAsync(server, task);
				task.phase = SceneBuildPhase.SNAPSHOT_PERSIST;
				task.cursor = 0L;
				return;
			}
		}
		task.phase = switch (task.phase) {
			case BOUNDARY_SNAPSHOT -> task.captureEntities ? SceneBuildPhase.ENTITY_SNAPSHOT : SceneBuildPhase.SNAPSHOT;
			case ENTITY_SNAPSHOT -> SceneBuildPhase.SNAPSHOT;
			case SNAPSHOT -> task.snapshotAccumulator != null ? SceneBuildPhase.VERTICAL_SNAPSHOT_BELOW
					: task.mode == SceneBuildMode.WORLD_REVEAL_RECOVERY ? SceneBuildPhase.FINALIZE
					: task.reuseExistingShell ? SceneBuildPhase.BUILD_LIGHTS
					: task.generatorFallback ? SceneBuildPhase.CLEAR_STALE : SceneBuildPhase.BUILD_OUTER;
			case VERTICAL_SNAPSHOT_BELOW -> SceneBuildPhase.VERTICAL_SNAPSHOT_ABOVE;
			case VERTICAL_SNAPSHOT_ABOVE -> task.mode == SceneBuildMode.WORLD_REVEAL_RECOVERY ? SceneBuildPhase.FINALIZE
					: task.reuseExistingShell ? SceneBuildPhase.BUILD_LIGHTS : SceneBuildPhase.VERTICAL_CLEAR_BELOW;
			case SNAPSHOT_PERSIST -> task.mode == SceneBuildMode.WORLD_REVEAL_RECOVERY ? SceneBuildPhase.FINALIZE
					: task.reuseExistingShell ? SceneBuildPhase.BUILD_LIGHTS : SceneBuildPhase.VERTICAL_CLEAR_BELOW;
			case VERTICAL_CLEAR_BELOW -> SceneBuildPhase.VERTICAL_CLEAR_ABOVE;
			case VERTICAL_CLEAR_ABOVE -> SceneBuildPhase.BUILD_OUTER;
			case CLEAR_STALE -> SceneBuildPhase.BUILD_OUTER;
			case BUILD_OUTER -> SceneBuildPhase.BUILD_BARRIER;
			case BUILD_BARRIER -> SceneBuildPhase.BUILD_LIGHTS;
			case BUILD_LIGHTS, FINALIZE -> SceneBuildPhase.FINALIZE;
		};
		task.cursor = 0L;
	}

	private static void finalizeSceneSnapshot(SceneBuildTask task) {
		for (int x = task.barrier.minX + 1; x <= task.barrier.maxX - 1; x++) {
			for (int z = task.barrier.minZ + 1; z <= task.barrier.maxZ - 1; z++) {
				long columnKey = surfaceColumnKey(x, z);
				int topSolidY = task.topSolidSurfaceY.getOrDefault(columnKey, Integer.MIN_VALUE);
				int topFilledY = WORLD_REVEAL_SURFACE_Y.getOrDefault(columnKey, Integer.MIN_VALUE);
				int surfaceY = topSolidY != Integer.MIN_VALUE
						? topSolidY
						: (topFilledY != Integer.MIN_VALUE ? topFilledY : task.barrier.floorY);
				WORLD_REVEAL_SURFACE_Y.put(columnKey, surfaceY);
			}
		}
	}

	private static void finishSceneBuild(MinecraftServer server, ServerLevel level, SceneBuildTask task) {
		if (server == null || level == null || task == null) {
			return;
		}
		if (task.mode == SceneBuildMode.WORLD_REVEAL_RECOVERY) {
			finishWorldRevealRecovery(level);
			return;
		}
		if (!isServerStructurePresent(level, task.anchor)) {
			ServerBlock.placeServerStructure(level, task.anchor, Direction.NORTH);
		}
		ServerBlock.ensureServerStructureDisplay(level, task.anchor, serverStructureAxis);
		beginWorldRevealPlanPreparation(level);
		ensureStartupWorldgenDisplay(level);
		clearSceneMobs(level);
		clearSceneExperienceOrbs(level);
		scenePrepared = true;
		sceneBuildTask = null;
		releaseSceneBuildFlight(server);

		clearSharedBitcoins(level, true);
		for (Map.Entry<UUID, PlayerSceneState> entry : PLAYER_STATES.entrySet()) {
			PlayerSceneState state = entry.getValue();
			if (state == null) {
				continue;
			}
			SlotDefinition slot = resolveSlotDefinition(task.barrier, state.slotIndex);
			if (slot != null) {
				clearIntroOre(level, slot);
			}
		}
		ServerRaceSystem.beginSeasonStartRaces(server, get().startupRaceId);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (isSeasonStartEligiblePlayer(player)) {
				assignOrRestorePlayer(server, player, true);
			}
		}
		if (sharedLaunchCollectedBitcoins < sharedLaunchRequiredBitcoins) {
			ensureSharedBitcoinPopulation(level);
		}
		syncPrivatePlayerProfiles(server);
		stateDirty = true;
	}

	private static long volumeOf(BoxGeometry geometry) {
		return (long) (geometry.maxX - geometry.minX + 1)
				* (long) (geometry.maxZ - geometry.minZ + 1)
				* (long) (geometry.roofY - geometry.floorY + 1);
	}

	private static BlockPos positionAtColumnMajor(BoxGeometry geometry, long index) {
		int height = geometry.roofY - geometry.floorY + 1;
		int depth = geometry.maxZ - geometry.minZ + 1;
		long columnIndex = index / height;
		int y = geometry.floorY + (int) (index % height);
		int x = geometry.minX + (int) (columnIndex / depth);
		int z = geometry.minZ + (int) (columnIndex % depth);
		return new BlockPos(x, y, z);
	}

	private static boolean isInsideBarrierInterior(BoxGeometry barrier, BlockPos pos) {
		return barrier != null && pos != null
				&& pos.getX() >= barrier.minX + 1 && pos.getX() <= barrier.maxX - 1
				&& pos.getZ() >= barrier.minZ + 1 && pos.getZ() <= barrier.maxZ - 1;
	}

	private static void removeSceneShellNow(ServerLevel level) {
		if (level == null || serverAnchor == null) {
			return;
		}
		clearStartupWorldgenDisplay(level);
		for (BlockPos pos : collectSceneShellBlocks(serverAnchor)) {
			BlockState state = level.getBlockState(pos);
			if (isStartupShellBlock(state) || state.is(Blocks.BARRIER) || state.is(Blocks.LIGHT)) {
				setSceneBlockSilently(level, pos, Blocks.AIR.defaultBlockState());
			}
		}
		scenePrepared = false;
		sceneBuildTask = null;
	}

	private static List<BlockPos> collectSceneShellBlocks(BlockPos anchor) {
		List<BlockPos> blocks = new ArrayList<>();
		BoxGeometry barrier = computeBarrierGeometry(anchor);
		blocks.addAll(collectBarrierShellBlocks(barrier));
		blocks.addAll(collectStartupLightBlocks(barrier, Set.of()));
		blocks.addAll(collectBlackShellBlocks(computeOuterBoxGeometry(anchor)));
		return blocks;
	}

	private static List<BlockPos> collectBlackShellBlocks(BoxGeometry geometry) {
		List<BlockPos> blocks = new ArrayList<>();
		for (int y = geometry.floorY; y <= geometry.roofY; y++) {
			for (int x = geometry.minX; x <= geometry.maxX; x++) {
				for (int z = geometry.minZ; z <= geometry.maxZ; z++) {
					boolean floor = y == geometry.floorY;
					boolean wall = x == geometry.minX || x == geometry.maxX || z == geometry.minZ || z == geometry.maxZ;
					boolean roof = y == geometry.roofY;
					if (floor || wall || roof) {
						blocks.add(new BlockPos(x, y, z));
					}
				}
			}
		}
		return blocks;
	}

	private static List<BlockPos> collectBarrierShellBlocks(BoxGeometry geometry) {
		List<BlockPos> blocks = new ArrayList<>();
		for (int y = geometry.floorY; y >= geometry.floorY - (BARRIER_FLOOR_DEPTH - 1); y--) {
			for (int x = geometry.minX; x <= geometry.maxX; x++) {
				for (int z = geometry.minZ; z <= geometry.maxZ; z++) {
					blocks.add(new BlockPos(x, y, z));
				}
			}
		}
		for (int y = geometry.floorY + 1; y <= geometry.roofY; y++) {
			for (int x = geometry.minX; x <= geometry.maxX; x++) {
				for (int z = geometry.minZ; z <= geometry.maxZ; z++) {
					boolean wall = x == geometry.minX || x == geometry.maxX || z == geometry.minZ || z == geometry.maxZ;
					boolean roof = y == geometry.roofY;
					if (wall || roof) {
						blocks.add(new BlockPos(x, y, z));
					}
				}
			}
		}
		return blocks;
	}

	/**
	 * Light blocks are invisible and non-solid. Filling the free volume keeps all
	 * entities and scene blocks at block-light level 15 without Night Vision.
	 */
	private static List<BlockPos> collectStartupLightBlocks(BoxGeometry geometry, Set<Long> structureFootprint) {
		if (geometry == null) {
			return List.of();
		}
		List<BlockPos> blocks = new ArrayList<>();
		for (int y = geometry.floorY + 1; y <= geometry.roofY - 1; y++) {
			for (int x = geometry.minX + 1; x <= geometry.maxX - 1; x++) {
				for (int z = geometry.minZ + 1; z <= geometry.maxZ - 1; z++) {
					BlockPos pos = new BlockPos(x, y, z);
					if (structureFootprint == null || !structureFootprint.contains(pos.asLong())) {
						blocks.add(pos);
					}
				}
			}
		}
		return blocks;
	}

	private static boolean isStartupLight(BlockState state) {
		return state != null && state.is(Blocks.LIGHT);
	}

	private static boolean isStartupSceneAir(BlockState state) {
		return state != null && (state.isAir() || isStartupLight(state));
	}

	private static void restoreStartupLight(ServerLevel level, BlockPos pos) {
		if (level == null || pos == null) {
			return;
		}
		if (active && serverAnchor != null && !isServerStructureFootprint(pos)
				&& isInsideBarrierInterior(computeBarrierGeometry(serverAnchor), pos)) {
			level.setBlock(pos, STARTUP_LIGHT_STATE, 3);
		} else {
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
		}
	}

	private static boolean isProtectedSceneBlock(ServerLevel level, BlockPos pos) {
		if (level == null || pos == null || serverAnchor == null) {
			return false;
		}
		BoxGeometry outerGeometry = computeOuterBoxGeometry(serverAnchor);
		if (!isInsideFootprint(outerGeometry, pos)) {
			return false;
		}
		BlockState blockState = level.getBlockState(pos);
		if (isStartupShellBlock(blockState) || blockState.is(Blocks.BARRIER) || isStartupLight(blockState)) {
			return true;
		}
		if (isServerStructureFootprint(pos)) {
			return true;
		}
		BoxGeometry barrierGeometry = computeBarrierGeometry(serverAnchor);
		for (PlayerSceneState state : PLAYER_STATES.values()) {
			SlotDefinition slot = resolveSlotDefinition(barrierGeometry, state.slotIndex);
			if (slot != null && (slot.spawnFloorPos.equals(pos) || slot.oreSupportPos.equals(pos) || slot.orePos.equals(pos))) {
				return true;
			}
		}
		return false;
	}

	private static boolean isInsideFootprint(BoxGeometry geometry, BlockPos pos) {
		return pos.getX() >= geometry.minX
				&& pos.getX() <= geometry.maxX
				&& pos.getZ() >= geometry.minZ
				&& pos.getZ() <= geometry.maxZ
				&& pos.getY() >= geometry.floorY
				&& pos.getY() <= geometry.roofY;
	}

	private static boolean isServerStructureFootprint(BlockPos pos) {
		if (serverAnchor == null || pos == null) {
			return false;
		}
		return ServerStructureBreakSystem.getStructurePositions(serverAnchor, serverStructureAxis).contains(pos);
	}

	private static void placeIntroOre(ServerLevel level, SlotDefinition slot) {
		if (level == null || slot == null) {
			return;
		}
		if (isStartupShellBlock(level.getBlockState(slot.oreSupportPos))) {
			level.setBlock(slot.oreSupportPos, Blocks.AIR.defaultBlockState(), 3);
		}
		level.setBlock(slot.orePos, ModBlocks.BITCOIN_ORE.defaultBlockState(), 3);
	}

	private static void clearIntroOre(ServerLevel level, SlotDefinition slot) {
		if (level == null || slot == null) {
			return;
		}
		if (level.getBlockState(slot.orePos).is(ModBlocks.BITCOIN_ORE)) {
			restoreStartupLight(level, slot.orePos);
		}
		if (isStartupShellBlock(level.getBlockState(slot.oreSupportPos))) {
			level.setBlock(slot.oreSupportPos, Blocks.AIR.defaultBlockState(), 3);
		}
	}

	private static void tickStartupOfferings(MinecraftServer server, ServerLevel level) {
		if (server == null || level == null || serverAnchor == null) {
			return;
		}
		boolean hasGuidedPlayers = false;
		for (PlayerSceneState state : PLAYER_STATES.values()) {
			if (state != null && state.phase == PlayerPhase.GUIDED_TO_SERVER) {
				hasGuidedPlayers = true;
				break;
			}
		}
		if (!hasGuidedPlayers && countSharedPlayers() <= 0) {
			return;
		}

		ServerStructureBounds bounds = resolveServerStructureBounds();
		if (bounds == null) {
			return;
		}
		tickGuidedBitcoinOfferings(server, level, bounds);
		AABB scanBox = new AABB(
				bounds.minX - 1.5D,
				serverAnchor.getY() - 1.5D,
				bounds.minZ - 1.5D,
				bounds.maxX + 1.5D,
				serverAnchor.getY() + 4.5D,
				bounds.maxZ + 1.5D
		);
		for (ItemEntity itemEntity : level.getEntitiesOfClass(ItemEntity.class, scanBox, entity ->
				entity != null
						&& entity.isAlive()
						&& !entity.isRemoved()
						&& !entity.getItem().isEmpty()
						&& entity.getItem().is(ModItems.BITCOIN)
		)) {
			if (isGuidedBitcoinOffering(itemEntity)) {
				// Personal offerings are animated and consumed by the dedicated path above.
				continue;
			}
			if (distanceToServerStructureSqr(itemEntity.position(), bounds) > SHARED_FEED_RADIUS * SHARED_FEED_RADIUS) {
				continue;
			}
			ServerPlayer guidedPlayer = resolveNearestOfferingPlayer(level, itemEntity, PlayerPhase.GUIDED_TO_SERVER);
			ServerPlayer sharedPlayer = resolveNearestOfferingPlayer(level, itemEntity, PlayerPhase.SHARED);
			double guidedDistance = guidedPlayer == null ? Double.MAX_VALUE : guidedPlayer.distanceToSqr(itemEntity);
			double sharedDistance = sharedPlayer == null ? Double.MAX_VALUE : sharedPlayer.distanceToSqr(itemEntity);
			if (guidedPlayer != null && guidedDistance <= sharedDistance + 0.25D) {
				consumeOffering(itemEntity, 1);
				spawnLaunchFeedParticles(level, itemEntity.position());
				transitionPlayerAfterFirstPayment(server, guidedPlayer);
				continue;
			}
			if (sharedPlayer == null || countSharedPlayers() <= 0) {
				continue;
			}
			if (sharedLaunchCollectedBitcoins >= sharedLaunchRequiredBitcoins) {
				// At 100% the launch no longer accepts offerings. Leave the item as a
				// normal drop instead of silently consuming a late stack.
				continue;
			}
			int consumed = itemEntity.getItem().getCount();
			if (consumed <= 0) {
				continue;
			}
			consumeOffering(itemEntity, consumed);
			spawnLaunchFeedParticles(level, itemEntity.position());
			incrementSharedLaunchProgress(server, consumed);
		}
	}

	/** A missed payment stays real, but takes a long smooth flight away from the server. */
	private static void tickGuidedBitcoinOfferings(MinecraftServer server, ServerLevel level, ServerStructureBounds bounds) {
		if (server == null || level == null || bounds == null || serverAnchor == null) {
			return;
		}
		BoxGeometry scene = computeBarrierGeometry(serverAnchor);
		AABB offeringBox = new AABB(
				scene.minX,
				scene.floorY,
				scene.minZ,
				scene.maxX + 1.0D,
				scene.roofY + 1.0D,
				scene.maxZ + 1.0D
		);
		long nowTick = level.getGameTime();
		for (ItemEntity itemEntity : level.getEntitiesOfClass(ItemEntity.class, offeringBox, SeasonStartSystem::isGuidedBitcoinOffering)) {
			UUID offeringId = itemEntity.getUUID();
			ServerPlayer owner = resolveGuidedBitcoinOfferingOwner(itemEntity);
			if (owner == null) {
				continue;
			}
			PlayerSceneState ownerState = PLAYER_STATES.get(owner.getUUID());
			if (ownerState == null) {
				continue;
			}
			if (ownerState.guidedBitcoinEscapeCount >= GUIDED_OFFERING_MAX_ESCAPES
					&& ownerState.escapedGuidedOfferingId == null) {
				// The slot was deliberately unlocked at the server. From here the coin is a
				// normal vanilla drop: no third scripted escape, but a toss into the server
				// still gets accepted across the full close-and-looking interaction range.
				if (distanceToServerStructureSqr(itemEntity.position(), bounds)
						<= GUIDED_FINAL_OFFERING_RADIUS * GUIDED_FINAL_OFFERING_RADIUS) {
					GUIDED_OFFERING_VISIBLE_SINCE_TICKS.remove(offeringId);
					consumeOffering(itemEntity, 1);
					spawnLaunchFeedParticles(level, itemEntity.position());
					transitionPlayerAfterFirstPayment(server, owner);
				}
				continue;
			}
			// Until that point, a tutorial coin is progression state, not ordinary loot.
			// It must survive even if its owner gets distracted for longer than vanilla's timeout.
			itemEntity.setUnlimitedLifetime();
			if (distanceToServerStructureSqr(itemEntity.position(), bounds) <= SHARED_FEED_RADIUS * SHARED_FEED_RADIUS) {
				GUIDED_OFFERING_VISIBLE_SINCE_TICKS.remove(offeringId);
				clearEscapedGuidedBitcoinState(ownerState);
				consumeOffering(itemEntity, 1);
				spawnLaunchFeedParticles(level, itemEntity.position());
				transitionPlayerAfterFirstPayment(server, owner);
				continue;
			}

			long visibleSince = GUIDED_OFFERING_VISIBLE_SINCE_TICKS.computeIfAbsent(offeringId, ignored -> nowTick);
			if (ownerState.escapedGuidedOfferingId == null) {
				if (ownerState.guidedBitcoinEscapeCount >= GUIDED_OFFERING_MAX_ESCAPES) {
					// Kept as a defensive guard for an in-flight state loaded from an older save.
					// Do not animate or delete the drop: after two recoveries it stays vanilla.
					continue;
				}
				if (nowTick - visibleSince < GUIDED_OFFERING_ESCAPE_DELAY_TICKS) {
					continue;
				}
				beginGuidedBitcoinEscape(server, level, owner, ownerState, itemEntity, scene, bounds, nowTick);
				continue;
			}
			if (!offeringId.equals(ownerState.escapedGuidedOfferingId)) {
				continue;
			}
			tickEscapedGuidedBitcoinFlight(itemEntity, ownerState);
		}
	}

	private static void beginGuidedBitcoinEscape(
			MinecraftServer server,
			ServerLevel level,
			ServerPlayer owner,
			PlayerSceneState state,
			ItemEntity itemEntity,
			BoxGeometry scene,
			ServerStructureBounds bounds,
			long nowTick
	) {
		if (server == null || level == null || owner == null || state == null || itemEntity == null || scene == null || bounds == null) {
			return;
		}
		Vec3 target = chooseGuidedBitcoinEscapeTarget(owner, itemEntity, scene, bounds);
		state.guidedBitcoinEscapeCount++;
		state.escapedGuidedOfferingId = itemEntity.getUUID();
		state.escapedGuidedOfferingTarget = target;
		state.escapedGuidedOfferingLanded = false;
		state.wasAtServerWithoutBitcoin = false;
		state.leftServerWhileRecoveringBitcoin = false;
		state.serverWithoutBitcoinVisitCount = 0;
		stateDirty = true;
		GUIDED_OFFERING_VISIBLE_SINCE_TICKS.remove(itemEntity.getUUID());
		resetGuidanceRoute(state);
		SeasonStartVoiceSystem.clearPlayerChannel(owner);
		String[] lostTriggers = state.guidedBitcoinEscapeCount == 1
				? INTRO_GUIDED_BITCOIN_LOST_TRIGGERS
				: INTRO_GUIDED_BITCOIN_LOST_AGAIN_TRIGGERS;
		long narrationLock = resolveGuidanceNarrationLockTicks(lostTriggers);
		state.guidanceNarrationGateTick = nowTick + narrationLock;
		state.nextGuidanceTick = nowTick;
		state.nextGuidanceVoiceTick = nowTick + narrationLock;
		state.nextGuidanceEarliestTick = nowTick + narrationLock;
		state.lastGuidanceStateKey = "";
		fireTriggerCycle(
				server,
				owner,
				state,
				state.guidedBitcoinEscapeCount == 1 ? "intro_guided_bitcoin_lost" : "intro_guided_bitcoin_lost_again",
				lostTriggers
		);

		itemEntity.setPickUpDelay((int) narrationLock);
		itemEntity.setUnlimitedLifetime();
		itemEntity.setNoGravity(true);
		Vec3 offset = target.subtract(itemEntity.position());
		double distance = offset.length();
		if (distance > 1.0E-4D) {
			itemEntity.setDeltaMovement(offset.scale(GUIDED_OFFERING_ESCAPE_SPEED_MAX / distance));
		}
	}

	private static Vec3 chooseGuidedBitcoinEscapeTarget(
			ServerPlayer owner,
			ItemEntity itemEntity,
			BoxGeometry scene,
			ServerStructureBounds bounds
	) {
		Vec3 thrownTarget = chooseGuidedBitcoinLookTarget(owner, itemEntity, scene, bounds);
		if (thrownTarget != null) {
			return thrownTarget;
		}
		return chooseGuidedBitcoinEscapeFallbackTarget(owner, itemEntity, scene, bounds);
	}

	/**
	 * The coin follows the throw where that points away from the server.  A throw aimed at the
	 * server is reflected outward instead: the missed payment must never pass through or stop at
	 * the server just because the player was standing a few blocks away.
	 */
	private static Vec3 chooseGuidedBitcoinLookTarget(
			ServerPlayer owner,
			ItemEntity itemEntity,
			BoxGeometry scene,
			ServerStructureBounds bounds
	) {
		Vec3 start = itemEntity.position();
		double centerX = (bounds.minX + bounds.maxX) * 0.5D;
		double centerZ = (bounds.minZ + bounds.maxZ) * 0.5D;
		Vec3 away = new Vec3(start.x - centerX, 0.0D, start.z - centerZ);
		if (away.lengthSqr() < 1.0E-4D) {
			away = new Vec3(owner.getX() - centerX, 0.0D, owner.getZ() - centerZ);
		}
		if (away.lengthSqr() < 1.0E-4D) {
			return null;
		}
		away = away.normalize();

		Vec3 look = owner.getLookAngle();
		Vec3 horizontalLook = new Vec3(look.x, 0.0D, look.z);
		Vec3 direction = horizontalLook.lengthSqr() < 1.0E-4D ? away : horizontalLook.normalize();
		// Preserve the player's throw direction when it already points outward. Otherwise,
		// turn it decisively away from the server rather than letting it cross the model.
		if (direction.dot(away) < 0.20D) {
			direction = away;
		} else {
			direction = direction.scale(0.72D).add(away.scale(0.28D)).normalize();
		}

		double boundaryDistance = distanceToGuidedOfferingBoundary(start, direction, scene);
		double throwDistance = Math.min(GUIDED_OFFERING_ESCAPE_THROW_DISTANCE, Math.max(0.0D, boundaryDistance - 0.35D));
		if (throwDistance < 2.0D) {
			return null;
		}

		double targetY = Mth.clamp(
				start.y + 0.75D + look.y * throwDistance * 0.55D,
				scene.floorY + 0.4D,
				scene.roofY - 1.0D
		);
		Vec3 target = new Vec3(
				start.x + direction.x * throwDistance,
				targetY,
				start.z + direction.z * throwDistance
		);
		double minimumServerDistance = SHARED_FEED_RADIUS + 2.5D;
		return distanceToServerStructureSqr(target, bounds) >= minimumServerDistance * minimumServerDistance ? target : null;
	}

	private static double distanceToGuidedOfferingBoundary(Vec3 start, Vec3 direction, BoxGeometry scene) {
		double minX = scene.minX + GUIDED_OFFERING_ESCAPE_BOUNDARY_MARGIN;
		double maxX = scene.maxX - GUIDED_OFFERING_ESCAPE_BOUNDARY_MARGIN;
		double minZ = scene.minZ + GUIDED_OFFERING_ESCAPE_BOUNDARY_MARGIN;
		double maxZ = scene.maxZ - GUIDED_OFFERING_ESCAPE_BOUNDARY_MARGIN;
		double distance = Double.POSITIVE_INFINITY;
		if (direction.x > 1.0E-4D) {
			distance = Math.min(distance, (maxX - start.x) / direction.x);
		} else if (direction.x < -1.0E-4D) {
			distance = Math.min(distance, (minX - start.x) / direction.x);
		}
		if (direction.z > 1.0E-4D) {
			distance = Math.min(distance, (maxZ - start.z) / direction.z);
		} else if (direction.z < -1.0E-4D) {
			distance = Math.min(distance, (minZ - start.z) / direction.z);
		}
		return Double.isFinite(distance) ? Math.max(0.0D, distance) : 0.0D;
	}

	private static boolean guidedOfferingThrowCrossesServer(
			Vec3 start,
			Vec3 direction,
			double throwDistance,
			ServerStructureBounds bounds
	) {
		double centerX = (bounds.minX + bounds.maxX) * 0.5D;
		double centerZ = (bounds.minZ + bounds.maxZ) * 0.5D;
		double toServerX = centerX - start.x;
		double toServerZ = centerZ - start.z;
		double forwardDistance = toServerX * direction.x + toServerZ * direction.z;
		if (forwardDistance < 0.0D || forwardDistance > throwDistance) {
			return false;
		}
		double sideDistance = Math.abs(toServerX * direction.z - toServerZ * direction.x);
		return sideDistance < SHARED_FEED_RADIUS + 1.25D;
	}

	private static Vec3 chooseGuidedBitcoinEscapeFallbackTarget(
			ServerPlayer owner,
			ItemEntity itemEntity,
			BoxGeometry scene,
			ServerStructureBounds bounds
	) {
		long seed = itemEntity.getUUID().getMostSignificantBits() ^ itemEntity.getUUID().getLeastSignificantBits();
		Random random = new Random(seed);
		double centerX = (bounds.minX + bounds.maxX) * 0.5D;
		double centerZ = (bounds.minZ + bounds.maxZ) * 0.5D;
		double awayX = owner.getX() - centerX;
		double awayZ = owner.getZ() - centerZ;
		double awayLength = Math.sqrt(awayX * awayX + awayZ * awayZ);
		if (awayLength < 0.25D) {
			double angle = random.nextDouble() * Math.PI * 2.0D;
			awayX = Math.cos(angle);
			awayZ = Math.sin(angle);
			awayLength = 1.0D;
		}
		awayX /= awayLength;
		awayZ /= awayLength;
		double interiorWidth = Math.max(2.0D, scene.maxX - scene.minX - 2.0D);
		double interiorDepth = Math.max(2.0D, scene.maxZ - scene.minZ - 2.0D);
		double targetDistance = Math.min(GUIDED_OFFERING_ESCAPE_THROW_DISTANCE, Math.max(5.0D, Math.min(interiorWidth, interiorDepth) * 0.42D));
		double minimumServerDistance = SHARED_FEED_RADIUS + 2.5D;
		for (int attempt = 0; attempt < 18; attempt++) {
			double angleOffset = (random.nextDouble() - 0.5D) * 1.7D;
			double cos = Math.cos(angleOffset);
			double sin = Math.sin(angleOffset);
			double directionX = awayX * cos - awayZ * sin;
			double directionZ = awayX * sin + awayZ * cos;
			double distance = targetDistance * (0.82D + random.nextDouble() * 0.34D);
			Vec3 candidate = new Vec3(
					Mth.clamp(owner.getX() + directionX * distance, scene.minX + 1.5D, scene.maxX - 0.5D),
					scene.floorY + 2.0D,
					Mth.clamp(owner.getZ() + directionZ * distance, scene.minZ + 1.5D, scene.maxZ - 0.5D)
			);
			if (candidate.distanceToSqr(owner.position()) >= 4.5D * 4.5D
					&& distanceToServerStructureSqr(candidate, bounds) >= minimumServerDistance * minimumServerDistance) {
				return candidate;
			}
		}
		return new Vec3(
				Mth.clamp(owner.getX() + awayX * targetDistance, scene.minX + 1.5D, scene.maxX - 0.5D),
				scene.floorY + 2.0D,
				Mth.clamp(owner.getZ() + awayZ * targetDistance, scene.minZ + 1.5D, scene.maxZ - 0.5D)
		);
	}

	private static void tickEscapedGuidedBitcoinFlight(ItemEntity itemEntity, PlayerSceneState state) {
		if (itemEntity == null || state == null || state.escapedGuidedOfferingTarget == null || state.escapedGuidedOfferingLanded) {
			return;
		}
		if (!itemEntity.isNoGravity()) {
			if (itemEntity.onGround()) {
				state.escapedGuidedOfferingLanded = true;
				state.escapedGuidedOfferingTarget = itemEntity.position();
			}
			return;
		}
		Vec3 offset = state.escapedGuidedOfferingTarget.subtract(itemEntity.position());
		double distance = offset.length();
		if (distance <= GUIDED_OFFERING_ESCAPE_TARGET_DISTANCE) {
			itemEntity.setDeltaMovement(Vec3.ZERO);
			itemEntity.setNoGravity(false);
			return;
		}
		double speed = Mth.clamp(
				GUIDED_OFFERING_ESCAPE_SPEED_MIN + distance * 0.045D,
				GUIDED_OFFERING_ESCAPE_SPEED_MIN,
				GUIDED_OFFERING_ESCAPE_SPEED_MAX
		);
		Vec3 desiredVelocity = offset.scale(speed / distance);
		itemEntity.setDeltaMovement(itemEntity.getDeltaMovement().scale(0.18D).add(desiredVelocity.scale(0.82D)));
	}

	private static boolean tickLostGuidedBitcoinRecovery(MinecraftServer server, ServerPlayer player, PlayerSceneState state, long nowTick) {
		if (server == null || player == null || state == null || !(player.level() instanceof ServerLevel level)
				|| state.escapedGuidedOfferingId == null) {
			return false;
		}
		ItemEntity itemEntity = findEscapedGuidedBitcoin(level, state.escapedGuidedOfferingId);
		if (itemEntity == null) {
			// The item should never vanish during the private scene. Recover it rather than trapping the player.
			giveOrDrop(player, new ItemStack(ModItems.BITCOIN));
			clearEscapedGuidedBitcoinState(state);
			resetGuidanceRoute(state);
			return true;
		}
		ServerStructureBounds serverBounds = resolveServerStructureBounds();
		if (serverBounds != null && !state.leftServerWhileRecoveringBitcoin
				&& distanceToServerStructureSqr(player.position(), serverBounds)
				>= GUIDANCE_SERVER_RETURN_ARM_DISTANCE * GUIDANCE_SERVER_RETURN_ARM_DISTANCE) {
			state.leftServerWhileRecoveringBitcoin = true;
			stateDirty = true;
		}
		boolean backAtServer = isCloseAndLookingAtServer(player, GUIDANCE_SERVER_SIGHT_DISTANCE);
		if (state.leftServerWhileRecoveringBitcoin && backAtServer && !state.wasAtServerWithoutBitcoin) {
			state.announcedServerWithoutBitcoin = true;
			String trigger = state.serverWithoutBitcoinVisitCount++ == 0
					? "intro_server_without_bitcoin"
					: "intro_server_without_bitcoin_again";
			SeasonStartVoiceSystem.clearPlayerChannel(player);
			SeasonStartVoiceSystem.fireTrigger(server, trigger, player);
		}
		state.wasAtServerWithoutBitcoin = backAtServer;
		if (!state.escapedGuidedOfferingLanded) {
			return true;
		}
		if (player.distanceToSqr(itemEntity) <= GUIDED_OFFERING_RECOVERY_DISTANCE * GUIDED_OFFERING_RECOVERY_DISTANCE) {
			ItemStack returning = itemEntity.getItem().copy();
			int returnedCount = returning.getCount();
			if (player.getInventory().add(returning)) {
				GUIDED_OFFERING_VISIBLE_SINCE_TICKS.remove(itemEntity.getUUID());
				player.take(itemEntity, returnedCount);
				itemEntity.discard();
				clearEscapedGuidedBitcoinState(state);
				resetGuidanceRoute(state);
				SeasonStartVoiceSystem.clearPlayerChannel(player);
				String recoveredTrigger = "intro_guided_bitcoin_recovered";
				if (state.guidedBitcoinEscapeCount >= GUIDED_OFFERING_MAX_ESCAPES) {
					state.lockedGuidedBitcoinSlot = findBitcoinSlot(player);
					recoveredTrigger = "intro_guided_bitcoin_recovered_locked";
					keepLockedGuidedBitcoinInSlot(player, state, null);
				}
				long narrationLock = resolveTriggerSequenceDurationTicks(recoveredTrigger);
				state.guidanceNarrationGateTick = nowTick + narrationLock;
				state.nextGuidanceTick = nowTick;
				state.nextGuidanceVoiceTick = nowTick + narrationLock;
				state.nextGuidanceEarliestTick = nowTick + narrationLock;
				state.lastGuidanceStateKey = "";
				SeasonStartVoiceSystem.fireTrigger(server, recoveredTrigger, player);
				stateDirty = true;
			}
			return true;
		}
		if (nowTick < state.guidanceNarrationGateTick || nowTick < state.nextGuidanceTick) {
			return true;
		}
		state.nextGuidanceTick = nowTick + GUIDANCE_EVALUATE_TICKS;
		BoxGeometry barrier = computeBarrierGeometry(resolveServerAnchor(level));
		if (!state.guidanceRouteFinished) {
			tickConfusedRoute(server, player, state, GuidanceRouteKind.LOST_BITCOIN, itemEntity.position(), barrier, nowTick);
			return true;
		}
		GuidanceSnapshot snapshot = resolveGuidanceSnapshot(player, itemEntity.position());
		if (snapshot == null) {
			return true;
		}
		GuidanceInstruction instruction = resolveThrottledRouteTurnInstruction(snapshot, state, nowTick);
		if (instruction == null) {
			instruction = resolveRouteForwardInstruction(snapshot);
		}
		fireRouteGuidanceInstruction(server, player, state, instruction, snapshot, nowTick);
		return true;
	}

	private static ItemEntity findEscapedGuidedBitcoin(ServerLevel level, UUID offeringId) {
		if (level == null || offeringId == null || serverAnchor == null) {
			return null;
		}
		BoxGeometry scene = computeBarrierGeometry(serverAnchor);
		AABB sceneBounds = new AABB(scene.minX, scene.floorY, scene.minZ, scene.maxX + 1.0D, scene.roofY + 1.0D, scene.maxZ + 1.0D);
		for (ItemEntity itemEntity : level.getEntitiesOfClass(ItemEntity.class, sceneBounds, entity -> offeringId.equals(entity.getUUID()))) {
			return itemEntity;
		}
		return null;
	}

	private static void clearEscapedGuidedBitcoinState(PlayerSceneState state) {
		if (state == null) {
			return;
		}
		state.escapedGuidedOfferingId = null;
		state.escapedGuidedOfferingTarget = null;
		state.escapedGuidedOfferingLanded = false;
		stateDirty = true;
	}

	private static boolean isGuidedBitcoinOffering(ItemEntity itemEntity) {
		return itemEntity != null
				&& itemEntity.isAlive()
				&& !itemEntity.isRemoved()
				&& !itemEntity.getItem().isEmpty()
				&& itemEntity.getItem().is(ModItems.BITCOIN)
				&& resolveGuidedBitcoinOfferingOwner(itemEntity) != null;
	}

	private static ServerPlayer resolveGuidedBitcoinOfferingOwner(ItemEntity itemEntity) {
		if (itemEntity == null || !(itemEntity.getOwner() instanceof ServerPlayer owner)) {
			return null;
		}
		PlayerSceneState state = PLAYER_STATES.get(owner.getUUID());
		return state != null && state.phase == PlayerPhase.GUIDED_TO_SERVER ? owner : null;
	}

	private static ServerPlayer resolveNearestOfferingPlayer(ServerLevel level, ItemEntity itemEntity, PlayerPhase phase) {
		if (level == null || itemEntity == null || phase == null) {
			return null;
		}
		ServerPlayer matched = null;
		double bestDistance = SHARED_FEED_PLAYER_MATCH_DISTANCE * SHARED_FEED_PLAYER_MATCH_DISTANCE;
		for (ServerPlayer player : level.players()) {
			PlayerSceneState state = PLAYER_STATES.get(player.getUUID());
			if (state == null || state.phase != phase) {
				continue;
			}
			double distance = player.distanceToSqr(itemEntity);
			if (distance < bestDistance) {
				bestDistance = distance;
				matched = player;
			}
		}
		return matched;
	}

	private static void consumeOffering(ItemEntity itemEntity, int amount) {
		if (itemEntity == null || amount <= 0) {
			return;
		}
		ItemStack stack = itemEntity.getItem();
		if (stack.isEmpty() || !stack.is(ModItems.BITCOIN)) {
			return;
		}
		if (amount >= stack.getCount()) {
			itemEntity.discard();
			return;
		}
		stack.shrink(amount);
		itemEntity.setItem(stack);
	}

	private static void spawnLaunchFeedParticles(ServerLevel level, Vec3 position) {
		if (level == null || position == null) {
			return;
		}
		ServerStabilitySystem.emitFeedParticles(level, position.x, position.y, position.z, 10);
	}

	private static void incrementSharedLaunchProgress(MinecraftServer server, int bitcoins) {
		if (server == null || bitcoins <= 0) {
			return;
		}
		if (sharedLaunchRequiredBitcoins <= 0) {
			sharedLaunchRequiredBitcoins = SHARED_LAUNCH_REQUIRED_BITCOINS;
		}
		if (sharedLaunchCollectedBitcoins >= sharedLaunchRequiredBitcoins) {
			return;
		}
		ServerLevel overworld = server.overworld();
		long nowTick = overworld == null ? 0L : overworld.getGameTime();
		sharedLaunchCollectedBitcoins = Math.min(sharedLaunchRequiredBitcoins, sharedLaunchCollectedBitcoins + bitcoins);
		lastSharedLaunchProgressTick = nowTick;
		if (sharedLaunchCollectedBitcoins >= sharedLaunchRequiredBitcoins) {
			// The finale takes exclusive ownership of narration. This prevents an
			// unfinished personal menu line from leaking into it.
			menuExplanationActive = false;
			pendingMenuExplanationTick = Long.MIN_VALUE;
			SeasonStartVoiceSystem.resetSceneState();
			SeasonStartVoiceSystem.fireTrigger(server, "shared_launch_complete", null);
		}
		ensureSharedServerPowerNarration(server);
		ensureSharedRaceControlsNarration(server);
		if (!menuExplanationActive
				&& getSharedLaunchPercent() >= MENU_EXPLANATION_UNLOCK_PERCENT
				&& pendingMenuExplanationTick == Long.MIN_VALUE) {
			pendingMenuExplanationTick = nowTick + MENU_EXPLANATION_DELAY_TICKS;
		}
		if (sharedLaunchCollectedBitcoins >= sharedLaunchRequiredBitcoins && pendingSharedFinishTick == Long.MIN_VALUE) {
			pendingSharedFinishTick = nowTick
					+ resolveTriggerSequenceDurationTicks("shared_launch_complete")
					+ SHARED_FINISH_DELAY_TICKS;
		}
		refreshSharedLaunchBossBar(server);
		stateDirty = true;
	}

	private static void ensureSharedServerPowerNarration(MinecraftServer server) {
		if (server == null || sharedLaunchServerPowerNarrationTriggered
				|| sharedLaunchCollectedBitcoins >= sharedLaunchRequiredBitcoins
				|| getSharedLaunchPercent() < SHARED_LAUNCH_SERVER_POWER_PERCENT) {
			return;
		}
		sharedLaunchServerPowerNarrationTriggered = true;
		SeasonStartVoiceSystem.fireTrigger(server, "shared_launch_server_power", null);
		stateDirty = true;
	}

	/** Race controls are delivered through personal channels so a menu interaction cannot mask them. */
	private static void ensureSharedRaceControlsNarration(MinecraftServer server) {
		if (server == null || sharedLaunchRaceControlsTriggered
				|| sharedLaunchCollectedBitcoins >= sharedLaunchRequiredBitcoins
				|| getSharedLaunchPercent() < SHARED_LAUNCH_RACE_CONTROLS_PERCENT) {
			return;
		}
		sharedLaunchRaceControlsTriggered = true;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!isInSharedPhase(player)) {
				continue;
			}
			PlayerSceneState state = PLAYER_STATES.get(player.getUUID());
			if (state == null) {
				continue;
			}
			SeasonStartVoiceSystem.fireTrigger(server, "shared_launch_race_controls", player);
			if (!state.racePurchaseExplained && !state.raceMenuReminderExplained) {
				state.raceMenuReminderExplained = true;
				SeasonStartVoiceSystem.fireTrigger(server, "shared_launch_race_menu_reminder", player);
			}
		}
		stateDirty = true;
	}

	private static String resolveMenuItemExplanationTrigger(String upgradeId) {
		if (upgradeId == null || upgradeId.isBlank()) {
			return null;
		}
		return switch (upgradeId) {
			case "era_netherite" -> "player_menu_item_netherite";
			case "mechanic_redstone" -> "player_menu_item_redstone";
			case "it_camera" -> "player_menu_item_camera";
			case "world_nether" -> "player_menu_item_nether";
			default -> null;
		};
	}

	private static int getSharedLaunchPercent() {
		if (sharedLaunchRequiredBitcoins <= 0) {
			return 0;
		}
		double percent = (double) sharedLaunchCollectedBitcoins * 100.0D / (double) sharedLaunchRequiredBitcoins;
		return Mth.clamp((int) Math.floor(percent), 0, 100);
	}

	private static void ensureStartupWorldgenDisplay(ServerLevel level) {
		if (level == null || serverAnchor == null || !active || completed) {
			return;
		}
		BoxGeometry outerGeometry = computeOuterBoxGeometry(serverAnchor);
		AABB displayBounds = startupWorldgenDisplayBounds(serverAnchor, outerGeometry);
		Display.ItemDisplay display = resolveStartupWorldgenDisplay(level, displayBounds);
		boolean created = display == null;
		if (created) {
			display = new Display.ItemDisplay(EntityType.ITEM_DISPLAY, level);
			display.addTag(STARTUP_WORLDGEN_DISPLAY_TAG);
		}
		if (display == null) {
			return;
		}

		float scale = STARTUP_WORLDGEN_DISPLAY_SCALE * Math.max(
				16.0F,
				Math.min(outerGeometry.maxX - outerGeometry.minX, outerGeometry.maxZ - outerGeometry.minZ) - STARTUP_WORLDGEN_MARGIN_BLOCKS
		);
		// Keep the projection just above the animated outer floor.  The floor can
		// move independently of the barrier room, so this must not be based on the
		// player/barrier-floor height.
		display.setPos(serverAnchor.getX() + 0.5D, startupWorldgenDisplayY(outerGeometry), serverAnchor.getZ() + 0.5D);
		display.setYRot(0.0F);
		display.setXRot(0.0F);
		display.setYHeadRot(0.0F);
		display.setYBodyRot(0.0F);
		display.setItemTransform(ItemDisplayContext.FIXED);
		display.setBillboardConstraints(Display.BillboardConstraints.FIXED);
		display.setTransformation(new Transformation(
				new Vector3f(0.0F, 0.0F, 0.0F),
				new Quaternionf(),
				new Vector3f(scale, STARTUP_WORLDGEN_THICKNESS_SCALE, scale),
				new Quaternionf()
		));
		display.setBrightnessOverride(STARTUP_WORLDGEN_BRIGHTNESS);
		display.setNoGravity(true);
		display.setInvulnerable(true);
		display.setSilent(true);
		display.setShadowRadius(0.0F);
		display.setShadowStrength(0.0F);
		display.setViewRange(STARTUP_WORLDGEN_VIEW_RANGE);
		ItemDisplayHitboxHelper.clear(display);

		int desiredFrameIndex = resolveStartupWorldgenFrameIndex();
		if (created || desiredFrameIndex != startupWorldgenFrameIndex) {
			display.setItemStack(createStartupWorldgenFrameStack(desiredFrameIndex));
			startupWorldgenFrameIndex = desiredFrameIndex;
		}

		if (created) {
			level.addFreshEntity(display);
		}
	}

	private static int resolveStartupWorldgenFrameIndex() {
		int percent = getSharedLaunchPercent();
		double normalized = percent / 100.0D;
		return Mth.clamp((int) Math.round(normalized * (STARTUP_WORLDGEN_FRAME_COUNT - 1)), 0, STARTUP_WORLDGEN_FRAME_COUNT - 1);
	}

	private static ItemStack createStartupWorldgenFrameStack(int frameIndex) {
		int clamped = Mth.clamp(frameIndex, 0, STARTUP_WORLDGEN_FRAME_COUNT - 1);
		String suffix = clamped < 10 ? "0" + clamped : Integer.toString(clamped);
		ItemStack stack = new ItemStack(Items.PAPER);
		stack.set(DataComponents.ITEM_MODEL, Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "startup_worldgen_frame_" + suffix));
		return stack;
	}

	private static AABB startupWorldgenDisplayBounds(BlockPos anchor, BoxGeometry outerGeometry) {
		double centerX = anchor.getX() + 0.5D;
		double centerY = (outerGeometry.floorY + outerGeometry.roofY) * 0.5D;
		double centerZ = anchor.getZ() + 0.5D;
		return new AABB(
				outerGeometry.minX - 2.0D,
				outerGeometry.floorY - 2.0D,
				outerGeometry.minZ - 2.0D,
				outerGeometry.maxX + 2.0D,
				outerGeometry.roofY + 2.0D,
				outerGeometry.maxZ + 2.0D
		);
	}

	private static double startupWorldgenDisplayY(BoxGeometry outerGeometry) {
		return outerGeometry.floorY + STARTUP_WORLDGEN_Y_OFFSET_FROM_OUTER_FLOOR;
	}

	private static Display.ItemDisplay resolveStartupWorldgenDisplay(ServerLevel level, AABB box) {
		if (level == null || box == null) {
			return null;
		}
		List<Display.ItemDisplay> displays = level.getEntities(
				EntityType.ITEM_DISPLAY,
				box,
				display -> display.getTags().contains(STARTUP_WORLDGEN_DISPLAY_TAG)
		);
		if (displays.isEmpty()) {
			return null;
		}
		Display.ItemDisplay root = displays.get(0);
		for (int index = 1; index < displays.size(); index++) {
			displays.get(index).discard();
		}
		return root;
	}

	private static void clearStartupWorldgenDisplay(ServerLevel level) {
		if (level == null || serverAnchor == null) {
			startupWorldgenFrameIndex = Integer.MIN_VALUE;
			return;
		}
		AABB box = startupWorldgenDisplayBounds(serverAnchor, computeOuterBoxGeometry(serverAnchor));
		for (Display.ItemDisplay display : level.getEntities(
				EntityType.ITEM_DISPLAY,
				box,
				candidate -> candidate.getTags().contains(STARTUP_WORLDGEN_DISPLAY_TAG)
		)) {
			display.discard();
		}
		startupWorldgenFrameIndex = Integer.MIN_VALUE;
	}

	private static void rememberServerStructure(BlockPos anchor, Direction.Axis axis) {
		if (anchor == null) {
			return;
		}
		BlockPos immutableAnchor = anchor.immutable();
		Direction.Axis safeAxis = axis == Direction.Axis.X ? Direction.Axis.X : Direction.Axis.Z;
		if (!immutableAnchor.equals(serverAnchor) || serverStructureAxis != safeAxis) {
			serverAnchor = immutableAnchor;
			serverStructureAxis = safeAxis;
			stateDirty = true;
		}
	}

	public static void onServerStructurePlaced(ServerLevel level, BlockPos anchor, Direction.Axis axis) {
		if (level == null || anchor == null || !Level.OVERWORLD.equals(level.dimension())) {
			return;
		}
		rememberServerStructure(anchor, axis);
	}

	public static void onServerStructureRemoved(ServerLevel level, BlockPos anchor) {
		if (level == null || anchor == null || serverAnchor == null || !Level.OVERWORLD.equals(level.dimension())) {
			return;
		}
		if (!serverAnchor.equals(anchor)) {
			return;
		}
		serverAnchor = null;
		serverStructureAxis = Direction.Axis.Z;
		stateDirty = true;
	}

	private static Direction.Axis resolveKnownServerStructureAxis(ServerLevel level, BlockPos anchor) {
		if (level == null || anchor == null) {
			return null;
		}
		if (isWholeServerStructurePresent(level, anchor, serverStructureAxis)) {
			return serverStructureAxis;
		}
		for (Direction.Axis axis : List.of(Direction.Axis.Z, Direction.Axis.X)) {
			if (axis != serverStructureAxis && isWholeServerStructurePresent(level, anchor, axis)) {
				return axis;
			}
		}
		return null;
	}

	private static boolean isWholeServerStructurePresent(ServerLevel level, BlockPos anchor, Direction.Axis axis) {
		if (level == null || anchor == null || axis == null) {
			return false;
		}
		for (BlockPos pos : ServerStructureBreakSystem.getStructurePositions(anchor, axis)) {
			if (!level.getBlockState(pos).is(ModBlocks.SERVER)) {
				return false;
			}
		}
		return true;
	}

	private static ResolvedServerStructure resolveServerStructurePlacement(ServerLevel level, BlockPos structurePos) {
		if (level == null || structurePos == null) {
			return null;
		}
		Set<Long> checked = new HashSet<>();
		for (Direction.Axis axis : List.of(Direction.Axis.Z, Direction.Axis.X)) {
			for (int dy = 0; dy < ServerStructureBreakSystem.STRUCTURE_HEIGHT; dy++) {
				for (int depth = -ServerStructureBreakSystem.STRUCTURE_HALF_DEPTH; depth <= ServerStructureBreakSystem.STRUCTURE_HALF_DEPTH; depth++) {
					for (int width = -ServerStructureBreakSystem.STRUCTURE_HALF_WIDTH; width <= ServerStructureBreakSystem.STRUCTURE_HALF_WIDTH; width++) {
						BlockPos anchor = axis == Direction.Axis.X
								? structurePos.offset(-depth, -dy, -width)
								: structurePos.offset(-width, -dy, -depth);
						if (!checked.add(anchor.asLong())) {
							continue;
						}
						if (isWholeServerStructurePresent(level, anchor, axis)) {
							return new ResolvedServerStructure(anchor.immutable(), axis);
						}
					}
				}
			}
		}
		return null;
	}

	private static void reconcileStackedServerDuplicates(ServerLevel level) {
		if (level == null || serverAnchor == null) {
			return;
		}
		ResolvedServerStructure keeper = resolveServerStructurePlacement(level, serverAnchor);
		if (keeper == null) {
			return;
		}
		Map<Long, ResolvedServerStructure> stacked = new LinkedHashMap<>();
		stacked.put(keeper.anchor().asLong(), keeper);
		int minY = keeper.anchor().getY() - (ServerStructureBreakSystem.STRUCTURE_HEIGHT * 3);
		int maxY = keeper.anchor().getY() + (ServerStructureBreakSystem.STRUCTURE_HEIGHT * 3);
		for (int y = minY; y <= maxY; y++) {
			BlockPos centerPos = new BlockPos(keeper.anchor().getX(), y, keeper.anchor().getZ());
			if (!level.getBlockState(centerPos).is(ModBlocks.SERVER)) {
				continue;
			}
			ResolvedServerStructure candidate = resolveServerStructurePlacement(level, centerPos);
			if (candidate == null
					|| candidate.axis() != keeper.axis()
					|| candidate.anchor().getX() != keeper.anchor().getX()
					|| candidate.anchor().getZ() != keeper.anchor().getZ()) {
				continue;
			}
			stacked.putIfAbsent(candidate.anchor().asLong(), candidate);
		}
		if (stacked.size() <= 1) {
			rememberServerStructure(keeper.anchor(), keeper.axis());
			return;
		}
		ResolvedServerStructure canonical = keeper;
		BlockPos preferred = resolveBootstrapAnchor(level);
		for (ResolvedServerStructure candidate : stacked.values()) {
			if (isPreferredServerStructureCandidate(level, preferred, candidate, canonical)) {
				canonical = candidate;
			}
		}
		for (ResolvedServerStructure candidate : stacked.values()) {
			if (!candidate.anchor().equals(canonical.anchor())) {
				ServerStructureBreakSystem.clearStructureSilently(level, candidate.anchor(), candidate.axis());
			}
		}
		rememberServerStructure(canonical.anchor(), canonical.axis());
	}

	private static boolean isPreferredServerStructureCandidate(
			ServerLevel level,
			BlockPos preferred,
			ResolvedServerStructure candidate,
			ResolvedServerStructure currentBest
	) {
		if (candidate == null) {
			return false;
		}
		if (currentBest == null) {
			return true;
		}
		int candidateMaxGap = resolveServerStructureMaxSupportGap(level, candidate);
		int bestMaxGap = resolveServerStructureMaxSupportGap(level, currentBest);
		if (candidateMaxGap != bestMaxGap) {
			return candidateMaxGap < bestMaxGap;
		}
		long candidateTotalGap = resolveServerStructureTotalSupportGap(level, candidate);
		long bestTotalGap = resolveServerStructureTotalSupportGap(level, currentBest);
		if (candidateTotalGap != bestTotalGap) {
			return candidateTotalGap < bestTotalGap;
		}
		if (preferred != null) {
			double candidateDistance = candidate.anchor().distSqr(preferred);
			double bestDistance = currentBest.anchor().distSqr(preferred);
			if (candidateDistance != bestDistance) {
				return candidateDistance < bestDistance;
			}
		}
		if (candidate.anchor().getY() != currentBest.anchor().getY()) {
			return candidate.anchor().getY() < currentBest.anchor().getY();
		}
		if (candidate.anchor().getX() != currentBest.anchor().getX()) {
			return candidate.anchor().getX() < currentBest.anchor().getX();
		}
		return candidate.anchor().getZ() < currentBest.anchor().getZ();
	}

	private static int resolveServerStructureMaxSupportGap(ServerLevel level, ResolvedServerStructure structure) {
		if (level == null || structure == null) {
			return Integer.MAX_VALUE;
		}
		int maxGap = 0;
		for (BlockPos pos : ServerStructureBreakSystem.getStructurePositions(structure.anchor(), structure.axis())) {
			if (pos.getY() != structure.anchor().getY()) {
				continue;
			}
			int supportY = resolveBootstrapSupportY(level, pos.getX(), pos.getZ());
			if (supportY == Integer.MIN_VALUE) {
				return Integer.MAX_VALUE;
			}
			maxGap = Math.max(maxGap, Math.max(0, pos.getY() - (supportY + 1)));
		}
		return maxGap;
	}

	private static long resolveServerStructureTotalSupportGap(ServerLevel level, ResolvedServerStructure structure) {
		if (level == null || structure == null) {
			return Long.MAX_VALUE;
		}
		long totalGap = 0L;
		for (BlockPos pos : ServerStructureBreakSystem.getStructurePositions(structure.anchor(), structure.axis())) {
			if (pos.getY() != structure.anchor().getY()) {
				continue;
			}
			int supportY = resolveBootstrapSupportY(level, pos.getX(), pos.getZ());
			if (supportY == Integer.MIN_VALUE) {
				return Long.MAX_VALUE;
			}
			totalGap += Math.max(0, pos.getY() - (supportY + 1));
		}
		return totalGap;
	}

	private static boolean shouldRepairFloatingBootstrapServer(
			ServerLevel level,
			ResolvedServerStructure currentStructure,
			BlockPos bootstrapAnchor
	) {
		if (level == null || currentStructure == null || bootstrapAnchor == null) {
			return false;
		}
		if (currentStructure.anchor().equals(bootstrapAnchor)) {
			return false;
		}
		int currentMaxGap = resolveServerStructureMaxSupportGap(level, currentStructure);
		if (currentMaxGap < LEGACY_FLOATING_SERVER_REPAIR_MIN_GAP) {
			return false;
		}
		int verticalDrop = currentStructure.anchor().getY() - bootstrapAnchor.getY();
		if (verticalDrop < LEGACY_FLOATING_SERVER_REPAIR_MIN_DROP) {
			return false;
		}
		ResolvedServerStructure targetPlacement = new ResolvedServerStructure(bootstrapAnchor.immutable(), currentStructure.axis());
		int targetMaxGap = resolveServerStructureMaxSupportGap(level, targetPlacement);
		if (targetMaxGap == Integer.MAX_VALUE || targetMaxGap >= currentMaxGap) {
			return false;
		}
		long currentTotalGap = resolveServerStructureTotalSupportGap(level, currentStructure);
		long targetTotalGap = resolveServerStructureTotalSupportGap(level, targetPlacement);
		return targetTotalGap < currentTotalGap;
	}

	private static Direction.Axis parseServerAxis(String value) {
		return "x".equalsIgnoreCase(value) ? Direction.Axis.X : Direction.Axis.Z;
	}

	private static String serializeServerAxis(Direction.Axis axis) {
		return axis == Direction.Axis.X ? "x" : "z";
	}

	private static void refreshSharedLaunchBossBar(MinecraftServer server) {
		clearSharedLaunchBossBar();
	}

	private static void clearSharedLaunchBossBar() {
		if (sharedLaunchBossBar != null) {
			sharedLaunchBossBar.removeAllPlayers();
			sharedLaunchBossBar = null;
		}
	}

	private static void ensureSharedBitcoinPopulation(ServerLevel level) {
		if (level == null || serverAnchor == null) {
			return;
		}
		if (!sharedLaunchBitcoinPositionIndexLoaded) {
			rebuildSharedBitcoinPositionIndex(level);
			sharedLaunchBitcoinPositionIndexLoaded = true;
			migrateSharedBitcoinSupply(level);
		}
		pruneSharedBitcoinPositions(level);
		int targetCount = Math.max(0, countSharedPlayers() * SHARED_ACTIVE_ORES_PER_PLAYER);
		if (targetCount <= 0) {
			clearSharedBitcoins(level, true);
			return;
		}
		while (SHARED_BITCOIN_POSITIONS.size() > targetCount) {
			BlockPos pos = SHARED_BITCOIN_POSITIONS.iterator().next();
			SHARED_BITCOIN_POSITIONS.remove(pos);
			if (isSharedBitcoinBlock(level.getBlockState(pos))) {
				restoreStartupLight(level, pos);
			}
		}
		int spawnLimit = Math.max(0, sharedLaunchRequiredBitcoins + SHARED_LAUNCH_EXTRA_BITCOINS);
		int attempts = 0;
		boolean spawnedAny = false;
		while (SHARED_BITCOIN_POSITIONS.size() < targetCount
				&& sharedLaunchBitcoinSpawned < spawnLimit
				&& attempts++ < targetCount * 80) {
			BlockPos candidate = pickSharedBitcoinSpawnPos(level);
			if (candidate == null) {
				break;
			}
			level.setBlock(
					candidate,
					(level.getRandom().nextBoolean() ? ModBlocks.BITCOIN_ORE : ModBlocks.DEEPSLATE_BITCOIN_ORE).defaultBlockState(),
					3
			);
			SHARED_BITCOIN_POSITIONS.add(candidate.immutable());
			sharedLaunchBitcoinSpawned++;
			spawnedAny = true;
			Vec3 center = centerOf(candidate);
			ServerStabilitySystem.emitFeedParticles(level, center.x, center.y - 0.4D, center.z, 8);
		}
		if (spawnedAny) {
			stateDirty = true;
		}
	}

	private static void migrateSharedBitcoinSupply(ServerLevel level) {
		if (sharedLaunchBitcoinSupplyVersion >= SHARED_LAUNCH_SUPPLY_VERSION) {
			return;
		}
		// The previous implementation filled the whole room at once. Remove that
		// legacy field so the new two-per-player supply starts from a clean state.
		clearSharedBitcoins(level, true);
		sharedLaunchBitcoinSpawned = Math.max(0, sharedLaunchCollectedBitcoins);
		sharedLaunchBitcoinSupplyVersion = SHARED_LAUNCH_SUPPLY_VERSION;
		stateDirty = true;
	}

	private static void rebuildSharedBitcoinPositionIndex(ServerLevel level) {
		SHARED_BITCOIN_POSITIONS.clear();
		if (level == null || serverAnchor == null) {
			return;
		}
		BoxGeometry geometry = computeBarrierGeometry(serverAnchor);
		for (int x = geometry.minX + 1; x <= geometry.maxX - 1; x++) {
			for (int y = geometry.floorY + SHARED_ORE_MIN_Y_OFFSET;
					 y <= Math.min(geometry.roofY - 1, geometry.floorY + SHARED_ORE_MAX_Y_OFFSET); y++) {
				for (int z = geometry.minZ + 1; z <= geometry.maxZ - 1; z++) {
					BlockPos pos = new BlockPos(x, y, z);
					if (isSharedBitcoinBlock(level.getBlockState(pos)) && !isIntroReservedPosition(pos)) {
						SHARED_BITCOIN_POSITIONS.add(pos.immutable());
					}
				}
			}
		}
	}

	private static void pruneSharedBitcoinPositions(ServerLevel level) {
		if (level == null) {
			return;
		}
		SHARED_BITCOIN_POSITIONS.removeIf(pos -> pos == null || !isSharedBitcoinBlock(level.getBlockState(pos)));
	}

	private static BlockPos pickSharedBitcoinSpawnPos(ServerLevel level) {
		if (level == null || serverAnchor == null) {
			return null;
		}
		BoxGeometry geometry = computeBarrierGeometry(serverAnchor);
		ServerStructureBounds bounds = resolveServerStructureBounds();
		int minX = geometry.minX + 1;
		int maxX = geometry.maxX - 1;
		int minZ = geometry.minZ + 1;
		int maxZ = geometry.maxZ - 1;
		int minY = geometry.floorY + SHARED_ORE_MIN_Y_OFFSET;
		int maxY = Math.min(geometry.roofY - 1, geometry.floorY + SHARED_ORE_MAX_Y_OFFSET);
		if (maxX < minX || maxZ < minZ || maxY < minY) {
			return null;
		}
		for (int attempt = 0; attempt < 96; attempt++) {
			int x = Mth.nextInt(level.getRandom(), minX, maxX);
			int y = Mth.nextInt(level.getRandom(), minY, maxY);
			int z = Mth.nextInt(level.getRandom(), minZ, maxZ);
			BlockPos candidate = new BlockPos(x, y, z);
			if (!isValidSharedBitcoinSpawn(level, candidate, geometry, bounds)) {
				continue;
			}
			return candidate;
		}
		return null;
	}

	private static boolean isValidSharedBitcoinSpawn(ServerLevel level, BlockPos pos, BoxGeometry geometry, ServerStructureBounds bounds) {
		if (level == null || pos == null || geometry == null || !isInsideFootprint(geometry, pos)) {
			return false;
		}
		if (SHARED_BITCOIN_POSITIONS.contains(pos) || !isStartupSceneAir(level.getBlockState(pos))) {
			return false;
		}
		if (isServerStructureFootprint(pos) || isIntroReservedPosition(pos)) {
			return false;
		}
		return bounds == null || distanceToServerStructureSqr(centerOf(pos), bounds) >= SHARED_ORE_SERVER_BUFFER * SHARED_ORE_SERVER_BUFFER;
	}

	private static boolean isIntroReservedPosition(BlockPos pos) {
		if (pos == null || serverAnchor == null) {
			return false;
		}
		BoxGeometry barrierGeometry = computeBarrierGeometry(serverAnchor);
		for (PlayerSceneState state : PLAYER_STATES.values()) {
			if (state == null) {
				continue;
			}
			SlotDefinition slot = resolveSlotDefinition(barrierGeometry, state.slotIndex);
			if (slot == null) {
				continue;
			}
			if (slot.spawnFloorPos.equals(pos)
					|| slot.spawnFloorPos.above().equals(pos)
					|| slot.oreSupportPos.equals(pos)
					|| slot.orePos.equals(pos)) {
				return true;
			}
		}
		return false;
	}

	private static void clearSharedBitcoins(ServerLevel level, boolean removeBlocks) {
		if (level == null || serverAnchor == null) {
			SHARED_BITCOIN_POSITIONS.clear();
			return;
		}
		BoxGeometry geometry = computeBarrierGeometry(serverAnchor);
		for (int x = geometry.minX + 1; x <= geometry.maxX - 1; x++) {
			for (int y = geometry.floorY + SHARED_ORE_MIN_Y_OFFSET; y <= Math.min(geometry.roofY - 1, geometry.floorY + SHARED_ORE_MAX_Y_OFFSET); y++) {
				for (int z = geometry.minZ + 1; z <= geometry.maxZ - 1; z++) {
					BlockPos pos = new BlockPos(x, y, z);
					if (!isSharedBitcoinBlock(level.getBlockState(pos)) || isIntroReservedPosition(pos)) {
						continue;
					}
					if (removeBlocks) {
						restoreStartupLight(level, pos);
					}
				}
			}
		}
		SHARED_BITCOIN_POSITIONS.clear();
	}

	private static boolean isSharedBitcoinBlock(net.minecraft.world.level.block.state.BlockState state) {
		return state != null && (state.is(ModBlocks.BITCOIN_ORE) || state.is(ModBlocks.DEEPSLATE_BITCOIN_ORE));
	}

	private static double distanceToServerStructureSqr(Vec3 point, ServerStructureBounds bounds) {
		if (point == null || bounds == null) {
			return Double.MAX_VALUE;
		}
		double dx = axisDistance(point.x, bounds.minX, bounds.maxX);
		double dy = axisDistance(point.y, serverAnchor.getY(), serverAnchor.getY() + ServerStructureBreakSystem.STRUCTURE_HEIGHT);
		double dz = axisDistance(point.z, bounds.minZ, bounds.maxZ);
		return dx * dx + dy * dy + dz * dz;
	}

	private static void clearSceneMobs(ServerLevel level) {
		if (level == null || serverAnchor == null) {
			return;
		}
		BoxGeometry geometry = computeOuterBoxGeometry(serverAnchor);
		AABB sceneBounds = new AABB(
				geometry.minX,
				geometry.floorY,
				geometry.minZ,
				geometry.maxX + 1.0D,
				geometry.roofY + 1.0D,
				geometry.maxZ + 1.0D
		);
		for (Mob mob : level.getEntitiesOfClass(Mob.class, sceneBounds)) {
			mob.discard();
		}
	}

	private static void clearSceneExperienceOrbs(ServerLevel level) {
		if (level == null || serverAnchor == null) {
			return;
		}
		BoxGeometry geometry = computeOuterBoxGeometry(serverAnchor);
		for (ExperienceOrb orb : level.getEntitiesOfClass(ExperienceOrb.class, new AABB(
				geometry.minX,
				geometry.floorY,
				geometry.minZ,
				geometry.maxX + 1.0D,
				geometry.roofY + 1.0D,
				geometry.maxZ + 1.0D
		))) {
			orb.discard();
		}
	}

	private static void ensureIntroPlayerState(ServerPlayer player, PlayerSceneState state, SlotDefinition slot) {
		if (player == null || state == null || slot == null || serverAnchor == null) {
			return;
		}
		if (!player.level().dimension().equals(Level.OVERWORLD)) {
			MinecraftServer server = player.level().getServer();
			ServerLevel overworld = server == null ? null : server.overworld();
			teleportPlayer(player, overworld, slot.spawnPos, slot.yaw);
		}
		applyPrivateScenePresentation(player, state, GameType.SURVIVAL);
	}

	private static void ensureGuidedPlayerState(ServerPlayer player, PlayerSceneState state, SlotDefinition slot) {
		if (player == null || state == null || slot == null || serverAnchor == null) {
			return;
		}
		ServerLevel level = player.level() instanceof ServerLevel serverLevel ? serverLevel : null;
		if (level == null) {
			return;
		}
		BoxGeometry barrierGeometry = computeBarrierGeometry(resolveServerAnchor(level));
		Vec3 target = slot.spawnPos;
		if (!player.level().dimension().equals(Level.OVERWORLD) || !isInsideFootprint(barrierGeometry, player.blockPosition())) {
			teleportPlayer(player, level, target, slot.yaw);
		}
		applyPrivateScenePresentation(player, state, GameType.SURVIVAL);
	}

	/** Restores the same escaped tutorial coin after reconnecting, never a duplicate. */
	private static void restoreGuidedBitcoinAfterReconnect(ServerPlayer player, PlayerSceneState state) {
		if (player == null || state == null || state.phase != PlayerPhase.GUIDED_TO_SERVER || hasBitcoin(player)) {
			return;
		}
		ServerLevel level = player.level() instanceof ServerLevel serverLevel ? serverLevel : null;
		if (level == null || serverAnchor == null) {
			return;
		}
		if (state.escapedGuidedOfferingId != null) {
			ItemEntity escapedCoin = findEscapedGuidedBitcoin(level, state.escapedGuidedOfferingId);
			if (escapedCoin != null) {
				restoreEscapedGuidedBitcoinRoute(state, escapedCoin);
				stateDirty = true;
				return;
			}
			clearEscapedGuidedBitcoinState(state);
		}

		BoxGeometry scene = computeBarrierGeometry(serverAnchor);
		AABB sceneBounds = new AABB(
				scene.minX,
				scene.floorY,
				scene.minZ,
				scene.maxX + 1.0D,
				scene.roofY + 1.0D,
				scene.maxZ + 1.0D
		);
		ItemEntity droppedCoin = level.getEntitiesOfClass(ItemEntity.class, sceneBounds, itemEntity ->
				itemEntity != null
						&& itemEntity.isAlive()
						&& !itemEntity.isRemoved()
						&& itemEntity.getOwner() instanceof ServerPlayer owner
						&& owner.getUUID().equals(player.getUUID())
						&& itemEntity.getItem().is(ModItems.BITCOIN)
		).stream().findFirst().orElse(null);
		if (droppedCoin != null) {
			// After two recoveries this is an ordinary vanilla throw. Leave it alone:
			// the offering loop will absorb a successful throw, or the owner can pick it up.
			if (state.guidedBitcoinEscapeCount >= GUIDED_OFFERING_MAX_ESCAPES
					&& state.lockedGuidedBitcoinSlot == NO_LOCKED_GUIDED_BITCOIN_SLOT) {
				return;
			}
			state.escapedGuidedOfferingId = droppedCoin.getUUID();
			restoreEscapedGuidedBitcoinRoute(state, droppedCoin);
			stateDirty = true;
			return;
		}
		giveOrDrop(player, new ItemStack(ModItems.BITCOIN));
		resetGuidanceRoute(state);
		state.guidanceNarrationGateTick = 0L;
		state.nextGuidanceTick = 0L;
		state.nextGuidanceVoiceTick = 0L;
		state.nextGuidanceEarliestTick = 0L;
		state.lastGuidanceStateKey = "";
		if (state.lockedGuidedBitcoinSlot != NO_LOCKED_GUIDED_BITCOIN_SLOT) {
			keepLockedGuidedBitcoinInSlot(player, state, null);
		}
		stateDirty = true;
	}

	private static void restoreEscapedGuidedBitcoinRoute(PlayerSceneState state, ItemEntity itemEntity) {
		if (state == null || itemEntity == null) {
			return;
		}
		itemEntity.setUnlimitedLifetime();
		itemEntity.setNoGravity(false);
		itemEntity.setDeltaMovement(Vec3.ZERO);
		state.escapedGuidedOfferingId = itemEntity.getUUID();
		state.escapedGuidedOfferingTarget = itemEntity.position();
		state.escapedGuidedOfferingLanded = true;
		state.guidanceNarrationGateTick = 0L;
		state.nextGuidanceTick = 0L;
		state.nextGuidanceVoiceTick = 0L;
		state.nextGuidanceEarliestTick = 0L;
		state.lastGuidanceStateKey = "";
		resetGuidanceRoute(state);
	}

	private static void ensureWaitingStartPlayerState(ServerPlayer player, PlayerSceneState state, SlotDefinition slot) {
		if (player == null || state == null || slot == null) {
			return;
		}
		Vec3 target = slot.spawnPos;
		if (!player.level().dimension().equals(Level.OVERWORLD) || player.distanceToSqr(target.x, target.y, target.z) > 225.0D) {
			MinecraftServer server = player.level().getServer();
			ServerLevel overworld = server == null ? null : server.overworld();
			teleportPlayer(player, overworld, target, slot.yaw);
		}
		applyPrivateScenePresentation(player, state, GameType.ADVENTURE);
	}

	private static void ensureRestoringPlayerState(ServerPlayer player, PlayerSceneState state) {
		if (player == null || state == null) {
			return;
		}
		if (!state.sharedVisionRestored) {
			applyPrivateScenePresentation(player, state, GameType.SURVIVAL);
		}
	}

	private static void applyPrivateScenePresentation(ServerPlayer player, PlayerSceneState state, GameType targetMode) {
		if (player == null || state == null || targetMode == null || state.seasonStartPresentationApplied) {
			return;
		}
		player.setSilent(true);
		setSeasonStartGameMode(player, targetMode);
		INTRO_BLINDNESS.ensure(player, 0);
		clearLegacyIntroInvisibility(player);
		removeLegacyIntroTool(player);
		state.seasonStartPresentationApplied = true;
	}

	private static void applySharedPlayerState(ServerPlayer player) {
		clearLegacyIntroInvisibility(player);
		INTRO_BLINDNESS.clear(player);
		player.setSilent(false);
		setSeasonStartGameMode(player, GameType.SURVIVAL);
	}

	private static void applyFreeState(ServerPlayer player) {
		applyFreeState(player, true);
	}

	private static void applyFreeState(ServerPlayer player, boolean clearDarkness) {
		if (player == null) {
			return;
		}
		clearLegacyIntroInvisibility(player);
		INTRO_BLINDNESS.clear(player);
		if (clearDarkness) {
			player.removeEffect(MobEffects.DARKNESS);
		}
		player.setSilent(false);
		setSeasonStartGameMode(player, GameType.SURVIVAL);
		removeLegacyIntroTool(player);
	}

	private static void setSeasonStartGameMode(ServerPlayer player, GameType targetMode) {
		if (player != null && targetMode != null && player.gameMode.getGameModeForPlayer() != targetMode) {
			player.setGameMode(targetMode);
		}
	}

	private static void clearLegacyIntroInvisibility(ServerPlayer player) {
		if (player == null) {
			return;
		}
		ServerAbsoluteInvisibilitySystem.deactivate(player);
		player.removeEffect(MobEffects.INVISIBILITY);
	}

	private static void applyStateForPhase(ServerPlayer player, PlayerSceneState state) {
		if (state == null || player == null) {
			return;
		}
		if (state.phase == PlayerPhase.RESTORING) {
			ensureRestoringPlayerState(player, state);
			return;
		}
		if (state.phase == PlayerPhase.SHARED) {
			applySharedPlayerState(player);
			return;
		}
		if (state.phase == PlayerPhase.FREE) {
			applyFreeState(player);
		}
	}

	private static boolean isInPrivateIntroPhase(ServerPlayer player) {
		PlayerSceneState state = player == null ? null : PLAYER_STATES.get(player.getUUID());
		return active
				&& state != null
				&& (state.phase == PlayerPhase.WAITING_START
				|| state.phase == PlayerPhase.ISOLATED
				|| state.phase == PlayerPhase.GUIDED_TO_SERVER
				|| state.phase == PlayerPhase.RESTORING);
	}

	private static void syncPrivatePlayerProfiles(MinecraftServer server) {
		if (server == null) {
			return;
		}
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		Set<UUID> onlineIds = new HashSet<>();
		for (ServerPlayer player : players) {
			if (isSeasonStartEligiblePlayer(player)) {
				onlineIds.add(player.getUUID());
			}
		}
		HIDDEN_PLAYER_PROFILE_PAIRS.removeIf(pair -> !onlineIds.contains(pair.viewerId) || !onlineIds.contains(pair.subjectId));
		for (ServerPlayer viewer : players) {
			if (!isSeasonStartEligiblePlayer(viewer)) {
				continue;
			}
			for (ServerPlayer subject : players) {
				if (!isSeasonStartEligiblePlayer(subject) || viewer == subject) {
					continue;
				}
				PlayerVisibilityPair pair = new PlayerVisibilityPair(viewer.getUUID(), subject.getUUID());
					if (shouldHidePlayerFrom(viewer, subject)) {
						if (HIDDEN_PLAYER_PROFILE_PAIRS.add(pair)) {
							viewer.connection.send(new ClientboundPlayerInfoRemovePacket(List.of(subject.getUUID())));
							viewer.connection.send(ClientboundTrackedWaypointPacket.removeWaypoint(subject.getUUID()));
						}
				} else if (HIDDEN_PLAYER_PROFILE_PAIRS.remove(pair)) {
					viewer.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(subject)));
				}
			}
		}
		refreshPrivateIntroEntityTracking(server);
	}

	/**
	 * ChunkMap prevents new tracking for hidden items. This additionally removes items that were
	 * tracked before the player entered their personal scene, so no dropped item can leak through.
	 */
	private static void refreshPrivateIntroEntityTracking(MinecraftServer server) {
		if (server == null || serverAnchor == null) {
			return;
		}
		for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
			if (!isSeasonStartEligiblePlayer(viewer) || !isInPrivateIntroPhase(viewer)
					|| viewer.connection == null || !(viewer.level() instanceof ServerLevel level)) {
				continue;
			}
			// Force ChunkMap to discard any stale entity pair through the same tracking predicate.
			level.getChunkSource().move(viewer);
			BoxGeometry geometry = computeOuterBoxGeometry(resolveServerAnchor(level));
			AABB sceneBounds = new AABB(
					geometry.minX,
					geometry.floorY,
					geometry.minZ,
					geometry.maxX + 1.0D,
					geometry.roofY + 1.0D,
					geometry.maxZ + 1.0D
			);
			List<ItemEntity> hiddenItems = level.getEntitiesOfClass(
					ItemEntity.class,
					sceneBounds,
					itemEntity -> itemEntity.getOwner() != viewer || !isGuidedBitcoinOffering(itemEntity)
			);
			if (hiddenItems.isEmpty()) {
				continue;
			}
			int[] hiddenIds = new int[hiddenItems.size()];
			for (int index = 0; index < hiddenItems.size(); index++) {
				hiddenIds[index] = hiddenItems.get(index).getId();
			}
			viewer.connection.send(new ClientboundRemoveEntitiesPacket(hiddenIds));
		}
	}

	/**
	 * Private-intro players are deliberately removed from ChunkMap tracking. Changing only the
	 * phase leaves a stationary viewer with no reason for vanilla to retry pairing the entity,
	 * so make the normal tracker reevaluate every shared viewer immediately after the light cue.
	 */
	private static void refreshSharedPlayerEntityTracking(MinecraftServer server) {
		if (server == null) {
			return;
		}
		for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
			if (!isSeasonStartEligiblePlayer(viewer) || !isInSharedPhase(viewer) || !(viewer.level() instanceof ServerLevel level)) {
				continue;
			}
			level.getChunkSource().move(viewer);
		}
	}

	/** Removes the now-retired tutorial pickaxe from scenes that started before this update. */
	private static void removeLegacyIntroTool(ServerPlayer player) {
		if (player == null || !LEGACY_INTRO_TOOL_PURGED_PLAYERS.add(player.getUUID())) {
			return;
		}
		Inventory inventory = player.getInventory();
		if (inventory == null) {
			return;
		}
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (isLegacyIntroTool(stack)) {
				inventory.setItem(slot, ItemStack.EMPTY);
			}
		}
	}

	private static boolean isLegacyIntroTool(ItemStack stack) {
		if (stack == null || stack.isEmpty() || !stack.is(ModItems.SPECIAL_PICKAXE)) {
			return false;
		}
		Component customName = stack.get(DataComponents.CUSTOM_NAME);
		return customName != null && "Пусковой инструмент".equals(customName.getString());
	}

	private static boolean hasBitcoin(ServerPlayer player) {
		Inventory inventory = player == null ? null : player.getInventory();
		if (inventory == null) {
			return false;
		}
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			if (inventory.getItem(slot).is(ModItems.BITCOIN)) {
				return true;
			}
		}
		return false;
	}

	private static int findBitcoinSlot(ServerPlayer player) {
		Inventory inventory = player == null ? null : player.getInventory();
		if (inventory == null) {
			return NO_LOCKED_GUIDED_BITCOIN_SLOT;
		}
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			if (inventory.getItem(slot).is(ModItems.BITCOIN)) {
				return slot;
			}
		}
		return NO_LOCKED_GUIDED_BITCOIN_SLOT;
	}

	private static void keepLockedGuidedBitcoinInSlot(ServerPlayer player, PlayerSceneState state, ItemStack returningStack) {
		if (player == null || state == null || state.lockedGuidedBitcoinSlot == NO_LOCKED_GUIDED_BITCOIN_SLOT) {
			return;
		}
		Inventory inventory = player.getInventory();
		int lockedSlot = state.lockedGuidedBitcoinSlot;
		if (lockedSlot < 0 || lockedSlot >= inventory.getContainerSize()) {
			state.lockedGuidedBitcoinSlot = NO_LOCKED_GUIDED_BITCOIN_SLOT;
			return;
		}
		ItemStack lockedStack = inventory.getItem(lockedSlot);
		if (!lockedStack.is(ModItems.BITCOIN)) {
			int bitcoinSlot = findBitcoinSlot(player);
			if (bitcoinSlot != NO_LOCKED_GUIDED_BITCOIN_SLOT && bitcoinSlot != lockedSlot) {
				ItemStack displaced = inventory.getItem(lockedSlot);
				inventory.setItem(lockedSlot, inventory.getItem(bitcoinSlot));
				inventory.setItem(bitcoinSlot, displaced);
				lockedStack = inventory.getItem(lockedSlot);
			}
		}
		if (!lockedStack.is(ModItems.BITCOIN) && returningStack != null && returningStack.is(ModItems.BITCOIN)) {
			inventory.setItem(lockedSlot, returningStack.copy());
		}
		inventory.setChanged();
		player.inventoryMenu.broadcastFullState();
		player.inventoryMenu.sendAllDataToRemote();
		if (player.containerMenu != player.inventoryMenu) {
			player.containerMenu.broadcastFullState();
		}
	}

	private static void giveOrDrop(ServerPlayer player, ItemStack stack) {
		if (player == null || stack == null || stack.isEmpty()) {
			return;
		}
		if (!player.getInventory().add(stack)) {
			player.drop(stack, false);
		}
	}

	private static void spawnLightOnlineParticles(ServerLevel level) {
		if (level == null || serverAnchor == null) {
			return;
		}
		level.sendParticles(ParticleTypes.END_ROD, serverAnchor.getX() + 0.5D, serverAnchor.getY() + 1.2D, serverAnchor.getZ() + 0.5D, 80, 3.5D, 2.0D, 3.5D, 0.03D);
		level.playSound(null, serverAnchor, SoundEvents.BEACON_ACTIVATE, SoundSource.AMBIENT, 1.1F, 1.0F);
	}

	private static void teleportPlayer(ServerPlayer player, ServerLevel level, Vec3 target, float yaw) {
		if (player == null || level == null || target == null) {
			return;
		}
		if (player.level().dimension().equals(level.dimension())) {
			player.connection.teleport(target.x, target.y, target.z, yaw, 0.0F);
			return;
		}
		player.teleportTo(level, target.x, target.y, target.z, ABSOLUTE_TELEPORT, yaw, 0.0F, false);
	}

	private static boolean isServerStructurePresent(ServerLevel level, BlockPos anchor) {
		return resolveKnownServerStructureAxis(level, anchor) != null;
	}

	private static BlockPos resolveBootstrapAnchor(ServerLevel level) {
		if (level == null) {
			return new BlockPos(0, 64, 0);
		}
		LevelData.RespawnData respawnData = level.getRespawnData();
		BlockPos sharedSpawn = respawnData == null ? null : respawnData.pos();
		int requestedX = sharedSpawn == null ? 0 : sharedSpawn.getX();
		int requestedZ = sharedSpawn == null ? 0 : sharedSpawn.getZ();
		int centerX = centerOfChunk(requestedX);
		int centerZ = centerOfChunk(requestedZ);
		int maxSupportY = Integer.MIN_VALUE;
		for (int x = -ServerStructureBreakSystem.STRUCTURE_HALF_WIDTH; x <= ServerStructureBreakSystem.STRUCTURE_HALF_WIDTH; x++) {
			for (int z = -ServerStructureBreakSystem.STRUCTURE_HALF_DEPTH; z <= ServerStructureBreakSystem.STRUCTURE_HALF_DEPTH; z++) {
				maxSupportY = Math.max(maxSupportY, resolveBootstrapSupportY(level, centerX + x, centerZ + z));
			}
		}
		int anchorY = maxSupportY == Integer.MIN_VALUE ? 64 : maxSupportY + 1;
		if (anchorY < level.getMinY() + 4) {
			anchorY = 64;
		}
		int maxAnchorY = level.getMaxY() - ServerStructureBreakSystem.STRUCTURE_HEIGHT - 2;
		if (anchorY > maxAnchorY) {
			anchorY = maxAnchorY;
		}
		// The starter structure is centred in a chunk. Together with the aligned
		// outer geometry below this makes the sealed scene a stable chunk grid for
		// every newly generated world, irrespective of vanilla's spawn coordinate.
		return new BlockPos(centerX, anchorY, centerZ);
	}

	private static int centerOfChunk(int coordinate) {
		return Math.floorDiv(coordinate, 16) * 16 + 8;
	}

	private static int resolveBootstrapSupportY(ServerLevel level, int x, int z) {
		if (level == null) {
			return Integer.MIN_VALUE;
		}
		int surfaceTop = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		int solidY = Math.min(level.getMaxY() - 1, Math.max(level.getMinY(), surfaceTop - 1));
		while (solidY > level.getMinY()) {
			BlockState state = level.getBlockState(new BlockPos(x, solidY, z));
			if (state.blocksMotion() && !isBootstrapTransientSupportState(state)) {
				break;
			}
			solidY--;
		}
		BlockState supportState = level.getBlockState(new BlockPos(x, solidY, z));
		return supportState.blocksMotion() && !isBootstrapTransientSupportState(supportState) ? solidY : Integer.MIN_VALUE;
	}

	private static boolean isBootstrapTransientSupportState(BlockState state) {
		return state != null
				&& (state.is(ModBlocks.SERVER)
				|| state.is(Blocks.BARRIER)
				|| isStartupShellBlock(state));
	}

	private static boolean isBootstrapVolumeClear(ServerLevel level, BlockPos anchor, Direction.Axis axis) {
		if (level == null || anchor == null || axis == null) {
			return false;
		}
		for (BlockPos pos : ServerStructureBreakSystem.getStructurePositions(anchor, axis)) {
			BlockState state = level.getBlockState(pos);
			if (!state.isAir() && !state.canBeReplaced()) {
				return false;
			}
		}
		return true;
	}

	private static BlockPos resolveServerAnchor(ServerLevel level) {
		if (level == null) {
			return serverAnchor;
		}
		ResolvedServerStructure resolved = discoverLiveServerStructure(level);
		if (resolved != null) {
			rememberServerStructure(resolved.anchor(), resolved.axis());
			return serverAnchor;
		}
		if (serverAnchor == null) {
			rememberServerStructure(resolveBootstrapAnchor(level), Direction.Axis.Z);
		}
		return serverAnchor;
	}

	private static ResolvedServerStructure discoverLiveServerStructure(ServerLevel level) {
		if (level == null) {
			return serverAnchor == null ? null : new ResolvedServerStructure(serverAnchor, serverStructureAxis);
		}
		if (serverAnchor != null) {
			if (isWholeServerStructurePresent(level, serverAnchor, serverStructureAxis)) {
				return new ResolvedServerStructure(serverAnchor, serverStructureAxis);
			}
			ResolvedServerStructure current = resolveServerStructurePlacement(level, serverAnchor);
			if (current != null) {
				return current;
			}
		}
		BlockPos preferred = serverAnchor != null ? serverAnchor : resolveBootstrapAnchor(level);
		ResolvedServerStructure best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Entity entity : level.getAllEntities()) {
			if (!ServerStructureBreakSystem.isServerStructureDisplay(entity)) {
				continue;
			}
			var anchor = ServerStructureBreakSystem.getServerStructureDisplayAnchor(entity);
			if (anchor.isEmpty()) {
				continue;
			}
			ResolvedServerStructure candidate = resolveServerStructurePlacement(level, anchor.get());
			if (candidate == null) {
				continue;
			}
			double distance = candidate.anchor().distSqr(preferred);
			if (best == null
					|| distance < bestDistance
					|| (distance == bestDistance && isPreferredServerStructureCandidate(level, preferred, candidate, best))) {
				best = candidate;
				bestDistance = distance;
			}
		}
		return best;
	}

	private static ResolvedServerStructure discoverExistingServerStructure(ServerLevel level, BlockPos primaryAnchor, BlockPos bootstrapAnchor) {
		if (level == null) {
			return null;
		}
		ResolvedServerStructure discovered = discoverLiveServerStructure(level);
		if (discovered != null) {
			return discovered;
		}
		BlockPos around = primaryAnchor != null ? primaryAnchor : bootstrapAnchor;
		discovered = discoverServerStructureNearSurface(level, around, EXISTING_SERVER_SCAN_RADIUS);
		if (discovered != null) {
			return discovered;
		}
		if (bootstrapAnchor != null && (around == null || !bootstrapAnchor.equals(around))) {
			discovered = discoverServerStructureNearSurface(level, bootstrapAnchor, EXISTING_SERVER_SCAN_RADIUS);
			if (discovered != null) {
				return discovered;
			}
		}
		return null;
	}

	private static ResolvedServerStructure discoverServerStructureNearSurface(ServerLevel level, BlockPos center, int radius) {
		if (level == null || center == null || radius < 0) {
			return null;
		}
		ResolvedServerStructure best = null;
		double bestDistance = Double.MAX_VALUE;
		Set<Long> seenAnchors = new HashSet<>();
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				int x = center.getX() + dx;
				int z = center.getZ() + dz;
				int chunkX = x >> 4;
				int chunkZ = z >> 4;
				if (!level.hasChunk(chunkX, chunkZ)) {
					continue;
				}
				int surfaceTop = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
				int minY = Math.max(level.getMinY(), surfaceTop - EXISTING_SERVER_SCAN_VERTICAL_MARGIN);
				int maxY = Math.min(level.getMaxY() - 1, surfaceTop + EXISTING_SERVER_SCAN_VERTICAL_MARGIN);
				for (int y = maxY; y >= minY; y--) {
					BlockPos pos = new BlockPos(x, y, z);
					if (!level.getBlockState(pos).is(ModBlocks.SERVER)) {
						continue;
					}
					ResolvedServerStructure candidate = resolveServerStructurePlacement(level, pos);
					if (candidate == null) {
						break;
					}
					if (!seenAnchors.add(candidate.anchor().asLong())) {
						break;
					}
					double distance = candidate.anchor().distSqr(center);
					if (best == null
							|| distance < bestDistance
							|| (distance == bestDistance && isPreferredServerStructureCandidate(level, center, candidate, best))) {
						best = candidate;
						bestDistance = distance;
					}
					break;
				}
			}
		}
		return best;
	}

	private static void setDefaultSpawn(ServerLevel level) {
		BlockPos anchor = resolveServerAnchor(level);
		if (level == null || anchor == null) {
			return;
		}
		BlockPos spawn = anchor.offset(0, 0, get().barrierHalfDepth - 2);
		MinecraftServer server = level.getServer();
		if (server != null) {
			server.setRespawnData(LevelData.RespawnData.of(level.dimension(), spawn, 180.0F, 0.0F));
		}
	}

	private static int allocateSlotIndex(BoxGeometry geometry) {
		Set<Integer> used = new LinkedHashSet<>();
		for (PlayerSceneState state : PLAYER_STATES.values()) {
			used.add(state.slotIndex);
		}
		List<SlotDefinition> slots = collectSlotDefinitions(geometry);
		for (int index = 0; index < slots.size(); index++) {
			if (!used.contains(index)) {
				return index;
			}
		}
		return Math.max(0, PLAYER_STATES.size() % Math.max(1, slots.size()));
	}

	private static SlotDefinition resolveSlotDefinition(BoxGeometry geometry, int slotIndex) {
		List<SlotDefinition> slots = collectSlotDefinitions(geometry);
		if (slots.isEmpty()) {
			return null;
		}
		return slots.get(Math.floorMod(slotIndex, slots.size()));
	}

	private static List<SlotDefinition> collectSlotDefinitions(BoxGeometry geometry) {
		int inset = 3;
		int spacing = get().playerSlotSpacing;
		int floorY = geometry.floorY;
		List<SlotDefinition> slots = new ArrayList<>();
		for (int x = geometry.minX + inset; x <= geometry.maxX - inset; x += spacing) {
			slots.add(createSlot(slots.size(), floorY, x, geometry.minZ + inset, Direction.SOUTH));
		}
		for (int z = geometry.minZ + inset + spacing; z <= geometry.maxZ - inset; z += spacing) {
			slots.add(createSlot(slots.size(), floorY, geometry.maxX - inset, z, Direction.WEST));
		}
		for (int x = geometry.maxX - inset - spacing; x >= geometry.minX + inset; x -= spacing) {
			slots.add(createSlot(slots.size(), floorY, x, geometry.maxZ - inset, Direction.NORTH));
		}
		for (int z = geometry.maxZ - inset - spacing; z >= geometry.minZ + inset + spacing; z -= spacing) {
			slots.add(createSlot(slots.size(), floorY, geometry.minX + inset, z, Direction.EAST));
		}
		return slots;
	}

	private static SlotDefinition createSlot(int slotIndex, int floorY, int x, int z, Direction facing) {
		BlockPos floor = new BlockPos(x, floorY, z);
		int[] lateralOffsets = {-4, -2, 0, 2, 4};
		int lateral = lateralOffsets[Math.floorMod(slotIndex * 3 + 1, lateralOffsets.length)];
		Direction lateralDirection = lateral >= 0 ? facing.getClockWise() : facing.getCounterClockWise();
		BlockPos oreSupportPos = floor.relative(facing, 8).relative(lateralDirection, Math.abs(lateral)).above();
		BlockPos orePos = oreSupportPos.above();
		Vec3 spawnPos = new Vec3(x + 0.5D, floorY + 1.0D, z + 0.5D);
		// The ore remains safely inside the room, but the player spawns facing the
		// opposite direction so the first instruction is a real navigation beat.
		return new SlotDefinition(floor, oreSupportPos, orePos, spawnPos, facing.getOpposite().toYRot());
	}

	private static BoxGeometry computeOuterBoxGeometry(BlockPos anchor) {
		int minimumSide = Math.max(get().boxHalfWidth, get().boxHalfDepth) * 2 + 1;
		int chunkSide = Math.max(1, (minimumSide + 15) / 16);
		// An odd number keeps the server and its barrier room centred. With the
		// current 71-block request this deliberately yields a 5x5 chunk box.
		if ((chunkSide & 1) == 0) {
			chunkSide++;
		}
		int sideLength = chunkSide * 16;
		int minX = (Math.floorDiv(anchor.getX(), 16) - chunkSide / 2) * 16;
		int minZ = (Math.floorDiv(anchor.getZ(), 16) - chunkSide / 2) * 16;
		int barrierFloorY = anchor.getY() - 1;
		// The visible world-generation projection needs its own floor below the
		// barrier-room floor.  Keeping it just below the barrier's five-block base
		// makes the animation visible again without restoring the former 71-block
		// deep outer cube.  boxHeight remains the headroom above the room floor.
		int floorY = barrierFloorY - BARRIER_FLOOR_DEPTH - 1;
		int roofY = barrierFloorY + get().boxHeight - 1;
		return new BoxGeometry(
				minX,
				minX + sideLength - 1,
				minZ,
				minZ + sideLength - 1,
				floorY,
				roofY
		);
	}

	private static BoxGeometry computeBarrierGeometry(BlockPos anchor) {
		int halfWidth = get().barrierHalfWidth;
		int halfDepth = get().barrierHalfDepth;
		int floorY = anchor.getY() - 1;
		return new BoxGeometry(
				anchor.getX() - halfWidth,
				anchor.getX() + halfWidth,
				anchor.getZ() - halfDepth,
				anchor.getZ() + halfDepth,
				floorY,
				floorY + resolveBarrierBoxHeight()
		);
	}

	private static int resolveBarrierBoxHeight() {
		// The barrier room must remain entirely inside the black cube. Its old
		// width-derived height let the roof pierce the outer shell as a square ring.
		int clearanceToOuterRoof = 5;
		int maximumContainedHeight = Math.max(1, get().boxHeight - clearanceToOuterRoof);
		return Mth.clamp(get().barrierHeight, 1, maximumContainedHeight);
	}

	private static Path getStatePath(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve(STATE_FILE_NAME);
	}

	private static Path getWorldRevealSnapshotPath(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve(WORLD_REVEAL_SNAPSHOT_FILE_NAME);
	}

	/**
	 * The vanilla generator only exposes base terrain, not trees or surface
	 * features. Keep the exact pre-scene states in a compact sidecar instead.
	 * Reading the file is isolated from world access and is therefore safe to do
	 * off-thread; registry conversion is throttled back onto server ticks below.
	 */
	private static void beginWorldRevealSnapshotLoad(MinecraftServer server) {
		if (server == null || (!active && !worldRevealActive) || worldRevealSnapshotLoadAttempted) {
			return;
		}
		worldRevealSnapshotLoadAttempted = true;
		Path path = getWorldRevealSnapshotPath(server);
		if (!Files.isRegularFile(path)) {
			return;
		}
		worldRevealSnapshotLoadFuture = CompletableFuture.supplyAsync(() -> {
			try {
				return readWorldRevealSnapshot(path);
			} catch (IOException exception) {
				throw new IllegalStateException("Failed to read season-start terrain snapshot " + path, exception);
			}
		});
	}

	private static boolean tickWorldRevealSnapshotLoad(MinecraftServer server) {
		if (server == null) {
			return true;
		}
		if (worldRevealSnapshotLoadFuture != null) {
			if (!worldRevealSnapshotLoadFuture.isDone()) {
				return false;
			}
			PersistedWorldRevealSnapshot snapshot;
			try {
				snapshot = worldRevealSnapshotLoadFuture.join();
			} catch (RuntimeException exception) {
				Lg2.LOGGER.error("Season-start terrain snapshot could not be loaded; legacy recovery will use base terrain only.", exception);
				worldRevealSnapshotLoadFuture = null;
				return true;
			}
			worldRevealSnapshotLoadFuture = null;
			if (snapshot == null || serverAnchor == null || snapshot.anchor() != serverAnchor.asLong()) {
				Lg2.LOGGER.warn("Ignoring a season-start terrain snapshot with a different server anchor.");
				return true;
			}
			worldRevealSnapshotLoadTask = new WorldRevealSnapshotLoadTask(snapshot);
		}
		if (worldRevealSnapshotLoadTask == null) {
			return true;
		}
		WorldRevealSnapshotLoadTask task = worldRevealSnapshotLoadTask;
		int budget = SCENE_SNAPSHOT_BATCH_BLOCKS;
		while (budget-- > 0 && task.hasNext()) {
			PersistedSnapshotBlock entry = task.next();
			BlockState state = task.stateAt(entry.stateIndex());
			if (state == null || state.isAir()) {
				continue;
			}
			BlockPos pos = BlockPos.of(entry.pos());
			if (task.readingBoundary()) {
				WORLD_REVEAL_BOUNDARY_TARGET_STATES.put(pos.asLong(), state);
			} else {
				WORLD_REVEAL_TERRAIN.add(new TerrainPlacement(pos, state));
			}
		}
		if (task.hasNext()) {
			return false;
		}
		rebuildWorldRevealSnapshotIndexesFromTerrain();
		worldRevealSnapshotLoadTask = null;
		Lg2.LOGGER.info("Loaded exact season-start terrain snapshot ({} blocks, {} boundary blocks).",
				WORLD_REVEAL_TERRAIN.size(), WORLD_REVEAL_BOUNDARY_TARGET_STATES.size());
		return true;
	}

	private static boolean isWorldRevealSnapshotLoading() {
		return worldRevealSnapshotLoadFuture != null || worldRevealSnapshotLoadTask != null;
	}

	private static boolean hasWorldRevealSnapshot() {
		return !WORLD_REVEAL_TERRAIN.isEmpty();
	}

	private static void writeWorldRevealSnapshotAsync(MinecraftServer server, SceneBuildTask task) {
		if (server == null || task == null || task.snapshotAccumulator == null) {
			return;
		}
		PersistedWorldRevealSnapshot snapshot = task.snapshotAccumulator.freeze(task.anchor);
		Path path = getWorldRevealSnapshotPath(server);
		task.snapshotWriteFuture = CompletableFuture.runAsync(() -> {
			try {
				writeWorldRevealSnapshot(path, snapshot);
			} catch (IOException exception) {
				throw new IllegalStateException("Failed to write season-start terrain snapshot " + path, exception);
			}
		});
	}

	private static void writeWorldRevealSnapshot(Path path, PersistedWorldRevealSnapshot snapshot) throws IOException {
		if (path == null || snapshot == null) {
			throw new IOException("Missing world-reveal snapshot destination or contents");
		}
		Files.createDirectories(path.getParent());
		Path temporaryPath = path.resolveSibling(path.getFileName() + ".tmp");
		try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(temporaryPath))))) {
			output.writeInt(WORLD_REVEAL_SNAPSHOT_MAGIC);
			output.writeInt(WORLD_REVEAL_SNAPSHOT_VERSION);
			output.writeLong(snapshot.anchor());
			output.writeInt(snapshot.palette().size());
			for (String descriptor : snapshot.palette()) {
				output.writeUTF(descriptor == null ? "" : descriptor);
			}
			writeSnapshotBlocks(output, snapshot.terrain());
			writeSnapshotBlocks(output, snapshot.boundary());
		}
		try {
			Files.move(temporaryPath, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException ignored) {
			Files.move(temporaryPath, path, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static void writeSnapshotBlocks(DataOutputStream output, List<PersistedSnapshotBlock> blocks) throws IOException {
		output.writeInt(blocks.size());
		for (PersistedSnapshotBlock entry : blocks) {
			output.writeLong(entry.pos());
			output.writeInt(entry.stateIndex());
		}
	}

	private static PersistedWorldRevealSnapshot readWorldRevealSnapshot(Path path) throws IOException {
		try (DataInputStream input = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(path))))) {
			if (input.readInt() != WORLD_REVEAL_SNAPSHOT_MAGIC) {
				throw new IOException("Unexpected season-start terrain snapshot header");
			}
			if (input.readInt() != WORLD_REVEAL_SNAPSHOT_VERSION) {
				throw new IOException("Unsupported season-start terrain snapshot version");
			}
			long anchor = input.readLong();
			int paletteSize = input.readInt();
			if (paletteSize < 0 || paletteSize > 16_384) {
				throw new IOException("Invalid season-start snapshot palette size");
			}
			List<String> palette = new ArrayList<>(paletteSize);
			for (int index = 0; index < paletteSize; index++) {
				palette.add(input.readUTF());
			}
			List<PersistedSnapshotBlock> terrain = readSnapshotBlocks(input, paletteSize);
			List<PersistedSnapshotBlock> boundary = readSnapshotBlocks(input, paletteSize);
			return new PersistedWorldRevealSnapshot(anchor, List.copyOf(palette), List.copyOf(terrain), List.copyOf(boundary));
		}
	}

	private static List<PersistedSnapshotBlock> readSnapshotBlocks(DataInputStream input, int paletteSize) throws IOException {
		int count = input.readInt();
		if (count < 0 || count > 3_000_000) {
			throw new IOException("Invalid season-start snapshot block count");
		}
		List<PersistedSnapshotBlock> entries = new ArrayList<>(count);
		for (int index = 0; index < count; index++) {
			long pos = input.readLong();
			int stateIndex = input.readInt();
			if (stateIndex < 0 || stateIndex >= paletteSize) {
				throw new IOException("Invalid season-start snapshot palette index");
			}
			entries.add(new PersistedSnapshotBlock(pos, stateIndex));
		}
		return entries;
	}

	private static void deleteWorldRevealSnapshot(MinecraftServer server) {
		if (server == null) {
			return;
		}
		try {
			Files.deleteIfExists(getWorldRevealSnapshotPath(server));
		} catch (IOException exception) {
			Lg2.LOGGER.warn("Failed to remove completed season-start terrain snapshot", exception);
		}
	}

	private static void loadState(MinecraftServer server) {
		Path path = getStatePath(server);
		PLAYER_STATES.clear();
		LEGACY_INTRO_TOOL_PURGED_PLAYERS.clear();
		GUIDED_OFFERING_VISIBLE_SINCE_TICKS.clear();
		HIDDEN_PLAYER_PROFILE_PAIRS.clear();
		SHARED_BITCOIN_POSITIONS.clear();
		clearSharedLaunchBossBar();
		stateLoaded = true;
		stateDirty = false;
		scenePrepared = false;
		sceneBuildTask = null;
		shellDissolving = false;
		worldRevealActive = false;
		worldRevealRecoveryPending = false;
		worldRevealBarriersPlaced = false;
		lastSharedLaunchProgressTick = Long.MIN_VALUE;
		pendingSharedFinishTick = Long.MIN_VALUE;
		pendingMenuExplanationTick = Long.MIN_VALUE;
		worldRevealPhaseStartTick = Long.MIN_VALUE;
		worldRevealCrackStartTick = Long.MIN_VALUE;
		worldRevealCrackNotBeforeTick = Long.MIN_VALUE;
		worldRevealMusicEndTick = Long.MIN_VALUE;
		worldRevealDarknessClearTick = Long.MIN_VALUE;
		worldRevealCompletionTick = Long.MIN_VALUE;
		worldRevealDarknessPulseCount = 0;
		worldRevealGameplayReleased = false;
		sharedLaunchCollectedBitcoins = 0;
		sharedLaunchRequiredBitcoins = 0;
		sharedLaunchBitcoinSpawned = 0;
		sharedLaunchBitcoinSupplyVersion = SHARED_LAUNCH_SUPPLY_VERSION;
		sharedLaunchBitcoinPositionIndexLoaded = false;
		sharedLaunchIntroTriggered = false;
		sharedLaunchServerPowerNarrationTriggered = false;
		sharedLaunchRaceControlsTriggered = false;
		menuExplanationActive = false;
		startupWorldgenFrameIndex = Integer.MIN_VALUE;
		worldRevealVisibleEpisodeCursor = 0;
		worldRevealBurstCursor = 0;
		worldRevealBurstWeightProgress = 0.0F;
		worldRevealEarthquakeSoundStarted = false;
		worldRevealDarknessRepositioned = false;
		worldRevealPhase = WorldRevealPhase.NONE;
		SHELL_DISSOLVE_ORDER.clear();
		WORLD_REVEAL_TERRAIN.clear();
		WORLD_REVEAL_BARRIER_COLLISION.clear();
		WORLD_REVEAL_EPISODES.clear();
		WORLD_REVEAL_SURFACE_Y.clear();
		WORLD_REVEAL_TARGET_STATES.clear();
		WORLD_REVEAL_BOUNDARY_TARGET_STATES.clear();
		WORLD_REVEAL_SAFE_TARGETS.clear();
		WORLD_REVEAL_REQUIRED_POSITIONS.clear();
		WORLD_REVEAL_DEFERRED_POSITIONS.clear();
		WORLD_REVEAL_REVEALED_POSITIONS.clear();
		worldRevealPlanFuture = null;
		worldRevealPlanReady = false;
		if (!Files.exists(path)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(path)) {
			PersistedState state = STATE_GSON.fromJson(reader, PersistedState.class);
			if (state == null) {
				return;
			}
			bootstrapComplete = state.bootstrapComplete;
			active = state.active;
			completed = state.completed;
			worldRevealActive = state.worldRevealActive;
			serverAnchor = parseBlockPos(state.serverAnchor);
			serverStructureAxis = parseServerAxis(state.serverAxis);
			difficultyBeforeSeasonStart = parseDifficulty(state.difficultyBeforeSeasonStart);
			pristineWorldFreeze = PristineWorldFreezeState.fromPersisted(state.pristineWorldFreeze);
			sharedLaunchCollectedBitcoins = Math.max(0, state.sharedLaunchCollectedBitcoins);
			sharedLaunchRequiredBitcoins = Math.max(0, state.sharedLaunchRequiredBitcoins);
			sharedLaunchBitcoinSpawned = Math.max(0, state.sharedLaunchBitcoinSpawned);
			sharedLaunchBitcoinSupplyVersion = Math.max(0, state.sharedLaunchBitcoinSupplyVersion);
			sharedLaunchBitcoinPositionIndexLoaded = false;
			sharedLaunchIntroTriggered = state.sharedLaunchIntroTriggered;
			sharedLaunchServerPowerNarrationTriggered = state.sharedLaunchServerPowerNarrationTriggered;
			sharedLaunchRaceControlsTriggered = state.sharedLaunchRaceControlsTriggered;
			menuExplanationActive = state.menuExplanationActive;
			if (state.players != null) {
				for (Map.Entry<String, PersistedPlayerState> entry : state.players.entrySet()) {
					UUID playerId = parseUuid(entry.getKey());
					PersistedPlayerState persisted = entry.getValue();
					if (playerId == null || persisted == null) {
						continue;
					}
					PlayerSceneState runtime = new PlayerSceneState();
					runtime.slotIndex = persisted.slotIndex;
					runtime.minedIntroBitcoin = persisted.minedIntroBitcoin;
					runtime.introOreRevealed = persisted.introOreRevealed;
					runtime.poweredServer = persisted.poweredServer;
					runtime.guidedBitcoinEscapeCount = Math.max(0, persisted.guidedBitcoinEscapeCount);
					runtime.lockedGuidedBitcoinSlot = persisted.lockedGuidedBitcoinSlot;
					runtime.escapedGuidedOfferingId = parseUuid(persisted.escapedGuidedOfferingId);
					runtime.phase = PlayerPhase.byId(persisted.phase);
					if (runtime.poweredServer) {
						runtime.phase = PlayerPhase.SHARED;
					}
				runtime.menuOpened = persisted.menuOpened;
				runtime.menuNarrationMuted = persisted.menuNarrationMuted;
				runtime.raceMenuReached = persisted.raceMenuReached;
				runtime.racePurchaseExplained = persisted.racePurchaseExplained;
				runtime.raceMenuReminderExplained = persisted.raceMenuReminderExplained;
				runtime.menuRaceAllowanceGranted = persisted.menuRaceAllowanceGranted;
					if (persisted.seenMenuSections != null) {
						runtime.seenMenuSections.addAll(persisted.seenMenuSections);
					}
					PLAYER_STATES.put(playerId, runtime);
				}
			}
		} catch (Exception exception) {
			Lg2.LOGGER.warn("Failed to read season-start state {}", path, exception);
		}
	}

	private static void saveState(MinecraftServer server) {
		if (!stateLoaded || !stateDirty || server == null) {
			return;
		}
		PersistedState state = new PersistedState();
		state.bootstrapComplete = bootstrapComplete;
		state.active = active;
		state.completed = completed;
		state.worldRevealActive = worldRevealActive;
		state.serverAnchor = serializeBlockPos(serverAnchor);
		state.serverAxis = serializeServerAxis(serverStructureAxis);
		state.difficultyBeforeSeasonStart = difficultyBeforeSeasonStart == null ? "" : difficultyBeforeSeasonStart.getKey();
		state.pristineWorldFreeze = pristineWorldFreeze == null ? null : pristineWorldFreeze.toPersisted();
		state.sharedLaunchCollectedBitcoins = sharedLaunchCollectedBitcoins;
		state.sharedLaunchRequiredBitcoins = sharedLaunchRequiredBitcoins;
		state.sharedLaunchBitcoinSpawned = sharedLaunchBitcoinSpawned;
		state.sharedLaunchBitcoinSupplyVersion = sharedLaunchBitcoinSupplyVersion;
		state.sharedLaunchIntroTriggered = sharedLaunchIntroTriggered;
		state.sharedLaunchServerPowerNarrationTriggered = sharedLaunchServerPowerNarrationTriggered;
		state.sharedLaunchRaceControlsTriggered = sharedLaunchRaceControlsTriggered;
		state.menuExplanationActive = menuExplanationActive;
		state.players = new LinkedHashMap<>();
		for (Map.Entry<UUID, PlayerSceneState> entry : PLAYER_STATES.entrySet()) {
			PlayerSceneState runtime = entry.getValue();
			if (runtime == null) {
				continue;
			}
			PersistedPlayerState persisted = new PersistedPlayerState();
			persisted.slotIndex = runtime.slotIndex;
			persisted.phase = runtime.phase.id;
			persisted.minedIntroBitcoin = runtime.minedIntroBitcoin;
			persisted.introOreRevealed = runtime.introOreRevealed;
			persisted.poweredServer = runtime.poweredServer;
			persisted.guidedBitcoinEscapeCount = runtime.guidedBitcoinEscapeCount;
			persisted.lockedGuidedBitcoinSlot = runtime.lockedGuidedBitcoinSlot;
			persisted.escapedGuidedOfferingId = runtime.escapedGuidedOfferingId == null ? "" : runtime.escapedGuidedOfferingId.toString();
			persisted.menuOpened = runtime.menuOpened;
			persisted.menuNarrationMuted = runtime.menuNarrationMuted;
			persisted.raceMenuReached = runtime.raceMenuReached;
			persisted.racePurchaseExplained = runtime.racePurchaseExplained;
			persisted.raceMenuReminderExplained = runtime.raceMenuReminderExplained;
			persisted.menuRaceAllowanceGranted = runtime.menuRaceAllowanceGranted;
			persisted.seenMenuSections = new ArrayList<>(runtime.seenMenuSections);
			state.players.put(entry.getKey().toString(), persisted);
		}
		Path path = getStatePath(server);
		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path)) {
				STATE_GSON.toJson(state, writer);
			}
			stateDirty = false;
		} catch (Exception exception) {
			Lg2.LOGGER.warn("Failed to save season-start state {}", path, exception);
		}
	}

	private static String serializeBlockPos(BlockPos pos) {
		return pos == null ? "" : pos.getX() + "," + pos.getY() + "," + pos.getZ();
	}

	private static BlockPos parseBlockPos(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String[] parts = value.split(",");
		if (parts.length != 3) {
			return null;
		}
		try {
			return new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
		} catch (NumberFormatException ignored) {
			return null;
		}
	}

	private static UUID parseUuid(String value) {
		try {
			return value == null || value.isBlank() ? null : UUID.fromString(value);
		} catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	private static Difficulty parseDifficulty(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		for (Difficulty difficulty : Difficulty.values()) {
			if (difficulty.getKey().equalsIgnoreCase(value)) {
				return difficulty;
			}
		}
		return null;
	}

	private static void primeObservationState(ServerPlayer player, PlayerSceneState state) {
		if (player == null || state == null || player.level() == null) {
			return;
		}
		long nowTick = player.level().getGameTime();
		state.lastActivityTick = nowTick;
		state.nextIdleReactionTick = nowTick + INTRO_IDLE_TRIGGER_TICKS;
		state.nextLeaveReactionTick = 0L;
		state.nextSpinReactionTick = 0L;
		state.nextJumpReactionTick = 0L;
		state.nextAirPunchReactionTick = 0L;
		state.nextIntroTargetReactionTick = 0L;
		state.introTargetLocked = false;
		state.nextGuidanceTick = 0L;
		state.nextGuidanceVoiceTick = 0L;
		state.nextGuidanceEarliestTick = 0L;
		state.lastGuidanceStateKey = "";
		state.lastGuidanceDistance = Double.NaN;
		state.lastGuidanceAbsYaw = Double.NaN;
		state.lastGuidanceDistanceBucket = Integer.MIN_VALUE;
		state.wasGuidanceAligned = false;
		state.wasGuidanceClose = false;
		state.lastGuidanceAlignedTick = Long.MIN_VALUE;
		state.guidanceRouteStarted = false;
		state.guidanceAlignedEver = false;
		state.guidanceMistakeCount = 0;
		state.lastGuidanceTurnDirection = TurnHintDirection.NONE;
		state.lastGuidanceTurnAbsYaw = Double.NaN;
		state.lastGuidanceTurnPlayerYaw = Float.NaN;
		state.lastGuidanceTurnTick = Long.MIN_VALUE;
		state.turnRecoveryAllowedTick = Long.MIN_VALUE;
		state.lastGuidanceTurnRecoverUsed = false;
		state.announcedServerSight = false;
		state.guidanceCueCycles.clear();
		state.guidanceCueBags.clear();
		state.lastGuidanceTriggerByGroup.clear();
		state.guidanceSemanticNextAllowedTicks.clear();
		state.leftIntroTaskArea = false;
		resetGuidanceRoute(state);
		state.visibleGuidanceTargetKey = "";
		state.visibleGuidanceLastPlayerX = player.getX();
		state.visibleGuidanceLastPlayerZ = player.getZ();
		state.visibleGuidanceLastMoveTick = nowTick;
		if (state.phase == PlayerPhase.WAITING_START && state.nextStartPromptTick <= 0L && player.level() != null) {
			state.nextStartPromptTick = player.level().getGameTime() + WAITING_START_INITIAL_PROMPT_TICKS;
		}
		state.lastOnGround = player.onGround();
		state.spinScore = 0.0D;
		updateObservationBaseline(player, state);
	}

	private static void beginIntroAfterChatStart(MinecraftServer server, ServerPlayer player, PlayerSceneState state) {
		if (server == null || player == null || state == null || state.phase != PlayerPhase.WAITING_START) {
			return;
		}
		long nowTick = player.level() == null ? 0L : player.level().getGameTime();
		SeasonStartVoiceSystem.clearPlayerChannel(player);
		state.phase = PlayerPhase.ISOLATED;
		state.seasonStartPresentationApplied = false;
		state.introOreRevealed = false;
		state.nextStartPromptTick = Long.MAX_VALUE;
		state.nextPersonalExitSignTick = nowTick + PERSONAL_EXIT_SIGN_MIN_INTERVAL_TICKS;
		state.personalExitSignExpiresAtTick = Long.MIN_VALUE;
		state.personalExitSignsRemaining = rollPersonalExitSignBudget(player.getUUID(), nowTick);
		state.guidanceNarrationGateTick = nowTick + resolveTriggerSequenceDurationTicks("player_intro_assigned");
		stateDirty = true;
		primeObservationState(player, state);
		SeasonStartVoiceSystem.fireTrigger(server, "player_waiting_start_confirmed", player);
		SeasonStartVoiceSystem.fireTrigger(server, "player_intro_assigned", player);
	}

	private static boolean shouldFastForwardGuidanceNarration(
			GuidanceSnapshot snapshot,
			boolean carryingBitcoin,
			boolean seesServer
	) {
		if (snapshot == null || !carryingBitcoin) {
			return false;
		}
		return snapshot.horizontalDistance <= GUIDANCE_QUIET_DISTANCE || seesServer;
	}

	private static void interruptAndFastForwardPlayerNarration(ServerPlayer player, PlayerSceneState state, long nowTick) {
		if (player != null) {
			SeasonStartVoiceSystem.clearPlayerChannel(player);
		}
		if (state == null) {
			return;
		}
		state.guidanceNarrationGateTick = nowTick;
		state.nextGuidanceTick = nowTick;
		state.nextGuidanceVoiceTick = nowTick;
		state.nextGuidanceEarliestTick = nowTick;
		state.lastGuidanceStateKey = "";
	}

	private static boolean shouldFireIntroTargetReaction(PlayerSceneState state, long nowTick) {
		return state != null && (!state.introTargetLocked || nowTick >= state.nextIntroTargetReactionTick);
	}

	private static void fireIntroTargetReaction(MinecraftServer server, ServerPlayer player, PlayerSceneState state, long nowTick) {
		if (server == null || player == null || state == null) {
			return;
		}
		state.guidanceRouteStarted = true;
		fireTriggerCycle(
				server,
				player,
				state,
				state.introTargetLocked ? "intro_target_stare" : "intro_target_locked",
				state.introTargetLocked ? INTRO_TARGET_STARE_TRIGGERS : INTRO_TARGET_LOCK_TRIGGERS
		);
		state.introTargetLocked = true;
		state.nextIntroTargetReactionTick = nowTick + INTRO_TARGET_REACTION_REPEAT_TICKS;
	}

	private static void fireRoundRobinTrigger(
			MinecraftServer server,
			ServerPlayer player,
			PlayerSceneState state,
			String[] triggers
	) {
		if (server == null || player == null || state == null || triggers == null || triggers.length == 0) {
			return;
		}
		int index = Math.floorMod(state.startPromptVariantIndex++, triggers.length);
		SeasonStartVoiceSystem.fireTrigger(server, triggers[index], player);
	}

	private static void fireTriggerCycle(
			MinecraftServer server,
			ServerPlayer player,
			PlayerSceneState state,
			String cycleKey,
			String[] triggers
	) {
		if (server == null || player == null || state == null || cycleKey == null || cycleKey.isBlank() || triggers == null || triggers.length == 0) {
			return;
		}
		List<String> bag = state.guidanceCueBags.computeIfAbsent(cycleKey, ignored -> new ArrayList<>());
		if (bag.isEmpty()) {
			int refill = state.guidanceCueCycles.merge(cycleKey, 1, Integer::sum);
			for (String trigger : triggers) {
				if (trigger != null && !trigger.isBlank()) {
					bag.add(trigger);
				}
			}
			Collections.shuffle(bag, new Random(player.getUUID().hashCode() ^ cycleKey.hashCode() ^ refill));
			// Never end one shuffled round and immediately start the next with the
			// exact same line. Every variation gets heard before the bag refills.
			String previous = state.lastGuidanceTriggerByGroup.get(cycleKey);
			if (bag.size() > 1 && previous != null && previous.equals(bag.get(0))) {
				for (int index = 1; index < bag.size(); index++) {
					if (!previous.equals(bag.get(index))) {
						Collections.swap(bag, 0, index);
						break;
					}
				}
			}
		}
		if (bag.isEmpty()) {
			return;
		}
		String trigger = bag.remove(0);
		state.lastGuidanceTriggerByGroup.put(cycleKey, trigger);
		SeasonStartVoiceSystem.fireTrigger(server, trigger, player);
	}

	private static void updateObservationBaseline(ServerPlayer player, PlayerSceneState state) {
		if (player == null || state == null || player.level() == null) {
			return;
		}
		state.lastObservedX = player.getX();
		state.lastObservedY = player.getY();
		state.lastObservedZ = player.getZ();
		state.lastYaw = player.getYRot();
		state.lastPitch = player.getXRot();
		state.lastOnGround = player.onGround();
		state.lastObservationTick = player.level().getGameTime();
	}

	private static double horizontalDistanceSqr(Vec3 first, Vec3 second) {
		double dx = first.x - second.x;
		double dz = first.z - second.z;
		return dx * dx + dz * dz;
	}

	private static long surfaceColumnKey(int x, int z) {
		return BlockPos.asLong(x, 0, z);
	}

	private static double axisDistance(double value, double min, double max) {
		if (value < min) {
			return min - value;
		}
		if (value > max) {
			return value - max;
		}
		return 0.0D;
	}

	private static Vec3 centerOf(BlockPos pos) {
		return pos == null ? Vec3.ZERO : new Vec3(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
	}

	private static void setSceneBlockSilently(ServerLevel level, BlockPos pos, BlockState state) {
		if (level == null || pos == null || state == null) {
			return;
		}
		level.setBlock(pos, state, SCENE_BLOCK_SET_FLAGS);
	}

	private static boolean isLookingAtIntroOre(ServerPlayer player, SlotDefinition slot) {
		if (player == null || slot == null) {
			return false;
		}
		HitResult hit = player.pick(INTRO_PICK_REACH, 0.0F, false);
		return hit instanceof BlockHitResult blockHit
				&& hit.getType() == HitResult.Type.BLOCK
				&& slot.orePos.equals(blockHit.getBlockPos());
	}

	private static boolean isLookingAtServerStructure(ServerPlayer player) {
		if (player == null || serverAnchor == null) {
			return false;
		}
		HitResult hit = player.pick(INTRO_PICK_REACH, 0.0F, false);
		return hit instanceof BlockHitResult blockHit
				&& hit.getType() == HitResult.Type.BLOCK
				&& isServerStructureFootprint(blockHit.getBlockPos());
	}

	private enum PlayerPhase {
		WAITING_START("waiting_start"),
		ISOLATED("isolated"),
		GUIDED_TO_SERVER("guided"),
		RESTORING("restoring"),
		SHARED("shared"),
		FREE("free");

		private final String id;

		PlayerPhase(String id) {
			this.id = id;
		}

		private static PlayerPhase byId(String id) {
			for (PlayerPhase value : values()) {
				if (value.id.equalsIgnoreCase(id == null ? "" : id)) {
					return value;
				}
			}
			return ISOLATED;
		}
	}

	private enum GuidanceRouteKind {
		NONE,
		INTRO_ORE,
		SERVER,
		LOST_BITCOIN
	}

	private enum MenuSection {
		ROOT("main", "player_menu_opened"),
		ERAS("eras", "player_menu_section_eras"),
		MECHANICS("mechanics", "player_menu_section_mechanics"),
		IT("it", "player_menu_section_it"),
		WORLDS("worlds", "player_menu_section_worlds"),
		RACES("races", "player_menu_section_races"),
		OTHER("other", "player_menu_opened");

		private final String id;
		private final String openTrigger;

		MenuSection(String id, String openTrigger) {
			this.id = id;
			this.openTrigger = openTrigger;
		}

		private static MenuSection byScreenId(String screenId) {
			String normalized = screenId == null ? "" : screenId.trim().toLowerCase(java.util.Locale.ROOT);
			if (normalized.equals("main") || normalized.equals("root")) {
				return ROOT;
			}
			if (normalized.contains("race")) {
				return RACES;
			}
			if (normalized.contains("era")) {
				return ERAS;
			}
			if (normalized.contains("mechanic") || normalized.contains("system")) {
				return MECHANICS;
			}
			if (normalized.equals("it") || normalized.startsWith("it_") || normalized.contains("drone")) {
				return IT;
			}
			if (normalized.contains("world") || normalized.contains("dimension")) {
				return WORLDS;
			}
			return OTHER;
		}

		private static MenuSection byId(String id) {
			for (MenuSection value : values()) {
				if (value.id.equals(id)) {
					return value;
				}
			}
			return OTHER;
		}
	}

	private static final class PlayerSceneState {
		private int slotIndex;
		private PlayerPhase phase = PlayerPhase.ISOLATED;
		private boolean minedIntroBitcoin;
		private boolean introOreRevealed;
		private boolean poweredServer;
		private int guidedBitcoinEscapeCount;
		private int lockedGuidedBitcoinSlot = NO_LOCKED_GUIDED_BITCOIN_SLOT;
		private long restoreVisionTick = Long.MAX_VALUE;
		private boolean sharedVisionRestored = false;
		private boolean pendingSharedPeersLine = false;
		private boolean seasonStartPresentationApplied = false;
		private long guidanceNarrationGateTick = 0L;
		private long nextGuidanceTick = 0L;
		private long nextGuidanceVoiceTick = 0L;
		private long nextGuidanceEarliestTick = 0L;
		private String lastGuidanceStateKey = "";
		private double lastGuidanceDistance = Double.NaN;
		private double lastGuidanceAbsYaw = Double.NaN;
		private int lastGuidanceDistanceBucket = Integer.MIN_VALUE;
		private boolean wasGuidanceAligned = false;
		private boolean wasGuidanceClose = false;
		private long lastGuidanceAlignedTick = Long.MIN_VALUE;
		private boolean guidanceRouteStarted = false;
		private boolean guidanceAlignedEver = false;
		private int guidanceMistakeCount = 0;
		private boolean announcedServerSight = false;
		private boolean guidanceQuietZoneActive = false;
		private TurnHintDirection lastGuidanceTurnDirection = TurnHintDirection.NONE;
		private double lastGuidanceTurnAbsYaw = Double.NaN;
		private float lastGuidanceTurnPlayerYaw = Float.NaN;
		private long lastGuidanceTurnTick = Long.MIN_VALUE;
		private long turnRecoveryAllowedTick = Long.MIN_VALUE;
		private boolean lastGuidanceTurnRecoverUsed = false;
		private final Map<String, Integer> guidanceCueCycles = new LinkedHashMap<>();
		private final Map<String, List<String>> guidanceCueBags = new HashMap<>();
		private final Map<String, String> lastGuidanceTriggerByGroup = new HashMap<>();
		private final Map<String, Long> guidanceSemanticNextAllowedTicks = new HashMap<>();
		private GuidanceRouteKind guidanceRouteKind = GuidanceRouteKind.NONE;
		private int guidanceRouteLegIndex = 0;
		private boolean guidanceRouteLegAnnounced = false;
		private boolean guidanceRouteFinished = false;
		private boolean guidanceRouteArrivalAnnounced = false;
		private double guidanceRouteOriginX = Double.NaN;
		private double guidanceRouteOriginZ = Double.NaN;
		private double guidanceRouteDestinationX = Double.NaN;
		private double guidanceRouteDestinationZ = Double.NaN;
		private double guidanceRouteSide = 1.0D;
		private double guidanceRouteLastDistance = Double.NaN;
		private double guidanceRouteLastPlayerX = 0.0D;
		private double guidanceRouteLastPlayerZ = 0.0D;
		private long guidanceRouteLastMoveTick = Long.MIN_VALUE;
		private long guidanceRouteLegStartedTick = Long.MIN_VALUE;
		private long guidanceRouteStartedTick = Long.MIN_VALUE;
		private boolean guidanceRouteDirect = false;
		private long guidanceRouteInterruptAfterTick = Long.MIN_VALUE;
		private long guidanceRouteLastTurnCueTick = 0L;
		private long guidanceRouteNextTurnCueTick = 0L;
		private long guidanceRouteNextProgressCueTick = 0L;
		private long guidanceRouteNextWrongWayTick = 0L;
		private boolean guidanceRouteDirectionIssued = false;
		private String visibleGuidanceTargetKey = "";
		private double visibleGuidanceLastPlayerX = 0.0D;
		private double visibleGuidanceLastPlayerZ = 0.0D;
		private long visibleGuidanceLastMoveTick = Long.MIN_VALUE;
		private UUID escapedGuidedOfferingId;
		private Vec3 escapedGuidedOfferingTarget;
		private boolean escapedGuidedOfferingLanded = false;
		private boolean announcedServerBeforeBitcoin = false;
		private boolean announcedServerWithoutBitcoin = false;
		private boolean wasAtServerWithoutBitcoin = false;
		private boolean leftServerWhileRecoveringBitcoin = false;
		private int serverWithoutBitcoinVisitCount = 0;
		private long lastActivityTick = 0L;
		private long nextIdleReactionTick = 0L;
		private long nextLeaveReactionTick = 0L;
		private boolean leftIntroTaskArea = false;
		private long nextSpinReactionTick = 0L;
		private long nextJumpReactionTick = 0L;
		private long nextAirPunchReactionTick = 0L;
		private long nextIntroTargetReactionTick = 0L;
		private UUID personalExitSignId;
		private long personalExitSignExpiresAtTick = Long.MIN_VALUE;
		private long nextPersonalExitSignTick = Long.MAX_VALUE;
		private int personalExitSignsRemaining;
		private long lastObservationTick = Long.MIN_VALUE;
		private double lastObservedX;
		private double lastObservedY;
		private double lastObservedZ;
		private float lastYaw;
		private float lastPitch;
		private boolean lastOnGround = true;
		private boolean introTargetLocked = false;
		private double spinScore = 0.0D;
		private long nextStartPromptTick = 0L;
		private int startPromptVariantIndex = 0;
		private boolean menuOpened;
		private boolean menuNarrationMuted;
		private boolean raceMenuReached;
		private boolean racePurchaseExplained;
		private boolean raceMenuReminderExplained;
		private boolean menuRaceAllowanceGranted;
		private String activeMenuSection = "";
		private long nextMenuPriceReactionTick = Long.MIN_VALUE;
		private final Set<String> seenMenuSections = new LinkedHashSet<>();
	}

	private record PlayerVisibilityPair(UUID viewerId, UUID subjectId) {
	}

	/** A bounded, equality-stable view so ChunkMap can diff it without resending it every tick. */
	private record StartupChunkTrackingView(Set<Long> chunks) implements ChunkTrackingView {
		private StartupChunkTrackingView(Set<Long> chunks) {
			this.chunks = chunks == null ? Set.of() : Set.copyOf(chunks);
		}

		@Override
		public boolean contains(int chunkX, int chunkZ, boolean includeEdge) {
			return this.chunks.contains(new ChunkPos(chunkX, chunkZ).toLong());
		}

		@Override
		public void forEach(Consumer<ChunkPos> consumer) {
			if (consumer == null) {
				return;
			}
			for (long chunk : this.chunks) {
				consumer.accept(new ChunkPos(chunk));
			}
		}
	}

	private record StartupBiomePayloadKey(int sectionCount, ResourceKey<Biome> biome) {
	}

	/** Per-player state: the world stays unchanged; only this player's palette is replaced. */
	private static final class StartupBiomeOverride {
		private ResourceKey<Level> dimension;
		private List<ChunkPos> chunks;
		private int sectionCount;
		private int appliedStage = Integer.MIN_VALUE;
		private int nextChunkIndex;
		private long nextResyncTick = Long.MIN_VALUE;

		private StartupBiomeOverride(ResourceKey<Level> dimension, List<ChunkPos> chunks, int sectionCount) {
			this.dimension = dimension;
			this.chunks = chunks == null ? List.of() : List.copyOf(chunks);
			this.sectionCount = sectionCount;
		}

		private void updateWindow(ResourceKey<Level> dimension, List<ChunkPos> chunks, int sectionCount) {
			List<ChunkPos> nextChunks = chunks == null ? List.of() : List.copyOf(chunks);
			if (this.dimension.equals(dimension) && this.sectionCount == sectionCount && this.chunks.equals(nextChunks)) {
				return;
			}
			this.dimension = dimension;
			this.chunks = nextChunks;
			this.sectionCount = sectionCount;
			this.appliedStage = Integer.MIN_VALUE;
			this.nextChunkIndex = 0;
			this.nextResyncTick = Long.MIN_VALUE;
		}
	}

	private record BoxGeometry(int minX, int maxX, int minZ, int maxZ, int floorY, int roofY) {
	}

	private enum SceneBuildPhase {
		BOUNDARY_SNAPSHOT,
		ENTITY_SNAPSHOT,
		SNAPSHOT,
		VERTICAL_SNAPSHOT_BELOW,
		VERTICAL_SNAPSHOT_ABOVE,
		SNAPSHOT_PERSIST,
		VERTICAL_CLEAR_BELOW,
		VERTICAL_CLEAR_ABOVE,
		CLEAR_STALE,
		BUILD_OUTER,
		BUILD_BARRIER,
		BUILD_LIGHTS,
		FINALIZE
	}

	private enum SceneBuildMode {
		STARTUP,
		WORLD_REVEAL_RECOVERY
	}

	private static final class SceneBuildTask {
		private final BlockPos anchor;
		private final BoxGeometry outer;
		private final BoxGeometry barrier;
		private final BoxGeometry boundary;
		private final BoxGeometry stale;
		private final BoxGeometry verticalBelow;
		private final BoxGeometry verticalAbove;
		private final SceneBuildMode mode;
		private final boolean generatorFallback;
		private final boolean reuseExistingShell;
		private final boolean captureEntities;
		private final Set<Long> structureFootprint;
		private final List<BlockPos> barrierShellBlocks;
		private final List<BlockPos> startupLightBlocks;
		private final Map<Long, Integer> topSolidSurfaceY = new HashMap<>();
		private final WorldRevealSnapshotAccumulator snapshotAccumulator;
		private List<Entity> sceneEntityCandidates;
		private SceneBuildPhase phase;
		private long cursor;
		private CompletableFuture<Void> snapshotWriteFuture;
		private NoiseColumn noiseColumn;
		private int noiseColumnX = Integer.MIN_VALUE;
		private int noiseColumnZ = Integer.MIN_VALUE;

		private SceneBuildTask(
				BlockPos anchor,
				BoxGeometry outer,
				BoxGeometry barrier,
				BoxGeometry boundary,
				BoxGeometry stale,
				BoxGeometry verticalBelow,
				BoxGeometry verticalAbove,
				SceneBuildMode mode,
				boolean generatorFallback,
				boolean reuseExistingShell,
				boolean captureRestorationData,
				boolean reusePersistedSnapshot,
				Set<Long> structureFootprint
		) {
			this.anchor = anchor;
			this.outer = outer;
			this.barrier = barrier;
			this.boundary = boundary;
			this.stale = stale;
			this.verticalBelow = verticalBelow;
			this.verticalAbove = verticalAbove;
			this.mode = mode;
			this.generatorFallback = generatorFallback;
			this.reuseExistingShell = reuseExistingShell;
			this.captureEntities = captureRestorationData;
			this.structureFootprint = structureFootprint;
			this.barrierShellBlocks = collectBarrierShellBlocks(barrier);
			this.startupLightBlocks = collectStartupLightBlocks(barrier, structureFootprint);
			this.snapshotAccumulator = captureRestorationData ? new WorldRevealSnapshotAccumulator() : null;
			this.phase = mode == SceneBuildMode.WORLD_REVEAL_RECOVERY
					? SceneBuildPhase.FINALIZE
					: reusePersistedSnapshot
					? (reuseExistingShell ? SceneBuildPhase.BUILD_LIGHTS : SceneBuildPhase.BUILD_OUTER)
					: captureRestorationData
					? SceneBuildPhase.BOUNDARY_SNAPSHOT
					: SceneBuildPhase.SNAPSHOT;
		}

		private BoxGeometry currentGeometry() {
			return switch (this.phase) {
				case BOUNDARY_SNAPSHOT -> this.boundary;
				case SNAPSHOT, BUILD_OUTER -> this.outer;
				case VERTICAL_SNAPSHOT_BELOW, VERTICAL_CLEAR_BELOW -> this.verticalBelow;
				case VERTICAL_SNAPSHOT_ABOVE, VERTICAL_CLEAR_ABOVE -> this.verticalAbove;
				case CLEAR_STALE -> this.stale;
				case BUILD_BARRIER, BUILD_LIGHTS -> this.barrier;
				case ENTITY_SNAPSHOT, SNAPSHOT_PERSIST, FINALIZE -> this.outer;
			};
		}
	}

	private record SlotDefinition(BlockPos spawnFloorPos, BlockPos oreSupportPos, BlockPos orePos, Vec3 spawnPos, float yaw) {
	}

	private record TerrainPlacement(BlockPos pos, BlockState state) {
	}

	private record PersistedSnapshotBlock(long pos, int stateIndex) {
	}

	private record PersistedWorldRevealSnapshot(
			long anchor,
			List<String> palette,
			List<PersistedSnapshotBlock> terrain,
			List<PersistedSnapshotBlock> boundary
	) {
	}

	/** Builds the compact on-disk form while the existing snapshot scan is already batched. */
	private static final class WorldRevealSnapshotAccumulator {
		private final List<String> palette = new ArrayList<>();
		private final Map<String, Integer> paletteIndexes = new HashMap<>();
		private final List<PersistedSnapshotBlock> terrain = new ArrayList<>();
		private final List<PersistedSnapshotBlock> boundary = new ArrayList<>();

		private void addTerrain(BlockPos pos, BlockState state) {
			this.terrain.add(new PersistedSnapshotBlock(pos.asLong(), this.indexOf(state)));
		}

		private void addBoundary(BlockPos pos, BlockState state) {
			this.boundary.add(new PersistedSnapshotBlock(pos.asLong(), this.indexOf(state)));
		}

		private int indexOf(BlockState state) {
			String descriptor = serializeWorldRevealBlockState(state);
			Integer knownIndex = this.paletteIndexes.get(descriptor);
			if (knownIndex != null) {
				return knownIndex;
			}
			int index = this.palette.size();
			this.palette.add(descriptor);
			this.paletteIndexes.put(descriptor, index);
			return index;
		}

		private PersistedWorldRevealSnapshot freeze(BlockPos anchor) {
			return new PersistedWorldRevealSnapshot(
					anchor.asLong(),
					List.copyOf(this.palette),
					List.copyOf(this.terrain),
					List.copyOf(this.boundary)
			);
		}
	}

	/** Converts the persisted entries in bounded server-tick batches after a restart. */
	private static final class WorldRevealSnapshotLoadTask {
		private final List<PersistedSnapshotBlock> terrain;
		private final List<PersistedSnapshotBlock> boundary;
		private final List<BlockState> palette;
		private int terrainIndex;
		private int boundaryIndex;
		private boolean readingBoundary;

		private WorldRevealSnapshotLoadTask(PersistedWorldRevealSnapshot snapshot) {
			this.terrain = snapshot.terrain();
			this.boundary = snapshot.boundary();
			this.palette = new ArrayList<>(snapshot.palette().size());
			for (String descriptor : snapshot.palette()) {
				this.palette.add(parseWorldRevealBlockState(descriptor));
			}
		}

		private boolean hasNext() {
			return this.terrainIndex < this.terrain.size() || this.boundaryIndex < this.boundary.size();
		}

		private PersistedSnapshotBlock next() {
			if (this.terrainIndex < this.terrain.size()) {
				this.readingBoundary = false;
				return this.terrain.get(this.terrainIndex++);
			}
			this.readingBoundary = true;
			return this.boundary.get(this.boundaryIndex++);
		}

		private boolean readingBoundary() {
			return this.readingBoundary;
		}

		private BlockState stateAt(int index) {
			return index < 0 || index >= this.palette.size() ? null : this.palette.get(index);
		}
	}

	private static String serializeWorldRevealBlockState(BlockState state) {
		if (state == null) {
			return "minecraft:air";
		}
		Identifier blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
		StringBuilder descriptor = new StringBuilder(blockId == null ? "minecraft:air" : blockId.toString());
		if (state.getProperties().isEmpty()) {
			return descriptor.toString();
		}
		descriptor.append('[');
		boolean first = true;
		for (Property<?> property : state.getProperties()) {
			if (!first) {
				descriptor.append(',');
			}
			first = false;
			descriptor.append(property.getName())
					.append('=')
					.append(serializeWorldRevealPropertyValue(state, property));
		}
		return descriptor.append(']').toString();
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static String serializeWorldRevealPropertyValue(BlockState state, Property<?> property) {
		Property rawProperty = property;
		return rawProperty.getName(state.getValue(rawProperty));
	}

	private static BlockState parseWorldRevealBlockState(String descriptor) {
		if (descriptor == null || descriptor.isBlank()) {
			return null;
		}
		int propertiesStart = descriptor.indexOf('[');
		String rawBlockId = propertiesStart < 0 ? descriptor : descriptor.substring(0, propertiesStart);
		Identifier blockId = Identifier.tryParse(rawBlockId);
		if (blockId == null || !BuiltInRegistries.BLOCK.containsKey(blockId)) {
			Lg2.LOGGER.warn("Skipping unavailable block state {} from season-start terrain snapshot.", descriptor);
			return null;
		}
		Block block = BuiltInRegistries.BLOCK.getValue(blockId);
		BlockState state = block.defaultBlockState();
		if (propertiesStart < 0 || !descriptor.endsWith("]")) {
			return state;
		}
		String properties = descriptor.substring(propertiesStart + 1, descriptor.length() - 1);
		if (properties.isBlank()) {
			return state;
		}
		for (String entry : properties.split(",")) {
			int delimiter = entry.indexOf('=');
			if (delimiter <= 0 || delimiter >= entry.length() - 1) {
				continue;
			}
			Property<?> property = block.getStateDefinition().getProperty(entry.substring(0, delimiter));
			if (property != null) {
				state = applyWorldRevealProperty(state, property, entry.substring(delimiter + 1));
			}
		}
		return state;
	}

	private static <T extends Comparable<T>> BlockState applyWorldRevealProperty(BlockState state, Property<T> property, String value) {
		return property.getValue(value)
				.map(parsedValue -> state.setValue(property, parsedValue))
				.orElse(state);
	}

	private record GuidanceSnapshot(double horizontalDistance, double deltaYaw, boolean aligned, int distanceBucket, float playerYaw) {
		private GuidanceSnapshot withAlignmentLock() {
			return new GuidanceSnapshot(horizontalDistance, 0.0D, true, distanceBucket, playerYaw);
		}
	}

	private record GuidanceInstruction(String stateKey, String groupKey, String[] triggers, long cooldownTicks) {
	}

	private record GuidanceRoute(List<Vec3> waypoints) {
	}

	private record ServerStructureBounds(double minX, double maxX, double minZ, double maxZ) {
	}

	private record ResolvedServerStructure(BlockPos anchor, Direction.Axis axis) {
	}

	private record WorldRevealSeed(Vec3 point, int round, double crackRadius, double terrainRadius) {
	}

	private record WorldRevealPlanInput(
			BlockPos anchor,
			BoxGeometry outerGeometry,
			long randomSeed,
			Set<Long> structureFootprint,
			Set<Long> shellCandidates,
			Map<Long, BlockState> terrainTargets,
			Map<Long, Integer> surfaceY,
			Set<Long> requiredPositions
	) {
	}

	private record WorldRevealPlan(Set<Long> requiredPositions, List<WorldRevealEpisode> episodes) {
		private static WorldRevealPlan empty() {
			return new WorldRevealPlan(Set.of(), List.of());
		}
	}

	private record WorldRevealEpisode(List<BlockPos> revealPositions, List<Vec3> crackPoints) {
	}

	private record WorldRevealFaceConstraint(Direction.Axis axis, double value) {
	}

	private record WorldRevealBurst(int offsetTicks, float growthWeight, int particleBudget, float impactStrength) {
	}

	private enum TurnHintDirection {
		NONE,
		LEFT,
		RIGHT
	}

	private enum WorldRevealPhase {
		NONE,
		CRACKING,
		BLACKOUT_FADE,
		RELOCATE,
		SETTLE
	}

	private enum VerticalAimHint {
		NONE,
		UP,
		DOWN
	}

	private static final class PersistedState {
		private boolean bootstrapComplete;
		private boolean active;
		private boolean completed;
		private boolean worldRevealActive;
		private String serverAnchor;
		private String serverAxis;
		private String difficultyBeforeSeasonStart;
		private PersistedPristineWorldFreezeState pristineWorldFreeze;
		private int sharedLaunchCollectedBitcoins;
		private int sharedLaunchRequiredBitcoins;
		private int sharedLaunchBitcoinSpawned;
		private int sharedLaunchBitcoinSupplyVersion;
		private boolean sharedLaunchIntroTriggered;
		private boolean sharedLaunchServerPowerNarrationTriggered;
		private boolean sharedLaunchRaceControlsTriggered;
		private boolean menuExplanationActive;
		private Map<String, PersistedPlayerState> players;
	}

	private record PristineWorldFreezeState(
			boolean advanceTime,
			boolean advanceWeather,
			boolean spawnMobs,
			boolean mobGriefing,
			int randomTickSpeed,
			boolean projectilesCanBreakBlocks,
			boolean tntExplodes,
			boolean spreadVines,
			int fireSpreadRadius
	) {
		private static PristineWorldFreezeState fromPersisted(PersistedPristineWorldFreezeState state) {
			return state == null ? null : new PristineWorldFreezeState(
					state.advanceTime,
					state.advanceWeather,
					state.spawnMobs,
					state.mobGriefing,
					Math.max(0, state.randomTickSpeed),
					state.projectilesCanBreakBlocks,
					state.tntExplodes,
					state.spreadVines,
					Math.max(0, state.fireSpreadRadius)
			);
		}

		private PersistedPristineWorldFreezeState toPersisted() {
			PersistedPristineWorldFreezeState state = new PersistedPristineWorldFreezeState();
			state.advanceTime = advanceTime;
			state.advanceWeather = advanceWeather;
			state.spawnMobs = spawnMobs;
			state.mobGriefing = mobGriefing;
			state.randomTickSpeed = randomTickSpeed;
			state.projectilesCanBreakBlocks = projectilesCanBreakBlocks;
			state.tntExplodes = tntExplodes;
			state.spreadVines = spreadVines;
			state.fireSpreadRadius = fireSpreadRadius;
			return state;
		}
	}

	private static final class PersistedPristineWorldFreezeState {
		private boolean advanceTime;
		private boolean advanceWeather;
		private boolean spawnMobs;
		private boolean mobGriefing;
		private int randomTickSpeed;
		private boolean projectilesCanBreakBlocks;
		private boolean tntExplodes;
		private boolean spreadVines;
		private int fireSpreadRadius;
	}

	private static final class PersistedPlayerState {
		private int slotIndex;
		private String phase;
		private boolean minedIntroBitcoin;
		private boolean introOreRevealed;
		private boolean poweredServer;
		private int guidedBitcoinEscapeCount;
		private int lockedGuidedBitcoinSlot = NO_LOCKED_GUIDED_BITCOIN_SLOT;
		private String escapedGuidedOfferingId;
		private boolean menuOpened;
		private boolean menuNarrationMuted;
		private boolean raceMenuReached;
		private boolean racePurchaseExplained;
		private boolean raceMenuReminderExplained;
		private boolean menuRaceAllowanceGranted;
		private List<String> seenMenuSections;
	}
}
