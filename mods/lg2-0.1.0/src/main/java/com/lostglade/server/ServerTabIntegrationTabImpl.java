package com.lostglade.server;

import com.lostglade.Lg2;
import me.neznamy.tab.api.TabAPI;
import me.neznamy.tab.api.TabPlayer;
import me.neznamy.tab.api.event.player.PlayerLoadEvent;
import me.neznamy.tab.api.integration.VanishIntegration;
import me.neznamy.tab.api.placeholder.PlaceholderManager;
import me.neznamy.tab.api.tablist.TabListFormatManager;
import me.neznamy.tab.shared.chat.component.TabComponent;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

final class ServerTabIntegrationTabImpl {
	private static VanishIntegration rendererBotVanishIntegration;

	private ServerTabIntegrationTabImpl() {
	}

	static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			TabAPI api = getApi();
			if (api == null) {
				Lg2.LOGGER.warn("TAB API is not available even though TAB is listed as a dependency");
				return;
			}
			Lg2.LOGGER.info("Connected TAB API");
		});
	}

	static List<ServerPlayer> getOnlinePlayers() {
		TabAPI api = getApi();
		if (api == null) {
			return List.of();
		}

		List<ServerPlayer> players = new ArrayList<>();
		for (TabPlayer tabPlayer : api.getOnlinePlayers()) {
			if (rendererBotVanishIntegration != null && rendererBotVanishIntegration.isVanished(tabPlayer)) {
				continue;
			}
			Object rawPlayer = tabPlayer.getPlayer();
			if (rawPlayer instanceof ServerPlayer serverPlayer) {
				players.add(serverPlayer);
			}
		}
		return players;
	}

	static void registerPlayerPlaceholder(String identifier, int refreshMillis, Function<ServerPlayer, String> resolver) {
		TabAPI api = getApi();
		if (api == null) {
			return;
		}

		PlaceholderManager manager = api.getPlaceholderManager();
		manager.unregisterPlaceholder(identifier);
		manager.registerPlayerPlaceholder(identifier, refreshMillis, tabPlayer -> {
			Object rawPlayer = tabPlayer.getPlayer();
			if (rawPlayer instanceof ServerPlayer serverPlayer) {
				return resolver.apply(serverPlayer);
			}
			return "";
		});
	}

	static void registerServerPlaceholder(String identifier, int refreshMillis, Supplier<String> resolver) {
		TabAPI api = getApi();
		if (api == null) {
			return;
		}

		PlaceholderManager manager = api.getPlaceholderManager();
		manager.unregisterPlaceholder(identifier);
		manager.registerServerPlaceholder(identifier, refreshMillis, resolver);
	}

	static void registerRelationalPlaceholder(String identifier, int refreshMillis,
			BiFunction<ServerPlayer, ServerPlayer, String> resolver) {
		TabAPI api = getApi();
		if (api == null) {
			return;
		}

		PlaceholderManager manager = api.getPlaceholderManager();
		manager.unregisterPlaceholder(identifier);
		manager.registerRelationalPlaceholder(identifier, refreshMillis, (viewer, target) -> {
			Object rawViewer = viewer.getPlayer();
			Object rawTarget = target.getPlayer();
			if (rawViewer instanceof ServerPlayer viewerPlayer && rawTarget instanceof ServerPlayer targetPlayer) {
				return resolver.apply(viewerPlayer, targetPlayer);
			}
			return "";
		});
	}

	static void setTabSuffix(ServerPlayer player, String suffix) {
		TabPlayer tabPlayer = getPlayer(player.getUUID());
		if (tabPlayer == null) {
			return;
		}

		TabAPI api = getApi();
		if (api == null) {
			return;
		}

		TabListFormatManager manager = api.getTabListFormatManager();
		if (manager != null) {
			manager.setSuffix(tabPlayer, suffix == null ? "" : suffix);
		}
	}

	static void setHeaderFooter(ServerPlayer player, TabComponent header, TabComponent footer) {
		if (player == null) {
			return;
		}

		TabPlayer tabPlayer = getPlayer(player.getUUID());
		if (!(tabPlayer instanceof me.neznamy.tab.shared.platform.TabPlayer sharedPlayer)) {
			return;
		}

		sharedPlayer.getTabList().setPlayerListHeaderFooter(header, footer);
	}

	static void registerPlayerLoadHandler(Consumer<ServerPlayer> handler) {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			TabAPI api = getApi();
			if (api == null) {
				return;
			}

			api.getEventBus().register(PlayerLoadEvent.class, event -> {
				Object rawPlayer = event.getPlayer().getPlayer();
				if (rawPlayer instanceof ServerPlayer serverPlayer) {
					handler.accept(serverPlayer);
				}
			});
		});
	}

	static void registerRendererBotVanishIntegration(Predicate<ServerPlayer> hiddenPredicate) {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			TabAPI api = getApi();
			if (api == null) {
				return;
			}

			if (rendererBotVanishIntegration != null) {
				rendererBotVanishIntegration.unregister();
			}

			rendererBotVanishIntegration = new VanishIntegration(Lg2.MOD_ID) {
				@Override
				public boolean canSee(TabPlayer viewer, TabPlayer target) {
					Object rawViewer = viewer == null ? null : viewer.getPlayer();
					if (rawViewer instanceof ServerPlayer viewerPlayer && !AccountAuthSystem.isAuthenticated(viewerPlayer)) {
						return false;
					}
					return !isHidden(target);
				}

				@Override
				public boolean isVanished(TabPlayer player) {
					return isHidden(player);
				}

				private boolean isHidden(TabPlayer player) {
					Object raw = player == null ? null : player.getPlayer();
					return raw instanceof ServerPlayer serverPlayer && hiddenPredicate.test(serverPlayer);
				}
			};
			rendererBotVanishIntegration.register();
		});
	}

	private static TabAPI getApi() {
		try {
			return TabAPI.getInstance();
		} catch (Throwable throwable) {
			Lg2.LOGGER.warn("Failed to access TAB API", throwable);
			return null;
		}
	}

	private static TabPlayer getPlayer(UUID playerId) {
		TabAPI api = getApi();
		return api == null ? null : api.getPlayer(playerId);
	}
}
