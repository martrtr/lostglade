package com.lostglade.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.lostglade.Lg2;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CollectionTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.ItemSupplier;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/** Tracks only marked items and the storage hosts that have actually held them. */
public final class GennadiyReportTracker {
    static final String MARKER = "lg2_gennadiy_report";
    private static final String EPOCH = "tracking_epoch";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final net.minecraft.server.level.TicketType LOOKUP_TICKET = new net.minecraft.server.level.TicketType(
            20L, net.minecraft.server.level.TicketType.FLAG_LOADING | (1 << 27));
    private static final Map<String, Source> SOURCES = new LinkedHashMap<>();
    private static final Map<Container, Host> CONTAINERS = new WeakHashMap<>();
    private static final Set<String> DIRTY = new LinkedHashSet<>();
    private static final Map<ItemStack, CachedItems> CACHE = new WeakHashMap<>();
    private static final Map<Class<?>, List<Field>> INVENTORY_FIELDS = new HashMap<>();
    private static State state = new State();
    private static MinecraftServer currentServer;
    private static boolean processing;
    private static boolean changed;

    static final class State {
        int version = 2;
        UUID epoch = UUID.randomUUID();
        boolean acceptLegacy = true;
        Map<UUID, TrackedReport> reports = new LinkedHashMap<>();
        Set<UUID> revoked = new HashSet<>();
    }

    static final class TrackedReport {
        Map<String, Host> locations = new LinkedHashMap<>();
        transient int missingSince = -1;
    }

    record Host(String kind, String dimension, UUID owner, long position, int chunkX, int chunkZ) {
        String key() { return kind + ":" + dimension + ":" + (owner == null ? position : owner); }
        static Host player(ServerPlayer player) { return new Host("player", "", player.getUUID(), 0, 0, 0); }
        static Host block(BlockEntity block) {
            BlockPos pos = block.getBlockPos();
            return new Host("block", block.getLevel().dimension().identifier().toString(), null,
                    pos.asLong(), pos.getX() >> 4, pos.getZ() >> 4);
        }
        static Host entity(Entity entity) {
            return new Host("entity", entity.level().dimension().identifier().toString(), entity.getUUID(),
                    entity.blockPosition().asLong(), entity.chunkPosition().x, entity.chunkPosition().z);
        }
    }

    private record Source(Host host, WeakReference<Object> object, Set<UUID> ids) {
        Source(Host host, WeakReference<Object> object) { this(host, object, null); }
    }
    private record CachedItems(CustomData data, ItemContainerContents container, BundleContents bundle, Set<UUID> ids) {}

