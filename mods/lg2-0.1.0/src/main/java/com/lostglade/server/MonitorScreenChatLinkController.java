package com.lostglade.server;

import static com.lostglade.server.MonitorScreenMessages.*;
import static com.lostglade.server.MonitorScreenGalleryRuntime.*;
import static com.lostglade.server.MonitorScreenMediaLoadResults.*;
import static com.lostglade.server.MonitorScreenMediaActions.*;
import static com.lostglade.server.MonitorScreenMediaFrameRuntime.*;
import static com.lostglade.server.MonitorScreenMediaSessionLifecycle.*;
import static com.lostglade.server.MonitorScreenSystem.*;
import static com.lostglade.server.MonitorScreenYoutubeQueueRuntime.*;

import com.lostglade.server.monitor.MonitorMediaApp;
import com.lostglade.server.monitor.MonitorYoutubeRelayClient;
import com.lostglade.server.monitor.MonitorYoutubeMusicCache;
import com.lostglade.server.progress.TaskProgress;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

final class MonitorScreenChatLinkController {
	private MonitorScreenChatLinkController() {
	}

	static boolean onAllowChatMessage(PlayerChatMessage message, ServerPlayer sender, ChatType.Bound params) {
		if (!AccountAuthSystem.isAuthenticated(sender)) return true;
		if (sender == null || message == null) {
			return true;
		}
		MinecraftServer server = sender.level().getServer();
		if (server == null) {
			return true;
		}
		if (!MonitorMaxRuntime.onAllowChatMessage(message, sender, params)) {
			return false;
		}
		if (MonitorSupportRuntime.consumeSupportChatMessage(server, message, sender)) {
			return false;
		}
		if (MonitorYandexMapsRuntime.consumeMarkerTitleChatMessage(server, message, sender)) {
			return false;
		}
		PendingGalleryRenameRequest rename = PENDING_GALLERY_RENAMES.remove(sender.getUUID());
		if (rename != null) {
			return handleGalleryRenameChatMessage(server, message, sender, rename);
		}
		PendingMediaLinkRequest pending = PENDING_MEDIA_LINKS.remove(sender.getUUID());
		if (pending == null) {
			return true;
		}

		MediaRuntimeState state = MEDIA_STATES.get(pending.screenKey());
		if (state == null) {
			ACTIVE_MEDIA_ACTIONBARS.remove(sender.getUUID());
			sender.displayClientMessage(Component.empty(), true);
			return false;
		}

		boolean preservePlaybackDuringPrompt;
		synchronized (state) {
			preservePlaybackDuringPrompt = shouldKeepPlaybackWhilePromptingLocked(state, pending.mode(), pending.youtubeAction());
		}
		String url = message.signedContent() != null ? message.signedContent().trim() : "";
		if (url.isEmpty()) {
			synchronized (state) {
				if (preservePlaybackDuringPrompt) {
					state.overlayMode = MediaOverlayMode.CONTROLS;
				} else {
					state.waitingForLink = true;
					state.loading = false;
					state.statusText = linkPromptStatus(pending.mode(), sender);
				}
				state.version++;
			}
			PENDING_MEDIA_LINKS.put(sender.getUUID(), pending);
			ACTIVE_MEDIA_ACTIONBARS.put(sender.getUUID(), pending.screenKey());
			sender.displayClientMessage(linkPromptMessage(pending.mode(), sender), true);
			requestRuntimeRender(server, pending.screenKey());
			return false;
		}

		synchronized (state) {
			state.mode = pending.mode();
			state.overlayMode = MediaOverlayMode.CONTROLS;
			if (preservePlaybackDuringPrompt) {
				state.waitingForLink = false;
			} else {
				state.waitingForLink = false;
				state.loading = true;
				state.statusText = loadingStatus(pending.mode(), sender);
				replaceProgressTrackerLocked(state);
				state.progress.setIndeterminate("LOADING");
			}
			state.version++;
		}
		IN_FLIGHT_MEDIA_LINKS.put(sender.getUUID(), new InFlightMediaLinkRequest(pending.screenKey(), pending.mode(), pending.youtubeAction()));
		ACTIVE_MEDIA_ACTIONBARS.put(sender.getUUID(), pending.screenKey());
		sender.displayClientMessage(loadingMessage(pending.mode(), sender), true);
		requestRuntimeRender(server, pending.screenKey());
		if (!preservePlaybackDuringPrompt) {
			resumeMediaPlaybackIfNeeded(server, pending.screenKey());
		}
		TaskProgress linkLoadProgress;
		long sessionGeneration;
		synchronized (state) {
			linkLoadProgress = state.progress;
			sessionGeneration = state.sessionGeneration;
		}

		if (isYoutubeFamilyMode(pending.mode())) {
			CompletableFuture
					.supplyAsync(() -> {
						try {
							return new YoutubeQueueResolveResult(
									pending.screenKey(),
									sender.getUUID(),
									pending.mode(),
									url,
									pending.mode() == ScreenViewMode.YOUTUBE_MUSIC
											? MonitorYoutubeMusicCache.resolveQueue(url)
											: MonitorYoutubeRelayClient.resolveQueue(url),
									pending.youtubeAction(),
									sessionGeneration,
									null
							);
						} catch (Exception exception) {
							return new YoutubeQueueResolveResult(
									pending.screenKey(),
									sender.getUUID(),
									pending.mode(),
									url,
									null,
									pending.youtubeAction(),
									sessionGeneration,
									sanitizeMediaError(exception.getMessage())
							);
						}
					}, mediaIoExecutor)
					.thenAccept(result -> server.execute(() -> applyYoutubeQueueResolveResult(server, result)));
		} else {
			CompletableFuture
					.supplyAsync(() -> {
						try {
							if (pending.mode() == ScreenViewMode.GALLERY && MonitorYoutubeMusicCache.looksLikeSupportedUrl(url)) {
								MonitorYoutubeMusicCache.LoadedTrack track = MonitorYoutubeMusicCache.load(url, linkLoadProgress);
								return new MediaLoadResult(
										pending.screenKey(),
										sender.getUUID(),
										url,
										track.title(),
										track.artist(),
										GalleryItemKind.AUDIO,
										null,
										track.video(),
										sessionGeneration,
										null
								);
							}
							if (MonitorMediaApp.looksLikeDirectAudioUrl(url)) {
								MonitorMediaApp.LoadedAudioTrack track = MonitorMediaApp.loadAudioFromUrl(url, linkLoadProgress);
								return new MediaLoadResult(
										pending.screenKey(),
										sender.getUUID(),
										url,
										track.title(),
										track.artist(),
										GalleryItemKind.AUDIO,
										null,
										track.video(),
										sessionGeneration,
										null
								);
							}
							if (MonitorMediaApp.looksLikeDirectVideoUrl(url)) {
								return new MediaLoadResult(pending.screenKey(), sender.getUUID(), url, null, "", GalleryItemKind.VIDEO, null, MonitorMediaApp.loadVideoFromUrl(url, linkLoadProgress), sessionGeneration, null);
							}
							return new MediaLoadResult(pending.screenKey(), sender.getUUID(), url, null, "", GalleryItemKind.MEDIA, MonitorMediaApp.loadFromUrl(url, linkLoadProgress), null, sessionGeneration, null);
						} catch (Exception exception) {
							return new MediaLoadResult(pending.screenKey(), sender.getUUID(), url, null, "", GalleryItemKind.MEDIA, null, null, sessionGeneration, sanitizeMediaError(exception.getMessage()));
						}
					}, mediaIoExecutor)
					.thenAccept(result -> server.execute(() -> applyMediaLoadResult(server, result)));
		}
		return false;
	}

