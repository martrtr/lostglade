package com.lostglade.server;

import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.datafixers.util.Pair;
import com.lostglade.Lg2;
import com.lostglade.block.ModBlocks;
import com.lostglade.item.DroneItem;
import com.lostglade.item.ModItems;
import com.lostglade.mixin.ClientboundMoveEntityPacketAccessor;
import com.lostglade.mixin.ClientboundSetPassengersPacketAccessor;
import com.lostglade.util.ItemDisplayHitboxHelper;
import com.lostglade.mixin.DisplayTrackedDataAccessor;
import com.lostglade.mixin.EntityTrackedDataAccessor;
import com.lostglade.mixin.PlayerTrackedDataAccessor;
import com.lostglade.server.map.MapImageRenderSystem;
import de.maxhenkel.voicechat.api.Position;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import de.maxhenkel.voicechat.plugins.impl.packets.LocationalSoundPacketImpl;
import de.maxhenkel.voicechat.voice.common.LocationSoundPacket;
import eu.pb4.polymer.core.api.entity.PolymerEntityUtils;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import com.mojang.math.Transformation;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.core.dispenser.DefaultDispenseItemBehavior;
import net.minecraft.core.dispenser.DispenseItemBehavior;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.RemoteChatSession;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.BossEvent;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.arrow.SpectralArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEgg;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownExperienceBottle;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownLingeringPotion;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.vehicle.minecart.MinecartHopper;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class DroneSystem {
	private static final String IT_DRONE_SCOUT = "it_drone_scout";
	private static final String IT_DRONE_KAMIKAZE = "it_drone_kamikaze";
	private static final String IT_DRONE_COMBAT = "it_drone_combat";
	private static final String IT_DRONE_PAINT = "it_drone_paint";
	private static final String IT_DRONE_NIGHT_VISION = "it_drone_night_vision";
	private static final String IT_DRONE_AUTO_AIM = "it_drone_auto_aim";
	private static final String IT_DRONE_MICROPHONE = "it_drone_microphone";
	private static final String DRONE_ROOT_TAG = "lg2_drone_root";
	private static final String DRONE_TYPE_TAG_PREFIX = "lg2_drone_type_";
	private static final String DRONE_KAMIKAZE_POWER_TAG_PREFIX = "lg2_drone_kamikaze_power_";
	private static final String DRONE_NIGHT_VISION_TAG = "lg2_drone_night_vision";
	private static final String DRONE_AUTO_AIM_TAG = "lg2_drone_auto_aim";
	private static final String DRONE_AUTO_AIM_TARGET_ENTITY_TAG_PREFIX = "lg2_drone_auto_aim_target_entity_";
	private static final String DRONE_AUTO_AIM_TARGET_BLOCK_TAG_PREFIX = "lg2_drone_auto_aim_target_block_";
	private static final String DRONE_MICROPHONE_TAG = "lg2_drone_microphone";
	private static final String DRONE_PAINT_TAG_PREFIX = "lg2_drone_paint_";
	private static final String DRONE_DISPLAY_TAG = "lg2_drone_display";
	private static final String DRONE_DISPLAY_OWNER_TAG_PREFIX = "lg2_drone_display_owner_";
	private static final String DRONE_DISPLAY_LAYER_TAG_PREFIX = "lg2_drone_display_layer_";
	private static final String DRONE_DISPLAY_LAYER_BASE = "base";
	private static final String DRONE_CAMERA_TAG = "lg2_drone_camera_anchor";
	private static final String DRONE_CAMERA_OWNER_TAG_PREFIX = "lg2_drone_camera_owner_";
	private static final String DRONE_NIGHT_VISION_CAMERA_TAG = "lg2_drone_night_vision_camera";
	private static final String DRONE_TURRET_TRIGGER_TAG = "lg2_drone_turret_trigger";
	private static final String DRONE_TURRET_TRIGGER_OWNER_TAG_PREFIX = "lg2_drone_turret_trigger_owner_";
	// Stored on the root entity so an abrupt restart can recover the last stable
	// drone pose without trusting Minecraft's in-flight Motion value.
	private static final String DRONE_RESTART_RECOVERY_TAG_PREFIX = "lg2_drone_restart_recovery_";
	private static final Identifier DRONE_LOOP_SOUND_ID = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "drone_loop");
	private static final Identifier DRONE_KAMIKAZE_LOOP_SOUND_ID = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "drone_kamikaze_loop");
	private static final Identifier DRONE_BREAK_SOUND_ID = Identifier.fromNamespaceAndPath("minecraft", "entity.firework_rocket.blast");
	private static final Holder<SoundEvent> DRONE_LOOP_SOUND = Holder.direct(SoundEvent.createVariableRangeEvent(DRONE_LOOP_SOUND_ID));
	private static final Holder<SoundEvent> DRONE_KAMIKAZE_LOOP_SOUND = Holder.direct(
			SoundEvent.createVariableRangeEvent(DRONE_KAMIKAZE_LOOP_SOUND_ID)
	);
	private static final double DRONE_CRASH_EQUIVALENT_FALL_BLOCKS = 3.25D;
	private static final double DRONE_CRASH_REFERENCE_ACCELERATION = 0.04D;
	private static final double DRONE_SURFACE_WEAR_DECAY_PER_TICK = 0.018D;
	private static final double DRONE_UNCONTROLLED_SURFACE_WEAR_MULTIPLIER = 1.35D;
	private static final double DRONE_COLLISION_SWEEP_STEP = 0.12D;
	private static final int DRONE_SURFACE_WEAR_PARTICLE_INTERVAL_TICKS = 2;
	private static final int DRONE_SURFACE_WEAR_PARTICLE_MULTIPLIER = 2;
	private static final int DRONE_SUBMERGED_SURFACE_WEAR_PARTICLE_MULTIPLIER = 4;
	private static final double VANILLA_PARTICLE_SEND_RANGE_SQR = 32.0D * 32.0D;
	private static final float DRONE_WIDTH = DroneGeometry.WIDTH;
	private static final float DRONE_HEIGHT = DroneGeometry.HEIGHT;
	private static final float DRONE_CAMERA_ANCHOR_SIZE = 0.01F;
	private static final double DRONE_SPAWN_Y_OFFSET = 0.24D;
	private static final float DRONE_DISPLAY_VIEW_RANGE = 64.0F;
	// The fixed item-display pivot sits on the middle of the extracted rig.
	// Lift the display just enough so the lowest visible geometry rests on the ground.
	private static final float DRONE_DISPLAY_Y_OFFSET = 0.13125F;
	// Additional lift for module variants that hang lower than the base rig.
	private static final double DRONE_KAMIKAZE_VISUAL_LIFT = 0.24375D;
	private static final double DRONE_TURRET_VISUAL_LIFT = 0.2125D;
	// Operator-only local tuning for the passenger-mounted body preview while controlling a drone.
	private static final float CONTROLLED_OPERATOR_PASSENGER_DISPLAY_Y_OFFSET = 1.652F;
	private static final float CONTROLLED_OPERATOR_PASSENGER_DISPLAY_Z_OFFSET = -0.3F;
	private static final int DRONE_DISPLAY_INTERPOLATION_TICKS = 2;
	private static final float DRONE_DISPLAY_DRIVE_SMOOTHING = 0.35F;
	private static final float DRONE_MAX_TILT_DEGREES = 32.0F;
	private static final long DRONE_LOOP_REPLAY_TICKS = 10L;
	private static final double DRONE_SOUND_RADIUS_SQR = 16.0D * 16.0D;
	private static final float DRONE_SOUND_SOURCE_POWER = 0.58F;
	private static final float DRONE_SOUND_MIN_VOLUME = 1.0F;
	private static final float DRONE_SOUND_MAX_VOLUME = 1.0F;
	private static final float DRONE_SOUND_MIN_PITCH = 0.76F;
	private static final float DRONE_SOUND_MAX_PITCH = 1.18F;
	private static final float DRONE_KAMIKAZE_LOOP_LEVEL_1_VOLUME_SCALE = 0.42F;
	private static final float DRONE_KAMIKAZE_LOOP_LEVEL_2_VOLUME_SCALE = 0.76F;
	private static final float DRONE_KAMIKAZE_LOOP_LEVEL_3_VOLUME_SCALE = 1.15F;
	private static final float DRONE_KAMIKAZE_LOOP_PITCH_SHIFT = 1.08F;
	private static final int DRONE_KAMIKAZE_NO_POWER = 0;
	private static final int DRONE_KAMIKAZE_MIN_POWER = 1;
	private static final int DRONE_KAMIKAZE_MAX_POWER = 3;
	private static final float DRONE_KAMIKAZE_TNT_SPREAD = 0.26F;
	private static final float DRONE_VANILLA_WOBBLE_ROTATION_SCALE = 0.015625F;
	private static final float DRONE_VANILLA_WOBBLE_NEGATIVE_SCALE = 0.125F;
	private static final float DRONE_VANILLA_WOBBLE_PROGRESS_TO_RADIANS = (float) (Math.PI * 2.0D);
	private static final float DRONE_VANILLA_WOBBLE_NEGATIVE_PROGRESS_TO_RADIANS = (float) (Math.PI * 3.0D);
	private static final double UNCONTROLLED_GRAVITY = 0.04D;
	private static final double UNCONTROLLED_AIR_DRAG = 0.985D;
	private static final double DRONE_WATER_HORIZONTAL_DRAG = 0.88D;
	private static final double DRONE_WATER_VERTICAL_DRAG = 0.78D;
	private static final double DRONE_WATER_BUOYANCY = 0.005D;
	private static final double DRONE_WATER_FLOW_SCALE = 0.008D;
	private static final double DRONE_WATER_STRESS_MIN_SPEED = 0.16D;
	private static final double DRONE_WATER_STRESS_BASE_DAMAGE_PER_TICK = 0.006D;
	private static final double DRONE_WATER_STRESS_DAMAGE_PER_TICK = 0.045D;
	private static final double DRONE_LAVA_HORIZONTAL_DRAG = 0.50D;
	private static final double DRONE_LAVA_VERTICAL_DRAG = 0.35D;
	private static final double DRONE_COBWEB_HORIZONTAL_DRAG = 0.25D;
	private static final double DRONE_COBWEB_VERTICAL_DRAG = 0.05D;
	private static final double DRONE_SOUL_SAND_HORIZONTAL_DRAG = 0.42D;
	private static final double DRONE_HONEY_HORIZONTAL_DRAG = 0.45D;
	private static final double DRONE_SLIME_BOUNCE_MIN_SPEED = 0.08D;
	private static final double DRONE_SLIME_BOUNCE_MULTIPLIER = 0.82D;
	private static final double DRONE_BUBBLE_COLUMN_PUSH = 0.10D;
	private static final double DRONE_EXTERNAL_PUSH_MAX_DISTANCE = 2.0D;
	private static final double DRONE_EXTERNAL_PUSH_IMPULSE_SCALE = 0.45D;
	private static final double DRONE_EXTERNAL_PUSH_MAX_COMPONENT = 0.34D;
	private static final double DRONE_ENTITY_PUSH_SEARCH_XZ = 0.10D;
	private static final double DRONE_ENTITY_PUSH_SEARCH_Y = 0.36D;
	private static final double DRONE_ENVIRONMENT_BREAK_DAMAGE = 1.0D;
	private static final double DRONE_LAVA_DAMAGE_PER_TICK = 1.20D;
	private static final double DRONE_FIRE_DAMAGE_PER_TICK = 0.10D;
	private static final double DRONE_MAGMA_DAMAGE_PER_TICK = 0.045D;
	private static final double DRONE_ENVIRONMENT_DAMAGE_DECAY_PER_TICK = 0.02D;
	private static final double DRONE_ENVIRONMENT_WATER_COOLING_PER_TICK = 0.08D;
	private static final double DRONE_GROUND_HORIZONTAL_DRAG = 0.32D;
	private static final double DRONE_GROUND_STOP_HORIZONTAL_SPEED_SQR = 2.5E-4D;
	private static final double DRONE_GROUND_STOP_VERTICAL_SPEED = 0.05D;
	private static final float UNCONTROLLED_ROTATION_LERP = 0.35F;
	private static final double UNCONTROLLED_SETTLED_HORIZONTAL_SPEED_SQR = 2.5E-4D;
	private static final double UNCONTROLLED_SETTLED_VERTICAL_SPEED = 0.05D;
	private static final long UNCONTROLLED_DRONE_RELEASE_GLIDE_TICKS = 60L;
	private static final double DRONE_RESTART_RECOVERY_SPEED = 0.16D;
	private static final double DRONE_RESTART_RECOVERY_COMPLETE_DISTANCE = 0.035D;
	private static final long CONTROLLED_DRONE_MISSING_ROOT_GRACE_TICKS = 20L * 20L;
	private static final int DRONE_TURRET_INVENTORY_SIZE = 9;
	private static final long DRONE_TURRET_FIRE_COOLDOWN_TICKS = 4L;
	private static final long DRONE_TURRET_CONTROL_START_SUPPRESS_TICKS = 6L;
	private static final double DRONE_RELEASE_IDLE_DRIVE_EPSILON = 1.0E-4D;
	private static final long DRONE_TURRET_AUTOMATION_INTERVAL_TICKS = 8L;
	private static final float DRONE_TURRET_AIR_TRIGGER_WIDTH = 1.6F;
	private static final float DRONE_TURRET_AIR_TRIGGER_HEIGHT = 1.6F;
	private static final double DRONE_TURRET_AIR_TRIGGER_HEAD_FORWARD_OFFSET = 0.24D;
	private static final double DRONE_TURRET_MUZZLE_FORWARD_OFFSET = 0.62D;
	private static final float DRONE_TURRET_POTION_SPEED = 1.75F;
	private static final int DRONE_SURFACE_WEAR_DUST_DEFAULT_COLOR = 0x242424;
	private static final long DRONE_CONTROL_PRELOAD_TIMEOUT_TICKS = 20L * 8L;
	private static final int DRONE_CONTROL_PRELOAD_READY_RADIUS_CHUNKS = 2;
	private static final int DRONE_LOADING_CHUNK_TICKET_UNIQUE_FLAG = 32;
	private static final int DRONE_SIMULATION_CHUNK_TICKET_UNIQUE_FLAG = 64;
	private static final int OPERATOR_BODY_MIRROR_ENTITY_ID_START = -1_700_000_000;
	private static final byte OPERATOR_BODY_MIRROR_ALL_SKIN_PARTS = (byte) 0x7F;
	// TicketType equality is based on timeout/flags, so keep these distinct from vanilla player tickets.
	private static final TicketType DRONE_LOADING_CHUNK_TICKET_TYPE = new TicketType(
			0L,
			TicketType.FLAG_LOADING | DRONE_LOADING_CHUNK_TICKET_UNIQUE_FLAG
	);
	private static final TicketType DRONE_SIMULATION_CHUNK_TICKET_TYPE = new TicketType(
			0L,
			TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE | DRONE_SIMULATION_CHUNK_TICKET_UNIQUE_FLAG
	);
	private static final int PLAYER_HOTBAR_MENU_SLOT_START = 36;
	private static final int PLAYER_OFFHAND_MENU_SLOT = 45;
	private static final Set<Relative> ABSOLUTE_TELEPORT = EnumSet.noneOf(Relative.class);
	private static final ServerEntity.Synchronizer DRONE_RELEASE_VISUAL_RESYNC_NOOP_SYNCHRONIZER = new ServerEntity.Synchronizer() {
		@Override
		public void sendToTrackingPlayers(Packet<? super ClientGamePacketListener> packet) {
		}

		@Override
		public void sendToTrackingPlayersAndSelf(Packet<? super ClientGamePacketListener> packet) {
		}

		@Override
		public void sendToTrackingPlayersFiltered(
				Packet<? super ClientGamePacketListener> packet,
				java.util.function.Predicate<ServerPlayer> predicate
		) {
		}
	};
	private static final long DRONE_HUD_REFRESH_TICKS = 2L;
	private static final int DRONE_HUD_GRID_SIZE = 11;
	private static final int DRONE_HUD_GLYPH_BASE = 0xE700;
	private static final int DRONE_HUD_SPEED_BAR_GLYPH_BASE = 0xE780;
	private static final int DRONE_HUD_HEADING_GLYPH_BASE = 0xE7A0;
	private static final int DRONE_HUD_HEADING_FRAME_COUNT = 10;
	private static final int DRONE_HUD_HEADING_DIGIT_GLYPH_BASE = 0xE8D0;
	private static final int DRONE_HUD_ATTITUDE_GLYPH_BASE = 0xE7F0;
	private static final int DRONE_HUD_ATTITUDE_FRAME_COUNT = 5;
	private static final int DRONE_HUD_ATTITUDE_LABEL_GLYPH_BASE = 0xEAB0;
	private static final int DRONE_HUD_ATTITUDE_LABEL_FRAME_COUNT = 39;
	private static final int DRONE_HUD_BANK_GLYPH_BASE = 0xE800;
	private static final int DRONE_HUD_BANK_FRAME_COUNT = 13;
	private static final int DRONE_HUD_GLITCH_IDLE_GLYPH_BASE = 0xE600;
	private static final int DRONE_HUD_GLITCH_IDLE_FRAME_COUNT = 32;
	private static final int DRONE_HUD_GLITCH_BURST_GLYPH_BASE = 0xE860;
	private static final int DRONE_HUD_GLITCH_BURST_FRAME_COUNT = 6;
	private static final long DRONE_HUD_GLITCH_IDLE_FRAME_TICKS = 1L;
	private static final int DRONE_HUD_GLITCH_TILES_PER_FRAME = 8;
	private static final String DRONE_HUD_GLITCH_ROW_REWIND_GLYPH = "\uE890";
	private static final FontDescription DRONE_HUD_GLITCH_FONT = new FontDescription.Resource(
			Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "drone_glitch")
	);
	private static final String DRONE_HUD_BAR_OVERLAP_GLYPH = "\uE944";
	private static final String DRONE_HUD_CENTER_GLYPH_REWIND = "\uE940\uE94B\uE946";
	private static final String DRONE_HUD_HEADING_DIGIT_REWIND = "\uE940\uE94C\uE947";
	private static final String DRONE_HUD_HEADING_DIGIT_RESTORE = "\uE94B\uE948\uE947";
	private static final int DRONE_HUD_LABEL_COLOR = 0x6BD7FF;
	private static final int DRONE_HUD_VALUE_COLOR = 0xF4FFF6;
	private static final int DRONE_HUD_DIM_COLOR = 0x5A7080;
	private static final byte ENTITY_FLAG_ON_FIRE = 0x01;
	private static final byte ENTITY_FLAG_SHIFTING = 0x02;
	private static final byte ENTITY_FLAG_SPRINTING = 0x08;
	private static final byte ENTITY_FLAG_SWIMMING = 0x10;
	private static final byte ENTITY_FLAG_INVISIBLE = 0x20;
	private static final byte ENTITY_FLAG_FALL_FLYING = (byte) 0x80;
	private static final int CONTROLLED_VIEW_TELEPORT_ID_BASE = 1_000_000_000;
	private static final long CONTROLLED_DRONE_START_ROTATION_SUPPRESSION_TICKS = 20L;
	private static final long CONTROLLED_DRONE_START_POSITION_SUPPRESSION_TICKS = 20L;
	private static final long CONTROLLED_DRONE_TELEPORT_ACK_ROTATION_GRACE_TICKS = 2L;
	private static final double CONTROLLED_DRONE_BLOCKED_MOVEMENT_EPSILON = 1.0E-5D;
	private static final double CONTROLLED_DRONE_MAX_REPORTED_MOVE_BLOCKS = 4.0D;
	private static final long POST_CONTROL_MOVE_PACKET_SUPPRESSION_TICKS = 20L;
	private static final long POST_CONTROL_CLIENT_RESYNC_TICKS = 8L;
	private static final long POST_CONTROL_VIEW_TELEPORT_ACK_GRACE_TICKS = 40L;
	private static final double POST_CONTROL_MOVE_ACCEPT_DISTANCE_SQR = 2.0D * 2.0D;
	private static final int DRONE_MANAGED_NIGHT_VISION_AMPLIFIER = 1;
	private static final double CONTROLLED_OPERATOR_DISABLED_INTERACTION_RANGE_BLOCKS = 0.01D;
	private static final double DRONE_AUTO_AIM_SELECTION_RANGE_BLOCKS = 64.0D;
	private static final double DRONE_AUTO_AIM_INTERACTION_RANGE_BONUS = 64.0D;
	private static final float DRONE_AUTO_AIM_CONTROLLED_ROTATION_BLEND = 0.08F;
	private static final float DRONE_AUTO_AIM_CONTROLLED_MAX_YAW_STEP_DEGREES = 0.78F;
	private static final float DRONE_AUTO_AIM_CONTROLLED_MAX_PITCH_STEP_DEGREES = 0.60F;
	private static final float DRONE_AUTO_AIM_MANUAL_LOOK_THRESHOLD_DEGREES = 0.08F;
	private static final long DRONE_AUTO_AIM_MANUAL_SUPPRESSION_TICKS = 3L;
	private static final float DRONE_AUTO_AIM_UNCONTROLLED_ROTATION_BLEND = 0.09F;
	private static final float DRONE_AUTO_AIM_UNCONTROLLED_MAX_YAW_STEP_DEGREES = 1.00F;
	private static final float DRONE_AUTO_AIM_UNCONTROLLED_MAX_PITCH_STEP_DEGREES = 0.75F;
	private static final int DRONE_AUTO_AIM_DISPLAY_FRAME_PAIR_COUNT = 4;
	private static final long DRONE_AUTO_AIM_DISPLAY_MIN_FRAME_HOLD_TICKS = 4L;
	private static final long DRONE_AUTO_AIM_DISPLAY_MAX_FRAME_HOLD_TICKS = 8L;
	private static final Identifier DRONE_AUTO_AIM_BLOCK_INTERACTION_RANGE_MODIFIER_ID = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "drone_auto_aim_block_interaction_range");
	private static final Identifier DRONE_AUTO_AIM_ENTITY_INTERACTION_RANGE_MODIFIER_ID = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "drone_auto_aim_entity_interaction_range");
	private static final double DRONE_STATIONARY_BREAK_HORIZONTAL_SPEED_SQR = 4.0E-4D;
	private static final double DRONE_STATIONARY_BREAK_VERTICAL_SPEED = 0.05D;
	private static final int CONTROLLED_OPERATOR_AUDIO_SAMPLE_RATE = 48_000;
	private static final int CONTROLLED_OPERATOR_AUDIO_FRAME_SAMPLES = 960;
	private static final long CONTROLLED_OPERATOR_AUDIO_FRAME_NANOS = TimeUnit.MILLISECONDS.toNanos(
			CONTROLLED_OPERATOR_AUDIO_FRAME_SAMPLES * 1000L / CONTROLLED_OPERATOR_AUDIO_SAMPLE_RATE
	);
	private static final int CONTROLLED_OPERATOR_AUDIO_FRAME_BUFFER_CAPACITY = 192;
	private static final long CONTROLLED_OPERATOR_AUDIO_MAX_FRAME_AGE = 3L;
	private static final long CONTROLLED_OPERATOR_AUDIO_SOURCE_EXPIRE_AFTER_FRAMES = 12L;
	private static final double CONTROLLED_OPERATOR_BODY_VOICE_WHISPER_DISTANCE_FACTOR = 0.5D;
	private static final float CONTROLLED_OPERATOR_BODY_VOICE_GAIN = 1.0F;
	private static final short[] CONTROLLED_OPERATOR_AUDIO_SILENCE_FRAME = new short[CONTROLLED_OPERATOR_AUDIO_FRAME_SAMPLES];
	private static final double DRONE_CAMERA_ESCAPE_STEP = 0.04D;
	private static final int DRONE_CAMERA_ESCAPE_XZ_RADIUS_STEPS = 4;
	private static final double[] DRONE_CAMERA_ESCAPE_Y_OFFSETS = new double[]{
			0.0D,
			-0.05D,
			0.05D,
			-0.10D,
			0.10D,
			-0.15D,
			-0.20D,
			-0.25D,
			-0.30D,
			-0.35D,
			-0.40D
	};
	private static final Map<UUID, DroneControlSession> ACTIVE_SESSIONS = new HashMap<>();
	private static final Map<UUID, ServerBossEvent> PLAYER_DRONE_HUDS = new HashMap<>();
	private static final Map<UUID, Component> PLAYER_DRONE_HUD_TITLES = new HashMap<>();
	private static final Map<UUID, ServerBossEvent> PLAYER_DRONE_GLITCH_OVERLAYS = new HashMap<>();
	private static final Map<UUID, ServerBossEvent> PLAYER_DRONE_GLITCH_BURSTS = new HashMap<>();
	private static final Map<UUID, Long> PLAYER_DRONE_GLITCH_BURST_START_TICKS = new HashMap<>();
	// Once a HUD has begun closing, its UUID must never be recreated by a late
	// ServerBossEvent update. The set lives until the player disconnects; every
	// new HUD event has a fresh UUID.
	private static final Map<UUID, Set<UUID>> CLOSING_DRONE_HUD_BOSS_BARS = new HashMap<>();
	private static final Map<UUID, DroneInputState> INPUTS = new HashMap<>();
	private static final Map<UUID, UUID> CONTROLLERS_BY_DRONE = new HashMap<>();
	private static final Map<UUID, UUID> DISPLAYS_BY_DRONE = new HashMap<>();
	private static final Map<UUID, LinkedHashSet<UUID>> DISPLAY_LAYERS_BY_DRONE = new HashMap<>();
	private static final Map<UUID, UUID> CAMERA_ANCHORS_BY_DRONE = new HashMap<>();
	private static final Set<Item> DRONE_TURRET_PROJECTILES = Set.of(
			Items.ARROW,
			Items.TIPPED_ARROW,
			Items.SPECTRAL_ARROW,
			Items.TRIDENT,
			Items.FIREWORK_ROCKET,
			Items.FIRE_CHARGE,
			Items.WIND_CHARGE,
			Items.SNOWBALL,
			Items.EGG,
			Items.EXPERIENCE_BOTTLE,
			Items.SPLASH_POTION,
			Items.LINGERING_POTION
	);
	private static final Map<UUID, TurretInventory> DRONE_TURRET_INVENTORIES = new HashMap<>();
	private static final Map<UUID, Long> NEXT_DRONE_TURRET_FIRE_TICK = new HashMap<>();
	private static final Map<UUID, UUID> CONTROLLED_DRONE_TURRET_TRIGGERS = new HashMap<>();
	private static final Map<UUID, UncontrolledDroneState> UNCONTROLLED_DRONES = new HashMap<>();
	private static final Map<DroneChunkTicketKey, Integer> ACTIVE_DRONE_CHUNK_TICKETS = new HashMap<>();
	private static final Map<UUID, DroneScreenStreamLoadState> SCREEN_STREAM_DRONE_LOAD_STATES = new HashMap<>();
	private static final Map<UUID, DroneScreenStreamLoadState> LAST_KNOWN_DRONE_FEED_STATES = new HashMap<>();
	private static final Set<UUID> POWERED_SCREEN_LINKED_DRONES = new HashSet<>();
	private static final Map<UUID, PendingDroneControlStart> PENDING_CONTROL_STARTS = new HashMap<>();
	private static final Map<UUID, OperatorBodyMirror> OPERATOR_BODY_MIRRORS = new HashMap<>();
	private static final Map<UUID, Long> NEXT_DRONE_SOUND_TICK = new HashMap<>();
	private static final Map<UUID, Long> NEXT_DRONE_ARM_ALLOWED_TICK = new HashMap<>();
	private static final Map<UUID, DroneDisplayWobbleState> DISPLAY_WOBBLE_BY_DRONE = new HashMap<>();
	private static final Map<UUID, DroneAutoAimDisplayAnimationState> AUTO_AIM_DISPLAY_ANIMATIONS = new HashMap<>();
	private static final Map<UUID, Double> DRONE_ENVIRONMENT_DAMAGE = new HashMap<>();
	private static final Map<UUID, Long> POST_CONTROL_MOVE_SUPPRESSED_UNTIL_TICK = new HashMap<>();
	private static final Map<UUID, Long> POST_CONTROL_CLIENT_RESYNC_UNTIL_TICK = new HashMap<>();
	private static final Map<UUID, Long> POST_CONTROL_VIEW_TELEPORT_ACK_UNTIL_TICK = new HashMap<>();
	private static final Map<UUID, ControlledOperatorAudioRuntime> CONTROLLED_OPERATOR_AUDIO = new HashMap<>();
	private static final Map<UUID, Long> DRONE_MICROPHONE_RELAY_SEQUENCES = new HashMap<>();
	private static final Set<UUID> VISUALLY_CONTROLLED_PLAYERS = new HashSet<>();
	private static final Set<UUID> CONTROLLED_OPERATOR_MANAGED_NIGHT_VISION = new HashSet<>();
	private static final Set<UUID> CONTROLLED_OPERATOR_AUTO_AIM_HIGHLIGHTS = new HashSet<>();
	private static final Set<UUID> CONTROLLED_OPERATOR_AUTO_AIM_BODY_HIGHLIGHTS = new HashSet<>();
	private static final Set<PendingDroneLoadDiscard> PENDING_DRONE_LOAD_DISCARDS = new LinkedHashSet<>();
	private static final ThreadLocal<Boolean> CONTROLLED_OPERATOR_PACKET_REWRITE_BYPASS = ThreadLocal.withInitial(() -> false);
	private static final DispenseItemBehavior DEFAULT_DRONE_DISPENSE_FALLBACK = new DefaultDispenseItemBehavior();
	private static int nextOperatorBodyMirrorEntityId = OPERATOR_BODY_MIRROR_ENTITY_ID_START;

	private DroneSystem() {
	}

	public static void register() {
		registerDroneDispenseBehaviors();
		UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
			if (world.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
				return InteractionResult.PASS;
			}
			if (isControllingDrone(serverPlayer)) {
				if (hand == InteractionHand.MAIN_HAND) {
					InteractionResult autoAimResult = handleControlledAutoAimEntityInteraction(serverPlayer, hand, entity, true);
					if (autoAimResult != InteractionResult.PASS) {
						return autoAimResult;
					}
				}
				return InteractionResult.SUCCESS;
			}
			if (hand != InteractionHand.MAIN_HAND) {
				return InteractionResult.PASS;
			}
			Entity root = resolveDroneRoot(entity);
			if (root == null) {
				return InteractionResult.PASS;
			}
			ItemStack heldStack = serverPlayer.getItemInHand(hand);
			if (heldStack.is(ModItems.BLUETOOTH_ADAPTER)) {
				return InteractionResult.PASS;
			}
			DroneControlSession activeSession = ACTIVE_SESSIONS.get(serverPlayer.getUUID());
			if (activeSession != null && Objects.equals(activeSession.droneUuid(), root.getUUID())) {
				return InteractionResult.CONSUME;
			}
			InteractionResult tuningResult = tryTuneDrone(serverPlayer, root, heldStack);
			if (tuningResult != InteractionResult.PASS) {
				return tuningResult;
			}
			if (serverPlayer.isShiftKeyDown() && hasDroneTurretModule(root)) {
				openDroneTurretMenu(serverPlayer, root);
				return InteractionResult.CONSUME;
			}
			String requiredUpgrade = resolveRequiredUpgradeForDroneRoot(root);
			if (requiredUpgrade != null && !ServerUpgradeUiSystem.hasUpgrade(serverPlayer, requiredUpgrade)) {
				return InteractionResult.FAIL;
			}
			return startControlling(serverPlayer, root) ? InteractionResult.SUCCESS : InteractionResult.CONSUME;
		});

		AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
			if (world.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
				return InteractionResult.PASS;
			}
			if (isControllingDrone(serverPlayer)) {
				InteractionResult autoAimResult = handleControlledAutoAimEntityInteraction(serverPlayer, hand, entity, false);
				if (autoAimResult != InteractionResult.PASS) {
					return autoAimResult;
				}
				return InteractionResult.SUCCESS;
			}
			Entity root = resolveDroneRoot(entity);
			if (root == null) {
				return InteractionResult.PASS;
			}
			destroyDrone(root, serverPlayer, true);
			return InteractionResult.SUCCESS;
		});
		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			if (world.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
				return InteractionResult.PASS;
			}
			InteractionResult autoAimResult = hand == InteractionHand.MAIN_HAND
					? handleControlledAutoAimBlockInteraction(serverPlayer, hand, hitResult == null ? null : hitResult.getBlockPos(), true)
					: InteractionResult.PASS;
			if (autoAimResult != InteractionResult.PASS) {
				return autoAimResult;
			}
			return isControllingDrone(serverPlayer) ? InteractionResult.SUCCESS : InteractionResult.PASS;
		});
		AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
			if (world.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
				return InteractionResult.PASS;
			}
			InteractionResult autoAimResult = handleControlledAutoAimBlockInteraction(serverPlayer, hand, pos, false);
			if (autoAimResult != InteractionResult.PASS) {
				return autoAimResult;
			}
			return isControllingDrone(serverPlayer) ? InteractionResult.SUCCESS : InteractionResult.PASS;
		});

		ServerTickEvents.END_SERVER_TICK.register(DroneSystem::tick);
		ServerEntityEvents.ENTITY_LOAD.register(DroneSystem::onEntityLoad);
		ServerEntityEvents.ENTITY_UNLOAD.register(DroneSystem::onEntityUnload);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			stopControlling((ServerPlayer) handler.player, false);
			CLOSING_DRONE_HUD_BOSS_BARS.remove(handler.player.getUUID());
		});
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> stopControlling(newPlayer, false));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			for (UUID playerId : new ArrayList<>(ACTIVE_SESSIONS.keySet())) {
				ServerPlayer player = server.getPlayerList().getPlayer(playerId);
				if (player != null) {
					stopControlling(player, false);
				}
			}
			releaseAllDroneChunkTickets(server);
			ACTIVE_SESSIONS.clear();
			PLAYER_DRONE_HUDS.clear();
			PLAYER_DRONE_HUD_TITLES.clear();
			PLAYER_DRONE_GLITCH_OVERLAYS.clear();
			PLAYER_DRONE_GLITCH_BURSTS.clear();
			PLAYER_DRONE_GLITCH_BURST_START_TICKS.clear();
			CLOSING_DRONE_HUD_BOSS_BARS.clear();
			INPUTS.clear();
			CONTROLLERS_BY_DRONE.clear();
			DISPLAYS_BY_DRONE.clear();
			DISPLAY_LAYERS_BY_DRONE.clear();
			CAMERA_ANCHORS_BY_DRONE.clear();
			DRONE_TURRET_INVENTORIES.clear();
			NEXT_DRONE_TURRET_FIRE_TICK.clear();
			CONTROLLED_DRONE_TURRET_TRIGGERS.clear();
			UNCONTROLLED_DRONES.clear();
			ACTIVE_DRONE_CHUNK_TICKETS.clear();
			SCREEN_STREAM_DRONE_LOAD_STATES.clear();
			LAST_KNOWN_DRONE_FEED_STATES.clear();
			POWERED_SCREEN_LINKED_DRONES.clear();
			PENDING_CONTROL_STARTS.clear();
			OPERATOR_BODY_MIRRORS.clear();
			NEXT_DRONE_ARM_ALLOWED_TICK.clear();
			POST_CONTROL_MOVE_SUPPRESSED_UNTIL_TICK.clear();
			POST_CONTROL_CLIENT_RESYNC_UNTIL_TICK.clear();
			POST_CONTROL_VIEW_TELEPORT_ACK_UNTIL_TICK.clear();
			VISUALLY_CONTROLLED_PLAYERS.clear();
			DISPLAY_WOBBLE_BY_DRONE.clear();
			AUTO_AIM_DISPLAY_ANIMATIONS.clear();
			DRONE_ENVIRONMENT_DAMAGE.clear();
			DRONE_MICROPHONE_RELAY_SEQUENCES.clear();
			PENDING_DRONE_LOAD_DISCARDS.clear();
			shutdownAllControlledOperatorAudio();
			CONTROLLED_OPERATOR_MANAGED_NIGHT_VISION.clear();
			CONTROLLED_OPERATOR_AUTO_AIM_HIGHLIGHTS.clear();
			CONTROLLED_OPERATOR_AUTO_AIM_BODY_HIGHLIGHTS.clear();
		});
	}

	public static InteractionResult placeDrone(UseOnContext context) {
		if (context == null) {
			return InteractionResult.PASS;
		}
		Level level = context.getLevel();
		if (level.isClientSide()) {
			return InteractionResult.SUCCESS;
		}
		if (!(level instanceof ServerLevel serverLevel) || !(context.getPlayer() instanceof ServerPlayer player)) {
			return InteractionResult.PASS;
		}
		ItemStack placedStack = context.getItemInHand();
		ItemStack placementSnapshot = placedStack.copy();
		String requiredUpgrade = resolveRequiredUpgradeForDroneItem(placementSnapshot);
		if (requiredUpgrade != null && !ServerUpgradeUiSystem.hasUpgrade(player, requiredUpgrade)) {
			return InteractionResult.FAIL;
		}
		if (!spawnConfiguredDrone(serverLevel, placementSnapshot, resolvePlacementPosition(context), player.getYRot())) {
			return InteractionResult.FAIL;
		}

		if (!player.getAbilities().instabuild) {
			context.getItemInHand().shrink(1);
		}
		return InteractionResult.CONSUME;
	}

	private static boolean spawnConfiguredDrone(ServerLevel serverLevel, ItemStack placementSnapshot, Vec3 spawnPos, float yRot) {
		if (serverLevel == null || placementSnapshot == null || placementSnapshot.isEmpty() || spawnPos == null) {
			return false;
		}
		DroneItem.DroneType droneType = DroneItem.getDroneType(placementSnapshot);
		int kamikazePower = DroneItem.getKamikazePower(placementSnapshot);
		boolean nightVision = DroneItem.hasNightVisionModule(placementSnapshot);
		boolean autoAim = DroneItem.hasAutoAimModule(placementSnapshot);
		boolean microphone = DroneItem.hasMicrophoneModule(placementSnapshot);
		DyeColor paintColor = DroneItem.getPaintColor(placementSnapshot);

		AABB placementBox = droneBoxAt(spawnPos);
		if (!serverLevel.noCollision(placementBox)) {
			return false;
		}

		Interaction root = new Interaction(EntityType.INTERACTION, serverLevel);
		root.addTag(DRONE_ROOT_TAG);
		root.setPos(spawnPos.x, spawnPos.y, spawnPos.z);
		root.setYRot(yRot);
		root.setXRot(0.0F);
		root.setNoGravity(true);
		root.setInvulnerable(true);
		root.setSilent(true);
		root.noPhysics = false;
		root.setResponse(true);
		root.setWidth(DRONE_WIDTH);
		root.setHeight(DRONE_HEIGHT);
		if (droneType != DroneItem.DroneType.NORMAL) {
			root.addTag(DRONE_TYPE_TAG_PREFIX + droneType.name().toLowerCase(java.util.Locale.ROOT));
		}
		if (kamikazePower > DRONE_KAMIKAZE_NO_POWER) {
			root.addTag(droneKamikazePowerTag(kamikazePower));
		}
		if (nightVision) {
			root.addTag(DRONE_NIGHT_VISION_TAG);
		}
		if (autoAim) {
			root.addTag(DRONE_AUTO_AIM_TAG);
		}
		if (microphone) {
			root.addTag(DRONE_MICROPHONE_TAG);
		}
		if (paintColor != null) {
			root.addTag(DRONE_PAINT_TAG_PREFIX + paintColor.getName());
		}

		Display.ItemDisplay display = createDroneDisplay(
				serverLevel,
				spawnPos,
				yRot,
				0.0F,
				DroneItem.createDisplayStack(ModItems.DRONE, droneType, kamikazePower, nightVision, autoAim, microphone, paintColor),
				DRONE_DISPLAY_LAYER_BASE,
				resolveDroneDisplayYOffset(droneType)
		);
		Interaction cameraAnchor = createDroneCameraAnchor(
				serverLevel,
				droneCameraOrigin(spawnPos, resolveDroneVisualLift(droneType)),
				yRot,
				0.0F
		);
		serverLevel.addFreshEntity(root);
		serverLevel.addFreshEntity(display);
		serverLevel.addFreshEntity(cameraAnchor);
		display.addTag(DRONE_DISPLAY_OWNER_TAG_PREFIX + root.getUUID());
		DISPLAYS_BY_DRONE.put(root.getUUID(), display.getUUID());
		rememberDroneDisplayLayer(root, display);
		cameraAnchor.addTag(DRONE_CAMERA_OWNER_TAG_PREFIX + root.getUUID());
		CAMERA_ANCHORS_BY_DRONE.put(root.getUUID(), cameraAnchor.getUUID());
		syncDroneDisplayLayers(root);
		syncDroneDisplay(root, yRot, 0.0F, 0.0D, 0.0D, false);
		UncontrolledDroneState uncontrolledState = new UncontrolledDroneState(root.getUUID(), serverLevel.dimension(), Vec3.ZERO, yRot, 0.0F);
		uncontrolledState.setLastPosition(root.position());
		UNCONTROLLED_DRONES.put(root.getUUID(), uncontrolledState);
		rememberLastKnownDroneFeedState(root);
		return true;
	}

	private static void registerDroneDispenseBehaviors() {
		registerDroneDispenseBehavior(ModItems.DRONE, DroneSystem::tryDispensePlaceDrone);
		// Tuning is deliberately player-only: each module must check that its
		// corresponding server-menu upgrade has been purchased.
	}

	private static void registerDroneDispenseBehavior(Item item, DroneDispenseHandler handler) {
		if (item == null || handler == null) {
			return;
		}
		DispenseItemBehavior fallback = resolveDroneDispenseFallback(item);
		DispenserBlock.registerBehavior(item, (source, stack) -> {
			DroneAutomationAction action = handler.apply(source, stack);
			if (action == DroneAutomationAction.PASS) {
				return fallback.dispense(source, stack);
			}
			playDroneDispenseFeedback(source, action == DroneAutomationAction.SUCCESS);
			return stack;
		});
	}

	private static DispenseItemBehavior resolveDroneDispenseFallback(Item item) {
		DispenseItemBehavior behavior = item == null ? null : DispenserBlock.DISPENSER_REGISTRY.get(item);
		return behavior == null ? DEFAULT_DRONE_DISPENSE_FALLBACK : behavior;
	}

	private static void playDroneDispenseFeedback(BlockSource source, boolean success) {
		if (source == null) {
			return;
		}
		Direction facing = source.state() != null && source.state().hasProperty(DispenserBlock.FACING)
				? source.state().getValue(DispenserBlock.FACING)
				: Direction.NORTH;
		source.level().levelEvent(success ? 1000 : 1001, source.pos(), 0);
		source.level().levelEvent(2000, source.pos(), facing.get3DDataValue());
	}

	private static DroneAutomationAction tryDispensePlaceDrone(BlockSource source, ItemStack stack) {
		if (source == null || stack == null || stack.isEmpty() || source.state() == null || !source.state().hasProperty(DispenserBlock.FACING)) {
			return DroneAutomationAction.PASS;
		}
		Direction facing = source.state().getValue(DispenserBlock.FACING);
		Vec3 spawnPos = resolvePlacementPosition(source.pos().relative(facing), facing);
		if (!spawnConfiguredDrone(source.level(), stack.copy(), spawnPos, facing.toYRot())) {
			return DroneAutomationAction.FAIL_KEEP;
		}
		stack.shrink(1);
		return DroneAutomationAction.SUCCESS;
	}

	public static void handleInput(ServerPlayer player, Input input) {
		if (player == null || input == null || !isControllingDrone(player)) {
			return;
		}
		INPUTS.put(
				player.getUUID(),
				new DroneInputState(
						input.forward(),
						input.backward(),
						input.left(),
						input.right(),
						input.jump(),
						input.shift(),
						input.sprint()
				)
		);
	}

	public static boolean isControllingDrone(ServerPlayer player) {
		if (player == null) {
			return false;
		}
		DroneControlSession session = ACTIVE_SESSIONS.get(player.getUUID());
		return session != null && Objects.equals(resolveAuthoritativeDroneControllerId(session.droneUuid()), player.getUUID());
	}

	public static boolean hasActiveController(UUID droneUuid) {
		return resolveAuthoritativeDroneControllerId(droneUuid) != null;
	}

	public static boolean tryStartControllingDrone(ServerPlayer player, UUID droneUuid, net.minecraft.resources.ResourceKey<Level> dimension, BlockPos fallbackPos) {
		if (player == null || droneUuid == null || dimension == null || player.level() == null || player.level().getServer() == null) {
			return false;
		}
		MinecraftServer server = player.level().getServer();
		UUID currentControllerId = resolveAuthoritativeDroneControllerId(droneUuid);
		if (currentControllerId != null && !Objects.equals(currentControllerId, player.getUUID())) {
			Lg2Messages.actionBar(player, "message.lg2.drone.busy");
			return false;
		}
		Entity root = findDroneRoot(server, dimension, droneUuid);
		if (root == null || !root.isAlive()) {
			DroneLiveFeedState liveFeedState = resolveLiveFeedState(server, droneUuid, dimension, fallbackPos);
			if (liveFeedState == null || liveFeedState.dimension() == null || liveFeedState.pos() == null) {
				return false;
			}
			queueDroneControlStartPreload(player, liveFeedState);
			return true;
		}
		if (isDroneControlStartAreaReady(root, DRONE_CONTROL_PRELOAD_READY_RADIUS_CHUNKS)) {
			return startControlling(player, root);
		}
		queueDroneControlStartPreload(player, root);
		return true;
	}

	private static boolean isActiveDroneController(UUID controllerId, UUID droneUuid) {
		if (controllerId == null || droneUuid == null) {
			return false;
		}
		DroneControlSession session = ACTIVE_SESSIONS.get(controllerId);
		return session != null && Objects.equals(session.droneUuid(), droneUuid);
	}

	private static Set<UUID> collectDroneControllerIds(UUID droneUuid) {
		Set<UUID> controllerIds = new LinkedHashSet<>();
		if (droneUuid == null) {
			return controllerIds;
		}

		UUID indexedControllerId = CONTROLLERS_BY_DRONE.get(droneUuid);
		if (isActiveDroneController(indexedControllerId, droneUuid)) {
			controllerIds.add(indexedControllerId);
		} else if (indexedControllerId != null) {
			CONTROLLERS_BY_DRONE.remove(droneUuid, indexedControllerId);
		}

		for (Map.Entry<UUID, DroneControlSession> entry : ACTIVE_SESSIONS.entrySet()) {
			UUID controllerId = entry.getKey();
			DroneControlSession session = entry.getValue();
			if (controllerId == null || session == null || !Objects.equals(session.droneUuid(), droneUuid)) {
				continue;
			}
			controllerIds.add(controllerId);
		}
		return controllerIds;
	}

	private static UUID resolveAuthoritativeDroneControllerId(UUID droneUuid) {
		if (droneUuid == null) {
			return null;
		}
		Set<UUID> controllerIds = collectDroneControllerIds(droneUuid);
		if (controllerIds.isEmpty()) {
			CONTROLLERS_BY_DRONE.remove(droneUuid);
			return null;
		}

		UUID indexedControllerId = CONTROLLERS_BY_DRONE.get(droneUuid);
		UUID resolvedControllerId = indexedControllerId != null && controllerIds.contains(indexedControllerId)
				? indexedControllerId
				: controllerIds.iterator().next();
		if (!Objects.equals(indexedControllerId, resolvedControllerId)) {
			CONTROLLERS_BY_DRONE.put(droneUuid, resolvedControllerId);
		}
		return resolvedControllerId;
	}

	public static void handleControlledMovePacket(ServerPlayer player, ServerboundMovePlayerPacket packet) {
		if (player == null || packet == null) {
			return;
		}
		DroneControlSession session = ACTIVE_SESSIONS.get(player.getUUID());
		if (session == null) {
			return;
		}
		MinecraftServer server = player.level() == null ? null : player.level().getServer();
		Entity root = findDroneRoot(server, session.droneDimension(), session.droneUuid());
		if (root == null || !root.isAlive()) {
			if (shouldKeepControlledSessionWaitingForDroneRoot(server, session)) {
				return;
			}
			stopControlling(player, true);
			return;
		}
		session.refreshKnownDroneLocation(root);
		applyControlledMovePacket(player, root, session, packet);
	}

	public static boolean shouldSuppressPostControlMovePacket(ServerPlayer player, ServerboundMovePlayerPacket packet) {
		if (player == null || packet == null) {
			return false;
		}
		Long untilTick = POST_CONTROL_MOVE_SUPPRESSED_UNTIL_TICK.get(player.getUUID());
		if (untilTick == null) {
			return false;
		}
		long now = player.level() == null ? Long.MAX_VALUE : player.level().getGameTime();
		if (now > untilTick) {
			POST_CONTROL_MOVE_SUPPRESSED_UNTIL_TICK.remove(player.getUUID(), untilTick);
			return false;
		}
		if (!packet.hasPosition()) {
			return false;
		}

		Vec3 expected = player.position();
		Vec3 reported = new Vec3(
				packet.getX(expected.x),
				packet.getY(expected.y),
				packet.getZ(expected.z)
		);
		if (!Double.isFinite(reported.x) || !Double.isFinite(reported.y) || !Double.isFinite(reported.z)) {
			return true;
		}
		if (reported.subtract(expected).lengthSqr() > POST_CONTROL_MOVE_ACCEPT_DISTANCE_SQR) {
			return true;
		}

		POST_CONTROL_MOVE_SUPPRESSED_UNTIL_TICK.remove(player.getUUID(), untilTick);
		return false;
	}

	private static void destroyControlledDroneFromImpact(ServerPlayer player, DroneControlSession session, Entity root) {
		if (player == null || session == null || root == null || !root.isAlive()) {
			return;
		}
		igniteDroneCrashSite(root, session.velocity());
		destroyDrone(root, null, false);
		if (ACTIVE_SESSIONS.containsKey(player.getUUID())) {
			stopControlling(player, true, false);
		}
	}

	/**
	 * Consumes acknowledgements for synthetic drone-camera teleports. A final
	 * acknowledgement can arrive after the drone was destroyed and its session
	 * removed, so it must not reach vanilla's normal teleport state machine.
	 */
	public static boolean shouldConsumeDroneProxyTeleportAck(ServerPlayer player, int teleportId) {
		if (player == null) {
			return false;
		}
		if (teleportId < CONTROLLED_VIEW_TELEPORT_ID_BASE) {
			return false;
		}
		DroneControlSession session = ACTIVE_SESSIONS.get(player.getUUID());
		long gameTime = player.level() == null ? Long.MIN_VALUE : player.level().getGameTime();
		if (session != null) {
			session.acknowledgeStartupViewSync(gameTime);
			return true;
		}
		Long untilTick = POST_CONTROL_VIEW_TELEPORT_ACK_UNTIL_TICK.get(player.getUUID());
		if (untilTick == null) {
			return false;
		}
		if (gameTime <= untilTick) {
			return true;
		}
		POST_CONTROL_VIEW_TELEPORT_ACK_UNTIL_TICK.remove(player.getUUID(), untilTick);
		return false;
	}

	public static ChunkTrackingView createVirtualChunkTrackingView(ServerPlayer player) {
		if (!isControllingDrone(player)) {
			return ChunkTrackingView.of(player.chunkPosition(), resolveRequestedViewDistance(player));
		}

		Entity root = resolveControlledDroneRoot(player);
		DroneControlSession session = ACTIVE_SESSIONS.get(player.getUUID());
		DroneCameraChunkTarget target = root == null && session != null
				? session.lastKnownCameraTarget()
				: resolveDroneCameraChunkTarget(root);
		return ChunkTrackingView.of(chunkPosAt(target.x(), target.z()), resolveServerViewDistance(player.level().getServer()));
	}

	public static boolean isEntityWithinVirtualTrackingRange(ServerPlayer viewer, Entity entity, double horizontalRangeBlocks) {
		if (!isControllingDrone(viewer)
				|| viewer == null
				|| entity == null
				|| horizontalRangeBlocks <= 0.0D
				|| viewer.level() != entity.level()) {
			return false;
		}

		Entity root = resolveControlledDroneRoot(viewer);
		if (root != null) {
			return isWithinHorizontalRange(root.position(), entity, horizontalRangeBlocks);
		}
		DroneControlSession session = ACTIVE_SESSIONS.get(viewer.getUUID());
		if (session == null || !Objects.equals(session.droneDimension(), entity.level().dimension())) {
			return false;
		}
		return isWithinHorizontalRange(session.lastKnownDronePos(), entity, horizontalRangeBlocks);
	}

	public static boolean isOutgoingControlledOperatorPacketRewriteBypassed() {
		return Boolean.TRUE.equals(CONTROLLED_OPERATOR_PACKET_REWRITE_BYPASS.get());
	}

	public static void runWithControlledOperatorPacketRewriteBypass(Runnable action) {
		if (action == null) {
			return;
		}
		CONTROLLED_OPERATOR_PACKET_REWRITE_BYPASS.set(true);
		try {
			action.run();
		} finally {
			CONTROLLED_OPERATOR_PACKET_REWRITE_BYPASS.remove();
		}
	}

	public static Packet<?> rewriteOutgoingControlledOperatorPacket(ServerPlayer receiver, Packet<?> packet) {
		if (receiver == null || packet == null) {
			return packet;
		}
		DroneControlSession session = ACTIVE_SESSIONS.get(receiver.getUUID());
		if (session == null) {
			return packet;
		}

		if (packet instanceof ClientboundBundlePacket bundlePacket) {
			List<Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener>> rewrittenPackets = new ArrayList<>();
			boolean changed = false;
			for (Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener> bundledPacket : bundlePacket.subPackets()) {
				Packet<?> rewritten = rewriteOutgoingControlledOperatorPacket(receiver, (Packet<?>) bundledPacket);
				if (rewritten != null) {
					rewrittenPackets.add((Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener>) rewritten);
				}
				if (rewritten != bundledPacket) {
					changed = true;
				}
			}
			return changed ? (rewrittenPackets.isEmpty() ? null : new ClientboundBundlePacket(rewrittenPackets)) : packet;
		}

		if (packet instanceof ClientboundPlayerPositionPacket playerPositionPacket) {
			return buildControlledPlayerPositionPacket(session, playerPositionPacket.id());
		}

		if (packet instanceof ClientboundSetEntityMotionPacket entityMotionPacket) {
			if (entityMotionPacket.getId() == receiver.getId()) {
				return null;
			}
			if (isControlledOperatorPassengerVisualEntity(receiver, entityMotionPacket.getId())) {
				return null;
			}
		}

		if (packet instanceof ClientboundTeleportEntityPacket entityTeleportPacket) {
			if (entityTeleportPacket.id() == receiver.getId()) {
				return buildControlledSelfTeleportPacket(receiver, session);
			}
			if (isControlledOperatorPassengerVisualEntity(receiver, entityTeleportPacket.id())) {
				return null;
			}
		}

		if (packet instanceof ClientboundEntityPositionSyncPacket positionSyncPacket
				&& isControlledOperatorPassengerVisualEntity(receiver, positionSyncPacket.id())) {
			return null;
		}

		if (packet instanceof ClientboundMoveEntityPacket moveEntityPacket
				&& isControlledOperatorPassengerVisualEntity(
						receiver,
						((ClientboundMoveEntityPacketAccessor) (Object) moveEntityPacket).lg2$getEntityId()
				)) {
			return null;
		}

		if (packet instanceof ClientboundSetEntityDataPacket entityDataPacket) {
			if (entityDataPacket.id() == receiver.getId()) {
				return buildControlledSelfMetadataPacket(receiver);
			}
			Packet<?> rewrittenDisplayMetadataPacket = rewriteControlledOperatorPassengerDisplayMetadata(receiver, entityDataPacket);
			if (rewrittenDisplayMetadataPacket != entityDataPacket) {
				return rewrittenDisplayMetadataPacket;
			}
		}

		if (packet instanceof ClientboundSetPassengersPacket passengersPacket
				&& passengersPacket.getVehicle() == receiver.getId()) {
			return buildControlledOperatorPassengerPacket(receiver, session);
		}
		if (packet instanceof ClientboundSetPassengersPacket passengersPacket) {
			Entity root = resolveControlledDroneRoot(receiver);
			if (root != null && passengersPacket.getVehicle() == root.getId()) {
				return buildControlledOperatorDroneLayerPassengerPacket(root);
			}
		}

		if (packet instanceof ClientboundContainerSetSlotPacket slotPacket
				&& slotPacket.getContainerId() == receiver.inventoryMenu.containerId
				&& isControlledHotbarSlot(slotPacket.getSlot())) {
			return new ClientboundContainerSetSlotPacket(
					slotPacket.getContainerId(),
					slotPacket.getStateId(),
					slotPacket.getSlot(),
					ItemStack.EMPTY
			);
		}

		return packet;
	}

	public static boolean isCameraBlockedByDroneControl(ServerPlayer player) {
		if (player == null) {
			return false;
		}
		return isControllingDrone(player) || VISUALLY_CONTROLLED_PLAYERS.contains(player.getUUID());
	}

	public static BluetoothLinkSystem.Endpoint resolveBluetoothDroneEndpoint(ServerLevel level, Entity entity) {
		if (level == null || entity == null) {
			return null;
		}
		Entity root = resolveDroneRoot(entity);
		if (root == null || !root.isAlive() || root.level() != level) {
			return null;
		}
		return BluetoothLinkSystem.droneEndpoint(level.dimension(), root.blockPosition(), root.getUUID());
	}

	public static List<ServerSelectionHighlightSystem.DisplayBlueprint> resolveBluetoothDroneHighlightBlueprints(ServerLevel level, BluetoothLinkSystem.Endpoint endpoint) {
		if (level == null || endpoint == null || endpoint.type() != BluetoothLinkSystem.EndpointType.DRONE) {
			return List.of();
		}
		Entity root = endpoint.deviceUuid() == null ? null : findDroneRoot(level.getServer(), level.dimension(), endpoint.deviceUuid());
		if (root == null || !root.isAlive() || root.level() != level) {
			return List.of();
		}
		List<ServerSelectionHighlightSystem.DisplayBlueprint> blueprints = new ArrayList<>();
		for (Display.ItemDisplay display : findDroneDisplayLayers(root)) {
			if (display.isAlive()) {
				blueprints.add(new ServerSelectionHighlightSystem.EntityGlowBlueprint(display));
			}
		}
		if (!blueprints.isEmpty()) {
			return blueprints;
		}
		return List.of(new ServerSelectionHighlightSystem.EntityGlowBlueprint(root));
	}

	public static DroneLiveFeedState resolveLiveFeedState(MinecraftServer server, BluetoothLinkSystem.Endpoint endpoint) {
		if (endpoint == null || endpoint.type() != BluetoothLinkSystem.EndpointType.DRONE) {
			return null;
		}
		return resolveLiveFeedState(server, endpoint.deviceUuid(), endpoint.dimension(), endpoint.pos());
	}

	public static boolean isDroneCameraAnchor(Entity entity) {
		if (entity == null) {
			return false;
		}
		for (String tag : entity.getTags()) {
			if (tag != null && tag.startsWith(DRONE_CAMERA_OWNER_TAG_PREFIX)) {
				return true;
			}
		}
		return false;
	}

	public static DroneLiveFeedState resolveLiveFeedState(
			MinecraftServer server,
			UUID droneUuid,
			net.minecraft.resources.ResourceKey<Level> fallbackDimension,
			BlockPos fallbackPos
	) {
		if (server == null || droneUuid == null) {
			return null;
		}
		Entity root = fallbackDimension == null ? findDroneRoot(server, droneUuid) : findDroneRoot(server, fallbackDimension, droneUuid);
		if (root == null || !root.isAlive() || !(root.level() instanceof ServerLevel droneLevel)) {
			net.minecraft.resources.ResourceKey<Level> dimension = fallbackDimension == null ? Level.OVERWORLD : fallbackDimension;
			BlockPos pos = fallbackPos == null ? BlockPos.ZERO : fallbackPos.immutable();
			DroneScreenStreamLoadState loadState = SCREEN_STREAM_DRONE_LOAD_STATES.get(droneUuid);
			if (loadState != null) {
				Vec3 lastDronePos = loadState.dronePos() == null ? Vec3.atCenterOf(pos) : loadState.dronePos();
				Vec3 lastCameraPos = loadState.cameraPos() == null ? droneCameraOrigin(lastDronePos) : loadState.cameraPos();
				return new DroneLiveFeedState(
						droneUuid,
						loadState.dimension() == null ? dimension : loadState.dimension(),
						BlockPos.containing(lastDronePos.x, lastDronePos.y, lastDronePos.z),
						true,
						lastCameraPos.x,
						lastCameraPos.y,
						lastCameraPos.z,
						loadState.cameraYaw(),
						loadState.cameraPitch(),
						null,
						Set.of(),
						true,
						null
				);
			}
			DroneScreenStreamLoadState lastKnownState = LAST_KNOWN_DRONE_FEED_STATES.get(droneUuid);
			if (lastKnownState != null) {
				Vec3 lastDronePos = lastKnownState.dronePos() == null ? Vec3.atCenterOf(pos) : lastKnownState.dronePos();
				Vec3 lastCameraPos = lastKnownState.cameraPos() == null ? droneCameraOrigin(lastDronePos) : lastKnownState.cameraPos();
				return new DroneLiveFeedState(
						droneUuid,
						lastKnownState.dimension() == null ? dimension : lastKnownState.dimension(),
						BlockPos.containing(lastDronePos.x, lastDronePos.y, lastDronePos.z),
						false,
						lastCameraPos.x,
						lastCameraPos.y,
						lastCameraPos.z,
						lastKnownState.cameraYaw(),
						lastKnownState.cameraPitch(),
						null,
						Set.of(),
						true,
						null
				);
			}
			return new DroneLiveFeedState(
					droneUuid,
					dimension,
					pos,
					false,
					pos.getX() + 0.5D,
					pos.getY(),
					pos.getZ() + 0.5D,
					0.0F,
					0.0F,
					null,
					Set.of(),
					true,
					null
			);
		}
		rememberDroneScreenStreamLoadState(root);
		UUID controllerId = resolveAuthoritativeDroneControllerId(root.getUUID());
		ServerPlayer controller = controllerId == null ? null : server.getPlayerList().getPlayer(controllerId);
			Entity cameraAnchor = ensureDroneCameraAnchor(root);
			Vec3 cameraOrigin = cameraAnchor != null ? cameraAnchor.position() : resolveDroneCameraPosition(root);
			float cameraYaw = cameraAnchor != null ? cameraAnchor.getYRot() : resolveDroneCameraYaw(root);
			float cameraPitch = cameraAnchor != null ? cameraAnchor.getXRot() : resolveDroneCameraPitch(root);
			UUID cameraAnchorUuid = cameraAnchor != null ? cameraAnchor.getUUID() : null;
			Set<UUID> hiddenEntities = hiddenDroneCameraEntityUuids(root, null);
			// A drone camera can rotate every tick.  Its shadow world therefore must
			// keep a stable radial chunk window: a directional frustum moves both the
			// virtual cache centre and its edge on every yaw update, causing vanilla
			// to evict/rebuild terrain while the picture is being captured.
			boolean omnidirectionalChunkLoading = true;
			if (controller != null && ACTIVE_SESSIONS.containsKey(controller.getUUID())) {
				DroneControlSession session = ACTIVE_SESSIONS.get(controller.getUUID());
				if (session != null
						&& Objects.equals(session.droneUuid(), root.getUUID())
						&& controller.level() == droneLevel) {
				return new DroneLiveFeedState(
						root.getUUID(),
						droneLevel.dimension(),
						root.blockPosition(),
						true,
						cameraOrigin.x,
						cameraOrigin.y,
						cameraOrigin.z,
							cameraYaw,
							cameraPitch,
							cameraAnchorUuid,
							hiddenEntities,
							omnidirectionalChunkLoading,
							controller.getScoreboardName()
					);
				}
			}
			return new DroneLiveFeedState(
				root.getUUID(),
				droneLevel.dimension(),
				root.blockPosition(),
				true,
				cameraOrigin.x,
				cameraOrigin.y,
				cameraOrigin.z,
					cameraYaw,
					cameraPitch,
					cameraAnchorUuid,
					hiddenEntities,
					omnidirectionalChunkLoading,
					null
			);
		}

	private static Set<UUID> hiddenDroneCameraEntityUuids(Entity root, ServerPlayer controller) {
		if (root == null) {
			return Set.of();
		}
		Set<UUID> hidden = new LinkedHashSet<>();
		hidden.add(root.getUUID());
		UUID baseDisplayId = DISPLAYS_BY_DRONE.get(root.getUUID());
		if (baseDisplayId != null) {
			hidden.add(baseDisplayId);
		}
		for (Display.ItemDisplay display : findDroneDisplayLayers(root)) {
			if (display != null) {
				hidden.add(display.getUUID());
			}
		}
		return Set.copyOf(hidden);
	}

	private static void applyControlledMovePacket(
			ServerPlayer player,
			Entity root,
			DroneControlSession session,
			ServerboundMovePlayerPacket packet
	) {
		if (player == null || root == null || session == null || packet == null) {
			return;
		}
		Vec3 previousPos = session.proxyPos();
		if (previousPos == null) {
			previousPos = root.position();
		}
		long gameTime = root.level() == null ? Long.MIN_VALUE : root.level().getGameTime();
		float previousYaw = session.proxyYaw();
		float previousPitch = session.proxyPitch();
		boolean hasRotation = packet.hasRotation();
		float packetYaw = hasRotation ? packet.getYRot(session.controlYaw()) : session.controlYaw();
		float packetPitch = hasRotation
				? net.minecraft.util.Mth.clamp(packet.getXRot(session.controlPitch()), -90.0F, 90.0F)
				: session.controlPitch();
		boolean suppressStartupRotation = hasRotation && session.shouldSuppressStartupRotation(gameTime);
		float yaw = suppressStartupRotation ? session.controlYaw() : packetYaw;
		float pitch = suppressStartupRotation ? session.controlPitch() : packetPitch;
		if (hasRotation && !suppressStartupRotation) {
			session.recordManualLookDelta(
					net.minecraft.util.Mth.wrapDegrees(yaw - previousYaw),
					pitch - previousPitch,
					gameTime
			);
		}
		session.setControlYaw(yaw);
		session.setControlPitch(pitch);
		session.setProxyYaw(yaw);
		session.setProxyPitch(pitch);
		if (packet.hasPosition() && session.shouldSuppressStartupPosition(root.level() == null ? Long.MIN_VALUE : root.level().getGameTime())) {
			return;
		}

		if (!packet.hasPosition()) {
			root.setYRot(yaw);
			root.setXRot(pitch);
			syncControlledDronePresentation(player, root, session);
			session.refreshKnownDroneLocation(root);
			return;
		}
		Vec3 reportedPos = new Vec3(
				packet.getX(previousPos.x),
				packet.getY(previousPos.y),
				packet.getZ(previousPos.z)
		);
		if (!Double.isFinite(reportedPos.x) || !Double.isFinite(reportedPos.y) || !Double.isFinite(reportedPos.z)) {
			stopControlling(player, true);
			return;
		}
		Vec3 actualVelocity = reportedPos.subtract(previousPos);
		Vec3 intendedMovement = DroneFlightPhysics.step(
				pitch,
				yaw,
				session.forwardDrive(),
				session.strafeDrive()
		);
		if (!isPlausibleControlledDroneMove(actualVelocity, intendedMovement)) {
			Vec3 safeVelocity = finiteVecOr(session.velocity(), Vec3.ZERO);
			if (!isPlausibleControlledDroneMove(safeVelocity, intendedMovement)) {
				safeVelocity = Vec3.ZERO;
			}
			session.setProxyPos(previousPos);
			session.setIntendedVelocity(intendedMovement);
			session.setVelocity(safeVelocity);
			prepareControlledDroneBody(root);
			root.setPos(previousPos.x, previousPos.y, previousPos.z);
			root.setBoundingBox(droneBoxAt(previousPos));
			root.setYRot(yaw);
			root.setXRot(pitch);
			root.setDeltaMovement(safeVelocity);
			root.hurtMarked = true;
			syncControlledDronePresentation(player, root, session);
			syncControlledOperatorView(player, session, root, false, true);
			session.refreshKnownDroneLocation(root);
			return;
		}

		session.setProxyPos(reportedPos);
		session.setIntendedVelocity(intendedMovement);
		session.setVelocity(actualVelocity);

		ControlledCollisionState collisionState = resolveControlledCollisionState(
				root,
				previousPos,
				intendedMovement,
				actualVelocity
		);

		prepareControlledDroneBody(root);
		root.setPos(reportedPos.x, reportedPos.y, reportedPos.z);
		root.setBoundingBox(droneBoxAt(reportedPos));
		root.setYRot(yaw);
		root.setXRot(pitch);
		root.setDeltaMovement(actualVelocity);
		root.horizontalCollision = collisionState.horizontalCollision();
		root.verticalCollision = collisionState.verticalCollision();
		root.verticalCollisionBelow = collisionState.verticalCollisionBelow();
		root.hurtMarked = true;
		playDroneSubmergedMotionEffects(root, actualVelocity, gameTime);

		if (handleControlledServerCollision(player, root, session, intendedMovement, actualVelocity)) {
			return;
		}

		syncControlledDronePresentation(player, root, session);
		session.refreshKnownDroneLocation(root);
	}

	private static void prepareControlledDroneBody(Entity root) {
		if (root == null) {
			return;
		}
		root.setNoGravity(true);
		root.noPhysics = false;
		root.fallDistance = 0.0F;
		root.setBoundingBox(droneBoxAt(root.position()));
	}

	public static boolean isDroneEntity(Entity entity) {
		return resolveDroneRoot(entity) != null;
	}

	public static boolean shouldDroneRootCollideWithEntities(Entity root) {
		if (root == null || !root.getTags().contains(DRONE_ROOT_TAG)) {
			return false;
		}
		if (isDroneActivelyControlled(root)) {
			return false;
		}
		UncontrolledDroneState state = UNCONTROLLED_DRONES.get(root.getUUID());
		if (state != null && (isUncontrolledReleaseGlideActive(root, state) || isDroneHeldByScreen(root))) {
			return false;
		}
		return root.onGround() || hasSupportingBlockBelow(root);
	}

	public static boolean shouldSuppressDroneEntityPush(Entity self, Entity other) {
		if (self == null || other == null || self == other) {
			return false;
		}
		Entity root = self.getTags().contains(DRONE_ROOT_TAG)
				? self
				: (other.getTags().contains(DRONE_ROOT_TAG) ? other : null);
		if (root == null || !shouldDroneRootCollideWithEntities(root)) {
			return false;
		}
		Entity counterpart = root == self ? other : self;
		return counterpart != null && !isDroneInternalEntity(counterpart);
	}

	public static String requiredUpgradeForDroneEntity(Entity entity) {
		Entity root = resolveDroneRoot(entity);
		return root == null ? null : resolveRequiredUpgradeForDroneRoot(root);
	}

	private static void tick(MinecraftServer server) {
		if (server == null) {
			return;
		}
		flushPendingDroneLoadDiscards(server);
		updateDroneChunkTickets(server);
		tickPendingControlStarts(server);
		tickControlledSessions(server);
		synchronizeControlledOperatorAudio(server);
		tickUncontrolledDrones(server);
		updateDroneChunkTickets(server);
		cleanupExpiredPostControlMoveSuppression(server);
		recoverOrphanedControlledOperators(server);
		recoverPlayersWithStaleDronePassenger(server);
		processPendingPostControlClientResync(server);
		tickDroneHudGlitchOverlays(server);
		tickDroneHudGlitchBursts(server);
	}

	private static void updateDroneChunkTickets(MinecraftServer server) {
		if (server == null) {
			return;
		}

		Map<DroneChunkTicketKey, Integer> desiredRefs = new HashMap<>();
		Set<UUID> activeScreenStreamDrones = new HashSet<>();
		for (DroneScreenStreamReference screenStream : MonitorScreenSystem.collectActiveDroneScreenStreams(server)) {
			if (screenStream == null || screenStream.droneUuid() == null) {
				continue;
			}
			activeScreenStreamDrones.add(screenStream.droneUuid());
			Entity root = findDroneRoot(server, screenStream.dimension(), screenStream.droneUuid());
			DroneScreenStreamLoadState loadState;
			if (root != null && root.isAlive()) {
				loadState = rememberDroneScreenStreamLoadState(root);
			} else {
				loadState = SCREEN_STREAM_DRONE_LOAD_STATES.computeIfAbsent(
						screenStream.droneUuid(),
						ignored -> createDroneScreenStreamLoadState(screenStream)
				);
			}
			addDroneChunkTickets(server, desiredRefs, loadState);
		}
		POWERED_SCREEN_LINKED_DRONES.clear();
		POWERED_SCREEN_LINKED_DRONES.addAll(activeScreenStreamDrones);
		SCREEN_STREAM_DRONE_LOAD_STATES.keySet().removeIf(droneUuid -> !activeScreenStreamDrones.contains(droneUuid));

		for (Map.Entry<UUID, DroneControlSession> entry : ACTIVE_SESSIONS.entrySet()) {
			DroneControlSession session = entry.getValue();
			if (session == null) {
				continue;
			}
			Entity root = findDroneRoot(server, session.droneDimension(), session.droneUuid());
			ServerPlayer controller = server.getPlayerList().getPlayer(entry.getKey());
			if (controller == null || !controller.isAlive() || controller.isSpectator()) {
				continue;
			}
			addControlledOperatorBodyChunkTickets(server, desiredRefs, controller);
			if (root != null && root.isAlive()) {
				session.refreshKnownDroneLocation(root);
				addDroneChunkTickets(server, desiredRefs, root);
			} else {
				addDroneChunkTickets(server, desiredRefs, session);
			}
		}

		for (PendingDroneControlStart pending : PENDING_CONTROL_STARTS.values()) {
			if (pending == null) {
				continue;
			}
			Entity root = findDroneRoot(server, pending.droneDimension(), pending.droneUuid());
			if (root != null && root.isAlive()) {
				addDroneChunkTickets(server, desiredRefs, root);
				continue;
			}
			DroneLiveFeedState liveFeedState = resolveLiveFeedState(server, pending.droneUuid(), pending.droneDimension(), pending.fallbackPos());
			addDroneChunkTickets(server, desiredRefs, liveFeedState);
		}

		for (UncontrolledDroneState state : UNCONTROLLED_DRONES.values()) {
			if (state == null) {
				continue;
			}
			Entity root = findDroneRoot(server, state.dimension(), state.droneUuid());
			if (root == null || !root.isAlive() || !isDroneHeldByScreen(root)) {
				continue;
			}
			rememberLastKnownDroneFeedState(root);
			addDroneChunkTickets(server, desiredRefs, root);
		}

		syncDroneChunkTickets(server, desiredRefs);
	}

	private static void addControlledOperatorBodyChunkTickets(MinecraftServer server, Map<DroneChunkTicketKey, Integer> desiredRefs, ServerPlayer player) {
		if (server == null || desiredRefs == null || player == null || !(player.level() instanceof ServerLevel level)) {
			return;
		}
		ChunkPos bodyCenter = player.chunkPosition();
		desiredRefs.merge(
				new DroneChunkTicketKey(level.dimension(), bodyCenter.toLong(), resolveServerViewDistance(server), false),
				1,
				Integer::sum
		);
		desiredRefs.merge(
				new DroneChunkTicketKey(level.dimension(), bodyCenter.toLong(), resolveServerSimulationTicketRadius(server), true),
				1,
				Integer::sum
		);
	}

	private static void addDroneChunkTickets(MinecraftServer server, Map<DroneChunkTicketKey, Integer> desiredRefs, Entity root) {
		if (desiredRefs == null || root == null || !(root.level() instanceof ServerLevel level)) {
			return;
		}
		if (isDroneActivelyControlled(root)) {
			UUID controllerId = resolveAuthoritativeDroneControllerId(root.getUUID());
			DroneControlSession session = controllerId == null ? null : ACTIVE_SESSIONS.get(controllerId);
			if (session != null) {
				session.refreshKnownDroneLocation(root);
			}
		}
		DroneCameraChunkTarget target = resolveDroneCameraChunkTarget(root);
		ChunkPos loadingCenter = chunkPosAt(target.x(), target.z());
		desiredRefs.merge(
				new DroneChunkTicketKey(level.dimension(), loadingCenter.toLong(), resolveServerViewDistance(server), false),
				1,
				Integer::sum
		);
		desiredRefs.merge(
				new DroneChunkTicketKey(level.dimension(), root.chunkPosition().toLong(), resolveServerSimulationTicketRadius(server), true),
				1,
				Integer::sum
		);
	}

	private static void addDroneChunkTickets(MinecraftServer server, Map<DroneChunkTicketKey, Integer> desiredRefs, DroneControlSession session) {
		if (server == null || desiredRefs == null || session == null || session.droneDimension() == null) {
			return;
		}
		ServerLevel level = server.getLevel(session.droneDimension());
		if (level == null) {
			return;
		}
		DroneCameraChunkTarget target = session.lastKnownCameraTarget();
		ChunkPos loadingCenter = chunkPosAt(target.x(), target.z());
		Vec3 dronePos = session.lastKnownDronePos();
		ChunkPos simulationCenter = chunkPosAt(dronePos.x, dronePos.z);
		desiredRefs.merge(
				new DroneChunkTicketKey(level.dimension(), loadingCenter.toLong(), resolveServerViewDistance(server), false),
				1,
				Integer::sum
		);
		desiredRefs.merge(
				new DroneChunkTicketKey(level.dimension(), simulationCenter.toLong(), resolveServerSimulationTicketRadius(server), true),
				1,
				Integer::sum
		);
	}

	private static void addDroneChunkTickets(MinecraftServer server, Map<DroneChunkTicketKey, Integer> desiredRefs, DroneScreenStreamLoadState state) {
		if (server == null || desiredRefs == null || state == null || state.dimension() == null) {
			return;
		}
		ServerLevel level = server.getLevel(state.dimension());
		if (level == null) {
			return;
		}
		DroneCameraChunkTarget target = state.cameraTarget();
		ChunkPos loadingCenter = chunkPosAt(target.x(), target.z());
		Vec3 dronePos = state.dronePos() == null ? Vec3.ZERO : state.dronePos();
		ChunkPos simulationCenter = chunkPosAt(dronePos.x, dronePos.z);
		desiredRefs.merge(
				new DroneChunkTicketKey(level.dimension(), loadingCenter.toLong(), resolveServerViewDistance(server), false),
				1,
				Integer::sum
		);
		desiredRefs.merge(
				new DroneChunkTicketKey(level.dimension(), simulationCenter.toLong(), resolveServerSimulationTicketRadius(server), true),
				1,
					Integer::sum
			);
	}

	private static void addDroneChunkTickets(MinecraftServer server, Map<DroneChunkTicketKey, Integer> desiredRefs, DroneLiveFeedState state) {
		if (server == null || desiredRefs == null || state == null || state.dimension() == null || state.pos() == null) {
			return;
		}
		ServerLevel level = server.getLevel(state.dimension());
		if (level == null) {
			return;
		}
		ChunkPos loadingCenter = chunkPosAt(state.expectedX(), state.expectedZ());
		ChunkPos simulationCenter = new ChunkPos(state.pos());
		desiredRefs.merge(
				new DroneChunkTicketKey(level.dimension(), loadingCenter.toLong(), resolveServerViewDistance(server), false),
				1,
				Integer::sum
		);
		desiredRefs.merge(
				new DroneChunkTicketKey(level.dimension(), simulationCenter.toLong(), resolveServerSimulationTicketRadius(server), true),
				1,
				Integer::sum
		);
	}

	private static DroneScreenStreamLoadState rememberLastKnownDroneFeedState(Entity root) {
		DroneScreenStreamLoadState state = captureDroneScreenStreamLoadState(root);
		if (state != null) {
			LAST_KNOWN_DRONE_FEED_STATES.put(root.getUUID(), state);
		}
		return state;
	}

	private static DroneScreenStreamLoadState rememberDroneScreenStreamLoadState(Entity root) {
		DroneScreenStreamLoadState state = rememberLastKnownDroneFeedState(root);
		if (state == null) {
			return null;
		}
		SCREEN_STREAM_DRONE_LOAD_STATES.put(root.getUUID(), state);
		return state;
	}

	private static DroneScreenStreamLoadState captureDroneScreenStreamLoadState(Entity root) {
		if (root == null || !(root.level() instanceof ServerLevel level)) {
			return null;
		}
		return new DroneScreenStreamLoadState(
				root.getUUID(),
				level.dimension(),
				root.position(),
				resolveDroneCameraPosition(root),
				resolveDroneCameraYaw(root),
				resolveDroneCameraPitch(root)
		);
	}

	private static DroneScreenStreamLoadState createDroneScreenStreamLoadState(DroneScreenStreamReference reference) {
		if (reference == null || reference.droneUuid() == null) {
			return null;
		}
		net.minecraft.resources.ResourceKey<Level> dimension = reference.dimension() == null ? Level.OVERWORLD : reference.dimension();
		BlockPos pos = reference.pos() == null ? BlockPos.ZERO : reference.pos();
		Vec3 dronePos = Vec3.atCenterOf(pos);
		return new DroneScreenStreamLoadState(
				reference.droneUuid(),
				dimension,
				dronePos,
				droneCameraOrigin(dronePos),
				0.0F,
				0.0F
		);
	}

	private static void syncDroneChunkTickets(MinecraftServer server, Map<DroneChunkTicketKey, Integer> desiredRefs) {
		if (server == null || desiredRefs == null) {
			return;
		}
		if (Objects.equals(ACTIVE_DRONE_CHUNK_TICKETS, desiredRefs)) {
			return;
		}

		for (DroneChunkTicketKey key : desiredRefs.keySet()) {
			if (ACTIVE_DRONE_CHUNK_TICKETS.getOrDefault(key, 0) > 0) {
				continue;
			}
			ServerLevel level = server.getLevel(key.dimension());
			if (level != null) {
				level.getChunkSource().addTicketWithRadius(droneChunkTicketType(key), new ChunkPos(key.chunkLong()), key.radius());
			}
		}
		for (DroneChunkTicketKey key : ACTIVE_DRONE_CHUNK_TICKETS.keySet()) {
			if (desiredRefs.getOrDefault(key, 0) > 0) {
				continue;
			}
			ServerLevel level = server.getLevel(key.dimension());
			if (level != null) {
				level.getChunkSource().removeTicketWithRadius(droneChunkTicketType(key), new ChunkPos(key.chunkLong()), key.radius());
			}
		}
		ACTIVE_DRONE_CHUNK_TICKETS.clear();
		ACTIVE_DRONE_CHUNK_TICKETS.putAll(desiredRefs);
	}

	private static void releaseAllDroneChunkTickets(MinecraftServer server) {
		if (server == null || ACTIVE_DRONE_CHUNK_TICKETS.isEmpty()) {
			ACTIVE_DRONE_CHUNK_TICKETS.clear();
			return;
		}
		for (DroneChunkTicketKey key : new ArrayList<>(ACTIVE_DRONE_CHUNK_TICKETS.keySet())) {
			ServerLevel level = server.getLevel(key.dimension());
			if (level != null) {
				level.getChunkSource().removeTicketWithRadius(droneChunkTicketType(key), new ChunkPos(key.chunkLong()), key.radius());
			}
		}
		ACTIVE_DRONE_CHUNK_TICKETS.clear();
	}

	private static TicketType droneChunkTicketType(DroneChunkTicketKey key) {
		return key != null && key.simulation() ? DRONE_SIMULATION_CHUNK_TICKET_TYPE : DRONE_LOADING_CHUNK_TICKET_TYPE;
	}

	private static void cleanupExpiredPostControlMoveSuppression(MinecraftServer server) {
		if (server == null) {
			return;
		}
		ServerLevel overworld = server.overworld();
		long now = overworld == null ? Long.MAX_VALUE : overworld.getGameTime();
		for (Map.Entry<UUID, Long> entry : new ArrayList<>(POST_CONTROL_MOVE_SUPPRESSED_UNTIL_TICK.entrySet())) {
			Long untilTick = entry.getValue();
			if (untilTick == null || now > untilTick) {
				POST_CONTROL_MOVE_SUPPRESSED_UNTIL_TICK.remove(entry.getKey(), untilTick);
			}
		}
		for (Map.Entry<UUID, Long> entry : new ArrayList<>(POST_CONTROL_VIEW_TELEPORT_ACK_UNTIL_TICK.entrySet())) {
			Long untilTick = entry.getValue();
			if (untilTick == null || now > untilTick) {
				POST_CONTROL_VIEW_TELEPORT_ACK_UNTIL_TICK.remove(entry.getKey(), untilTick);
			}
		}
	}

	private static void recoverOrphanedControlledOperators(MinecraftServer server) {
		if (server == null || VISUALLY_CONTROLLED_PLAYERS.isEmpty()) {
			return;
		}
		for (UUID playerId : new ArrayList<>(VISUALLY_CONTROLLED_PLAYERS)) {
			if (playerId == null || ACTIVE_SESSIONS.containsKey(playerId)) {
				continue;
			}
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			if (player == null) {
				VISUALLY_CONTROLLED_PLAYERS.remove(playerId);
				continue;
			}
			restoreOrphanedControlledOperator(player);
		}
	}

	private static void recoverPlayersWithStaleDronePassenger(MinecraftServer server) {
		if (server == null || server.getPlayerList() == null) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player == null || ACTIVE_SESSIONS.containsKey(player.getUUID())) {
				continue;
			}
			if (VISUALLY_CONTROLLED_PLAYERS.contains(player.getUUID())) {
				restoreOrphanedControlledOperator(player);
				continue;
			}
			boolean hasDronePassenger = false;
			for (Entity passenger : new ArrayList<>(player.getPassengers())) {
				if (resolveDroneRoot(passenger) != null) {
					hasDronePassenger = true;
					break;
				}
			}
			if (!hasDronePassenger) {
				continue;
			}
			restoreOrphanedControlledOperator(player);
		}
	}

	private static void restoreOrphanedControlledOperator(ServerPlayer player) {
		if (player == null) {
			return;
		}
		VISUALLY_CONTROLLED_PLAYERS.remove(player.getUUID());
		clearControlledOperatorTransientState(player, null);
		clearControlledOperatorPassengerAttachment(player);
		removeControlledOperatorBodyMirror(player);
		detachAnyDronePassengersFromController(player);
		clearControlledOperatorMovementState(player);
		markPostControlMoveSuppressedForPlayer(player);
		restoreControlledOperatorClientState(player);
		schedulePostControlClientResync(player);
	}

	private static void markPostControlMoveSuppressedForPlayer(ServerPlayer player) {
		if (player == null || player.level() == null) {
			return;
		}
		POST_CONTROL_MOVE_SUPPRESSED_UNTIL_TICK.put(
				player.getUUID(),
				player.level().getGameTime() + POST_CONTROL_MOVE_PACKET_SUPPRESSION_TICKS
		);
	}

	private static void markPostControlDroneTeleportAcks(ServerPlayer player) {
		if (player == null || player.level() == null) {
			return;
		}
		POST_CONTROL_VIEW_TELEPORT_ACK_UNTIL_TICK.put(
				player.getUUID(),
				player.level().getGameTime() + POST_CONTROL_VIEW_TELEPORT_ACK_GRACE_TICKS
		);
	}

	private static void schedulePostControlClientResync(ServerPlayer player) {
		if (player == null || player.level() == null) {
			return;
		}
		POST_CONTROL_CLIENT_RESYNC_UNTIL_TICK.put(
				player.getUUID(),
				player.level().getGameTime() + POST_CONTROL_CLIENT_RESYNC_TICKS
		);
	}

	private static void processPendingPostControlClientResync(MinecraftServer server) {
		if (server == null || POST_CONTROL_CLIENT_RESYNC_UNTIL_TICK.isEmpty() || server.getPlayerList() == null) {
			return;
		}
		ServerLevel overworld = server.overworld();
		long now = overworld == null ? Long.MAX_VALUE : overworld.getGameTime();
		for (Map.Entry<UUID, Long> entry : new ArrayList<>(POST_CONTROL_CLIENT_RESYNC_UNTIL_TICK.entrySet())) {
			UUID playerId = entry.getKey();
			Long untilTick = entry.getValue();
			if (playerId == null || untilTick == null || now > untilTick) {
				POST_CONTROL_CLIENT_RESYNC_UNTIL_TICK.remove(playerId, untilTick);
				continue;
			}
			if (ACTIVE_SESSIONS.containsKey(playerId)) {
				POST_CONTROL_CLIENT_RESYNC_UNTIL_TICK.remove(playerId, untilTick);
				continue;
			}
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			if (player == null) {
				POST_CONTROL_CLIENT_RESYNC_UNTIL_TICK.remove(playerId, untilTick);
				continue;
			}
			restoreControlledOperatorClientState(player, false);
		}
	}

	private static void tickControlledSessions(MinecraftServer server) {
		if (server == null || ACTIVE_SESSIONS.isEmpty()) {
			return;
		}
		for (Map.Entry<UUID, DroneControlSession> entry : new ArrayList<>(ACTIVE_SESSIONS.entrySet())) {
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			DroneControlSession session = entry.getValue();
			if (player == null || session == null) {
				continue;
			}

			Entity root = findDroneRoot(server, session.droneDimension(), session.droneUuid());
			if (root == null || !root.isAlive()) {
				removeControlledDroneTurretAirTrigger(player);
				if (shouldKeepControlledSessionWaitingForDroneRoot(server, session)) {
					syncControlledOperatorFallbackView(player, session);
					syncControlledOperatorBodyMirror(player, false);
					updateDroneHud(player, session, false);
					continue;
				}
				stopControlling(player, true);
				continue;
			}
			session.refreshKnownDroneLocation(root);
			if (!player.isAlive() || player.isSpectator()) {
				stopControlling(player, false);
				continue;
			}
			if (!Objects.equals(resolveAuthoritativeDroneControllerId(session.droneUuid()), player.getUUID())) {
				stopControlling(player, false);
				continue;
			}
			if (tickDroneProjectileImpact(root) || tickDroneEnvironmentDamage(root)) {
				continue;
			}

			DroneInputState input = INPUTS.getOrDefault(player.getUUID(), DroneInputState.EMPTY);
			if (input.shift()) {
				stopControlling(player, true);
				continue;
			}
			tickControlledOperatorBodyPhysics(player);
			updateControlledDrives(player, session, input);
			tickControlledDrone(player, root, session);
			handleControlledTurretJumpInput(player, root, session, input);
			syncControlledDroneTurretAirTrigger(player, root);
		}
	}

	private static void synchronizeControlledOperatorAudio(MinecraftServer server) {
		if (server == null || CONTROLLED_OPERATOR_AUDIO.isEmpty()) {
			return;
		}
		// While controlling a drone the operator should hear only the drone-side scene.
		// Keeping a second body-side voice feed active makes the parked body act like a
		// parallel listener, which is exactly the "кукла" echo the player asked to remove.
		shutdownAllControlledOperatorAudio();
	}

	public static void onVoicechatMicrophonePacket(MicrophonePacketEvent event) {
		if (event == null || !ServerVoicechatIntegration.isLoaded()) {
			return;
		}
		VoicechatApi voicechatApi = ServerVoicechatIntegration.getApi();
		VoicechatServerApi voicechatServerApi = ServerVoicechatIntegration.getServerApi();
		if (voicechatApi == null || voicechatServerApi == null) {
			return;
		}
		VoicechatConnection senderConnection = event.getSenderConnection();
		if (senderConnection == null || senderConnection.getPlayer() == null || event.getPacket() == null) {
			return;
		}
		Object rawPlayer = senderConnection.getPlayer().getPlayer();
		if (!(rawPlayer instanceof ServerPlayer senderPlayer) || !(senderPlayer.level() instanceof ServerLevel senderLevel)) {
			return;
		}
		byte[] opusData = event.getPacket().getOpusEncodedData();
		if (opusData == null || opusData.length == 0) {
			return;
		}
		MinecraftServer server = senderLevel.getServer();
		if (server == null) {
			return;
		}
		UUID senderUuid = senderConnection.getPlayer().getUuid();
		boolean whispering = event.getPacket().isWhispering();
		byte[] copiedOpusData = opusData.clone();
		server.execute(() -> {
			relayControlledOperatorDroneMicrophoneVoice(
					server,
					senderUuid,
					whispering,
					copiedOpusData,
					voicechatApi,
					voicechatServerApi
			);
		});
	}

	private static void routeControlledOperatorBodyVoice(
			MinecraftServer server,
			net.minecraft.resources.ResourceKey<Level> senderDimension,
			Position senderPosition,
			UUID senderUuid,
			boolean whispering,
			byte[] opusData,
			VoicechatApi voicechatApi
	) {
		if (server == null || senderDimension == null || senderPosition == null || senderUuid == null || opusData == null || opusData.length == 0 || voicechatApi == null) {
			return;
		}
		if (CONTROLLED_OPERATOR_AUDIO.isEmpty()) {
			return;
		}
		double voiceDistance = Math.max(1.0D, voicechatApi.getVoiceChatDistance());
		double maxDistance = whispering ? voiceDistance * CONTROLLED_OPERATOR_BODY_VOICE_WHISPER_DISTANCE_FACTOR : voiceDistance;
		double maxDistanceSqr = maxDistance * maxDistance;
		for (Map.Entry<UUID, ControlledOperatorAudioRuntime> entry : new ArrayList<>(CONTROLLED_OPERATOR_AUDIO.entrySet())) {
			UUID operatorUuid = entry.getKey();
			if (operatorUuid == null || operatorUuid.equals(senderUuid)) {
				continue;
			}
			ControlledOperatorAudioRuntime runtime = entry.getValue();
			ServerPlayer operator = server.getPlayerList().getPlayer(operatorUuid);
			DroneControlSession session = ACTIVE_SESSIONS.get(operatorUuid);
			if (runtime == null
					|| operator == null
					|| session == null
					|| !Objects.equals(resolveAuthoritativeDroneControllerId(session.droneUuid()), operatorUuid)
					|| !(operator.level() instanceof ServerLevel operatorLevel)
					|| !Objects.equals(operatorLevel.dimension(), senderDimension)) {
				continue;
			}
			double dx = senderPosition.getX() - operator.getX();
			double dy = senderPosition.getY() - (operator.getY() + operator.getEyeHeight());
			double dz = senderPosition.getZ() - operator.getZ();
			double distanceSqr = (dx * dx) + (dy * dy) + (dz * dz);
			if (distanceSqr > maxDistanceSqr) {
				continue;
			}
			if (isVoiceAlreadyAudibleAtControlledDrone(server, session, senderDimension, senderPosition, maxDistanceSqr)) {
				continue;
			}
			float attenuation = controlledOperatorBodyVoiceAttenuation(Math.sqrt(distanceSqr), maxDistance);
			if (attenuation <= 0.0F) {
				continue;
			}
			runtime.offerBodyVoicePacket(senderUuid, opusData, attenuation * CONTROLLED_OPERATOR_BODY_VOICE_GAIN, voicechatApi);
		}
	}

	private static boolean isVoiceAlreadyAudibleAtControlledDrone(
			MinecraftServer server,
			DroneControlSession session,
			net.minecraft.resources.ResourceKey<Level> senderDimension,
			Position senderPosition,
			double maxDistanceSqr
	) {
		if (server == null || session == null || senderDimension == null || senderPosition == null || maxDistanceSqr <= 0.0D) {
			return false;
		}
		if (!Objects.equals(session.droneDimension(), senderDimension)) {
			return false;
		}
		Vec3 droneVoiceOrigin = controlledDroneVoiceOrigin(server, session);
		if (droneVoiceOrigin == null) {
			return false;
		}
		double dx = senderPosition.getX() - droneVoiceOrigin.x;
		double dy = senderPosition.getY() - droneVoiceOrigin.y;
		double dz = senderPosition.getZ() - droneVoiceOrigin.z;
		return (dx * dx) + (dy * dy) + (dz * dz) <= maxDistanceSqr;
	}

	private static Vec3 controlledDroneVoiceOrigin(MinecraftServer server, DroneControlSession session) {
		if (server == null || session == null) {
			return null;
		}
		Entity root = findDroneRoot(server, session.droneDimension(), session.droneUuid());
		if (root != null && root.isAlive()) {
			return resolveSafeDroneCameraOrigin(root, droneCameraOrigin(root));
		}
		return droneCameraOrigin(session.lastKnownDronePos());
	}

	private static float controlledOperatorBodyVoiceAttenuation(double distance, double maxDistance) {
		if (maxDistance <= 0.0D || distance >= maxDistance) {
			return 0.0F;
		}
		return (float) Math.clamp(1.0D - distance / maxDistance, 0.0D, 1.0D);
	}

	private static void relayControlledOperatorDroneMicrophoneVoice(
			MinecraftServer server,
			UUID senderUuid,
			boolean whispering,
			byte[] opusData,
			VoicechatApi voicechatApi,
			VoicechatServerApi voicechatServerApi
	) {
		if (server == null || senderUuid == null || opusData == null || opusData.length == 0 || voicechatApi == null || voicechatServerApi == null) {
			return;
		}
		DroneControlSession session = ACTIVE_SESSIONS.get(senderUuid);
		if (session == null || !Objects.equals(resolveAuthoritativeDroneControllerId(session.droneUuid()), senderUuid)) {
			return;
		}
		Entity root = findDroneRoot(server, session.droneDimension(), session.droneUuid());
		if (root == null || !root.isAlive() || !hasDroneMicrophoneModule(root) || !(root.level() instanceof ServerLevel droneLevel)) {
			return;
		}
		Vec3 droneVoiceOrigin = resolveSafeDroneCameraOrigin(root, droneCameraOrigin(root));
		float relayDistance = resolveDroneMicrophoneRelayDistance(voicechatApi, whispering);
		if (relayDistance <= 0.0F) {
			return;
		}
		Position relayPosition = voicechatApi.createPosition(droneVoiceOrigin.x, droneVoiceOrigin.y, droneVoiceOrigin.z);
		long sequence = nextDroneMicrophoneRelaySequence(senderUuid);
		UUID channelId = droneMicrophoneRelayChannelId(senderUuid);
		LocationSoundPacket soundPacket = new LocationSoundPacket(
				channelId,
				senderUuid,
				droneVoiceOrigin,
				opusData,
				sequence,
				relayDistance,
				null
		);
		LocationalSoundPacketImpl wrappedPacket = new LocationalSoundPacketImpl(soundPacket);
		for (de.maxhenkel.voicechat.api.ServerPlayer nearbyPlayer : voicechatServerApi.getPlayersInRange(
				voicechatApi.fromServerLevel(droneLevel),
				relayPosition,
				relayDistance,
				player -> player != null && !Objects.equals(player.getUuid(), senderUuid)
		)) {
			if (nearbyPlayer == null) {
				continue;
			}
			VoicechatConnection receiverConnection = voicechatServerApi.getConnectionOf(nearbyPlayer.getUuid());
			if (receiverConnection == null) {
				continue;
			}
			voicechatServerApi.sendLocationalSoundPacketTo(receiverConnection, wrappedPacket);
		}
	}

	private static float resolveDroneMicrophoneRelayDistance(VoicechatApi voicechatApi, boolean whispering) {
		double voiceDistance = Math.max(1.0D, voicechatApi == null ? 0.0D : voicechatApi.getVoiceChatDistance());
		if (whispering) {
			voiceDistance *= CONTROLLED_OPERATOR_BODY_VOICE_WHISPER_DISTANCE_FACTOR;
		}
		return (float) voiceDistance;
	}

	private static long nextDroneMicrophoneRelaySequence(UUID senderUuid) {
		long next = DRONE_MICROPHONE_RELAY_SEQUENCES.getOrDefault(senderUuid, 0L);
		DRONE_MICROPHONE_RELAY_SEQUENCES.put(senderUuid, next + 1L);
		return next;
	}

	private static UUID droneMicrophoneRelayChannelId(UUID senderUuid) {
		UUID resolvedSenderUuid = senderUuid == null ? UUID.randomUUID() : senderUuid;
		return UUID.nameUUIDFromBytes(("lg2:drone_microphone:" + resolvedSenderUuid).getBytes(StandardCharsets.UTF_8));
	}

	private static void tickControlledOperatorBodyPhysics(ServerPlayer player) {
		if (player == null
				|| !player.isAlive()
				|| player.isSpectator()
				|| !(player.level() instanceof ServerLevel level)) {
			return;
		}
		level.getChunkAt(player.blockPosition());
		Vec3 before = player.position();
		Vec3 beforeVelocity = player.getDeltaMovement();
		player.travel(Vec3.ZERO);
		if (!before.equals(player.position()) || !beforeVelocity.equals(player.getDeltaMovement())) {
			player.hurtMarked = true;
		}
	}

	private static boolean shouldKeepControlledSessionWaitingForDroneRoot(MinecraftServer server, DroneControlSession session) {
		if (server == null || session == null) {
			return false;
		}
		if (SCREEN_STREAM_DRONE_LOAD_STATES.containsKey(session.droneUuid())) {
			return true;
		}
		long now = controlledSessionGameTime(server, session);
		return session.markDroneRootMissing(now);
	}

	private static long controlledSessionGameTime(MinecraftServer server, DroneControlSession session) {
		if (server == null) {
			return Long.MAX_VALUE;
		}
		ServerLevel level = session == null || session.droneDimension() == null ? null : server.getLevel(session.droneDimension());
		if (level == null) {
			level = server.overworld();
		}
		return level == null ? Long.MAX_VALUE : level.getGameTime();
	}

	private static void queueDroneControlStartPreload(ServerPlayer player, Entity root) {
		if (player == null || root == null || !(root.level() instanceof ServerLevel level)) {
			return;
		}
		PENDING_CONTROL_STARTS.put(
				player.getUUID(),
				new PendingDroneControlStart(root.getUUID(), level.dimension(), root.blockPosition(), level.getGameTime())
		);
		updateDroneChunkTickets(level.getServer());
		Lg2Messages.actionBar(player, "message.lg2.drone.preparing_link");
	}

	private static void queueDroneControlStartPreload(ServerPlayer player, DroneLiveFeedState state) {
		if (player == null || state == null || state.droneUuid() == null || state.dimension() == null || state.pos() == null) {
			return;
		}
		MinecraftServer server = player.level() == null ? null : player.level().getServer();
		if (server == null) {
			return;
		}
		ServerLevel level = server.getLevel(state.dimension());
		long startedAtTick = level != null ? level.getGameTime() : server.getTickCount();
		PENDING_CONTROL_STARTS.put(
				player.getUUID(),
				new PendingDroneControlStart(state.droneUuid(), state.dimension(), state.pos(), startedAtTick)
		);
		updateDroneChunkTickets(server);
		Lg2Messages.actionBar(player, "message.lg2.drone.preparing_link");
	}

	private static void tickPendingControlStarts(MinecraftServer server) {
		if (server == null || PENDING_CONTROL_STARTS.isEmpty()) {
			return;
		}
		for (Map.Entry<UUID, PendingDroneControlStart> entry : new ArrayList<>(PENDING_CONTROL_STARTS.entrySet())) {
			UUID playerUuid = entry.getKey();
			PendingDroneControlStart pending = entry.getValue();
			ServerPlayer player = playerUuid == null ? null : server.getPlayerList().getPlayer(playerUuid);
			if (player == null || pending == null || pending.droneDimension() == null || pending.droneUuid() == null) {
				PENDING_CONTROL_STARTS.remove(playerUuid);
				continue;
			}
			if (!player.isAlive() || player.isSpectator()) {
				PENDING_CONTROL_STARTS.remove(playerUuid);
				continue;
			}
			UUID currentControllerId = resolveAuthoritativeDroneControllerId(pending.droneUuid());
			if (currentControllerId != null && !Objects.equals(currentControllerId, player.getUUID())) {
				PENDING_CONTROL_STARTS.remove(playerUuid);
				Lg2Messages.actionBar(player, "message.lg2.drone.busy");
				continue;
			}
			ServerLevel pendingLevel = server.getLevel(pending.droneDimension());
			long now = pendingLevel != null ? pendingLevel.getGameTime() : server.getTickCount();
			boolean timedOut = now - pending.startedAtTick() >= DRONE_CONTROL_PRELOAD_TIMEOUT_TICKS;
			Entity root = findDroneRoot(server, pending.droneDimension(), pending.droneUuid());
			if (root == null || !root.isAlive()) {
				if (!timedOut) {
					continue;
				}
				PENDING_CONTROL_STARTS.remove(playerUuid);
				Lg2Messages.actionBar(player, "message.lg2.drone.unavailable");
				continue;
			}
			boolean ready = isDroneControlStartAreaReady(root, DRONE_CONTROL_PRELOAD_READY_RADIUS_CHUNKS);
			if (!ready && !(timedOut && isDroneControlStartAreaReady(root, 0))) {
				continue;
			}
			PENDING_CONTROL_STARTS.remove(playerUuid);
			startControlling(player, root);
		}
	}

	private static boolean isDroneControlStartAreaReady(Entity root, int radiusChunks) {
		if (root == null || !(root.level() instanceof ServerLevel level)) {
			return false;
		}
		Vec3 cameraPos = resolveDroneCameraPosition(root);
		ChunkPos center = chunkPosAt(cameraPos.x, cameraPos.z);
		int radius = Math.max(0, radiusChunks);
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				if (level.getChunkSource().getChunkNow(center.x + dx, center.z + dz) == null) {
					return false;
				}
			}
		}
		return level.getChunkSource().getChunkNow(root.chunkPosition().x, root.chunkPosition().z) != null;
	}

	private static void tickUncontrolledDrones(MinecraftServer server) {
		if (server == null || UNCONTROLLED_DRONES.isEmpty()) {
			return;
		}

		for (Map.Entry<UUID, UncontrolledDroneState> entry : new ArrayList<>(UNCONTROLLED_DRONES.entrySet())) {
			UncontrolledDroneState state = entry.getValue();
			if (state == null || state.dimension() == null) {
				UNCONTROLLED_DRONES.remove(entry.getKey());
				continue;
			}
			Entity root = findDroneRoot(server, state.dimension(), state.droneUuid());
			if (root == null || !root.isAlive()) {
				UNCONTROLLED_DRONES.remove(entry.getKey());
				continue;
			}

			// If someone is actively controlling the drone, controlled physics owns the root body.
			if (isDroneActivelyControlled(root)) {
				UNCONTROLLED_DRONES.remove(entry.getKey());
				continue;
			}

			tickUncontrolledDrone(root, state);
		}
	}

	private static void tickControlledDrone(ServerPlayer player, Entity root, DroneControlSession session) {
		if (!(root.level() instanceof ServerLevel)) {
			return;
		}
		tickDroneTurretAutomation(root);
		boolean autoAimAdjustedView = syncControlledOperatorAutoAim(player, session, root);
		session.setIntendedVelocity(DroneFlightPhysics.step(
				session.controlPitch(),
				session.controlYaw(),
				session.forwardDrive(),
				session.strafeDrive()
		));
		decayControlledDroneSurfaceWear(session, root.level().getGameTime());
		syncControlledDronePresentation(player, root, session);
		syncControlledOperatorBodyMirror(player, false);
		syncControlledOperatorView(player, session, root, false, autoAimAdjustedView);
		syncControlledOperatorNightVision(player, session, root);
		updateDroneHud(player, session, false);
	}

	private static void updateControlledDrives(ServerPlayer player, DroneControlSession session, DroneInputState input) {
		if (player == null || session == null) {
			return;
		}
		DroneInputState controlInput = input == null ? DroneInputState.EMPTY : input;
		double driveStep = getControlDriveStep(getControlSpeedSlot(player));
		session.setForwardDrive(DroneFlightPhysics.adjustDrive(
				session.forwardDrive(),
				controlInput.forward(),
				controlInput.backward(),
				driveStep,
				DroneFlightPhysics.MAX_FORWARD_DRIVE
		));
		session.setStrafeDrive(DroneFlightPhysics.adjustDrive(
				session.strafeDrive(),
				controlInput.right(),
				controlInput.left(),
				driveStep,
				DroneFlightPhysics.MAX_STRAFE_DRIVE
		));
	}

	private static void seedControlledDrivesFromWorldVelocity(
			DroneControlSession session,
			Vec3 worldVelocity,
			float yaw,
			float pitch
	) {
		if (session == null || worldVelocity == null) {
			return;
		}
		Vec3 horizontalVelocity = new Vec3(worldVelocity.x, 0.0D, worldVelocity.z);
		if (horizontalVelocity.lengthSqr() <= 1.0E-6D) {
			return;
		}
		Vec3 horizontalForward = Vec3.directionFromRotation(0.0F, yaw);
		Vec3 right = new Vec3(-horizontalForward.z, 0.0D, horizontalForward.x);
		if (right.lengthSqr() <= 1.0E-6D) {
			right = new Vec3(1.0D, 0.0D, 0.0D);
		} else {
			right = right.normalize();
		}
		double forwardProjection = horizontalVelocity.dot(horizontalForward);
		double forwardScale = forwardProjection >= 0.0D
				? DroneFlightPhysics.MAX_FORWARD_SPEED
				: DroneFlightPhysics.MAX_REVERSE_SPEED;
		double forwardDrive = forwardScale <= 1.0E-6D ? 0.0D : forwardProjection / forwardScale;
		double strafeDrive = DroneFlightPhysics.MAX_STRAFE_SPEED <= 1.0E-6D
				? 0.0D
				: horizontalVelocity.dot(right) / DroneFlightPhysics.MAX_STRAFE_SPEED;
		forwardDrive = net.minecraft.util.Mth.clamp(forwardDrive, -DroneFlightPhysics.MAX_FORWARD_DRIVE, DroneFlightPhysics.MAX_FORWARD_DRIVE);
		strafeDrive = net.minecraft.util.Mth.clamp(strafeDrive, -DroneFlightPhysics.MAX_STRAFE_DRIVE, DroneFlightPhysics.MAX_STRAFE_DRIVE);
		if (Math.abs(forwardDrive) <= 1.0E-4D && Math.abs(strafeDrive) <= 1.0E-4D) {
			return;
		}
		session.setForwardDrive(forwardDrive);
		session.setStrafeDrive(strafeDrive);
		session.setDisplayForwardDrive(forwardDrive);
		session.setDisplayStrafeDrive(strafeDrive);
		Vec3 seededVelocity = DroneFlightPhysics.step(pitch, yaw, forwardDrive, strafeDrive);
		session.setIntendedVelocity(seededVelocity);
		session.setVelocity(finiteVecOr(worldVelocity, seededVelocity));
	}

	private static void restoreControlledDrivesFromUncontrolledState(
			DroneControlSession session,
			UncontrolledDroneState state,
			float yaw,
			float pitch
	) {
		if (session == null || state == null) {
			return;
		}
		if (!state.hasDriveState()) {
			seedControlledDrivesFromWorldVelocity(session, state.velocity(), yaw, pitch);
			return;
		}
		session.setForwardDrive(state.forwardDrive());
		session.setStrafeDrive(state.strafeDrive());
		session.setDisplayForwardDrive(state.displayForwardDrive());
		session.setDisplayStrafeDrive(state.displayStrafeDrive());
		Vec3 intendedVelocity = DroneFlightPhysics.step(pitch, yaw, state.forwardDrive(), state.strafeDrive());
		session.setIntendedVelocity(intendedVelocity);
		session.setVelocity(finiteVecOr(state.velocity(), intendedVelocity));
	}

	private static void syncControlledDronePresentation(ServerPlayer player, Entity root, DroneControlSession session) {
		if (root == null || session == null) {
			return;
		}
		Vec3 velocity = controlledOperatorVisualVelocity(session);
		syncDroneCameraAnchor(root, velocity);
		maybePlayDroneLoopSound(root, session.forwardDrive(), session.strafeDrive(), true);
		if (player != null) {
			setHotbarVisualHidden(player, true);
		}
		double displayForwardDrive = net.minecraft.util.Mth.lerp(
				DRONE_DISPLAY_DRIVE_SMOOTHING,
				session.displayForwardDrive(),
				session.forwardDrive()
		);
		double displayStrafeDrive = net.minecraft.util.Mth.lerp(
				DRONE_DISPLAY_DRIVE_SMOOTHING,
				session.displayStrafeDrive(),
				session.strafeDrive()
		);
		session.setDisplayForwardDrive(displayForwardDrive);
		session.setDisplayStrafeDrive(displayStrafeDrive);
		syncDroneDisplay(root, session.proxyYaw(), session.proxyPitch(), displayForwardDrive, displayStrafeDrive, true);
		persistDroneRestartRecoveryState(root, null);
	}

	private static boolean handleControlledServerCollision(
			ServerPlayer player,
			Entity root,
			DroneControlSession session,
			Vec3 intendedMovement,
			Vec3 actualMovement
	) {
		if (player == null || root == null || session == null) {
			return false;
		}
		boolean horizontalCollision = root.horizontalCollision;
		boolean verticalCollisionBelow = root.verticalCollisionBelow;
		boolean verticalCollision = DroneImpactModel.hasMeaningfulVerticalCollision(
				intendedMovement,
				actualMovement,
				root.verticalCollision,
				verticalCollisionBelow
		);
		boolean groundContact = DroneImpactModel.hasVerifiedGroundWearContact(
				intendedMovement,
				actualMovement,
				verticalCollisionBelow,
				hasSupportingBlockBelow(root)
		);
		if (!horizontalCollision && !verticalCollision && !groundContact) {
			return false;
		}
		Vec3 currentPos = root.position();
		Vec3 previousPos = currentPos.subtract(actualMovement == null ? Vec3.ZERO : actualMovement);
		long gameTime = root.level() == null ? Long.MIN_VALUE : root.level().getGameTime();
		if (isDroneCollisionWaterProtected(root, previousPos, intendedMovement)) {
			// Water still protects the drone from collision damage, but a drone scraping
			// the bottom must remain readable to its FPV operator.
			playDroneSurfaceWearVisuals(root, intendedMovement, actualMovement, groundContact, gameTime);
			return false;
		}

		float impactDamage = DroneImpactModel.computeImpactDamage(
				intendedMovement,
			actualMovement,
			horizontalCollision,
			verticalCollision
		);
		if (impactDamage > DroneImpactModel.CONTROL_IMPACT_BREAK_DAMAGE
				|| updateControlledDroneSurfaceWear(session, root, intendedMovement, actualMovement, groundContact, gameTime)) {
			destroyControlledDroneFromImpact(player, session, root);
			return true;
		}
		return false;
	}

	private static boolean hasBlockedHorizontalMovement(Vec3 intendedMovement, Vec3 actualMovement) {
		if (intendedMovement == null || actualMovement == null) {
			return false;
		}
		double intendedHorizontal = Math.sqrt(intendedMovement.x * intendedMovement.x + intendedMovement.z * intendedMovement.z);
		if (intendedHorizontal <= CONTROLLED_DRONE_BLOCKED_MOVEMENT_EPSILON) {
			return false;
		}
		double actualHorizontal = Math.sqrt(actualMovement.x * actualMovement.x + actualMovement.z * actualMovement.z);
		double requiredLoss = Math.max(CONTROLLED_DRONE_BLOCKED_MOVEMENT_EPSILON, intendedHorizontal * 0.12D);
		return intendedHorizontal - actualHorizontal > requiredLoss;
	}

	private static boolean hasBlockedVerticalMovement(Vec3 intendedMovement, Vec3 actualMovement) {
		if (intendedMovement == null || actualMovement == null) {
			return false;
		}
		return positiveMovementDeficit(intendedMovement.y, actualMovement.y) > CONTROLLED_DRONE_BLOCKED_MOVEMENT_EPSILON;
	}

	private static boolean hasBlockedDownwardMovement(Vec3 intendedMovement, Vec3 actualMovement) {
		if (intendedMovement == null || actualMovement == null) {
			return false;
		}
		return intendedMovement.y < -CONTROLLED_DRONE_BLOCKED_MOVEMENT_EPSILON
				&& positiveMovementDeficit(intendedMovement.y, actualMovement.y) > CONTROLLED_DRONE_BLOCKED_MOVEMENT_EPSILON;
	}

	private static boolean isPlausibleControlledDroneMove(Vec3 actualMovement, Vec3 intendedMovement) {
		if (!isFiniteVec(actualMovement)) {
			return false;
		}
		double intendedDistance = isFiniteVec(intendedMovement) ? intendedMovement.length() : 0.0D;
		double maxDistance = Math.max(CONTROLLED_DRONE_MAX_REPORTED_MOVE_BLOCKS, intendedDistance + 2.0D);
		return actualMovement.lengthSqr() <= maxDistance * maxDistance;
	}

	private static Vec3 finiteVecOr(Vec3 value, Vec3 fallback) {
		return isFiniteVec(value) ? value : fallback;
	}

	private static boolean isFiniteVec(Vec3 value) {
		return value != null
				&& Double.isFinite(value.x)
				&& Double.isFinite(value.y)
				&& Double.isFinite(value.z);
	}

	private static double positiveMovementDeficit(double intendedComponent, double actualComponent) {
		double intendedMagnitude = Math.abs(intendedComponent);
		if (intendedMagnitude <= CONTROLLED_DRONE_BLOCKED_MOVEMENT_EPSILON) {
			return 0.0D;
		}
		double actualMagnitude = Math.abs(actualComponent);
		if (actualMagnitude <= CONTROLLED_DRONE_BLOCKED_MOVEMENT_EPSILON) {
			return intendedMagnitude;
		}
		if (Math.signum(actualComponent) != Math.signum(intendedComponent)) {
			return 0.0D;
		}
		return Math.max(0.0D, intendedMagnitude - actualMagnitude);
	}

	private static ControlledCollisionState resolveControlledCollisionState(
			Entity root,
			Vec3 previousPos,
			Vec3 intendedMovement,
			Vec3 actualMovement
	) {
		if (root == null || !(root.level() instanceof ServerLevel level) || previousPos == null) {
			return ControlledCollisionState.NONE;
		}
		boolean blockedHorizontal = hasBlockedHorizontalMovement(intendedMovement, actualMovement);
		boolean blockedVertical = hasBlockedVerticalMovement(intendedMovement, actualMovement);
		boolean blockedDownward = hasBlockedDownwardMovement(intendedMovement, actualMovement);
		if (!blockedHorizontal && !blockedVertical && !blockedDownward) {
			return ControlledCollisionState.NONE;
		}

		AABB previousBox = droneBoxAt(previousPos);
		Vec3 horizontalMovement = intendedMovement == null
				? Vec3.ZERO
				: new Vec3(intendedMovement.x, 0.0D, intendedMovement.z);
		AABB horizontalTargetBox = previousBox.move(horizontalMovement);
		Vec3 verticalMovement = intendedMovement == null
				? Vec3.ZERO
				: new Vec3(0.0D, intendedMovement.y, 0.0D);
		boolean horizontalCollision = blockedHorizontal && pathHitsSolidCollision(level, previousBox, horizontalMovement);
		boolean verticalCollisionBelow = blockedDownward && pathHitsSolidCollision(level, horizontalTargetBox, verticalMovement);
		boolean verticalCollision = blockedVertical && pathHitsSolidCollision(level, horizontalTargetBox, verticalMovement);
		return new ControlledCollisionState(horizontalCollision, verticalCollision, verticalCollisionBelow);
	}

	private static boolean pathHitsSolidCollision(ServerLevel level, AABB startBox, Vec3 movement) {
		if (level == null || startBox == null || movement == null) {
			return false;
		}
		double movementLength = movement.length();
		if (movementLength <= CONTROLLED_DRONE_BLOCKED_MOVEMENT_EPSILON) {
			return false;
		}
		int steps = Math.max(1, net.minecraft.util.Mth.ceil(movementLength / DRONE_COLLISION_SWEEP_STEP));
		for (int step = 1; step <= steps; step++) {
			double progress = (double) step / (double) steps;
			AABB sampleBox = startBox.move(movement.scale(progress));
			if (boxHitsSolidCollision(level, sampleBox)) {
				return true;
			}
		}
		return false;
	}

	private static boolean boxHitsSolidCollision(ServerLevel level, AABB box) {
		if (level == null || box == null) {
			return false;
		}
		VoxelShape probeShape = Shapes.create(box);
		int minX = net.minecraft.util.Mth.floor(box.minX + 1.0E-7D);
		int minY = net.minecraft.util.Mth.floor(box.minY + 1.0E-7D);
		int minZ = net.minecraft.util.Mth.floor(box.minZ + 1.0E-7D);
		int maxX = net.minecraft.util.Mth.floor(box.maxX - 1.0E-7D);
		int maxY = net.minecraft.util.Mth.floor(box.maxY - 1.0E-7D);
		int maxZ = net.minecraft.util.Mth.floor(box.maxZ - 1.0E-7D);
		for (BlockPos pos : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
			BlockState state = level.getBlockState(pos);
			VoxelShape collisionShape = state.getCollisionShape(level, pos);
			if (collisionShape.isEmpty()) {
				continue;
			}
			VoxelShape shiftedShape = collisionShape.move(pos.getX(), pos.getY(), pos.getZ());
			if (Shapes.joinIsNotEmpty(shiftedShape, probeShape, BooleanOp.AND)) {
				return true;
			}
		}
		return false;
	}

	private static boolean hasSupportingBlockBelow(Entity entity) {
		if (entity == null || !(entity.level() instanceof ServerLevel level)) {
			return false;
		}
		AABB box = entity.getBoundingBox();
		if (box == null) {
			return false;
		}
		return boxHitsSolidCollision(level, box.move(0.0D, -1.0E-4D, 0.0D));
	}

	private static Vec3 applyUncontrolledDroneEnvironment(Entity root, Vec3 velocity) {
		if (root == null || !(root.level() instanceof ServerLevel level)) {
			return finiteVecOr(velocity, Vec3.ZERO);
		}
		Vec3 adjusted = finiteVecOr(velocity, Vec3.ZERO);
		AABB box = root.getBoundingBox();
		if (box == null) {
			return adjusted;
		}

		boolean inWater = boxIntersectsFluid(level, box, FluidTags.WATER);
		boolean inLava = boxIntersectsFluid(level, box, FluidTags.LAVA);
		boolean stickyBlock = boxIntersectsAnyBlock(level, box, Blocks.COBWEB, Blocks.POWDER_SNOW, Blocks.SWEET_BERRY_BUSH);
		boolean honeyContact = boxIntersectsAnyBlock(level, box, Blocks.HONEY_BLOCK);
		boolean soulSandContact = boxTouchesAnyBlockBelow(level, box, Blocks.SOUL_SAND);

		if (stickyBlock) {
			adjusted = new Vec3(
					adjusted.x * DRONE_COBWEB_HORIZONTAL_DRAG,
					adjusted.y * DRONE_COBWEB_VERTICAL_DRAG,
					adjusted.z * DRONE_COBWEB_HORIZONTAL_DRAG
			);
		}
		if (inWater) {
			Vec3 flow = averageFluidFlow(level, box, FluidTags.WATER);
			adjusted = new Vec3(
					adjusted.x * DRONE_WATER_HORIZONTAL_DRAG,
					adjusted.y * DRONE_WATER_VERTICAL_DRAG + DRONE_WATER_BUOYANCY,
					adjusted.z * DRONE_WATER_HORIZONTAL_DRAG
			).add(flow.scale(DRONE_WATER_FLOW_SCALE));
		}
		if (inLava) {
			Vec3 flow = averageFluidFlow(level, box, FluidTags.LAVA);
			adjusted = new Vec3(
					adjusted.x * DRONE_LAVA_HORIZONTAL_DRAG,
					adjusted.y * DRONE_LAVA_VERTICAL_DRAG,
					adjusted.z * DRONE_LAVA_HORIZONTAL_DRAG
			).add(flow.scale(DRONE_WATER_FLOW_SCALE * 0.45D));
		}
		if (honeyContact) {
			adjusted = new Vec3(
					adjusted.x * DRONE_HONEY_HORIZONTAL_DRAG,
					Math.min(adjusted.y, 0.0D) * 0.80D + Math.max(adjusted.y, 0.0D) * 0.45D,
					adjusted.z * DRONE_HONEY_HORIZONTAL_DRAG
			);
		}
		if (soulSandContact) {
			adjusted = new Vec3(
					adjusted.x * DRONE_SOUL_SAND_HORIZONTAL_DRAG,
					adjusted.y,
					adjusted.z * DRONE_SOUL_SAND_HORIZONTAL_DRAG
			);
		}

		BlockState bubbleColumn = findIntersectingBubbleColumn(level, box);
		if (bubbleColumn != null) {
			double push = bubbleColumn.getValue(BubbleColumnBlock.DRAG_DOWN)
					? -DRONE_BUBBLE_COLUMN_PUSH
					: DRONE_BUBBLE_COLUMN_PUSH;
			adjusted = new Vec3(adjusted.x, adjusted.y + push, adjusted.z);
		}
		return finiteVecOr(adjusted, Vec3.ZERO);
	}

	private static Vec3 applyUncontrolledGroundBraking(Vec3 velocity) {
		if (velocity == null) {
			return Vec3.ZERO;
		}
		double x = velocity.x * DRONE_GROUND_HORIZONTAL_DRAG;
		double z = velocity.z * DRONE_GROUND_HORIZONTAL_DRAG;
		double y = Math.abs(velocity.y) <= DRONE_GROUND_STOP_VERTICAL_SPEED ? 0.0D : velocity.y;
		if (x * x + z * z <= DRONE_GROUND_STOP_HORIZONTAL_SPEED_SQR) {
			x = 0.0D;
			z = 0.0D;
		}
		return new Vec3(x, y, z);
	}

	private static boolean shouldApplyUncontrolledGroundBraking(Entity root) {
		if (root == null || !(root.level() instanceof ServerLevel level)) {
			return false;
		}
		if (!root.onGround() && !root.verticalCollisionBelow && !hasSupportingBlockBelow(root)) {
			return false;
		}
		AABB box = root.getBoundingBox();
		if (box == null) {
			return false;
		}
		return !boxIntersectsFluid(level, box, FluidTags.WATER)
				&& !boxIntersectsFluid(level, box, FluidTags.LAVA)
				&& !boxIntersectsAnyBlock(level, box, Blocks.COBWEB, Blocks.POWDER_SNOW, Blocks.SWEET_BERRY_BUSH)
				&& !boxIntersectsAnyBlock(level, box, Blocks.HONEY_BLOCK)
				&& !boxTouchesAnyBlockBelow(level, box, Blocks.SOUL_SAND);
	}

	private static boolean hasUncontrolledDroneEnvironmentalMotion(Entity root) {
		if (root == null || !(root.level() instanceof ServerLevel level)) {
			return false;
		}
		AABB box = root.getBoundingBox();
		if (box == null) {
			return false;
		}
		if (findIntersectingBubbleColumn(level, box) != null) {
			return true;
		}
		Vec3 waterFlow = averageFluidFlow(level, box, FluidTags.WATER);
		Vec3 lavaFlow = averageFluidFlow(level, box, FluidTags.LAVA);
		return waterFlow.lengthSqr() > 1.0E-6D || lavaFlow.lengthSqr() > 1.0E-6D;
	}

	private static boolean tryApplyUncontrolledDroneSlimeBounce(Entity root, Vec3 incomingVelocity, boolean waterProtectedImpact) {
		if (root == null || !(root.level() instanceof ServerLevel level) || incomingVelocity == null || waterProtectedImpact) {
			return false;
		}
		if (!root.verticalCollisionBelow && !root.onGround()) {
			return false;
		}
		if (incomingVelocity.y >= -DRONE_SLIME_BOUNCE_MIN_SPEED) {
			return false;
		}
		AABB box = root.getBoundingBox();
		BlockState bounceState = box == null ? null : findTouchedBlockStateBelow(level, box, Blocks.SLIME_BLOCK);
		if (bounceState == null) {
			return false;
		}
		root.setDeltaMovement(incomingVelocity);
		bounceState.getBlock().updateEntityMovementAfterFallOn(level, root);
		Vec3 bounce = finiteVecOr(root.getDeltaMovement(), Vec3.ZERO);
		if (bounce.y <= 0.0D) {
			bounce = new Vec3(
					incomingVelocity.x * 0.80D,
					-incomingVelocity.y * DRONE_SLIME_BOUNCE_MULTIPLIER,
					incomingVelocity.z * 0.80D
			);
		}
		root.setDeltaMovement(bounce);
		root.hurtMarked = true;
		return true;
	}

	private static boolean tickDroneProjectileImpact(Entity root) {
		if (root == null || !root.isAlive() || !(root.level() instanceof ServerLevel level)) {
			return false;
		}
		AABB hitbox = droneBoxAt(root.position()).inflate(0.08D);
		List<Projectile> projectiles = level.getEntitiesOfClass(
				Projectile.class,
				hitbox.inflate(4.0D),
				projectile -> projectile != null
						&& projectile.isAlive()
						&& projectile.getOwner() != root
						&& !isDroneInternalEntity(projectile.getOwner())
						&& projectileIntersectsDroneHitbox(projectile, hitbox)
		);
		if (projectiles.isEmpty()) {
			return false;
		}
		Projectile projectile = projectiles.get(0);
		Entity owner = projectile.getOwner();
		projectile.discard();
		ServerPlayer breaker = owner instanceof ServerPlayer player ? player : null;
		destroyDrone(root, breaker, breaker != null);
		return true;
	}

	private static boolean projectileIntersectsDroneHitbox(Projectile projectile, AABB droneHitbox) {
		if (projectile == null || droneHitbox == null) {
			return false;
		}
		AABB projectileBox = projectile.getBoundingBox();
		AABB expandedDroneHitbox = droneHitbox.inflate(0.08D);
		if (projectileBox != null && projectileBox.inflate(0.03D).intersects(expandedDroneHitbox)) {
			return true;
		}
		Vec3 end = projectile.position();
		Vec3 movement = finiteVecOr(projectile.getDeltaMovement(), Vec3.ZERO);
		Vec3 start = end.subtract(movement);
		double movementLength = movement.length();
		int steps = Math.max(1, net.minecraft.util.Mth.ceil(movementLength / 0.10D));
		for (int step = 0; step <= steps; step++) {
			double progress = (double) step / (double) steps;
			Vec3 sample = start.lerp(end, progress);
			if (expandedDroneHitbox.contains(sample)) {
				return true;
			}
		}
		return false;
	}

	private static boolean tickDroneEnvironmentDamage(Entity root) {
		if (root == null || !root.isAlive() || !(root.level() instanceof ServerLevel level)) {
			return false;
		}
		AABB box = root.getBoundingBox();
		if (box == null) {
			return false;
		}

		double damage = 0.0D;
		boolean inWater = boxIntersectsFluid(level, box, FluidTags.WATER);
		if (boxIntersectsFluid(level, box, FluidTags.LAVA)) {
			damage += DRONE_LAVA_DAMAGE_PER_TICK;
		}
		if (boxIntersectsAnyBlock(level, box, Blocks.FIRE, Blocks.SOUL_FIRE)) {
			damage += DRONE_FIRE_DAMAGE_PER_TICK;
		}
		if (boxIntersectsAnyBlock(level, box, Blocks.MAGMA_BLOCK) || boxTouchesAnyBlockBelow(level, box, Blocks.MAGMA_BLOCK)) {
			damage += DRONE_MAGMA_DAMAGE_PER_TICK;
		}
		if (inWater) {
			double waterSpeed = finiteVecOr(root.getDeltaMovement(), Vec3.ZERO).length();
			damage += DRONE_WATER_STRESS_BASE_DAMAGE_PER_TICK;
			if (waterSpeed > DRONE_WATER_STRESS_MIN_SPEED) {
				damage += (waterSpeed - DRONE_WATER_STRESS_MIN_SPEED) * DRONE_WATER_STRESS_DAMAGE_PER_TICK;
			}
		}

		UUID droneId = root.getUUID();
		double accumulated = DRONE_ENVIRONMENT_DAMAGE.getOrDefault(droneId, 0.0D);
		if (damage > 0.0D) {
			accumulated += damage;
		} else {
			double decay = inWater
					? DRONE_ENVIRONMENT_WATER_COOLING_PER_TICK
					: DRONE_ENVIRONMENT_DAMAGE_DECAY_PER_TICK;
			accumulated = Math.max(0.0D, accumulated - decay);
		}

		if (accumulated >= DRONE_ENVIRONMENT_BREAK_DAMAGE) {
			destroyDrone(root, null, false);
			return true;
		}
		if (accumulated <= 1.0E-6D) {
			DRONE_ENVIRONMENT_DAMAGE.remove(droneId);
		} else {
			DRONE_ENVIRONMENT_DAMAGE.put(droneId, accumulated);
		}
		return false;
	}

	private static boolean isDroneCollisionWaterProtected(Entity root, Vec3 startPos, Vec3 movement) {
		if (root == null || !(root.level() instanceof ServerLevel level)) {
			return false;
		}
		AABB currentBox = root.getBoundingBox();
		if (currentBox != null && boxIntersectsFluid(level, currentBox, FluidTags.WATER)) {
			return true;
		}
		if (startPos != null) {
			AABB startBox = droneBoxAt(startPos);
			if (boxIntersectsFluid(level, startBox, FluidTags.WATER)) {
				return true;
			}
			return pathIntersectsFluid(level, startBox, movement, FluidTags.WATER);
		}
		return false;
	}

	private static boolean pathIntersectsFluid(ServerLevel level, AABB startBox, Vec3 movement, TagKey<Fluid> fluidTag) {
		if (level == null || startBox == null || movement == null || fluidTag == null) {
			return false;
		}
		double movementLength = movement.length();
		if (movementLength <= CONTROLLED_DRONE_BLOCKED_MOVEMENT_EPSILON) {
			return false;
		}
		int steps = Math.max(1, net.minecraft.util.Mth.ceil(movementLength / DRONE_COLLISION_SWEEP_STEP));
		for (int step = 1; step <= steps; step++) {
			double progress = (double) step / (double) steps;
			if (boxIntersectsFluid(level, startBox.move(movement.scale(progress)), fluidTag)) {
				return true;
			}
		}
		return false;
	}

	private static boolean boxIntersectsFluid(ServerLevel level, AABB box, TagKey<Fluid> fluidTag) {
		if (level == null || box == null || fluidTag == null) {
			return false;
		}
		for (BlockPos pos : blockPositionsTouchedBy(box)) {
			FluidState fluidState = level.getFluidState(pos);
			if (fluidState == null || !fluidState.is(fluidTag)) {
				continue;
			}
			double fluidHeight = fluidState.getHeight(level, pos);
			if (fluidHeight <= 0.0D) {
				continue;
			}
			AABB fluidBox = new AABB(
					pos.getX(),
					pos.getY(),
					pos.getZ(),
					pos.getX() + 1.0D,
					pos.getY() + fluidHeight,
					pos.getZ() + 1.0D
			);
			if (fluidBox.intersects(box)) {
				return true;
			}
		}
		return false;
	}

	private static Vec3 averageFluidFlow(ServerLevel level, AABB box, TagKey<Fluid> fluidTag) {
		if (level == null || box == null || fluidTag == null) {
			return Vec3.ZERO;
		}
		Vec3 flow = Vec3.ZERO;
		int samples = 0;
		for (BlockPos pos : blockPositionsTouchedBy(box)) {
			FluidState fluidState = level.getFluidState(pos);
			if (fluidState == null || !fluidState.is(fluidTag)) {
				continue;
			}
			Vec3 sampleFlow = fluidState.getFlow(level, pos);
			if (sampleFlow.lengthSqr() <= 1.0E-8D) {
				continue;
			}
			flow = flow.add(sampleFlow);
			samples++;
		}
		return samples <= 0 ? Vec3.ZERO : flow.scale(1.0D / samples);
	}

	private static boolean boxIntersectsAnyBlock(ServerLevel level, AABB box, Block... blocks) {
		if (level == null || box == null || blocks == null || blocks.length == 0) {
			return false;
		}
		for (BlockPos pos : blockPositionsTouchedBy(box)) {
			BlockState state = level.getBlockState(pos);
			for (Block block : blocks) {
				if (block != null && state.is(block)) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean boxTouchesAnyBlockBelow(ServerLevel level, AABB box, Block... blocks) {
		return findTouchedBlockStateBelow(level, box, blocks) != null;
	}

	private static BlockState findTouchedBlockStateBelow(ServerLevel level, AABB box, Block... blocks) {
		if (level == null || box == null || blocks == null || blocks.length == 0) {
			return null;
		}
		AABB below = new AABB(
				box.minX,
				box.minY - 0.055D,
				box.minZ,
				box.maxX,
				box.minY + 0.025D,
				box.maxZ
		);
		for (BlockPos pos : blockPositionsTouchedBy(below)) {
			BlockState state = level.getBlockState(pos);
			for (Block block : blocks) {
				if (block != null && state.is(block)) {
					return state;
				}
			}
		}
		return null;
	}

	private static BlockState findIntersectingBubbleColumn(ServerLevel level, AABB box) {
		if (level == null || box == null) {
			return null;
		}
		for (BlockPos pos : blockPositionsTouchedBy(box)) {
			BlockState state = level.getBlockState(pos);
			if (state.is(Blocks.BUBBLE_COLUMN)) {
				return state;
			}
		}
		return null;
	}

	private static Iterable<BlockPos> blockPositionsTouchedBy(AABB box) {
		int minX = net.minecraft.util.Mth.floor(box.minX + 1.0E-7D);
		int minY = net.minecraft.util.Mth.floor(box.minY + 1.0E-7D);
		int minZ = net.minecraft.util.Mth.floor(box.minZ + 1.0E-7D);
		int maxX = net.minecraft.util.Mth.floor(box.maxX - 1.0E-7D);
		int maxY = net.minecraft.util.Mth.floor(box.maxY - 1.0E-7D);
		int maxZ = net.minecraft.util.Mth.floor(box.maxZ - 1.0E-7D);
		return BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ);
	}

	private static Vec3 resolveExternalUncontrolledDroneImpulse(Entity root, UncontrolledDroneState state) {
		if (root == null || state == null) {
			return Vec3.ZERO;
		}
		Vec3 currentPos = root.position();
		Vec3 lastPos = state.lastPosition();
		if (lastPos == null) {
			state.setLastPosition(currentPos);
			return Vec3.ZERO;
		}
		Vec3 externalMovement = currentPos.subtract(lastPos);
		double externalMovementSqr = externalMovement.lengthSqr();
		if (externalMovementSqr <= 1.0E-8D) {
			return Vec3.ZERO;
		}
		double maxDistanceSqr = DRONE_EXTERNAL_PUSH_MAX_DISTANCE * DRONE_EXTERNAL_PUSH_MAX_DISTANCE;
		if (externalMovementSqr > maxDistanceSqr) {
			state.setLastPosition(currentPos);
			return Vec3.ZERO;
		}
		return limitExternalDroneImpulse(externalMovement);
	}

	private static boolean applyUncontrolledDroneEntityPushes(Entity root) {
		if (root == null || !shouldDroneRootCollideWithEntities(root) || !(root.level() instanceof ServerLevel level)) {
			return false;
		}
		AABB pushSearchBox = root.getBoundingBox().inflate(
				DRONE_ENTITY_PUSH_SEARCH_XZ,
				DRONE_ENTITY_PUSH_SEARCH_Y,
				DRONE_ENTITY_PUSH_SEARCH_XZ
		);
		boolean pushed = false;
		for (Entity entity : level.getEntities(root, pushSearchBox)) {
			if (entity == null
					|| !entity.isAlive()
					|| entity.isSpectator()
					|| isDroneInternalEntity(entity)
					|| !entity.isPushable()) {
				continue;
			}
			pushed |= pushDroneAwayFromEntity(root, entity);
		}
		return pushed;
	}

	private static boolean pushDroneAwayFromEntity(Entity root, Entity entity) {
		if (root == null || entity == null) {
			return false;
		}
		double pushX = root.getX() - entity.getX();
		double pushZ = root.getZ() - entity.getZ();
		double horizontalDistanceSqr = pushX * pushX + pushZ * pushZ;
		if (horizontalDistanceSqr <= 1.0E-8D) {
			return false;
		}
		double horizontalDistance = Math.sqrt(horizontalDistanceSqr);
		double scale = 0.05D * Math.min(1.0D, 1.0D / horizontalDistance);
		root.push(pushX / horizontalDistance * scale, 0.0D, pushZ / horizontalDistance * scale);
		return true;
	}

	private static void absorbVanillaUncontrolledDroneVelocity(Entity root, UncontrolledDroneState state) {
		if (root == null || state == null) {
			return;
		}
		Vec3 vanillaVelocity = finiteVecOr(root.getDeltaMovement(), Vec3.ZERO);
		Vec3 trackedVelocity = finiteVecOr(state.velocity(), Vec3.ZERO);
		Vec3 extraVelocity = vanillaVelocity.subtract(trackedVelocity);
		if (extraVelocity.lengthSqr() <= 1.0E-8D) {
			return;
		}
		state.setVelocity(trackedVelocity.add(limitExternalDroneImpulse(extraVelocity)));
		root.setDeltaMovement(state.velocity());
	}

	private static Vec3 limitExternalDroneImpulse(Vec3 impulse) {
		Vec3 safeImpulse = finiteVecOr(impulse, Vec3.ZERO).scale(DRONE_EXTERNAL_PUSH_IMPULSE_SCALE);
		return new Vec3(
				net.minecraft.util.Mth.clamp(safeImpulse.x, -DRONE_EXTERNAL_PUSH_MAX_COMPONENT, DRONE_EXTERNAL_PUSH_MAX_COMPONENT),
				net.minecraft.util.Mth.clamp(safeImpulse.y, -DRONE_EXTERNAL_PUSH_MAX_COMPONENT, DRONE_EXTERNAL_PUSH_MAX_COMPONENT),
				net.minecraft.util.Mth.clamp(safeImpulse.z, -DRONE_EXTERNAL_PUSH_MAX_COMPONENT, DRONE_EXTERNAL_PUSH_MAX_COMPONENT)
		);
	}

	private static void tickUncontrolledDrone(Entity root, UncontrolledDroneState state) {
		if (root == null || state == null) {
			return;
		}
		tickDroneTurretAutomation(root);
		long gameTime = root.level() == null ? Long.MIN_VALUE : root.level().getGameTime();
		if (tickDroneProjectileImpact(root) || tickDroneEnvironmentDamage(root)) {
			UNCONTROLLED_DRONES.remove(root.getUUID());
			return;
		}
		boolean pushedByEntity = applyUncontrolledDroneEntityPushes(root);
		absorbVanillaUncontrolledDroneVelocity(root, state);
		Vec3 externalImpulse = pushedByEntity ? Vec3.ZERO : resolveExternalUncontrolledDroneImpulse(root, state);
		if (externalImpulse.lengthSqr() > 1.0E-8D) {
			state.setVelocity(finiteVecOr(state.velocity(), Vec3.ZERO).add(externalImpulse));
			root.setDeltaMovement(state.velocity());
		}
		boolean heldByReleaseGlide = isUncontrolledReleaseGlideActive(root, state);
		boolean heldByScreenStream = isDroneStreamingToScreen(root);
		boolean heldByPoweredScreen = isDroneHeldByPoweredScreen(root);
		boolean holdWithoutGravity = heldByReleaseGlide || heldByScreenStream || heldByPoweredScreen;
		if (!holdWithoutGravity && isUncontrolledDroneSettled(root, state.velocity()) && !hasUncontrolledDroneEnvironmentalMotion(root)) {
			decayUncontrolledDroneSurfaceWear(state, gameTime);
			settleUncontrolledDrone(root, state);
			return;
		}

		Vec3 autoAimTargetPoint = resolveUncontrolledDroneAutoAimTargetPoint(root, state);
		if (state.autoAimTarget() != null
				&& autoAimTargetPoint == null
				&& isDroneAutoAimTargetDefinitelyMissing(root.level().getServer(), state.dimension(), state.autoAimTarget())) {
			state.setAutoAimTarget(null);
			setPersistedDroneAutoAimTarget(root, null);
		}
		boolean autoAimAdjusted = syncUncontrolledDroneAutoAim(root, state, autoAimTargetPoint);
		boolean restoringScreenFlight = holdWithoutGravity && state.hasRestartRecoveryTarget();
		if (restoringScreenFlight && isAtDroneRestartRecoveryTarget(root, state.restartRecoveryTarget())) {
			finishDroneRestartRecovery(root, state);
			restoringScreenFlight = false;
		}
		boolean screenDrive = !restoringScreenFlight && heldByScreenStream && state.hasDriveState();
		Vec3 velocity;
		if (restoringScreenFlight) {
			velocity = droneRestartRecoveryVelocity(root.position(), state.restartRecoveryTarget());
		} else if (screenDrive) {
			velocity = DroneFlightPhysics.step(state.pitch(), state.yaw(), state.forwardDrive(), state.strafeDrive());
		} else if (holdWithoutGravity) {
			// A stream/powered screen is a hover hold, never an instruction to keep
			// applying a stale entity Motion vector forever after a restart.
			velocity = Vec3.ZERO;
		} else {
			velocity = state.velocity() == null ? Vec3.ZERO : state.velocity();
		}
		if (!holdWithoutGravity) {
			velocity = new Vec3(
					velocity.x * UNCONTROLLED_AIR_DRAG,
					velocity.y * UNCONTROLLED_AIR_DRAG - UNCONTROLLED_GRAVITY,
					velocity.z * UNCONTROLLED_AIR_DRAG
			);
		}
		velocity = applyUncontrolledDroneEnvironment(root, velocity);

		Vec3 startPos = root.position();
		root.noPhysics = false;
		root.move(MoverType.SELF, velocity);
		Vec3 actualMovement = root.position().subtract(startPos);
		boolean waterProtectedImpact = isDroneCollisionWaterProtected(root, startPos, velocity);
		boolean slimeBounce = tryApplyUncontrolledDroneSlimeBounce(root, velocity, waterProtectedImpact);
		if (slimeBounce) {
			actualMovement = root.getDeltaMovement();
		}
		if (waterProtectedImpact && !slimeBounce) {
			playDroneSurfaceWearVisuals(
					root,
					velocity,
					actualMovement,
					hasUncontrolledSurfaceWearGroundContact(root),
					gameTime
			);
		}

		if (!state.isRestartLandingProtected()
				&& !waterProtectedImpact
				&& !slimeBounce
				&& shouldDestroyDroneFromCollision(velocity, actualMovement, root.horizontalCollision, root.verticalCollision)) {
			igniteDroneCrashSite(root, velocity);
			destroyDrone(root, null, false);
			UNCONTROLLED_DRONES.remove(root.getUUID());
			return;
		}
		if (!state.isRestartLandingProtected()
				&& !screenDrive
				&& !waterProtectedImpact
				&& !slimeBounce
				&& updateUncontrolledDroneSurfaceWear(state, root, velocity, actualMovement, gameTime)) {
			igniteDroneCrashSite(root, actualMovement);
			destroyDrone(root, null, false);
			UNCONTROLLED_DRONES.remove(root.getUUID());
			return;
		}
		decayUncontrolledDroneSurfaceWear(state, gameTime);

		if (restoringScreenFlight && isAtDroneRestartRecoveryTarget(root, state.restartRecoveryTarget())) {
			finishDroneRestartRecovery(root, state);
			restoringScreenFlight = false;
			actualMovement = Vec3.ZERO;
		}
		Vec3 nextVelocity = (holdWithoutGravity && (!screenDrive || restoringScreenFlight)) ? Vec3.ZERO : actualMovement;
		if (!screenDrive && !holdWithoutGravity && shouldApplyUncontrolledGroundBraking(root)) {
			nextVelocity = applyUncontrolledGroundBraking(actualMovement);
		}
		state.setVelocity(nextVelocity);
		boolean settledAfterBraking = !holdWithoutGravity
				&& isUncontrolledDroneSettled(root, nextVelocity)
				&& !hasUncontrolledDroneEnvironmentalMotion(root);
		if (settledAfterBraking && !autoAimAdjusted && !screenDrive && actualMovement.lengthSqr() > 1.0E-8D) {
			applyUncontrolledRotation(root, state, actualMovement);
		}
		if (settledAfterBraking) {
			settleUncontrolledDrone(root, state);
			return;
		}
		if (autoAimAdjusted) {
			root.hurtMarked = true;
		} else if (screenDrive) {
			root.setYRot(state.yaw());
			root.setXRot(state.pitch());
		} else if (holdWithoutGravity) {
			applyUncontrolledHeldFlightRotation(root, state);
		} else {
			applyUncontrolledRotation(root, state, actualMovement);
		}
		root.setDeltaMovement(nextVelocity);
		root.hurtMarked = true;
		double displayForwardDrive = 0.0D;
		double displayStrafeDrive = 0.0D;
		if (screenDrive) {
			displayForwardDrive = net.minecraft.util.Mth.lerp(
					DRONE_DISPLAY_DRIVE_SMOOTHING,
					state.displayForwardDrive(),
					state.forwardDrive()
			);
			displayStrafeDrive = net.minecraft.util.Mth.lerp(
					DRONE_DISPLAY_DRIVE_SMOOTHING,
					state.displayStrafeDrive(),
					state.strafeDrive()
			);
			state.setDisplayForwardDrive(displayForwardDrive);
			state.setDisplayStrafeDrive(displayStrafeDrive);
		}
		syncDroneDisplay(root, root.getYRot(), root.getXRot(), displayForwardDrive, displayStrafeDrive, holdWithoutGravity);
		syncDroneCameraAnchor(root, actualMovement);
		syncPersistentDroneLocation(root, state.lastPosition());
		state.setLastPosition(root.position());
		persistDroneRestartRecoveryState(root, state);
		NEXT_DRONE_SOUND_TICK.remove(root.getUUID());
	}

	private static void tickDroneTurretAutomation(Entity root) {
		if (root == null || !root.isAlive() || !hasDroneTurretModule(root) || !(root.level() instanceof ServerLevel level)) {
			return;
		}
		if (Math.floorMod(level.getGameTime() + (long) root.getId(), DRONE_TURRET_AUTOMATION_INTERVAL_TICKS) != 0L) {
			return;
		}
		transferDroneTurretAmmoFromNearbyContainers(root, droneTurretInventory(root));
	}

	private static boolean transferDroneTurretAmmoFromNearbyContainers(Entity root, TurretInventory inventory) {
		if (root == null || inventory == null || !(root.level() instanceof ServerLevel level)) {
			return false;
		}
		AABB searchBox = root.getBoundingBox().inflate(0.85D, 1.10D, 0.85D);
		for (BlockPos pos : blockPositionsTouchedBy(searchBox)) {
			BlockState state = level.getBlockState(pos);
			if (!(level.getBlockEntity(pos) instanceof HopperBlockEntity hopper) || !canHopperFeedDrone(root, pos, state)) {
				continue;
			}
			if (transferDroneTurretAmmoFromContainer(hopper, inventory)) {
				return true;
			}
		}
		for (MinecartHopper hopper : level.getEntitiesOfClass(
				MinecartHopper.class,
				searchBox,
				entity -> entity != null && entity.isAlive() && entity.isEnabled()
		)) {
			if (transferDroneTurretAmmoFromContainer(hopper, inventory)) {
				return true;
			}
		}
		return false;
	}

	private static boolean canHopperFeedDrone(Entity root, BlockPos hopperPos, BlockState hopperState) {
		if (root == null || hopperPos == null || hopperState == null || !hopperState.is(Blocks.HOPPER) || !hopperState.hasProperty(HopperBlock.FACING)) {
			return false;
		}
		BlockPos outputPos = hopperPos.relative(hopperState.getValue(HopperBlock.FACING));
		return root.getBoundingBox().inflate(0.35D, 0.35D, 0.35D).intersects(new AABB(outputPos));
	}

	private static boolean transferDroneTurretAmmoFromContainer(Container source, TurretInventory target) {
		if (source == null || target == null) {
			return false;
		}
		for (int slot = 0; slot < source.getContainerSize(); slot++) {
			ItemStack sourceStack = source.getItem(slot);
			if (!isDroneTurretProjectileStack(sourceStack)) {
				continue;
			}
			ItemStack removed = source.removeItem(slot, 1);
			if (removed.isEmpty()) {
				continue;
			}
			ItemStack remaining = insertIntoDroneTurretInventory(target, removed);
			if (remaining.isEmpty()) {
				source.setChanged();
				target.setChanged();
				return true;
			}
			restoreContainerTransferRemainder(source, slot, remaining);
			source.setChanged();
		}
		return false;
	}

	private static void restoreContainerTransferRemainder(Container source, int slot, ItemStack remaining) {
		if (source == null || remaining == null || remaining.isEmpty()) {
			return;
		}
		ItemStack current = source.getItem(slot);
		if (current.isEmpty()) {
			source.setItem(slot, remaining);
			return;
		}
		if (ItemStack.isSameItemSameComponents(current, remaining)) {
			current.grow(remaining.getCount());
			source.setItem(slot, current);
		}
	}

	private static boolean isUncontrolledReleaseGlideActive(Entity root, UncontrolledDroneState state) {
		return root != null
				&& state != null
				&& root.level() != null
				&& root.level().getGameTime() < state.holdWithoutGravityUntilTick();
	}

	private static boolean isDroneStreamingToScreen(Entity root) {
		if (root == null || !root.isAlive()) {
			return false;
		}
		boolean streaming = isDroneScreenStreamActive(root);
		if (streaming) {
			rememberDroneScreenStreamLoadState(root);
		}
		return streaming;
	}

	private static boolean isDroneHeldByPoweredScreen(Entity root) {
		return root != null && root.isAlive() && POWERED_SCREEN_LINKED_DRONES.contains(root.getUUID());
	}

	private static boolean isDroneHeldByScreen(Entity root) {
		return isDroneStreamingToScreen(root) || isDroneHeldByPoweredScreen(root);
	}

	private static boolean isDroneScreenStreamActive(Entity root) {
		if (root == null || !root.isAlive()) {
			return false;
		}
		Entity cameraAnchor = findDroneCameraAnchor(root);
		return cameraAnchor != null && RendererBotCameraSystem.hasHealthyLiveStreamFollowingEntity(cameraAnchor.getUUID());
	}

	private static DroneCameraChunkTarget resolveDroneCameraChunkTarget(Entity root) {
		if (root == null) {
			return new DroneCameraChunkTarget(0.0D, 0.0D, 0.0F);
		}
		Vec3 origin = resolveDroneCameraPosition(root);
		float yaw = resolveDroneCameraYaw(root);
		return new DroneCameraChunkTarget(origin.x, origin.z, yaw);
	}

	private static Vec3 resolveDroneCameraPosition(Entity root) {
		if (root == null) {
			return Vec3.ZERO;
		}
		Entity cameraAnchor = findDroneCameraAnchor(root);
		return cameraAnchor != null ? cameraAnchor.position() : droneCameraOrigin(root);
	}

	private static float resolveDroneCameraYaw(Entity root) {
		if (root == null) {
			return 0.0F;
		}
		Entity cameraAnchor = findDroneCameraAnchor(root);
		return cameraAnchor != null ? cameraAnchor.getYRot() : root.getYRot();
	}

	private static float resolveDroneCameraPitch(Entity root) {
		if (root == null) {
			return 0.0F;
		}
		Entity cameraAnchor = findDroneCameraAnchor(root);
		return cameraAnchor != null ? cameraAnchor.getXRot() : root.getXRot();
	}

	private static ChunkPos chunkPosAt(double x, double z) {
		return new ChunkPos(
				SectionPos.blockToSectionCoord(net.minecraft.util.Mth.floor(x)),
				SectionPos.blockToSectionCoord(net.minecraft.util.Mth.floor(z))
		);
	}

	private static void applyUncontrolledHeldFlightRotation(Entity root, UncontrolledDroneState state) {
		if (root == null || state == null) {
			return;
		}
		// A linked screen holds an unattended drone in place, but it must not
		// level its camera as soon as its operator disconnects.  In particular,
		// the released control session has already persisted the latest look
		// pitch into this state.  Keep that pose for the screen stream; an active
		// auto-aim target is still applied earlier in tickUncontrolledDrone.
		root.setYRot(state.yaw());
		root.setXRot(state.pitch());
	}

	private static Vec3 resolveUncontrolledDroneAutoAimTargetPoint(Entity root, UncontrolledDroneState state) {
		if (root == null
				|| state == null
				|| state.autoAimTarget() == null
				|| !root.isAlive()
				|| !hasDroneAutoAimModule(root)
				|| root.level() == null) {
			return null;
		}
		MinecraftServer server = root.level().getServer();
		return resolveDroneAutoAimTargetPoint(
				root,
				server,
				state.dimension() != null ? state.dimension() : root.level().dimension(),
				state.autoAimTarget()
		);
	}

	private static boolean syncUncontrolledDroneAutoAim(Entity root, UncontrolledDroneState state, Vec3 targetPoint) {
		if (root == null
				|| state == null
				|| targetPoint == null
				|| state.autoAimTarget() == null
				|| !root.isAlive()
				|| !hasDroneAutoAimModule(root)
				|| root.level() == null) {
			return false;
		}
		if (targetPoint == null) {
			return false;
		}
		Vec3 origin = resolveSafeDroneCameraOrigin(root, droneCameraOrigin(root));
		float targetYaw = yawTo(origin, targetPoint);
		float targetPitch = pitchTo(origin, targetPoint);
		DroneAutoAimAngles nextAngles = approachDroneAutoAimAngles(
				state.yaw(),
				state.pitch(),
				targetYaw,
				targetPitch,
				DRONE_AUTO_AIM_UNCONTROLLED_ROTATION_BLEND,
				DRONE_AUTO_AIM_UNCONTROLLED_MAX_YAW_STEP_DEGREES,
				DRONE_AUTO_AIM_UNCONTROLLED_MAX_PITCH_STEP_DEGREES
		);
		state.setYaw(nextAngles.yaw());
		state.setPitch(nextAngles.pitch());
		root.setYRot(nextAngles.yaw());
		root.setXRot(nextAngles.pitch());
		return true;
	}

	private static void applyUncontrolledRotation(Entity root, UncontrolledDroneState state, Vec3 velocity) {
		if (root == null || state == null || velocity == null) {
			return;
		}
		double speedSq = velocity.lengthSqr();
		if (speedSq <= 1.0E-8D) {
			if (root.onGround()) {
				state.setPitch(0.0F);
			}
			root.setYRot(state.yaw());
			root.setXRot(state.pitch());
			return;
		}

		double horizontalSq = velocity.x * velocity.x + velocity.z * velocity.z;
		float nextYaw = state.yaw();
		if (horizontalSq > 1.0E-8D) {
			float targetYaw = (float) Math.toDegrees(Math.atan2(-velocity.x, velocity.z));
			nextYaw = lerpAngleDegrees(state.yaw(), targetYaw, UNCONTROLLED_ROTATION_LERP);
			state.setYaw(nextYaw);
		}
		float targetPitch = (float) Math.toDegrees(Math.atan2(-velocity.y, Math.sqrt(horizontalSq)));
		float nextPitch = (float) net.minecraft.util.Mth.lerp(UNCONTROLLED_ROTATION_LERP, state.pitch(), targetPitch);
		nextPitch = net.minecraft.util.Mth.clamp(nextPitch, -90.0F, 90.0F);
		state.setPitch(nextPitch);

		root.setYRot(nextYaw);
		root.setXRot(nextPitch);
	}

	private static boolean shouldDestroyDroneFromCollision(
			Vec3 intendedMovement,
			Vec3 actualMovement,
			boolean horizontalCollision,
			boolean verticalCollision
	) {
		return computeCrashEquivalentFallBlocks(intendedMovement, actualMovement, horizontalCollision, verticalCollision)
				>= DRONE_CRASH_EQUIVALENT_FALL_BLOCKS;
	}

	private static double computeCrashEquivalentFallBlocks(
			Vec3 intendedMovement,
			Vec3 actualMovement,
			boolean horizontalCollision,
			boolean verticalCollision
	) {
		if (intendedMovement == null || actualMovement == null || (!horizontalCollision && !verticalCollision)) {
			return 0.0D;
		}

		Vec3 blockedMovement = intendedMovement.subtract(actualMovement);
		double horizontalImpactSq = 0.0D;
		if (horizontalCollision) {
			horizontalImpactSq = blockedMovement.x * blockedMovement.x + blockedMovement.z * blockedMovement.z;
		}

		double verticalImpact = verticalCollision ? Math.abs(intendedMovement.y) : 0.0D;
		double crashEnergy = horizontalImpactSq + verticalImpact * verticalImpact;
		if (crashEnergy <= 1.0E-8D) {
			return 0.0D;
		}

		return crashEnergy / (2.0D * DRONE_CRASH_REFERENCE_ACCELERATION);
	}

	private static boolean updateControlledDroneSurfaceWear(
			DroneControlSession session,
			Entity root,
			Vec3 intendedMovement,
			Vec3 actualMovement,
			boolean verifiedGroundContact,
			long gameTime
	) {
		if (session == null || intendedMovement == null || actualMovement == null || !verifiedGroundContact) {
			return false;
		}

		DroneImpactModel.SurfaceWear surfaceWear = DroneImpactModel.computeSurfaceWear(
				intendedMovement,
				actualMovement,
				true
		);
		if (surfaceWear.delta() <= 0.0D) {
			return false;
		}

		session.setLastSurfaceWearContactTick(gameTime);
		session.setSurfaceWear(Math.min(DroneImpactModel.SURFACE_WEAR_BREAK_LEVEL, session.surfaceWear() + surfaceWear.delta()));
		playDroneSurfaceWearEffects(
				root,
				actualMovement,
				surfaceWear.speedFactor(),
				surfaceWear.pressureFactor(),
				surfaceWear.delta(),
				session.surfaceWear(),
				gameTime
		);
		return session.surfaceWear() >= DroneImpactModel.SURFACE_WEAR_BREAK_LEVEL;
	}

	private static boolean updateUncontrolledDroneSurfaceWear(
			UncontrolledDroneState state,
			Entity root,
			Vec3 intendedMovement,
			Vec3 actualMovement,
			long gameTime
	) {
		if (state == null || root == null || intendedMovement == null || actualMovement == null) {
			return false;
		}
		if (!hasUncontrolledSurfaceWearGroundContact(root)) {
			return false;
		}

		DroneImpactModel.SurfaceWear surfaceWear = DroneImpactModel.computeSurfaceWear(
				intendedMovement,
				actualMovement,
				true
		);
		if (surfaceWear.delta() <= 0.0D) {
			return false;
		}

		double wearDelta = Math.min(
				DroneImpactModel.SURFACE_WEAR_MAX_DELTA_PER_TICK,
				surfaceWear.delta() * DRONE_UNCONTROLLED_SURFACE_WEAR_MULTIPLIER
		);
		state.setLastSurfaceWearContactTick(gameTime);
		state.setSurfaceWear(Math.min(DroneImpactModel.SURFACE_WEAR_BREAK_LEVEL, state.surfaceWear() + wearDelta));
		playDroneSurfaceWearEffects(
				root,
				actualMovement,
				surfaceWear.speedFactor(),
				surfaceWear.pressureFactor(),
				wearDelta,
				state.surfaceWear(),
				gameTime
		);
		return state.surfaceWear() >= DroneImpactModel.SURFACE_WEAR_BREAK_LEVEL;
	}

	private static boolean hasUncontrolledSurfaceWearGroundContact(Entity root) {
		return root != null && (root.onGround() || root.verticalCollisionBelow || hasSupportingBlockBelow(root));
	}

	/**
	 * Water cancels collision damage, not the visual feedback of the hull rubbing
	 * along the bottom. Keep that feedback separate from real wear so a submerged
	 * drone cannot be destroyed merely by showing particles.
	 */
	private static void playDroneSurfaceWearVisuals(
			Entity root,
			Vec3 intendedMovement,
			Vec3 actualMovement,
			boolean verifiedGroundContact,
			long gameTime
	) {
		if (root == null || intendedMovement == null || actualMovement == null || !verifiedGroundContact) {
			return;
		}
		DroneImpactModel.SurfaceWear surfaceWear = DroneImpactModel.computeSurfaceWear(
				intendedMovement,
				actualMovement,
				true
		);
		if (surfaceWear.delta() <= 0.0D) {
			return;
		}
		playDroneSurfaceWearEffects(
				root,
				actualMovement,
				surfaceWear.speedFactor(),
				surfaceWear.pressureFactor(),
				surfaceWear.delta(),
				0.0D,
				gameTime
		);
	}

	/**
	 * A drone moving through water needs a continuous wake, not only the one
	 * collision effect emitted when it first enters the fluid. This is visual
	 * only; water continues to prevent actual surface-wear damage.
	 */
	private static void playDroneSubmergedMotionEffects(Entity root, Vec3 movement, long gameTime) {
		if (root == null || movement == null || !(root.level() instanceof ServerLevel level)
				|| !boxIntersectsFluid(level, root.getBoundingBox(), FluidTags.WATER)) {
			return;
		}
		double speed = movement.length();
		if (speed < 0.012D) {
			return;
		}
		double speedFactor = net.minecraft.util.Mth.clamp(
				speed / DroneFlightPhysics.MAX_COMBINED_SPEED,
				0.18D,
				1.0D
		);
		playDroneSurfaceWearEffects(
				root,
				movement,
				speedFactor,
				0.18D,
				0.01D,
				0.0D,
				gameTime
		);
	}

	private static void playDroneSurfaceWearEffects(
			Entity root,
			Vec3 actualMovement,
			double speedFactor,
			double pressureFactor,
			double wearDelta,
			double surfaceWearLevel,
			long gameTime
	) {
		if (root == null || !(root.level() instanceof ServerLevel level) || actualMovement == null || wearDelta <= 1.0E-6D) {
			return;
		}
		double scrapeStrength = net.minecraft.util.Mth.clamp(speedFactor * 0.70D + pressureFactor * 0.30D, 0.0D, 1.0D);
		if (scrapeStrength < 0.08D && gameTime % 4L != 0L) {
			return;
		}
		if (gameTime % DRONE_SURFACE_WEAR_PARTICLE_INTERVAL_TICKS != 0L && scrapeStrength < 0.72D) {
			return;
		}

		Vec3 origin = root.position();
		Vec3 slide = new Vec3(actualMovement.x, 0.0D, actualMovement.z);
		if (slide.lengthSqr() > 1.0E-6D) {
			slide = slide.normalize();
		}
		double particleX = origin.x - slide.x * DRONE_WIDTH * 0.24D;
		double particleY = origin.y + 0.035D;
		double particleZ = origin.z - slide.z * DRONE_WIDTH * 0.24D;
		double wearRatio = net.minecraft.util.Mth.clamp(surfaceWearLevel / DroneImpactModel.SURFACE_WEAR_BREAK_LEVEL, 0.0D, 1.0D);
		double dangerRatio = wearRatio * wearRatio;
		double spraySpeed = 0.012D + scrapeStrength * 0.042D;
		boolean submerged = boxIntersectsFluid(level, root.getBoundingBox(), FluidTags.WATER);
		int particleMultiplier = submerged
				? DRONE_SUBMERGED_SURFACE_WEAR_PARTICLE_MULTIPLIER
				: DRONE_SURFACE_WEAR_PARTICLE_MULTIPLIER;
		int smokeCount = particleMultiplier * (1 + (int) Math.round(scrapeStrength * 2.5D + dangerRatio * 2.0D));
		int flameCount = pressureFactor > 0.12D || wearRatio > 0.30D
				? 1 + (int) Math.floor(scrapeStrength * pressureFactor * 2.0D + dangerRatio * 1.5D)
				: 0;
		flameCount *= particleMultiplier;
		int dustCount = particleMultiplier * (1 + (int) Math.round(scrapeStrength * 3.0D + dangerRatio * 3.0D));
		DustParticleOptions dust = new DustParticleOptions(
				resolveDroneSurfaceWearDustColor(root),
				(0.75F + (float) (scrapeStrength * 0.32D + dangerRatio * 0.18D))
						* (submerged ? 1.12F : 1.0F)
		);

		sendDroneSurfaceWearParticles(
				root,
				submerged ? ParticleTypes.BUBBLE : ParticleTypes.SMOKE,
				particleX,
				particleY + 0.03D,
				particleZ,
				smokeCount,
				0.09D + scrapeStrength * 0.08D,
				0.025D,
				0.09D + scrapeStrength * 0.08D,
				spraySpeed
		);
		if (flameCount > 0) {
			sendDroneSurfaceWearParticles(
					root,
					submerged ? ParticleTypes.BUBBLE : ParticleTypes.FLAME,
					particleX,
					particleY + 0.02D,
					particleZ,
					flameCount,
					0.06D + scrapeStrength * 0.05D,
					0.018D,
					0.06D + scrapeStrength * 0.05D,
					spraySpeed * 0.85D
			);
		}
		sendDroneSurfaceWearParticles(
				root,
				dust,
				particleX,
				particleY + 0.01D,
				particleZ,
				dustCount,
				0.07D + scrapeStrength * 0.07D,
				0.015D,
				0.07D + scrapeStrength * 0.07D,
				0.004D + scrapeStrength * 0.010D
		);
	}

	/** Sends normal local particles and explicitly mirrors them to the FPV operator.
	 * ServerLevel's normal fan-out stops at 32 blocks from the operator's physical
	 * body, while its camera can be hundreds of blocks away at the drone. */
	private static <T extends ParticleOptions> void sendDroneSurfaceWearParticles(
			Entity root,
			T particle,
			double x,
			double y,
			double z,
			int count,
			double xDist,
			double yDist,
			double zDist,
			double maxSpeed
	) {
		if (root == null || particle == null || !(root.level() instanceof ServerLevel level) || count <= 0) {
			return;
		}
		level.sendParticles(particle, x, y, z, count, xDist, yDist, zDist, maxSpeed);

		UUID controllerId = CONTROLLERS_BY_DRONE.get(root.getUUID());
		ServerPlayer controller = controllerId == null || level.getServer() == null
				? null
				: level.getServer().getPlayerList().getPlayer(controllerId);
		if (controller == null || controller.connection == null || controller.level() != level
				|| controller.position().distanceToSqr(x, y, z) <= VANILLA_PARTICLE_SEND_RANGE_SQR) {
			return;
		}

		// Send directly rather than via ServerLevel#sendParticles(player, ...): that
		// overload still imposes vanilla's 512-block cap. The controller's camera and
		// its loaded drone view are the authoritative visibility context here.
		controller.connection.send(new ClientboundLevelParticlesPacket(
				particle,
				true,
				true,
				x,
				y,
				z,
				(float) xDist,
				(float) yDist,
				(float) zDist,
				(float) maxSpeed,
				count
		));
	}

	private static void decayControlledDroneSurfaceWear(DroneControlSession session, long gameTime) {
		if (session == null || session.surfaceWear() <= 0.0D) {
			return;
		}
		if (session.lastSurfaceWearContactTick() == gameTime) {
			return;
		}
		session.setSurfaceWear(Math.max(0.0D, session.surfaceWear() - DRONE_SURFACE_WEAR_DECAY_PER_TICK));
	}

	private static void decayUncontrolledDroneSurfaceWear(UncontrolledDroneState state, long gameTime) {
		if (state == null || state.surfaceWear() <= 0.0D) {
			return;
		}
		if (state.lastSurfaceWearContactTick() == gameTime) {
			return;
		}
		state.setSurfaceWear(Math.max(0.0D, state.surfaceWear() - DRONE_SURFACE_WEAR_DECAY_PER_TICK));
	}

	private static boolean isUncontrolledDroneSettled(Entity root, Vec3 velocity) {
		if (root == null || velocity == null || !root.onGround()) {
			return false;
		}
		if (!hasSupportingBlockBelow(root)) {
			return false;
		}
		double horizontalSpeedSq = velocity.x * velocity.x + velocity.z * velocity.z;
		return horizontalSpeedSq <= UNCONTROLLED_SETTLED_HORIZONTAL_SPEED_SQR
				&& Math.abs(velocity.y) <= UNCONTROLLED_SETTLED_VERTICAL_SPEED;
	}

	private static void settleUncontrolledDrone(Entity root, UncontrolledDroneState state) {
		if (root == null || state == null) {
			return;
		}
		state.setVelocity(Vec3.ZERO);
		state.clearRestartRecovery();
		state.setPitch(0.0F);
		root.setXRot(0.0F);
		root.setDeltaMovement(Vec3.ZERO);
		root.hurtMarked = true;
		syncDroneDisplay(root, root.getYRot(), 0.0F, 0.0D, 0.0D, false);
		syncDroneCameraAnchor(root, Vec3.ZERO);
		syncPersistentDroneLocation(root, state.lastPosition());
		state.setLastPosition(root.position());
		persistDroneRestartRecoveryState(root, state);
		NEXT_DRONE_SOUND_TICK.remove(root.getUUID());
	}

	private static void maybePlayDroneLoopSound(Entity root, double forwardDrive, double strafeDrive, boolean controlled) {
		if (root == null || !root.isAlive() || !(root.level() instanceof ServerLevel level)) {
			return;
		}
		if (!controlled) {
			NEXT_DRONE_SOUND_TICK.remove(root.getUUID());
			return;
		}

		float power = computeDroneSoundPower(root, forwardDrive, strafeDrive, controlled);
		if (power <= 1.0E-3F) {
			NEXT_DRONE_SOUND_TICK.remove(root.getUUID());
			return;
		}

		long now = level.getGameTime();
		long nextAllowedTick = NEXT_DRONE_SOUND_TICK.getOrDefault(root.getUUID(), Long.MIN_VALUE);
		if (now < nextAllowedTick) {
			return;
		}

		NEXT_DRONE_SOUND_TICK.put(root.getUUID(), now + DRONE_LOOP_REPLAY_TICKS);
		float volume = computeDroneSoundVolume(power);
		float pitch = computeDroneSoundPitch(power);
		playDroneLoopSound(level, root.position(), DRONE_LOOP_SOUND, volume, pitch);
		if (isKamikazeDrone(root)) {
			int kamikazePower = resolveDroneKamikazePower(root);
			playDroneLoopSound(
					level,
					root.position(),
					DRONE_KAMIKAZE_LOOP_SOUND,
					computeKamikazeLoopVolume(volume, kamikazePower),
					pitch * DRONE_KAMIKAZE_LOOP_PITCH_SHIFT
			);
		}
	}

	private static float computeKamikazeLoopVolume(float baseVolume, int kamikazePower) {
		float scale = switch (net.minecraft.util.Mth.clamp(kamikazePower, DRONE_KAMIKAZE_MIN_POWER, DRONE_KAMIKAZE_MAX_POWER)) {
			case 1 -> DRONE_KAMIKAZE_LOOP_LEVEL_1_VOLUME_SCALE;
			case 2 -> DRONE_KAMIKAZE_LOOP_LEVEL_2_VOLUME_SCALE;
			default -> DRONE_KAMIKAZE_LOOP_LEVEL_3_VOLUME_SCALE;
		};
		return baseVolume * scale;
	}

	private static float computeDroneSoundPower(Entity root, double forwardDrive, double strafeDrive, boolean controlled) {
		if (root == null) {
			return 0.0F;
		}

		Vec3 velocity = root.getDeltaMovement();
		if (velocity == null) {
			velocity = Vec3.ZERO;
		}

		float forwardPower = (float) net.minecraft.util.Mth.clamp(Math.abs(forwardDrive) / DroneFlightPhysics.MAX_FORWARD_DRIVE, 0.0D, 1.0D);
		float strafePower = (float) net.minecraft.util.Mth.clamp(Math.abs(strafeDrive) / DroneFlightPhysics.MAX_STRAFE_DRIVE, 0.0D, 1.0D);
		float drivePower = Math.max(forwardPower, strafePower);
		float speedPower = (float) net.minecraft.util.Mth.clamp(velocity.length() / DroneFlightPhysics.MAX_COMBINED_SPEED, 0.0D, 1.0D);
		float verticalPower = (float) net.minecraft.util.Mth.clamp(Math.abs(velocity.y) / 0.42D, 0.0D, 1.0D);

		float hoverFloor = root.onGround()
				? (controlled ? 0.18F : 0.0F)
				: (controlled ? 0.46F : 0.28F);
		float responsivePower = Math.max(drivePower, speedPower * 0.78F + verticalPower * 0.22F);
		return net.minecraft.util.Mth.clamp(Math.max(hoverFloor, responsivePower), 0.0F, 1.0F);
	}

	private static float computeDroneSoundPitch(float power) {
		float shiftedPower = 1.0F + (power - DRONE_SOUND_SOURCE_POWER) * 0.58F;
		return net.minecraft.util.Mth.clamp(shiftedPower, DRONE_SOUND_MIN_PITCH, DRONE_SOUND_MAX_PITCH);
	}

	private static float computeDroneSoundVolume(float power) {
		return net.minecraft.util.Mth.clamp(
				DRONE_SOUND_MIN_VOLUME + power * (DRONE_SOUND_MAX_VOLUME - DRONE_SOUND_MIN_VOLUME),
				0.0F,
				DRONE_SOUND_MAX_VOLUME
		);
	}

	private static void playDroneLoopSound(ServerLevel level, Vec3 origin, Holder<SoundEvent> sound, float volume, float pitch) {
		if (level == null || origin == null || volume <= 0.0F) {
			return;
		}
		if (sound == null) {
			return;
		}

		long seed = level.random.nextLong();
		for (ServerPlayer viewer : level.players()) {
			if (viewer.distanceToSqr(origin) > DRONE_SOUND_RADIUS_SQR || !PolymerResourcePackUtils.hasMainPack(viewer)) {
				continue;
			}
			viewer.connection.send(new ClientboundSoundPacket(
					sound,
					SoundSource.PLAYERS,
					origin.x,
					origin.y,
					origin.z,
					volume,
					pitch,
					seed
			));
		}
	}

	private static float lerpAngleDegrees(float current, float target, float delta) {
		float wrapped = net.minecraft.util.Mth.wrapDegrees(target - current);
		return current + wrapped * net.minecraft.util.Mth.clamp(delta, 0.0F, 1.0F);
	}

	private static void queueDeferredDroneLoadDiscard(Entity entity) {
		if (!(entity != null && entity.level() instanceof ServerLevel level)) {
			return;
		}
		PENDING_DRONE_LOAD_DISCARDS.add(new PendingDroneLoadDiscard(level.dimension(), entity.getUUID()));
	}

	private static void flushPendingDroneLoadDiscards(MinecraftServer server) {
		if (server == null || PENDING_DRONE_LOAD_DISCARDS.isEmpty()) {
			return;
		}
		for (PendingDroneLoadDiscard pending : new ArrayList<>(PENDING_DRONE_LOAD_DISCARDS)) {
			PENDING_DRONE_LOAD_DISCARDS.remove(pending);
			if (pending == null || pending.dimension() == null || pending.entityUuid() == null) {
				continue;
			}
			ServerLevel level = server.getLevel(pending.dimension());
			Entity entity = level == null ? null : level.getEntity(pending.entityUuid());
			if (entity != null && entity.isAlive()) {
				entity.discard();
			}
		}
	}

	private static void onEntityLoad(Entity entity, ServerLevel level) {
		if (entity == null) {
			return;
		}
		if (entity.getTags().contains(DRONE_NIGHT_VISION_CAMERA_TAG)) {
			queueDeferredDroneLoadDiscard(entity);
			return;
		}
		if (entity.getTags().contains(DRONE_DISPLAY_TAG)) {
			Entity root = resolveDroneRoot(entity);
			if (root == null || !root.isAlive()) {
				queueDeferredDroneLoadDiscard(entity);
			} else if (entity instanceof Display.ItemDisplay display) {
				rememberDroneDisplayLayer(root, display);
				collapseDroneDisplayHitbox(display);
			}
			return;
		}
		if (entity.getTags().contains(DRONE_CAMERA_TAG)) {
			UUID ownerId = resolveTaggedUuid(entity, DRONE_CAMERA_OWNER_TAG_PREFIX);
			Entity owner = ownerId == null || level == null ? null : level.getEntity(ownerId);
			if (owner == null || !owner.isAlive() || !owner.getTags().contains(DRONE_ROOT_TAG)) {
				queueDeferredDroneLoadDiscard(entity);
			}
			return;
		}
		if (entity.getTags().contains(DRONE_TURRET_TRIGGER_TAG)) {
			UUID ownerId = resolveTaggedUuid(entity, DRONE_TURRET_TRIGGER_OWNER_TAG_PREFIX);
			ServerPlayer owner = ownerId == null || level == null || level.getServer() == null
					? null
					: level.getServer().getPlayerList().getPlayer(ownerId);
			if (owner == null || !isControllingDrone(owner)) {
				queueDeferredDroneLoadDiscard(entity);
			}
			return;
		}
		if (!(entity instanceof Interaction root) || level == null || !root.getTags().contains(DRONE_ROOT_TAG)) {
			return;
		}
		// Do not reuse the entity Motion value here. A controlled/hovering drone
		// can have a positive Motion value saved by vanilla; once a screen holds it
		// without gravity that value would otherwise propel it upward forever.
		if (isDroneActivelyControlled(root)) {
			return;
		}
		DroneRestartRecoveryState restartRecovery = readDroneRestartRecoveryState(root);
		UncontrolledDroneState uncontrolledState = new UncontrolledDroneState(
				root.getUUID(),
				level.dimension(),
				Vec3.ZERO,
				root.getYRot(),
				root.getXRot()
		);
		root.setDeltaMovement(Vec3.ZERO);
		if (restartRecovery != null && restartRecovery.airborne()) {
			uncontrolledState.beginRestartRecovery(restartRecovery.target());
		} else if (!isDroneSafelyParked(root, uncontrolledState)) {
			// Older entities have no recovery tag yet. They still get one safe
			// post-restart landing instead of breaking on the first ground impact.
			uncontrolledState.beginRestartLandingProtection();
		}
		uncontrolledState.setAutoAimTarget(resolvePersistedDroneAutoAimTarget(root));
		uncontrolledState.setLastPosition(root.position());
		UNCONTROLLED_DRONES.putIfAbsent(root.getUUID(), uncontrolledState);
		rememberLastKnownDroneFeedState(root);
		syncDroneDisplayLayers(root);
		syncDroneDisplay(root, root.getYRot(), root.getXRot(), 0.0D, 0.0D, false);
		syncDroneCameraAnchor(root, root.getDeltaMovement());
	}

	private static UUID resolveTaggedUuid(Entity entity, String prefix) {
		if (entity == null || prefix == null || prefix.isBlank()) {
			return null;
		}
		for (String tag : entity.getTags()) {
			if (tag == null || !tag.startsWith(prefix)) {
				continue;
			}
			try {
				return UUID.fromString(tag.substring(prefix.length()));
			} catch (IllegalArgumentException ignored) {
			}
		}
		return null;
	}

	private static void onEntityUnload(Entity entity, ServerLevel level) {
		if (!(entity instanceof Interaction root) || level == null || !root.getTags().contains(DRONE_ROOT_TAG)) {
			return;
		}
		UNCONTROLLED_DRONES.remove(root.getUUID());
		NEXT_DRONE_SOUND_TICK.remove(root.getUUID());
		NEXT_DRONE_ARM_ALLOWED_TICK.remove(root.getUUID());
		DISPLAY_WOBBLE_BY_DRONE.remove(root.getUUID());
		AUTO_AIM_DISPLAY_ANIMATIONS.remove(root.getUUID());
		DRONE_ENVIRONMENT_DAMAGE.remove(root.getUUID());
	}

	private static void detachAnyDronePassengersFromController(ServerPlayer player) {
		if (player == null) {
			return;
		}
		boolean changed = false;
		for (Entity passenger : new ArrayList<>(player.getPassengers())) {
			if (resolveDroneRoot(passenger) == null) {
				continue;
			}
			if (passenger.getVehicle() == player || passenger.isPassenger()) {
				passenger.stopRiding();
				changed = true;
			}
		}
		if (changed) {
			syncPassengerAttachment(player);
		}
	}

	private static Entity resolveControlledDroneRoot(ServerPlayer player) {
		if (player == null) {
			return null;
		}
		DroneControlSession session = ACTIVE_SESSIONS.get(player.getUUID());
		if (session == null || player.level() == null || player.level().getServer() == null) {
			return null;
		}
		return findDroneRoot(player.level().getServer(), session.droneDimension(), session.droneUuid());
	}

	private static int resolveRequestedViewDistance(ServerPlayer player) {
		MinecraftServer server = player != null && player.level() != null ? player.level().getServer() : null;
		return net.minecraft.util.Mth.clamp(
				player != null ? player.requestedViewDistance() : 2,
				2,
				Math.max(2, server != null ? server.getPlayerList().getViewDistance() : 2)
		);
	}

	private static int resolveServerViewDistance(MinecraftServer server) {
		return net.minecraft.util.Mth.clamp(
				server != null && server.getPlayerList() != null ? server.getPlayerList().getViewDistance() : 2,
				2,
				32
		);
	}

	private static int resolveServerSimulationTicketRadius(MinecraftServer server) {
		int simulationDistance = net.minecraft.util.Mth.clamp(
				server != null && server.getPlayerList() != null ? server.getPlayerList().getSimulationDistance() : 0,
				0,
				32
		);
		return Math.min(33, simulationDistance + 2);
	}

	private static boolean isDroneActivelyControlled(Entity root) {
		if (root == null) {
			return false;
		}
		return resolveAuthoritativeDroneControllerId(root.getUUID()) != null;
	}

	private static boolean isWithinHorizontalRange(Vec3 origin, Entity entity, double horizontalRange) {
		if (origin == null || entity == null || horizontalRange <= 0.0D) {
			return false;
		}
		double dx = origin.x - entity.getX();
		double dz = origin.z - entity.getZ();
		double horizontalRangeSq = horizontalRange * horizontalRange;
		return dx * dx + dz * dz <= horizontalRangeSq;
	}

	private static boolean isControlledHotbarSlot(int slot) {
		return (slot >= PLAYER_HOTBAR_MENU_SLOT_START && slot < PLAYER_HOTBAR_MENU_SLOT_START + 9)
				|| slot == PLAYER_OFFHAND_MENU_SLOT;
	}

	private static Packet<?> buildControlledSelfMetadataPacket(ServerPlayer player) {
		EntityDataAccessor<Byte> sharedFlagsAccessor = EntityTrackedDataAccessor.lg2$getDataSharedFlagsId();
		EntityDataAccessor<Boolean> noGravityAccessor = EntityTrackedDataAccessor.lg2$getDataNoGravity();
		EntityDataAccessor<Pose> poseAccessor = EntityTrackedDataAccessor.lg2$getDataPose();
		byte flags = 0;
		if (player != null) {
			Byte currentFlags = player.getEntityData().get(sharedFlagsAccessor);
			flags = currentFlags == null ? 0 : currentFlags;
		}
		flags &= (byte) ~(ENTITY_FLAG_ON_FIRE | ENTITY_FLAG_SHIFTING | ENTITY_FLAG_SPRINTING | ENTITY_FLAG_SWIMMING);
		flags |= (byte) (ENTITY_FLAG_INVISIBLE | ENTITY_FLAG_FALL_FLYING);
		return new ClientboundSetEntityDataPacket(
				player.getId(),
				List.of(
						SynchedEntityData.DataValue.create(sharedFlagsAccessor, flags),
						SynchedEntityData.DataValue.create(noGravityAccessor, true),
						SynchedEntityData.DataValue.create(poseAccessor, Pose.FALL_FLYING)
				)
		);
	}

	private static Packet<?> buildActualSelfMetadataPacket(ServerPlayer player) {
		EntityDataAccessor<Byte> sharedFlagsAccessor = EntityTrackedDataAccessor.lg2$getDataSharedFlagsId();
		EntityDataAccessor<Boolean> noGravityAccessor = EntityTrackedDataAccessor.lg2$getDataNoGravity();
		EntityDataAccessor<Pose> poseAccessor = EntityTrackedDataAccessor.lg2$getDataPose();
		byte flags = 0;
		boolean noGravity = false;
		Pose pose = Pose.STANDING;
		if (player != null) {
			Byte currentFlags = player.getEntityData().get(sharedFlagsAccessor);
			flags = currentFlags == null ? 0 : currentFlags;
			Boolean currentNoGravity = player.getEntityData().get(noGravityAccessor);
			noGravity = Boolean.TRUE.equals(currentNoGravity);
			Pose currentPose = player.getEntityData().get(poseAccessor);
			pose = currentPose == null ? player.getPose() : currentPose;
		}
		return new ClientboundSetEntityDataPacket(
				player.getId(),
				List.of(
						SynchedEntityData.DataValue.create(sharedFlagsAccessor, flags),
						SynchedEntityData.DataValue.create(noGravityAccessor, noGravity),
						SynchedEntityData.DataValue.create(poseAccessor, pose)
				)
		);
	}

	private static byte toPackedRotation(float degrees) {
		return (byte) net.minecraft.util.Mth.floor((degrees * 256.0F) / 360.0F);
	}

	private static Packet<?> buildControlledSelfTeleportPacket(ServerPlayer player, DroneControlSession session) {
		PositionMoveRotation change = new PositionMoveRotation(
				session.proxyPos(),
				controlledOperatorVisualVelocity(session),
				session.proxyYaw(),
				session.proxyPitch()
		);
		return ClientboundTeleportEntityPacket.teleport(player.getId(), change, ABSOLUTE_TELEPORT, false);
	}

	private static ClientboundPlayerPositionPacket buildControlledPlayerPositionPacket(DroneControlSession session) {
		return buildControlledPlayerPositionPacket(session, session.nextViewSyncTeleportId());
	}

	private static ClientboundPlayerPositionPacket buildControlledPlayerPositionPacket(DroneControlSession session, int teleportId) {
		PositionMoveRotation change = new PositionMoveRotation(
				session.proxyPos(),
				controlledOperatorVisualVelocity(session),
				session.proxyYaw(),
				session.proxyPitch()
		);
		return ClientboundPlayerPositionPacket.of(teleportId, change, ABSOLUTE_TELEPORT);
	}

	private static Vec3 controlledOperatorVisualVelocity(DroneControlSession session) {
		Vec3 velocity = session == null ? null : session.velocity();
		return velocity == null ? Vec3.ZERO : velocity;
	}

	private static Vec3 controlledOperatorDriveVelocity(DroneControlSession session) {
		Vec3 velocity = session == null ? null : session.intendedVelocity();
		return velocity == null ? Vec3.ZERO : velocity;
	}

	private static ClientboundSetPassengersPacket buildControlledOperatorPassengerPacket(ServerPlayer player, DroneControlSession session) {
		Entity root = (player != null && player.level() != null && player.level().getServer() != null && session != null)
				? findDroneRoot(player.level().getServer(), session.droneDimension(), session.droneUuid())
				: null;
		int[] passengerIds = root != null && root.isAlive() ? new int[]{root.getId()} : new int[0];
		return buildPassengerPacket(player, player == null ? 0 : player.getId(), passengerIds);
	}

	private static ClientboundSetPassengersPacket buildControlledOperatorDroneLayerPassengerPacket(Entity root) {
		if (!root.isAlive()) {
			return buildPassengerPacket(root, root.getId(), new int[0]);
		}

		List<Display.ItemDisplay> displays = findRegisteredDroneDisplayLayers(root);
		int[] passengerIds = new int[displays.size()];
		int count = 0;
		for (Display.ItemDisplay display : displays) {
			if (display == null || !display.isAlive()) {
				continue;
			}
			passengerIds[count++] = display.getId();
		}
		if (count != passengerIds.length) {
			int[] compact = new int[count];
			System.arraycopy(passengerIds, 0, compact, 0, count);
			passengerIds = compact;
		}
		return buildPassengerPacket(root, root.getId(), passengerIds);
	}

	private static void syncControlledOperatorDroneLayerAttachment(ServerPlayer player, Entity root) {
		if (player == null || root == null || player.connection == null || !root.isAlive()) {
			return;
		}
		sendControlledOperatorPacket(player, buildControlledOperatorDroneLayerPassengerPacket(root));
		syncControlledOperatorPassengerDisplayOffsets(player, root);
	}

	private static void clearControlledOperatorDroneLayerAttachment(ServerPlayer player, Entity root) {
		if (player == null || root == null || player.connection == null || !root.isAlive()) {
			return;
		}
		syncControlledOperatorBaseDisplayOffsets(player, root);
		sendControlledOperatorPacket(player, buildPassengerPacket(root, root.getId(), new int[0]));
	}

	private static void clearControlledOperatorPassengerAttachment(ServerPlayer player) {
		if (player == null || player.connection == null) {
			return;
		}
		sendControlledOperatorPacket(player, buildPassengerPacket(player, player.getId(), new int[0]));
	}

	private static ClientboundSetPassengersPacket buildPassengerPacket(Entity seedEntity, int vehicleId, int[] passengerIds) {
		ClientboundSetPassengersPacket packet = new ClientboundSetPassengersPacket(seedEntity);
		ClientboundSetPassengersPacketAccessor accessor = (ClientboundSetPassengersPacketAccessor) (Object) packet;
		accessor.lg2$setVehicle(vehicleId);
		accessor.lg2$setPassengers(passengerIds == null ? new int[0] : passengerIds);
		return packet;
	}

	private static void syncControlledOperatorPassengerDisplayOffsets(ServerPlayer player, Entity root) {
		if (player == null || root == null || player.connection == null || !root.isAlive()) {
			return;
		}
		for (Display.ItemDisplay display : findDroneDisplayLayers(root)) {
			Packet<?> packet = buildControlledOperatorPassengerDisplayOffsetPacket(player, root, display, true);
			if (packet != null) {
				sendControlledOperatorPacket(player, packet);
			}
		}
	}

	private static void syncControlledOperatorBaseDisplayOffsets(ServerPlayer player, Entity root) {
		if (player == null || root == null || player.connection == null || !root.isAlive()) {
			return;
		}
		for (Display.ItemDisplay display : findDroneDisplayLayers(root)) {
			Packet<?> packet = buildControlledOperatorPassengerDisplayOffsetPacket(player, root, display, false);
			if (packet != null) {
				sendControlledOperatorPacket(player, packet);
			}
		}
	}

	private static Packet<?> buildControlledOperatorPassengerDisplayOffsetPacket(
			ServerPlayer player,
			Entity root,
			Display.ItemDisplay display,
			boolean applyPassengerOffset
	) {
		if (player == null || root == null || display == null || !display.isAlive()) {
			return null;
		}
		EntityDataAccessor<Vector3fc> translationAccessor = DisplayTrackedDataAccessor.lg2$getDataTranslationId();
		Vector3fc translation = buildControlledOperatorPassengerDisplayTranslation(
				player,
				root,
				display.getEntityData().get(translationAccessor),
				applyPassengerOffset
		);
		return new ClientboundSetEntityDataPacket(
				display.getId(),
				List.of(SynchedEntityData.DataValue.create(translationAccessor, translation))
		);
	}

	private static Packet<?> rewriteControlledOperatorPassengerDisplayMetadata(ServerPlayer player, ClientboundSetEntityDataPacket packet) {
		Entity root = resolveControlledDroneRoot(player);
		Display.ItemDisplay display = resolveControlledOperatorPassengerDisplay(player, packet == null ? Integer.MIN_VALUE : packet.id());
		if (display == null || packet == null || root == null || !root.isAlive()) {
			return packet;
		}
		EntityDataAccessor<Vector3fc> translationAccessor = DisplayTrackedDataAccessor.lg2$getDataTranslationId();
		List<SynchedEntityData.DataValue<?>> packedItems = packet.packedItems();
		if (packedItems == null || packedItems.isEmpty()) {
			return packet;
		}
		List<SynchedEntityData.DataValue<?>> rewritten = new ArrayList<>(packedItems.size());
		boolean changed = false;
		for (SynchedEntityData.DataValue<?> value : packedItems) {
			if (value != null && value.id() == translationAccessor.id()) {
				rewritten.add(SynchedEntityData.DataValue.create(
						translationAccessor,
						buildControlledOperatorPassengerDisplayTranslation(
								player,
								root,
								display.getEntityData().get(translationAccessor),
								true
						)
				));
				changed = true;
				continue;
			}
			rewritten.add(value);
		}
		return changed ? new ClientboundSetEntityDataPacket(packet.id(), rewritten) : packet;
	}

	private static Vector3fc buildControlledOperatorPassengerDisplayTranslation(
			ServerPlayer player,
			Entity root,
			Vector3fc baseTranslation,
			boolean applyPassengerOffset
	) {
		float baseX = baseTranslation == null ? 0.0F : baseTranslation.x();
		float baseY = baseTranslation == null ? 0.0F : baseTranslation.y();
		float baseZ = baseTranslation == null ? 0.0F : baseTranslation.z();
		if (!applyPassengerOffset || player == null || root == null) {
			return new Vector3f(baseX, baseY, baseZ);
		}
		Vec3 passengerOffset = resolveControlledOperatorPassengerDisplayAttachmentOffset(player, root);
		float offsetX = (float) -passengerOffset.x;
		float offsetY = (float) (resolveDroneDisplayYOffset(resolveDroneType(root)) - passengerOffset.y)
				+ CONTROLLED_OPERATOR_PASSENGER_DISPLAY_Y_OFFSET;
		float offsetZ = (float) -passengerOffset.z + CONTROLLED_OPERATOR_PASSENGER_DISPLAY_Z_OFFSET;
		return new Vector3f(
				baseX + offsetX,
				baseY + offsetY,
				baseZ + offsetZ
		);
	}

	private static Vec3 resolveControlledOperatorPassengerDisplayAttachmentOffset(ServerPlayer player, Entity root) {
		return resolvePassengerAttachmentAverage(player).add(resolvePassengerAttachmentAverage(root));
	}

	private static Vec3 resolvePassengerAttachmentAverage(Entity entity) {
		if (entity == null) {
			return Vec3.ZERO;
		}
		EntityDimensions dimensions = entity.getDimensions(entity.getPose());
		return dimensions == null ? Vec3.ZERO : dimensions.attachments().getAverage(EntityAttachment.PASSENGER);
	}

	private static boolean isControlledOperatorPassengerVisualEntity(ServerPlayer player, int entityId) {
		if (player == null || entityId == 0) {
			return false;
		}
		Entity root = resolveControlledDroneRoot(player);
		if (root == null || !root.isAlive()) {
			return false;
		}
		if (root.getId() == entityId) {
			return true;
		}
		return resolveControlledOperatorPassengerDisplay(player, entityId) != null;
	}

	private static Display.ItemDisplay resolveControlledOperatorPassengerDisplay(ServerPlayer player, int entityId) {
		if (player == null) {
			return null;
		}
		Entity root = resolveControlledDroneRoot(player);
		if (root == null || !root.isAlive()) {
			return null;
		}
		return resolveRegisteredDroneDisplayLayer(root, entityId);
	}

	private static void sendControlledOperatorPacket(ServerPlayer player, Packet<?> packet) {
		if (player == null || player.connection == null || packet == null) {
			return;
		}
		runWithControlledOperatorPacketRewriteBypass(() -> player.connection.send(packet));
	}

	private static void rebuildReleasedDroneVisualEntitiesForOperator(ServerPlayer player, Entity root) {
		if (player == null || player.connection == null || root == null || !root.isAlive()) {
			return;
		}
		List<Entity> visualEntities = new ArrayList<>();
		visualEntities.add(root);
		for (Display.ItemDisplay display : findDroneDisplayLayers(root)) {
			if (display != null && display.isAlive()) {
				visualEntities.add(display);
			}
		}
		int[] entityIds = new int[visualEntities.size()];
		for (int i = 0; i < visualEntities.size(); i++) {
			entityIds[i] = visualEntities.get(i).getId();
		}
		sendControlledOperatorPacket(player, new ClientboundRemoveEntitiesPacket(entityIds));
		for (Entity entity : visualEntities) {
			sendSingleViewerEntityPairingData(player, entity);
			sendControlledOperatorPacket(player, ClientboundEntityPositionSyncPacket.of(entity));
			if (entity == root) {
				sendControlledOperatorPacket(player, new ClientboundSetEntityMotionPacket(root.getId(), root.getDeltaMovement()));
				sendControlledOperatorPacket(player, buildPassengerPacket(root, root.getId(), new int[0]));
			}
		}
	}

	@SuppressWarnings("unchecked")
	private static void sendSingleViewerEntityPairingData(ServerPlayer player, Entity entity) {
		if (player == null || player.connection == null || entity == null || !entity.isAlive() || !(entity.level() instanceof ServerLevel level)) {
			return;
		}
		ServerEntity tracker = new ServerEntity(
				level,
				entity,
				1,
				false,
				DRONE_RELEASE_VISUAL_RESYNC_NOOP_SYNCHRONIZER
		);
		tracker.sendPairingData(player, packet ->
				sendControlledOperatorPacket(player, (Packet<? super ClientGamePacketListener>) packet)
		);
		List<SynchedEntityData.DataValue<?>> values = entity.getEntityData().getNonDefaultValues();
		if (values != null && !values.isEmpty()) {
			sendControlledOperatorPacket(player, new ClientboundSetEntityDataPacket(entity.getId(), values));
		}
	}

	private static void refreshControlledOperatorActualView(ServerPlayer player) {
		refreshControlledOperatorActualView(player, true);
	}

	private static void refreshControlledOperatorActualView(ServerPlayer player, boolean includeTeleport) {
		if (player == null || player.connection == null || !(player.level() instanceof ServerLevel level)) {
			return;
		}
		if (includeTeleport) {
			level.getChunkAt(BlockPos.containing(player.position()));
			player.connection.teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
		}
		player.connection.send(buildActualSelfMetadataPacket(player));
		player.connection.send(new ClientboundSetEntityMotionPacket(player.getId(), player.getDeltaMovement()));
		player.connection.send(new ClientboundSetPassengersPacket(player));
		player.connection.send(new ClientboundPlayerAbilitiesPacket(player.getAbilities()));
	}

	private static void restoreControlledOperatorClientState(ServerPlayer player) {
		restoreControlledOperatorClientState(player, true);
	}

	private static void restoreControlledOperatorClientState(ServerPlayer player, boolean includeViewTeleport) {
		if (player == null) {
			return;
		}
		setHotbarVisualHidden(player, false);
		refreshControlledOperatorActualView(player, includeViewTeleport);
		ServerMechanicsGateSystem.syncPlayerInventory(player);
	}

	private static void clearControlledOperatorMovementState(ServerPlayer player) {
		if (player == null) {
			return;
		}
		player.setCamera(player);
		player.stopFallFlying();
		player.setDeltaMovement(Vec3.ZERO);
		player.fallDistance = 0.0F;
		player.hurtMarked = true;
	}

	private static void clearControlledOperatorTransientState(ServerPlayer player, DroneControlSession session) {
		clearManagedDroneNightVision(player);
		clearControlledAutoAimTarget(player, session);
		clearControlledOperatorInteractionRange(player);
		removeControlledDroneTurretAirTrigger(player);
	}

	private static void clearControlledOperatorInteractionRange(ServerPlayer player) {
		if (player == null) {
			return;
		}
		boolean changed = false;
		changed |= syncControlledOperatorAttributeModifier(
				player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE),
				DRONE_AUTO_AIM_BLOCK_INTERACTION_RANGE_MODIFIER_ID,
				0.0D
		);
		changed |= syncControlledOperatorAttributeModifier(
				player.getAttribute(Attributes.ENTITY_INTERACTION_RANGE),
				DRONE_AUTO_AIM_ENTITY_INTERACTION_RANGE_MODIFIER_ID,
				0.0D
		);
		if (changed) {
			syncControlledOperatorInteractionRangeClient(player);
		}
	}

	private static void stopControlledOperatorAudio(UUID playerId) {
		if (playerId == null) {
			return;
		}
		ControlledOperatorAudioRuntime runtime = CONTROLLED_OPERATOR_AUDIO.remove(playerId);
		if (runtime != null) {
			runtime.close();
		}
	}

	private static void shutdownAllControlledOperatorAudio() {
		for (ControlledOperatorAudioRuntime runtime : CONTROLLED_OPERATOR_AUDIO.values()) {
			if (runtime != null) {
				runtime.close();
			}
		}
		CONTROLLED_OPERATOR_AUDIO.clear();
	}

	private static void syncControlledOperatorView(
			ServerPlayer player,
			DroneControlSession session,
			Entity root,
			boolean initialSync,
			boolean forcePositionSync
	) {
		if (player == null || session == null || root == null || player.connection == null) {
			return;
		}
		if (initialSync || forcePositionSync) {
			sendControlledOperatorPacket(player, buildControlledPlayerPositionPacket(session));
		}
		sendControlledOperatorPacket(player, new ClientboundSetEntityMotionPacket(player.getId(), controlledOperatorDriveVelocity(session)));
		sendControlledOperatorPacket(player, buildControlledSelfMetadataPacket(player));
		sendControlledOperatorPacket(player, buildControlledOperatorPassengerPacket(player, session));
		syncControlledOperatorDroneLayerAttachment(player, root);
		syncControlledOperatorPassengerRotation(player, root);
	}

	private static void syncControlledOperatorFallbackView(ServerPlayer player, DroneControlSession session) {
		if (player == null || session == null || player.connection == null) {
			return;
		}
		sendControlledOperatorPacket(player, new ClientboundSetEntityMotionPacket(player.getId(), controlledOperatorDriveVelocity(session)));
		sendControlledOperatorPacket(player, buildControlledSelfMetadataPacket(player));
		clearControlledOperatorPassengerAttachment(player);
	}

	private static void syncControlledOperatorPassengerRotation(ServerPlayer player, Entity root) {
		if (player == null || root == null || player.connection == null || !root.isAlive()) {
			return;
		}
		sendControlledOperatorPassengerRotationPacket(player, root);
		for (Display.ItemDisplay display : findDroneDisplayLayers(root)) {
			if (display == null || !display.isAlive()) {
				continue;
			}
			sendControlledOperatorPassengerRotationPacket(player, display);
		}
	}

	private static void sendControlledOperatorPassengerRotationPacket(ServerPlayer player, Entity entity) {
		if (player == null || entity == null || player.connection == null || !entity.isAlive()) {
			return;
		}
		sendControlledOperatorPacket(player, new ClientboundMoveEntityPacket.Rot(
				entity.getId(),
				toPackedRotation(entity.getYRot()),
				toPackedRotation(entity.getXRot()),
				entity.onGround()
		));
	}

	private static void syncControlledOperatorBodyMirror(ServerPlayer player, boolean forceSpawn) {
		if (player == null || player.connection == null || !isControllingDrone(player)) {
			return;
		}
		OperatorBodyMirror mirror = OPERATOR_BODY_MIRRORS.get(player.getUUID());
		if (mirror == null) {
			mirror = createOperatorBodyMirror(player);
			OPERATOR_BODY_MIRRORS.put(player.getUUID(), mirror);
			forceSpawn = true;
		}
		if (forceSpawn || !mirror.spawned()) {
			sendControlledOperatorBodyMirrorSpawn(player, mirror);
			mirror.setSpawned(true);
		}
		sendControlledOperatorBodyMirrorState(player, mirror);
	}

	private static OperatorBodyMirror createOperatorBodyMirror(ServerPlayer player) {
		UUID profileId = UUID.nameUUIDFromBytes(
				("lg2:drone_operator_body:" + player.getUUID()).getBytes(StandardCharsets.UTF_8)
		);
		GameProfile sourceProfile = player.getGameProfile();
		PropertyMap properties = sourceProfile != null
				? new PropertyMap(ImmutableMultimap.copyOf(sourceProfile.properties()))
				: new PropertyMap(ImmutableMultimap.of());
		String name = sourceProfile == null || sourceProfile.name() == null || sourceProfile.name().isBlank()
				? "operator"
				: sourceProfile.name();
		return new OperatorBodyMirror(allocateOperatorBodyMirrorEntityId(), profileId, new GameProfile(profileId, name, properties));
	}

	private static int allocateOperatorBodyMirrorEntityId() {
		return nextOperatorBodyMirrorEntityId--;
	}

	private static void sendControlledOperatorBodyMirrorSpawn(ServerPlayer player, OperatorBodyMirror mirror) {
		if (player == null || mirror == null) {
			return;
		}
		sendControlledOperatorPacket(player, buildOperatorBodyMirrorPlayerInfoPacket(player, mirror));
		sendControlledOperatorPacket(player, new ClientboundAddEntityPacket(
				mirror.entityId(),
				mirror.profileId(),
				player.getX(),
				player.getY(),
				player.getZ(),
				player.getXRot(),
				player.getYRot(),
				EntityType.PLAYER,
				0,
				player.getDeltaMovement(),
				player.getYHeadRot()
		));
	}

	private static ClientboundPlayerInfoUpdatePacket buildOperatorBodyMirrorPlayerInfoPacket(ServerPlayer player, OperatorBodyMirror mirror) {
		EnumSet<ClientboundPlayerInfoUpdatePacket.Action> actions = EnumSet.of(
				ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
				ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
				ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
				ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY,
				ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME,
				ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LIST_ORDER,
				ClientboundPlayerInfoUpdatePacket.Action.UPDATE_HAT
		);
		ClientboundPlayerInfoUpdatePacket packet = PolymerEntityUtils.createMutablePlayerListPacket(actions);
		GameType gameMode = player == null || player.gameMode == null ? GameType.SURVIVAL : player.gameMode.getGameModeForPlayer();
		ClientboundPlayerInfoUpdatePacket.Entry entry = new ClientboundPlayerInfoUpdatePacket.Entry(
				mirror.profileId(),
				mirror.profile(),
				false,
				0,
				gameMode,
				null,
				true,
				0,
				(RemoteChatSession.Data) null
		);
		packet.entries().add(entry);
		return packet;
	}

	private static void sendControlledOperatorBodyMirrorState(ServerPlayer player, OperatorBodyMirror mirror) {
		if (player == null || mirror == null || player.connection == null) {
			return;
		}
		PositionMoveRotation bodyPose = new PositionMoveRotation(
				player.position(),
				player.getDeltaMovement(),
				player.getYRot(),
				player.getXRot()
		);
		sendControlledOperatorPacket(player, ClientboundTeleportEntityPacket.teleport(
				mirror.entityId(),
				bodyPose,
				ABSOLUTE_TELEPORT,
				player.onGround()
		));
		sendControlledOperatorPacket(player, new ClientboundSetEntityMotionPacket(mirror.entityId(), player.getDeltaMovement()));
		sendControlledOperatorPacket(player, new ClientboundSetEntityDataPacket(
				mirror.entityId(),
				buildOperatorBodyMirrorMetadata(player)
		));
		sendControlledOperatorPacket(player, new ClientboundSetEquipmentPacket(
				mirror.entityId(),
				buildOperatorBodyMirrorEquipment(player)
		));
	}

	private static List<SynchedEntityData.DataValue<?>> buildOperatorBodyMirrorMetadata(ServerPlayer player) {
		List<SynchedEntityData.DataValue<?>> values = player.getEntityData().getNonDefaultValues();
		List<SynchedEntityData.DataValue<?>> data = values == null ? new ArrayList<>() : new ArrayList<>(values);
		EntityDataAccessor<Byte> sharedFlagsAccessor = EntityTrackedDataAccessor.lg2$getDataSharedFlagsId();
		EntityDataAccessor<Boolean> noGravityAccessor = EntityTrackedDataAccessor.lg2$getDataNoGravity();
		EntityDataAccessor<Pose> poseAccessor = EntityTrackedDataAccessor.lg2$getDataPose();
		EntityDataAccessor<HumanoidArm> mainHandAccessor = PlayerTrackedDataAccessor.lg2$getDataPlayerMainHand();
		EntityDataAccessor<Byte> skinPartsAccessor = PlayerTrackedDataAccessor.lg2$getDataPlayerModeCustomisation();
		byte sharedFlags = player.getEntityData().get(sharedFlagsAccessor);
		if (CONTROLLED_OPERATOR_AUTO_AIM_BODY_HIGHLIGHTS.contains(player.getUUID())) {
			sharedFlags |= 0x40;
		}
		upsertTrackedData(data, SynchedEntityData.DataValue.create(sharedFlagsAccessor, sharedFlags));
		upsertTrackedData(data, SynchedEntityData.DataValue.create(noGravityAccessor, player.getEntityData().get(noGravityAccessor)));
		upsertTrackedData(data, SynchedEntityData.DataValue.create(poseAccessor, player.getEntityData().get(poseAccessor)));
		HumanoidArm mainHand = player.getEntityData().get(mainHandAccessor);
		Byte skinParts = player.getEntityData().get(skinPartsAccessor);
		upsertTrackedData(data, SynchedEntityData.DataValue.create(mainHandAccessor, mainHand == null ? HumanoidArm.RIGHT : mainHand));
		upsertTrackedData(data, SynchedEntityData.DataValue.create(skinPartsAccessor, skinParts == null ? OPERATOR_BODY_MIRROR_ALL_SKIN_PARTS : skinParts));
		return data;
	}

	private static List<Pair<EquipmentSlot, ItemStack>> buildOperatorBodyMirrorEquipment(ServerPlayer player) {
		List<Pair<EquipmentSlot, ItemStack>> slots = new ArrayList<>();
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			slots.add(Pair.of(slot, player.getItemBySlot(slot).copy()));
		}
		return slots;
	}

	private static <T> void upsertTrackedData(List<SynchedEntityData.DataValue<?>> data, SynchedEntityData.DataValue<T> replacement) {
		if (data == null || replacement == null) {
			return;
		}
		for (int i = 0; i < data.size(); i++) {
			SynchedEntityData.DataValue<?> current = data.get(i);
			if (current != null && current.id() == replacement.id()) {
				data.set(i, replacement);
				return;
			}
		}
		data.add(replacement);
	}

	private static void removeControlledOperatorBodyMirror(ServerPlayer player) {
		if (player == null) {
			return;
		}
		OperatorBodyMirror mirror = OPERATOR_BODY_MIRRORS.remove(player.getUUID());
		if (mirror == null || player.connection == null) {
			return;
		}
		sendControlledOperatorPacket(player, new ClientboundRemoveEntitiesPacket(mirror.entityId()));
		sendControlledOperatorPacket(player, new ClientboundPlayerInfoRemovePacket(List.of(mirror.profileId())));
	}

	private static void syncControlledOperatorNightVision(ServerPlayer player, DroneControlSession session, Entity root) {
		if (player == null || session == null || root == null) {
			return;
		}
		if (hasDroneNightVisionModule(root)) {
			applyManagedDroneNightVision(player);
			return;
		}
		clearManagedDroneNightVision(player);
	}

	private static void applyManagedDroneNightVision(ServerPlayer player) {
		if (player == null) {
			return;
		}
		MobEffectInstance current = player.getEffect(MobEffects.NIGHT_VISION);
		if (current != null && !isDroneManagedNightVision(current)) {
			return;
		}
		if (current == null || !current.isInfiniteDuration()) {
			player.addEffect(new MobEffectInstance(
					MobEffects.NIGHT_VISION,
					MobEffectInstance.INFINITE_DURATION,
					DRONE_MANAGED_NIGHT_VISION_AMPLIFIER,
					false,
					false,
					false
			));
		}
		CONTROLLED_OPERATOR_MANAGED_NIGHT_VISION.add(player.getUUID());
	}

	private static void clearManagedDroneNightVision(ServerPlayer player) {
		if (player == null || !CONTROLLED_OPERATOR_MANAGED_NIGHT_VISION.remove(player.getUUID())) {
			return;
		}
		MobEffectInstance current = player.getEffect(MobEffects.NIGHT_VISION);
		if (isDroneManagedNightVision(current)) {
			player.removeEffect(MobEffects.NIGHT_VISION);
		}
	}

	private static boolean isDroneManagedNightVision(MobEffectInstance effect) {
		return effect != null
				&& effect.getAmplifier() == DRONE_MANAGED_NIGHT_VISION_AMPLIFIER
				&& effect.isInfiniteDuration()
				&& !effect.isVisible();
	}

	private static boolean syncControlledOperatorAutoAim(ServerPlayer player, DroneControlSession session, Entity root) {
		if (player == null || session == null || root == null) {
			return false;
		}
		boolean enabled = hasDroneAutoAimModule(root);
		syncControlledOperatorAutoAimInteractionRange(player, enabled);
		if (!enabled) {
			clearControlledAutoAimTarget(player, session);
			return false;
		}

		DroneAutoAimTarget target = session.autoAimTarget();
		if (target == null) {
			return false;
		}
		MinecraftServer server = player.level() == null ? null : player.level().getServer();
		Vec3 targetPoint = resolveControlledAutoAimTargetPoint(root, server, session, target);
		if (targetPoint == null) {
			if (isDroneAutoAimTargetDefinitelyMissing(server, session.droneDimension(), target)) {
				clearControlledAutoAimTarget(player, session);
			} else {
				// Retain the selected target so aiming resumes immediately when it
				// becomes visible again, without keeping an indicator through walls.
				clearControlledAutoAimHighlight(player);
			}
			return false;
		}
		if (!CONTROLLED_OPERATOR_AUTO_AIM_HIGHLIGHTS.contains(player.getUUID())) {
			showControlledAutoAimTarget(player, session, target);
		}
		if (session.isManualLookRecentlyActive(root.level() == null ? Long.MIN_VALUE : root.level().getGameTime())) {
			return false;
		}

		Vec3 origin = resolveSafeDroneCameraOrigin(root, droneCameraOrigin(root));
		float targetYaw = yawTo(origin, targetPoint);
		float targetPitch = pitchTo(origin, targetPoint);
		DroneAutoAimAngles nextAngles = approachDroneAutoAimAngles(
				session.proxyYaw(),
				session.proxyPitch(),
				targetYaw,
				targetPitch,
				DRONE_AUTO_AIM_CONTROLLED_ROTATION_BLEND,
				DRONE_AUTO_AIM_CONTROLLED_MAX_YAW_STEP_DEGREES,
				DRONE_AUTO_AIM_CONTROLLED_MAX_PITCH_STEP_DEGREES
		);
		boolean changed = Math.abs(net.minecraft.util.Mth.wrapDegrees(nextAngles.yaw() - session.proxyYaw())) > 1.0E-4F
				|| Math.abs(nextAngles.pitch() - session.proxyPitch()) > 1.0E-4F;
		if (!changed) {
			return false;
		}

		session.setControlYaw(nextAngles.yaw());
		session.setControlPitch(nextAngles.pitch());
		session.setProxyYaw(nextAngles.yaw());
		session.setProxyPitch(nextAngles.pitch());
		root.setYRot(nextAngles.yaw());
		root.setXRot(nextAngles.pitch());
		root.hurtMarked = true;
		return true;
	}

	private static boolean shouldHandleControlledAutoAimAttackInput(Entity root) {
		return root != null && hasDroneAutoAimModule(root);
	}

	private static boolean shouldHandleControlledAutoAimUseInput(Entity root) {
		return root != null && hasDroneAutoAimModule(root) && !hasDroneTurretModule(root);
	}

	private static InteractionResult handleControlledAutoAimEntityInteraction(
			ServerPlayer player,
			InteractionHand hand,
			Entity entity,
			boolean useInput
	) {
		if (player == null || hand != InteractionHand.MAIN_HAND || entity == null) {
			return InteractionResult.PASS;
		}
		DroneControlSession session = ACTIVE_SESSIONS.get(player.getUUID());
		Entity root = resolveControlledDroneRoot(player);
		if (session == null
				|| root == null
				|| !(useInput ? shouldHandleControlledAutoAimUseInput(root) : shouldHandleControlledAutoAimAttackInput(root))) {
			return InteractionResult.PASS;
		}
		if (session.shouldSuppressAutoAimSelection(root.level() == null ? Long.MIN_VALUE : root.level().getGameTime())) {
			return InteractionResult.SUCCESS;
		}
		if (isDroneInternalEntity(entity)) {
			return selectControlledAutoAimTargetFromView(player, session, root);
		}
		if (!isSelectableControlledAutoAimEntityTarget(root, entity)) {
			return InteractionResult.PASS;
		}
		Vec3 targetPoint = entity.getEyePosition();
		if (!isWithinControlledAutoAimSelectionRange(player, root, targetPoint)) {
			return InteractionResult.SUCCESS;
		}
		toggleControlledAutoAimTarget(player, session, new DroneAutoAimEntityTarget(entity.getUUID()));
		return InteractionResult.SUCCESS;
	}

	private static InteractionResult selectControlledAutoAimTargetFromView(
			ServerPlayer player,
			DroneControlSession session,
			Entity root
	) {
		if (player == null || session == null || root == null || !(root.level() instanceof ServerLevel level)) {
			return InteractionResult.PASS;
		}
		if (!hasDroneAutoAimModule(root)) {
			return InteractionResult.PASS;
		}
		if (session.shouldSuppressAutoAimSelection(level.getGameTime())) {
			return InteractionResult.SUCCESS;
		}
		Vec3 origin = resolveSafeDroneCameraOrigin(
				root,
				droneCameraOrigin(finiteVecOr(session.proxyPos(), root.position()), resolveDroneVisualLift(root))
		);
		Vec3 direction = controlledTurretDirection(player, session);
		double selectionRangeBlocks = resolveDroneAutoAimSelectionRangeBlocks(player);
		Vec3 end = origin.add(direction.scale(selectionRangeBlocks));
		BlockHitResult blockHit = clipAutoAimBlockThroughPartialBlocks(level, origin, end, root);
		Vec3 blockLimitedEnd = blockHit.getType() == HitResult.Type.MISS ? end : blockHit.getLocation();
		Vec3 operatorHit = resolveControlledOperatorAutoAimRayHit(player, level, origin, blockLimitedEnd);
		EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
				level,
				root,
				origin,
				blockLimitedEnd,
				new AABB(origin, blockLimitedEnd).inflate(0.75D),
				entity -> isSelectableControlledAutoAimEntityTarget(root, entity),
				0.25F
		);
		if (operatorHit != null
				&& (entityHit == null || origin.distanceToSqr(operatorHit) <= origin.distanceToSqr(entityHit.getLocation()))) {
			toggleControlledAutoAimTarget(player, session, new DroneAutoAimEntityTarget(player.getUUID()));
			return InteractionResult.SUCCESS;
		}
		if (entityHit != null && entityHit.getEntity() != null) {
			toggleControlledAutoAimTarget(player, session, new DroneAutoAimEntityTarget(entityHit.getEntity().getUUID()));
			return InteractionResult.SUCCESS;
		}
		if (blockHit.getType() == HitResult.Type.MISS) {
			return InteractionResult.SUCCESS;
		}
		BlockPos blockPos = blockHit.getBlockPos();
		if (!level.hasChunkAt(blockPos)) {
			return InteractionResult.SUCCESS;
		}
		BlockState state = level.getBlockState(blockPos);
		if (!isSelectableAutoAimBlock(level, blockPos, state)) {
			return InteractionResult.SUCCESS;
		}
		toggleControlledAutoAimTarget(player, session, new DroneAutoAimBlockTarget(blockPos.immutable()));
		return InteractionResult.SUCCESS;
	}

	private static Vec3 resolveControlledOperatorAutoAimRayHit(
			ServerPlayer player,
			ServerLevel droneLevel,
			Vec3 origin,
			Vec3 end
	) {
		if (player == null
				|| droneLevel == null
				|| player.level() != droneLevel
				|| origin == null
				|| end == null
				|| !isSelectableControlledAutoAimEntityTarget(null, player)) {
			return null;
		}
		return player.getBoundingBox().inflate(0.25D).clip(origin, end).orElse(null);
	}

	private static boolean isSelectableControlledAutoAimEntityTarget(Entity root, Entity entity) {
		return entity != null
				&& entity.isAlive()
				&& entity != root
				&& !isDroneInternalEntity(entity);
	}

	private static InteractionResult handleControlledAutoAimBlockInteraction(
			ServerPlayer player,
			InteractionHand hand,
			BlockPos pos,
			boolean useInput
	) {
		if (player == null || hand != InteractionHand.MAIN_HAND || pos == null || !(player.level() instanceof ServerLevel level)) {
			return InteractionResult.PASS;
		}
		DroneControlSession session = ACTIVE_SESSIONS.get(player.getUUID());
		Entity root = resolveControlledDroneRoot(player);
		if (session == null
				|| root == null
				|| !(useInput ? shouldHandleControlledAutoAimUseInput(root) : shouldHandleControlledAutoAimAttackInput(root))) {
			return InteractionResult.PASS;
		}
		if (!level.hasChunkAt(pos)) {
			return InteractionResult.SUCCESS;
		}
		BlockState state = level.getBlockState(pos);
		if (!isSelectableAutoAimBlock(level, pos, state)) {
			return selectControlledAutoAimTargetFromView(player, session, root);
		}
		Vec3 targetPoint = resolveAutoAimBlockTargetPoint(level, pos, state);
		if (targetPoint == null) {
			return InteractionResult.SUCCESS;
		}
		if (!isWithinControlledAutoAimSelectionRange(player, root, targetPoint)) {
			return InteractionResult.SUCCESS;
		}
		toggleControlledAutoAimTarget(player, session, new DroneAutoAimBlockTarget(pos.immutable()));
		return InteractionResult.SUCCESS;
	}

	private static void toggleControlledAutoAimTarget(ServerPlayer player, DroneControlSession session, DroneAutoAimTarget target) {
		if (player == null || session == null || target == null) {
			return;
		}
		if (Objects.equals(session.autoAimTarget(), target)) {
			clearControlledAutoAimTarget(player, session);
			return;
		}
		session.setAutoAimTarget(target);
		setPersistedDroneAutoAimTarget(resolveControlledDroneRoot(player), target);
		showControlledAutoAimTarget(player, session, target);
	}

	private static void showControlledAutoAimTarget(ServerPlayer player, DroneControlSession session, DroneAutoAimTarget target) {
		if (player == null || session == null || target == null) {
			return;
		}
		MinecraftServer server = player.level() == null ? null : player.level().getServer();
		if (target instanceof DroneAutoAimEntityTarget entityTarget && Objects.equals(entityTarget.entityUuid(), player.getUUID())) {
			showControlledOperatorBodyAutoAimHighlight(player);
			CONTROLLED_OPERATOR_AUTO_AIM_HIGHLIGHTS.add(player.getUUID());
			return;
		}
		clearControlledOperatorBodyAutoAimHighlight(player);
		List<ServerSelectionHighlightSystem.DisplayBlueprint> blueprints = resolveControlledAutoAimHighlightBlueprints(server, session, target);
		if (blueprints.isEmpty()) {
			clearControlledAutoAimHighlight(player);
			return;
		}
		ServerSelectionHighlightSystem.show(player, blueprints);
		CONTROLLED_OPERATOR_AUTO_AIM_HIGHLIGHTS.add(player.getUUID());
	}

	private static void clearControlledAutoAimTarget(ServerPlayer player, DroneControlSession session) {
		if (session != null) {
			session.setAutoAimTarget(null);
			setPersistedDroneAutoAimTarget(resolveControlledDroneRoot(player), null);
		}
		clearControlledAutoAimHighlight(player);
	}

	private static void clearControlledAutoAimHighlight(ServerPlayer player) {
		clearControlledOperatorBodyAutoAimHighlight(player);
		if (player != null && CONTROLLED_OPERATOR_AUTO_AIM_HIGHLIGHTS.remove(player.getUUID())) {
			ServerSelectionHighlightSystem.clear(player);
		}
	}

	private static void showControlledOperatorBodyAutoAimHighlight(ServerPlayer player) {
		if (player == null) {
			return;
		}
		ServerSelectionHighlightSystem.clear(player);
		CONTROLLED_OPERATOR_AUTO_AIM_BODY_HIGHLIGHTS.add(player.getUUID());
		syncControlledOperatorBodyMirror(player, false);
	}

	private static void clearControlledOperatorBodyAutoAimHighlight(ServerPlayer player) {
		if (player != null && CONTROLLED_OPERATOR_AUTO_AIM_BODY_HIGHLIGHTS.remove(player.getUUID())) {
			syncControlledOperatorBodyMirror(player, false);
		}
	}

	private static List<ServerSelectionHighlightSystem.DisplayBlueprint> resolveControlledAutoAimHighlightBlueprints(
			MinecraftServer server,
			DroneControlSession session,
			DroneAutoAimTarget target
	) {
		if (server == null || session == null || target == null) {
			return List.of();
		}
		ServerLevel level = server.getLevel(session.droneDimension());
		if (level == null) {
			return List.of();
		}
		if (target instanceof DroneAutoAimEntityTarget entityTarget) {
			Entity entity = findEntity(server, session.droneDimension(), entityTarget.entityUuid());
			if (entity == null || !entity.isAlive()) {
				return List.of();
			}
			return List.of(new ServerSelectionHighlightSystem.EntityGlowBlueprint(entity));
		}
		if (target instanceof DroneAutoAimBlockTarget blockTarget) {
			if (!level.hasChunkAt(blockTarget.blockPos())) {
				return List.of();
			}
			BlockState state = level.getBlockState(blockTarget.blockPos());
			if (!isSelectableAutoAimBlock(level, blockTarget.blockPos(), state)) {
				return List.of();
			}
			Vec3 highlightPos = resolveAutoAimBlockTargetPoint(level, blockTarget.blockPos(), state);
			if (highlightPos == null) {
				return List.of();
			}
			return List.of(new ServerSelectionHighlightSystem.ItemDisplayBlueprint(
					level,
					highlightPos,
					0.0F,
					0.0F,
					ServerSelectionHighlightSystem.createHighlightCarrierStack(),
					ItemDisplayContext.FIXED,
					ServerSelectionHighlightSystem.defaultHighlightCarrierTransformation()
			));
		}
		return List.of();
	}

	private static Vec3 resolveControlledAutoAimTargetPoint(Entity root, MinecraftServer server, DroneControlSession session, DroneAutoAimTarget target) {
		if (session == null) {
			return null;
		}
		return resolveDroneAutoAimTargetPoint(root, server, session.droneDimension(), target);
	}

	private static Vec3 resolveDroneAutoAimTargetPoint(
			Entity root,
			MinecraftServer server,
			net.minecraft.resources.ResourceKey<Level> droneDimension,
			DroneAutoAimTarget target
	) {
		if (server == null || droneDimension == null || target == null) {
			return null;
		}
		if (target instanceof DroneAutoAimEntityTarget entityTarget) {
			Entity entity = findEntity(server, droneDimension, entityTarget.entityUuid());
			if (entity == null || !entity.isAlive() || isDroneInternalEntity(entity)
					|| !isDroneAutoAimEntityVisible(root, entity)) {
				return null;
			}
			return entity.getEyePosition();
		}
		if (target instanceof DroneAutoAimBlockTarget blockTarget) {
			ServerLevel level = server.getLevel(droneDimension);
			if (level == null || !level.hasChunkAt(blockTarget.blockPos())) {
				return null;
			}
			BlockState state = level.getBlockState(blockTarget.blockPos());
			return resolveAutoAimBlockTargetPoint(level, blockTarget.blockPos(), state);
		}
		return null;
	}

	/**
	 * A target is visible when the camera can reach at least one meaningful part
	 * of its body without crossing an opaque block. This lets a target peek
	 * around a corner but stops the module from steering through a solid wall.
	 */
	private static boolean isDroneAutoAimEntityVisible(Entity root, Entity target) {
		if (root == null
				|| target == null
				|| !root.isAlive()
				|| !target.isAlive()
				|| !(root.level() instanceof ServerLevel level)
				|| target.level() != level) {
			return false;
		}
		Vec3 origin = resolveSafeDroneCameraOrigin(root, droneCameraOrigin(root));
		for (Vec3 targetPoint : droneAutoAimVisibilityPoints(target)) {
			if (!isDroneAutoAimLineBlockedByOpaqueBlock(level, origin, targetPoint, root)) {
				return true;
			}
		}
		return false;
	}

	private static List<Vec3> droneAutoAimVisibilityPoints(Entity target) {
		if (target == null) {
			return List.of();
		}
		AABB bounds = target.getBoundingBox();
		double centerX = (bounds.minX + bounds.maxX) * 0.5D;
		double centerZ = (bounds.minZ + bounds.maxZ) * 0.5D;
		double middleY = bounds.minY + (bounds.maxY - bounds.minY) * 0.55D;
		double lowerY = bounds.minY + (bounds.maxY - bounds.minY) * 0.25D;
		double xOffset = Math.min(0.25D, Math.max(0.0D, (bounds.maxX - bounds.minX) * 0.25D));
		double zOffset = Math.min(0.25D, Math.max(0.0D, (bounds.maxZ - bounds.minZ) * 0.25D));
		return List.of(
				target.getEyePosition(),
				new Vec3(centerX, middleY, centerZ),
				new Vec3(centerX, lowerY, centerZ),
				new Vec3(centerX - xOffset, middleY, centerZ - zOffset),
				new Vec3(centerX + xOffset, middleY, centerZ + zOffset)
		);
	}

	private static boolean isDroneAutoAimLineBlockedByOpaqueBlock(ServerLevel level, Vec3 origin, Vec3 end, Entity clipContextEntity) {
		if (level == null || origin == null || end == null) {
			return true;
		}
		Vec3 travel = end.subtract(origin);
		double totalDistanceSqr = travel.lengthSqr();
		if (totalDistanceSqr <= 1.0E-9D) {
			return false;
		}
		Vec3 currentStart = origin;
		Vec3 step = travel.normalize().scale(0.002D);
		int maxSkips = Math.max(1, net.minecraft.util.Mth.ceil(Math.sqrt(totalDistanceSqr)) + 4);
		for (int i = 0; i < maxSkips; i++) {
			BlockHitResult hit = level.clip(new ClipContext(currentStart, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, clipContextEntity));
			if (hit.getType() == HitResult.Type.MISS) {
				return false;
			}
			BlockPos pos = hit.getBlockPos();
			if (level.hasChunkAt(pos) && isDroneAutoAimOpaqueOccluder(level, pos, level.getBlockState(pos))) {
				return true;
			}
			currentStart = hit.getLocation().add(step);
			if (currentStart.distanceToSqr(origin) >= totalDistanceSqr) {
				return false;
			}
		}
		return false;
	}

	private static boolean isDroneAutoAimOpaqueOccluder(ServerLevel level, BlockPos pos, BlockState state) {
		return level != null
				&& pos != null
				&& state != null
				&& state.isSolidRender()
				&& state.getLightBlock() >= 15;
	}

	private static boolean isDroneAutoAimTargetDefinitelyMissing(
			MinecraftServer server,
			net.minecraft.resources.ResourceKey<Level> droneDimension,
			DroneAutoAimTarget target
	) {
		if (server == null || droneDimension == null || target == null) {
			return false;
		}
		if (target instanceof DroneAutoAimEntityTarget entityTarget) {
			Entity entity = findEntity(server, droneDimension, entityTarget.entityUuid());
			return entity != null && (!entity.isAlive() || isDroneInternalEntity(entity));
		}
		if (target instanceof DroneAutoAimBlockTarget blockTarget) {
			ServerLevel level = server.getLevel(droneDimension);
			if (level == null || !level.hasChunkAt(blockTarget.blockPos())) {
				return false;
			}
			return !isSelectableAutoAimBlock(level, blockTarget.blockPos(), level.getBlockState(blockTarget.blockPos()));
		}
		return false;
	}

	private static DroneAutoAimTarget resolvePersistedDroneAutoAimTarget(Entity root) {
		if (root == null) {
			return null;
		}
		for (String tag : root.getTags()) {
			if (tag != null && tag.startsWith(DRONE_AUTO_AIM_TARGET_ENTITY_TAG_PREFIX)) {
				try {
					return new DroneAutoAimEntityTarget(UUID.fromString(tag.substring(DRONE_AUTO_AIM_TARGET_ENTITY_TAG_PREFIX.length())));
				} catch (IllegalArgumentException ignored) {
				}
			}
			if (tag != null && tag.startsWith(DRONE_AUTO_AIM_TARGET_BLOCK_TAG_PREFIX)) {
				String[] coordinates = tag.substring(DRONE_AUTO_AIM_TARGET_BLOCK_TAG_PREFIX.length()).split("_", -1);
				if (coordinates.length != 3) {
					continue;
				}
				try {
					return new DroneAutoAimBlockTarget(new BlockPos(
							Integer.parseInt(coordinates[0]),
							Integer.parseInt(coordinates[1]),
							Integer.parseInt(coordinates[2])
					));
				} catch (NumberFormatException ignored) {
				}
			}
		}
		return null;
	}

	private static void setPersistedDroneAutoAimTarget(Entity root, DroneAutoAimTarget target) {
		if (root == null) {
			return;
		}
		for (String tag : new ArrayList<>(root.getTags())) {
			if (tag != null && (tag.startsWith(DRONE_AUTO_AIM_TARGET_ENTITY_TAG_PREFIX)
					|| tag.startsWith(DRONE_AUTO_AIM_TARGET_BLOCK_TAG_PREFIX))) {
				root.removeTag(tag);
			}
		}
		if (target instanceof DroneAutoAimEntityTarget entityTarget && entityTarget.entityUuid() != null) {
			root.addTag(DRONE_AUTO_AIM_TARGET_ENTITY_TAG_PREFIX + entityTarget.entityUuid());
		} else if (target instanceof DroneAutoAimBlockTarget blockTarget && blockTarget.blockPos() != null) {
			BlockPos pos = blockTarget.blockPos();
			root.addTag(DRONE_AUTO_AIM_TARGET_BLOCK_TAG_PREFIX + pos.getX() + "_" + pos.getY() + "_" + pos.getZ());
		}
	}

	private static boolean isSelectableAutoAimBlock(ServerLevel level, BlockPos pos, BlockState state) {
		return level != null
				&& pos != null
				&& state != null
				&& !state.isAir()
				&& state.isCollisionShapeFullBlock(level, pos);
	}

	private static boolean isDroneInternalEntity(Entity entity) {
		if (entity == null) {
			return false;
		}
		if (entity instanceof Display || entity instanceof Interaction) {
			return true;
		}
		return entity.getTags().contains(DRONE_ROOT_TAG)
				|| entity.getTags().contains(DRONE_DISPLAY_TAG)
				|| entity.getTags().contains(DRONE_CAMERA_TAG)
				|| entity.getTags().contains(DRONE_TURRET_TRIGGER_TAG);
	}

	private static Vec3 resolveAutoAimBlockTargetPoint(ServerLevel level, BlockPos pos, BlockState state) {
		if (!isSelectableAutoAimBlock(level, pos, state)) {
			return null;
		}
		return Vec3.atCenterOf(pos);
	}

	private static boolean isWithinControlledAutoAimSelectionRange(ServerPlayer player, Entity root, Vec3 targetPoint) {
		if (player == null || root == null || targetPoint == null) {
			return false;
		}
		Vec3 origin = resolveSafeDroneCameraOrigin(root, droneCameraOrigin(root));
		double selectionRangeBlocks = resolveDroneAutoAimSelectionRangeBlocks(player);
		return origin.distanceToSqr(targetPoint) <= selectionRangeBlocks * selectionRangeBlocks;
	}

	private static void syncControlledOperatorAutoAimInteractionRange(ServerPlayer player, boolean enabled) {
		if (player == null) {
			return;
		}
		if (!enabled && !isControllingDrone(player)) {
			clearControlledOperatorInteractionRange(player);
			return;
		}
		double targetRangeBlocks = enabled
				? resolveDroneAutoAimSelectionRangeBlocks(player)
				: (isControllingDrone(player) ? CONTROLLED_OPERATOR_DISABLED_INTERACTION_RANGE_BLOCKS : 0.0D);
		boolean changed = false;
		changed |= syncControlledOperatorAttributeModifier(
				player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE),
				DRONE_AUTO_AIM_BLOCK_INTERACTION_RANGE_MODIFIER_ID,
				resolveTargetInteractionRangeOffset(
						player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE),
						DRONE_AUTO_AIM_BLOCK_INTERACTION_RANGE_MODIFIER_ID,
						targetRangeBlocks
				)
		);
		changed |= syncControlledOperatorAttributeModifier(
				player.getAttribute(Attributes.ENTITY_INTERACTION_RANGE),
				DRONE_AUTO_AIM_ENTITY_INTERACTION_RANGE_MODIFIER_ID,
				resolveTargetInteractionRangeOffset(
						player.getAttribute(Attributes.ENTITY_INTERACTION_RANGE),
						DRONE_AUTO_AIM_ENTITY_INTERACTION_RANGE_MODIFIER_ID,
						targetRangeBlocks
				)
		);
		if (changed) {
			syncControlledOperatorInteractionRangeClient(player);
		}
	}

	private static double resolveDroneAutoAimSelectionRangeBlocks(ServerPlayer player) {
		return resolveRequestedViewDistance(player) * 16.0D;
	}

	private static double resolveAutoAimRangeBonus(AttributeInstance attribute, double targetRangeBlocks) {
		if (attribute == null || targetRangeBlocks <= 0.0D) {
			return 0.0D;
		}
		return Math.max(0.0D, targetRangeBlocks - attribute.getBaseValue());
	}

	private static double resolveTargetInteractionRangeOffset(
			AttributeInstance attribute,
			Identifier modifierId,
			double targetRangeBlocks
	) {
		if (attribute == null) {
			return 0.0D;
		}
		AttributeModifier current = modifierId == null ? null : attribute.getModifier(modifierId);
		double currentAmount = current == null ? 0.0D : current.amount();
		double effectiveValueWithoutControlledModifier = attribute.getValue() - currentAmount;
		return targetRangeBlocks - effectiveValueWithoutControlledModifier;
	}

	private static BlockHitResult clipAutoAimBlockThroughPartialBlocks(ServerLevel level, Vec3 origin, Vec3 end, Entity clipContextEntity) {
		if (level == null || origin == null || end == null) {
			return new BlockHitResult(origin == null ? Vec3.ZERO : origin, Direction.UP, BlockPos.containing(origin == null ? Vec3.ZERO : origin), true);
		}
		Vec3 currentStart = origin;
		Vec3 travel = end.subtract(origin);
		double totalDistanceSqr = travel.lengthSqr();
		if (totalDistanceSqr <= 1.0E-9D) {
			return level.clip(new ClipContext(origin, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, clipContextEntity));
		}
		Vec3 step = travel.normalize().scale(0.002D);
		int maxSkips = Math.max(1, net.minecraft.util.Mth.ceil(Math.sqrt(totalDistanceSqr)) + 4);
		for (int i = 0; i < maxSkips; i++) {
			BlockHitResult blockHit = level.clip(new ClipContext(currentStart, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, clipContextEntity));
			if (blockHit.getType() == HitResult.Type.MISS) {
				return blockHit;
			}
			BlockPos blockPos = blockHit.getBlockPos();
			if (level.hasChunkAt(blockPos) && isSelectableAutoAimBlock(level, blockPos, level.getBlockState(blockPos))) {
				return blockHit;
			}
			currentStart = blockHit.getLocation().add(step);
			if (currentStart.distanceToSqr(origin) >= totalDistanceSqr) {
				break;
			}
		}
		return new BlockHitResult(end, Direction.UP, BlockPos.containing(end), true);
	}

	private static void syncControlledOperatorInteractionRangeClient(ServerPlayer player) {
		if (player == null || player.connection == null) {
			return;
		}
		List<AttributeInstance> attributes = new ArrayList<>(2);
		AttributeInstance blockRange = player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE);
		if (blockRange != null) {
			attributes.add(blockRange);
		}
		AttributeInstance entityRange = player.getAttribute(Attributes.ENTITY_INTERACTION_RANGE);
		if (entityRange != null) {
			attributes.add(entityRange);
		}
		if (attributes.isEmpty()) {
			return;
		}
		sendControlledOperatorPacket(player, new ClientboundUpdateAttributesPacket(player.getId(), attributes));
	}

	private static boolean syncControlledOperatorAttributeModifier(AttributeInstance attribute, Identifier modifierId, double amount) {
		if (attribute == null || modifierId == null) {
			return false;
		}
		AttributeModifier current = attribute.getModifier(modifierId);
		if (Math.abs(amount) <= 1.0E-6D) {
			if (current != null) {
				attribute.removeModifier(modifierId);
				return true;
			}
			return false;
		}
		if (current == null
				|| current.operation() != AttributeModifier.Operation.ADD_VALUE
				|| Double.compare(current.amount(), amount) != 0) {
			if (current != null) {
				attribute.removeModifier(modifierId);
			}
			attribute.addTransientModifier(new AttributeModifier(modifierId, amount, AttributeModifier.Operation.ADD_VALUE));
			return true;
		}
		return false;
	}

	private static float yawTo(Vec3 origin, Vec3 target) {
		Vec3 delta = target.subtract(origin);
		return (float) Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0F;
	}

	private static float pitchTo(Vec3 origin, Vec3 target) {
		Vec3 delta = target.subtract(origin);
		double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
		return (float) -Math.toDegrees(Math.atan2(delta.y, horizontal));
	}

	private static DroneAutoAimAngles approachDroneAutoAimAngles(
			float currentYaw,
			float currentPitch,
			float targetYaw,
			float targetPitch,
			float blend,
			float maxYawStep,
			float maxPitchStep
	) {
		float yawError = net.minecraft.util.Mth.wrapDegrees(targetYaw - currentYaw);
		float pitchError = targetPitch - currentPitch;
		float nextYaw = currentYaw + net.minecraft.util.Mth.clamp(
				yawError * net.minecraft.util.Mth.clamp(blend, 0.0F, 1.0F),
				-Math.abs(maxYawStep),
				Math.abs(maxYawStep)
		);
		float nextPitch = currentPitch + net.minecraft.util.Mth.clamp(
				pitchError * net.minecraft.util.Mth.clamp(blend, 0.0F, 1.0F),
				-Math.abs(maxPitchStep),
				Math.abs(maxPitchStep)
		);
		return new DroneAutoAimAngles(nextYaw, net.minecraft.util.Mth.clamp(nextPitch, -90.0F, 90.0F));
	}

	private static void updateDroneHud(ServerPlayer player, DroneControlSession session, boolean force) {
		if (player == null || session == null || player.connection == null) {
			return;
		}

		long now = player.level().getGameTime();
		int controlSpeedSlot = getControlSpeedSlot(player);
		String snapshot = buildDroneHudSnapshot(session, session.velocity(), controlSpeedSlot);
		if (!force
				&& session.hudVisible()
				&& Objects.equals(session.lastHudSnapshot(), snapshot)
				&& now - session.lastHudTick() < DRONE_HUD_REFRESH_TICKS) {
			return;
		}

		player.connection.send(new ClientboundSetTitlesAnimationPacket(0, 30, 0));
		if (player != null && PolymerResourcePackUtils.hasMainPack(player)) {
			if (force) {
				player.connection.send(new ClientboundSetTitleTextPacket(Component.empty()));
				player.connection.send(new ClientboundSetSubtitleTextPacket(Component.empty()));
			}
			showDroneHudOverlay(player, session);
			player.connection.send(new ClientboundSetActionBarTextPacket(buildDroneHudWidget(session, controlSpeedSlot)));
		} else {
			hideDroneHudOverlay(player);
			if (force) {
				player.connection.send(new ClientboundSetTitleTextPacket(Component.empty()));
				player.connection.send(new ClientboundSetActionBarTextPacket(Component.empty()));
			}
			player.connection.send(new ClientboundSetSubtitleTextPacket(Component.empty()));
			player.connection.send(new ClientboundSetActionBarTextPacket(buildDroneHudTextSubtitle(session, session.velocity(), controlSpeedSlot)));
		}
		session.setHudVisible(true);
		session.setLastHudSnapshot(snapshot);
		session.setLastHudTick(now);
	}

	private static void clearDroneHud(ServerPlayer player, DroneControlSession session, boolean force) {
		if (player == null || session == null || player.connection == null) {
			return;
		}
		if (!force && !session.hudVisible()) {
			return;
		}

		player.connection.send(new ClientboundSetTitleTextPacket(Component.empty()));
		player.connection.send(new ClientboundSetSubtitleTextPacket(Component.empty()));
		player.connection.send(new ClientboundSetActionBarTextPacket(Component.empty()));
		hideDroneHudOverlay(player);
		session.setHudVisible(false);
		session.setLastHudSnapshot("");
		session.setLastHudTick(Long.MIN_VALUE);
	}

	private static Component buildDroneHudTextSubtitle(DroneControlSession session, Vec3 velocity, int controlSpeedSlot) {
		int forwardPct = drivePercent(Math.max(0.0D, session.forwardDrive()), DroneFlightPhysics.MAX_FORWARD_DRIVE);
		int reversePct = drivePercent(Math.max(0.0D, -session.forwardDrive()), DroneFlightPhysics.MAX_FORWARD_DRIVE);
		int leftPct = drivePercent(Math.max(0.0D, -session.strafeDrive()), DroneFlightPhysics.MAX_STRAFE_DRIVE);
		int rightPct = drivePercent(Math.max(0.0D, session.strafeDrive()), DroneFlightPhysics.MAX_STRAFE_DRIVE);
		int speedPct = (int) Math.round(net.minecraft.util.Mth.clamp(
				(velocity == null ? 0.0D : velocity.length()) / DroneFlightPhysics.MAX_COMBINED_SPEED,
				0.0D,
				1.0D
		) * 100.0D);
		int controlPct = (int) Math.round(((controlSpeedSlot + 1) / 9.0D) * 100.0D);
		int heading = compassHeadingDegrees(session.proxyYaw());
		int pitch = pitchDegrees(session.proxyPitch());
		int bank = bankDegrees(session);
		double altitude = session.proxyPos().y;
		double verticalSpeed = velocity == null ? 0.0D : velocity.y;

		MutableComponent line = Component.empty();
		line.append(hudLabel("FWD ")).append(hudValue(percentText(forwardPct)));
		line.append(hudSeparator("  "));
		line.append(hudLabel("REV ")).append(hudValue(percentText(reversePct)));
		line.append(hudSeparator("  "));
		line.append(hudLabel("L ")).append(hudValue(percentText(leftPct)));
		line.append(hudSeparator("  "));
		line.append(hudLabel("R ")).append(hudValue(percentText(rightPct)));
		line.append(hudSeparator("   "));
		line.append(hudLabel("CTRL ")).append(hudValue(percentText(controlPct)));
		line.append(hudSeparator("   "));
		line.append(hudLabel("SPD ")).append(hudValue(percentText(speedPct)));
		line.append(hudSeparator("   "));
		line.append(hudLabel("ALT ")).append(hudValue("%.0f".formatted(altitude)));
		line.append(hudSeparator("   "));
		line.append(hudLabel("V/S ")).append(hudValue("%+.2f".formatted(verticalSpeed)));
		line.append(hudSeparator("   "));
		line.append(hudLabel("HDG ")).append(hudValue("%03d".formatted(heading)));
		line.append(hudSeparator("   "));
		line.append(hudLabel("PIT ")).append(hudValue("%+03d".formatted(pitch)));
		line.append(hudSeparator("   "));
		line.append(hudLabel("ROL ")).append(hudValue("%+03d".formatted(bank)));
		return line;
	}

	private static Component buildDroneHudWidget(DroneControlSession session, int controlSpeedSlot) {
		int xIndex = quantizeDrive(session.strafeDrive(), DroneFlightPhysics.MAX_STRAFE_DRIVE);
		int yIndex = quantizeDrive(-session.forwardDrive(), DroneFlightPhysics.MAX_FORWARD_DRIVE);
		int stickGlyph = DRONE_HUD_GLYPH_BASE + yIndex * DRONE_HUD_GRID_SIZE + xIndex;
		int speedGlyph = DRONE_HUD_SPEED_BAR_GLYPH_BASE + net.minecraft.util.Mth.clamp(controlSpeedSlot, 0, 8);
		String glyphText = new String(new char[]{(char) stickGlyph})
				+ DRONE_HUD_BAR_OVERLAP_GLYPH
				+ (char) speedGlyph;
		return Component.literal(glyphText)
				.withStyle(style -> style
						.withColor(DRONE_HUD_VALUE_COLOR)
						.withItalic(false)
						.withShadowColor(0x00000000));
	}

	private static void showDroneHudOverlay(ServerPlayer player, DroneControlSession session) {
		Component title = buildDroneHudOverlayTitle(session);
		PLAYER_DRONE_HUD_TITLES.put(player.getUUID(), title);
		if (ServerBossBarVisibilitySystem.refreshDroneHudOverlay(player)) {
			ServerStabilitySystem.clearSpacerHudOverlayTitle(player);
			hideDroneHudOverlayEvent(player);
			showDroneHudGlitchOverlay(player);
			return;
		}

		if (ServerStabilitySystem.setSpacerHudOverlayTitle(player, title)) {
			ServerBossBarVisibilitySystem.clearDroneHudOverlay(player);
			hideDroneHudOverlayEvent(player);
			showDroneHudGlitchOverlay(player);
			return;
		}

		ServerBossEvent hud = PLAYER_DRONE_HUDS.computeIfAbsent(player.getUUID(), id -> createDroneHudOverlay());
		hud.setName(title);
		hud.setProgress(0.0F);
		hud.setVisible(true);

		if (!hud.getPlayers().contains(player)) {
			hud.addPlayer(player);
			// The drone overlay must remain the first bossbar so its fixed title origin
			// stays at the intended crosshair height.
			ServerStabilitySystem.reorderHudBelowExternalBossBar(player);
			ServerBossBarVisibilitySystem.reorderTrackedBossBarsBelowReservedHud(player);
		}
		showDroneHudGlitchOverlay(player);
	}

	private static ServerBossEvent createDroneHudOverlay() {
		ServerBossEvent event = new ServerBossEvent(
				Component.empty(),
				// GREEN is the project's existing fully transparent bossbar slot.
				BossEvent.BossBarColor.GREEN,
				BossEvent.BossBarOverlay.PROGRESS
		);
		event.setDarkenScreen(false);
		event.setPlayBossMusic(false);
		event.setCreateWorldFog(false);
		event.setProgress(0.0F);
		return event;
	}

	private static void hideDroneHudOverlay(ServerPlayer player) {
		if (player == null) {
			return;
		}
		PLAYER_DRONE_HUD_TITLES.remove(player.getUUID());
		hideDroneHudGlitchOverlay(player);
		ServerStabilitySystem.clearSpacerHudOverlayTitle(player);
		ServerBossBarVisibilitySystem.clearDroneHudOverlay(player);
		hideDroneHudOverlayEvent(player);
	}

	private static void hideDroneHudOverlayEvent(ServerPlayer player) {
		ServerBossEvent hud = PLAYER_DRONE_HUDS.get(player.getUUID());
		if (hud == null) {
			return;
		}
		markDroneHudBossBarClosing(player, hud);
		hud.removePlayer(player);
		if (hud.getPlayers().isEmpty()) {
			PLAYER_DRONE_HUDS.remove(player.getUUID());
		}
	}

	/** Called by the bossbar packet bridge before a real bossbar is shown. */
	public static void suspendHudOverlayForExternalBossBar(ServerPlayer player) {
		hideDroneHudOverlayEvent(player);
	}

	/** Restores the standalone title after the last real bossbar has disappeared. */
	public static void restoreHudOverlayWithoutExternalBossBar(ServerPlayer player) {
		Component title = getHudOverlayTitle(player);
		if (player == null || title == null) {
			return;
		}
		if (ServerStabilitySystem.setSpacerHudOverlayTitle(player, title)) {
			return;
		}
		ServerBossEvent hud = PLAYER_DRONE_HUDS.computeIfAbsent(player.getUUID(), id -> createDroneHudOverlay());
		hud.setName(title);
		hud.setProgress(0.0F);
		hud.setVisible(true);
		if (!hud.getPlayers().contains(player)) {
			hud.addPlayer(player);
			ServerStabilitySystem.reorderHudBelowExternalBossBar(player);
		}
	}

	private static Component buildDroneHudOverlayTitle(DroneControlSession session) {
		return styleDroneHudOverlay(Component.literal(buildDroneHudCenterGlyphText(session)));
	}

	private static Component buildDroneHudGlitchBurstTitle(int frame) {
		int boundedFrame = net.minecraft.util.Mth.clamp(frame, 0, DRONE_HUD_GLITCH_BURST_FRAME_COUNT - 1);
		return buildDroneHudGlitchTitle(DRONE_HUD_GLITCH_BURST_GLYPH_BASE + boundedFrame * DRONE_HUD_GLITCH_TILES_PER_FRAME);
	}

	private static Component buildDroneHudGlitchIdleTitle(ServerPlayer player) {
		long gameTime = player == null || player.level() == null ? 0L : player.level().getGameTime();
		int frame = (int) Math.floorMod(
				gameTime / DRONE_HUD_GLITCH_IDLE_FRAME_TICKS,
				DRONE_HUD_GLITCH_IDLE_FRAME_COUNT
		);
		int firstTileGlyph = DRONE_HUD_GLITCH_IDLE_GLYPH_BASE + frame * DRONE_HUD_GLITCH_TILES_PER_FRAME;
		if (!isControlledDroneSubmerged(player)) {
			return buildDroneHudGlitchTitle(firstTileGlyph);
		}
		// Use the exact same high-density frames as the drone-destruction burst,
		// cycling them while submerged instead of showing the light idle noise.
		int burstFrame = (int) Math.floorMod(gameTime, DRONE_HUD_GLITCH_BURST_FRAME_COUNT);
		return buildDroneHudGlitchBurstTitle(burstFrame);
	}

	private static Component buildDroneHudGlitchTitle(int... firstTileGlyphs) {
		if (firstTileGlyphs == null || firstTileGlyphs.length == 0) {
			return Component.empty();
		}
		StringBuilder glyphs = new StringBuilder((DRONE_HUD_GLITCH_TILES_PER_FRAME + 2) * firstTileGlyphs.length);
		for (int frameIndex = 0; frameIndex < firstTileGlyphs.length; frameIndex++) {
			if (frameIndex > 0) {
				// Each tile frame ends one full screen-width to the right. Rewind before
				// drawing the next frame so both occupy the same HUD area.
				glyphs.append(DRONE_HUD_GLITCH_ROW_REWIND_GLYPH);
			}
			int firstTileGlyph = firstTileGlyphs[frameIndex];
			for (int tile = 0; tile < DRONE_HUD_GLITCH_TILES_PER_FRAME / 2; tile++) {
				glyphs.append((char) (firstTileGlyph + tile));
			}
			glyphs.append(DRONE_HUD_GLITCH_ROW_REWIND_GLYPH);
			for (int tile = DRONE_HUD_GLITCH_TILES_PER_FRAME / 2; tile < DRONE_HUD_GLITCH_TILES_PER_FRAME; tile++) {
				glyphs.append((char) (firstTileGlyph + tile));
			}
		}
		return styleDroneHudGlitch(Component.literal(glyphs.toString()));
	}

	private static boolean isControlledDroneSubmerged(ServerPlayer player) {
		Entity root = resolveControlledDroneRoot(player);
		return root != null
				&& root.level() instanceof ServerLevel level
				&& boxIntersectsFluid(level, root.getBoundingBox(), FluidTags.WATER);
	}

	private static Component styleDroneHudOverlay(Component component) {
		return component.copy().withStyle(style -> style
				.withColor(DRONE_HUD_VALUE_COLOR)
				.withItalic(false)
				.withShadowColor(0x00000000));
	}

	private static Component styleDroneHudGlitch(Component component) {
		return component.copy().withStyle(style -> style
				.withColor(DRONE_HUD_VALUE_COLOR)
				.withItalic(false)
				.withFont(DRONE_HUD_GLITCH_FONT)
				.withShadowColor(0x00000000));
	}

	private static void showDroneHudGlitchOverlay(ServerPlayer player) {
		if (player == null || player.connection == null) {
			return;
		}
		ServerBossEvent overlay = PLAYER_DRONE_GLITCH_OVERLAYS.computeIfAbsent(player.getUUID(), id -> createDroneHudOverlay());
		overlay.setName(buildDroneHudGlitchIdleTitle(player));
		overlay.setProgress(0.0F);
		overlay.setVisible(true);
		if (!overlay.getPlayers().contains(player)) {
			overlay.addPlayer(player);
			ServerStabilitySystem.reorderHudBelowExternalBossBar(player);
			ServerBossBarVisibilitySystem.reorderTrackedBossBarsBelowReservedHud(player);
		}
	}

	private static void hideDroneHudGlitchOverlay(ServerPlayer player) {
		if (player == null) {
			return;
		}
		UUID playerId = player.getUUID();
		ServerBossEvent overlay = PLAYER_DRONE_GLITCH_OVERLAYS.get(playerId);
		if (overlay != null) {
			markDroneHudBossBarClosing(player, overlay);
			overlay.removePlayer(player);
		}
		PLAYER_DRONE_GLITCH_OVERLAYS.remove(playerId, overlay);
	}

	private static void tickDroneHudGlitchOverlays(MinecraftServer server) {
		if (server == null || PLAYER_DRONE_GLITCH_OVERLAYS.isEmpty()) {
			return;
		}
		for (Map.Entry<UUID, ServerBossEvent> entry : new ArrayList<>(PLAYER_DRONE_GLITCH_OVERLAYS.entrySet())) {
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			ServerBossEvent overlay = entry.getValue();
			if (player == null || overlay == null || !overlay.getPlayers().contains(player)) {
				PLAYER_DRONE_GLITCH_OVERLAYS.remove(entry.getKey(), overlay);
				continue;
			}
			overlay.setName(buildDroneHudGlitchIdleTitle(player));
		}
	}

	private static void startDroneHudGlitchBurst(ServerPlayer player) {
		if (player == null || player.connection == null || player.level() == null) {
			return;
		}
		stopDroneHudGlitchBurst(player);
		ServerBossEvent burst = createDroneHudOverlay();
		// Register the bar before sending its ADD packet.  The bossbar bridge must
		// recognise it as HUD rather than briefly treating it as a real bossbar.
		PLAYER_DRONE_GLITCH_BURSTS.put(player.getUUID(), burst);
		burst.setName(buildDroneHudGlitchBurstTitle(0));
		burst.setVisible(true);
		burst.addPlayer(player);
		PLAYER_DRONE_GLITCH_BURST_START_TICKS.put(player.getUUID(), player.level().getGameTime());
		ServerStabilitySystem.reorderHudBelowExternalBossBar(player);
		ServerBossBarVisibilitySystem.reorderTrackedBossBarsBelowReservedHud(player);
	}

	private static void stopDroneHudGlitchBurst(ServerPlayer player) {
		if (player == null) {
			return;
		}
		UUID playerId = player.getUUID();
		PLAYER_DRONE_GLITCH_BURST_START_TICKS.remove(playerId);
		ServerBossEvent burst = PLAYER_DRONE_GLITCH_BURSTS.get(playerId);
		if (burst != null) {
			markDroneHudBossBarClosing(player, burst);
			burst.removePlayer(player);
		}
		PLAYER_DRONE_GLITCH_BURSTS.remove(playerId, burst);
	}

	private static void tickDroneHudGlitchBursts(MinecraftServer server) {
		if (server == null || PLAYER_DRONE_GLITCH_BURST_START_TICKS.isEmpty()) {
			return;
		}
		for (Map.Entry<UUID, Long> entry : new ArrayList<>(PLAYER_DRONE_GLITCH_BURST_START_TICKS.entrySet())) {
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			ServerBossEvent burst = PLAYER_DRONE_GLITCH_BURSTS.get(entry.getKey());
			if (player == null || burst == null || player.level() == null) {
				PLAYER_DRONE_GLITCH_BURST_START_TICKS.remove(entry.getKey());
				PLAYER_DRONE_GLITCH_BURSTS.remove(entry.getKey());
				continue;
			}
			long elapsed = player.level().getGameTime() - entry.getValue();
			if (elapsed >= DRONE_HUD_GLITCH_BURST_FRAME_COUNT) {
				stopDroneHudGlitchBurst(player);
				continue;
			}
			burst.setName(buildDroneHudGlitchBurstTitle((int) elapsed));
		}
	}

	public static Component getHudOverlayTitle(ServerPlayer player) {
		if (player == null) {
			return null;
		}
		Component title = PLAYER_DRONE_HUD_TITLES.get(player.getUUID());
		return title == null ? null : title.copy();
	}

	private static String buildDroneHudCenterGlyphText(DroneControlSession session) {
		int attitudeGlyph = DRONE_HUD_ATTITUDE_GLYPH_BASE + quantizeAttitudeMotion(session.proxyPitch());
		int attitudeLabelGlyph = DRONE_HUD_ATTITUDE_LABEL_GLYPH_BASE + quantizePitchLabel(session.proxyPitch());
		int bankGlyph = DRONE_HUD_BANK_GLYPH_BASE + quantizeBank(session);
		String glyphText = buildDroneHudHeadingGlyphText(session)
				+ DRONE_HUD_CENTER_GLYPH_REWIND
				+ (char) attitudeGlyph;
		return glyphText
				+ DRONE_HUD_CENTER_GLYPH_REWIND
				+ (char) attitudeLabelGlyph
				+ DRONE_HUD_CENTER_GLYPH_REWIND
				+ (char) bankGlyph;
	}

	private static String buildDroneHudHeadingGlyphText(DroneControlSession session) {
		int headingGlyph = DRONE_HUD_HEADING_GLYPH_BASE + quantizeHeading(session.proxyYaw());
		int headingDegrees = compassHeadingDegrees(session.proxyYaw());
		String digits = "%03d".formatted(headingDegrees);
		StringBuilder glyphs = new StringBuilder()
				.append((char) headingGlyph)
				.append(DRONE_HUD_HEADING_DIGIT_REWIND);
		for (int index = 0; index < digits.length(); index++) {
			glyphs.append((char) (DRONE_HUD_HEADING_DIGIT_GLYPH_BASE + (digits.charAt(index) - '0')));
		}
		return glyphs.append(DRONE_HUD_HEADING_DIGIT_RESTORE).toString();
	}

	public static boolean isHudBossBar(ServerPlayer player, UUID bossBarId) {
		if (player == null || bossBarId == null) {
			return false;
		}
		Set<UUID> closingBars = CLOSING_DRONE_HUD_BOSS_BARS.get(player.getUUID());
		if (closingBars != null && closingBars.contains(bossBarId)) {
			return true;
		}
		ServerBossEvent hud = PLAYER_DRONE_HUDS.get(player.getUUID());
		if (hud != null && hud.getId().equals(bossBarId)) {
			return true;
		}
		ServerBossEvent overlay = PLAYER_DRONE_GLITCH_OVERLAYS.get(player.getUUID());
		if (overlay != null && overlay.getId().equals(bossBarId)) {
			return true;
		}
		ServerBossEvent burst = PLAYER_DRONE_GLITCH_BURSTS.get(player.getUUID());
		return burst != null && burst.getId().equals(bossBarId);
	}

	/**
	 * Closing is a one-way barrier. The remove packet for this exact id is still
	 * allowed through, while any later ADD or UPDATE is stale and must be ignored.
	 */
	public static boolean isClosingHudBossBar(ServerPlayer player, UUID bossBarId) {
		if (player == null || bossBarId == null) {
			return false;
		}
		Set<UUID> closingBars = CLOSING_DRONE_HUD_BOSS_BARS.get(player.getUUID());
		return closingBars != null && closingBars.contains(bossBarId);
	}

	private static void markDroneHudBossBarClosing(ServerPlayer player, ServerBossEvent bossBar) {
		if (player == null || bossBar == null) {
			return;
		}
		CLOSING_DRONE_HUD_BOSS_BARS
				.computeIfAbsent(player.getUUID(), ignored -> new HashSet<>())
				.add(bossBar.getId());
	}

	private static String buildDroneHudSnapshot(DroneControlSession session, Vec3 velocity, int controlSpeedSlot) {
		int forwardPct = drivePercent(Math.max(0.0D, session.forwardDrive()), DroneFlightPhysics.MAX_FORWARD_DRIVE);
		int reversePct = drivePercent(Math.max(0.0D, -session.forwardDrive()), DroneFlightPhysics.MAX_FORWARD_DRIVE);
		int leftPct = drivePercent(Math.max(0.0D, -session.strafeDrive()), DroneFlightPhysics.MAX_STRAFE_DRIVE);
		int rightPct = drivePercent(Math.max(0.0D, session.strafeDrive()), DroneFlightPhysics.MAX_STRAFE_DRIVE);
		int speedPct = (int) Math.round(net.minecraft.util.Mth.clamp(
				(velocity == null ? 0.0D : velocity.length()) / DroneFlightPhysics.MAX_COMBINED_SPEED,
				0.0D,
				1.0D
		) * 100.0D);
		return forwardPct + "|" + reversePct + "|" + leftPct + "|" + rightPct + "|" + speedPct + "|" + controlSpeedSlot
				+ "|" + quantizeHeading(session.proxyYaw())
				+ "|" + quantizeAttitudeMotion(session.proxyPitch())
				+ "|" + quantizePitchLabel(session.proxyPitch())
				+ "|" + quantizeBank(session);
	}

	private static int getControlSpeedSlot(ServerPlayer player) {
		if (player == null) {
			return 0;
		}
		return net.minecraft.util.Mth.clamp(player.getInventory().getSelectedSlot(), 0, 8);
	}

	private static double getControlDriveStep(int controlSpeedSlot) {
		return DroneControlTuning.driveStepForSlot(controlSpeedSlot);
	}

	private static int drivePercent(double value, double maxValue) {
		if (maxValue <= 1.0E-6D) {
			return 0;
		}
		return (int) Math.round(net.minecraft.util.Mth.clamp(value / maxValue, 0.0D, 1.0D) * 100.0D);
	}

	private static int quantizeDrive(double value, double maxMagnitude) {
		if (maxMagnitude <= 1.0E-6D) {
			return DRONE_HUD_GRID_SIZE / 2;
		}
		double normalized = net.minecraft.util.Mth.clamp(value / maxMagnitude, -1.0D, 1.0D);
		return net.minecraft.util.Mth.clamp(
				(int) Math.round((normalized + 1.0D) * 0.5D * (DRONE_HUD_GRID_SIZE - 1)),
				0,
				DRONE_HUD_GRID_SIZE - 1
		);
	}

	private static int quantizeHeading(float minecraftYaw) {
		float phaseWithinTenDegrees = net.minecraft.util.Mth.positiveModulo(compassHeadingDegreesFloat(minecraftYaw), 10.0F);
		return net.minecraft.util.Mth.clamp(
				(int) Math.floor(phaseWithinTenDegrees * DRONE_HUD_HEADING_FRAME_COUNT / 10.0F),
				0,
				DRONE_HUD_HEADING_FRAME_COUNT - 1
		);
	}

	private static int quantizeAttitudeMotion(float pitch) {
		float phaseWithinTwentyDegrees = net.minecraft.util.Mth.positiveModulo(pitchDegrees(pitch) + 90.0F, 20.0F);
		int motionFrame = net.minecraft.util.Mth.clamp(
				Math.round(phaseWithinTwentyDegrees / 20.0F * (DRONE_HUD_ATTITUDE_FRAME_COUNT - 1)),
				0,
				DRONE_HUD_ATTITUDE_FRAME_COUNT - 1
		);
		return DRONE_HUD_ATTITUDE_FRAME_COUNT - 1 - motionFrame;
	}

	private static int quantizePitchLabel(float pitch) {
		float clampedPitch = pitchDegrees(pitch);
		return net.minecraft.util.Mth.clamp(
				Math.round((clampedPitch + 90.0F) / 180.0F * (DRONE_HUD_ATTITUDE_LABEL_FRAME_COUNT - 1)),
				0,
				DRONE_HUD_ATTITUDE_LABEL_FRAME_COUNT - 1
		);
	}

	private static int quantizeBank(DroneControlSession session) {
		int bank = bankDegrees(session);
		return net.minecraft.util.Mth.clamp(
				Math.round((bank + DRONE_MAX_TILT_DEGREES) / (DRONE_MAX_TILT_DEGREES * 2.0F) * (DRONE_HUD_BANK_FRAME_COUNT - 1)),
				0,
				DRONE_HUD_BANK_FRAME_COUNT - 1
		);
	}

	private static int compassHeadingDegrees(float minecraftYaw) {
		return Math.floorMod(Math.round(compassHeadingDegreesFloat(minecraftYaw)), 360);
	}

	private static float compassHeadingDegreesFloat(float minecraftYaw) {
		return net.minecraft.util.Mth.positiveModulo(180.0F - minecraftYaw, 360.0F);
	}

	private static int pitchDegrees(float pitch) {
		return Math.round(net.minecraft.util.Mth.clamp(pitch, -90.0F, 90.0F));
	}

	private static int bankDegrees(DroneControlSession session) {
		if (session == null || DroneFlightPhysics.MAX_STRAFE_DRIVE <= 1.0E-6D) {
			return 0;
		}
		double normalizedStrafe = net.minecraft.util.Mth.clamp(
				session.displayStrafeDrive() / DroneFlightPhysics.MAX_STRAFE_DRIVE,
				-1.0D,
				1.0D
		);
		return Math.round((float) (normalizedStrafe * DRONE_MAX_TILT_DEGREES));
	}

	private static String percentText(int value) {
		return "%03d%%".formatted(Math.max(0, Math.min(999, value)));
	}

	private static MutableComponent hudLabel(String text) {
		return Component.literal(text).withStyle(style -> style.withColor(DRONE_HUD_LABEL_COLOR).withItalic(false));
	}

	private static MutableComponent hudValue(String text) {
		return Component.literal(text).withStyle(style -> style.withColor(DRONE_HUD_VALUE_COLOR).withItalic(false));
	}

	private static MutableComponent hudSeparator(String text) {
		return Component.literal(text).withStyle(style -> style.withColor(DRONE_HUD_DIM_COLOR).withItalic(false));
	}

	private static MutableComponent hudSegment(String text, int color) {
		return Component.literal(text).withStyle(style -> style.withColor(color).withItalic(false));
	}

	private static boolean startControlling(ServerPlayer player, Entity root) {
		if (player == null || root == null || !root.isAlive() || !(root.level() instanceof ServerLevel droneLevel)) {
			return false;
		}
		PENDING_CONTROL_STARTS.remove(player.getUUID());
		POST_CONTROL_CLIENT_RESYNC_UNTIL_TICK.remove(player.getUUID());
		UUID currentControllerId = resolveAuthoritativeDroneControllerId(root.getUUID());
		if (currentControllerId != null && !Objects.equals(currentControllerId, player.getUUID())) {
			Lg2Messages.actionBar(player, "message.lg2.drone.busy");
			return false;
		}

		stopDroneHudGlitchBurst(player);
		stopControlling(player, false);
		POST_CONTROL_MOVE_SUPPRESSED_UNTIL_TICK.remove(player.getUUID());
		CameraVideoRecordingSystem.stopForDroneControl(player);
		MapImageRenderSystem.cancelRender(player.getUUID());
		RendererBotCameraSystem.stopCameraHotbarWarmupForPlayer(player.getUUID());
		ServerRaceSystem.suspendCopperManJetpackForDrone(player);
		player.closeContainer();
		UncontrolledDroneState previousUncontrolledState = UNCONTROLLED_DRONES.remove(root.getUUID());
		Vec3 dronePos = root.position();
		float droneYaw = root.getYRot();
		float dronePitch = root.getXRot();
		Vec3 fallbackVelocity = finiteVecOr(root.getDeltaMovement(), Vec3.ZERO);
		Vec3 incomingVelocity = finiteVecOr(
				previousUncontrolledState == null ? fallbackVelocity : previousUncontrolledState.velocity(),
				fallbackVelocity
		);
		prepareControlledDroneBody(root);
		syncDroneDisplayLayers(root);
		root.setYRot(droneYaw);
		root.setXRot(dronePitch);
		root.setDeltaMovement(incomingVelocity);
		droneLevel.getChunkAt(root.blockPosition());
		setHotbarVisualHidden(player, true);
		syncDroneCameraAnchor(root, incomingVelocity);

		DroneControlSession session = new DroneControlSession(
				root.getUUID(),
				droneLevel.dimension()
		);
		session.setProxyPos(dronePos);
		session.setControlYaw(droneYaw);
		session.setControlPitch(dronePitch);
		session.setProxyYaw(droneYaw);
		session.setProxyPitch(dronePitch);
		session.setIntendedVelocity(incomingVelocity);
		session.setVelocity(incomingVelocity);
		session.refreshKnownDroneLocation(root);
		session.setTurretInputSuppressedUntilTick(droneLevel.getGameTime() + DRONE_TURRET_CONTROL_START_SUPPRESS_TICKS);
		session.suppressAutoAimSelectionUntil(droneLevel.getGameTime() + 1L);
		session.suppressStartupRotationUntil(droneLevel.getGameTime() + CONTROLLED_DRONE_START_ROTATION_SUPPRESSION_TICKS);
		session.suppressStartupPositionUntil(droneLevel.getGameTime() + CONTROLLED_DRONE_START_POSITION_SUPPRESSION_TICKS);
		DroneAutoAimTarget restoredAutoAimTarget = resolvePersistedDroneAutoAimTarget(root);
		if (restoredAutoAimTarget == null && previousUncontrolledState != null) {
			restoredAutoAimTarget = previousUncontrolledState.autoAimTarget();
			setPersistedDroneAutoAimTarget(root, restoredAutoAimTarget);
		}
		session.setAutoAimTarget(restoredAutoAimTarget);
		if (previousUncontrolledState != null) {
			restoreControlledDrivesFromUncontrolledState(session, previousUncontrolledState, droneYaw, dronePitch);
		} else {
			seedControlledDrivesFromWorldVelocity(session, incomingVelocity, droneYaw, dronePitch);
		}
		root.setDeltaMovement(finiteVecOr(session.velocity(), incomingVelocity));
		syncDroneCameraAnchor(root, session.velocity());
		syncDroneDisplay(root, droneYaw, dronePitch, session.displayForwardDrive(), session.displayStrafeDrive(), true);
		VISUALLY_CONTROLLED_PLAYERS.add(player.getUUID());
		ACTIVE_SESSIONS.put(player.getUUID(), session);
		INPUTS.put(player.getUUID(), DroneInputState.EMPTY);
		CONTROLLERS_BY_DRONE.put(root.getUUID(), player.getUUID());
		updateDroneChunkTickets(player.level().getServer());
		syncControlledOperatorAutoAimInteractionRange(player, hasDroneAutoAimModule(root));
		if (session.autoAimTarget() != null) {
			showControlledAutoAimTarget(player, session, session.autoAimTarget());
		}
		syncControlledOperatorBodyMirror(player, true);
		syncControlledOperatorView(player, session, root, true, true);
		syncControlledOperatorNightVision(player, session, root);
		notifyDroneNetworkChanged(root);
		updateDroneHud(player, session, true);
		Lg2Messages.actionBar(player, "message.lg2.drone.control_started");
		return true;
	}

	private static void stopControlling(ServerPlayer player, boolean notify) {
		stopControlling(player, notify, true);
	}

	private static void stopControlling(ServerPlayer player, boolean notify, boolean releaseDrone) {
		if (player == null) {
			return;
		}
		DroneControlSession session = ACTIVE_SESSIONS.remove(player.getUUID());
		if (session != null) {
			markPostControlDroneTeleportAcks(player);
		}
		INPUTS.remove(player.getUUID());
		stopControlledOperatorAudio(player.getUUID());
		if (session == null) {
			clearControlledOperatorTransientState(player, null);
			clearControlledOperatorPassengerAttachment(player);
			removeControlledOperatorBodyMirror(player);
			if (VISUALLY_CONTROLLED_PLAYERS.contains(player.getUUID())) {
				restoreOrphanedControlledOperator(player);
			} else {
				markPostControlMoveSuppressedForPlayer(player);
				restoreControlledOperatorClientState(player);
				schedulePostControlClientResync(player);
			}
			return;
		}
		CONTROLLERS_BY_DRONE.remove(session.droneUuid(), player.getUUID());
		MinecraftServer server = player.level().getServer();
		Entity root = server == null ? null : findDroneRoot(server, session.droneDimension(), session.droneUuid());
		// A destroyed drone is discarded immediately after this method returns.  Do not
		// send its layer/passenger packets (or respawn its visual entities) to the
		// operator in that short interval: the subsequent entity-removal packets can
		// otherwise race them on the client and leave its entity tracker inconsistent.
		boolean droneRemainsAfterControlStop = releaseDrone && root != null && root.isAlive();
		DroneAutoAimTarget releasedAutoAimTarget = session.autoAimTarget();

		clearControlledOperatorTransientState(player, session);
		clearControlledOperatorPassengerAttachment(player);
		if (droneRemainsAfterControlStop) {
			clearControlledOperatorDroneLayerAttachment(player, root);
		}
		removeControlledOperatorBodyMirror(player);
		clearControlledOperatorMovementState(player);
		markPostControlMoveSuppressedForPlayer(player);
		detachAnyDronePassengersFromController(player);
		if (releaseDrone && root != null) {
			boolean releaseDriveIdle = hasIdleReleasedDroneDrive(session);
			Vec3 currentRootPos = root.position();
			Vec3 proxyPos = finiteVecOr(session.proxyPos(), currentRootPos);
			if (!isPlausibleControlledDroneMove(proxyPos.subtract(currentRootPos), session.intendedVelocity())) {
				proxyPos = currentRootPos;
				session.setProxyPos(currentRootPos);
			}
			root.setPos(proxyPos.x, proxyPos.y, proxyPos.z);
			root.setBoundingBox(droneBoxAt(root.position()));
			root.setYRot(session.proxyYaw());
			root.setXRot(session.proxyPitch());
			Vec3 releasedVelocity = releaseDriveIdle ? Vec3.ZERO : finiteVecOr(session.velocity(), Vec3.ZERO);
			if (!isPlausibleControlledDroneMove(releasedVelocity, session.intendedVelocity())) {
				releasedVelocity = Vec3.ZERO;
			}
			root.noPhysics = false;
			root.setDeltaMovement(releasedVelocity);
			root.hurtMarked = true;
			UncontrolledDroneState uncontrolledState = new UncontrolledDroneState(
					root.getUUID(),
					((ServerLevel) root.level()).dimension(),
					releasedVelocity,
					root.getYRot(),
					root.getXRot(),
					releasedAutoAimTarget,
					root.level().getGameTime() + UNCONTROLLED_DRONE_RELEASE_GLIDE_TICKS,
					releaseDriveIdle ? 0.0D : session.forwardDrive(),
					releaseDriveIdle ? 0.0D : session.strafeDrive(),
					releaseDriveIdle ? 0.0D : session.displayForwardDrive(),
					releaseDriveIdle ? 0.0D : session.displayStrafeDrive(),
					!releaseDriveIdle
			);
			uncontrolledState.setLastPosition(root.position());
			uncontrolledState.setSurfaceWear(session.surfaceWear());
			uncontrolledState.setLastSurfaceWearContactTick(session.lastSurfaceWearContactTick());
			UNCONTROLLED_DRONES.put(root.getUUID(), uncontrolledState);
			persistDroneRestartRecoveryState(root, uncontrolledState);
			syncDroneDisplayLayers(root);
			syncDroneDisplay(root, root.getYRot(), root.getXRot(), 0.0D, 0.0D, true);
			syncDroneCameraAnchor(root, releasedVelocity);
			notifyDroneNetworkChanged(root);
		} else {
			NEXT_DRONE_SOUND_TICK.remove(session.droneUuid());
		}
		clearDroneHud(player, session, true);
		if (notify) {
			startDroneHudGlitchBurst(player);
		}
		restoreControlledOperatorClientState(player);
		if (droneRemainsAfterControlStop) {
			rebuildReleasedDroneVisualEntitiesForOperator(player, root);
		}
		schedulePostControlClientResync(player);
		VISUALLY_CONTROLLED_PLAYERS.remove(player.getUUID());

		ServerRaceSystem.resumeCopperManJetpackAfterDrone(player);

		if (notify) {
			Lg2Messages.actionBar(player, "message.lg2.drone.control_stopped");
		}
	}

	private static boolean isDroneStationaryForBreakPickup(Entity root) {
		if (root == null || !root.isAlive()) {
			return false;
		}
		Vec3 velocity = finiteVecOr(root.getDeltaMovement(), Vec3.ZERO);
		UncontrolledDroneState uncontrolledState = UNCONTROLLED_DRONES.get(root.getUUID());
		if (uncontrolledState != null && uncontrolledState.velocity() != null) {
			Vec3 uncontrolledVelocity = uncontrolledState.velocity();
			if (uncontrolledVelocity.lengthSqr() > velocity.lengthSqr()) {
				velocity = uncontrolledVelocity;
			}
		}
		double horizontalSpeedSq = velocity.x * velocity.x + velocity.z * velocity.z;
		return horizontalSpeedSq <= DRONE_STATIONARY_BREAK_HORIZONTAL_SPEED_SQR
				&& Math.abs(velocity.y) <= DRONE_STATIONARY_BREAK_VERTICAL_SPEED;
	}

	private static boolean hasIdleReleasedDroneDrive(DroneControlSession session) {
		return session != null
				&& Math.abs(session.forwardDrive()) <= DRONE_RELEASE_IDLE_DRIVE_EPSILON
				&& Math.abs(session.strafeDrive()) <= DRONE_RELEASE_IDLE_DRIVE_EPSILON;
	}

	private static void dropDroneBreakRecoveryItems(Entity root, ServerLevel level) {
		if (root == null || level == null) {
			return;
		}
		DroneItem.DroneType type = resolveDroneType(root);
		if (type == DroneItem.DroneType.KAMIKAZE) {
			spawnDroneRecoveryItem(root, level, Items.TNT);
		} else if (type == DroneItem.DroneType.COMBAT) {
			spawnDroneRecoveryItem(root, level, Items.CROSSBOW);
		}
		int kamikazePower = resolveDroneKamikazePower(root);
		if (kamikazePower > DRONE_KAMIKAZE_NO_POWER) {
			spawnDroneRecoveryItem(root, level, new ItemStack(Items.TNT, kamikazePower));
		}
		if (hasDroneNightVisionModule(root)) {
			spawnDroneRecoveryItem(root, level, Items.LIME_STAINED_GLASS);
		}
		if (hasDroneAutoAimModule(root)) {
			spawnDroneRecoveryItem(root, level, Items.SCULK_SENSOR);
		}
		if (hasDroneMicrophoneModule(root)) {
			spawnDroneRecoveryItem(root, level, ModBlocks.MICROPHONE_ITEM);
		}
		DyeColor paintColor = resolveDronePaintColor(root);
		if (paintColor == null) {
			return;
		}
		Item dyeItem = dyeItemForColor(paintColor);
		spawnDroneRecoveryItem(root, level, dyeItem);
	}

	private static void spawnDroneRecoveryItem(Entity root, ServerLevel level, Item item) {
		if (item == null || item == Items.AIR) {
			return;
		}
		spawnDroneRecoveryItem(root, level, new ItemStack(item));
	}

	private static void spawnDroneRecoveryItem(Entity root, ServerLevel level, ItemStack stack) {
		if (root == null || level == null || stack == null || stack.isEmpty()) {
			return;
		}
		root.spawnAtLocation(level, stack.copy());
	}

	private static void discardOwnedDroneInternalEntities(ServerLevel level, Entity root) {
		if (level == null || root == null) {
			return;
		}
		String displayOwnerTag = DRONE_DISPLAY_OWNER_TAG_PREFIX + root.getUUID();
		String cameraOwnerTag = DRONE_CAMERA_OWNER_TAG_PREFIX + root.getUUID();
		AABB searchBox = root.getBoundingBox().inflate(DRONE_DISPLAY_VIEW_RANGE);
		for (Entity candidate : level.getEntities(root, searchBox, entity ->
				entity != null
						&& entity.isAlive()
						&& (entity.getTags().contains(displayOwnerTag) || entity.getTags().contains(cameraOwnerTag)))) {
			candidate.discard();
		}
	}

	private static void destroyDrone(Entity root, ServerPlayer breaker, boolean dropItem) {
		if (root == null || !root.isAlive() || !(root.level() instanceof ServerLevel level)) {
			return;
		}
		boolean kamikazeDrone = isKamikazeDrone(root);
		int kamikazePower = resolveDroneKamikazePower(root);
		boolean canDropWholeDrone = dropItem && isDroneStationaryForBreakPickup(root);
		playDroneBreakEffects(level, droneCameraOrigin(root), root.getDeltaMovement());
		UNCONTROLLED_DRONES.remove(root.getUUID());
		NEXT_DRONE_SOUND_TICK.remove(root.getUUID());
		NEXT_DRONE_ARM_ALLOWED_TICK.remove(root.getUUID());
		NEXT_DRONE_TURRET_FIRE_TICK.remove(root.getUUID());
		DISPLAY_WOBBLE_BY_DRONE.remove(root.getUUID());
		AUTO_AIM_DISPLAY_ANIMATIONS.remove(root.getUUID());
		DRONE_ENVIRONMENT_DAMAGE.remove(root.getUUID());
		SCREEN_STREAM_DRONE_LOAD_STATES.remove(root.getUUID());
		BluetoothLinkSystem.removeDroneEndpoint(level, root.getUUID(), root.blockPosition(), root.position().add(0.0D, 0.25D, 0.0D));
		stopAllDroneControllers(root, true);
		CONTROLLERS_BY_DRONE.remove(root.getUUID());
		discardOwnedDroneInternalEntities(level, root);
		for (Display.ItemDisplay display : findDroneDisplayLayers(root)) {
			display.discard();
		}
		DISPLAYS_BY_DRONE.remove(root.getUUID());
		DISPLAY_LAYERS_BY_DRONE.remove(root.getUUID());
		UUID cameraAnchorId = CAMERA_ANCHORS_BY_DRONE.remove(root.getUUID());
		Entity cameraAnchor = cameraAnchorId == null ? findDroneCameraAnchor(root) : findEntity(level.getServer(), level.dimension(), cameraAnchorId);
		if (cameraAnchor != null) {
			cameraAnchor.discard();
		}
		for (Entity passenger : new ArrayList<>(root.getPassengers())) {
			passenger.discard();
		}
		dropDroneTurretInventory(root);
		if (canDropWholeDrone) {
			root.spawnAtLocation(level, buildDroneDropStack(root));
			dropDroneBreakRecoveryItems(root, level);
		}
		if (kamikazeDrone && !canDropWholeDrone) {
			detonateKamikazeDrone(level, droneCameraOrigin(root), root.getDeltaMovement(), kamikazePower);
		}
		root.discard();
	}

	private static void igniteDroneCrashSite(Entity root, Vec3 crashMovement) {
		if (root == null || !(root.level() instanceof ServerLevel level)) {
			return;
		}
		Vec3 origin = droneCameraOrigin(root);
		Vec3 rootPos = root.position();
		Vec3 movement = finiteVecOr(crashMovement, Vec3.ZERO);
		LinkedHashSet<BlockPos> candidates = new LinkedHashSet<>();
		BlockPos rootBlock = BlockPos.containing(rootPos);
		BlockPos cameraBlock = BlockPos.containing(origin);
		candidates.add(rootBlock);
		candidates.add(cameraBlock);
		candidates.add(rootBlock.above());
		candidates.add(cameraBlock.above());
		candidates.add(rootBlock.below());
		candidates.add(cameraBlock.below());
		if (movement.lengthSqr() > 1.0E-6D) {
			Vec3 normal = movement.normalize();
			candidates.add(BlockPos.containing(rootPos.subtract(normal.scale(0.28D))));
			candidates.add(BlockPos.containing(origin.subtract(normal.scale(0.28D))));
			candidates.add(BlockPos.containing(rootPos.add(normal.scale(0.18D))));
		}
		for (Direction direction : Direction.values()) {
			candidates.add(rootBlock.relative(direction));
			candidates.add(cameraBlock.relative(direction));
		}
		for (BlockPos candidate : candidates) {
			if (tryPlaceDroneCrashFire(level, candidate)) {
				return;
			}
			if (tryPlaceDroneCrashFire(level, candidate.above())) {
				return;
			}
		}
	}

	private static boolean tryPlaceDroneCrashFire(ServerLevel level, BlockPos pos) {
		if (level == null || pos == null || !level.isEmptyBlock(pos)) {
			return false;
		}
		BlockState fire = BaseFireBlock.getState(level, pos);
		if (!fire.canSurvive(level, pos)) {
			return false;
		}
		level.setBlockAndUpdate(pos, fire);
		return true;
	}

	private static void stopAllDroneControllers(Entity root, boolean notify) {
		if (root == null) {
			return;
		}
		MinecraftServer server = root.level() == null ? null : root.level().getServer();
		if (server == null || server.getPlayerList() == null) {
			return;
		}

		UUID rootUuid = root.getUUID();
		Set<UUID> controllerIds = collectDroneControllerIds(rootUuid);

		for (UUID controllerId : controllerIds) {
			if (controllerId == null) {
				continue;
			}
			ServerPlayer controller = server.getPlayerList().getPlayer(controllerId);
			if (controller == null) {
				continue;
			}
			stopControlling(controller, notify, false);
		}
		CONTROLLERS_BY_DRONE.remove(rootUuid);

		if (server != null) {
			recoverOrphanedControlledOperators(server);
			recoverPlayersWithStaleDronePassenger(server);
		}
	}

	private static void playDroneBreakEffects(ServerLevel level, Vec3 origin, Vec3 velocity) {
		if (level == null || origin == null) {
			return;
		}
		SoundEvent breakSound = resolveVanillaSoundEvent(DRONE_BREAK_SOUND_ID, SoundEvents.GENERIC_EXPLODE.value());
		float pitch = 0.86F + level.random.nextFloat() * 0.10F;
		level.playSound(
				null,
				origin.x,
				origin.y,
				origin.z,
				breakSound,
				SoundSource.PLAYERS,
				1.05F,
				pitch
		);

		double motionSpread = velocity == null ? 0.05D : net.minecraft.util.Mth.clamp(velocity.length() * 0.35D, 0.05D, 0.35D);
		level.sendParticles(ParticleTypes.EXPLOSION, origin.x, origin.y + DRONE_HEIGHT * 0.28D, origin.z, 3, 0.10D, 0.07D, 0.10D, 0.01D);
		level.sendParticles(ParticleTypes.SMOKE, origin.x, origin.y + DRONE_HEIGHT * 0.12D, origin.z, 5, 0.13D, 0.10D, 0.13D, motionSpread * 0.25D);
		level.sendParticles(ParticleTypes.FLAME, origin.x, origin.y + DRONE_HEIGHT * 0.16D, origin.z, 2, 0.07D, 0.05D, 0.07D, 0.01D);
	}

	private static String resolveRequiredUpgradeForDroneItem(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return null;
		}
		if (stack.getItem() == ModItems.DRONE) {
			return DroneItem.getDroneType(stack) == DroneItem.DroneType.KAMIKAZE ? IT_DRONE_KAMIKAZE : IT_DRONE_SCOUT;
		}
		return null;
	}

	private static InteractionResult tryTuneDrone(ServerPlayer player, Entity root, ItemStack heldStack) {
		if (player == null || root == null || !(root.level() instanceof ServerLevel level)) {
			return InteractionResult.PASS;
		}

		long now = level.getGameTime();
		long nextAllowedTick = NEXT_DRONE_ARM_ALLOWED_TICK.getOrDefault(root.getUUID(), Long.MIN_VALUE);
		if (now < nextAllowedTick) {
			return InteractionResult.CONSUME;
		}
		if (heldStack == null || heldStack.isEmpty()) {
			return InteractionResult.PASS;
		}

		if (heldStack.is(Items.TNT)) {
			NEXT_DRONE_ARM_ALLOWED_TICK.put(root.getUUID(), now + 1L);
			return resolveDroneType(root) == DroneItem.DroneType.KAMIKAZE
					? tryArmDroneWithTnt(player, root, heldStack)
					: tryInstallDroneType(player, root, heldStack, DroneItem.DroneType.KAMIKAZE, Items.TNT);
		}
		if (heldStack.is(Items.CROSSBOW)) {
			NEXT_DRONE_ARM_ALLOWED_TICK.put(root.getUUID(), now + 1L);
			return tryInstallDroneType(player, root, heldStack, DroneItem.DroneType.COMBAT, Items.CROSSBOW);
		}
		if (heldStack.is(Items.LIME_STAINED_GLASS)) {
			NEXT_DRONE_ARM_ALLOWED_TICK.put(root.getUUID(), now + 1L);
			return tryInstallNightVisionModule(player, root, heldStack);
		}
		if (heldStack.is(Items.SCULK_SENSOR)) {
			NEXT_DRONE_ARM_ALLOWED_TICK.put(root.getUUID(), now + 1L);
			return tryInstallAutoAimModule(player, root, heldStack);
		}
		if (heldStack.is(ModBlocks.MICROPHONE_ITEM)) {
			NEXT_DRONE_ARM_ALLOWED_TICK.put(root.getUUID(), now + 1L);
			return tryInstallMicrophoneModule(player, root, heldStack);
		}
		if (heldStack.getItem() instanceof DyeItem dyeItem) {
			NEXT_DRONE_ARM_ALLOWED_TICK.put(root.getUUID(), now + 1L);
			return tryPaintDrone(player, root, heldStack, dyeItem.getDyeColor());
		}
		return InteractionResult.PASS;
	}

	private static boolean requireDroneTuningUpgrade(ServerPlayer player, String upgradeId) {
		if (player == null || upgradeId == null || upgradeId.isBlank() || ServerUpgradeUiSystem.hasUpgrade(player, upgradeId)) {
			return true;
		}
		String upgradeName = ServerUpgradeUiSystem.getUpgradeDisplayName(player, upgradeId);
		String resolvedName = upgradeName == null || upgradeName.isBlank() ? "drone tuning module" : upgradeName;
		Lg2Messages.actionBar(player, 0xFF6B6B, "message.lg2.drone.tuning_upgrade_locked", resolvedName);
		return false;
	}

	private static InteractionResult tryArmDroneWithTnt(ServerPlayer player, Entity root, ItemStack heldStack) {
		if (player == null || root == null || !(root.level() instanceof ServerLevel level) || heldStack == null || !heldStack.is(Items.TNT)) {
			return InteractionResult.PASS;
		}
		if (resolveDroneType(root) != DroneItem.DroneType.KAMIKAZE) {
			return InteractionResult.PASS;
		}
		if (!requireDroneTuningUpgrade(player, IT_DRONE_KAMIKAZE)) {
			return InteractionResult.FAIL;
		}

		int currentPower = resolveDroneKamikazePower(root);
		if (currentPower >= DRONE_KAMIKAZE_MAX_POWER) {
			return InteractionResult.PASS;
		}

		int newPower = net.minecraft.util.Mth.clamp(currentPower + 1, DRONE_KAMIKAZE_MIN_POWER, DRONE_KAMIKAZE_MAX_POWER);
		setDroneKamikazePower(root, newPower);
		applyDroneTuningSuccess(root);
		playDroneKamikazeInsertFeedback(level, droneCameraOrigin(root), newPower);

		if (!player.getAbilities().instabuild) {
			heldStack.shrink(1);
		}
		return InteractionResult.SUCCESS;
	}

	private static InteractionResult tryInstallDroneType(
			ServerPlayer player,
			Entity root,
			ItemStack heldStack,
			DroneItem.DroneType targetType,
			Item installedItem
	) {
		if (player == null || root == null || heldStack == null || targetType == null || installedItem == null) {
			return InteractionResult.PASS;
		}

		DroneItem.DroneType currentType = resolveDroneType(root);
		if (currentType == targetType) {
			return InteractionResult.PASS;
		}
		if (currentType == DroneItem.DroneType.KAMIKAZE && resolveDroneKamikazePower(root) > 0) {
			return InteractionResult.PASS;
		}
		String requiredUpgradeId = switch (targetType) {
			case KAMIKAZE -> IT_DRONE_KAMIKAZE;
			case COMBAT -> IT_DRONE_COMBAT;
			default -> null;
		};
		if (!requireDroneTuningUpgrade(player, requiredUpgradeId)) {
			return InteractionResult.FAIL;
		}

		Item returnedTypeItem = switch (currentType) {
			case KAMIKAZE -> Items.TNT;
			case COMBAT -> Items.CROSSBOW;
			default -> null;
		};
		setDroneType(root, targetType);
		if (targetType != DroneItem.DroneType.KAMIKAZE) {
			setDroneKamikazePower(root, DRONE_KAMIKAZE_NO_POWER);
		}
		applyDroneTuningSuccess(root);

		if (!player.getAbilities().instabuild) {
			heldStack.shrink(1);
			giveOrDropTuningItem(player, root, returnedTypeItem);
		}
		return InteractionResult.SUCCESS;
	}

	private static InteractionResult tryInstallNightVisionModule(ServerPlayer player, Entity root, ItemStack heldStack) {
		if (!requireDroneTuningUpgrade(player, IT_DRONE_NIGHT_VISION)) {
			return InteractionResult.FAIL;
		}
		if (hasDroneNightVisionModule(root)) {
			return InteractionResult.PASS;
		}
		setDroneNightVisionModule(root, true);
		applyDroneTuningSuccess(root);
		if (!player.getAbilities().instabuild) {
			heldStack.shrink(1);
		}
		return InteractionResult.SUCCESS;
	}

	private static InteractionResult tryInstallAutoAimModule(ServerPlayer player, Entity root, ItemStack heldStack) {
		if (!requireDroneTuningUpgrade(player, IT_DRONE_AUTO_AIM)) {
			return InteractionResult.FAIL;
		}
		if (hasDroneAutoAimModule(root)) {
			return InteractionResult.PASS;
		}
		setDroneAutoAimModule(root, true);
		applyDroneTuningSuccess(root);
		if (!player.getAbilities().instabuild) {
			heldStack.shrink(1);
		}
		return InteractionResult.SUCCESS;
	}

	private static InteractionResult tryInstallMicrophoneModule(ServerPlayer player, Entity root, ItemStack heldStack) {
		if (!requireDroneTuningUpgrade(player, IT_DRONE_MICROPHONE)) {
			return InteractionResult.FAIL;
		}
		if (hasDroneMicrophoneModule(root)) {
			return InteractionResult.PASS;
		}
		setDroneMicrophoneModule(root, true);
		applyDroneTuningSuccess(root);
		if (!player.getAbilities().instabuild) {
			heldStack.shrink(1);
		}
		return InteractionResult.SUCCESS;
	}

	private static InteractionResult tryPaintDrone(ServerPlayer player, Entity root, ItemStack heldStack, DyeColor color) {
		if (!requireDroneTuningUpgrade(player, IT_DRONE_PAINT)) {
			return InteractionResult.FAIL;
		}
		if (color == null) {
			return InteractionResult.PASS;
		}
		DyeColor targetColor = normalizeDronePaintColor(color);
		DyeColor currentColor = resolveDronePaintColor(root);
		if (Objects.equals(currentColor, targetColor)) {
			return InteractionResult.PASS;
		}
		if (!player.getAbilities().instabuild && currentColor != null) {
			giveOrDropTuningItem(player, root, dyeItemForColor(currentColor));
		}
		setDronePaintColor(root, targetColor);
		applyDroneTuningSuccess(root);
		if (!player.getAbilities().instabuild) {
			heldStack.shrink(1);
		}
		return InteractionResult.SUCCESS;
	}

	private static void giveOrDropTuningItem(ServerPlayer player, Entity root, Item item) {
		if (player == null || root == null || item == null) {
			return;
		}
		giveOrDropTuningItem(player, root, new ItemStack(item));
	}

	private static void giveOrDropTuningItem(ServerPlayer player, Entity root, ItemStack stack) {
		if (player == null || root == null || stack == null || stack.isEmpty()) {
			return;
		}
		boolean inserted = player.getInventory().add(stack);
		if (!inserted && root.level() instanceof ServerLevel level) {
			root.spawnAtLocation(level, stack);
		}
	}

	private static void applyDroneTuningSuccess(Entity root) {
		if (root == null) {
			return;
		}
		triggerDroneDisplayWobble(root, DroneDisplayWobbleType.POSITIVE);
		notifyDroneNetworkChanged(root);
	}

	private static Item dyeItemForColor(DyeColor color) {
		if (color == null) {
			return null;
		}
		return switch (color) {
			case WHITE -> Items.WHITE_DYE;
			case ORANGE -> Items.ORANGE_DYE;
			case MAGENTA -> Items.MAGENTA_DYE;
			case LIGHT_BLUE -> Items.LIGHT_BLUE_DYE;
			case YELLOW -> Items.YELLOW_DYE;
			case LIME -> Items.LIME_DYE;
			case PINK -> Items.PINK_DYE;
			case GRAY -> Items.GRAY_DYE;
			case LIGHT_GRAY -> Items.LIGHT_GRAY_DYE;
			case CYAN -> Items.CYAN_DYE;
			case PURPLE -> Items.PURPLE_DYE;
			case BLUE -> Items.BLUE_DYE;
			case BROWN -> Items.BROWN_DYE;
			case GREEN -> Items.GREEN_DYE;
			case RED -> Items.RED_DYE;
			case BLACK -> Items.BLACK_DYE;
		};
	}

	private static String resolveRequiredUpgradeForDroneRoot(Entity root) {
		if (root == null || !root.getTags().contains(DRONE_ROOT_TAG)) {
			return null;
		}
		return IT_DRONE_SCOUT;
	}

	private static ItemStack buildDroneDropStack(Entity root) {
		return new ItemStack(ModItems.DRONE);
	}

	private static boolean isKamikazeDrone(Entity root) {
		return resolveDroneKamikazePower(root) > DRONE_KAMIKAZE_NO_POWER;
	}

	private static DroneItem.DroneType resolveDroneType(Entity root) {
		if (root == null || !root.getTags().contains(DRONE_ROOT_TAG)) {
			return DroneItem.DroneType.NORMAL;
		}
		for (String tag : root.getTags()) {
			if (!tag.startsWith(DRONE_TYPE_TAG_PREFIX)) {
				continue;
			}
			String rawType = tag.substring(DRONE_TYPE_TAG_PREFIX.length());
			try {
				return DroneItem.DroneType.valueOf(rawType.trim().toUpperCase(java.util.Locale.ROOT));
			} catch (IllegalArgumentException ignored) {
			}
		}
		return resolveDroneKamikazePower(root) > DRONE_KAMIKAZE_NO_POWER ? DroneItem.DroneType.KAMIKAZE : DroneItem.DroneType.NORMAL;
	}

	private static int resolveDroneKamikazePower(Entity root) {
		if (root == null || !root.getTags().contains(DRONE_ROOT_TAG)) {
			return DRONE_KAMIKAZE_NO_POWER;
		}
		int resolvedPower = DRONE_KAMIKAZE_NO_POWER;
		for (String tag : root.getTags()) {
			if (!tag.startsWith(DRONE_KAMIKAZE_POWER_TAG_PREFIX)) {
				continue;
			}
			try {
				int parsed = Integer.parseInt(tag.substring(DRONE_KAMIKAZE_POWER_TAG_PREFIX.length()));
				resolvedPower = net.minecraft.util.Mth.clamp(parsed, DRONE_KAMIKAZE_MIN_POWER, DRONE_KAMIKAZE_MAX_POWER);
				break;
			} catch (NumberFormatException ignored) {
			}
		}
		return resolvedPower;
	}

	private static void setDroneKamikazePower(Entity root, int power) {
		if (root == null || !root.getTags().contains(DRONE_ROOT_TAG)) {
			return;
		}
		int clampedPower = net.minecraft.util.Mth.clamp(power, DRONE_KAMIKAZE_NO_POWER, DRONE_KAMIKAZE_MAX_POWER);
		removeDroneKamikazePowerTags(root);
		if (clampedPower > DRONE_KAMIKAZE_NO_POWER) {
			root.addTag(droneKamikazePowerTag(clampedPower));
		}
		if (clampedPower > DRONE_KAMIKAZE_NO_POWER && resolveDroneType(root) != DroneItem.DroneType.KAMIKAZE) {
			setDroneType(root, DroneItem.DroneType.KAMIKAZE);
		}
		syncDroneDisplayLayers(root);
	}

	private static void removeDroneKamikazePowerTags(Entity root) {
		if (root == null) {
			return;
		}
		for (String tag : new ArrayList<>(root.getTags())) {
			if (tag != null && tag.startsWith(DRONE_KAMIKAZE_POWER_TAG_PREFIX)) {
				root.removeTag(tag);
			}
		}
	}

	private static void applyDroneVisualState(
			Entity root,
			DroneItem.DroneType type,
			int kamikazePower,
			boolean nightVision,
			boolean autoAim,
			boolean microphone,
			DyeColor paintColor
	) {
		if (root == null || !root.getTags().contains(DRONE_ROOT_TAG)) {
			return;
		}
		setDroneType(root, type);
		setDroneKamikazePower(root, kamikazePower);
		setDroneNightVisionModule(root, nightVision);
		setDroneAutoAimModule(root, autoAim);
		setDroneMicrophoneModule(root, microphone);
		setDronePaintColor(root, paintColor);
	}

	private static void setDroneType(Entity root, DroneItem.DroneType type) {
		if (root == null || !root.getTags().contains(DRONE_ROOT_TAG)) {
			return;
		}
		DroneItem.DroneType previousType = resolveDroneType(root);
		for (String tag : new ArrayList<>(root.getTags())) {
			if (tag != null && tag.startsWith(DRONE_TYPE_TAG_PREFIX)) {
				root.removeTag(tag);
			}
		}
		DroneItem.DroneType resolvedType = type == null ? DroneItem.DroneType.NORMAL : type;
		if (resolvedType != DroneItem.DroneType.NORMAL) {
			root.addTag(DRONE_TYPE_TAG_PREFIX + resolvedType.name().toLowerCase(java.util.Locale.ROOT));
		}
		if (previousType == DroneItem.DroneType.COMBAT && resolvedType != DroneItem.DroneType.COMBAT) {
			dropDroneTurretInventory(root);
			NEXT_DRONE_TURRET_FIRE_TICK.remove(root.getUUID());
		}
		syncDroneDisplayLayers(root);
	}

	private static boolean hasDroneTurretModule(Entity root) {
		return resolveDroneType(root) == DroneItem.DroneType.COMBAT;
	}

	private static boolean openDroneTurretMenu(ServerPlayer player, Entity root) {
		if (player == null || root == null || !root.isAlive() || !hasDroneTurretModule(root)) {
			return false;
		}
		TurretInventory inventory = droneTurretInventory(root);
		return player.openMenu(new SimpleMenuProvider(
				(syncId, playerInventory, opener) -> new DispenserMenu(syncId, playerInventory, inventory),
				Component.literal("Турель дрона")
		)).isPresent();
	}

	private static TurretInventory droneTurretInventory(Entity root) {
		return DRONE_TURRET_INVENTORIES.computeIfAbsent(root.getUUID(), ignored -> new TurretInventory());
	}

	private static boolean isDroneTurretProjectileStack(ItemStack stack) {
		return stack != null && !stack.isEmpty() && DRONE_TURRET_PROJECTILES.contains(stack.getItem());
	}

	private static ItemStack insertIntoDroneTurretInventory(TurretInventory inventory, ItemStack stack) {
		if (inventory == null || stack == null || stack.isEmpty()) {
			return ItemStack.EMPTY;
		}
		ItemStack remaining = stack.copy();

		for (int slot = 0; slot < inventory.getContainerSize() && !remaining.isEmpty(); slot++) {
			ItemStack existing = inventory.getItem(slot);
			if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, remaining) || !inventory.canPlaceItem(slot, remaining)) {
				continue;
			}
			int limit = Math.min(inventory.getMaxStackSize(), existing.getMaxStackSize());
			int free = Math.max(0, limit - existing.getCount());
			if (free <= 0) {
				continue;
			}
			int move = Math.min(remaining.getCount(), free);
			existing.grow(move);
			remaining.shrink(move);
			inventory.setItem(slot, existing);
		}

		for (int slot = 0; slot < inventory.getContainerSize() && !remaining.isEmpty(); slot++) {
			ItemStack existing = inventory.getItem(slot);
			if (!existing.isEmpty() || !inventory.canPlaceItem(slot, remaining)) {
				continue;
			}
			int move = Math.min(remaining.getCount(), Math.min(inventory.getMaxStackSize(), remaining.getMaxStackSize()));
			if (move <= 0) {
				continue;
			}
			inventory.setItem(slot, remaining.copyWithCount(move));
			remaining.shrink(move);
		}

		return remaining;
	}

	private static void dropDroneTurretInventory(Entity root) {
		if (root == null) {
			return;
		}
		TurretInventory inventory = DRONE_TURRET_INVENTORIES.remove(root.getUUID());
		if (inventory == null || !(root.level() instanceof ServerLevel level)) {
			return;
		}
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.isEmpty()) {
				continue;
			}
			root.spawnAtLocation(level, stack.copy());
			inventory.setItem(slot, ItemStack.EMPTY);
		}
	}

	public static boolean handleControlledUseItem(ServerPlayer player, InteractionHand hand) {
		if (player == null || hand != InteractionHand.MAIN_HAND || player.level() == null) {
			return false;
		}
		DroneControlSession session = ACTIVE_SESSIONS.get(player.getUUID());
		if (session == null || !Objects.equals(resolveAuthoritativeDroneControllerId(session.droneUuid()), player.getUUID())) {
			return false;
		}
		MinecraftServer server = player.level().getServer();
		Entity root = server == null ? null : findDroneRoot(server, session.droneDimension(), session.droneUuid());
		if (root == null || !root.isAlive()) {
			return false;
		}
		if (hasDroneTurretModule(root)) {
			return fireDroneTurret(player, root, session, false);
		}
		if (!shouldHandleControlledAutoAimUseInput(root)) {
			return false;
		}
		return selectControlledAutoAimTargetFromView(player, session, root) != InteractionResult.PASS;
	}

	public static boolean handleControlledUseItemOn(ServerPlayer player, InteractionHand hand, BlockHitResult hitResult) {
		if (player == null || hand != InteractionHand.MAIN_HAND || !isControllingDrone(player)) {
			return false;
		}
		DroneControlSession session = ACTIVE_SESSIONS.get(player.getUUID());
		Entity root = resolveControlledDroneRoot(player);
		if (session == null || root == null || !root.isAlive()) {
			return true;
		}
		if (hasDroneTurretModule(root)) {
			return fireDroneTurret(player, root, session, false);
		}
		if (!shouldHandleControlledAutoAimUseInput(root)) {
			return true;
		}
		BlockPos pos = hitResult == null ? null : hitResult.getBlockPos();
		InteractionResult result = handleControlledAutoAimBlockInteraction(player, hand, pos, true);
		if (result != InteractionResult.PASS) {
			return true;
		}
		return selectControlledAutoAimTargetFromView(player, session, root) != InteractionResult.PASS;
	}

	public static boolean handleControlledAttackInteraction(ServerPlayer player, ServerboundInteractPacket packet) {
		if (player == null || packet == null || !isControllingDrone(player) || !(player.level() instanceof ServerLevel level)) {
			return false;
		}
		DroneControlSession session = ACTIVE_SESSIONS.get(player.getUUID());
		Entity root = resolveControlledDroneRoot(player);
		if (session == null || root == null || !root.isAlive() || !shouldHandleControlledAutoAimAttackInput(root)) {
			return false;
		}
		Entity target = packet.getTarget(level);
		if (target != null) {
			return handleControlledAutoAimEntityInteraction(player, InteractionHand.MAIN_HAND, target, false) != InteractionResult.PASS;
		}
		return selectControlledAutoAimTargetFromView(player, session, root) != InteractionResult.PASS;
	}

	public static void handleControlledPlayerAction(ServerPlayer player, ServerboundPlayerActionPacket packet) {
		if (player == null || packet == null || !isControllingDrone(player)) {
			return;
		}
		if (packet.getAction() != ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK) {
			return;
		}
		BlockPos pos = packet.getPos();
		handleControlledAutoAimBlockInteraction(player, InteractionHand.MAIN_HAND, pos, false);
		if (player.connection != null && player.level() instanceof ServerLevel level && pos != null && level.hasChunkAt(pos)) {
			sendControlledOperatorPacket(player, new ClientboundBlockUpdatePacket(level, pos));
		}
	}

	private static void handleControlledTurretJumpInput(
			ServerPlayer player,
			Entity root,
			DroneControlSession session,
			DroneInputState input
	) {
		if (player == null || root == null || session == null || input == null) {
			return;
		}
		if (!hasDroneTurretModule(root)) {
			return;
		}
		if (!input.jump()) {
			return;
		}
		fireDroneTurret(player, root, session, false);
	}

	private static boolean fireDroneTurret(
			ServerPlayer player,
			Entity root,
			DroneControlSession session,
			boolean ignoreInputSuppression
	) {
		if (player == null || root == null || session == null || !(root.level() instanceof ServerLevel level)) {
			return false;
		}
		if (!hasDroneTurretModule(root)) {
			return false;
		}
		long now = level.getGameTime();
		if (!ignoreInputSuppression && now < session.turretInputSuppressedUntilTick()) {
			return true;
		}
		long nextAllowed = NEXT_DRONE_TURRET_FIRE_TICK.getOrDefault(root.getUUID(), Long.MIN_VALUE);
		if (now < nextAllowed) {
			return true;
		}
		TurretInventory inventory = droneTurretInventory(root);
		int slot = selectRandomDroneTurretProjectileSlot(level, inventory);
		if (slot < 0) {
			level.levelEvent(1001, BlockPos.containing(droneCameraOrigin(root)), 0);
			NEXT_DRONE_TURRET_FIRE_TICK.put(root.getUUID(), now + DRONE_TURRET_FIRE_COOLDOWN_TICKS);
			return true;
		}
		ItemStack stack = inventory.getItem(slot);
		ItemStack shotStack = stack.copyWithCount(1);
		Vec3 direction = controlledTurretDirection(player, session);
		Vec3 origin = controlledTurretMuzzleOrigin(root, session, direction);
		Projectile projectile = createDroneTurretProjectile(level, root, origin, direction, shotStack);
		if (projectile == null) {
			level.levelEvent(1001, BlockPos.containing(origin), 0);
			NEXT_DRONE_TURRET_FIRE_TICK.put(root.getUUID(), now + DRONE_TURRET_FIRE_COOLDOWN_TICKS);
			return true;
		}

		level.addFreshEntity(projectile);
		stack.shrink(1);
		inventory.setItem(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
		inventory.setChanged();
		level.levelEvent(1000, BlockPos.containing(origin), 0);
		NEXT_DRONE_TURRET_FIRE_TICK.put(root.getUUID(), now + DRONE_TURRET_FIRE_COOLDOWN_TICKS);
		return true;
	}

	private static int selectRandomDroneTurretProjectileSlot(ServerLevel level, TurretInventory inventory) {
		if (level == null || inventory == null) {
			return -1;
		}
		int selectedSlot = -1;
		int validCount = 0;
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			if (!isDroneTurretProjectileStack(inventory.getItem(slot))) {
				continue;
			}
			validCount++;
			if (level.random.nextInt(validCount) == 0) {
				selectedSlot = slot;
			}
		}
		return selectedSlot;
	}

	private static Vec3 controlledTurretDirection(ServerPlayer player, DroneControlSession session) {
		Vec3 direction = session == null ? null : Vec3.directionFromRotation(session.proxyPitch(), session.proxyYaw());
		if (direction == null || direction.lengthSqr() < 1.0E-8D) {
			direction = player == null ? Vec3.ZERO : player.getLookAngle();
		}
		if (direction.lengthSqr() < 1.0E-8D) {
			return new Vec3(0.0D, 0.0D, 1.0D);
		}
		return direction.normalize();
	}

	private static Vec3 controlledTurretMuzzleOrigin(Entity root, DroneControlSession session, Vec3 direction) {
		Vec3 fallback = root == null ? Vec3.ZERO : root.position();
		Vec3 base = session == null ? fallback : finiteVecOr(session.proxyPos(), fallback);
		return droneCameraOrigin(base, resolveDroneVisualLift(root)).add(direction.normalize().scale(DRONE_TURRET_MUZZLE_FORWARD_OFFSET));
	}

	private static Projectile createDroneTurretProjectile(
			ServerLevel level,
			Entity owner,
			Vec3 origin,
			Vec3 direction,
			ItemStack stack
	) {
		if (level == null || origin == null || direction == null || stack == null || stack.isEmpty()) {
			return null;
		}
		Vec3 normalized = direction.lengthSqr() < 1.0E-8D ? new Vec3(0.0D, 0.0D, 1.0D) : direction.normalize();
		Projectile projectile;
		float speed;
		Item item = stack.getItem();
		if (item == Items.SPECTRAL_ARROW) {
			projectile = new SpectralArrow(level, origin.x, origin.y, origin.z, stack, new ItemStack(Items.CROSSBOW));
			speed = 3.0F;
		} else if (item == Items.ARROW || item == Items.TIPPED_ARROW) {
			projectile = new Arrow(level, origin.x, origin.y, origin.z, stack, new ItemStack(Items.CROSSBOW));
			speed = 3.0F;
		} else if (item == Items.TRIDENT) {
			projectile = new ThrownTrident(level, origin.x, origin.y, origin.z, stack);
			speed = 2.5F;
		} else if (item == Items.FIREWORK_ROCKET) {
			projectile = new FireworkRocketEntity(level, stack, owner, origin.x, origin.y, origin.z, true);
			speed = 1.8F;
		} else if (item == Items.FIRE_CHARGE) {
			projectile = new SmallFireball(level, origin.x, origin.y, origin.z, normalized);
			speed = 1.5F;
		} else if (item == Items.WIND_CHARGE) {
			projectile = new WindCharge(level, origin.x, origin.y, origin.z, normalized);
			speed = 1.4F;
		} else if (item == Items.SNOWBALL) {
			projectile = new Snowball(level, origin.x, origin.y, origin.z, stack);
			speed = 1.5F;
		} else if (item == Items.EGG) {
			projectile = new ThrownEgg(level, origin.x, origin.y, origin.z, stack);
			speed = 1.5F;
		} else if (item == Items.EXPERIENCE_BOTTLE) {
			projectile = new ThrownExperienceBottle(level, origin.x, origin.y, origin.z, stack);
			speed = 1.3F;
		} else if (item == Items.SPLASH_POTION) {
			projectile = new ThrownSplashPotion(level, origin.x, origin.y, origin.z, stack);
			speed = DRONE_TURRET_POTION_SPEED;
		} else if (item == Items.LINGERING_POTION) {
			projectile = new ThrownLingeringPotion(level, origin.x, origin.y, origin.z, stack);
			speed = DRONE_TURRET_POTION_SPEED;
		} else {
			return null;
		}
		projectile.setOwner(owner);
		projectile.setPos(origin.x, origin.y, origin.z);
		if (isDroneTurretStraightFlightProjectile(item)) {
			projectile.setNoGravity(true);
		}
		projectile.shoot(normalized.x, normalized.y, normalized.z, speed, 0.0F);
		return projectile;
	}

	private static boolean isDroneTurretStraightFlightProjectile(Item item) {
		return item == Items.SPLASH_POTION || item == Items.LINGERING_POTION;
	}

	private static void syncControlledDroneTurretAirTrigger(ServerPlayer player, Entity root) {
		if (player == null || root == null || !(root.level() instanceof ServerLevel level)) {
			return;
		}
		DroneControlSession session = ACTIVE_SESSIONS.get(player.getUUID());
		if (session == null) {
			removeControlledDroneTurretAirTrigger(player);
			return;
		}
		if (!shouldMaintainControlledDroneTurretAirTrigger(player, root)) {
			removeControlledDroneTurretAirTrigger(player);
			return;
		}
		Vec3 look = controlledTurretDirection(player, session);
		Vec3 cameraOrigin = droneCameraOrigin(
				finiteVecOr(session.proxyPos(), root.position()),
				resolveDroneVisualLift(root)
		);
		Vec3 pos = cameraOrigin
				.add(look.normalize().scale(DRONE_TURRET_AIR_TRIGGER_HEAD_FORWARD_OFFSET))
				.subtract(0.0D, DRONE_TURRET_AIR_TRIGGER_HEIGHT * 0.5D, 0.0D);
		UUID triggerId = CONTROLLED_DRONE_TURRET_TRIGGERS.get(player.getUUID());
		Interaction trigger = null;
		if (triggerId != null) {
			Entity existing = level.getEntity(triggerId);
			if (existing instanceof Interaction existingTrigger && existingTrigger.isAlive()) {
				trigger = existingTrigger;
			}
		}
		if (trigger == null) {
			trigger = new Interaction(EntityType.INTERACTION, level);
			trigger.addTag(DRONE_TURRET_TRIGGER_TAG);
			trigger.addTag(DRONE_TURRET_TRIGGER_OWNER_TAG_PREFIX + player.getUUID());
			trigger.setNoGravity(true);
			trigger.setSilent(true);
			trigger.setInvisible(true);
			trigger.setResponse(false);
			trigger.setWidth(DRONE_TURRET_AIR_TRIGGER_WIDTH);
			trigger.setHeight(DRONE_TURRET_AIR_TRIGGER_HEIGHT);
			trigger.setPos(pos.x, pos.y, pos.z);
			trigger.setDeltaMovement(Vec3.ZERO);
			trigger.setYRot(session.proxyYaw());
			trigger.setXRot(session.proxyPitch());
			level.addFreshEntity(trigger);
			CONTROLLED_DRONE_TURRET_TRIGGERS.put(player.getUUID(), trigger.getUUID());
			sendControlledOperatorPacket(player, new ClientboundAddEntityPacket(
					trigger.getId(),
					trigger.getUUID(),
					trigger.getX(),
					trigger.getY(),
					trigger.getZ(),
					trigger.getXRot(),
					trigger.getYRot(),
					EntityType.INTERACTION,
					0,
					Vec3.ZERO,
					trigger.getYHeadRot()
			));
			List<SynchedEntityData.DataValue<?>> trackedData = trigger.getEntityData().getNonDefaultValues();
			if (trackedData != null && !trackedData.isEmpty()) {
				sendControlledOperatorPacket(player, new ClientboundSetEntityDataPacket(trigger.getId(), trackedData));
			}
		}
		trigger.setInvisible(true);
		trigger.setPos(pos.x, pos.y, pos.z);
		trigger.setDeltaMovement(Vec3.ZERO);
		trigger.setYRot(session.proxyYaw());
		trigger.setXRot(session.proxyPitch());
		sendControlledOperatorPacket(player, ClientboundEntityPositionSyncPacket.of(trigger));
	}

	private static boolean shouldMaintainControlledDroneTurretAirTrigger(ServerPlayer player, Entity root) {
		if (player == null
				|| root == null
				|| !root.isAlive()
				|| (!hasDroneTurretModule(root) && !hasDroneAutoAimModule(root))
				|| !player.isAlive()
				|| player.isSpectator()) {
			return false;
		}
		DroneControlSession session = ACTIVE_SESSIONS.get(player.getUUID());
		return session != null
				&& Objects.equals(session.droneUuid(), root.getUUID())
				&& Objects.equals(resolveAuthoritativeDroneControllerId(root.getUUID()), player.getUUID());
	}

	private static void removeControlledDroneTurretAirTrigger(ServerPlayer player) {
		if (player == null) {
			return;
		}
		UUID triggerId = CONTROLLED_DRONE_TURRET_TRIGGERS.remove(player.getUUID());
		if (triggerId == null || player.level() == null || player.level().getServer() == null) {
			return;
		}
		Entity trigger = findEntity(player.level().getServer(), triggerId);
		if (trigger != null) {
			sendControlledOperatorPacket(player, new ClientboundRemoveEntitiesPacket(trigger.getId()));
			trigger.discard();
		}
	}

	private static boolean hasDroneNightVisionModule(Entity root) {
		return root != null && root.getTags().contains(DRONE_NIGHT_VISION_TAG);
	}

	private static void setDroneNightVisionModule(Entity root, boolean enabled) {
		if (root == null || !root.getTags().contains(DRONE_ROOT_TAG)) {
			return;
		}
		if (enabled) {
			root.addTag(DRONE_NIGHT_VISION_TAG);
		} else {
			root.removeTag(DRONE_NIGHT_VISION_TAG);
		}
		syncDroneDisplayLayers(root);
	}

	private static boolean hasDroneAutoAimModule(Entity root) {
		return root != null && root.getTags().contains(DRONE_AUTO_AIM_TAG);
	}

	private static void setDroneAutoAimModule(Entity root, boolean enabled) {
		if (root == null || !root.getTags().contains(DRONE_ROOT_TAG)) {
			return;
		}
		boolean changed = hasDroneAutoAimModule(root) != enabled;
		if (enabled) {
			root.addTag(DRONE_AUTO_AIM_TAG);
		} else {
			root.removeTag(DRONE_AUTO_AIM_TAG);
			AUTO_AIM_DISPLAY_ANIMATIONS.remove(root.getUUID());
		}
		syncDroneDisplayLayers(root);
		if (changed) {
			syncDroneAutoAimModuleState(root, enabled);
		}
	}

	private static boolean hasDroneMicrophoneModule(Entity root) {
		return root != null && root.getTags().contains(DRONE_MICROPHONE_TAG);
	}

	private static void setDroneMicrophoneModule(Entity root, boolean enabled) {
		if (root == null || !root.getTags().contains(DRONE_ROOT_TAG)) {
			return;
		}
		if (enabled) {
			root.addTag(DRONE_MICROPHONE_TAG);
		} else {
			root.removeTag(DRONE_MICROPHONE_TAG);
		}
		syncDroneDisplayLayers(root);
	}

	private static void syncDroneAutoAimModuleState(Entity root, boolean enabled) {
		if (root == null) {
			return;
		}
		UncontrolledDroneState uncontrolledState = UNCONTROLLED_DRONES.get(root.getUUID());
		if (!enabled && uncontrolledState != null) {
			uncontrolledState.setAutoAimTarget(null);
		}
		if (!enabled) {
			setPersistedDroneAutoAimTarget(root, null);
		}
		UUID controllerId = resolveAuthoritativeDroneControllerId(root.getUUID());
		if (controllerId == null || !(root.level() instanceof ServerLevel level)) {
			return;
		}
		ServerPlayer controller = level.getServer().getPlayerList().getPlayer(controllerId);
		DroneControlSession session = ACTIVE_SESSIONS.get(controllerId);
		if (controller == null || session == null) {
			return;
		}
		syncControlledOperatorAutoAimInteractionRange(controller, enabled);
		if (!enabled) {
			clearControlledAutoAimTarget(controller, session);
		} else if (session.autoAimTarget() != null) {
			showControlledAutoAimTarget(controller, session, session.autoAimTarget());
		}
	}

	private static int resolveDroneSurfaceWearDustColor(Entity root) {
		DyeColor paintColor = resolveDronePaintColor(root);
		int baseColor = paintColor == null ? DRONE_SURFACE_WEAR_DUST_DEFAULT_COLOR : paintColor.getTextureDiffuseColor();
		return darkenRgb(baseColor, paintColor == null ? 0.85F : 0.58F);
	}

	private static int darkenRgb(int rgb, float factor) {
		float clampedFactor = net.minecraft.util.Mth.clamp(factor, 0.0F, 1.0F);
		int red = (int) (((rgb >> 16) & 0xFF) * clampedFactor);
		int green = (int) (((rgb >> 8) & 0xFF) * clampedFactor);
		int blue = (int) ((rgb & 0xFF) * clampedFactor);
		return (red << 16) | (green << 8) | blue;
	}

	private static DyeColor resolveDronePaintColor(Entity root) {
		if (root == null || !root.getTags().contains(DRONE_ROOT_TAG)) {
			return null;
		}
		for (String tag : root.getTags()) {
			if (!tag.startsWith(DRONE_PAINT_TAG_PREFIX)) {
				continue;
			}
			String rawColor = tag.substring(DRONE_PAINT_TAG_PREFIX.length());
			for (DyeColor color : DyeColor.values()) {
				if (color.getName().equalsIgnoreCase(rawColor)) {
					return normalizeDronePaintColor(color);
				}
			}
		}
		return null;
	}

	private static void setDronePaintColor(Entity root, DyeColor color) {
		if (root == null || !root.getTags().contains(DRONE_ROOT_TAG)) {
			return;
		}
		DyeColor normalizedColor = normalizeDronePaintColor(color);
		for (String tag : new ArrayList<>(root.getTags())) {
			if (tag != null && tag.startsWith(DRONE_PAINT_TAG_PREFIX)) {
				root.removeTag(tag);
			}
		}
		if (normalizedColor != null) {
			root.addTag(DRONE_PAINT_TAG_PREFIX + normalizedColor.getName());
		}
		syncDroneDisplayLayers(root);
	}

	private static DyeColor normalizeDronePaintColor(DyeColor color) {
		return color == DyeColor.WHITE ? null : color;
	}

	private static void playDroneKamikazeInsertFeedback(ServerLevel level, Vec3 origin, int newPower) {
		if (level == null || origin == null) {
			return;
		}
		float fillRatio = net.minecraft.util.Mth.clamp(newPower / (float) DRONE_KAMIKAZE_MAX_POWER, 0.0F, 1.0F);
		float pitch = 0.7F + 0.5F * fillRatio;
		level.playSound(
				null,
				origin.x,
				origin.y,
				origin.z,
				SoundEvents.DECORATED_POT_INSERT,
				SoundSource.PLAYERS,
				1.0F,
				pitch
		);
		level.sendParticles(
				ParticleTypes.DUST_PLUME,
				origin.x,
				origin.y + DRONE_HEIGHT * 0.50D,
				origin.z,
				7,
				0.0D,
				0.0D,
				0.0D,
				0.0D
		);
	}

	private static void playDroneKamikazeInsertFailFeedback(ServerLevel level, Vec3 origin) {
		if (level == null || origin == null) {
			return;
		}
		level.playSound(
				null,
				origin.x,
				origin.y,
				origin.z,
				SoundEvents.DECORATED_POT_INSERT_FAIL,
				SoundSource.PLAYERS,
				1.0F,
				1.0F
		);
	}

	private static String droneKamikazePowerTag(int power) {
		return DRONE_KAMIKAZE_POWER_TAG_PREFIX
				+ net.minecraft.util.Mth.clamp(power, DRONE_KAMIKAZE_MIN_POWER, DRONE_KAMIKAZE_MAX_POWER);
	}

	private static void detonateKamikazeDrone(ServerLevel level, Vec3 origin, Vec3 velocity, int power) {
		if (level == null || origin == null) {
			return;
		}
		int charges = net.minecraft.util.Mth.clamp(power, DRONE_KAMIKAZE_MIN_POWER, DRONE_KAMIKAZE_MAX_POWER);
		Vec3 inheritedVelocity = velocity == null ? Vec3.ZERO : velocity.scale(0.30D);

		for (int index = 0; index < charges; index++) {
			Entity created = EntityType.TNT.create(level, EntitySpawnReason.TRIGGERED);
			if (!(created instanceof PrimedTnt tnt)) {
				continue;
			}
			double angle = charges <= 1 ? 0.0D : (Math.PI * 2.0D * index) / charges;
			double radius = charges <= 1 ? 0.0D : DRONE_KAMIKAZE_TNT_SPREAD;
			double x = origin.x + Math.cos(angle) * radius;
			double y = origin.y + 0.04D;
			double z = origin.z + Math.sin(angle) * radius;
			tnt.setPos(x, y, z);
			tnt.setFuse(0);
			tnt.setDeltaMovement(
					inheritedVelocity.add(
							(level.random.nextDouble() - 0.5D) * 0.08D,
							0.03D + level.random.nextDouble() * 0.03D,
							(level.random.nextDouble() - 0.5D) * 0.08D
					)
			);
			level.addFreshEntity(tnt);
		}
	}

	private static SoundEvent resolveVanillaSoundEvent(Identifier soundId, SoundEvent fallback) {
		if (soundId == null) {
			return fallback;
		}
		SoundEvent resolved = BuiltInRegistries.SOUND_EVENT.getValue(soundId);
		return resolved != null ? resolved : fallback;
	}

	private static Entity resolveDroneRoot(Entity entity) {
		if (entity != null && entity.getTags().contains(DRONE_ROOT_TAG)) {
			return entity;
		}
		if (entity != null && entity.getTags().contains(DRONE_DISPLAY_TAG) && entity.level() instanceof ServerLevel level) {
			for (String tag : entity.getTags()) {
				if (!tag.startsWith(DRONE_DISPLAY_OWNER_TAG_PREFIX)) {
					continue;
				}
				try {
					UUID rootId = UUID.fromString(tag.substring(DRONE_DISPLAY_OWNER_TAG_PREFIX.length()));
					Entity root = level.getEntity(rootId);
					if (root != null && root.getTags().contains(DRONE_ROOT_TAG)) {
						return root;
					}
				} catch (IllegalArgumentException ignored) {
				}
			}
		}
		return null;
	}

	private static void rememberDroneDisplayLayer(Entity root, Entity display) {
		if (root == null || display == null) {
			return;
		}
		DISPLAY_LAYERS_BY_DRONE
				.computeIfAbsent(root.getUUID(), ignored -> new LinkedHashSet<>())
				.add(display.getUUID());
	}

	private static void rememberDroneDisplayLayers(Entity root, Collection<Display.ItemDisplay> displays) {
		if (root == null || displays == null) {
			return;
		}
		LinkedHashSet<UUID> remembered = new LinkedHashSet<>();
		for (Display.ItemDisplay display : displays) {
			if (display != null && display.isAlive()) {
				remembered.add(display.getUUID());
			}
		}
		if (remembered.isEmpty()) {
			DISPLAY_LAYERS_BY_DRONE.remove(root.getUUID());
			return;
		}
		DISPLAY_LAYERS_BY_DRONE.put(root.getUUID(), remembered);
	}

	private static Display.ItemDisplay createDroneDisplay(
			ServerLevel level,
			Vec3 position,
			float yRot,
			float xRot,
			ItemStack displayStack,
			String layerKey,
			double displayYOffset
	) {
		Display.ItemDisplay display = new Display.ItemDisplay(EntityType.ITEM_DISPLAY, level);
		display.addTag(DRONE_DISPLAY_TAG);
		display.addTag(DRONE_DISPLAY_LAYER_TAG_PREFIX + (layerKey == null || layerKey.isBlank() ? DRONE_DISPLAY_LAYER_BASE : layerKey));
		display.setPos(position.x, position.y + displayYOffset, position.z);
		display.setYRot(yRot);
		display.setXRot(xRot);
		display.setYHeadRot(yRot);
		display.setYBodyRot(yRot);
		display.setItemStack(displayStack == null ? DroneItem.createDisplayStack() : displayStack);
		display.setItemTransform(ItemDisplayContext.FIXED);
		display.setBillboardConstraints(Display.BillboardConstraints.FIXED);
		display.setNoGravity(true);
		display.setInvulnerable(true);
		display.setSilent(true);
		display.setShadowRadius(0.0F);
		display.setShadowStrength(0.0F);
		display.setViewRange(DRONE_DISPLAY_VIEW_RANGE);
		display.setPosRotInterpolationDuration(DRONE_DISPLAY_INTERPOLATION_TICKS);
		display.setTransformationInterpolationDelay(0);
		display.setTransformationInterpolationDuration(DRONE_DISPLAY_INTERPOLATION_TICKS);
		display.setTransformation(Transformation.identity());
		collapseDroneDisplayHitbox(display);
		return display;
	}

	private static Interaction createDroneCameraAnchor(ServerLevel level, Vec3 position, float yRot, float xRot) {
		Interaction anchor = new Interaction(EntityType.INTERACTION, level);
		anchor.addTag(DRONE_CAMERA_TAG);
		anchor.setPos(position.x, position.y, position.z);
		anchor.setYRot(yRot);
		anchor.setXRot(xRot);
		anchor.setNoGravity(true);
		anchor.setInvulnerable(true);
		anchor.setSilent(true);
		anchor.setResponse(false);
		anchor.setWidth(DRONE_CAMERA_ANCHOR_SIZE);
		anchor.setHeight(DRONE_CAMERA_ANCHOR_SIZE);
		return anchor;
	}

	private static void syncDroneDisplay(Entity root, float yRot, float xRot, double forwardDrive, double strafeDrive) {
		syncDroneDisplay(root, yRot, xRot, forwardDrive, strafeDrive, false);
	}

	private static void syncDroneDisplay(
			Entity root,
			float yRot,
			float xRot,
			double forwardDrive,
			double strafeDrive,
			boolean propellersShouldSpin
	) {
		List<Display.ItemDisplay> displays = findDroneDisplayLayers(root);
		if (displays.isEmpty()) {
			syncDroneDisplayLayers(root);
			displays = findDroneDisplayLayers(root);
		}
		boolean controlled = isDroneActivelyControlled(root);
		DroneItem.DroneType droneType = resolveDroneType(root);
		int kamikazePower = resolveDroneKamikazePower(root);
		DyeColor paintColor = resolveDronePaintColor(root);
		int cameraPitch = visualDroneCameraPitch(xRot);
		boolean propellersActive = propellersShouldSpin;
		float visualPitch = root != null && root.onGround()
				? 0.0F
				: (controlled ? 0.0F : xRot);
		double displayYOffset = resolveDroneDisplayYOffset(droneType);
		DroneAutoAimDisplayAnimationState autoAimAnimation = hasDroneAutoAimModule(root)
				? resolveDroneAutoAimDisplayAnimation(root)
				: null;
		for (Display.ItemDisplay display : displays) {
			if (display.isPassenger()) {
				display.stopRiding();
			}
			display.setPosRotInterpolationDuration(DRONE_DISPLAY_INTERPOLATION_TICKS);
			display.setTransformationInterpolationDelay(0);
			display.setTransformationInterpolationDuration(DRONE_DISPLAY_INTERPOLATION_TICKS);
			display.setYRot(yRot);
			display.setXRot(visualPitch);
			applyDynamicDroneDisplayLayer(display, paintColor, cameraPitch, propellersActive, kamikazePower, autoAimAnimation);
			display.setTransformation(buildDroneDisplayTransformation(root, forwardDrive, strafeDrive, propellersShouldSpin));
			display.setPos(root.getX(), root.getY() + displayYOffset, root.getZ());
			collapseDroneDisplayHitbox(display);
		}
	}

	private static void collapseDroneDisplayHitbox(Display.ItemDisplay display) {
		ItemDisplayHitboxHelper.clear(display);
	}

	private static Transformation buildDroneDisplayTransformation(
			Entity root,
			double forwardDrive,
			double strafeDrive,
			boolean activeFlight
	) {
		double forwardNorm = forwardDrive / DroneFlightPhysics.MAX_FORWARD_DRIVE;
		double strafeNorm = strafeDrive / DroneFlightPhysics.MAX_STRAFE_DRIVE;
		forwardNorm = net.minecraft.util.Mth.clamp(forwardNorm, -1.0D, 1.0D);
		strafeNorm = net.minecraft.util.Mth.clamp(strafeNorm, -1.0D, 1.0D);
		// A controlled drone is moved through its virtual flight path, where the
		// root entity can retain a stale on-ground flag.  Do not flatten its model
		// while the propellers are actively carrying it through a strafe.
		if (root != null && root.onGround() && !activeFlight) {
			forwardNorm = 0.0D;
			strafeNorm = 0.0D;
		}

		// Forward drive pitches the nose down; strafe drive rolls into the turn.
		float pitchTiltRad = (float) Math.toRadians((float) (forwardNorm * DRONE_MAX_TILT_DEGREES));
		float rollTiltRad = (float) Math.toRadians((float) (strafeNorm * DRONE_MAX_TILT_DEGREES));
		Quaternionf rotation = new Quaternionf().rotateXYZ(pitchTiltRad, 0.0F, rollTiltRad);
		DroneDisplayWobble wobble = root != null && !root.onGround() ? resolveActiveDroneDisplayWobble(root) : null;
		if (wobble != null) {
			applyVanillaPotWobbleRotation(rotation, wobble);
		}

		return new Transformation(
				new Vector3f(0.0F, 0.0F, 0.0F),
				rotation,
				new Vector3f(1.0F, 1.0F, 1.0F),
				new Quaternionf()
		);
	}

	private static void triggerDroneDisplayWobble(Entity root, DroneDisplayWobbleType type) {
		if (root == null || type == null || root.level() == null) {
			return;
		}
		DISPLAY_WOBBLE_BY_DRONE.put(root.getUUID(), new DroneDisplayWobbleState(type, root.level().getGameTime()));
	}

	private static DroneDisplayWobble resolveActiveDroneDisplayWobble(Entity root) {
		if (root == null || root.level() == null) {
			return null;
		}
		DroneDisplayWobbleState state = DISPLAY_WOBBLE_BY_DRONE.get(root.getUUID());
		if (state == null || state.type() == null) {
			return null;
		}

		float progress = (root.level().getGameTime() - state.startedAtTick()) / (float) state.type().lengthInTicks();
		if (progress < 0.0F || progress > 1.0F) {
			DISPLAY_WOBBLE_BY_DRONE.remove(root.getUUID());
			return null;
		}
		return new DroneDisplayWobble(state.type(), progress);
	}

	private static void applyVanillaPotWobbleRotation(Quaternionf rotation, DroneDisplayWobble wobble) {
		if (rotation == null || wobble == null || wobble.type() == null) {
			return;
		}
		float progress = wobble.progress();
		if (wobble.type() == DroneDisplayWobbleType.POSITIVE) {
			float phase = progress * DRONE_VANILLA_WOBBLE_PROGRESS_TO_RADIANS;
			float wobbleX = ((-1.5F * net.minecraft.util.Mth.cos(phase) + 0.5F) * net.minecraft.util.Mth.sin(phase / 2.0F))
					* DRONE_VANILLA_WOBBLE_ROTATION_SCALE;
			float wobbleZ = net.minecraft.util.Mth.sin(phase) * DRONE_VANILLA_WOBBLE_ROTATION_SCALE;
			rotation.rotateX(wobbleX);
			rotation.rotateZ(wobbleZ);
			return;
		}

		float yawWobble = net.minecraft.util.Mth.sin(-progress * DRONE_VANILLA_WOBBLE_NEGATIVE_PROGRESS_TO_RADIANS)
				* DRONE_VANILLA_WOBBLE_NEGATIVE_SCALE
				* (1.0F - progress);
		rotation.rotateY(yawWobble);
	}

	private static Entity findDroneDisplay(Entity root) {
		if (root == null || !(root.level() instanceof ServerLevel level)) {
			return null;
		}
		UUID displayId = DISPLAYS_BY_DRONE.get(root.getUUID());
		Entity display = displayId == null ? null : level.getEntity(displayId);
		if (display != null
				&& display.getTags().contains(DRONE_DISPLAY_TAG)
				&& display.getTags().contains(DRONE_DISPLAY_LAYER_TAG_PREFIX + DRONE_DISPLAY_LAYER_BASE)) {
			return display;
		}
		for (Entity candidate : level.getEntities(root, root.getBoundingBox().inflate(8.0D))) {
			if (!candidate.getTags().contains(DRONE_DISPLAY_TAG)) {
				continue;
			}
			if (candidate.getTags().contains(DRONE_DISPLAY_OWNER_TAG_PREFIX + root.getUUID())
					&& candidate.getTags().contains(DRONE_DISPLAY_LAYER_TAG_PREFIX + DRONE_DISPLAY_LAYER_BASE)) {
				DISPLAYS_BY_DRONE.put(root.getUUID(), candidate.getUUID());
				return candidate;
			}
		}
		return null;
	}

	private static List<Display.ItemDisplay> findDroneDisplayLayers(Entity root) {
		List<Display.ItemDisplay> displays = new ArrayList<>();
		if (root == null || !(root.level() instanceof ServerLevel level)) {
			return displays;
		}
		Set<UUID> seen = new LinkedHashSet<>();
		collectDroneDisplayLayers(level, root, root.getBoundingBox().inflate(8.0D), displays, seen);
		UUID registeredBaseId = DISPLAYS_BY_DRONE.get(root.getUUID());
		Entity registeredBase = registeredBaseId == null ? null : level.getEntity(registeredBaseId);
		if (registeredBase != null && registeredBase.isAlive()) {
			collectDroneDisplayLayer(root, registeredBase, displays, seen);
			collectDroneDisplayLayers(level, root, registeredBase.getBoundingBox().inflate(8.0D), displays, seen);
		} else if (registeredBaseId != null) {
			DISPLAYS_BY_DRONE.remove(root.getUUID(), registeredBaseId);
		}
		rememberDroneDisplayLayers(root, displays);
		return displays;
	}

	private static List<Display.ItemDisplay> findRegisteredDroneDisplayLayers(Entity root) {
		List<Display.ItemDisplay> displays = new ArrayList<>();
		if (root == null || !(root.level() instanceof ServerLevel level)) {
			return displays;
		}

		LinkedHashSet<UUID> candidateIds = new LinkedHashSet<>();
		UUID baseDisplayId = DISPLAYS_BY_DRONE.get(root.getUUID());
		if (baseDisplayId != null) {
			candidateIds.add(baseDisplayId);
		}
		LinkedHashSet<UUID> rememberedIds = DISPLAY_LAYERS_BY_DRONE.get(root.getUUID());
		if (rememberedIds != null) {
			candidateIds.addAll(rememberedIds);
		}
		if (candidateIds.isEmpty()) {
			return displays;
		}

		LinkedHashSet<UUID> aliveIds = new LinkedHashSet<>();
		String ownerTag = DRONE_DISPLAY_OWNER_TAG_PREFIX + root.getUUID();
		for (UUID displayId : candidateIds) {
			Entity candidate = displayId == null ? null : level.getEntity(displayId);
			if (!(candidate instanceof Display.ItemDisplay display)
					|| !candidate.isAlive()
					|| !candidate.getTags().contains(DRONE_DISPLAY_TAG)
					|| !candidate.getTags().contains(ownerTag)) {
				continue;
			}
			displays.add(display);
			aliveIds.add(display.getUUID());
		}
		if (aliveIds.isEmpty()) {
			DISPLAY_LAYERS_BY_DRONE.remove(root.getUUID());
		} else {
			DISPLAY_LAYERS_BY_DRONE.put(root.getUUID(), aliveIds);
		}
		return displays;
	}

	private static Display.ItemDisplay resolveRegisteredDroneDisplayLayer(Entity root, int entityId) {
		if (root == null || entityId == Integer.MIN_VALUE || !(root.level() instanceof ServerLevel level)) {
			return null;
		}
		Entity candidate = level.getEntity(entityId);
		if (!(candidate instanceof Display.ItemDisplay display)
				|| !candidate.isAlive()
				|| !candidate.getTags().contains(DRONE_DISPLAY_TAG)
				|| !candidate.getTags().contains(DRONE_DISPLAY_OWNER_TAG_PREFIX + root.getUUID())) {
			return null;
		}
		rememberDroneDisplayLayer(root, display);
		return display;
	}

	private static void collectDroneDisplayLayers(
			ServerLevel level,
			Entity root,
			AABB bounds,
			List<Display.ItemDisplay> displays,
			Set<UUID> seen
	) {
		if (level == null || root == null || bounds == null || displays == null || seen == null) {
			return;
		}
		for (Entity candidate : level.getEntities(root, bounds)) {
			collectDroneDisplayLayer(root, candidate, displays, seen);
		}
	}

	private static void collectDroneDisplayLayer(
			Entity root,
			Entity candidate,
			List<Display.ItemDisplay> displays,
			Set<UUID> seen
	) {
		if (root == null
				|| !(candidate instanceof Display.ItemDisplay display)
				|| displays == null
				|| seen == null
				|| !candidate.isAlive()) {
			return;
		}
		if (!candidate.getTags().contains(DRONE_DISPLAY_TAG)) {
			return;
		}
		if (!candidate.getTags().contains(DRONE_DISPLAY_OWNER_TAG_PREFIX + root.getUUID())) {
			return;
		}
		if (seen.add(candidate.getUUID())) {
			displays.add(display);
		}
	}

	private static void syncDroneDisplayLayers(Entity root) {
		if (root == null || !(root.level() instanceof ServerLevel level)) {
			return;
		}

		DroneItem.DroneType droneType = resolveDroneType(root);
		int kamikazePower = resolveDroneKamikazePower(root);
		boolean nightVision = hasDroneNightVisionModule(root);
		boolean autoAim = hasDroneAutoAimModule(root);
		boolean microphone = hasDroneMicrophoneModule(root);
		DyeColor paintColor = resolveDronePaintColor(root);

		Map<String, ItemStack> desiredLayers = new LinkedHashMap<>();
		desiredLayers.put(
				DRONE_DISPLAY_LAYER_BASE,
				DroneItem.createDisplayStack(ModItems.DRONE, droneType, kamikazePower, nightVision, autoAim, microphone, paintColor)
		);
		for (DroneItem.DisplayLayer layer : DroneItem.resolveDisplayLayers(droneType, kamikazePower, nightVision, autoAim, microphone, paintColor)) {
			if (layer == null || layer.key() == null || layer.key().isBlank() || layer.modelId() == null) {
				continue;
			}
			desiredLayers.put(layer.key(), DroneItem.createDisplayLayerStack(layer.modelId()));
		}

		Map<String, Display.ItemDisplay> existingLayers = new LinkedHashMap<>();
		for (Display.ItemDisplay display : findDroneDisplayLayers(root)) {
			String layerKey = resolveDroneDisplayLayerKey(display);
			if (layerKey == null || layerKey.isBlank()) {
				layerKey = DRONE_DISPLAY_LAYER_BASE;
			}
			Display.ItemDisplay previous = existingLayers.putIfAbsent(layerKey, display);
			if (previous != null && previous != display) {
				display.discard();
			}
		}

		for (Map.Entry<String, Display.ItemDisplay> entry : existingLayers.entrySet()) {
			if (desiredLayers.containsKey(entry.getKey())) {
				continue;
			}
			entry.getValue().discard();
		}

		for (Map.Entry<String, ItemStack> entry : desiredLayers.entrySet()) {
			String layerKey = entry.getKey();
			Display.ItemDisplay existing = existingLayers.get(layerKey);
			if (existing != null) {
				existing.setItemStack(entry.getValue());
				collapseDroneDisplayHitbox(existing);
				if (DRONE_DISPLAY_LAYER_BASE.equals(layerKey)) {
					DISPLAYS_BY_DRONE.put(root.getUUID(), existing.getUUID());
				}
				continue;
			}
			Display.ItemDisplay created = createDroneDisplay(
					level,
					root.position(),
					root.getYRot(),
					root.getXRot(),
					entry.getValue(),
					layerKey,
					resolveDroneDisplayYOffset(root)
			);
			created.addTag(DRONE_DISPLAY_OWNER_TAG_PREFIX + root.getUUID());
			level.addFreshEntity(created);
			rememberDroneDisplayLayer(root, created);
			if (DRONE_DISPLAY_LAYER_BASE.equals(layerKey)) {
				DISPLAYS_BY_DRONE.put(root.getUUID(), created.getUUID());
			}
		}
	}

	private static String resolveDroneDisplayLayerKey(Entity entity) {
		if (entity == null) {
			return null;
		}
		for (String tag : entity.getTags()) {
			if (tag == null || !tag.startsWith(DRONE_DISPLAY_LAYER_TAG_PREFIX)) {
				continue;
			}
			return tag.substring(DRONE_DISPLAY_LAYER_TAG_PREFIX.length());
		}
		return null;
	}

	private static void applyDynamicDroneDisplayLayer(
			Display.ItemDisplay display,
			DyeColor paintColor,
			int cameraPitch,
			boolean propellersActive,
			int kamikazePower,
			DroneAutoAimDisplayAnimationState autoAimAnimation
	) {
		if (display == null) {
			return;
		}
		String layerKey = resolveDroneDisplayLayerKey(display);
		if (layerKey == null || layerKey.isBlank()) {
			return;
		}

		Identifier desiredModel = null;
		if (DroneItem.CAMERA_LAYER_KEY.equals(layerKey)) {
			desiredModel = DroneItem.cameraLayerModelForAngle(cameraPitch);
		} else if (DroneItem.isNightVisionLayerKey(layerKey)) {
			desiredModel = DroneItem.nightVisionLayerModelForAngle(cameraPitch);
		} else if (DroneItem.isKamikazeLayerKey(layerKey)) {
			desiredModel = DroneItem.kamikazeLayerModelForPower(kamikazePower);
		} else if (DroneItem.isTurretLayerKey(layerKey)) {
			desiredModel = DroneItem.turretLayerModelForAngle(cameraPitch);
		} else if (DroneItem.isPropellerLayerKey(layerKey)) {
			desiredModel = DroneItem.propellerLayerModel(
					layerKey,
					paintColor,
					propellersActive ? 1 : 0
			);
		} else if (DroneItem.isAutoAimTentacleLayerKey(layerKey)) {
			desiredModel = DroneItem.autoAimTentacleLayerModel(
					layerKey,
					resolveAutoAimTentacleDisplayFrame(layerKey, autoAimAnimation)
			);
		}
		if (desiredModel == null) {
			return;
		}
		updateDroneDisplayLayerModel(display, desiredModel);
		if (DroneItem.isPropellerLayerKey(layerKey)) {
			display.setTransformation(Transformation.identity());
		}
	}

	private static DroneAutoAimDisplayAnimationState resolveDroneAutoAimDisplayAnimation(Entity root) {
		if (root == null || root.level() == null) {
			return null;
		}
		DroneAutoAimDisplayAnimationState state = AUTO_AIM_DISPLAY_ANIMATIONS.get(root.getUUID());
		long gameTime = root.level().getGameTime();
		if (state == null) {
			state = createDroneAutoAimDisplayAnimation(root, gameTime);
			AUTO_AIM_DISPLAY_ANIMATIONS.put(root.getUUID(), state);
		} else {
			advanceDroneAutoAimDisplayAnimation(root, state, gameTime);
		}
		return state;
	}

	private static DroneAutoAimDisplayAnimationState createDroneAutoAimDisplayAnimation(Entity root, long gameTime) {
		RandomSource random = root == null ? RandomSource.create() : root.getRandom();
		int primaryFrameRow = random.nextInt(DRONE_AUTO_AIM_DISPLAY_FRAME_PAIR_COUNT);
		int secondaryFrameRow = pickRandomAutoAimDisplayFramePairRow(random, primaryFrameRow, primaryFrameRow);
		return new DroneAutoAimDisplayAnimationState(
				primaryFrameRow,
				secondaryFrameRow,
				gameTime + sampleAutoAimDisplayFrameHoldTicks(random),
				gameTime + sampleAutoAimDisplayFrameHoldTicks(random)
		);
	}

	private static void advanceDroneAutoAimDisplayAnimation(Entity root, DroneAutoAimDisplayAnimationState state, long gameTime) {
		if (root == null || state == null || root.level() == null) {
			return;
		}
		RandomSource random = root.getRandom();
		if (gameTime >= state.nextPrimaryChangeTick()) {
			state.setPrimaryFrameRow(pickRandomAutoAimDisplayFramePairRow(
					random,
					state.secondaryFrameRow(),
					state.primaryFrameRow()
			));
			state.setNextPrimaryChangeTick(gameTime + sampleAutoAimDisplayFrameHoldTicks(random));
		}
		if (gameTime >= state.nextSecondaryChangeTick()) {
			state.setSecondaryFrameRow(pickRandomAutoAimDisplayFramePairRow(
					random,
					state.primaryFrameRow(),
					state.secondaryFrameRow()
			));
			state.setNextSecondaryChangeTick(gameTime + sampleAutoAimDisplayFrameHoldTicks(random));
		}
	}

	private static int pickRandomAutoAimDisplayFramePairRow(RandomSource random, int excludedA, int excludedB) {
		int[] candidates = new int[DRONE_AUTO_AIM_DISPLAY_FRAME_PAIR_COUNT];
		int count = 0;
		for (int row = 0; row < DRONE_AUTO_AIM_DISPLAY_FRAME_PAIR_COUNT; row++) {
			if (row == excludedA || row == excludedB) {
				continue;
			}
			candidates[count++] = row;
		}
		if (count <= 0) {
			return 0;
		}
		RandomSource resolvedRandom = random == null ? RandomSource.create() : random;
		return candidates[resolvedRandom.nextInt(count)];
	}

	private static long sampleAutoAimDisplayFrameHoldTicks(RandomSource random) {
		RandomSource resolvedRandom = random == null ? RandomSource.create() : random;
		if (DRONE_AUTO_AIM_DISPLAY_MAX_FRAME_HOLD_TICKS <= DRONE_AUTO_AIM_DISPLAY_MIN_FRAME_HOLD_TICKS) {
			return DRONE_AUTO_AIM_DISPLAY_MIN_FRAME_HOLD_TICKS;
		}
		long spread = DRONE_AUTO_AIM_DISPLAY_MAX_FRAME_HOLD_TICKS - DRONE_AUTO_AIM_DISPLAY_MIN_FRAME_HOLD_TICKS + 1L;
		return DRONE_AUTO_AIM_DISPLAY_MIN_FRAME_HOLD_TICKS + resolvedRandom.nextInt((int) spread);
	}

	private static int resolveAutoAimTentacleDisplayFrame(
			String layerKey,
			DroneAutoAimDisplayAnimationState autoAimAnimation
	) {
		if (autoAimAnimation == null) {
			return 0;
		}
		if (DroneItem.AUTO_AIM_RIGHT_FRONT_LAYER_KEY.equals(layerKey)) {
			return autoAimAnimation.primaryFrameRow() * 2;
		}
		if (DroneItem.AUTO_AIM_LEFT_BOTTOM_LAYER_KEY.equals(layerKey)) {
			return autoAimAnimation.primaryFrameRow() * 2 + 1;
		}
		if (DroneItem.AUTO_AIM_LEFT_FRONT_LAYER_KEY.equals(layerKey)) {
			return autoAimAnimation.secondaryFrameRow() * 2;
		}
		if (DroneItem.AUTO_AIM_RIGHT_BOTTOM_LAYER_KEY.equals(layerKey)) {
			return autoAimAnimation.secondaryFrameRow() * 2 + 1;
		}
		return 0;
	}

	private static void updateDroneDisplayLayerModel(Display.ItemDisplay display, Identifier desiredModel) {
		if (display == null || desiredModel == null) {
			return;
		}
		Identifier currentModel = DroneItem.getDisplayModelOverride(display.getItemStack());
		if (Objects.equals(currentModel, desiredModel)) {
			return;
		}
		display.setItemStack(DroneItem.createDisplayLayerStack(desiredModel));
		display.hurtMarked = true;
	}

	private static int visualDroneCameraPitch(float pitch) {
		float downwardPitch = net.minecraft.util.Mth.clamp(pitch, 0.0F, 90.0F);
		return net.minecraft.util.Mth.clamp(Math.round(90.0F - downwardPitch), 0, 90);
	}

	private static boolean shouldSpinDronePropellers(Entity root) {
		if (root == null || !root.isAlive()) {
			return false;
		}
		if (isDroneActivelyControlled(root) || isDroneHeldByScreen(root)) {
			return true;
		}
		UncontrolledDroneState state = UNCONTROLLED_DRONES.get(root.getUUID());
		return state != null && isUncontrolledReleaseGlideActive(root, state);
	}

	private static Entity findDroneCameraAnchor(Entity root) {
		if (root == null || !(root.level() instanceof ServerLevel level)) {
			return null;
		}
		UUID anchorId = CAMERA_ANCHORS_BY_DRONE.get(root.getUUID());
		Entity anchor = anchorId == null ? null : level.getEntity(anchorId);
		if (anchor != null && anchor.getTags().contains(DRONE_CAMERA_TAG)) {
			return anchor;
		}
		for (Entity candidate : level.getEntities(root, root.getBoundingBox().inflate(16.0D))) {
			if (!candidate.getTags().contains(DRONE_CAMERA_TAG)) {
				continue;
			}
			if (candidate.getTags().contains(DRONE_CAMERA_OWNER_TAG_PREFIX + root.getUUID())) {
				CAMERA_ANCHORS_BY_DRONE.put(root.getUUID(), candidate.getUUID());
				return candidate;
			}
		}
		return null;
	}

	private static Entity ensureDroneCameraAnchor(Entity root) {
		if (root == null || !root.isAlive() || !(root.level() instanceof ServerLevel level)) {
			return null;
		}
		Entity anchor = findDroneCameraAnchor(root);
		if (anchor != null) {
			return anchor;
		}
		Vec3 origin = resolveSafeDroneCameraOrigin(root, droneCameraOrigin(root));
		Interaction created = createDroneCameraAnchor(level, origin, root.getYRot(), root.getXRot());
		created.addTag(DRONE_CAMERA_OWNER_TAG_PREFIX + root.getUUID());
		level.addFreshEntity(created);
		CAMERA_ANCHORS_BY_DRONE.put(root.getUUID(), created.getUUID());
		return created;
	}

	private static void syncDroneCameraAnchor(Entity root, Vec3 velocity) {
		Entity anchor = ensureDroneCameraAnchor(root);
		if (anchor == null) {
			return;
		}
		Vec3 origin = resolveSafeDroneCameraOrigin(root, droneCameraOrigin(root));
		anchor.setPos(origin.x, origin.y, origin.z);
		anchor.setYRot(root.getYRot());
		anchor.setXRot(root.getXRot());
		anchor.setDeltaMovement(velocity == null ? Vec3.ZERO : velocity);
		anchor.hurtMarked = true;
	}

	private static Vec3 resolveSafeDroneCameraOrigin(Entity root, Vec3 desiredOrigin) {
		if (root == null || desiredOrigin == null || !(root.level() instanceof ServerLevel level)) {
			return desiredOrigin == null ? Vec3.ZERO : desiredOrigin;
		}
		if (!isCameraOriginInsideSolid(level, desiredOrigin)) {
			return desiredOrigin;
		}
		double minY = root.getY() + 0.01D;
		Vec3 best = null;
		double bestDistanceSqr = Double.POSITIVE_INFINITY;
		for (double yOffset : DRONE_CAMERA_ESCAPE_Y_OFFSETS) {
			double candidateY = desiredOrigin.y + yOffset;
			if (candidateY < minY) {
				continue;
			}
			for (int xStep = -DRONE_CAMERA_ESCAPE_XZ_RADIUS_STEPS; xStep <= DRONE_CAMERA_ESCAPE_XZ_RADIUS_STEPS; xStep++) {
				for (int zStep = -DRONE_CAMERA_ESCAPE_XZ_RADIUS_STEPS; zStep <= DRONE_CAMERA_ESCAPE_XZ_RADIUS_STEPS; zStep++) {
					if (xStep == 0 && zStep == 0 && yOffset == 0.0D) {
						continue;
					}
					double xOffset = xStep * DRONE_CAMERA_ESCAPE_STEP;
					double zOffset = zStep * DRONE_CAMERA_ESCAPE_STEP;
					Vec3 candidate = new Vec3(
							desiredOrigin.x + xOffset,
							candidateY,
							desiredOrigin.z + zOffset
					);
					if (isCameraOriginInsideSolid(level, candidate)) {
						continue;
					}
					double distanceSqr = xOffset * xOffset + yOffset * yOffset + zOffset * zOffset;
					if (distanceSqr < bestDistanceSqr) {
						best = candidate;
						bestDistanceSqr = distanceSqr;
					}
				}
			}
		}
		if (best != null) {
			return best;
		}
		return new Vec3(desiredOrigin.x, minY, desiredOrigin.z);
	}

	private static boolean isCameraOriginInsideSolid(ServerLevel level, Vec3 origin) {
		if (level == null || origin == null) {
			return false;
		}
		AABB probe = new AABB(
				origin.x - 0.04D,
				origin.y - 0.04D,
				origin.z - 0.04D,
				origin.x + 0.04D,
				origin.y + 0.04D,
				origin.z + 0.04D
		);
		return boxHitsSolidCollision(level, probe);
	}

	private static void setHotbarVisualHidden(ServerPlayer player, boolean hidden) {
		if (player == null || player.connection == null) {
			return;
		}

		AbstractContainerMenu menu = player.inventoryMenu;
		int stateId = menu.incrementStateId();
		for (int index = 0; index < 9; index++) {
			ItemStack stack = hidden ? ItemStack.EMPTY : player.getInventory().getItem(index).copy();
			player.connection.send(new ClientboundContainerSetSlotPacket(
					menu.containerId,
					stateId,
					PLAYER_HOTBAR_MENU_SLOT_START + index,
					stack
			));
		}
		ItemStack offhand = hidden ? ItemStack.EMPTY : player.getOffhandItem().copy();
		player.connection.send(new ClientboundContainerSetSlotPacket(
				menu.containerId,
				stateId,
				PLAYER_OFFHAND_MENU_SLOT,
				offhand
		));
	}

	private static Vec3 resolvePlacementPosition(UseOnContext context) {
		net.minecraft.core.Direction face = context.getClickedFace();
		net.minecraft.core.BlockPos anchor = context.getClickedPos().relative(face);
		return resolvePlacementPosition(anchor, face);
	}

	private static Vec3 resolvePlacementPosition(BlockPos anchor, Direction face) {
		if (anchor == null || face == null) {
			return Vec3.ZERO;
		}
		double yOffset = face == net.minecraft.core.Direction.UP ? 0.0D : DRONE_SPAWN_Y_OFFSET;
		return new Vec3(anchor.getX() + 0.5D, anchor.getY() + yOffset, anchor.getZ() + 0.5D);
	}

	private static AABB droneBoxAt(Vec3 position) {
		return DroneGeometry.boxAt(position);
	}

	private static Vec3 droneCameraOrigin(Entity root) {
		if (root == null) {
			return Vec3.ZERO;
		}
		return droneCameraOrigin(root.position(), resolveDroneVisualLift(resolveDroneType(root)));
	}

	private static Vec3 droneCameraOrigin(Vec3 rootPosition) {
		return droneCameraOrigin(rootPosition, 0.0D);
	}

	private static Vec3 droneCameraOrigin(Vec3 rootPosition, double additionalLift) {
		Vec3 base = DroneGeometry.cameraOrigin(rootPosition);
		return additionalLift == 0.0D ? base : base.add(0.0D, additionalLift, 0.0D);
	}

	private static double resolveDroneDisplayYOffset(Entity root) {
		return resolveDroneDisplayYOffset(resolveDroneType(root));
	}

	private static double resolveDroneDisplayYOffset(DroneItem.DroneType type) {
		return DRONE_DISPLAY_Y_OFFSET + resolveDroneVisualLift(type);
	}

	private static double resolveDroneVisualLift(Entity root) {
		return resolveDroneVisualLift(resolveDroneType(root));
	}

	private static double resolveDroneVisualLift(DroneItem.DroneType type) {
		return switch (type == null ? DroneItem.DroneType.NORMAL : type) {
			case KAMIKAZE -> DRONE_KAMIKAZE_VISUAL_LIFT;
			case COMBAT -> DRONE_TURRET_VISUAL_LIFT;
			default -> 0.0D;
		};
	}

	private static void notifyDroneNetworkChanged(Entity root) {
		if (root == null || !(root.level() instanceof ServerLevel level)) {
			return;
		}
		MonitorScreenSystem.onDroneNetworkChanged(
				level.getServer(),
				BluetoothLinkSystem.droneEndpoint(level.dimension(), root.blockPosition(), root.getUUID())
		);
	}

	private static void syncPersistentDroneLocation(Entity root, Vec3 previousPos) {
		if (root == null || !root.isAlive() || !(root.level() instanceof ServerLevel level)) {
			return;
		}
		BlockPos previousBlockPos = previousPos == null ? null : BlockPos.containing(previousPos);
		BlockPos currentBlockPos = root.blockPosition();
		rememberLastKnownDroneFeedState(root);
		if (Objects.equals(previousBlockPos, currentBlockPos)) {
			return;
		}
		BluetoothLinkSystem.refreshDroneEndpoint(level.getServer(), level.dimension(), currentBlockPos, root.getUUID());
	}

	private static void persistDroneRestartRecoveryState(Entity root, UncontrolledDroneState state) {
		if (root == null || !root.isAlive()) {
			return;
		}
		Vec3 target = state != null && state.restartRecoveryTarget() != null
				? state.restartRecoveryTarget()
				: root.position();
		boolean airborne = !isDroneSafelyParked(root, state) || (state != null && state.hasRestartRecoveryTarget());
		String replacement = DRONE_RESTART_RECOVERY_TAG_PREFIX
				+ (airborne ? "air:" : "ground:")
				+ Double.toString(target.x) + ":" + Double.toString(target.y) + ":" + Double.toString(target.z);
		for (String tag : new ArrayList<>(root.getTags())) {
			if (tag == null || !tag.startsWith(DRONE_RESTART_RECOVERY_TAG_PREFIX)) {
				continue;
			}
			if (tag.equals(replacement)) {
				return;
			}
			root.removeTag(tag);
		}
		root.addTag(replacement);
	}

	private static DroneRestartRecoveryState readDroneRestartRecoveryState(Entity root) {
		if (root == null) {
			return null;
		}
		for (String tag : root.getTags()) {
			if (tag == null || !tag.startsWith(DRONE_RESTART_RECOVERY_TAG_PREFIX)) {
				continue;
			}
			String[] parts = tag.substring(DRONE_RESTART_RECOVERY_TAG_PREFIX.length()).split(":", -1);
			if (parts.length != 4 || (!"air".equals(parts[0]) && !"ground".equals(parts[0]))) {
				continue;
			}
			try {
				Vec3 target = new Vec3(
						Double.parseDouble(parts[1]),
						Double.parseDouble(parts[2]),
						Double.parseDouble(parts[3])
				);
				if (Double.isFinite(target.x) && Double.isFinite(target.y) && Double.isFinite(target.z)) {
					return new DroneRestartRecoveryState("air".equals(parts[0]), target);
				}
			} catch (NumberFormatException ignored) {
			}
		}
		return null;
	}

	private static boolean isDroneSafelyParked(Entity root, UncontrolledDroneState state) {
		if (root == null || !root.onGround() || !hasSupportingBlockBelow(root)) {
			return false;
		}
		Vec3 velocity = state == null ? root.getDeltaMovement() : state.velocity();
		return velocity == null || velocity.lengthSqr() <= UNCONTROLLED_SETTLED_HORIZONTAL_SPEED_SQR;
	}

	private static boolean isAtDroneRestartRecoveryTarget(Entity root, Vec3 target) {
		return root != null && target != null
				&& root.position().distanceToSqr(target) <= DRONE_RESTART_RECOVERY_COMPLETE_DISTANCE * DRONE_RESTART_RECOVERY_COMPLETE_DISTANCE;
	}

	private static Vec3 droneRestartRecoveryVelocity(Vec3 current, Vec3 target) {
		if (current == null || target == null) {
			return Vec3.ZERO;
		}
		Vec3 delta = target.subtract(current);
		double distance = delta.length();
		if (distance <= DRONE_RESTART_RECOVERY_COMPLETE_DISTANCE) {
			return delta;
		}
		return delta.scale(Math.min(DRONE_RESTART_RECOVERY_SPEED, distance) / distance);
	}

	private static void finishDroneRestartRecovery(Entity root, UncontrolledDroneState state) {
		if (root == null || state == null || state.restartRecoveryTarget() == null) {
			return;
		}
		Vec3 target = state.restartRecoveryTarget();
		root.setPos(target.x, target.y, target.z);
		root.setBoundingBox(droneBoxAt(target));
		root.setDeltaMovement(Vec3.ZERO);
		state.setVelocity(Vec3.ZERO);
		state.clearRestartRecovery();
		root.hurtMarked = true;
	}

	private static Entity findDroneRoot(MinecraftServer server, net.minecraft.resources.ResourceKey<Level> dimension, UUID droneUuid) {
		Entity entity = findEntity(server, dimension, droneUuid);
		return entity != null && entity.getTags().contains(DRONE_ROOT_TAG) ? entity : null;
	}

	private static Entity findDroneRoot(MinecraftServer server, UUID droneUuid) {
		if (server == null || droneUuid == null) {
			return null;
		}
		for (ServerLevel level : server.getAllLevels()) {
			Entity entity = findDroneRoot(server, level.dimension(), droneUuid);
			if (entity != null) {
				return entity;
			}
		}
		return null;
	}

	private static Entity findEntity(MinecraftServer server, net.minecraft.resources.ResourceKey<Level> dimension, UUID uuid) {
		if (server == null || dimension == null || uuid == null) {
			return null;
		}
		ServerLevel level = server.getLevel(dimension);
		return level == null ? null : level.getEntity(uuid);
	}

	private static Entity findEntity(MinecraftServer server, UUID uuid) {
		if (server == null || uuid == null) {
			return null;
		}
		for (ServerLevel level : server.getAllLevels()) {
			Entity entity = level.getEntity(uuid);
			if (entity != null) {
				return entity;
			}
		}
		return null;
	}

	private static void syncPassengerAttachment(Entity vehicle) {
		if (vehicle == null || !(vehicle.level() instanceof ServerLevel level)) {
			return;
		}

		ClientboundSetPassengersPacket packet = new ClientboundSetPassengersPacket(vehicle);
		for (ServerPlayer viewer : level.players()) {
			viewer.connection.send(packet);
		}
	}

	private static final class UncontrolledDroneState {
		private final UUID droneUuid;
		private final net.minecraft.resources.ResourceKey<Level> dimension;
		private Vec3 velocity;
		private float yaw;
		private float pitch;
		private DroneAutoAimTarget autoAimTarget;
		private long holdWithoutGravityUntilTick;
		private double forwardDrive;
		private double strafeDrive;
		private double displayForwardDrive;
		private double displayStrafeDrive;
		private boolean driveStateKnown;
		private Vec3 lastPosition;
		private double surfaceWear;
		private long lastSurfaceWearContactTick = Long.MIN_VALUE;
		private Vec3 restartRecoveryTarget;
		private boolean restartLandingProtected;

		private UncontrolledDroneState(UUID droneUuid, net.minecraft.resources.ResourceKey<Level> dimension, Vec3 velocity, float yaw, float pitch) {
			this(droneUuid, dimension, velocity, yaw, pitch, null, Long.MIN_VALUE);
		}

		private UncontrolledDroneState(
				UUID droneUuid,
				net.minecraft.resources.ResourceKey<Level> dimension,
				Vec3 velocity,
				float yaw,
				float pitch,
				DroneAutoAimTarget autoAimTarget,
				long holdWithoutGravityUntilTick
		) {
			this(droneUuid, dimension, velocity, yaw, pitch, autoAimTarget, holdWithoutGravityUntilTick, 0.0D, 0.0D, 0.0D, 0.0D, false);
		}

		private UncontrolledDroneState(
				UUID droneUuid,
				net.minecraft.resources.ResourceKey<Level> dimension,
				Vec3 velocity,
				float yaw,
				float pitch,
				DroneAutoAimTarget autoAimTarget,
				long holdWithoutGravityUntilTick,
				double forwardDrive,
				double strafeDrive,
				double displayForwardDrive,
				double displayStrafeDrive,
				boolean driveStateKnown
		) {
			this.droneUuid = droneUuid;
			this.dimension = dimension;
			this.velocity = velocity == null ? Vec3.ZERO : velocity;
			this.yaw = yaw;
			this.pitch = pitch;
			this.autoAimTarget = autoAimTarget;
			this.holdWithoutGravityUntilTick = holdWithoutGravityUntilTick;
			this.forwardDrive = net.minecraft.util.Mth.clamp(forwardDrive, -DroneFlightPhysics.MAX_FORWARD_DRIVE, DroneFlightPhysics.MAX_FORWARD_DRIVE);
			this.strafeDrive = net.minecraft.util.Mth.clamp(strafeDrive, -DroneFlightPhysics.MAX_STRAFE_DRIVE, DroneFlightPhysics.MAX_STRAFE_DRIVE);
			this.displayForwardDrive = net.minecraft.util.Mth.clamp(displayForwardDrive, -DroneFlightPhysics.MAX_FORWARD_DRIVE, DroneFlightPhysics.MAX_FORWARD_DRIVE);
			this.displayStrafeDrive = net.minecraft.util.Mth.clamp(displayStrafeDrive, -DroneFlightPhysics.MAX_STRAFE_DRIVE, DroneFlightPhysics.MAX_STRAFE_DRIVE);
			this.driveStateKnown = driveStateKnown;
		}

		private UUID droneUuid() {
			return this.droneUuid;
		}

		private net.minecraft.resources.ResourceKey<Level> dimension() {
			return this.dimension;
		}

		private Vec3 velocity() {
			return this.velocity;
		}

		private void setVelocity(Vec3 velocity) {
			this.velocity = velocity == null ? Vec3.ZERO : velocity;
		}

		private Vec3 lastPosition() {
			return this.lastPosition;
		}

		private void setLastPosition(Vec3 lastPosition) {
			this.lastPosition = lastPosition;
		}

		private float yaw() {
			return this.yaw;
		}

		private void setYaw(float yaw) {
			this.yaw = yaw;
		}

		private float pitch() {
			return this.pitch;
		}

		private void setPitch(float pitch) {
			this.pitch = pitch;
		}

		private DroneAutoAimTarget autoAimTarget() {
			return this.autoAimTarget;
		}

		private void setAutoAimTarget(DroneAutoAimTarget autoAimTarget) {
			this.autoAimTarget = autoAimTarget;
		}

		private long holdWithoutGravityUntilTick() {
			return this.holdWithoutGravityUntilTick;
		}

		private double forwardDrive() {
			return this.forwardDrive;
		}

		private double strafeDrive() {
			return this.strafeDrive;
		}

		private double displayForwardDrive() {
			return this.displayForwardDrive;
		}

		private void setDisplayForwardDrive(double displayForwardDrive) {
			this.displayForwardDrive = net.minecraft.util.Mth.clamp(displayForwardDrive, -DroneFlightPhysics.MAX_FORWARD_DRIVE, DroneFlightPhysics.MAX_FORWARD_DRIVE);
		}

		private double displayStrafeDrive() {
			return this.displayStrafeDrive;
		}

		private void setDisplayStrafeDrive(double displayStrafeDrive) {
			this.displayStrafeDrive = net.minecraft.util.Mth.clamp(displayStrafeDrive, -DroneFlightPhysics.MAX_STRAFE_DRIVE, DroneFlightPhysics.MAX_STRAFE_DRIVE);
		}

		private boolean hasDriveState() {
			return this.driveStateKnown;
		}

		private double surfaceWear() {
			return this.surfaceWear;
		}

		private void setSurfaceWear(double surfaceWear) {
			this.surfaceWear = net.minecraft.util.Mth.clamp(surfaceWear, 0.0D, DroneImpactModel.SURFACE_WEAR_BREAK_LEVEL);
		}

		private long lastSurfaceWearContactTick() {
			return this.lastSurfaceWearContactTick;
		}

		private void setLastSurfaceWearContactTick(long lastSurfaceWearContactTick) {
			this.lastSurfaceWearContactTick = lastSurfaceWearContactTick;
		}

		private void beginRestartRecovery(Vec3 target) {
			this.restartRecoveryTarget = target;
			this.restartLandingProtected = true;
		}

		private void beginRestartLandingProtection() {
			this.restartLandingProtected = true;
		}

		private Vec3 restartRecoveryTarget() {
			return this.restartRecoveryTarget;
		}

		private boolean hasRestartRecoveryTarget() {
			return this.restartRecoveryTarget != null;
		}

		private boolean isRestartLandingProtected() {
			return this.restartLandingProtected;
		}

		private void clearRestartRecovery() {
			this.restartRecoveryTarget = null;
			this.restartLandingProtected = false;
		}
	}

	private record DroneChunkTicketKey(net.minecraft.resources.ResourceKey<Level> dimension, long chunkLong, int radius, boolean simulation) {
	}

	private record PendingDroneControlStart(
			UUID droneUuid,
			net.minecraft.resources.ResourceKey<Level> droneDimension,
			BlockPos fallbackPos,
			long startedAtTick
	) {
	}

	private record DroneRestartRecoveryState(boolean airborne, Vec3 target) {
	}

	private record DroneScreenStreamLoadState(
			UUID droneUuid,
			net.minecraft.resources.ResourceKey<Level> dimension,
			Vec3 dronePos,
			Vec3 cameraPos,
			float cameraYaw,
			float cameraPitch
	) {
		private DroneCameraChunkTarget cameraTarget() {
			Vec3 pos = this.cameraPos == null ? Vec3.ZERO : this.cameraPos;
			return new DroneCameraChunkTarget(pos.x, pos.z, this.cameraYaw);
		}
	}

	public record DroneScreenStreamReference(
			UUID droneUuid,
			net.minecraft.resources.ResourceKey<Level> dimension,
			BlockPos pos
	) {
	}

	private record DroneCameraChunkTarget(double x, double z, float yaw) {
	}

	private static final class OperatorBodyMirror {
		private final int entityId;
		private final UUID profileId;
		private final GameProfile profile;
		private boolean spawned;

		private OperatorBodyMirror(int entityId, UUID profileId, GameProfile profile) {
			this.entityId = entityId;
			this.profileId = profileId;
			this.profile = profile;
		}

		private int entityId() {
			return entityId;
		}

		private UUID profileId() {
			return profileId;
		}

		private GameProfile profile() {
			return profile;
		}

		private boolean spawned() {
			return spawned;
		}

		private void setSpawned(boolean spawned) {
			this.spawned = spawned;
		}
	}

	private record DroneInputState(boolean forward, boolean backward, boolean left, boolean right, boolean jump, boolean shift, boolean sprint) {
		private static final DroneInputState EMPTY = new DroneInputState(false, false, false, false, false, false, false);
	}

	private static final class TurretInventory extends SimpleContainer {
		private TurretInventory() {
			super(DRONE_TURRET_INVENTORY_SIZE);
		}

		@Override
		public boolean canPlaceItem(int slot, ItemStack stack) {
			return isDroneTurretProjectileStack(stack);
		}
	}

	private enum DroneAutomationAction {
		PASS,
		SUCCESS,
		FAIL_KEEP
	}

	@FunctionalInterface
	private interface DroneDispenseHandler {
		DroneAutomationAction apply(BlockSource source, ItemStack stack);
	}

	private record PendingDroneLoadDiscard(net.minecraft.resources.ResourceKey<Level> dimension, UUID entityUuid) {
	}

	private record ControlledCollisionState(
			boolean horizontalCollision,
			boolean verticalCollision,
			boolean verticalCollisionBelow
	) {
		private static final ControlledCollisionState NONE = new ControlledCollisionState(false, false, false);
	}

	private record DroneAutoAimAngles(float yaw, float pitch) {
	}

	private static final class DroneControlSession {
		private final UUID droneUuid;
		private final net.minecraft.resources.ResourceKey<Level> droneDimension;
		private Vec3 velocity = Vec3.ZERO;
		private Vec3 intendedVelocity = Vec3.ZERO;
		private Vec3 proxyPos = Vec3.ZERO;
		private Vec3 lastKnownDronePos = Vec3.ZERO;
		private DroneCameraChunkTarget lastKnownCameraTarget = new DroneCameraChunkTarget(0.0D, 0.0D, 0.0F);
		private float controlYaw;
		private float controlPitch;
		private float proxyYaw;
		private float proxyPitch;
		private long missingRootSinceTick = Long.MIN_VALUE;
		private int nextViewSyncTeleportId = CONTROLLED_VIEW_TELEPORT_ID_BASE;
		private double forwardDrive;
		private double strafeDrive;
		private double displayForwardDrive;
		private double displayStrafeDrive;
		private double surfaceWear;
		private long lastSurfaceWearContactTick = Long.MIN_VALUE;
		private boolean hudVisible;
		private String lastHudSnapshot = "";
		private long lastHudTick = Long.MIN_VALUE;
		private DroneAutoAimTarget autoAimTarget;
		private long lastManualLookTick = Long.MIN_VALUE;
		private long turretInputSuppressedUntilTick = Long.MIN_VALUE;
		private long autoAimSelectionSuppressedUntilTick = Long.MIN_VALUE;
		private long startupPositionSuppressedUntilTick = Long.MIN_VALUE;
		private long startupRotationSuppressedUntilTick = Long.MIN_VALUE;

		private DroneControlSession(
				UUID droneUuid,
				net.minecraft.resources.ResourceKey<Level> droneDimension
		) {
			this.droneUuid = droneUuid;
			this.droneDimension = droneDimension;
		}

		private UUID droneUuid() {
			return this.droneUuid;
		}

		private net.minecraft.resources.ResourceKey<Level> droneDimension() {
			return this.droneDimension;
		}

		private Vec3 velocity() {
			return this.velocity;
		}

		private void setVelocity(Vec3 velocity) {
			this.velocity = velocity == null ? Vec3.ZERO : velocity;
		}

		private Vec3 intendedVelocity() {
			return this.intendedVelocity;
		}

		private void setIntendedVelocity(Vec3 intendedVelocity) {
			this.intendedVelocity = intendedVelocity == null ? Vec3.ZERO : intendedVelocity;
		}

		private Vec3 proxyPos() {
			return this.proxyPos;
		}

		private void setProxyPos(Vec3 proxyPos) {
			this.proxyPos = proxyPos == null ? Vec3.ZERO : proxyPos;
		}

		private Vec3 lastKnownDronePos() {
			return this.lastKnownDronePos == null ? this.proxyPos() : this.lastKnownDronePos;
		}

		private DroneCameraChunkTarget lastKnownCameraTarget() {
			return this.lastKnownCameraTarget == null
					? new DroneCameraChunkTarget(this.lastKnownDronePos().x, this.lastKnownDronePos().z, this.proxyYaw)
					: this.lastKnownCameraTarget;
		}

		private void refreshKnownDroneLocation(Entity root) {
			if (root == null || !root.isAlive()) {
				return;
			}
			syncPersistentDroneLocation(root, this.lastKnownDronePos);
			this.lastKnownDronePos = root.position();
			this.lastKnownCameraTarget = resolveDroneCameraChunkTarget(root);
			this.missingRootSinceTick = Long.MIN_VALUE;
		}

		private boolean markDroneRootMissing(long nowTick) {
			if (nowTick == Long.MAX_VALUE) {
				return false;
			}
			if (this.missingRootSinceTick == Long.MIN_VALUE) {
				this.missingRootSinceTick = nowTick;
			}
			return nowTick - this.missingRootSinceTick <= CONTROLLED_DRONE_MISSING_ROOT_GRACE_TICKS;
		}

		private float controlYaw() {
			return this.controlYaw;
		}

		private void setControlYaw(float controlYaw) {
			this.controlYaw = controlYaw;
		}

		private float controlPitch() {
			return this.controlPitch;
		}

		private void setControlPitch(float controlPitch) {
			this.controlPitch = controlPitch;
		}

		private float proxyYaw() {
			return this.proxyYaw;
		}

		private void setProxyYaw(float proxyYaw) {
			this.proxyYaw = proxyYaw;
		}

		private float proxyPitch() {
			return this.proxyPitch;
		}

		private void setProxyPitch(float proxyPitch) {
			this.proxyPitch = proxyPitch;
		}

		private void suppressStartupRotationUntil(long gameTime) {
			if (gameTime == Long.MIN_VALUE) {
				return;
			}
			this.startupRotationSuppressedUntilTick = Math.max(this.startupRotationSuppressedUntilTick, gameTime);
		}

		private boolean shouldSuppressStartupRotation(long gameTime) {
			if (this.startupRotationSuppressedUntilTick == Long.MIN_VALUE) {
				return false;
			}
			if (gameTime == Long.MIN_VALUE) {
				return true;
			}
			if (gameTime <= this.startupRotationSuppressedUntilTick) {
				return true;
			}
			this.startupRotationSuppressedUntilTick = Long.MIN_VALUE;
			return false;
		}

		private void acknowledgeStartupViewSync(long gameTime) {
			this.startupPositionSuppressedUntilTick = Long.MIN_VALUE;
			if (this.startupRotationSuppressedUntilTick == Long.MIN_VALUE) {
				return;
			}
			if (gameTime == Long.MIN_VALUE) {
				this.startupRotationSuppressedUntilTick = Long.MIN_VALUE;
				return;
			}
			this.startupRotationSuppressedUntilTick = Math.min(
					this.startupRotationSuppressedUntilTick,
					gameTime + CONTROLLED_DRONE_TELEPORT_ACK_ROTATION_GRACE_TICKS
			);
		}

		private void suppressStartupPositionUntil(long gameTime) {
			if (gameTime != Long.MIN_VALUE) {
				this.startupPositionSuppressedUntilTick = Math.max(this.startupPositionSuppressedUntilTick, gameTime);
			}
		}

		private boolean shouldSuppressStartupPosition(long gameTime) {
			if (this.startupPositionSuppressedUntilTick == Long.MIN_VALUE) {
				return false;
			}
			if (gameTime == Long.MIN_VALUE || gameTime <= this.startupPositionSuppressedUntilTick) {
				return true;
			}
			this.startupPositionSuppressedUntilTick = Long.MIN_VALUE;
			return false;
		}

		private void recordManualLookDelta(float yawDelta, float pitchDelta, long gameTime) {
			if (gameTime == Long.MIN_VALUE) {
				return;
			}
			if (Math.max(Math.abs(yawDelta), Math.abs(pitchDelta)) > DRONE_AUTO_AIM_MANUAL_LOOK_THRESHOLD_DEGREES) {
				this.lastManualLookTick = gameTime;
			}
		}

		private boolean isManualLookRecentlyActive(long gameTime) {
			return gameTime != Long.MIN_VALUE
					&& this.lastManualLookTick != Long.MIN_VALUE
					&& gameTime - this.lastManualLookTick <= DRONE_AUTO_AIM_MANUAL_SUPPRESSION_TICKS;
		}

		private void suppressAutoAimSelectionUntil(long gameTime) {
			this.autoAimSelectionSuppressedUntilTick = Math.max(this.autoAimSelectionSuppressedUntilTick, gameTime);
		}

		private boolean shouldSuppressAutoAimSelection(long gameTime) {
			if (this.autoAimSelectionSuppressedUntilTick == Long.MIN_VALUE) {
				return false;
			}
			if (gameTime == Long.MIN_VALUE || gameTime <= this.autoAimSelectionSuppressedUntilTick) {
				return true;
			}
			this.autoAimSelectionSuppressedUntilTick = Long.MIN_VALUE;
			return false;
		}

		private int nextViewSyncTeleportId() {
			return this.nextViewSyncTeleportId++;
		}

		private double forwardDrive() {
			return this.forwardDrive;
		}

		private void setForwardDrive(double forwardDrive) {
			this.forwardDrive = forwardDrive;
		}

		private double strafeDrive() {
			return this.strafeDrive;
		}

		private void setStrafeDrive(double strafeDrive) {
			this.strafeDrive = strafeDrive;
		}

		private double displayForwardDrive() {
			return this.displayForwardDrive;
		}

		private void setDisplayForwardDrive(double displayForwardDrive) {
			this.displayForwardDrive = displayForwardDrive;
		}

		private double displayStrafeDrive() {
			return this.displayStrafeDrive;
		}

		private void setDisplayStrafeDrive(double displayStrafeDrive) {
			this.displayStrafeDrive = displayStrafeDrive;
		}

		private double surfaceWear() {
			return this.surfaceWear;
		}

		private void setSurfaceWear(double surfaceWear) {
			this.surfaceWear = net.minecraft.util.Mth.clamp(surfaceWear, 0.0D, DroneImpactModel.SURFACE_WEAR_BREAK_LEVEL);
		}

		private long lastSurfaceWearContactTick() {
			return this.lastSurfaceWearContactTick;
		}

		private void setLastSurfaceWearContactTick(long lastSurfaceWearContactTick) {
			this.lastSurfaceWearContactTick = lastSurfaceWearContactTick;
		}

		private boolean hudVisible() {
			return this.hudVisible;
		}

		private void setHudVisible(boolean hudVisible) {
			this.hudVisible = hudVisible;
		}

		private String lastHudSnapshot() {
			return this.lastHudSnapshot;
		}

		private void setLastHudSnapshot(String lastHudSnapshot) {
			this.lastHudSnapshot = lastHudSnapshot == null ? "" : lastHudSnapshot;
		}

		private long lastHudTick() {
			return this.lastHudTick;
		}

		private void setLastHudTick(long lastHudTick) {
			this.lastHudTick = lastHudTick;
		}

		private DroneAutoAimTarget autoAimTarget() {
			return this.autoAimTarget;
		}

		private void setAutoAimTarget(DroneAutoAimTarget autoAimTarget) {
			this.autoAimTarget = autoAimTarget;
		}

		private long turretInputSuppressedUntilTick() {
			return this.turretInputSuppressedUntilTick;
		}

		private void setTurretInputSuppressedUntilTick(long turretInputSuppressedUntilTick) {
			this.turretInputSuppressedUntilTick = turretInputSuppressedUntilTick;
		}

	}

	private sealed interface DroneAutoAimTarget permits DroneAutoAimEntityTarget, DroneAutoAimBlockTarget {
	}

	private record DroneAutoAimEntityTarget(UUID entityUuid) implements DroneAutoAimTarget {
	}

	private record DroneAutoAimBlockTarget(BlockPos blockPos) implements DroneAutoAimTarget {
	}

	private static final class DroneAutoAimDisplayAnimationState {
		private int primaryFrameRow;
		private int secondaryFrameRow;
		private long nextPrimaryChangeTick;
		private long nextSecondaryChangeTick;

		private DroneAutoAimDisplayAnimationState(
				int primaryFrameRow,
				int secondaryFrameRow,
				long nextPrimaryChangeTick,
				long nextSecondaryChangeTick
		) {
			this.primaryFrameRow = net.minecraft.util.Mth.clamp(primaryFrameRow, 0, DRONE_AUTO_AIM_DISPLAY_FRAME_PAIR_COUNT - 1);
			this.secondaryFrameRow = net.minecraft.util.Mth.clamp(secondaryFrameRow, 0, DRONE_AUTO_AIM_DISPLAY_FRAME_PAIR_COUNT - 1);
			this.nextPrimaryChangeTick = nextPrimaryChangeTick;
			this.nextSecondaryChangeTick = nextSecondaryChangeTick;
		}

		private int primaryFrameRow() {
			return this.primaryFrameRow;
		}

		private void setPrimaryFrameRow(int primaryFrameRow) {
			this.primaryFrameRow = net.minecraft.util.Mth.clamp(primaryFrameRow, 0, DRONE_AUTO_AIM_DISPLAY_FRAME_PAIR_COUNT - 1);
		}

		private int secondaryFrameRow() {
			return this.secondaryFrameRow;
		}

		private void setSecondaryFrameRow(int secondaryFrameRow) {
			this.secondaryFrameRow = net.minecraft.util.Mth.clamp(secondaryFrameRow, 0, DRONE_AUTO_AIM_DISPLAY_FRAME_PAIR_COUNT - 1);
		}

		private long nextPrimaryChangeTick() {
			return this.nextPrimaryChangeTick;
		}

		private void setNextPrimaryChangeTick(long nextPrimaryChangeTick) {
			this.nextPrimaryChangeTick = nextPrimaryChangeTick;
		}

		private long nextSecondaryChangeTick() {
			return this.nextSecondaryChangeTick;
		}

		private void setNextSecondaryChangeTick(long nextSecondaryChangeTick) {
			this.nextSecondaryChangeTick = nextSecondaryChangeTick;
		}
	}

	private static final class ControlledOperatorAudioRuntime {
		private final UUID playerUuid;
		private final UUID channelId;
		private final String droneCaptureOwnerKey;
		private final String bodyCaptureOwnerKey;
		private final ControlledOperatorAudioFeed feed = new ControlledOperatorAudioFeed();
		private StaticAudioChannel channel;
		private AudioPlayer player;
		private OpusEncoder encoder;
		private net.minecraft.resources.ResourceKey<Level> audioChannelDimension;
		private boolean closed;

		private ControlledOperatorAudioRuntime(UUID playerUuid) {
			this.playerUuid = playerUuid == null ? UUID.randomUUID() : playerUuid;
			this.channelId = UUID.nameUUIDFromBytes(
					("lg2:drone_control_audio:" + this.playerUuid).getBytes(StandardCharsets.UTF_8)
			);
			this.droneCaptureOwnerKey = "lg2:drone_control_audio:drone:" + this.playerUuid;
			this.bodyCaptureOwnerKey = "lg2:drone_control_audio:body:" + this.playerUuid;
		}

		private boolean sync(MinecraftServer server, ServerPlayer operator, DroneControlSession session) {
			if (this.closed || server == null || operator == null || session == null || !(operator.level() instanceof ServerLevel operatorLevel)) {
				return false;
			}
			if (!ServerVoicechatIntegration.isLoaded()) {
				return true;
			}
			VoicechatApi voicechatApi = ServerVoicechatIntegration.getApi();
			VoicechatServerApi voicechatServerApi = ServerVoicechatIntegration.getServerApi();
			if (voicechatApi == null || voicechatServerApi == null) {
				return true;
			}
			VoicechatConnection connection = voicechatServerApi.getConnectionOf(operator.getUUID());
			if (connection == null) {
				return true;
			}
			if (!ensurePlayback(operatorLevel, connection, voicechatApi, voicechatServerApi)) {
				return false;
			}
			RendererBotCameraSystem.stopAudioCapture(this.bodyCaptureOwnerKey);
			// The controlled player's client already hears vanilla world audio from the proxy drone position.
			// Mirroring the same space again through renderer-bot PCM creates a delayed second copy that crackles.
			RendererBotCameraSystem.stopAudioCapture(this.droneCaptureOwnerKey);
			return true;
		}

		private boolean ensurePlayback(
				ServerLevel operatorLevel,
				VoicechatConnection connection,
				VoicechatApi voicechatApi,
				VoicechatServerApi voicechatServerApi
		) {
			if (this.closed || operatorLevel == null || connection == null || voicechatApi == null || voicechatServerApi == null) {
				return false;
			}
			if (this.channel != null
					&& this.player != null
					&& !this.player.isStopped()
					&& Objects.equals(this.audioChannelDimension, operatorLevel.dimension())) {
				this.channel.addTarget(connection);
				return true;
			}
			closePlayback();
			try {
				OpusEncoder createdEncoder = voicechatApi.createEncoder();
				StaticAudioChannel createdChannel = voicechatServerApi.createStaticAudioChannel(
						this.channelId,
						voicechatApi.fromServerLevel(operatorLevel),
						connection
				);
				if (SpeakerSystem.isSpeakerVolumeCategoryRegistered()) {
					createdChannel.setCategory(SpeakerSystem.speakerVolumeCategoryId());
				}
				AudioPlayer createdPlayer = voicechatServerApi.createAudioPlayer(createdChannel, createdEncoder, this::nextFrame);
				createdPlayer.startPlaying();
				this.encoder = createdEncoder;
				this.channel = createdChannel;
				this.player = createdPlayer;
				this.audioChannelDimension = operatorLevel.dimension();
				return true;
			} catch (RuntimeException exception) {
				Lg2.LOGGER.debug("Failed to initialize controlled drone audio playback for {}", this.playerUuid, exception);
				closePlayback();
				return false;
			}
		}

		private void offerBodyVoicePacket(UUID senderUuid, byte[] opusData, float gain, VoicechatApi voicechatApi) {
			this.feed.offerBodyVoicePacket(senderUuid, opusData, gain, voicechatApi);
		}

		private short[] nextFrame() {
			short[] frame = this.feed.frameAt(System.nanoTime());
			return frame == null ? CONTROLLED_OPERATOR_AUDIO_SILENCE_FRAME : frame;
		}

		private void closePlayback() {
			AudioPlayer currentPlayer = this.player;
			this.player = null;
			if (currentPlayer != null && !currentPlayer.isStopped()) {
				currentPlayer.stopPlaying();
			}
			if (this.channel != null) {
				this.channel.clearTargets();
			}
			this.channel = null;
			if (this.encoder != null && !this.encoder.isClosed()) {
				this.encoder.close();
			}
			this.encoder = null;
			this.audioChannelDimension = null;
		}

		private void close() {
			if (this.closed) {
				return;
			}
			this.closed = true;
			RendererBotCameraSystem.stopAudioCapture(this.droneCaptureOwnerKey);
			RendererBotCameraSystem.stopAudioCapture(this.bodyCaptureOwnerKey);
			closePlayback();
			this.feed.close();
		}
	}

	private static final class ControlledOperatorAudioFeed {
		private final Object lock = new Object();
		private final Map<UUID, ControlledOperatorAudioSourceBuffer> bodyVoiceBuffers = new HashMap<>();
		private boolean closed;

		private void offerBodyVoicePacket(UUID senderUuid, byte[] opusData, float gain, VoicechatApi voicechatApi) {
			if (senderUuid == null || opusData == null || opusData.length == 0 || gain <= 0.0F || voicechatApi == null) {
				return;
			}
			synchronized (this.lock) {
				if (this.closed) {
					return;
				}
				long baseSequence = System.nanoTime() / CONTROLLED_OPERATOR_AUDIO_FRAME_NANOS;
				ControlledOperatorAudioSourceBuffer buffer = this.bodyVoiceBuffers.computeIfAbsent(
						senderUuid,
						ignored -> new ControlledOperatorAudioSourceBuffer(voicechatApi.createDecoder())
				);
				buffer.offerPacket(opusData, baseSequence, gain);
				pruneExpiredBodyVoiceBuffersLocked(baseSequence);
			}
		}

		private short[] frameAt(long nowNanos) {
			synchronized (this.lock) {
				if (this.closed) {
					return null;
				}
				long targetSequence = nowNanos / CONTROLLED_OPERATOR_AUDIO_FRAME_NANOS;
				short[] body = mixBodyVoiceBuffersLocked(targetSequence);
				pruneExpiredBodyVoiceBuffersLocked(targetSequence);
				return body;
			}
		}

		private void close() {
			synchronized (this.lock) {
				this.closed = true;
				for (ControlledOperatorAudioSourceBuffer buffer : this.bodyVoiceBuffers.values()) {
					buffer.close();
				}
				this.bodyVoiceBuffers.clear();
			}
		}

		private void pruneExpiredBodyVoiceBuffersLocked(long targetSequence) {
			java.util.Iterator<Map.Entry<UUID, ControlledOperatorAudioSourceBuffer>> iterator = this.bodyVoiceBuffers.entrySet().iterator();
			while (iterator.hasNext()) {
				Map.Entry<UUID, ControlledOperatorAudioSourceBuffer> entry = iterator.next();
				ControlledOperatorAudioSourceBuffer buffer = entry.getValue();
				if (buffer == null || buffer.isExpired(targetSequence)) {
					if (buffer != null) {
						buffer.close();
					}
					iterator.remove();
				}
			}
		}

		private short[] mixBodyVoiceBuffersLocked(long targetSequence) {
			if (this.bodyVoiceBuffers.isEmpty()) {
				return null;
			}
			float[] mixed = null;
			for (ControlledOperatorAudioSourceBuffer buffer : this.bodyVoiceBuffers.values()) {
				if (buffer == null) {
					continue;
				}
				short[] frame = buffer.frameAt(targetSequence);
				if (frame == null) {
					continue;
				}
				if (mixed == null) {
					mixed = new float[CONTROLLED_OPERATOR_AUDIO_FRAME_SAMPLES];
				}
				for (int index = 0; index < frame.length; index++) {
					mixed[index] += frame[index];
				}
			}
			if (mixed == null) {
				return null;
			}
			short[] output = new short[CONTROLLED_OPERATOR_AUDIO_FRAME_SAMPLES];
			for (int index = 0; index < output.length; index++) {
				output[index] = SpeakerSystem.softLimitSample(mixed[index]);
			}
			return output;
		}
	}

	private static final class ControlledOperatorAudioSourceBuffer {
		private final OpusDecoder decoder;
		private final NavigableMap<Long, short[]> frames = new TreeMap<>();
		private long lastSequence = Long.MIN_VALUE;
		private boolean closed;

		private ControlledOperatorAudioSourceBuffer() {
			this(null);
		}

		private ControlledOperatorAudioSourceBuffer(OpusDecoder decoder) {
			this.decoder = decoder;
		}

		private void offerPacket(byte[] opusData, long baseSequence, float gain) {
			if (this.closed || this.decoder == null || this.decoder.isClosed() || opusData == null || opusData.length == 0) {
				return;
			}
			short[] decoded;
			try {
				decoded = this.decoder.decode(opusData);
			} catch (RuntimeException exception) {
				Lg2.LOGGER.debug("Failed to decode controlled drone body voice packet", exception);
				return;
			}
			offerSamples(decoded, baseSequence, gain);
		}

		private void offerFrame(short[] samples, long baseSequence) {
			offerSamples(samples, baseSequence, 1.0F);
		}

		private void offerSamples(short[] samples, long baseSequence, float gain) {
			if (samples == null || samples.length == 0) {
				return;
			}
			if (this.closed) {
				return;
			}
			long nextSequence = Math.max(baseSequence, this.lastSequence + 1L);
			int frameCount = Math.max(1, (samples.length + CONTROLLED_OPERATOR_AUDIO_FRAME_SAMPLES - 1) / CONTROLLED_OPERATOR_AUDIO_FRAME_SAMPLES);
			for (int frameIndex = 0; frameIndex < frameCount; frameIndex++) {
				short[] frame = new short[CONTROLLED_OPERATOR_AUDIO_FRAME_SAMPLES];
				int sourceOffset = frameIndex * CONTROLLED_OPERATOR_AUDIO_FRAME_SAMPLES;
				int copyLength = Math.min(
						CONTROLLED_OPERATOR_AUDIO_FRAME_SAMPLES,
						Math.max(0, samples.length - sourceOffset)
				);
				if (copyLength > 0) {
					copyAudioSamples(samples, sourceOffset, frame, copyLength, gain);
				}
				this.frames.put(nextSequence++, frame);
			}
			this.lastSequence = nextSequence - 1L;
			while (this.frames.size() > CONTROLLED_OPERATOR_AUDIO_FRAME_BUFFER_CAPACITY) {
				this.frames.pollFirstEntry();
			}
		}

		private short[] frameAt(long targetSequence) {
			Map.Entry<Long, short[]> entry = this.frames.floorEntry(targetSequence);
			if (entry == null) {
				return null;
			}
			return targetSequence - entry.getKey() <= CONTROLLED_OPERATOR_AUDIO_MAX_FRAME_AGE ? entry.getValue() : null;
		}

		private boolean isExpired(long targetSequence) {
			Map.Entry<Long, short[]> latestEntry = this.frames.lastEntry();
			return latestEntry == null
					|| targetSequence - latestEntry.getKey() > CONTROLLED_OPERATOR_AUDIO_SOURCE_EXPIRE_AFTER_FRAMES;
		}

		private void clear() {
			this.frames.clear();
			this.lastSequence = Long.MIN_VALUE;
		}

		private void close() {
			this.closed = true;
			clear();
			if (this.decoder != null && !this.decoder.isClosed()) {
				this.decoder.close();
			}
		}

		private static void copyAudioSamples(short[] source, int sourceOffset, short[] target, int copyLength, float gain) {
			if (gain == 1.0F) {
				System.arraycopy(source, sourceOffset, target, 0, copyLength);
				return;
			}
			for (int index = 0; index < copyLength; index++) {
				target[index] = SpeakerSystem.softLimitSample(source[sourceOffset + index] * gain);
			}
		}
	}

	private record DroneDisplayWobbleState(DroneDisplayWobbleType type, long startedAtTick) {
	}

	private record DroneDisplayWobble(DroneDisplayWobbleType type, float progress) {
	}

	private enum DroneDisplayWobbleType {
		POSITIVE(7),
		NEGATIVE(10);

		private final int lengthInTicks;

		DroneDisplayWobbleType(int lengthInTicks) {
			this.lengthInTicks = Math.max(1, lengthInTicks);
		}

		private int lengthInTicks() {
			return this.lengthInTicks;
		}
	}

	public record DroneLiveFeedState(
			UUID droneUuid,
			net.minecraft.resources.ResourceKey<Level> dimension,
			BlockPos pos,
			boolean online,
			double expectedX,
			double expectedY,
			double expectedZ,
			float yaw,
			float pitch,
			UUID followEntityUuid,
			Set<UUID> hiddenEntityUuids,
			boolean omnidirectionalChunkLoading,
			String controllerName
	) {
	}
}
