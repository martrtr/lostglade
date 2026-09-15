package com.lostglade.server;

import com.lostglade.Lg2;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.monster.skeleton.WitherSkeleton;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class AncientUkrCollectorSystem {
    static final String COLLECTOR_TAG = "lg2.ancient_ukr_collector";
    private static final String OWNER_TAG_PREFIX = "lg2.ancient_ukr_collector_owner:";
    private static final double SPAWN_RADIUS = 10.0D;
    private static final double MIN_SPAWN_RADIUS = 2.5D;
    private static final int SPAWN_ATTEMPTS_PER_COLLECTOR = 80;
    private static final long WITHER_KILL_CREDIT_MILLIS = 15_000L;
    private static final Map<UUID, Set<UUID>> ACTIVE = new HashMap<>();
    private static final Map<UUID, Long> RECENT_COLLECTOR_HITS = new HashMap<>();

    private AncientUkrCollectorSystem() {
    }

    static int spawnWave(ServerPlayer owner, int requestedCount) {
        if (owner == null || requestedCount <= 0 || !(owner.level() instanceof ServerLevel level)) return 0;
        clearOwner(level.getServer(), owner.getUUID(), true);
        int spawned = 0;
        for (int index = 0; index < requestedCount; index++) {
            CollectorEntity collector = createAtReachablePosition(level, owner);
            if (collector == null || !level.addFreshEntity(collector)) continue;
            ACTIVE.computeIfAbsent(owner.getUUID(), ignored -> new HashSet<>()).add(collector.getUUID());
            spawned++;
        }
        Lg2.LOGGER.info("Spawned {}/{} Ancient Ukr collectors for {}",
                spawned, requestedCount, owner.getGameProfile().name());
        return spawned;
    }

    static void tick(MinecraftServer server) {
        if (server == null || ACTIVE.isEmpty()) return;
        Iterator<Map.Entry<UUID, Set<UUID>>> owners = ACTIVE.entrySet().iterator();
        while (owners.hasNext()) {
            Map.Entry<UUID, Set<UUID>> entry = owners.next();
            ServerPlayer owner = server.getPlayerList().getPlayer(entry.getKey());
            entry.getValue().removeIf(id -> {
                CollectorEntity collector = findCollector(server, id);
                if (collector == null || !collector.isAlive()) return true;
                collector.keepTarget(owner);
                return false;
            });
            if (entry.getValue().isEmpty()) owners.remove();
        }
        long cutoff = System.currentTimeMillis() - WITHER_KILL_CREDIT_MILLIS;
        RECENT_COLLECTOR_HITS.entrySet().removeIf(entry -> entry.getValue() < cutoff);
    }

    public static void onSuccessfulDamage(LivingEntity victim, DamageSource source, boolean applied) {
        if (!applied || !(victim instanceof ServerPlayer player)) return;
        CollectorEntity collector = collectorFrom(source);
        if (collector != null && collector.isAssignedTo(player)) {
            RECENT_COLLECTOR_HITS.put(player.getUUID(), System.currentTimeMillis());
        }
    }

    public static void onLivingDeath(ServerLevel level, LivingEntity victim, DamageSource source) {
        if (!(victim instanceof ServerPlayer owner)) return;
        CollectorEntity direct = collectorFrom(source);
        boolean directCollectorKill = direct != null && direct.isAssignedTo(owner);
        Long lastCollectorHit = RECENT_COLLECTOR_HITS.remove(owner.getUUID());
        boolean collectorWitherKill = source != null && source.is(DamageTypes.WITHER)
                && lastCollectorHit != null
                && System.currentTimeMillis() - lastCollectorHit <= WITHER_KILL_CREDIT_MILLIS;
        if (!directCollectorKill && !collectorWitherKill) return;

        int confiscated = AncientUkrCreditSystem.confiscateAllBitcoinsAndRepay(level.getServer(), owner);
        clearOwner(level.getServer(), owner.getUUID(), true);
        Lg2.LOGGER.info("Ancient Ukr {} died to collectors; confiscated {} bitcoins",
                owner.getGameProfile().name(), confiscated);
    }

    static void clearAll(MinecraftServer server, boolean particles) {
        if (server == null) return;
        for (UUID ownerId : new ArrayList<>(ACTIVE.keySet())) clearOwner(server, ownerId, particles);
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity.getTags().contains(COLLECTOR_TAG)) discard(entity, particles);
            }
        }
        ACTIVE.clear();
        RECENT_COLLECTOR_HITS.clear();
    }

    private static void clearOwner(MinecraftServer server, UUID ownerId, boolean particles) {
        Set<UUID> ids = ACTIVE.remove(ownerId);
        if (ids != null) {
            for (UUID id : ids) {
                Entity entity = findEntity(server, id);
                if (entity != null) discard(entity, particles);
            }
        }
        String ownerTag = OWNER_TAG_PREFIX + ownerId;
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity.getTags().contains(ownerTag)) discard(entity, particles);
            }
        }
    }

    private static void discard(Entity entity, boolean particles) {
        if (!entity.isAlive()) return;
        if (particles && entity.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.SMOKE, entity.getX(), entity.getY() + 1.0D, entity.getZ(),
                    18, 0.35D, 0.75D, 0.35D, 0.025D);
        }
        entity.discard();
    }

    private static CollectorEntity createAtReachablePosition(ServerLevel level, ServerPlayer owner) {
        for (int attempt = 0; attempt < SPAWN_ATTEMPTS_PER_COLLECTOR; attempt++) {
            double angle = level.getRandom().nextDouble() * Math.PI * 2.0D;
            double distance = Math.sqrt(level.getRandom().nextDouble()
                    * (SPAWN_RADIUS * SPAWN_RADIUS - MIN_SPAWN_RADIUS * MIN_SPAWN_RADIUS)
                    + MIN_SPAWN_RADIUS * MIN_SPAWN_RADIUS);
            double x = owner.getX() + Math.cos(angle) * distance;
            double z = owner.getZ() + Math.sin(angle) * distance;
            BlockPos feet = findSpawnFloor(level, owner.blockPosition(), x, z);
            if (feet == null) continue;

            CollectorEntity collector = new CollectorEntity(level, owner.getUUID());
            collector.setPos(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D);
            collector.setYRot((float) Math.toDegrees(Math.atan2(owner.getZ() - collector.getZ(),
                    owner.getX() - collector.getX())) - 90.0F);
            if (!level.noCollision(collector, collector.getBoundingBox())) continue;
            Vec3 from = collector.getEyePosition();
            Vec3 to = owner.getEyePosition();
            HitResult obstruction = level.clip(new ClipContext(from, to,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, collector));
            if (obstruction.getType() != HitResult.Type.MISS) continue;
            collector.keepTarget(owner);
            return collector;
        }
        return null;
    }

    private static BlockPos findSpawnFloor(ServerLevel level, BlockPos ownerPos, double x, double z) {
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        int[] offsets = {0, 1, -1, 2, -2, 3, -3, 4, -4, 5, -5};
        for (int offset : offsets) {
            BlockPos feet = new BlockPos(blockX, ownerPos.getY() + offset, blockZ);
            if (!level.hasChunkAt(feet) || !level.getWorldBorder().isWithinBounds(feet)) continue;
            BlockPos floor = feet.below();
            if (!level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) continue;
            if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()) continue;
            BlockPos head = feet.above();
            if (!level.getBlockState(head).getCollisionShape(level, head).isEmpty()) continue;
            return feet;
        }
        return null;
    }

    private static CollectorEntity collectorFrom(DamageSource source) {
        if (source == null) return null;
        if (source.getEntity() instanceof CollectorEntity collector) return collector;
        return source.getDirectEntity() instanceof CollectorEntity collector ? collector : null;
    }

    private static CollectorEntity findCollector(MinecraftServer server, UUID id) {
        Entity entity = findEntity(server, id);
        return entity instanceof CollectorEntity collector ? collector : null;
    }

    private static Entity findEntity(MinecraftServer server, UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(id);
            if (entity != null) return entity;
        }
        return null;
    }

    private static final class CollectorEntity extends WitherSkeleton {
        private final UUID ownerId;

        private CollectorEntity(ServerLevel level, UUID ownerId) {
            super(EntityType.WITHER_SKELETON, level);
            this.ownerId = ownerId;
            this.xpReward = 0;
            addTag(COLLECTOR_TAG);
            addTag(OWNER_TAG_PREFIX + ownerId);
            setCustomName(Component.literal("\u041a\u043e\u043b\u043b\u0435\u043a\u0442\u043e\u0440"));
            setCustomNameVisible(true);
            setPersistenceRequired();
            setCanPickUpLoot(false);
            equip(EquipmentSlot.MAINHAND, Items.IRON_SWORD);
            equip(EquipmentSlot.CHEST, Items.IRON_CHESTPLATE);
            equip(EquipmentSlot.LEGS, Items.IRON_LEGGINGS);
            equip(EquipmentSlot.FEET, Items.IRON_BOOTS);
            var followRange = getAttribute(Attributes.FOLLOW_RANGE);
            if (followRange != null) followRange.setBaseValue(2048.0D);
        }

        private void equip(EquipmentSlot slot, net.minecraft.world.item.Item item) {
            setItemSlot(slot, new ItemStack(item));
            setDropChance(slot, 0.0F);
        }

        @Override
        protected void registerGoals() {
            goalSelector.addGoal(0, new FloatGoal(this));
            goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.15D, false));
            goalSelector.addGoal(9, new RandomLookAroundGoal(this));
        }

        @Override
        public boolean canAttack(LivingEntity target) {
            return target != null && ownerId != null && ownerId.equals(target.getUUID()) && super.canAttack(target);
        }

        @Override
        public void setTarget(LivingEntity target) {
            if (target == null || ownerId != null && ownerId.equals(target.getUUID())) super.setTarget(target);
        }

        @Override
        public void checkDespawn() {
        }

        private boolean isAssignedTo(ServerPlayer player) {
            return player != null && ownerId.equals(player.getUUID());
        }

        private void keepTarget(ServerPlayer owner) {
            if (owner == null || !owner.isAlive() || owner.level() != level()) {
                setTarget(null);
                return;
            }
            setTarget(owner);
        }
    }
}