	private static boolean handleGalleryRenameChatMessage(
			MinecraftServer server,
			PlayerChatMessage message,
			ServerPlayer sender,
			PendingGalleryRenameRequest rename
	) {
		String title = sanitizeGalleryRenameTitle(message.signedContent());
		MediaRuntimeState state = MEDIA_STATES.get(rename.screenKey());
		if (state == null) {
			sender.displayClientMessage(galleryRenameNotFoundMessage(sender), true);
			return false;
		}
		boolean renamed = false;
		synchronized (state) {
			int index = resolvePendingGalleryRenameIndexLocked(state, rename);
			if (index < 0 || index >= state.galleryItems.size()) {
				state.statusText = "Файл не найден";
				state.version++;
			} else if (title.isBlank()) {
				state.statusText = "Имя не изменено";
				state.version++;
			} else {
				GalleryItem item = state.galleryItems.get(index);
				state.galleryItems.set(
						index,
						new GalleryItem(
								title,
								item.subtitle(),
								item.url(),
								item.localMediaKey(),
								item.media(),
								item.preview(),
								item.kind()
						)
				);
				if (Objects.equals(item.url(), state.sourceUrl)) {
					state.mediaTitle = title;
				}
				state.statusText = "Файл переименован";
				state.galleryFileMenuOpen = false;
				state.version++;
				renamed = true;
			}
		}
		if (renamed) {
			persistGalleryState(server, rename.screenKey(), state);
			sender.displayClientMessage(galleryRenamedMessage(sender), true);
		} else {
			sender.displayClientMessage(galleryRenameUnchangedMessage(sender), true);
		}
		requestRuntimeRender(server, rename.screenKey());
		return false;
	}

	private static int resolvePendingGalleryRenameIndexLocked(MediaRuntimeState state, PendingGalleryRenameRequest rename) {
		if (state == null || rename == null || state.galleryItems.isEmpty()) {
			return -1;
		}
		int index = rename.galleryIndex();
		if (index >= 0 && index < state.galleryItems.size()) {
			GalleryItem item = state.galleryItems.get(index);
			if (item != null && Objects.equals(item.url(), rename.itemUrl())) {
				return index;
			}
		}
		String itemUrl = rename.itemUrl();
		if (itemUrl == null || itemUrl.isBlank()) {
			return -1;
		}
		for (int candidate = 0; candidate < state.galleryItems.size(); candidate++) {
			GalleryItem item = state.galleryItems.get(candidate);
			if (item != null && Objects.equals(item.url(), itemUrl)) {
				return candidate;
			}
		}
		return -1;
	}

	private static String sanitizeGalleryRenameTitle(String rawTitle) {
		if (rawTitle == null) {
			return "";
		}
		String normalized = rawTitle.trim().replaceAll("\\s+", " ");
		if (normalized.length() <= 64) {
			return normalized;
		}
		return normalized.substring(0, 64).trim();
	}
}
