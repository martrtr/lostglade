package com.lostglade.client;

import com.lostglade.Lg2;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.lang.reflect.Method;

/**
 * Recovers Simple Voice Chat when another client mod breaks Fabric's JOIN event
 * chain before SVC can create its ClientVoicechat and send RequestSecretPacket.
 *
 * SVC 2.6.17+ already registers an early JOIN phase for the same class of
 * compatibility problem. This guard is intentionally delayed and only runs
 * when SVC is loaded but its client object is still absent after joining.
 */
public final class VoiceChatCompatibilityGuard {
    private static final Identifier EARLY_JOIN = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "voicechat_guard_early_join");
    private static final int FIRST_CHECK_TICKS = 30;
    private static final int VERIFY_TICKS = 40;

    private static int ticksUntilCheck = -1;
    private static boolean recoveryAttempted;

    private VoiceChatCompatibilityGuard() {
    }

    public static void register() {
        if (!FabricLoader.getInstance().isModLoaded("voicechat")) {
            Lg2.LOGGER.warn("Simple Voice Chat is not loaded; voice chat recovery guard is disabled");
            return;
        }

        // Schedule our delayed check before ordinary JOIN listeners. This means
        // even a later listener throwing an exception cannot prevent the guard
        // from noticing that SVC missed its own JOIN callback.
        ClientPlayConnectionEvents.JOIN.addPhaseOrdering(EARLY_JOIN, Event.DEFAULT_PHASE);
        ClientPlayConnectionEvents.JOIN.register(EARLY_JOIN, (handler, sender, client) -> {
            ticksUntilCheck = FIRST_CHECK_TICKS;
            recoveryAttempted = false;
            Lg2.LOGGER.info("Voice chat compatibility guard armed (SVC {})", voiceChatVersion());
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
        ClientTickEvents.END_CLIENT_TICK.register(VoiceChatCompatibilityGuard::tick);
    }

    private static void tick(Minecraft client) {
        if (ticksUntilCheck < 0) {
            return;
        }
        if (client.getConnection() == null) {
            reset();
            return;
        }
        if (ticksUntilCheck-- > 0) {
            return;
        }

        Boolean ready = hasVoiceChatClient();
        if (Boolean.TRUE.equals(ready)) {
            reset();
            return;
        }

        if (!recoveryAttempted) {
            recoveryAttempted = true;
            if (recoverMissedJoin()) {
                ticksUntilCheck = VERIFY_TICKS;
                return;
            }
        }

        Lg2.LOGGER.error("Simple Voice Chat is loaded but did not initialize after joining the server (SVC {})", voiceChatVersion());
        if (client.player != null) {
            client.player.displayClientMessage(
                    Component.literal("§c[LostGlade] Simple Voice Chat загружен, но не запустился. Обнови SVC до 2.6.22+ и отключи моды, скрывающие modlist/network channels."),
                    false
            );
        }
        reset();
    }

    private static Boolean hasVoiceChatClient() {
        try {
            Class<?> managerClass = Class.forName("de.maxhenkel.voicechat.voice.client.ClientManager");
            Method getClient = managerClass.getMethod("getClient");
            return getClient.invoke(null) != null;
        } catch (ReflectiveOperationException | LinkageError exception) {
            Lg2.LOGGER.error("Could not inspect Simple Voice Chat client state", exception);
            return null;
        }
    }

    private static boolean recoverMissedJoin() {
        try {
            Class<?> managerClass = Class.forName("de.maxhenkel.voicechat.voice.client.ClientManager");
            Method instance = managerClass.getMethod("instance");
            Object manager = instance.invoke(null);
            Method onJoinWorld = managerClass.getDeclaredMethod("onJoinWorld");
            onJoinWorld.setAccessible(true);
            Lg2.LOGGER.warn("Simple Voice Chat missed Fabric JOIN; invoking its join initialization fallback");
            onJoinWorld.invoke(manager);
            return true;
        } catch (ReflectiveOperationException | LinkageError exception) {
            Lg2.LOGGER.error("Failed to recover Simple Voice Chat after a missed JOIN event", exception);
            return false;
        }
    }

    private static String voiceChatVersion() {
        return FabricLoader.getInstance().getModContainer("voicechat")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    private static void reset() {
        ticksUntilCheck = -1;
        recoveryAttempted = false;
    }
}
