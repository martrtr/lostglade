package com.lostglade.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.lostglade.Lg2;
import com.lostglade.network.RendererBotPayloads;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Persistent server-owned allowlist for client mods that are safe for canonical rendering. */
public final class RendererModAllowlist {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("lg2-renderer-mod-allowlist.json");
    private static final int MAX_MOD_ID_LENGTH = 128;
    private static final int MAX_MANUAL_MOD_IDS = 2048;
    private static volatile Set<String> allowedModIds = Set.of();

    private RendererModAllowlist() {
    }

    public static void register() {
        loadFromDisk(true);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                Commands.literal("rendererallowlist")
                        .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .executes(context -> list(context.getSource()))
                        .then(Commands.literal("list").executes(context -> list(context.getSource())))
                        .then(Commands.literal("add")
                                .then(Commands.argument("mod_id", StringArgumentType.word())
                                        .executes(context -> add(context.getSource(), StringArgumentType.getString(context, "mod_id")))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("mod_id", StringArgumentType.word())
                                        .executes(context -> remove(context.getSource(), StringArgumentType.getString(context, "mod_id")))))
                        .then(Commands.literal("reload").executes(context -> reload(context.getSource())))
        ));
    }

    /** Installed server mod IDs plus manually allowed client-only mod IDs. Versions never participate. */
    public static List<String> manifestModIds() {
        TreeSet<String> ids = new TreeSet<>();
        FabricLoader.getInstance().getAllMods().stream()
                .map(container -> container.getMetadata().getId())
                .filter(Objects::nonNull)
                .map(RendererModAllowlist::normalize)
                .filter(Objects::nonNull)
                .forEach(ids::add);
        ids.addAll(allowedModIds);
        return List.copyOf(ids);
    }

    public static Set<String> allowedModIds() {
        return allowedModIds;
    }

    public static void sendManifest(ServerPlayer player) {
        if (player == null || !ServerPlayNetworking.canSend(player, RendererBotPayloads.RendererBotServerModIdsS2CPayload.TYPE)) return;
        ServerPlayNetworking.send(player, new RendererBotPayloads.RendererBotServerModIdsS2CPayload(manifestModIds()));
    }

    private static void broadcastManifest(MinecraftServer server) {
        List<String> manifest = manifestModIds();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (ServerPlayNetworking.canSend(player, RendererBotPayloads.RendererBotServerModIdsS2CPayload.TYPE)) {
                ServerPlayNetworking.send(player, new RendererBotPayloads.RendererBotServerModIdsS2CPayload(manifest));
            }
        }
    }

    private static int add(CommandSourceStack source, String rawId) {
        String id = normalize(rawId);
        if (id == null) {
            source.sendFailure(Component.literal("Invalid mod id. Use 1-128 characters: a-z, 0-9, _, -, ."));
            return 0;
        }
        TreeSet<String> next = new TreeSet<>(allowedModIds);
        if (!next.add(id)) {
            source.sendSuccess(() -> Component.literal("Renderer mod '" + id + "' is already manually allowed."), false);
            return 1;
        }
        if (next.size() > MAX_MANUAL_MOD_IDS) {
            source.sendFailure(Component.literal("Renderer mod allowlist is full (" + MAX_MANUAL_MOD_IDS + " entries)."));
            return 0;
        }
        if (!persist(next)) {
            source.sendFailure(Component.literal("Could not save renderer mod allowlist."));
            return 0;
        }
        allowedModIds = Set.copyOf(next);
        broadcastManifest(source.getServer());
        source.sendSuccess(() -> Component.literal("Allowed renderer client mod '" + id + "'."), true);
        return 1;
    }

    private static int remove(CommandSourceStack source, String rawId) {
        String id = normalize(rawId);
        if (id == null) {
            source.sendFailure(Component.literal("Invalid mod id."));
            return 0;
        }
        TreeSet<String> next = new TreeSet<>(allowedModIds);
        if (!next.remove(id)) {
            source.sendFailure(Component.literal("Renderer mod '" + id + "' is not in the manual allowlist."));
            return 0;
        }
        if (!persist(next)) {
            source.sendFailure(Component.literal("Could not save renderer mod allowlist."));
            return 0;
        }
        allowedModIds = Set.copyOf(next);
        broadcastManifest(source.getServer());
        source.sendSuccess(() -> Component.literal("Removed renderer client mod '" + id + "' from the allowlist."), true);
        return 1;
    }

    private static int reload(CommandSourceStack source) {
        if (!loadFromDisk(false)) {
            source.sendFailure(Component.literal("Could not reload renderer mod allowlist; keeping the previous list."));
            return 0;
        }
        broadcastManifest(source.getServer());
        source.sendSuccess(() -> Component.literal("Reloaded renderer mod allowlist: " + allowedModIds.size() + " manual entries."), true);
        return 1;
    }

    private static int list(CommandSourceStack source) {
        List<String> manual = new ArrayList<>(allowedModIds);
        manual.sort(String::compareTo);
        String values = manual.isEmpty() ? "<empty>" : String.join(", ", manual);
        source.sendSuccess(() -> Component.literal("Manual renderer mod allowlist (" + manual.size() + "): " + values), false);
        source.sendSuccess(() -> Component.literal("Effective renderer manifest: " + manifestModIds().size() + " mod IDs (server-installed + manual)."), false);
        return 1;
    }

    private static synchronized boolean loadFromDisk(boolean createIfMissing) {
        try {
            if (!Files.exists(PATH)) {
                if (createIfMissing && !persist(Set.of())) return false;
                allowedModIds = Set.of();
                return true;
            }
            String[] parsed;
            try (Reader reader = Files.newBufferedReader(PATH)) {
                parsed = GSON.fromJson(reader, String[].class);
            }
            TreeSet<String> sanitized = new TreeSet<>();
            if (parsed != null) {
                Arrays.stream(parsed).map(RendererModAllowlist::normalize).filter(Objects::nonNull).forEach(sanitized::add);
            }
            if (sanitized.size() > MAX_MANUAL_MOD_IDS) {
                Lg2.LOGGER.error("Renderer mod allowlist {} has {} entries; maximum is {}", PATH, sanitized.size(), MAX_MANUAL_MOD_IDS);
                return false;
            }
            allowedModIds = Set.copyOf(sanitized);
            return true;
        } catch (Exception exception) {
            Lg2.LOGGER.error("Failed to load renderer mod allowlist {}", PATH, exception);
            return false;
        }
    }

    private static synchronized boolean persist(Set<String> ids) {
        Path temp = PATH.resolveSibling(PATH.getFileName() + ".tmp");
        try {
            Files.createDirectories(PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(temp)) {
                GSON.toJson(new TreeSet<>(ids), writer);
            }
            try {
                Files.move(temp, PATH, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, PATH, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception exception) {
            Lg2.LOGGER.error("Failed to save renderer mod allowlist {}", PATH, exception);
            try { Files.deleteIfExists(temp); } catch (Exception ignored) { }
            return false;
        }
    }

    private static String normalize(String rawId) {
        if (rawId == null) return null;
        String id = rawId.trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty() || id.length() > MAX_MOD_ID_LENGTH) return null;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.')) return null;
        }
        return id;
    }
}