    private GennadiyReportTracker() {}

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTING.register(GennadiyReportTracker::load);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            save(server);
            currentServer = null;
            SOURCES.clear(); CONTAINERS.clear(); DIRTY.clear(); CACHE.clear();
        });
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
            // This event runs before the chunk promotion future completes.
            // Reading/dirtying a container here can recursively wait for that same future.
            for (BlockEntity block : chunk.getBlockEntities().values()) blockChanged(block);
        });
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
            for (BlockEntity block : chunk.getBlockEntities().values()) {
                observeBlock(block);
                SOURCES.remove(Host.block(block).key());
            }
            save(level.getServer());
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> observeEntity(entity));
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            if (entity.getRemovalReason() != null && entity.getRemovalReason().shouldDestroy()) {
                noteEntityRemoved(entity);
            } else {
                observeEntity(entity);
                SOURCES.remove(Host.entity(entity).key());
            }
            save(level.getServer());
        });
    }

    private static Path statePath(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("data/lg2_gennadiy_report.json");
    }

    private static void load(MinecraftServer server) {
        currentServer = server;
        state = new State();
        SOURCES.clear(); CONTAINERS.clear(); DIRTY.clear(); CACHE.clear();
        Path path = statePath(server);
        if (Files.exists(path)) {
            try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                var json = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
                if (json.has("version")) {
                    State loaded = GSON.fromJson(json, State.class);
                    if (loaded.epoch == null || loaded.reports == null || loaded.revoked == null) {
                        throw new IllegalStateException("Incomplete REPORT tracking state");
                    }
                    state = loaded;
                }
                // The obsolete boolean registry has no identity/location to track.
                // Existing items are adopted as their host loads; no world scan on startup.
            } catch (Exception error) {
                throw new IllegalStateException("Cannot load REPORT tracking state " + path, error);
            }
        }
        changed = true;
        if (!save(server)) throw new IllegalStateException("Cannot initialize persistent REPORT tracking");
    }

    public static boolean save(MinecraftServer server) {
        if (server == null || server != currentServer) return false;
        if (!changed) return true;
        Path path = statePath(server);
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(temporary, GSON.toJson(state), StandardCharsets.UTF_8);
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            changed = false;
            return true;
        } catch (Exception error) {
            Lg2.LOGGER.error("Failed to save REPORT tracking state", error);
            return false;
        }
    }

    static UUID rawId(CompoundTag root) {
        String value = root.getStringOr("id", "");
        if (value.isEmpty()) return null;
        try { return UUID.fromString(value); } catch (IllegalArgumentException ignored) { return null; }
    }

    public static UUID rawId(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? null : rawId(data.copyTag().getCompoundOrEmpty(MARKER));
    }

    static boolean revoked(State tracking, CompoundTag root) {
        UUID id = rawId(root);
        if (id == null) return false;
        String epoch = root.getStringOr(EPOCH, "");
        return tracking.revoked.contains(id) || (epoch.isEmpty() ? !tracking.acceptLegacy : !tracking.epoch.toString().equals(epoch));
    }

    public static boolean isRevoked(ItemStack stack) {
        if (currentServer == null) return false;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && revoked(state, data.copyTag().getCompoundOrEmpty(MARKER));
    }

    public static void stamp(ItemStack stack) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            CompoundTag report = tag.getCompoundOrEmpty(MARKER);
            report.putString(EPOCH, state.epoch.toString());
            tag.put(MARKER, report);
        });
        CACHE.remove(stack);
    }

    public static void created(ServerPlayer player, ItemStack stack) {
        stamp(stack);
        observePlayer(player);
        save(player.level().getServer());
    }

    private static Set<UUID> itemIds(ItemStack stack) {
        if (stack.isEmpty()) return Set.of();
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        ItemContainerContents container = stack.get(DataComponents.CONTAINER);
        BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        CachedItems cached = CACHE.get(stack);
        if (cached != null && cached.data == data && cached.container == container && cached.bundle == bundle) return cached.ids;
        Set<UUID> ids = new LinkedHashSet<>();
        UUID own = rawId(stack);
        if (own != null && !isRevoked(stack)) ids.add(own);
        if (container != null) for (ItemStack child : container.nonEmptyItems()) ids.addAll(itemIds(child));
        if (bundle != null) for (ItemStack child : bundle.items()) ids.addAll(itemIds(child));
        Set<UUID> result = Set.copyOf(ids);
        CACHE.put(stack, new CachedItems(data, container, bundle, result));
        return result;
    }

    static boolean purgeStack(ItemStack stack, State tracking) {
        if (stack.isEmpty()) return false;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data != null && revoked(tracking, data.copyTag().getCompoundOrEmpty(MARKER))) {
            stack.setCount(0);
            return true;
        }
        boolean changedContainer = false;
        ItemContainerContents container = stack.get(DataComponents.CONTAINER);
        if (container != null) {
            List<ItemStack> children = container.stream().map(ItemStack::copy).toList();
            for (ItemStack child : children) changedContainer |= purgeStack(child, tracking);
            if (changedContainer) stack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(children));
        }
        boolean changedBundle = false;
        BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null) {
            List<ItemStack> children = new ArrayList<>();
            for (ItemStack child : bundle.itemsCopy()) {
                changedBundle |= purgeStack(child, tracking);
                if (!child.isEmpty()) children.add(child);
            }
            if (changedBundle) stack.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(children));
        }
        return changedContainer || changedBundle;
    }

    private static void inspect(ItemStack stack, Set<UUID> ids) {
        if (stack.isEmpty()) return;
        CachedItems cached = CACHE.get(stack);
        if (cached != null && cached.data == stack.get(DataComponents.CUSTOM_DATA)
                && cached.container == stack.get(DataComponents.CONTAINER) && cached.bundle == stack.get(DataComponents.BUNDLE_CONTENTS)) {
            ids.addAll(cached.ids);
            return;
        }
        if (purgeStack(stack, state)) CACHE.remove(stack);
        ids.addAll(itemIds(stack));
    }

    private static void inspect(Container container, Host host, Set<UUID> ids) {
        CONTAINERS.put(container, host);
        boolean modified = false;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            CachedItems cached = CACHE.get(stack);
            if (!stack.isEmpty() && cached != null && cached.data == stack.get(DataComponents.CUSTOM_DATA)
                    && cached.container == stack.get(DataComponents.CONTAINER) && cached.bundle == stack.get(DataComponents.BUNDLE_CONTENTS)) {
                ids.addAll(cached.ids);
                continue;
            }
            if (purgeStack(stack, state)) {
                CACHE.remove(stack);
                container.setItem(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
                modified = true;
            }
            ids.addAll(itemIds(stack));
        }
        if (modified) container.setChanged();
    }

    private static void remember(Host host, Object object, Set<UUID> ids) {
        SOURCES.put(host.key(), new Source(host, new WeakReference<>(object), Set.copyOf(ids)));
        for (var entry : state.reports.entrySet()) {
            if (!ids.contains(entry.getKey()) && entry.getValue().locations.size() > 1
                    && entry.getValue().locations.remove(host.key()) != null) changed = true;
        }
        for (UUID id : ids) {
            TrackedReport report = state.reports.computeIfAbsent(id, key -> new TrackedReport());
            if (!host.equals(report.locations.put(host.key(), host))) changed = true;
        }
    }

    public static void observePlayer(ServerPlayer player) {
        if (currentServer == null || player.level().getServer() != currentServer || !currentServer.isSameThread()) return;
        Host host = Host.player(player);
        Set<UUID> ids = new LinkedHashSet<>();
        inspect(player.getInventory(), host, ids);
        inspect(player.getEnderChestInventory(), host, ids);
        if (player.containerMenu != null) {
            inspect(player.containerMenu.getCarried(), ids);
            for (Slot slot : player.containerMenu.slots) {
                Host owner = CONTAINERS.get(slot.container);
                if (owner == null || owner.equals(host)) {
                    // Crafting/trading inputs belong to the player until the menu closes.
                    inspect(slot.getItem(), ids);
                }
            }
        }
        remember(host, player, ids);
    }

    public static void observeBlock(BlockEntity block) {
        if (!(block.getLevel() instanceof ServerLevel level) || currentServer != level.getServer() || !currentServer.isSameThread()) return;
        Host host = Host.block(block);
        Set<UUID> ids = new LinkedHashSet<>();
        boolean oldProcessing = processing;
        processing = true;
        try {
            if (block instanceof net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity randomizable
                    && randomizable.getLootTable() != null) {
                remember(host, block, ids);
                return;
            }
            if (block instanceof Container container) {
                inspect(container, host, ids);
            } else {
                // Also covers storage blocks exposing items only through their NBT/components.
                CompoundTag tag = block.saveWithoutMetadata(level.registryAccess());
                collectTagIds(tag, ids);
                if (purgeTag(tag, state)) {
                    block.loadWithComponents(net.minecraft.world.level.storage.TagValueInput.create(
                            net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess(), tag));
                    block.setChanged();
                }
            }
            remember(host, block, ids);
        } finally { processing = oldProcessing; }
    }

    public static void blockChanged(BlockEntity block) {
        if (processing || !(block.getLevel() instanceof ServerLevel level) || currentServer != level.getServer() || !currentServer.isSameThread()) return;
        Host host = Host.block(block);
        SOURCES.put(host.key(), new Source(host, new WeakReference<>(block)));
        DIRTY.add(host.key());
    }

    public static void blockChanged(Level world, BlockPos pos) {
        if (!(world instanceof ServerLevel level) || currentServer != level.getServer() || processing
                || !level.getServer().isSameThread()) return;
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) return;
        BlockEntity block = chunk.getBlockEntity(pos);
        if (block != null) blockChanged(block);
    }

    public static void containerChanged(Container container) {
        if (processing || currentServer == null || !currentServer.isSameThread()) return;
        Host host = CONTAINERS.get(container);
        if (host != null) DIRTY.add(host.key());
    }

    public static void observeEntity(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level) || currentServer != level.getServer() || !currentServer.isSameThread()) return;
        if (entity instanceof ServerPlayer player) { observePlayer(player); return; }
        Host host = Host.entity(entity);
        Set<UUID> ids = new LinkedHashSet<>();
        boolean oldProcessing = processing;
        processing = true;
        try {
            if (entity instanceof Container container) inspect(container, host, ids);
            for (Field field : inventoryFields(entity.getClass())) {
                try {
                    if (field.get(entity) instanceof Container container) inspect(container, host, ids);
                } catch (IllegalAccessException error) { throw new IllegalStateException(error); }
            }
            if (entity instanceof ItemEntity item) inspect(item.getItem(), ids);
            if (entity instanceof LivingEntity living) {
                for (EquipmentSlot slot : EquipmentSlot.values()) inspect(living.getItemBySlot(slot), ids);
            }
            if (entity instanceof ItemFrame frame) inspect(frame.getItem(), ids);
            if (entity instanceof Display.ItemDisplay display) inspect(display.getItemStack(), ids);
            if (entity instanceof ItemSupplier supplier) inspect(supplier.getItem(), ids);
            // Covers items in entity data/memories and modded inventories as well.
            var output = net.minecraft.world.level.storage.TagValueOutput.createWithContext(
                    net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess());
            entity.saveWithoutId(output);
            CompoundTag saved = output.buildResult();
            if (purgeTag(saved, state)) {
                entity.load(net.minecraft.world.level.storage.TagValueInput.create(
                        net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess(), saved));
            }
            collectTagIds(saved, ids);
            if (entity instanceof ItemEntity item && item.getItem().isEmpty() && !item.isRemoved()) item.discard();
            remember(host, entity, ids);
        } finally { processing = oldProcessing; }
    }

    private static List<Field> inventoryFields(Class<?> type) {
        return INVENTORY_FIELDS.computeIfAbsent(type, ignored -> {
            List<Field> result = new ArrayList<>();
            for (Class<?> parent = type; parent != null; parent = parent.getSuperclass()) {
                for (Field field : parent.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers()) && Container.class.isAssignableFrom(field.getType())
                            && field.trySetAccessible()) result.add(field);
                }
            }
            return result;
        });
    }

    public static void itemEntityChanged(ItemEntity entity) {
        entityChanged(entity);
    }

    public static void entityChanged(Entity entity) {
        if (processing || !(entity.level() instanceof ServerLevel level) || currentServer != level.getServer() || !currentServer.isSameThread()) return;
        Host host = Host.entity(entity);
        SOURCES.put(host.key(), new Source(host, new WeakReference<>(entity)));
        DIRTY.add(host.key());
    }

    public static void noteEntityRemoved(Entity entity) {
        if (currentServer == null || !(entity.level() instanceof ServerLevel level) || level.getServer() != currentServer) return;
        Host host = Host.entity(entity);
        if (state.reports.values().stream().noneMatch(report -> report.locations.containsKey(host.key()))) {
            SOURCES.remove(host.key());
            DIRTY.remove(host.key());
            return;
        }
        SOURCES.put(host.key(), new Source(host, new WeakReference<>(entity), Set.of()));
        DIRTY.add(host.key());
    }

    public static void destroyed(MinecraftServer server, ItemStack stack) {
        // Discard also happens on pickup/merging. Confirm absence at its hosts before retiring an ID.
        if (server != currentServer) return;
        UUID id = rawId(stack);
        TrackedReport report = state.reports.get(id);
        if (report != null) DIRTY.addAll(report.locations.keySet());
    }

    public static void tick(MinecraftServer server) {
        if (server != currentServer) return;
        flush();
        for (var entry : new ArrayList<>(state.reports.entrySet())) {
            TrackedReport report = entry.getValue();
            boolean absent = !report.locations.isEmpty();
            for (Host host : report.locations.values()) {
                Source source = SOURCES.get(host.key());
                Object object = source == null ? null : source.object.get();
                if (source == null || source.ids == null || source.ids.contains(entry.getKey())
                        || object instanceof ServerPlayer player && server.getPlayerList().getPlayer(player.getUUID()) == null) {
                    absent = false;
                    break;
                }
            }
            if (!absent) report.missingSince = -1;
            else if (report.missingSince < 0) report.missingSince = server.getTickCount();
            else if (server.getTickCount() - report.missingSince >= 2) retire(entry.getKey());
        }
        if (server.getTickCount() % 20 != 0) return;
        Set<String> hosts = new HashSet<>();
        for (TrackedReport report : state.reports.values()) hosts.addAll(report.locations.keySet());
        for (String key : hosts) {
            Source source = SOURCES.get(key);
            if (source != null) observeSource(source);
        }
        save(server);
    }

    private static void flush() {
        List<String> keys = new ArrayList<>(DIRTY);
        DIRTY.clear();
        for (String key : keys) {
            Source source = SOURCES.get(key);
            if (source != null) observeSource(source);
        }
    }

    private static void observeSource(Source source) {
        Object object = source.object.get();
        if (object instanceof BlockEntity block && !block.isRemoved()) observeBlock(block);
        if (object instanceof Entity entity && !entity.isRemoved()) observeEntity(entity);
    }

    /** Called immediately before proposing AND confirming a new REPORT. */
    public static boolean hasActive(MinecraftServer server) {
        if (server != currentServer) return true;
        // A command can mutate a nested stack without replacing the parent component.
        CACHE.clear();
        flush();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) observePlayer(player);
        boolean present = false;
        for (var entry : new ArrayList<>(state.reports.entrySet())) {
            UUID id = entry.getKey();
            boolean exists = false;
            for (Host host : new ArrayList<>(entry.getValue().locations.values())) {
                if (atHost(server, host, id)) { exists = true; break; }
            }
            if (exists) present = true;
            else retire(id);
        }
        save(server);
        return present;
    }

    private static void retire(UUID id) {
        state.reports.remove(id);
        state.revoked.add(id);
        CACHE.clear();
        changed = true;
    }

    private static boolean atHost(MinecraftServer server, Host host, UUID id) {
        try {
            if (host.kind.equals("player")) {
                ServerPlayer player = server.getPlayerList().getPlayer(host.owner);
                if (player != null) return playerIds(player).contains(id);
                Path file = server.getWorldPath(LevelResource.ROOT).resolve("playerdata/" + host.owner + ".dat");
                return Files.exists(file) && tagIds(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())).contains(id);
            }
            ServerLevel level = server.getLevel(ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                    Identifier.parse(host.dimension)));
            if (level == null) return true;
            if (host.kind.equals("block")) {
                // Load only the last known chunk; never search other world regions.
                level.getChunk(host.chunkX, host.chunkZ);
                BlockEntity block = level.getBlockEntity(BlockPos.of(host.position));
                if (block == null) return false;
                observeBlock(block);
                return blockIds(block).contains(id);
            }
            Entity entity = level.getEntity(host.owner);
            if (entity != null && !entity.isRemoved()) {
                observeEntity(entity);
                return entityIds(entity).contains(id);
            }
            Source source = SOURCES.get(host.key());
            if (source != null && source.object.get() instanceof Entity removed && removed.isRemoved()
                    && removed.getRemovalReason().shouldDestroy()) return false;
            // Use Minecraft's own pending-I/O-aware loader, rather than opening a
            // second RegionFile over a chunk the server may still be writing.
            ChunkPos position = new ChunkPos(host.chunkX, host.chunkZ);
            level.getChunkSource().addTicketWithRadius(LOOKUP_TICKET, position, 1);
            try {
                level.getChunk(host.chunkX, host.chunkZ);
                level.waitForEntities(position, 0);
                entity = level.getEntity(host.owner);
                if (entity == null) return false;
                observeEntity(entity);
                return entityIds(entity).contains(id);
            } finally {
                level.getChunkSource().removeTicketWithRadius(LOOKUP_TICKET, position, 1);
            }
        } catch (Exception error) {
            Lg2.LOGGER.warn("Could not verify REPORT {} at {}; retaining its reservation", id, host, error);
            return true;
        }
    }

    private static Set<UUID> playerIds(ServerPlayer player) {
        Set<UUID> ids = new HashSet<>();
        Host host = Host.player(player);
        inspect(player.getInventory(), host, ids);
        inspect(player.getEnderChestInventory(), host, ids);
        if (player.containerMenu != null) {
            inspect(player.containerMenu.getCarried(), ids);
            for (Slot slot : player.containerMenu.slots) if (!CONTAINERS.containsKey(slot.container)
                    || CONTAINERS.get(slot.container).equals(host)) inspect(slot.getItem(), ids);
        }
        return ids;
    }

    private static Set<UUID> blockIds(BlockEntity block) {
        if (block instanceof net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity randomizable
                && randomizable.getLootTable() != null) return Set.of();
        if (block instanceof Container container) {
            Set<UUID> ids = new HashSet<>(); inspect(container, Host.block(block), ids); return ids;
        }
        return tagIds(block.saveWithoutMetadata(block.getLevel().registryAccess()));
    }

    private static Set<UUID> entityIds(Entity entity) {
        var output = net.minecraft.world.level.storage.TagValueOutput.createWithContext(
                net.minecraft.util.ProblemReporter.DISCARDING, entity.registryAccess());
        entity.saveWithoutId(output);
        return tagIds(output.buildResult());
    }

    static Set<UUID> tagIds(Tag tag) { Set<UUID> ids = new HashSet<>(); collectTagIds(tag, ids); return ids; }

    private static void collectTagIds(Tag tag, Set<UUID> ids) {
        if (tag instanceof CompoundTag compound) {
            CompoundTag marker = compound.getCompoundOrEmpty(MARKER);
            UUID id = rawId(marker);
            if (id != null && !revoked(state, marker)) ids.add(id);
            for (Tag child : compound.values()) collectTagIds(child, ids);
        } else if (tag instanceof CollectionTag collection) {
            for (Tag child : collection) collectTagIds(child, ids);
        }
    }

    private static boolean revokedItemTag(Tag tag, State tracking) {
        if (!(tag instanceof CompoundTag compound)) return false;
        CompoundTag components = compound.getCompoundOrEmpty("components");
        return compound.contains("id") && revoked(tracking,
                components.getCompoundOrEmpty("minecraft:custom_data").getCompoundOrEmpty(MARKER));
    }

    /** Remove item entries rather than leaving invalid stacks inside strict bundle codecs. */
    static boolean purgeTag(Tag tag, State tracking) {
        if (tag instanceof CompoundTag compound) {
            boolean modified = false;
            for (String key : new ArrayList<>(compound.keySet())) {
                Tag child = compound.get(key);
                if (revokedItemTag(child, tracking)) { compound.remove(key); modified = true; }
                else modified |= purgeTag(child, tracking);
            }
            return modified;
        }
        if (tag instanceof net.minecraft.nbt.ListTag collection) {
            boolean modified = false;
            for (int index = collection.size() - 1; index >= 0; index--) {
                Tag child = collection.get(index);
                if (revokedItemTag(child, tracking) || child instanceof CompoundTag wrapper
                        && revokedItemTag(wrapper.get("item"), tracking)) {
                    collection.remove(index);
                    modified = true;
                } else modified |= purgeTag(child, tracking);
            }
            return modified;
        }
        return false;
    }

    public static int clearCommand(CommandContext<CommandSourceStack> context) {
        MinecraftServer server = context.getSource().getServer();
        State previous = state;
        state = new State();
        state.acceptLegacy = false;
        CACHE.clear();
        changed = true;
        // Persist the revocation BEFORE touching hosts. Unloaded/offline copies are
        // invalid immediately and are physically removed before their next use.
        if (!save(server)) {
            state = previous;
            changed = true;
            context.getSource().sendFailure(Component.literal("Не удалось сохранить аннигиляцию REPORT. Предметы не изменены."));
            return 0;
        }
        for (Source source : new ArrayList<>(SOURCES.values())) observeSource(source);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            observePlayer(player);
            player.containerMenu.broadcastChanges();
        }
        save(server);
        context.getSource().sendSuccess(() -> Component.literal("\u0412\u0441\u0435 REPORT \u0430\u043d\u043d\u0438\u0433\u0438\u043b\u0438\u0440\u043e\u0432\u0430\u043d\u044b."), true);
        return 1;
    }
}
