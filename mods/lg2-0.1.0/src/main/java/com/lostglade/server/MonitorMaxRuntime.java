package com.lostglade.server;

import com.lostglade.Lg2;
import com.lostglade.block.CameraBlock;
import com.lostglade.server.monitor.MonitorApp;
import com.lostglade.server.monitor.MonitorMediaApp;
import com.lostglade.server.monitor.MonitorYoutubeMusicCache;
import com.lostglade.server.monitor.MonitorYoutubeRelayClient;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;

import static com.lostglade.server.MonitorScreenGalleryRuntime.*;
import static com.lostglade.server.MonitorScreenLiveSources.*;
import static com.lostglade.server.MonitorScreenMediaHydration.*;
import static com.lostglade.server.MonitorScreenMediaLoadResults.*;
import static com.lostglade.server.MonitorScreenMessages.*;
import static com.lostglade.server.MonitorScreenSystem.*;

final class MonitorMaxRuntime {
	private static final String PERSISTED_MAX_ROOT_TAG = "lg2_max";
	private static final String MAX_ACCOUNT_CODE_TAG = "account_code";
	private static final String MAX_ACCOUNT_NAME_TAG = "account_name";
	private static final String MAX_AVATAR_URL_TAG = "avatar_url";
	private static final String MAX_AVATAR_LOCAL_MEDIA_TAG = "avatar_local_media";
	private static final String MAX_SELECTED_CAMERA_URL_TAG = "selected_camera_url";
	private static final String MAX_SELECTED_MICROPHONE_KEY_TAG = "selected_microphone_key";
	private static final String MAX_SELECTED_MICROPHONE_INDEX_TAG = "selected_microphone_index";
	private static final String MAX_CAMERA_ENABLED_TAG = "camera_enabled";
	private static final String MAX_MICROPHONE_ENABLED_TAG = "microphone_enabled";
	private static final String MAX_RINGTONE_URL_TAG = "ringtone_url";
	private static final String MAX_RINGTONE_LOCAL_MEDIA_TAG = "ringtone_local_media";
	private static final String MAX_RINGTONE_TITLE_TAG = "ringtone_title";
	private static final String MAX_CONTACT_COUNT_TAG = "contact_count";
	private static final String MAX_CONTACT_PREFIX = "contact_";
	private static final String MAX_INCOMING_FILE_COUNT_TAG = "incoming_file_count";
	private static final String MAX_INCOMING_FILE_PREFIX = "incoming_file_";
	private static final String MAX_INCOMING_FILE_ID_TAG = "id";
	private static final String MAX_INCOMING_FILE_SENDER_TAG = "sender";
	private static final String MAX_INCOMING_FILE_SENDER_NAME_TAG = "sender_name";
	private static final String MAX_INCOMING_FILE_SENDER_AVATAR_URL_TAG = "sender_avatar_url";
	private static final String MAX_INCOMING_FILE_SENDER_AVATAR_LOCAL_MEDIA_TAG = "sender_avatar_local_media";
	private static final String MAX_INCOMING_FILE_TITLE_TAG = "title";
	private static final String MAX_INCOMING_FILE_SUBTITLE_TAG = "subtitle";
	private static final String MAX_INCOMING_FILE_URL_TAG = "url";
	private static final String MAX_INCOMING_FILE_LOCAL_MEDIA_TAG = "local_media";
	private static final String MAX_INCOMING_FILE_KIND_TAG = "kind";
	private static final String MAX_INCOMING_FILE_CREATED_TAG = "created";
	private static final int MAX_ACCOUNT_NAME_MAX_LENGTH = 24;
	private static final String MAX_PENDING_ADD_STATUS = "Введи MAX id или ник в чат";
	private static final String MAX_PENDING_RENAME_STATUS = "Напиши новый ник в чат";
	private static final String MAX_PENDING_CALL_INVITE_STATUS = "Напиши MAX id или ник, чтобы добавить контакт и пригласить в звонок";
	private static final String MAX_RINGTONE_SOURCE_PREFIX = "max:ring:";
	private static final String MAX_RINGTONE_PREVIEW_SOURCE_PREFIX = "max:ring-preview:";
	private static final String MAX_NOTIFICATION_PREVIEW_SOURCE_PREFIX = "max:notification-preview:";
	private static final String MAX_DEFAULT_RINGTONE_URL = "max:default-ringtone";
	private static final String MAX_TRANSFER_URL_PREFIX = "max:file:";
	private static final String MAX_TRANSFER_LOCAL_KEY_PREFIX = "max_transfer_";
	private static final Identifier MAX_NOTIFICATION_SOUND_ID = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "max_notification");
	private static final SoundEvent MAX_NOTIFICATION_SOUND = SoundEvent.createVariableRangeEvent(MAX_NOTIFICATION_SOUND_ID);
	private static final long MAX_RINGTONE_PREVIEW_TIMELINE_MS = 30_000L;
	private static final long MAX_AVATAR_ANIMATION_RENDER_DELAY_MS = 80L;
	private static final long MAX_NOTIFICATION_PREVIEW_RENDER_FALLBACK_DELAY_MS = 120L;
	private static final Path DEFAULT_PROJECT_RINGTONE = Path.of(System.getProperty("user.dir"), "server-assets", "max", "default-ringtone.mp3");
	private static final Path DEFAULT_SOURCE_RINGTONE = Path.of("/home/mart/Downloads/Rington_-_na_zvonok_(SkySound.cc).mp3");
	private static final Map<ScreenRuntimeKey, MaxRuntimeState> MAX_STATES = new ConcurrentHashMap<>();
	private static final Map<String, ScreenRuntimeKey> ACCOUNT_INDEX = new ConcurrentHashMap<>();
	private static final Map<String, ScreenRuntimeKey> ACCOUNT_NAME_INDEX = new ConcurrentHashMap<>();
	private static final Map<String, MaxStoredAccountProfile> ACCOUNT_PROFILE_INDEX = new ConcurrentHashMap<>();
	private static final Map<UUID, ScreenRuntimeKey> PENDING_CONTACT_CODE_INPUTS = new ConcurrentHashMap<>();
	private static final Map<UUID, ScreenRuntimeKey> PENDING_ACCOUNT_NAME_INPUTS = new ConcurrentHashMap<>();
	private static final Map<UUID, ScreenRuntimeKey> PENDING_CALL_CONTACT_INVITES = new ConcurrentHashMap<>();
	private static final Map<UUID, MaxCallSession> CALLS_BY_ID = new ConcurrentHashMap<>();
	private static final Map<ScreenRuntimeKey, UUID> CALL_BY_SCREEN = new ConcurrentHashMap<>();

	private MonitorMaxRuntime() {
	}

	static MaxVisualSnapshot captureSnapshot(MinecraftServer server, ScreenComponent component) {
		if (server == null || component == null) {
			return emptySnapshot();
		}
		MaxRuntimeState state = ensureState(server, component);
		if (state == null) {
			return emptySnapshot();
		}
		boolean sanitizedContacts;
		List<LiveCameraReference> cameras = connectedCameraReferences(server, component);
		synchronized (state) {
			boolean incomingSendersAdded = ensureIncomingSendersAsContactsLocked(state);
			sanitizedContacts = sanitizeContactsLocked(state) || incomingSendersAdded;
			if (sanitizedContacts) {
				state.version++;
			}
			boolean selectedCameraMissing = state.selectedCameraUrl != null
					&& !state.selectedCameraUrl.isBlank()
					&& cameras.stream().map(MonitorScreenLiveSources::liveCameraGalleryUrl).noneMatch(url -> Objects.equals(url, state.selectedCameraUrl));
			if ((state.selectedCameraUrl == null || state.selectedCameraUrl.isBlank() || selectedCameraMissing) && !cameras.isEmpty()) {
				state.selectedCameraUrl = liveCameraGalleryUrl(cameras.get(0));
				state.version++;
				persistState(server, component.runtimeKey(), state);
			}
		}
		if (sanitizedContacts) {
			persistState(server, component.runtimeKey(), state);
		}
		ScreenRuntimeKey runtimeKey = component.runtimeKey();
		MaxCallSession call = currentCall(runtimeKey);
		MaxCallVisualSnapshot callSnapshot = captureCallSnapshot(server, component, state, cameras);
		UiLayout overlayLayout = createUiLayout(component.width(), component.height());
		WindowedSnapshot<MaxContactSnapshot> contacts = contactFeedSnapshots(server, state, runtimeKey, overlayLayout);
		WindowedSnapshot<MaxAvatarCandidateSnapshot> avatarCandidates = WindowedSnapshot.empty();
		int avatarPickerScroll;
		WindowedSnapshot<MaxRingtoneCandidateSnapshot> ringtoneCandidates = WindowedSnapshot.empty();
		int ringtonePickerScroll;
		WindowedSnapshot<MaxFileShareContactSnapshot> fileShareContacts = WindowedSnapshot.empty();
		int fileSharePickerScroll;
		WindowedSnapshot<MaxContactSnapshot> callContactCandidates = WindowedSnapshot.empty();
		boolean avatarPickerOpen;
		boolean ringtonePickerOpen;
		boolean fileSharePickerOpen;
		boolean callContactPickerOpen;
		boolean notificationsOpen;
		String notificationContactCode;
		List<MaxIncomingFile> notificationEntries;
		MaxNotificationPreviewVisualState notificationPreviewState;
		WindowedSnapshot<MaxIncomingFileSnapshot> incomingFiles;
		int notificationScroll;
		synchronized (state) {
			pruneIncomingPreviewCacheLocked(state);
			avatarPickerOpen = state.avatarPickerOpen;
			ringtonePickerOpen = state.ringtonePickerOpen;
			fileSharePickerOpen = state.fileSharePickerOpen;
			callContactPickerOpen = state.callContactPickerOpen;
			if (state.notificationsOpen && maxNotificationRawIndexesForActiveContactLocked(state).isEmpty()) {
				closeNotificationsLocked(state);
				state.version++;
			}
			notificationsOpen = state.notificationsOpen;
			notificationContactCode = state.notificationContactCode;
			notificationEntries = notificationsOpen ? List.copyOf(state.incomingFiles) : List.of();
			notificationPreviewState = notificationsOpen
					? new MaxNotificationPreviewVisualState(
							state.notificationPreviewFileId,
							currentNotificationPreviewFrameLocked(state, null),
							state.notificationPreviewPlaying,
							state.notificationPreviewLoading
					)
					: null;
		}
		if (avatarPickerOpen) {
			avatarCandidates = avatarCandidateSnapshots(component, state, overlayLayout, runtimeKey);
		}
		if (ringtonePickerOpen) {
			ringtoneCandidates = ringtoneCandidateSnapshots(component, state, overlayLayout, runtimeKey);
		}
		if (fileSharePickerOpen) {
			fileShareContacts = fileShareContactSnapshots(server, state, runtimeKey, overlayLayout, runtimeKey);
		}
		if (callContactPickerOpen && call != null) {
			callContactCandidates = callContactCandidateSnapshots(server, state, call, runtimeKey, overlayLayout, runtimeKey);
		}
		incomingFiles = notificationsOpen
				? incomingFileSnapshots(server, runtimeKey, state, notificationContactCode, notificationEntries, notificationPreviewState, overlayLayout)
				: WindowedSnapshot.empty();
		synchronized (state) {
			state.avatarPickerScroll = clampInt(state.avatarPickerScroll, 0, maxAvatarPickerScroll(overlayLayout, avatarCandidates.totalCount()));
			avatarPickerScroll = state.avatarPickerScroll;
			state.ringtonePickerScroll = clampInt(state.ringtonePickerScroll, 0, maxRingtonePickerScroll(overlayLayout, ringtoneCandidates.totalCount()));
			ringtonePickerScroll = state.ringtonePickerScroll;
			state.fileSharePickerScroll = clampInt(state.fileSharePickerScroll, 0, maxContactPickerScroll(overlayLayout, fileShareContacts.totalCount()));
			fileSharePickerScroll = state.fileSharePickerScroll;
			state.callContactPickerScroll = clampInt(state.callContactPickerScroll, 0, maxCallContactPickerScroll(overlayLayout, callContactCandidates.totalCount()));
			state.notificationScroll = clampInt(state.notificationScroll, 0, maxNotificationScroll(overlayLayout, incomingFiles.totalCount()));
			notificationScroll = state.notificationScroll;
			boolean animatedAvatars = maxAnimatedAvatarsVisible(component, state, contacts.items(), callSnapshot, incomingFiles.items());
			if (animatedAvatars) {
				scheduleAvatarAnimationRender(server, runtimeKey, state);
			}
			return new MaxVisualSnapshot(
					state.version,
					state.accountCode,
					state.accountName,
					currentAvatarFrameLocked(state),
					contacts,
					callSnapshot,
					avatarCandidates,
					avatarPickerScroll,
					ringtoneCandidates,
					ringtonePickerScroll,
					fileShareContacts,
					fileSharePickerScroll,
					incomingFiles,
					notificationScroll,
					state.incomingFiles.size(),
					state.fileShareFiles.size(),
					state.fileShareSelectedContacts.size(),
					fileShareTitleLocked(state),
					callContactCandidates,
					state.avatarPickerOpen,
					state.ringtonePickerOpen,
					state.fileSharePickerOpen,
					state.notificationsOpen,
					animatedAvatars,
					state.ringtonePreviewPlaying,
					state.statusText
			);
		}
	}

	static boolean handleTouch(ServerPlayer player, ServerLevel level, ScreenComponent component, UiLayout layout, UiPoint touchPoint) {
		if (player == null || level == null || component == null || layout == null || touchPoint == null) {
			return false;
		}
		MinecraftServer server = level.getServer();
		if (server == null) {
			return false;
		}
		MaxRuntimeState state = ensureState(server, component);
		if (state == null) {
			return false;
		}
		if (mediaCloseRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			deactivateRuntime(server, component.runtimeKey());
			applyTransientComponentViewState(server, level, component, ScreenViewMode.HOME, component.launcherPage());
			return true;
		}
		boolean avatarPickerOpen;
		boolean ringtonePickerOpen;
		boolean fileSharePickerOpen;
		boolean notificationsOpen;
		synchronized (state) {
			avatarPickerOpen = state.avatarPickerOpen;
			ringtonePickerOpen = state.ringtonePickerOpen;
			fileSharePickerOpen = state.fileSharePickerOpen;
			notificationsOpen = state.notificationsOpen;
		}
		if (fileSharePickerOpen) {
			return handleFileSharePickerTouch(server, component, state, layout, touchPoint);
		}
		if (notificationsOpen) {
			return handleNotificationsTouch(server, component, state, layout, touchPoint);
		}
		if (avatarPickerOpen) {
			return handleAvatarPickerTouch(server, component, state, layout, touchPoint);
		}
		if (ringtonePickerOpen) {
			return handleRingtonePickerTouch(server, component, state, layout, touchPoint);
		}

		if (handleCallTouch(player, level, component, state, layout, touchPoint)) {
			return true;
		}

		if (maxProfileAvatarRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			synchronized (state) {
				state.avatarPickerOpen = true;
				state.avatarPickerScroll = 0;
				state.statusText = "";
				state.version++;
			}
			requestRuntimeRender(server, component.runtimeKey());
			return true;
		}
		if (maxProfileCodeRect(layout).contains(touchPoint.x(), touchPoint.y())
				|| maxAppTitleRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			String accountName;
			synchronized (state) {
				accountName = state.accountName;
				state.statusText = MAX_PENDING_RENAME_STATUS;
				state.version++;
			}
			PENDING_ACCOUNT_NAME_INPUTS.put(player.getUUID(), component.runtimeKey());
			PENDING_CONTACT_CODE_INPUTS.remove(player.getUUID());
			PENDING_CALL_CONTACT_INVITES.remove(player.getUUID());
			sendAccountRenamePrompt(player, accountName);
			requestRuntimeRender(server, component.runtimeKey());
			return true;
		}
		if (maxRingtonePreviewRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			toggleSelectedRingtonePreview(server, component, state);
			return true;
		}
		if (maxRingtonePickerOpenRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			synchronized (state) {
				state.ringtonePickerOpen = true;
				state.ringtonePickerScroll = 0;
				state.avatarPickerOpen = false;
				closeNotificationsLocked(state);
				state.fileSharePickerOpen = false;
				state.statusText = "";
				state.version++;
			}
			refreshConnectedSpeakersNow(server, component.runtimeKey());
			requestRuntimeRender(server, component.runtimeKey());
			return true;
		}
		if (maxAddContactRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			PENDING_CONTACT_CODE_INPUTS.put(player.getUUID(), component.runtimeKey());
			PENDING_ACCOUNT_NAME_INPUTS.remove(player.getUUID());
			PENDING_CALL_CONTACT_INVITES.remove(player.getUUID());
			synchronized (state) {
				state.statusText = MAX_PENDING_ADD_STATUS;
				state.version++;
			}
			player.displayClientMessage(maxAddContactPromptMessage(player), true);
			requestRuntimeRender(server, component.runtimeKey());
			return true;
		}
		WindowedSnapshot<MaxContactSnapshot> contactWindow = contactFeedSnapshots(server, state, component.runtimeKey(), layout);
		List<MaxContactSnapshot> contactSnapshots = contactWindow.items();
		int contactIndex = maxContactIndexAt(layout, contactSnapshots.size(), touchPoint);
		if (contactIndex >= 0) {
			MaxContactSnapshot contact = contactIndex < contactSnapshots.size() ? contactSnapshots.get(contactIndex) : null;
			String contactCode = contact != null ? contact.code() : null;
			UiRect contactRow = maxContactRowRect(layout, contactIndex);
			if (contact != null
					&& contact.notificationCount() > 0
					&& maxContactNotificationRect(contactRow, layout, contact.notificationCount(), contact.savedContact()).contains(touchPoint.x(), touchPoint.y())) {
				openContactNotifications(server, component.runtimeKey(), state, contact.code());
				return true;
			}
			if (contact != null && contact.savedContact() && maxContactDeleteRect(contactRow, layout).contains(touchPoint.x(), touchPoint.y())) {
				removeContact(server, component.runtimeKey(), state, contactCode);
				return true;
			}
			if (contactCode != null && !contactCode.isBlank()) {
				startCall(server, component.runtimeKey(), contactCode);
			}
			return true;
		}
		return true;
	}

	static boolean handleGlobalTouch(ServerPlayer player, ServerLevel level, ScreenComponent component, UiLayout layout, UiPoint touchPoint) {
		if (player == null || level == null || component == null || layout == null || touchPoint == null) {
			return false;
		}
		MinecraftServer server = level.getServer();
		if (server == null || !hasVisibleCall(component.runtimeKey())) {
			return false;
		}
		MaxRuntimeState state = ensureState(server, component);
		return state != null && handleCallTouch(player, level, component, state, layout, touchPoint);
	}

	static boolean onPlayerHotbarScroll(ServerPlayer player, int previousSlot, int currentSlot) {
		if (player == null) {
			return false;
		}
		ObservedCallUiTarget target = findObservedCallUiTarget(player);
		if (target == null || target.touchPoint() == null) {
			return false;
		}
		MinecraftServer server = player.level().getServer();
		if (server == null) {
			return false;
		}
		ScreenComponent component = target.component();
		int delta = normalizeHotbarDelta(previousSlot, currentSlot);
		if (delta == 0) {
			return false;
		}
		MaxRuntimeState state = MAX_STATES.get(component.runtimeKey());
		if (state == null) {
			return false;
		}
		if (handleOverlayHotbarScroll(server, component, state, target.layout(), target.touchPoint(), delta)) {
			return true;
		}
		if (handleContactFeedHotbarScroll(server, component, state, target.layout(), target.touchPoint(), delta)) {
			return true;
		}
		MaxCallSession call = currentCall(component.runtimeKey());
		if (state == null || call == null || callPhase(call, component.runtimeKey()) != MaxCallPhase.ACTIVE || !callFocused(state) || callMiniParticipantsHidden(state)) {
			return false;
		}
		List<ScreenRuntimeKey> miniParticipants = callMiniParticipantKeys(component.runtimeKey(), state, call);
		if (miniParticipants.isEmpty() || !maxCallMiniStripViewportRect(target.layout(), miniParticipants.size()).contains(target.touchPoint().x(), target.touchPoint().y())) {
			return false;
		}
		if (maxCallMiniParticipantScroll(target.layout(), miniParticipants.size()) <= 0) {
			return false;
		}
		int previousScroll = callMiniParticipantScroll(state);
		scrollCallMiniParticipants(server, component.runtimeKey(), state, target.layout(), miniParticipants.size(), -delta);
		return callMiniParticipantScroll(state) != previousScroll;
	}

	private static ObservedCallUiTarget findObservedCallUiTarget(ServerPlayer player) {
		ObservedScreenTouch target = findObservedScreenTouch(
				player,
				component -> component.viewMode() == ScreenViewMode.MAX || hasVisibleCall(component.runtimeKey())
		);
		if (target == null) {
			return null;
		}
		ScreenComponent component = target.component();
		return new ObservedCallUiTarget(component, createUiLayout(component.width(), component.height()), target.touchPoint());
	}

	private static boolean handleContactFeedHotbarScroll(
			MinecraftServer server,
			ScreenComponent component,
			MaxRuntimeState state,
			UiLayout layout,
			UiPoint touchPoint,
			int delta
	) {
		if (server == null || component == null || state == null || layout == null || touchPoint == null || delta == 0
				|| component.viewMode() != ScreenViewMode.MAX || currentCall(component.runtimeKey()) != null
				|| !maxContactListRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			return false;
		}
		int previousScroll;
		int nextScroll;
		synchronized (state) {
			if (state.avatarPickerOpen || state.ringtonePickerOpen || state.fileSharePickerOpen || state.notificationsOpen || state.callContactPickerOpen) {
				return false;
			}
			int maxScroll = Math.max(0, state.contacts.size() - maxVisibleContactRows(layout));
			if (maxScroll <= 0) {
				state.contactScroll = 0;
				return false;
			}
			previousScroll = clampInt(state.contactScroll, 0, maxScroll);
			nextScroll = clampInt(previousScroll - delta, 0, maxScroll);
			state.contactScroll = nextScroll;
			if (nextScroll != previousScroll) {
				state.version++;
			}
		}
		if (nextScroll == previousScroll) {
			return false;
		}
		requestRuntimeRender(server, component.runtimeKey());
		return true;
	}

	private static boolean handleOverlayHotbarScroll(
			MinecraftServer server,
			ScreenComponent component,
			MaxRuntimeState state,
			UiLayout layout,
			UiPoint touchPoint,
			int delta
	) {
		if (server == null || component == null || state == null || layout == null || touchPoint == null || delta == 0) {
			return false;
		}
		boolean avatarPickerOpen;
		boolean ringtonePickerOpen;
		boolean fileSharePickerOpen;
		boolean notificationsOpen;
		boolean cameraPickerOpen;
		boolean contactPickerOpen;
		synchronized (state) {
			avatarPickerOpen = state.avatarPickerOpen;
			ringtonePickerOpen = state.ringtonePickerOpen;
			fileSharePickerOpen = state.fileSharePickerOpen;
			notificationsOpen = state.notificationsOpen;
			cameraPickerOpen = state.cameraPickerOpen;
			contactPickerOpen = state.callContactPickerOpen;
		}
		if (avatarPickerOpen && maxAvatarPickerGridRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			int candidateCount = avatarCandidates(component).size();
			if (candidateCount > maxAvatarPickerCapacity(layout)) {
				scrollAvatarPicker(server, component.runtimeKey(), state, layout, candidateCount, -delta);
				return true;
			}
		}
		if (ringtonePickerOpen && maxAvatarPickerGridRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			int candidateCount = ringtoneCandidates(component, state).size();
			if (candidateCount > maxRingtonePickerCapacity(layout)) {
				scrollRingtonePicker(server, component.runtimeKey(), state, layout, candidateCount, -delta);
				return true;
			}
		}
		if (fileSharePickerOpen && maxContactPickerListRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			List<MaxFileShareContactSnapshot> contacts;
			synchronized (state) {
				contacts = fileShareContacts(server, state, component.runtimeKey());
			}
			if (contacts.size() > maxContactPickerCapacity(layout)) {
				scrollFileSharePicker(server, component.runtimeKey(), state, layout, contacts.size(), -delta);
				return true;
			}
		}
		if (notificationsOpen && maxNotificationFeedRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			int maxScroll;
			synchronized (state) {
				maxScroll = maxNotificationScroll(layout, maxNotificationRawIndexesForActiveContactLocked(state).size());
			}
			if (maxScroll > 0) {
				scrollNotifications(server, component.runtimeKey(), state, maxScroll, -delta * maxNotificationScrollDelta(layout));
				return true;
			}
		}
		MaxCallSession call = currentCall(component.runtimeKey());
		if (cameraPickerOpen && call != null && callPhase(call, component.runtimeKey()) == MaxCallPhase.ACTIVE) {
			List<LiveCameraReference> cameraRefs = connectedCameraReferences(server, component);
			List<MaxCameraOptionSnapshot> cameras = cameraOptions(server, state, cameraRefs);
			List<MicrophoneSystem.ScreenMicrophoneDevice> microphones = MicrophoneSystem.connectedMicrophoneDevices(server, component.runtimeKey());
			if (maxCallDeviceCameraGridRect(layout).contains(touchPoint.x(), touchPoint.y()) && cameras.size() > maxCallDeviceCameraCapacity(layout)) {
				scrollCallCameraPicker(server, component.runtimeKey(), state, layout, cameras.size(), microphones.size(), -delta);
				return true;
			}
			if (maxCallDeviceMicrophoneListRect(layout).contains(touchPoint.x(), touchPoint.y()) && microphones.size() > maxCallDeviceMicrophoneCapacity(layout)) {
				scrollCallMicrophonePicker(server, component.runtimeKey(), state, layout, cameras.size(), microphones.size(), -delta);
				return true;
			}
		}
		if (contactPickerOpen && call != null && maxCallContactPickerGridRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			List<MaxContactSnapshot> contacts = contactInviteCandidates(server, state, call, component.runtimeKey());
			if (contacts.size() > maxCallContactPickerCapacity(layout)) {
				scrollCallContactPicker(server, component.runtimeKey(), state, layout, contacts.size(), -delta);
				return true;
			}
		}
		return false;
	}

	private static boolean handleCallParticipantAddContactTouch(
			ServerPlayer player,
			MinecraftServer server,
			ScreenComponent component,
			MaxRuntimeState state,
			MaxCallSession call,
			ScreenRuntimeKey participantKey,
			UiRect participantTile,
			UiLayout layout,
			UiPoint touchPoint
	) {
		if (player == null || server == null || component == null || state == null || call == null || participantKey == null
				|| Objects.equals(participantKey, component.runtimeKey()) || participantTile == null || touchPoint == null
				|| !maxCallParticipantAddContactRect(participantTile, layout).contains(touchPoint.x(), touchPoint.y())) {
			return false;
		}
		String code = normalizeAccountCode(call.participantCode(participantKey));
		MaxRuntimeState participantState = MAX_STATES.get(participantKey);
		if (participantState != null) {
			synchronized (participantState) {
				if (participantState.accountCode != null && !participantState.accountCode.isBlank()) {
					code = normalizeAccountCode(participantState.accountCode);
				}
			}
		}
		if (code.isBlank()) {
			return true;
		}
		boolean added;
		synchronized (state) {
			added = !Objects.equals(code, normalizeAccountCode(state.accountCode)) && !state.contacts.contains(code);
			if (added) {
				state.contacts.add(code);
				state.statusText = "Контакт добавлен";
				state.version++;
			}
		}
		if (added) {
			persistState(server, component.runtimeKey(), state);
			requestRuntimeRender(server, component.runtimeKey());
			player.displayClientMessage(maxContactFeedbackMessage(player, "added"), true);
		}
		return true;
	}

	private static boolean handleCallTouch(ServerPlayer player, ServerLevel level, ScreenComponent component, MaxRuntimeState state, UiLayout layout, UiPoint touchPoint) {
		if (level == null || component == null || state == null || layout == null || touchPoint == null) {
			return false;
		}
		MinecraftServer server = level.getServer();
		if (server == null) {
			return false;
		}
		MaxCallSession call = currentCall(component.runtimeKey());
		MaxCallPhase phase = callPhase(call, component.runtimeKey());
		if (phase == MaxCallPhase.IDLE) {
			return false;
		}
		if (phase == MaxCallPhase.ACTIVE) {
			boolean cameraPickerOpen;
			boolean contactPickerOpen;
			synchronized (state) {
				cameraPickerOpen = state.cameraPickerOpen;
				contactPickerOpen = state.callContactPickerOpen;
			}
			if (cameraPickerOpen) {
				return handleCallCameraPickerTouch(server, component, state, layout, touchPoint);
			}
			if (contactPickerOpen) {
				return handleCallContactPickerTouch(player, server, component, state, call, layout, touchPoint);
			}
		}
		if (phase == MaxCallPhase.INCOMING) {
			if (maxIncomingAcceptRect(layout).contains(touchPoint.x(), touchPoint.y())) {
				acceptCall(server, component.runtimeKey());
				return true;
			}
			if (maxIncomingDeclineRect(layout).contains(touchPoint.x(), touchPoint.y())) {
				endCall(server, component.runtimeKey());
				return true;
			}
			return true;
		}
		if (phase == MaxCallPhase.OUTGOING) {
			if (maxOutgoingCancelRect(layout).contains(touchPoint.x(), touchPoint.y())) {
				endCall(server, component.runtimeKey());
				return true;
			}
			return true;
		}
		boolean menuVisible = isCallMenuVisible(state);
		boolean focused = callFocused(state);
		int microphoneCount = MicrophoneSystem.connectedMicrophoneCount(server, component.runtimeKey());
		boolean multiMicrophone = microphoneCount > 1;
		if (menuVisible) {
			if (maxCallLeaveRect(layout, multiMicrophone, focused).contains(touchPoint.x(), touchPoint.y())) {
				endCall(server, component.runtimeKey());
				return true;
			}
			if (maxCallCameraToggleRect(layout, multiMicrophone, focused).contains(touchPoint.x(), touchPoint.y())) {
				toggleCamera(server, component.runtimeKey());
				return true;
			}
			if (maxCallCameraSelectRect(layout, multiMicrophone, focused).contains(touchPoint.x(), touchPoint.y())) {
				openCameraPicker(server, component.runtimeKey());
				return true;
			}
			if (maxCallInviteRect(layout, multiMicrophone, focused).contains(touchPoint.x(), touchPoint.y())) {
				openCallContactPicker(server, component.runtimeKey());
				return true;
			}
			if (maxCallMicrophoneToggleRect(layout, multiMicrophone, focused).contains(touchPoint.x(), touchPoint.y())) {
				toggleMicrophone(server, component.runtimeKey());
				return true;
			}
			if (multiMicrophone && maxCallMicrophoneSelectRect(layout, focused).contains(touchPoint.x(), touchPoint.y())) {
				openCameraPicker(server, component.runtimeKey());
				return true;
			}
		}
		boolean focusedSelf = callFocusSelf(state);
		boolean focusedPeer = callFocusPeer(state);
		if (focusedSelf || focusedPeer) {
			List<ScreenRuntimeKey> miniParticipants = callMiniParticipantKeys(component.runtimeKey(), state, call);
			boolean miniParticipantsHidden = callMiniParticipantsHidden(state);
			if (menuVisible) {
				if (maxCallGridExitRect(layout, miniParticipants.size(), miniParticipantsHidden).contains(touchPoint.x(), touchPoint.y())) {
					clearCallFocus(server, component.runtimeKey());
					return true;
				}
				if (maxCallMiniStripToggleRect(layout, miniParticipants.size(), miniParticipantsHidden).contains(touchPoint.x(), touchPoint.y())) {
					toggleCallMiniParticipantsHidden(server, component.runtimeKey());
					return true;
				}
			}
			if (!miniParticipantsHidden && !miniParticipants.isEmpty()) {
				int visibleRows = maxCallMiniParticipantVisibleRows(layout, miniParticipants.size());
				if (scrollbarVisible(visibleRows, miniParticipants.size())
						&& maxCallMiniStripTrackRect(layout, miniParticipants.size()).contains(touchPoint.x(), touchPoint.y())) {
					setCallMiniParticipantScroll(
							server,
							component.runtimeKey(),
							state,
							layout,
							miniParticipants.size(),
							scrollValueForTrack(
									maxCallMiniStripTrackRect(layout, miniParticipants.size()),
									visibleRows,
									miniParticipants.size(),
									touchPoint.y()
							)
					);
					return true;
				}
				int miniIndex = maxCallMiniParticipantIndexAt(layout, miniParticipants.size(), callMiniParticipantScroll(state), touchPoint);
				if (miniIndex >= 0 && miniIndex < miniParticipants.size()) {
					ScreenRuntimeKey selected = miniParticipants.get(miniIndex);
					if (Objects.equals(selected, component.runtimeKey())) {
						focusCallParticipant(server, component.runtimeKey(), true);
					} else {
						focusCallParticipant(server, component.runtimeKey(), selected);
					}
					return true;
				}
			}
			ScreenRuntimeKey focusedParticipant = focusedCallParticipantKey(component.runtimeKey(), state, call);
			if (handleCallParticipantAddContactTouch(player, server, component, state, call, focusedParticipant, maxCallFocusedTileRect(layout), layout, touchPoint)) {
				return true;
			}
			if (maxCallFocusedTileRect(layout).contains(touchPoint.x(), touchPoint.y())) {
				toggleCallMenu(server, component.runtimeKey());
				return true;
			}
			return true;
		}
		List<ScreenRuntimeKey> participants = call.participants();
		int participantIndex = maxCallParticipantIndexAt(layout, participants.size(), menuVisible, touchPoint);
		if (participantIndex >= 0 && participantIndex < participants.size()) {
			ScreenRuntimeKey selected = participants.get(participantIndex);
			UiRect selectedTile = maxCallParticipantTileRect(layout, participants.size(), participantIndex, menuVisible);
			if (handleCallParticipantAddContactTouch(player, server, component, state, call, selected, selectedTile, layout, touchPoint)) {
				return true;
			}
			if (Objects.equals(selected, component.runtimeKey())) {
				focusCallParticipant(server, component.runtimeKey(), true);
			} else {
				focusCallParticipant(server, component.runtimeKey(), selected);
			}
			return true;
		}
		toggleCallMenu(server, component.runtimeKey());
		return true;
	}

	static boolean onAllowChatMessage(PlayerChatMessage message, ServerPlayer sender, ChatType.Bound params) {
		if (sender == null || message == null) {
			return true;
		}
		ScreenRuntimeKey renameKey = PENDING_ACCOUNT_NAME_INPUTS.remove(sender.getUUID());
		if (renameKey != null) {
			return handleAccountNameInput(message, sender, renameKey);
		}
		ScreenRuntimeKey inviteKey = PENDING_CALL_CONTACT_INVITES.remove(sender.getUUID());
		if (inviteKey != null) {
			return handleCallContactInviteInput(message, sender, inviteKey);
		}
		ScreenRuntimeKey key = PENDING_CONTACT_CODE_INPUTS.remove(sender.getUUID());
		if (key == null) {
			return true;
		}
		MinecraftServer server = sender.level().getServer();
		if (server == null) {
			return false;
		}
		ScreenComponent component = resolveScreenComponent(server, key);
		MaxRuntimeState state = component != null ? ensureState(server, component) : MAX_STATES.get(key);
		String rawInput = message.signedContent() != null ? message.signedContent() : "";
		String code = resolveAccountCode(server, rawInput);
		if (state == null || code.isBlank()) {
			sender.displayClientMessage(maxContactNotFoundMessage(sender), true);
			return false;
		}
		String ownCode;
		boolean added;
		String feedbackKey;
		synchronized (state) {
			ownCode = state.accountCode;
			if (Objects.equals(code, ownCode)) {
				state.statusText = "Это код этого экрана";
				added = false;
				feedbackKey = "self";
			} else if (!state.contacts.contains(code)) {
				state.contacts.add(code);
				state.statusText = "Контакт добавлен";
				added = true;
				feedbackKey = "added";
			} else {
				state.statusText = "Контакт уже добавлен";
				added = false;
				feedbackKey = "duplicate";
			}
			state.version++;
		}
		if (added) {
			persistState(server, key, state);
		}
		sender.displayClientMessage(maxContactFeedbackMessage(sender, feedbackKey), true);
		requestRuntimeRender(server, key);
		return false;
	}

	private static boolean handleCallContactInviteInput(PlayerChatMessage message, ServerPlayer sender, ScreenRuntimeKey key) {
		if (sender == null || message == null || key == null) {
			return false;
		}
		MinecraftServer server = sender.level().getServer();
		if (server == null) {
			return false;
		}
		ScreenComponent component = resolveScreenComponent(server, key);
		MaxRuntimeState state = component != null ? ensureState(server, component) : MAX_STATES.get(key);
		if (state == null) {
			return false;
		}
		String code = resolveAccountCode(server, message.signedContent());
		if (code.isBlank()) {
			sender.displayClientMessage(maxContactNotFoundMessage(sender), true);
			return false;
		}
		String ownCode;
		boolean added;
		synchronized (state) {
			ownCode = state.accountCode;
			if (Objects.equals(code, ownCode)) {
				state.statusText = "Это id этого экрана";
				state.version++;
				sender.displayClientMessage(maxContactFeedbackMessage(sender, "self"), true);
				requestRuntimeRender(server, key);
				return false;
			}
			added = !state.contacts.contains(code);
			if (added) {
				state.contacts.add(code);
				state.version++;
			}
		}
		if (added) {
			persistState(server, key, state);
		}
		boolean invited = inviteContactToCall(server, key, code);
		if (invited) {
			sender.displayClientMessage(maxCallInviteFeedbackMessage(sender, added), true);
		} else if (added) {
			sender.displayClientMessage(maxContactFeedbackMessage(sender, "added"), true);
			requestRuntimeRender(server, key);
		}
		return false;
	}

	private static boolean handleAccountNameInput(PlayerChatMessage message, ServerPlayer sender, ScreenRuntimeKey key) {
		if (sender == null || message == null || key == null) {
			return false;
		}
		MinecraftServer server = sender.level().getServer();
		if (server == null) {
			return false;
		}
		ScreenComponent component = resolveScreenComponent(server, key);
		MaxRuntimeState state = component != null ? ensureState(server, component) : MAX_STATES.get(key);
		if (state == null) {
			return false;
		}
		String previousName;
		synchronized (state) {
			previousName = state.accountName;
		}
		String accountName = sanitizeAccountName(message.signedContent());
		if (accountName.isBlank()) {
			sender.displayClientMessage(maxAccountNameBlankMessage(sender), true);
			return false;
		}
		if (accountNameCodePointLength(accountName) > MAX_ACCOUNT_NAME_MAX_LENGTH) {
			sender.displayClientMessage(maxAccountNameTooLongMessage(sender), true);
			return false;
		}
		if (Objects.equals(accountName, previousName)) {
			sender.displayClientMessage(maxAccountNameUnchangedMessage(sender), true);
			return false;
		}
		if (accountNameTakenByOther(accountName, key) || accountNameConflictsWithAccountCode(accountName, key)) {
			sender.displayClientMessage(maxAccountNameTakenMessage(sender), true);
			return false;
		}
		String previousNameKey = accountNameLookupKey(previousName);
		synchronized (state) {
			state.accountName = accountName;
			state.statusText = "Ник обновлён";
			state.version++;
		}
		if (!previousNameKey.isBlank()) {
			ACCOUNT_NAME_INDEX.remove(previousNameKey, key);
		}
		ACCOUNT_NAME_INDEX.put(accountNameLookupKey(accountName), key);
		persistState(server, key, state);
		requestAllMaxRenders(server);
		sender.displayClientMessage(maxAccountNameUpdatedMessage(sender), true);
		return false;
	}


	static List<SpeakerAudioSource> findSpeakerAudioSources(MinecraftServer server, Collection<ScreenComponent> components) {
		if (server == null || components == null || components.isEmpty()) {
			return List.of();
		}
		List<SpeakerAudioSource> sources = new ArrayList<>();
		for (ScreenComponent component : components) {
			if (component == null || component.runtimeKey() == null || !component.powered()) {
				continue;
			}
			MaxRuntimeState state = MAX_STATES.get(component.runtimeKey());
			if (state != null) {
				SpeakerAudioSource preview = previewRingtoneSource(component.runtimeKey(), state);
				if (preview != null) {
					sources.add(preview);
				}
				SpeakerAudioSource notificationPreview = notificationPreviewSource(component.runtimeKey(), state);
				if (notificationPreview != null) {
					sources.add(notificationPreview);
				}
			}
			MaxCallSession call = currentCall(component.runtimeKey());
			if (call == null || !call.isRinging(component.runtimeKey())) {
				continue;
			}
			String ringtone = ringtoneSourceForScreen(call.inviterFor(component.runtimeKey()));
			if (ringtone == null || ringtone.isBlank()) {
				continue;
			}
			String sourceKey = MAX_RINGTONE_SOURCE_PREFIX + call.id + ":" + componentGroupId(component.runtimeKey());
			sources.add(new SpeakerAudioSource(
					sourceKey,
					sourceKey,
					ringtone,
					0L,
					call.createdAtMillis,
					false,
					false,
					false,
					false,
					true
			));
		}
		return sources.isEmpty() ? List.of() : List.copyOf(sources);
	}

	static boolean hasVisibleCall(ScreenRuntimeKey key) {
		return key != null && currentCall(key) != null;
	}

	static void onDeviceNetworkChanged(MinecraftServer server, ScreenRuntimeKey key) {
		if (server == null || key == null || currentCall(key) == null) {
			return;
		}
		MicrophoneSystem.onMaxCallStateChanged(server);
		requestRuntimeRender(server, key);
		requestPeerRender(server, key);
	}

	static boolean hasCallOverlay(MaxVisualSnapshot snapshot) {
		return snapshot != null && snapshot.call() != null && snapshot.call().phase() != MaxCallPhase.IDLE;
	}

	static boolean beginGalleryFileShare(MinecraftServer server, ScreenComponent component, List<GalleryItem> items) {
		if (server == null || component == null || component.runtimeKey() == null || items == null || items.isEmpty()) {
			return false;
		}
		MaxRuntimeState state = ensureState(server, component);
		if (state == null) {
			return false;
		}
		List<MaxSharedGalleryFile> files = new ArrayList<>();
		for (GalleryItem item : items) {
			MaxSharedGalleryFile file = sharedGalleryFile(item);
			if (file != null) {
				files.add(file);
			}
		}
		synchronized (state) {
			if (files.isEmpty()) {
				state.statusText = "Эти файлы нельзя отправить";
				state.version++;
				requestRuntimeRender(server, component.runtimeKey());
				return false;
			}
			state.fileShareFiles.clear();
			state.fileShareFiles.addAll(files);
			state.fileShareSelectedContacts.clear();
			state.fileSharePickerScroll = 0;
			state.fileSharePickerOpen = true;
			closeNotificationsLocked(state);
			state.avatarPickerOpen = false;
			state.ringtonePickerOpen = false;
			state.ringtonePreviewPlaying = false;
			state.statusText = files.size() == 1 ? "Выбери получателя" : "Выбери получателей";
			state.version++;
		}
		refreshConnectedSpeakersNow(server, component);
		requestRuntimeRender(server, component.runtimeKey());
		return true;
	}

	private static MaxSharedGalleryFile sharedGalleryFile(GalleryItem item) {
		if (item == null || effectiveGalleryItemKind(item) == GalleryItemKind.LIVE_CAMERA) {
			return null;
		}
		String localMediaKey = item.localMediaKey() == null ? "" : item.localMediaKey().trim();
		String url = item.url() == null ? "" : item.url().trim();
		if (url.isBlank() && localMediaKey.isBlank()) {
			return null;
		}
		GalleryItemKind kind = effectiveGalleryItemKind(item);
		String title = item.title() == null || item.title().isBlank() ? defaultSharedFileTitle(kind) : item.title().trim();
		String subtitle = item.subtitle() == null ? "" : item.subtitle().trim();
		if (url.isBlank()) {
			url = MAX_TRANSFER_URL_PREFIX + localMediaKey;
		}
		return new MaxSharedGalleryFile(title, subtitle, url, localMediaKey, kind != null ? kind : GalleryItemKind.MEDIA);
	}

	private static String defaultSharedFileTitle(GalleryItemKind kind) {
		return switch (kind != null ? kind : GalleryItemKind.MEDIA) {
			case AUDIO -> "Аудиофайл";
			case VIDEO -> "Видео";
			case YOUTUBE -> "YouTube";
			case LIVE_CAMERA -> "Камера";
			case MEDIA -> "Файл";
		};
	}

	private static String fileShareTitleLocked(MaxRuntimeState state) {
		if (state == null || state.fileShareFiles.isEmpty()) {
			return "";
		}
		if (state.fileShareFiles.size() == 1) {
			MaxSharedGalleryFile file = state.fileShareFiles.get(0);
			return file != null && file.title() != null ? file.title() : "";
		}
		return "Файлов: " + state.fileShareFiles.size();
	}

	static void drawCallOverlay(Graphics2D graphics, UiLayout layout, ScreenRuntimeKey runtimeKey, MaxVisualSnapshot snapshot) {
		if (!hasCallOverlay(snapshot)) {
			return;
		}
		drawMaxCallScreen(graphics, layout, runtimeKey, snapshot);
	}

	static List<MicrophoneSystem.ScreenMicrophoneCallRoute> collectMicrophoneCallRoutes(MinecraftServer server) {
		if (server == null || CALLS_BY_ID.isEmpty()) {
			return List.of();
		}
		List<MicrophoneSystem.ScreenMicrophoneCallRoute> routes = new ArrayList<>();
		for (MaxCallSession call : CALLS_BY_ID.values()) {
			if (call == null || !call.accepted) {
				continue;
			}
			List<ScreenRuntimeKey> acceptedParticipants = call.acceptedParticipants();
			for (ScreenRuntimeKey sourceKey : acceptedParticipants) {
				ScreenComponent source = resolveScreenComponent(server, sourceKey);
				if (source == null || !source.powered() || !isMicrophoneEnabled(sourceKey)) {
					continue;
				}
				for (ScreenRuntimeKey targetKey : acceptedParticipants) {
					if (Objects.equals(sourceKey, targetKey)) {
						continue;
					}
					ScreenComponent target = resolveScreenComponent(server, targetKey);
					if (target == null || !target.powered()) {
						continue;
					}
					routes.add(new MicrophoneSystem.ScreenMicrophoneCallRoute(
							"max:" + call.id + ":" + componentGroupId(sourceKey) + "-to-" + componentGroupId(targetKey),
							sourceKey,
							targetKey,
							selectedMicrophoneIndex(server, sourceKey)
					));
				}
			}
		}
		return routes.isEmpty() ? List.of() : List.copyOf(routes);
	}

	private static boolean isMicrophoneEnabled(ScreenRuntimeKey key) {
		MaxRuntimeState state = MAX_STATES.get(key);
		if (state == null) {
			return true;
		}
		synchronized (state) {
			return state.microphoneEnabled;
		}
	}

	private static int selectedMicrophoneIndex(MinecraftServer server, ScreenRuntimeKey key) {
		if (server == null || key == null) {
			return -1;
		}
		MaxRuntimeState state = MAX_STATES.get(key);
		if (state == null) {
			return -1;
		}
		List<MicrophoneSystem.ScreenMicrophoneDevice> microphones = MicrophoneSystem.connectedMicrophoneDevices(server, key);
		synchronized (state) {
			return normalizeSelectedMicrophoneLocked(state, microphones);
		}
	}

	static void closeRuntime(MinecraftServer server, ScreenRuntimeKey key) {
		closeRuntime(server, key, false);
	}

	static void deactivateRuntime(MinecraftServer server, ScreenRuntimeKey key) {
		if (key == null) {
			return;
		}
		boolean hadActiveCall = CALL_BY_SCREEN.containsKey(key);
		endCall(server, key);
		RendererBotCameraSystem.stopLiveStream(maxVideoStreamOwnerId(key));
		RendererBotCameraSystem.stopLiveStream(maxLocalVideoStreamOwnerId(key));
		MaxRuntimeState state = MAX_STATES.get(key);
		if (state == null) {
			return;
		}
		boolean changed = false;
		boolean speakerActivity = hadActiveCall;
		synchronized (state) {
			speakerActivity = speakerActivity || state.ringtonePreviewPlaying || state.notificationPreviewPlaying;
			closeNotificationsLocked(state);
			if (state.avatarPickerOpen
					|| state.avatarPickerScroll != 0
					|| state.ringtonePickerOpen
					|| state.ringtonePickerScroll != 0
					|| state.ringtonePreviewPlaying
					|| state.ringtonePreviewStartedAtMillis != 0L
					|| state.callMenuOpen
					|| state.cameraPickerOpen
					|| state.cameraPickerScroll != 0
					|| state.microphonePickerScroll != 0
					|| state.callMiniParticipantScroll != 0
					|| state.callContactPickerOpen
					|| state.callContactPickerScroll != 0
					|| state.callMiniParticipantsHidden
					|| state.focusSelf
					|| state.focusPeer
					|| state.focusedPeerKey != null
					|| state.fileSharePickerOpen
					|| state.fileSharePickerScroll != 0
					|| !state.fileShareFiles.isEmpty()
					|| !state.fileShareSelectedContacts.isEmpty()
					|| state.localFrame != null
					|| !state.localVideoUrl.isBlank()
					|| state.remoteFrame != null
					|| !state.remoteVideoUrl.isBlank()
					|| !state.statusText.isBlank()) {
				changed = true;
			}
			state.avatarPickerOpen = false;
			state.avatarPickerScroll = 0;
			state.ringtonePickerOpen = false;
			state.ringtonePickerScroll = 0;
			state.ringtonePreviewPlaying = false;
			state.ringtonePreviewStartedAtMillis = 0L;
			state.callMenuOpen = false;
			state.cameraPickerOpen = false;
			state.cameraPickerScroll = 0;
			state.microphonePickerScroll = 0;
			state.callMiniParticipantScroll = 0;
			state.callContactPickerOpen = false;
			state.callContactPickerScroll = 0;
			state.callMiniParticipantsHidden = false;
			state.focusSelf = false;
			state.focusPeer = false;
			state.focusedPeerKey = null;
			state.fileSharePickerOpen = false;
			state.fileSharePickerScroll = 0;
			state.fileShareFiles.clear();
			state.fileShareSelectedContacts.clear();
			state.statusText = "";
			state.localFrame = null;
			state.localVideoUrl = "";
			state.remoteFrame = null;
			state.remoteVideoUrl = "";
			if (changed) {
				state.version++;
			}
		}
		if (speakerActivity) {
			refreshConnectedSpeakersNow(server, key);
		}
	}

	static void closeRuntime(MinecraftServer server, ScreenRuntimeKey key, boolean contentDestroyed) {
		if (key == null) {
			return;
		}
		endCall(server, key);
		RendererBotCameraSystem.stopLiveStream(maxVideoStreamOwnerId(key));
		RendererBotCameraSystem.stopLiveStream(maxLocalVideoStreamOwnerId(key));
		MaxRuntimeState removed = MAX_STATES.remove(key);
		String removedAccountCode = "";
		String removedAccountName = "";
		if (removed != null) {
			synchronized (removed) {
				removedAccountCode = normalizeAccountCode(removed.accountCode);
				removedAccountName = removed.accountName == null ? "" : removed.accountName;
			}
		}
		if (!removedAccountCode.isBlank()) {
			ACCOUNT_INDEX.remove(removedAccountCode, key);
		}
		if (!removedAccountName.isBlank()) {
			ACCOUNT_NAME_INDEX.remove(accountNameLookupKey(removedAccountName), key);
		}
		if (contentDestroyed && !removedAccountCode.isBlank()) {
			ACCOUNT_PROFILE_INDEX.remove(removedAccountCode);
		}
		PENDING_CONTACT_CODE_INPUTS.entrySet().removeIf(entry -> key.equals(entry.getValue()));
		PENDING_ACCOUNT_NAME_INPUTS.entrySet().removeIf(entry -> key.equals(entry.getValue()));
		PENDING_CALL_CONTACT_INVITES.entrySet().removeIf(entry -> key.equals(entry.getValue()));
		if (contentDestroyed && !removedAccountCode.isBlank()) {
			removePendingIncomingFilesFromSender(server, removedAccountCode);
		}
	}

	private static void removePendingIncomingFilesFromSender(MinecraftServer server, String senderCode) {
		String normalizedSender = normalizeAccountCode(senderCode);
		if (normalizedSender.isBlank()) {
			return;
		}
		for (Map.Entry<ScreenRuntimeKey, MaxRuntimeState> entry : MAX_STATES.entrySet()) {
			ScreenRuntimeKey recipientKey = entry.getKey();
			MaxRuntimeState recipientState = entry.getValue();
			if (recipientKey == null || recipientState == null) {
				continue;
			}
			List<MaxIncomingFile> removedFiles = new ArrayList<>();
			boolean changed = false;
			synchronized (recipientState) {
				for (int index = 0; index < recipientState.incomingFiles.size(); ) {
					MaxIncomingFile incoming = recipientState.incomingFiles.get(index);
					if (incoming != null && Objects.equals(normalizedSender, normalizeAccountCode(incoming.senderCode()))) {
						removedFiles.add(incoming);
						recipientState.incomingFiles.remove(index);
						changed = true;
						continue;
					}
					index++;
				}
				if (changed) {
					pruneIncomingPreviewCacheLocked(recipientState);
					if (Objects.equals(normalizedSender, normalizeAccountCode(recipientState.notificationContactCode))) {
						closeNotificationsLocked(recipientState);
					}
					recipientState.version++;
				}
			}
			for (MaxIncomingFile removedFile : removedFiles) {
				deleteTransferLocalMediaIfTemporary(removedFile);
			}
			if (changed && server != null) {
				persistState(server, recipientKey, recipientState);
				refreshConnectedSpeakersNow(server, recipientKey);
				requestRuntimeRender(server, recipientKey);
			}
		}
	}

	static void clearRuntime() {
		for (ScreenRuntimeKey key : new ArrayList<>(MAX_STATES.keySet())) {
			RendererBotCameraSystem.stopLiveStream(maxVideoStreamOwnerId(key));
			RendererBotCameraSystem.stopLiveStream(maxLocalVideoStreamOwnerId(key));
		}
		MAX_STATES.clear();
		ACCOUNT_INDEX.clear();
		ACCOUNT_NAME_INDEX.clear();
		ACCOUNT_PROFILE_INDEX.clear();
		PENDING_CONTACT_CODE_INPUTS.clear();
		PENDING_ACCOUNT_NAME_INPUTS.clear();
		PENDING_CALL_CONTACT_INVITES.clear();
		CALLS_BY_ID.clear();
		CALL_BY_SCREEN.clear();
	}

	static void drawMaxScreen(Graphics2D graphics, UiLayout layout, MonitorApp app, ScreenRuntimeKey runtimeKey, MaxVisualSnapshot snapshot) {
		if (graphics == null || layout == null) {
			return;
		}
		MaxVisualSnapshot state = snapshot != null ? snapshot : emptySnapshot();
		UiRect canvas = mediaCanvasRect(layout);
		drawMaxAtmosphere(graphics, canvas, layout);
		drawMediaCloseButton(graphics, mediaCloseRect(layout), layout, MediaButtonSegment.SINGLE);
		if (state.fileSharePickerOpen()) {
			drawMaxFileSharePicker(graphics, layout, runtimeKey, state);
			return;
		}
		if (state.avatarPickerOpen()) {
			drawMaxAvatarPicker(graphics, layout, runtimeKey, state);
			return;
		}
		if (state.ringtonePickerOpen()) {
			drawMaxRingtonePicker(graphics, layout, runtimeKey, state);
			return;
		}
		if (state.call() != null && state.call().phase() != MaxCallPhase.IDLE) {
			drawMaxCallScreen(graphics, layout, runtimeKey, state);
		} else {
			drawMaxFeedScreen(graphics, layout, app, state);
		}
		if (state.notificationsOpen()) {
			drawMaxNotificationsScreen(graphics, layout, runtimeKey, state);
		}
	}

	private static MaxVisualSnapshot emptySnapshot() {
		return new MaxVisualSnapshot(0L, "MAX-000000", "000000", null, WindowedSnapshot.empty(), null, WindowedSnapshot.empty(), 0, WindowedSnapshot.empty(), 0, WindowedSnapshot.empty(), 0, WindowedSnapshot.empty(), 0, 0, 0, 0, "", WindowedSnapshot.empty(), false, false, false, false, false, false, "");
	}

	private static MaxRuntimeState ensureState(MinecraftServer server, ScreenComponent component) {
		if (server == null || component == null || component.runtimeKey() == null) {
			return null;
		}
		MaxRuntimeState state = MAX_STATES.computeIfAbsent(component.runtimeKey(), ignored -> new MaxRuntimeState());
		synchronized (state) {
			if (!state.hydrated) {
				PersistedMaxState persisted = resolvePersistedMaxState(component);
				state.accountCode = persisted != null && !persisted.accountCode().isBlank()
						? normalizeAccountCode(persisted.accountCode())
						: generateAccountCode();
				String hydratedAccountName = persisted != null ? persisted.accountName() : "";
				state.accountName = resolveHydratedAccountName(hydratedAccountName, state.accountCode, component.runtimeKey());
				state.avatarUrl = persisted != null ? persisted.avatarUrl() : "";
				state.avatarLocalMediaKey = persisted != null ? persisted.avatarLocalMediaKey() : "";
				state.selectedCameraUrl = persisted != null ? persisted.selectedCameraUrl() : "";
				state.selectedMicrophoneKey = persisted != null ? persisted.selectedMicrophoneKey() : "";
				state.selectedMicrophoneIndex = persisted != null ? persisted.selectedMicrophoneIndex() : -1;
				state.cameraEnabled = persisted == null || persisted.cameraEnabled();
				state.microphoneEnabled = persisted == null || persisted.microphoneEnabled();
				state.ringtoneUrl = persisted != null ? persisted.ringtoneUrl() : "";
					state.ringtoneLocalMediaKey = persisted != null ? persisted.ringtoneLocalMediaKey() : "";
					state.ringtoneTitle = persisted != null ? persisted.ringtoneTitle() : "";
					state.incomingFiles.clear();
					if (persisted != null) {
						state.incomingFiles.addAll(persisted.incomingFiles());
					}
					pruneIncomingPreviewCacheLocked(state);
					state.contacts.clear();
					if (persisted != null) {
						for (String contact : persisted.contacts()) {
							String normalized = normalizeAccountCode(contact);
							if (!normalized.isBlank() && !Objects.equals(normalized, state.accountCode) && !state.contacts.contains(normalized)) {
								state.contacts.add(normalized);
							}
						}
					}
					ensureIncomingSendersAsContactsLocked(state);
					state.avatarMedia = loadAvatarMedia(component, state.avatarUrl, state.avatarLocalMediaKey);
					state.avatarFrame = avatarFrame(state.avatarMedia, 0, null);
					state.avatarAnimationStartedAtMillis = System.currentTimeMillis();
					state.hydrated = true;
					state.version++;
					ACCOUNT_INDEX.put(state.accountCode, component.runtimeKey());
					ACCOUNT_NAME_INDEX.put(accountNameLookupKey(state.accountName), component.runtimeKey());
					cacheAccountProfile(
							state.accountCode,
							state.accountName,
							state.avatarUrl,
							state.avatarLocalMediaKey,
							currentAvatarFrameLocked(state),
							avatarAnimatedLocked(state)
					);
					persistState(server, component.runtimeKey(), state);
				}
			}
		return state;
	}

	private static PersistedMaxState resolvePersistedMaxState(ScreenComponent component) {
		if (component == null || component.frameCoords().isEmpty()) {
			return null;
		}
		List<Map.Entry<ItemFrame, TileCoord>> frames = new ArrayList<>(component.frameCoords().entrySet());
		frames.sort(Comparator
				.comparingInt((Map.Entry<ItemFrame, TileCoord> entry) -> entry.getValue().y())
				.thenComparingInt(entry -> entry.getValue().x()));
		for (Map.Entry<ItemFrame, TileCoord> entry : frames) {
			PersistedMaxState state = readPersistedMaxState(entry.getKey().getItem());
			if (state != null && !state.accountCode().isBlank()) {
				return state;
			}
		}
		return null;
	}

	private static PersistedMaxState readPersistedMaxState(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return null;
		}
		CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
		if (customData == null) {
			return null;
		}
		CompoundTag root = customData.copyTag();
		if (!root.contains(PERSISTED_MAX_ROOT_TAG)) {
			return null;
		}
		CompoundTag maxTag = root.getCompoundOrEmpty(PERSISTED_MAX_ROOT_TAG);
		String code = normalizeAccountCode(maxTag.getStringOr(MAX_ACCOUNT_CODE_TAG, ""));
		if (code.isBlank()) {
			return null;
		}
		int contactCount = Math.max(0, maxTag.getIntOr(MAX_CONTACT_COUNT_TAG, 0));
		List<String> contacts = new ArrayList<>(contactCount);
		for (int index = 0; index < contactCount; index++) {
			String contact = normalizeAccountCode(maxTag.getStringOr(MAX_CONTACT_PREFIX + index, ""));
			if (!contact.isBlank() && !contacts.contains(contact)) {
				contacts.add(contact);
			}
		}
		int incomingFileCount = Math.max(0, maxTag.getIntOr(MAX_INCOMING_FILE_COUNT_TAG, 0));
		List<MaxIncomingFile> incomingFiles = new ArrayList<>(incomingFileCount);
		for (int index = 0; index < incomingFileCount; index++) {
			CompoundTag fileTag = maxTag.getCompoundOrEmpty(MAX_INCOMING_FILE_PREFIX + index);
			String id = fileTag.getStringOr(MAX_INCOMING_FILE_ID_TAG, "");
			String sender = normalizeAccountCode(fileTag.getStringOr(MAX_INCOMING_FILE_SENDER_TAG, ""));
			String senderName = sanitizeAccountName(fileTag.getStringOr(MAX_INCOMING_FILE_SENDER_NAME_TAG, ""));
			String senderAvatarUrl = fileTag.getStringOr(MAX_INCOMING_FILE_SENDER_AVATAR_URL_TAG, "");
			String senderAvatarLocalMediaKey = fileTag.getStringOr(MAX_INCOMING_FILE_SENDER_AVATAR_LOCAL_MEDIA_TAG, "");
			String title = fileTag.getStringOr(MAX_INCOMING_FILE_TITLE_TAG, "");
			String subtitle = fileTag.getStringOr(MAX_INCOMING_FILE_SUBTITLE_TAG, "");
			String url = fileTag.getStringOr(MAX_INCOMING_FILE_URL_TAG, "");
			String localMediaKey = fileTag.getStringOr(MAX_INCOMING_FILE_LOCAL_MEDIA_TAG, "");
			GalleryItemKind kind = effectiveGalleryItemKind(url, localMediaKey, GalleryItemKind.fromPersisted(fileTag.getStringOr(MAX_INCOMING_FILE_KIND_TAG, ""), url));
			long createdAtMillis = fileTag.getLongOr(MAX_INCOMING_FILE_CREATED_TAG, 0L);
			if (!sender.isBlank() && (!url.isBlank() || !localMediaKey.isBlank())) {
				incomingFiles.add(new MaxIncomingFile(
						id == null || id.isBlank() ? UUID.randomUUID().toString() : id,
						sender,
						senderName,
						senderAvatarUrl,
						senderAvatarLocalMediaKey,
						title,
						subtitle,
						url,
						localMediaKey,
						kind,
						createdAtMillis > 0L ? createdAtMillis : System.currentTimeMillis()
				));
			}
		}
		return new PersistedMaxState(
				code,
				sanitizeAccountName(maxTag.getStringOr(MAX_ACCOUNT_NAME_TAG, "")),
				maxTag.getStringOr(MAX_AVATAR_URL_TAG, ""),
				maxTag.getStringOr(MAX_AVATAR_LOCAL_MEDIA_TAG, ""),
				contacts,
				maxTag.getStringOr(MAX_SELECTED_CAMERA_URL_TAG, ""),
				maxTag.getStringOr(MAX_SELECTED_MICROPHONE_KEY_TAG, ""),
				maxTag.getIntOr(MAX_SELECTED_MICROPHONE_INDEX_TAG, -1),
				maxTag.getBooleanOr(MAX_CAMERA_ENABLED_TAG, true),
				maxTag.getBooleanOr(MAX_MICROPHONE_ENABLED_TAG, true),
				maxTag.getStringOr(MAX_RINGTONE_URL_TAG, ""),
				maxTag.getStringOr(MAX_RINGTONE_LOCAL_MEDIA_TAG, ""),
				maxTag.getStringOr(MAX_RINGTONE_TITLE_TAG, ""),
				List.copyOf(incomingFiles)
		);
	}

	private static void persistState(MinecraftServer server, ScreenRuntimeKey key, MaxRuntimeState state) {
		if (server == null || key == null || state == null) {
			return;
		}
		String accountCode;
		String accountName;
		String avatarUrl;
		String avatarLocalMediaKey;
		BufferedImage avatarFrame;
		boolean avatarAnimated;
		synchronized (state) {
			accountCode = state.accountCode;
			accountName = state.accountName;
			avatarUrl = state.avatarUrl;
			avatarLocalMediaKey = state.avatarLocalMediaKey;
			avatarFrame = currentAvatarFrameLocked(state);
			avatarAnimated = avatarAnimatedLocked(state);
		}
		cacheAccountProfile(accountCode, accountName, avatarUrl, avatarLocalMediaKey, avatarFrame, avatarAnimated);
		ScreenComponent component = resolveScreenComponent(server, key);
		if (component == null) {
			return;
		}
		PersistedMaxState snapshot;
		synchronized (state) {
			snapshot = new PersistedMaxState(
					state.accountCode,
					state.accountName,
					state.avatarUrl,
					state.avatarLocalMediaKey,
					List.copyOf(state.contacts),
					state.selectedCameraUrl,
					state.selectedMicrophoneKey,
					state.selectedMicrophoneIndex,
					state.cameraEnabled,
					state.microphoneEnabled,
					state.ringtoneUrl,
					state.ringtoneLocalMediaKey,
					state.ringtoneTitle,
					List.copyOf(state.incomingFiles)
			);
		}
		for (ItemFrame frame : component.frameCoords().keySet()) {
			if (frame == null || frame.getItem().isEmpty()) {
				continue;
			}
			ItemStack updated = frame.getItem().copy();
			writePersistedMaxState(updated, snapshot);
			frame.setItem(updated, false);
		}
	}

	private static void writePersistedMaxState(ItemStack stack, PersistedMaxState state) {
		if (stack == null || stack.isEmpty() || state == null || state.accountCode().isBlank()) {
			return;
		}
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
			CompoundTag maxTag = new CompoundTag();
			maxTag.putString(MAX_ACCOUNT_CODE_TAG, state.accountCode());
			if (state.accountName() != null && !state.accountName().isBlank()) {
				maxTag.putString(MAX_ACCOUNT_NAME_TAG, state.accountName());
			}
			if (state.avatarUrl() != null && !state.avatarUrl().isBlank()) {
				maxTag.putString(MAX_AVATAR_URL_TAG, state.avatarUrl());
			}
			if (state.avatarLocalMediaKey() != null && !state.avatarLocalMediaKey().isBlank()) {
				maxTag.putString(MAX_AVATAR_LOCAL_MEDIA_TAG, state.avatarLocalMediaKey());
			}
			if (state.selectedCameraUrl() != null && !state.selectedCameraUrl().isBlank()) {
				maxTag.putString(MAX_SELECTED_CAMERA_URL_TAG, state.selectedCameraUrl());
			}
			if (state.selectedMicrophoneKey() != null && !state.selectedMicrophoneKey().isBlank()) {
				maxTag.putString(MAX_SELECTED_MICROPHONE_KEY_TAG, state.selectedMicrophoneKey());
			}
			maxTag.putInt(MAX_SELECTED_MICROPHONE_INDEX_TAG, state.selectedMicrophoneIndex());
			maxTag.putBoolean(MAX_CAMERA_ENABLED_TAG, state.cameraEnabled());
			maxTag.putBoolean(MAX_MICROPHONE_ENABLED_TAG, state.microphoneEnabled());
			if (state.ringtoneUrl() != null && !state.ringtoneUrl().isBlank()) {
				maxTag.putString(MAX_RINGTONE_URL_TAG, state.ringtoneUrl());
			}
			if (state.ringtoneLocalMediaKey() != null && !state.ringtoneLocalMediaKey().isBlank()) {
				maxTag.putString(MAX_RINGTONE_LOCAL_MEDIA_TAG, state.ringtoneLocalMediaKey());
			}
			if (state.ringtoneTitle() != null && !state.ringtoneTitle().isBlank()) {
				maxTag.putString(MAX_RINGTONE_TITLE_TAG, state.ringtoneTitle());
			}
			List<String> contacts = state.contacts() != null ? state.contacts() : List.of();
			maxTag.putInt(MAX_CONTACT_COUNT_TAG, contacts.size());
			for (int index = 0; index < contacts.size(); index++) {
				maxTag.putString(MAX_CONTACT_PREFIX + index, contacts.get(index));
			}
			List<MaxIncomingFile> incomingFiles = state.incomingFiles() != null ? state.incomingFiles() : List.of();
			int persistedIncomingFileCount = 0;
			for (MaxIncomingFile incoming : incomingFiles) {
				if (incoming == null) {
					continue;
				}
				String senderCode = normalizeAccountCode(incoming.senderCode());
				String url = Objects.toString(incoming.url(), "");
				String localMediaKey = Objects.toString(incoming.localMediaKey(), "");
				if (senderCode.isBlank() || url.isBlank() && localMediaKey.isBlank()) {
					continue;
				}
				CompoundTag fileTag = new CompoundTag();
					fileTag.putString(MAX_INCOMING_FILE_ID_TAG, incoming.id() == null || incoming.id().isBlank() ? UUID.randomUUID().toString() : incoming.id());
					fileTag.putString(MAX_INCOMING_FILE_SENDER_TAG, senderCode);
					if (incoming.senderDisplayName() != null && !incoming.senderDisplayName().isBlank()) {
						fileTag.putString(MAX_INCOMING_FILE_SENDER_NAME_TAG, incoming.senderDisplayName());
					}
					if (incoming.senderAvatarUrl() != null && !incoming.senderAvatarUrl().isBlank()) {
						fileTag.putString(MAX_INCOMING_FILE_SENDER_AVATAR_URL_TAG, incoming.senderAvatarUrl());
					}
					if (incoming.senderAvatarLocalMediaKey() != null && !incoming.senderAvatarLocalMediaKey().isBlank()) {
						fileTag.putString(MAX_INCOMING_FILE_SENDER_AVATAR_LOCAL_MEDIA_TAG, incoming.senderAvatarLocalMediaKey());
					}
					fileTag.putString(MAX_INCOMING_FILE_TITLE_TAG, Objects.toString(incoming.title(), ""));
				fileTag.putString(MAX_INCOMING_FILE_SUBTITLE_TAG, Objects.toString(incoming.subtitle(), ""));
				fileTag.putString(MAX_INCOMING_FILE_URL_TAG, url);
				fileTag.putString(MAX_INCOMING_FILE_LOCAL_MEDIA_TAG, localMediaKey);
				fileTag.putString(MAX_INCOMING_FILE_KIND_TAG, (incoming.kind() != null ? incoming.kind() : GalleryItemKind.MEDIA).persistedName());
				fileTag.putLong(MAX_INCOMING_FILE_CREATED_TAG, incoming.createdAtMillis());
				maxTag.put(MAX_INCOMING_FILE_PREFIX + persistedIncomingFileCount, fileTag);
				persistedIncomingFileCount++;
			}
			maxTag.putInt(MAX_INCOMING_FILE_COUNT_TAG, persistedIncomingFileCount);
			tag.put(PERSISTED_MAX_ROOT_TAG, maxTag);
		});
	}

	private static MaxCallVisualSnapshot captureCallSnapshot(
			MinecraftServer server,
			ScreenComponent component,
			MaxRuntimeState state,
			List<LiveCameraReference> cameras
	) {
		MaxCallSession call = currentCall(component.runtimeKey());
		MaxCallPhase phase = callPhase(call, component.runtimeKey());
		if (phase == MaxCallPhase.IDLE) {
			RendererBotCameraSystem.stopLiveStream(maxVideoStreamOwnerId(component.runtimeKey()));
			return null;
		}
		ScreenRuntimeKey peerKey = call.peer(component.runtimeKey());
		MaxRuntimeState peerState = peerKey != null ? MAX_STATES.get(peerKey) : null;
		BufferedImage peerAvatar;
		boolean peerAvatarAnimated;
		String peerCode;
		String peerDisplayName;
		boolean cameraEnabled;
		boolean microphoneEnabled;
		boolean callMenuOpen;
		boolean cameraPickerOpen;
		boolean contactPickerOpen;
		boolean focusSelf;
		boolean focusPeer;
		ScreenRuntimeKey focusedPeerKey;
		boolean miniParticipantsHidden;
		String selectedMicrophoneKey;
		int selectedMicrophoneIndex;
		int cameraScroll;
		int microphoneScroll;
		int contactPickerScroll;
		int miniParticipantScroll;
		BufferedImage remoteFrame;
		synchronized (state) {
			cameraEnabled = state.cameraEnabled;
			microphoneEnabled = state.microphoneEnabled;
			callMenuOpen = state.callMenuOpen;
			cameraPickerOpen = state.cameraPickerOpen;
			contactPickerOpen = state.callContactPickerOpen;
			focusSelf = state.focusSelf;
			focusPeer = state.focusPeer;
			focusedPeerKey = state.focusedPeerKey;
			miniParticipantsHidden = state.callMiniParticipantsHidden;
			selectedMicrophoneKey = state.selectedMicrophoneKey;
			selectedMicrophoneIndex = state.selectedMicrophoneIndex;
			cameraScroll = state.cameraPickerScroll;
			microphoneScroll = state.microphonePickerScroll;
			contactPickerScroll = state.callContactPickerScroll;
			miniParticipantScroll = state.callMiniParticipantScroll;
			remoteFrame = state.remoteFrame;
		}
		if (focusPeer && focusedPeerKey != null && call.isParticipant(focusedPeerKey) && !Objects.equals(focusedPeerKey, component.runtimeKey())) {
			peerKey = focusedPeerKey;
			peerState = MAX_STATES.get(peerKey);
		}
		if (peerState != null) {
			synchronized (peerState) {
				peerAvatar = currentAvatarFrameLocked(peerState);
				peerAvatarAnimated = avatarAnimatedLocked(peerState);
				peerCode = peerState.accountCode;
				peerDisplayName = peerState.accountName;
			}
		} else {
			peerAvatar = null;
			peerAvatarAnimated = false;
			peerCode = call.peerCode(component.runtimeKey());
			peerDisplayName = defaultAccountName(peerCode);
		}
		List<MaxCameraOptionSnapshot> cameraOptions = cameraOptions(server, state, cameras);
		int connectedCameraCount = connectedCameraCount(cameras);
		int connectedDroneCount = connectedDroneCount(cameras);
		int selectedCameraIndex = selectedCameraIndex(state, cameraOptions);
		BufferedImage localPreview = null;
		if (cameraEnabled && selectedCameraIndex >= 0 && selectedCameraIndex < cameraOptions.size()) {
			refreshLocalVideo(server, component, state);
			synchronized (state) {
				localPreview = state.localFrame;
			}
		} else {
			RendererBotCameraSystem.stopLiveStream(maxLocalVideoStreamOwnerId(component.runtimeKey()));
			clearLocalFrame(component.runtimeKey());
		}
		List<MicrophoneSystem.ScreenMicrophoneDevice> microphones = MicrophoneSystem.connectedMicrophoneDevices(server, component.runtimeKey());
		int microphoneCount = microphones.size();
		List<MaxMicrophoneOptionSnapshot> microphoneOptions;
		boolean microphoneSelectionChanged = false;
		synchronized (state) {
			int normalizedMicrophoneIndex = normalizeSelectedMicrophoneLocked(state, microphones);
			microphoneSelectionChanged = normalizedMicrophoneIndex != selectedMicrophoneIndex
					|| !Objects.equals(selectedMicrophoneKey, state.selectedMicrophoneKey);
			selectedMicrophoneIndex = normalizedMicrophoneIndex;
			UiLayout pickerLayout = createUiLayout(component.width(), component.height());
			normalizeCallDevicePickerScrollLocked(state, pickerLayout, cameraOptions.size(), microphoneCount);
			cameraScroll = state.cameraPickerScroll;
			microphoneScroll = state.microphonePickerScroll;
			microphoneOptions = microphoneOptionsLocked(state, microphones);
		}
		if (microphoneSelectionChanged) {
			persistState(server, component.runtimeKey(), state);
		}
		if (phase == MaxCallPhase.ACTIVE && peerState != null) {
			refreshRemoteVideo(server, component, peerState);
		} else {
			RendererBotCameraSystem.stopLiveStream(maxVideoStreamOwnerId(component.runtimeKey()));
		}
		List<MaxCallParticipantSnapshot> participants = captureCallParticipants(component.runtimeKey(), state, call, peerKey, localPreview, remoteFrame);
		UiLayout callLayout = createUiLayout(component.width(), component.height());
		synchronized (state) {
			state.callMiniParticipantScroll = clampInt(state.callMiniParticipantScroll, 0, maxCallMiniParticipantScroll(callLayout, Math.max(0, participants.size() - 1)));
			miniParticipantScroll = state.callMiniParticipantScroll;
		}
		String focusedParticipantCode = focusSelf ? state.accountCode : focusPeer ? peerCode : "";
		String status = switch (phase) {
			case INCOMING -> "Входящий вызов";
			case OUTGOING -> "Ожидание ответа";
			case ACTIVE -> "Защищённый видеоканал";
			case IDLE -> "";
		};
		return new MaxCallVisualSnapshot(
				phase,
				peerCode,
				peerDisplayName,
				peerAvatar,
				peerAvatarAnimated,
				localPreview,
				remoteFrame,
				participants,
				focusedParticipantCode,
				status,
				cameraEnabled,
				microphoneEnabled,
				cameraOptions,
				microphoneOptions,
				connectedCameraCount,
				connectedDroneCount,
				selectedCameraIndex,
				microphoneCount,
				selectedMicrophoneIndex,
				cameraScroll,
				microphoneScroll,
				contactPickerScroll,
				miniParticipantScroll,
				callMenuOpen,
				cameraPickerOpen,
				contactPickerOpen,
				focusSelf,
				focusPeer,
				miniParticipantsHidden,
				phase == MaxCallPhase.ACTIVE ? Math.max(0L, System.currentTimeMillis() - call.acceptedAtMillis) : 0L
		);
	}

	private static List<MaxCallParticipantSnapshot> captureCallParticipants(
			ScreenRuntimeKey selfKey,
			MaxRuntimeState selfState,
			MaxCallSession call,
			ScreenRuntimeKey videoPeerKey,
			BufferedImage localPreview,
			BufferedImage remoteFrame
	) {
		if (selfKey == null || selfState == null || call == null) {
			return List.of();
		}
		List<ScreenRuntimeKey> participantKeys = call.participants();
		if (participantKeys.isEmpty()) {
			return List.of();
		}
		List<MaxCallParticipantSnapshot> participants = new ArrayList<>(participantKeys.size());
		for (ScreenRuntimeKey participantKey : participantKeys) {
			if (participantKey == null) {
				continue;
			}
			boolean self = Objects.equals(participantKey, selfKey);
			MaxRuntimeState participantState = self ? selfState : MAX_STATES.get(participantKey);
			String code = call.participantCode(participantKey);
			String displayName = defaultAccountName(code);
			BufferedImage avatar = null;
			Color accent = participantAccent(code, null);
			boolean avatarAnimated = false;
			BufferedImage video = null;
			boolean cameraEnabled = false;
			boolean microphoneEnabled = true;
			if (participantState != null) {
				synchronized (participantState) {
					code = participantState.accountCode == null || participantState.accountCode.isBlank() ? code : participantState.accountCode;
					displayName = participantState.accountName == null || participantState.accountName.isBlank() ? defaultAccountName(code) : participantState.accountName;
					avatar = currentAvatarFrameLocked(participantState);
					accent = stableAvatarAccentLocked(participantState, code);
					avatarAnimated = avatarAnimatedLocked(participantState);
					cameraEnabled = participantState.cameraEnabled;
					microphoneEnabled = participantState.microphoneEnabled;
				}
			}
			if (self) {
				video = localPreview;
			} else if (Objects.equals(participantKey, videoPeerKey)) {
				video = remoteFrame;
			}
			boolean savedContact;
			synchronized (selfState) {
				savedContact = self || selfState.contacts.contains(normalizeAccountCode(code));
			}
			participants.add(new MaxCallParticipantSnapshot(
					code,
					displayName,
					avatar,
					accent,
					avatarAnimated,
					video,
					self,
					cameraEnabled && (self || video != null),
					microphoneEnabled,
					savedContact,
					call.isRinging(participantKey)
			));
		}
		return participants.isEmpty() ? List.of() : List.copyOf(participants);
	}

	private static List<MaxContactSnapshot> captureContactSnapshots(MinecraftServer server, MaxRuntimeState state, ScreenRuntimeKey selfKey) {
		List<String> contacts;
		Map<String, Integer> notificationCounts;
		Map<String, MaxIncomingFile> incomingProfiles;
		synchronized (state) {
			contacts = List.copyOf(state.contacts);
			notificationCounts = incomingNotificationCountsBySenderLocked(state);
			incomingProfiles = incomingProfileBySenderLocked(state);
		}
		if (contacts.isEmpty()) {
			return List.of();
		}
		List<MaxContactSnapshot> snapshots = new ArrayList<>(contacts.size());
		for (String contact : contacts) {
			MaxContactSnapshot snapshot = captureContactSnapshot(server, selfKey, contact, notificationCounts, incomingProfiles);
			if (snapshot != null) {
				snapshots.add(snapshot);
			}
		}
		return snapshots;
	}

	private static MaxContactSnapshot captureContactSnapshot(
			MinecraftServer server,
			ScreenRuntimeKey selfKey,
			String contact,
			Map<String, Integer> notificationCounts,
			Map<String, MaxIncomingFile> incomingProfiles
	) {
		String contactCode = normalizeAccountCode(contact);
		if (contactCode.isBlank()) {
			return null;
		}
		ScreenRuntimeKey key = ACCOUNT_INDEX.get(contactCode);
		MaxRuntimeState peerState = key != null ? MAX_STATES.get(key) : null;
		ScreenComponent component = key != null ? resolveScreenComponent(server, key) : null;
		MaxCallSession call = key != null ? currentCall(key) : null;
		String displayName = defaultAccountName(contactCode);
		BufferedImage avatar = null;
		boolean avatarAnimated = false;
		String avatarUrl = "";
		String avatarLocalMediaKey = "";
		if (peerState != null) {
			synchronized (peerState) {
				displayName = peerState.accountName == null || peerState.accountName.isBlank() ? defaultAccountName(contactCode) : peerState.accountName;
				avatar = currentAvatarFrameLocked(peerState);
				avatarAnimated = avatarAnimatedLocked(peerState);
				avatarUrl = peerState.avatarUrl == null ? "" : peerState.avatarUrl;
				avatarLocalMediaKey = peerState.avatarLocalMediaKey == null ? "" : peerState.avatarLocalMediaKey;
			}
			cacheAccountProfile(contactCode, displayName, avatarUrl, avatarLocalMediaKey, avatar, avatarAnimated);
		} else {
			MaxStoredAccountProfile storedProfile = resolveStoredAccountProfile(server, contactCode);
			if (storedProfile != null) {
				if (storedProfile.displayName() != null && !storedProfile.displayName().isBlank()) {
					displayName = storedProfile.displayName();
				}
				avatar = storedProfile.avatarFrame();
				avatarAnimated = storedProfile.avatarAnimated();
			} else {
				MaxIncomingFile incomingProfile = incomingProfiles.get(contactCode);
				if (incomingProfile != null) {
					if (incomingProfile.senderDisplayName() != null && !incomingProfile.senderDisplayName().isBlank()) {
						displayName = incomingProfile.senderDisplayName();
					}
					MonitorMediaApp.LoadedMedia avatarMedia = loadAvatarMedia(null, incomingProfile.senderAvatarUrl(), incomingProfile.senderAvatarLocalMediaKey());
					avatar = avatarFrame(avatarMedia, 0, null);
					avatarAnimated = avatarMedia != null && avatarMedia.frameCount() > 1;
				}
			}
		}
		boolean sameCall = call != null && call.isParticipant(selfKey);
		return new MaxContactSnapshot(
				contactCode,
				displayName,
				avatar,
				avatarAnimated,
				component != null && component.powered(),
				sameCall && call.isRinging(key),
				sameCall && call.isAccepted(key),
				notificationCounts != null ? notificationCounts.getOrDefault(contactCode, 0) : 0,
				true
		);
	}

	private static Map<String, Integer> incomingNotificationCountsBySenderLocked(MaxRuntimeState state) {
		if (state == null || state.incomingFiles.isEmpty()) {
			return Map.of();
		}
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (MaxIncomingFile incoming : state.incomingFiles) {
			String senderCode = incoming == null ? "" : normalizeAccountCode(incoming.senderCode());
			if (senderCode.isBlank()) {
				continue;
			}
			counts.merge(senderCode, 1, Integer::sum);
		}
		return counts.isEmpty() ? Map.of() : Map.copyOf(counts);
	}

	private static Map<String, MaxIncomingFile> incomingProfileBySenderLocked(MaxRuntimeState state) {
		if (state == null || state.incomingFiles.isEmpty()) {
			return Map.of();
		}
		Map<String, MaxIncomingFile> profiles = new LinkedHashMap<>();
		for (MaxIncomingFile incoming : state.incomingFiles) {
			String senderCode = incoming == null ? "" : normalizeAccountCode(incoming.senderCode());
			if (senderCode.isBlank()) {
				continue;
			}
			profiles.putIfAbsent(senderCode, incoming);
		}
		return profiles.isEmpty() ? Map.of() : Map.copyOf(profiles);
	}

	private static int incomingCountForSenderLocked(MaxRuntimeState state, String senderCode) {
		if (state == null || state.incomingFiles.isEmpty()) {
			return 0;
		}
		String normalizedSender = normalizeAccountCode(senderCode);
		if (normalizedSender.isBlank()) {
			return 0;
		}
		int count = 0;
		for (MaxIncomingFile incoming : state.incomingFiles) {
			if (incoming != null && Objects.equals(normalizedSender, normalizeAccountCode(incoming.senderCode()))) {
				count++;
			}
		}
		return count;
	}

	private static void cacheAccountProfile(
			String accountCode,
			String displayName,
			String avatarUrl,
			String avatarLocalMediaKey,
			BufferedImage avatarFrame,
			boolean avatarAnimated
	) {
		String normalizedCode = normalizeAccountCode(accountCode);
		if (normalizedCode.isBlank()) {
			return;
		}
		String normalizedName = displayName == null || displayName.isBlank() ? defaultAccountName(normalizedCode) : displayName;
		ACCOUNT_PROFILE_INDEX.put(
				normalizedCode,
				new MaxStoredAccountProfile(
						normalizedCode,
						normalizedName,
						avatarUrl == null ? "" : avatarUrl,
						avatarLocalMediaKey == null ? "" : avatarLocalMediaKey,
						avatarFrame,
						avatarAnimated
				)
		);
	}

	private static MaxStoredAccountProfile resolveStoredAccountProfile(MinecraftServer server, String accountCode) {
		String normalizedCode = normalizeAccountCode(accountCode);
		if (normalizedCode.isBlank()) {
			return null;
		}
		MaxStoredAccountProfile cached = ACCOUNT_PROFILE_INDEX.get(normalizedCode);
		if (cached != null && cached.hasVisualIdentity()) {
			return cached;
		}
		if (server == null) {
			return cached;
		}
		for (ServerLevel level : server.getAllLevels()) {
			for (ScreenComponent component : cachedComponents(level)) {
				if (component == null) {
					continue;
				}
				PersistedMaxState persisted = resolvePersistedMaxState(component);
				if (persisted == null || !Objects.equals(normalizedCode, normalizeAccountCode(persisted.accountCode()))) {
					continue;
				}
				MonitorMediaApp.LoadedMedia avatarMedia = loadAvatarMedia(component, persisted.avatarUrl(), persisted.avatarLocalMediaKey());
				MaxStoredAccountProfile resolved = new MaxStoredAccountProfile(
						normalizedCode,
						persisted.accountName() == null || persisted.accountName().isBlank() ? defaultAccountName(normalizedCode) : persisted.accountName(),
						persisted.avatarUrl() == null ? "" : persisted.avatarUrl(),
						persisted.avatarLocalMediaKey() == null ? "" : persisted.avatarLocalMediaKey(),
						avatarFrame(avatarMedia, 0, null),
						avatarMedia != null && avatarMedia.frameCount() > 1
				);
				ACCOUNT_PROFILE_INDEX.put(normalizedCode, resolved);
				return resolved;
			}
		}
		return cached;
	}

	private static List<MaxFileShareContactSnapshot> fileShareContacts(MinecraftServer server, MaxRuntimeState state, ScreenRuntimeKey selfKey) {
		List<MaxContactSnapshot> contacts = captureContactSnapshots(server, state, selfKey);
		if (contacts.isEmpty()) {
			return List.of();
		}
		Set<String> selected = new HashSet<>(state.fileShareSelectedContacts);
		List<MaxFileShareContactSnapshot> snapshots = new ArrayList<>(contacts.size());
		for (MaxContactSnapshot contact : contacts) {
			if (contact == null) {
				continue;
			}
			snapshots.add(new MaxFileShareContactSnapshot(
					contact.code(),
					contact.displayName(),
					contact.avatarFrame(),
					contact.online(),
					selected.contains(contact.code())
			));
		}
		return snapshots.isEmpty() ? List.of() : List.copyOf(snapshots);
	}

	private static List<MaxIncomingFileSnapshot> incomingFileSnapshots(
			MinecraftServer server,
			ScreenRuntimeKey key,
			MaxRuntimeState state,
			String activeContactCode,
			List<MaxIncomingFile> incomingFiles,
			MaxNotificationPreviewVisualState previewState
	) {
		if (incomingFiles == null || incomingFiles.isEmpty()) {
			return List.of();
		}
		String activeSender = normalizeAccountCode(activeContactCode);
		List<MaxIncomingFileSnapshot> snapshots = new ArrayList<>(incomingFiles.size());
		for (MaxIncomingFile incoming : incomingFiles) {
			if (incoming == null) {
				continue;
			}
			if (!activeSender.isBlank() && !Objects.equals(activeSender, normalizeAccountCode(incoming.senderCode()))) {
				continue;
			}
			MaxIncomingFileSnapshot snapshot = incomingFileSnapshot(server, key, state, incoming, previewState);
			if (snapshot != null) {
				snapshots.add(snapshot);
			}
		}
		return snapshots.isEmpty() ? List.of() : List.copyOf(snapshots);
	}

	private static MaxIncomingFileSnapshot incomingFileSnapshot(
			MinecraftServer server,
			ScreenRuntimeKey key,
			MaxRuntimeState state,
			MaxIncomingFile incoming,
			MaxNotificationPreviewVisualState previewState
	) {
		if (incoming == null) {
			return null;
		}
		String id = incoming.id() == null || incoming.id().isBlank() ? "" : incoming.id();
		String senderCode = normalizeAccountCode(incoming.senderCode());
		ScreenRuntimeKey senderKey = senderCode.isBlank() ? null : ACCOUNT_INDEX.get(senderCode);
		MaxRuntimeState senderState = senderKey != null ? MAX_STATES.get(senderKey) : null;
		String senderDisplayName = defaultAccountName(senderCode);
		BufferedImage senderAvatar = null;
		boolean senderAvatarAnimated = false;
		if (senderState != null) {
			String avatarUrl;
			String avatarLocalMediaKey;
			synchronized (senderState) {
				senderDisplayName = senderState.accountName == null || senderState.accountName.isBlank() ? defaultAccountName(senderCode) : senderState.accountName;
				senderAvatar = currentAvatarFrameLocked(senderState);
				senderAvatarAnimated = avatarAnimatedLocked(senderState);
				avatarUrl = senderState.avatarUrl == null ? "" : senderState.avatarUrl;
				avatarLocalMediaKey = senderState.avatarLocalMediaKey == null ? "" : senderState.avatarLocalMediaKey;
			}
			cacheAccountProfile(senderCode, senderDisplayName, avatarUrl, avatarLocalMediaKey, senderAvatar, senderAvatarAnimated);
		} else {
			MaxStoredAccountProfile storedProfile = resolveStoredAccountProfile(server, senderCode);
			if (storedProfile != null) {
				senderDisplayName = storedProfile.displayName();
				senderAvatar = storedProfile.avatarFrame();
				senderAvatarAnimated = storedProfile.avatarAnimated();
			} else {
				if (incoming.senderDisplayName() != null && !incoming.senderDisplayName().isBlank()) {
					senderDisplayName = incoming.senderDisplayName();
				}
				MonitorMediaApp.LoadedMedia avatarMedia = loadAvatarMedia(null, incoming.senderAvatarUrl(), incoming.senderAvatarLocalMediaKey());
				senderAvatar = avatarFrame(avatarMedia, 0, null);
				senderAvatarAnimated = avatarMedia != null && avatarMedia.frameCount() > 1;
				cacheAccountProfile(senderCode, senderDisplayName, incoming.senderAvatarUrl(), incoming.senderAvatarLocalMediaKey(), senderAvatar, senderAvatarAnimated);
			}
		}
		GalleryItemKind kind = incoming.kind() != null ? incoming.kind() : GalleryItemKind.MEDIA;
		boolean previewPlayable = notificationPreviewPlayable(kind, incoming);
		boolean previewActive = previewState != null && !id.isBlank() && Objects.equals(id, previewState.fileId());
		BufferedImage previewFrame = previewActive ? previewState.frame() : null;
		if (previewFrame == null) {
			previewFrame = cachedIncomingFilePreview(state, id);
		}
		if (previewFrame == null) {
			ensureIncomingFilePreviewAsync(server, key, state, incoming);
		}
		return new MaxIncomingFileSnapshot(
				id,
				senderCode,
				senderDisplayName,
				senderAvatar,
				senderAvatarAnimated,
				incoming.title() == null || incoming.title().isBlank() ? defaultSharedFileTitle(kind) : incoming.title(),
				incoming.subtitle() == null ? "" : incoming.subtitle(),
				kind,
				previewFrame,
				kind == GalleryItemKind.AUDIO,
				previewPlayable,
				previewActive,
				previewActive && previewState != null && previewState.playing(),
				previewActive && previewState != null && previewState.loading()
		);
	}

	private static int rowSnapshotWindowStartIndex(
			ScreenRuntimeKey runtimeKey,
			MonitorScrollAnimationSystem.ScrollChannel channel,
			int scroll,
			int maxScroll,
			int totalCount
	) {
		if (totalCount <= 0) {
			return 0;
		}
		MonitorScrollAnimationSystem.ScrollVisualState visualScroll = MonitorScrollAnimationSystem.sample(runtimeKey, channel, scroll, maxScroll);
		return clampInt(Math.min(scroll, visualScroll.anchorIndex()) - 1, 0, totalCount);
	}

	private static int rowSnapshotWindowEndExclusive(
			ScreenRuntimeKey runtimeKey,
			MonitorScrollAnimationSystem.ScrollChannel channel,
			int scroll,
			int maxScroll,
			int visibleCount,
			int totalCount,
			int startIndex
	) {
		if (totalCount <= 0) {
			return 0;
		}
		MonitorScrollAnimationSystem.ScrollVisualState visualScroll = MonitorScrollAnimationSystem.sample(runtimeKey, channel, scroll, maxScroll);
		int endExclusive = Math.max(scroll, visualScroll.anchorIndex()) + visibleCount + (visualScroll.animated() ? 1 : 0) + 1;
		return clampInt(Math.max(startIndex, endExclusive), 0, totalCount);
	}

	private static int gridSnapshotWindowStartIndex(
			ScreenRuntimeKey runtimeKey,
			MonitorScrollAnimationSystem.ScrollChannel channel,
			int scroll,
			int maxScroll,
			int columns,
			int totalCount
	) {
		if (totalCount <= 0) {
			return 0;
		}
		int safeColumns = Math.max(1, columns);
		MonitorScrollAnimationSystem.ScrollVisualState visualScroll = MonitorScrollAnimationSystem.sample(runtimeKey, channel, scroll, maxScroll);
		double displayRow = visualScroll.displayValue() / safeColumns;
		int anchorRow = clampInt((int) Math.floor(displayRow + 1.0E-6D), 0, Math.max(0, (int) Math.ceil(totalCount / (double) safeColumns)));
		return clampInt((Math.min(scroll / safeColumns, anchorRow) - 1) * safeColumns, 0, totalCount);
	}

	private static int gridSnapshotWindowEndExclusive(
			ScreenRuntimeKey runtimeKey,
			MonitorScrollAnimationSystem.ScrollChannel channel,
			int scroll,
			int maxScroll,
			int columns,
			int visibleRows,
			int totalCount,
			int startIndex
	) {
		if (totalCount <= 0) {
			return 0;
		}
		int safeColumns = Math.max(1, columns);
		MonitorScrollAnimationSystem.ScrollVisualState visualScroll = MonitorScrollAnimationSystem.sample(runtimeKey, channel, scroll, maxScroll);
		double displayRow = visualScroll.displayValue() / safeColumns;
		int anchorRow = clampInt((int) Math.floor(displayRow + 1.0E-6D), 0, Math.max(0, (int) Math.ceil(totalCount / (double) safeColumns)));
		double rowFraction = clampDouble(displayRow - anchorRow, 0.0D, 0.999999D);
		int endRowExclusive = Math.max(scroll / safeColumns, anchorRow) + visibleRows + (rowFraction > 1.0E-4D ? 1 : 0) + 1;
		return clampInt(Math.max(startIndex, endRowExclusive * safeColumns), 0, totalCount);
	}

	private static int pixelSnapshotWindowStartIndex(
			ScreenRuntimeKey runtimeKey,
			MonitorScrollAnimationSystem.ScrollChannel channel,
			int scroll,
			int maxScroll,
			int stride,
			int totalCount
	) {
		if (totalCount <= 0 || stride <= 0) {
			return 0;
		}
		MonitorScrollAnimationSystem.ScrollVisualState visualScroll = MonitorScrollAnimationSystem.sample(runtimeKey, channel, scroll, maxScroll);
		int minOffset = (int) Math.floor(Math.min(scroll, visualScroll.displayValue()));
		return clampInt(minOffset / stride - 1, 0, totalCount);
	}

	private static int pixelSnapshotWindowEndExclusive(
			ScreenRuntimeKey runtimeKey,
			MonitorScrollAnimationSystem.ScrollChannel channel,
			int scroll,
			int maxScroll,
			int stride,
			int viewportHeight,
			int totalCount,
			int startIndex
	) {
		if (totalCount <= 0 || stride <= 0) {
			return 0;
		}
		MonitorScrollAnimationSystem.ScrollVisualState visualScroll = MonitorScrollAnimationSystem.sample(runtimeKey, channel, scroll, maxScroll);
		int maxOffset = (int) Math.ceil(Math.max(scroll, visualScroll.displayValue())) + Math.max(0, viewportHeight);
		int endExclusive = (int) Math.ceil(maxOffset / (double) stride) + 1;
		return clampInt(Math.max(startIndex, endExclusive), 0, totalCount);
	}

	private static WindowedSnapshot<MaxContactSnapshot> contactFeedSnapshots(
			MinecraftServer server,
			MaxRuntimeState state,
			ScreenRuntimeKey selfKey,
			UiLayout layout
	) {
		if (state == null || layout == null) {
			return WindowedSnapshot.empty();
		}
		List<String> contacts;
		Map<String, Integer> notificationCounts;
		Map<String, MaxIncomingFile> incomingProfiles;
		synchronized (state) {
			contacts = List.copyOf(state.contacts);
			notificationCounts = incomingNotificationCountsBySenderLocked(state);
			incomingProfiles = incomingProfileBySenderLocked(state);
		}
		if (contacts.isEmpty()) {
			synchronized (state) {
				state.contactScroll = 0;
			}
			return WindowedSnapshot.empty();
		}
		int capacity = maxVisibleContactRows(layout);
		int startIndex;
		synchronized (state) {
			state.contactScroll = clampInt(state.contactScroll, 0, Math.max(0, contacts.size() - capacity));
			startIndex = state.contactScroll;
		}
		int endExclusive = Math.min(contacts.size(), startIndex + capacity);
		List<MaxContactSnapshot> snapshots = new ArrayList<>(Math.max(0, endExclusive - startIndex));
		for (int index = startIndex; index < endExclusive; index++) {
			MaxContactSnapshot snapshot = captureContactSnapshot(server, selfKey, contacts.get(index), notificationCounts, incomingProfiles);
			if (snapshot != null) {
				snapshots.add(snapshot);
			}
		}
		return new WindowedSnapshot<>(List.copyOf(snapshots), contacts.size(), startIndex);
	}

	private static WindowedSnapshot<MaxAvatarCandidateSnapshot> avatarCandidateSnapshots(
			ScreenComponent component,
			MaxRuntimeState state,
			UiLayout layout,
			ScreenRuntimeKey runtimeKey
	) {
		if (component == null || state == null || layout == null) {
			return WindowedSnapshot.empty();
		}
		List<PersistedGalleryItem> eligible = new ArrayList<>();
		for (PersistedGalleryItem item : resolvePersistedGalleryState(component)) {
			if (item == null || effectiveGalleryItemKind(item) != GalleryItemKind.MEDIA || item.url() == null || item.url().isBlank()) {
				continue;
			}
			eligible.add(item);
		}
		if (eligible.isEmpty()) {
			return WindowedSnapshot.empty();
		}
		int totalCount = eligible.size();
		int scroll;
		synchronized (state) {
			scroll = clampInt(state.avatarPickerScroll, 0, maxAvatarPickerScroll(layout, totalCount));
		}
		int startIndex = gridSnapshotWindowStartIndex(runtimeKey, MonitorScrollAnimationSystem.ScrollChannel.MAX_AVATAR_PICKER, scroll, maxAvatarPickerScroll(layout, totalCount), maxAvatarPickerColumns(layout), totalCount);
		int endExclusive = gridSnapshotWindowEndExclusive(runtimeKey, MonitorScrollAnimationSystem.ScrollChannel.MAX_AVATAR_PICKER, scroll, maxAvatarPickerScroll(layout, totalCount), maxAvatarPickerColumns(layout), maxAvatarPickerVisibleRows(layout), totalCount, startIndex);
		List<MaxAvatarCandidateSnapshot> snapshots = new ArrayList<>(Math.max(0, endExclusive - startIndex));
		for (int index = startIndex; index < endExclusive; index++) {
			PersistedGalleryItem item = eligible.get(index);
			BufferedImage preview = persistedGalleryPreviewForDisplay(item, GalleryItemKind.MEDIA);
			snapshots.add(new MaxAvatarCandidateSnapshot(
					item.title() == null || item.title().isBlank() ? "Аватар" : item.title(),
					item.url(),
					item.localMediaKey(),
					preview
			));
		}
		return new WindowedSnapshot<>(List.copyOf(snapshots), totalCount, startIndex);
	}

	private static WindowedSnapshot<MaxRingtoneCandidateSnapshot> ringtoneCandidateSnapshots(
			ScreenComponent component,
			MaxRuntimeState state,
			UiLayout layout,
			ScreenRuntimeKey runtimeKey
	) {
		if (component == null || state == null || layout == null) {
			return WindowedSnapshot.empty();
		}
		List<PersistedGalleryItem> audioItems = new ArrayList<>();
		for (PersistedGalleryItem item : resolvePersistedGalleryState(component)) {
			if (item != null && effectiveGalleryItemKind(item) == GalleryItemKind.AUDIO) {
				audioItems.add(item);
			}
		}
		int totalCount = 1 + audioItems.size();
		int scroll;
		synchronized (state) {
			scroll = clampInt(state.ringtonePickerScroll, 0, maxRingtonePickerScroll(layout, totalCount));
		}
		int maxScroll = maxRingtonePickerScroll(layout, totalCount);
		int startIndex = rowSnapshotWindowStartIndex(runtimeKey, MonitorScrollAnimationSystem.ScrollChannel.MAX_RINGTONE_PICKER, scroll, maxScroll, totalCount);
		int endExclusive = rowSnapshotWindowEndExclusive(runtimeKey, MonitorScrollAnimationSystem.ScrollChannel.MAX_RINGTONE_PICKER, scroll, maxScroll, maxRingtonePickerCapacity(layout), totalCount, startIndex);
		List<MaxRingtoneCandidateSnapshot> snapshots = new ArrayList<>(Math.max(0, endExclusive - startIndex));
		for (int index = startIndex; index < endExclusive; index++) {
			if (index == 0) {
				snapshots.add(ringtoneCandidate(state, "Стандартный рингтон", "MAX", MAX_DEFAULT_RINGTONE_URL, ""));
				continue;
			}
			PersistedGalleryItem item = audioItems.get(index - 1);
			String title = item.title() == null || item.title().isBlank() ? "Аудиотрек" : item.title();
			String subtitle = item.subtitle() == null || item.subtitle().isBlank() ? "Галерея" : item.subtitle();
			String url = item.url() == null || item.url().isBlank() ? "max:gallery:" + Objects.toString(item.localMediaKey(), title) : item.url();
			snapshots.add(ringtoneCandidate(state, title, subtitle, url, item.localMediaKey()));
		}
		return new WindowedSnapshot<>(List.copyOf(snapshots), totalCount, startIndex);
	}

	private static WindowedSnapshot<MaxFileShareContactSnapshot> fileShareContactSnapshots(
			MinecraftServer server,
			MaxRuntimeState state,
			ScreenRuntimeKey selfKey,
			UiLayout layout,
			ScreenRuntimeKey runtimeKey
	) {
		if (state == null || layout == null) {
			return WindowedSnapshot.empty();
		}
		List<String> contacts;
		Map<String, Integer> notificationCounts;
		Map<String, MaxIncomingFile> incomingProfiles;
		Set<String> selected;
		int scroll;
		synchronized (state) {
			contacts = List.copyOf(state.contacts);
			notificationCounts = incomingNotificationCountsBySenderLocked(state);
			incomingProfiles = incomingProfileBySenderLocked(state);
			selected = Set.copyOf(state.fileShareSelectedContacts);
			scroll = clampInt(state.fileSharePickerScroll, 0, maxContactPickerScroll(layout, contacts.size()));
		}
		if (contacts.isEmpty()) {
			return WindowedSnapshot.empty();
		}
		int maxScroll = maxContactPickerScroll(layout, contacts.size());
		int startIndex = rowSnapshotWindowStartIndex(runtimeKey, MonitorScrollAnimationSystem.ScrollChannel.MAX_FILE_SHARE_PICKER, scroll, maxScroll, contacts.size());
		int endExclusive = rowSnapshotWindowEndExclusive(runtimeKey, MonitorScrollAnimationSystem.ScrollChannel.MAX_FILE_SHARE_PICKER, scroll, maxScroll, maxContactPickerCapacity(layout), contacts.size(), startIndex);
		List<MaxFileShareContactSnapshot> snapshots = new ArrayList<>(Math.max(0, endExclusive - startIndex));
		for (int index = startIndex; index < endExclusive; index++) {
			MaxContactSnapshot contact = captureContactSnapshot(server, selfKey, contacts.get(index), notificationCounts, incomingProfiles);
			if (contact == null) {
				continue;
			}
			snapshots.add(new MaxFileShareContactSnapshot(
					contact.code(),
					contact.displayName(),
					contact.avatarFrame(),
					contact.online(),
					selected.contains(contact.code())
			));
		}
		return new WindowedSnapshot<>(List.copyOf(snapshots), contacts.size(), startIndex);
	}

	private static WindowedSnapshot<MaxContactSnapshot> callContactCandidateSnapshots(
			MinecraftServer server,
			MaxRuntimeState state,
			MaxCallSession call,
			ScreenRuntimeKey selfKey,
			UiLayout layout,
			ScreenRuntimeKey runtimeKey
	) {
		if (state == null || call == null || layout == null) {
			return WindowedSnapshot.empty();
		}
		List<String> contacts;
		Map<String, Integer> notificationCounts;
		Map<String, MaxIncomingFile> incomingProfiles;
		int scroll;
		synchronized (state) {
			contacts = List.copyOf(state.contacts);
			notificationCounts = incomingNotificationCountsBySenderLocked(state);
			incomingProfiles = incomingProfileBySenderLocked(state);
			scroll = clampInt(state.callContactPickerScroll, 0, maxCallContactPickerScroll(layout, contacts.size()));
		}
		if (contacts.isEmpty()) {
			return WindowedSnapshot.empty();
		}
		String peerCode = call.peerCode(selfKey);
		List<String> candidates = new ArrayList<>(contacts.size());
		for (String contact : contacts) {
			String contactCode = normalizeAccountCode(contact);
			if (contactCode.isBlank()) {
				continue;
			}
			ScreenRuntimeKey key = ACCOUNT_INDEX.get(contactCode);
			MaxCallSession current = key != null ? currentCall(key) : null;
			boolean active = current != null && current.isParticipant(selfKey) && current.isAccepted(key);
			boolean ringing = current != null && current.isParticipant(selfKey) && current.isRinging(key);
			if (active || ringing || Objects.equals(contactCode, peerCode) || (key != null && call.isParticipant(key))) {
				continue;
			}
			candidates.add(contact);
		}
		int totalCount = candidates.size();
		if (totalCount <= 0) {
			return WindowedSnapshot.empty();
		}
		int maxScroll = maxCallContactPickerScroll(layout, totalCount);
		scroll = clampInt(scroll, 0, maxScroll);
		int startIndex = rowSnapshotWindowStartIndex(runtimeKey, MonitorScrollAnimationSystem.ScrollChannel.MAX_CALL_CONTACT_PICKER, scroll, maxScroll, totalCount);
		int endExclusive = rowSnapshotWindowEndExclusive(runtimeKey, MonitorScrollAnimationSystem.ScrollChannel.MAX_CALL_CONTACT_PICKER, scroll, maxScroll, maxCallContactPickerCapacity(layout), totalCount, startIndex);
		List<MaxContactSnapshot> snapshots = new ArrayList<>(Math.max(0, endExclusive - startIndex));
		for (int index = startIndex; index < endExclusive; index++) {
			MaxContactSnapshot snapshot = captureContactSnapshot(server, selfKey, candidates.get(index), notificationCounts, incomingProfiles);
			if (snapshot != null) {
				snapshots.add(snapshot);
			}
		}
		return new WindowedSnapshot<>(List.copyOf(snapshots), totalCount, startIndex);
	}

	private static WindowedSnapshot<MaxIncomingFileSnapshot> incomingFileSnapshots(
			MinecraftServer server,
			ScreenRuntimeKey key,
			MaxRuntimeState state,
			String activeContactCode,
			List<MaxIncomingFile> incomingFiles,
			MaxNotificationPreviewVisualState previewState,
			UiLayout layout
	) {
		if (incomingFiles == null || incomingFiles.isEmpty()) {
			return WindowedSnapshot.empty();
		}
		String activeSender = normalizeAccountCode(activeContactCode);
		List<MaxIncomingFile> filtered = new ArrayList<>(incomingFiles.size());
		for (MaxIncomingFile incoming : incomingFiles) {
			if (incoming == null) {
				continue;
			}
			if (!activeSender.isBlank() && !Objects.equals(activeSender, normalizeAccountCode(incoming.senderCode()))) {
				continue;
			}
			filtered.add(incoming);
		}
		int totalCount = filtered.size();
		if (totalCount <= 0 || state == null || layout == null) {
			return new WindowedSnapshot<>(List.of(), totalCount, 0);
		}
		int itemStride = maxNotificationItemHeight(layout) + maxNotificationItemGap(layout);
		int maxScroll = maxNotificationScroll(layout, totalCount);
		int scroll;
		synchronized (state) {
			scroll = clampInt(state.notificationScroll, 0, maxScroll);
		}
		int startIndex = pixelSnapshotWindowStartIndex(key, MonitorScrollAnimationSystem.ScrollChannel.MAX_NOTIFICATION_FEED, scroll, maxScroll, itemStride, totalCount);
		int endExclusive = pixelSnapshotWindowEndExclusive(key, MonitorScrollAnimationSystem.ScrollChannel.MAX_NOTIFICATION_FEED, scroll, maxScroll, itemStride, maxNotificationFeedRect(layout).height(), totalCount, startIndex);
		List<MaxIncomingFileSnapshot> snapshots = new ArrayList<>(Math.max(0, endExclusive - startIndex));
		for (int index = startIndex; index < endExclusive; index++) {
			MaxIncomingFileSnapshot snapshot = incomingFileSnapshot(server, key, state, filtered.get(index), previewState);
			if (snapshot != null) {
				snapshots.add(snapshot);
			}
		}
		return new WindowedSnapshot<>(List.copyOf(snapshots), totalCount, startIndex);
	}

	private static BufferedImage incomingFilePreview(MaxIncomingFile incoming, GalleryItemKind kind) {
		if (incoming == null || kind == null) {
			return null;
		}
		String url = incoming.url() == null ? "" : incoming.url().trim();
		String localMediaKey = incoming.localMediaKey() == null ? "" : incoming.localMediaKey().trim();
		if (kind == GalleryItemKind.YOUTUBE) {
			BufferedImage youtubePreview = !url.isBlank() ? MonitorYoutubeRelayClient.queueEntryPreview(url) : null;
			if (youtubePreview != null) {
				return youtubePreview;
			}
		}
		if (kind == GalleryItemKind.AUDIO && !url.isBlank()) {
			BufferedImage musicPreview = MonitorYoutubeMusicCache.looksLikeSupportedUrl(url)
					? MonitorYoutubeMusicCache.queueEntryPreview(url)
					: null;
			if (musicPreview != null) {
				return musicPreview;
			}
		}
		PersistedGalleryItem persisted = new PersistedGalleryItem(
				incoming.title() == null || incoming.title().isBlank() ? defaultSharedFileTitle(kind) : incoming.title(),
				incoming.subtitle() == null ? "" : incoming.subtitle(),
				url,
				kind,
				localMediaKey
		);
		return persistedGalleryPreviewForDisplay(persisted, kind);
	}

	private static BufferedImage cachedIncomingFilePreview(MaxRuntimeState state, String fileId) {
		if (state == null || fileId == null || fileId.isBlank()) {
			return null;
		}
		synchronized (state) {
			MaxIncomingPreviewCacheEntry entry = state.incomingPreviewCache.get(fileId);
			return entry != null ? entry.previewFrame : null;
		}
	}

	private static void ensureIncomingFilePreviewAsync(MinecraftServer server, ScreenRuntimeKey key, MaxRuntimeState state, MaxIncomingFile incoming) {
		if (server == null || key == null || state == null || incoming == null) {
			return;
		}
		String fileId = notificationFileId(incoming);
		if (fileId.isBlank()) {
			return;
		}
		synchronized (state) {
			MaxIncomingPreviewCacheEntry entry = state.incomingPreviewCache.computeIfAbsent(fileId, ignored -> new MaxIncomingPreviewCacheEntry());
			if (entry.loading || entry.resolved) {
				return;
			}
			entry.loading = true;
		}
		GalleryItemKind kind = incoming.kind() != null ? incoming.kind() : GalleryItemKind.MEDIA;
		ensureExecutors();
		CompletableFuture
				.supplyAsync(() -> new MaxIncomingPreviewLoadResult(key, fileId, incomingFilePreview(incoming, kind)), mediaIoExecutor)
				.thenAccept(result -> server.execute(() -> applyIncomingFilePreviewLoadResult(server, result)));
	}

	private static void applyIncomingFilePreviewLoadResult(MinecraftServer server, MaxIncomingPreviewLoadResult result) {
		if (server == null || result == null || result.key() == null || result.fileId() == null || result.fileId().isBlank()) {
			return;
		}
		MaxRuntimeState state = MAX_STATES.get(result.key());
		if (state == null) {
			return;
		}
		boolean shouldRender;
		synchronized (state) {
			MaxIncomingPreviewCacheEntry entry = state.incomingPreviewCache.computeIfAbsent(result.fileId(), ignored -> new MaxIncomingPreviewCacheEntry());
			entry.loading = false;
			entry.resolved = true;
			if (hasIncomingFileLocked(state, result.fileId()) && result.previewFrame() != null) {
				entry.previewFrame = result.previewFrame();
			}
			shouldRender = state.notificationsOpen && hasIncomingFileLocked(state, result.fileId());
			if (shouldRender) {
				state.version++;
			}
		}
		if (shouldRender) {
			requestRuntimeRender(server, result.key());
		}
	}

	private static boolean hasIncomingFileLocked(MaxRuntimeState state, String fileId) {
		if (state == null || fileId == null || fileId.isBlank()) {
			return false;
		}
		for (MaxIncomingFile incoming : state.incomingFiles) {
			if (Objects.equals(fileId, notificationFileId(incoming))) {
				return true;
			}
		}
		return false;
	}

	private static void pruneIncomingPreviewCacheLocked(MaxRuntimeState state) {
		if (state == null) {
			return;
		}
		Set<String> activeIds = new HashSet<>();
		for (MaxIncomingFile incoming : state.incomingFiles) {
			String fileId = notificationFileId(incoming);
			if (!fileId.isBlank()) {
				activeIds.add(fileId);
			}
		}
		state.incomingPreviewCache.entrySet().removeIf(entry -> !activeIds.contains(entry.getKey()));
		if (!state.notificationPreviewFileId.isBlank() && !activeIds.contains(state.notificationPreviewFileId)) {
			clearNotificationPreviewLocked(state);
		}
	}

	private static boolean notificationPreviewPlayable(GalleryItemKind kind, MaxIncomingFile incoming) {
		if (incoming == null) {
			return false;
		}
		GalleryItemKind resolvedKind = kind != null ? kind : GalleryItemKind.MEDIA;
		if (resolvedKind != GalleryItemKind.AUDIO && resolvedKind != GalleryItemKind.VIDEO) {
			return false;
		}
		String url = incoming.url() == null ? "" : incoming.url().trim();
		String localMediaKey = incoming.localMediaKey() == null ? "" : incoming.localMediaKey().trim();
		return !url.isBlank() || !localMediaKey.isBlank();
	}

	private static BufferedImage currentNotificationPreviewFrameLocked(MaxRuntimeState state, BufferedImage fallback) {
		if (state == null || state.notificationPreviewMedia == null || state.notificationPreviewMedia.frameCount() <= 0) {
			return fallback;
		}
		int frameIndex = notificationPreviewFrameIndexLocked(state, System.currentTimeMillis());
		BufferedImage frame = state.notificationPreviewMedia.frame(frameIndex);
		return frame != null ? frame : fallback;
	}

	private static int notificationPreviewFrameIndexLocked(MaxRuntimeState state, long now) {
		if (state == null || state.notificationPreviewMedia == null || state.notificationPreviewMedia.frameCount() <= 1) {
			return 0;
		}
		return notificationPreviewFrameIndex(state.notificationPreviewMedia, notificationPreviewPositionMillisLocked(state, now));
	}

	private static int notificationPreviewFrameIndex(MonitorMediaApp.LoadedMedia media, long positionMillis) {
		if (media == null || media.frameCount() <= 1) {
			return 0;
		}
		long loopDuration = notificationPreviewLoopDurationMillis(media);
		if (loopDuration <= 0L) {
			return 0;
		}
		long normalizedPosition = Math.floorMod(Math.max(0L, positionMillis), loopDuration);
		long elapsed = 0L;
		for (int index = 0; index < media.frameCount(); index++) {
			elapsed += notificationPreviewFrameDelayMillis(media, index);
			if (normalizedPosition < elapsed) {
				return index;
			}
		}
		return Math.max(0, media.frameCount() - 1);
	}

	private static long notificationPreviewLoopDurationMillis(MonitorMediaApp.LoadedMedia media) {
		if (media == null || media.frameCount() <= 0) {
			return 0L;
		}
		long total = 0L;
		for (int index = 0; index < media.frameCount(); index++) {
			total += notificationPreviewFrameDelayMillis(media, index);
		}
		return Math.max(1L, total);
	}

	private static long notificationPreviewFrameDelayMillis(MonitorMediaApp.LoadedMedia media, int frameIndex) {
		if (media == null || media.frameCount() <= 0) {
			return MAX_NOTIFICATION_PREVIEW_RENDER_FALLBACK_DELAY_MS;
		}
		int safeIndex = clampInt(frameIndex, 0, media.frameCount() - 1);
		return Math.max(1L, media.delayMillis(safeIndex));
	}

	private static long notificationPreviewMillisUntilNextFrameLocked(MaxRuntimeState state, long now) {
		if (state == null || state.notificationPreviewMedia == null || state.notificationPreviewMedia.frameCount() <= 1) {
			return MAX_NOTIFICATION_PREVIEW_RENDER_FALLBACK_DELAY_MS;
		}
		MonitorMediaApp.LoadedMedia media = state.notificationPreviewMedia;
		long loopDuration = notificationPreviewLoopDurationMillis(media);
		if (loopDuration <= 0L) {
			return MAX_NOTIFICATION_PREVIEW_RENDER_FALLBACK_DELAY_MS;
		}
		long normalizedPosition = Math.floorMod(notificationPreviewPositionMillisLocked(state, now), loopDuration);
		long elapsed = 0L;
		for (int index = 0; index < media.frameCount(); index++) {
			elapsed += notificationPreviewFrameDelayMillis(media, index);
			if (normalizedPosition < elapsed) {
				return Math.max(1L, elapsed - normalizedPosition);
			}
		}
		return MAX_NOTIFICATION_PREVIEW_RENDER_FALLBACK_DELAY_MS;
	}

	private static void toggleNotificationPreview(MinecraftServer server, ScreenRuntimeKey key, MaxRuntimeState state, int index) {
		if (server == null || key == null || state == null) {
			return;
		}
		MaxIncomingFile incoming;
		long generationToLoad = -1L;
		boolean startLoad = false;
		boolean scheduleFrames = false;
		synchronized (state) {
			if (index < 0 || index >= state.incomingFiles.size()) {
				return;
			}
			incoming = state.incomingFiles.get(index);
			if (!notificationPreviewPlayable(incoming.kind(), incoming)) {
				return;
			}
			String fileId = notificationFileId(incoming);
			if (fileId.isBlank()) {
				return;
			}
			long now = System.currentTimeMillis();
			if (Objects.equals(fileId, state.notificationPreviewFileId)) {
				if (state.notificationPreviewLoading) {
					state.notificationPreviewPlaying = !state.notificationPreviewPlaying;
					if (state.notificationPreviewPlaying) {
						state.notificationPreviewStartedAtMillis = Math.max(1L, now - Math.max(0L, state.notificationPreviewPausedPositionMs));
					} else {
						state.notificationPreviewPausedPositionMs = notificationPreviewPositionMillisLocked(state, now);
					}
					state.version++;
				} else if (state.notificationPreviewMedia != null || !state.notificationPreviewAudioInput.isBlank()) {
					if (state.notificationPreviewPlaying) {
						state.notificationPreviewPausedPositionMs = notificationPreviewPositionMillisLocked(state, now);
						state.notificationPreviewPlaying = false;
					} else {
						state.notificationPreviewStartedAtMillis = Math.max(1L, now - Math.max(0L, state.notificationPreviewPausedPositionMs));
						state.notificationPreviewPlaying = true;
						scheduleFrames = state.notificationPreviewMedia != null && state.notificationPreviewMedia.frameCount() > 1;
					}
					state.version++;
				} else {
					generationToLoad = beginNotificationPreviewLoadLocked(state, incoming, now);
					startLoad = true;
				}
			} else {
				generationToLoad = beginNotificationPreviewLoadLocked(state, incoming, now);
				startLoad = true;
			}
		}
		requestRuntimeRender(server, key);
		refreshConnectedSpeakersNow(server, key);
		if (scheduleFrames) {
			scheduleNotificationPreviewRender(server, key, state);
		}
		if (startLoad) {
			loadNotificationPreviewAsync(server, key, incoming, generationToLoad);
		}
	}

	private static long beginNotificationPreviewLoadLocked(MaxRuntimeState state, MaxIncomingFile incoming, long now) {
		clearNotificationPreviewLocked(state);
		state.notificationPreviewFileId = notificationFileId(incoming);
		state.notificationPreviewLoading = true;
		state.notificationPreviewPlaying = true;
		state.notificationPreviewStartedAtMillis = Math.max(1L, now);
		state.notificationPreviewPausedPositionMs = 0L;
		state.notificationPreviewLoadGeneration++;
		state.version++;
		return state.notificationPreviewLoadGeneration;
	}

	private static void loadNotificationPreviewAsync(MinecraftServer server, ScreenRuntimeKey key, MaxIncomingFile incoming, long generation) {
		if (server == null || key == null || incoming == null || generation <= 0L) {
			return;
		}
		ensureExecutors();
		CompletableFuture
				.supplyAsync(() -> loadNotificationPreview(key, incoming, generation), mediaIoExecutor)
				.thenAccept(result -> server.execute(() -> applyNotificationPreviewLoadResult(server, result)));
	}

	private static MaxNotificationPreviewLoadResult loadNotificationPreview(ScreenRuntimeKey key, MaxIncomingFile incoming, long generation) {
		String fileId = notificationFileId(incoming);
		GalleryItemKind kind = incoming.kind() != null ? incoming.kind() : GalleryItemKind.MEDIA;
		String url = incoming.url() == null ? "" : incoming.url().trim();
		String localMediaKey = incoming.localMediaKey() == null ? "" : incoming.localMediaKey().trim();
		try {
			if (kind == GalleryItemKind.AUDIO) {
				MonitorMediaApp.LoadedVideo video;
				if (MonitorYoutubeMusicCache.looksLikeSupportedUrl(url) && localMediaKey.isBlank()) {
					video = MonitorYoutubeMusicCache.load(url, null).video();
				} else {
					MonitorMediaApp.LoadedAudioTrack track = !localMediaKey.isBlank()
							? MonitorMediaApp.loadSavedGalleryAudio(localMediaKey, null)
							: MonitorMediaApp.loadAudioFromUrl(url, null);
					video = track.video();
				}
				return new MaxNotificationPreviewLoadResult(
						key,
						fileId,
						generation,
						singleFrameNotificationPreviewMedia(video != null ? video.preview() : null),
						video != null && video.audioInput() != null ? video.audioInput() : "",
						null
				);
			}
			if (kind == GalleryItemKind.VIDEO) {
				MonitorMediaApp.LoadedVideo video = loadGalleryVideo(url, localMediaKey, null);
				MonitorMediaApp.LoadedMedia media = loadNotificationVideoPreviewMedia(url, localMediaKey);
				if (media == null) {
					media = singleFrameNotificationPreviewMedia(video != null ? video.preview() : null);
				}
				return new MaxNotificationPreviewLoadResult(
						key,
						fileId,
						generation,
						media,
						video != null && video.audioInput() != null ? video.audioInput() : "",
						null
				);
			}
			return new MaxNotificationPreviewLoadResult(key, fileId, generation, null, "", null);
		} catch (Exception exception) {
			return new MaxNotificationPreviewLoadResult(key, fileId, generation, null, "", sanitizeMediaError(exception.getMessage()));
		}
	}

	private static MonitorMediaApp.LoadedMedia loadNotificationVideoPreviewMedia(String url, String localMediaKey) {
		try {
			if (localMediaKey != null && !localMediaKey.isBlank()) {
				return MonitorMediaApp.loadSavedGalleryVideoAsMedia(localMediaKey, null);
			}
			if (isCameraGalleryVideoUrl(url)) {
				return loadCameraGalleryVideoMedia(url, localMediaKey, null);
			}
		} catch (Exception exception) {
			Lg2.LOGGER.debug("Failed to decode MAX notification video preview {}: {}", Objects.toString(localMediaKey, url), sanitizeMediaError(exception.getMessage()));
		}
		return null;
	}

	private static MonitorMediaApp.LoadedMedia singleFrameNotificationPreviewMedia(BufferedImage frame) {
		if (frame == null) {
			return null;
		}
		return new MonitorMediaApp.LoadedMedia(List.of(frame), List.of((int) MAX_NOTIFICATION_PREVIEW_RENDER_FALLBACK_DELAY_MS), frame.getWidth(), frame.getHeight(), false);
	}

	private static void applyNotificationPreviewLoadResult(MinecraftServer server, MaxNotificationPreviewLoadResult result) {
		if (server == null || result == null || result.key() == null) {
			return;
		}
		MaxRuntimeState state = MAX_STATES.get(result.key());
		if (state == null) {
			return;
		}
		boolean scheduleFrames = false;
		synchronized (state) {
			if (!state.notificationsOpen
					|| !Objects.equals(state.notificationPreviewFileId, result.fileId())
					|| state.notificationPreviewLoadGeneration != result.generation()) {
				return;
			}
			state.notificationPreviewLoading = false;
			if (result.error() == null) {
				state.notificationPreviewMedia = result.media();
				state.notificationPreviewAudioInput = result.audioInput() == null ? "" : result.audioInput();
				state.notificationPreviewFrameIndex = 0;
				if (state.notificationPreviewPlaying && state.notificationPreviewStartedAtMillis <= 0L) {
					state.notificationPreviewStartedAtMillis = System.currentTimeMillis();
				}
				scheduleFrames = state.notificationPreviewPlaying
						&& state.notificationPreviewMedia != null
						&& state.notificationPreviewMedia.frameCount() > 1;
			} else {
				state.notificationPreviewPlaying = false;
				state.notificationPreviewPausedPositionMs = 0L;
			}
			state.version++;
		}
		requestRuntimeRender(server, result.key());
		refreshConnectedSpeakersNow(server, result.key());
		if (scheduleFrames) {
			scheduleNotificationPreviewRender(server, result.key(), state);
		}
	}

	private static void scheduleNotificationPreviewRender(MinecraftServer server, ScreenRuntimeKey key, MaxRuntimeState state) {
		if (server == null || key == null || state == null) {
			return;
		}
		long delayMillis;
		synchronized (state) {
			if (state.notificationPreviewRenderScheduled || !state.notificationPreviewPlaying || state.notificationPreviewMedia == null || state.notificationPreviewMedia.frameCount() <= 1) {
				return;
			}
			delayMillis = notificationPreviewMillisUntilNextFrameLocked(state, System.currentTimeMillis());
			state.notificationPreviewRenderScheduled = true;
		}
		ensureExecutors();
		mediaScheduler.schedule(() -> server.execute(() -> advanceNotificationPreviewFrame(server, key)), Math.max(1L, delayMillis), TimeUnit.MILLISECONDS);
	}

	private static void advanceNotificationPreviewFrame(MinecraftServer server, ScreenRuntimeKey key) {
		if (server == null || key == null) {
			return;
		}
		MaxRuntimeState state = MAX_STATES.get(key);
		if (state == null) {
			return;
		}
		boolean shouldRender = false;
		boolean shouldContinue = false;
		synchronized (state) {
			state.notificationPreviewRenderScheduled = false;
			if (state.notificationsOpen
					&& state.notificationPreviewPlaying
					&& state.notificationPreviewMedia != null
					&& state.notificationPreviewMedia.frameCount() > 1) {
				state.notificationPreviewFrameIndex = notificationPreviewFrameIndexLocked(state, System.currentTimeMillis());
				state.version++;
				shouldRender = true;
				shouldContinue = true;
			}
		}
		if (shouldRender) {
			requestRuntimeRender(server, key);
		}
		if (shouldContinue) {
			scheduleNotificationPreviewRender(server, key, state);
		}
	}

	private static void clearNotificationPreviewLocked(MaxRuntimeState state) {
		if (state == null) {
			return;
		}
		state.notificationPreviewFileId = "";
		state.notificationPreviewLoading = false;
		state.notificationPreviewPlaying = false;
		state.notificationPreviewAudioInput = "";
		state.notificationPreviewMedia = null;
		state.notificationPreviewFrameIndex = 0;
		state.notificationPreviewStartedAtMillis = 0L;
		state.notificationPreviewPausedPositionMs = 0L;
		state.notificationPreviewLoadGeneration++;
		state.notificationPreviewRenderScheduled = false;
	}

	private static void openContactNotifications(MinecraftServer server, ScreenRuntimeKey key, MaxRuntimeState state, String contactCode) {
		if (server == null || key == null || state == null) {
			return;
		}
		String normalizedContact = normalizeAccountCode(contactCode);
		if (normalizedContact.isBlank()) {
			return;
		}
		synchronized (state) {
			state.notificationsOpen = true;
			state.notificationContactCode = normalizedContact;
			state.notificationScroll = 0;
			state.avatarPickerOpen = false;
			state.ringtonePickerOpen = false;
			state.fileSharePickerOpen = false;
			state.statusText = "";
			clearNotificationPreviewLocked(state);
			state.version++;
		}
		refreshConnectedSpeakersNow(server, key);
		requestRuntimeRender(server, key);
	}

	private static void closeNotificationsLocked(MaxRuntimeState state) {
		if (state == null) {
			return;
		}
		state.notificationsOpen = false;
		state.notificationContactCode = "";
		state.notificationScroll = 0;
		clearNotificationPreviewLocked(state);
	}

	private static String notificationFileId(MaxIncomingFile incoming) {
		return incoming == null || incoming.id() == null ? "" : incoming.id().trim();
	}

	private static long notificationPreviewPositionMillisLocked(MaxRuntimeState state, long now) {
		if (state == null) {
			return 0L;
		}
		if (!state.notificationPreviewPlaying) {
			return Math.max(0L, state.notificationPreviewPausedPositionMs);
		}
		if (state.notificationPreviewStartedAtMillis <= 0L) {
			return 0L;
		}
		return Math.max(0L, now - state.notificationPreviewStartedAtMillis);
	}

	private static List<MaxContactSnapshot> contactInviteCandidates(MinecraftServer server, MaxRuntimeState state, MaxCallSession call, ScreenRuntimeKey selfKey) {
		if (state == null || call == null) {
			return List.of();
		}
		List<MaxContactSnapshot> contacts = captureContactSnapshots(server, state, selfKey);
		if (contacts.isEmpty()) {
			return List.of();
		}
		String peerCode = call.peerCode(selfKey);
		List<MaxContactSnapshot> candidates = new ArrayList<>();
		for (MaxContactSnapshot contact : contacts) {
			if (contact == null) {
				continue;
			}
			if (contact.active() || contact.ringing() || Objects.equals(contact.code(), peerCode)) {
				continue;
			}
			ScreenRuntimeKey key = ACCOUNT_INDEX.get(contact.code());
			if (key != null && call.isParticipant(key)) {
				continue;
			}
			candidates.add(contact);
		}
		return candidates.isEmpty() ? List.of() : List.copyOf(candidates);
	}

	private static void startCall(MinecraftServer server, ScreenRuntimeKey callerKey, String targetCode) {
		if (server == null || callerKey == null) {
			return;
		}
		String code = normalizeAccountCode(targetCode);
		MaxRuntimeState callerState = MAX_STATES.get(callerKey);
		ScreenRuntimeKey calleeKey = ACCOUNT_INDEX.get(code);
		ScreenComponent callerComponent = resolveScreenComponent(server, callerKey);
		ScreenComponent calleeComponent = calleeKey != null ? resolveScreenComponent(server, calleeKey) : null;
		if (callerState == null || callerComponent == null) {
			return;
		}
		if (calleeKey == null || calleeComponent == null || !calleeComponent.powered()) {
			synchronized (callerState) {
				callerState.statusText = "Контакт недоступен";
				callerState.version++;
			}
			requestRuntimeRender(server, callerKey);
			return;
		}
		endCall(server, callerKey);
		endCall(server, calleeKey);
		String callerCode;
		synchronized (callerState) {
			callerCode = callerState.accountCode;
		}
		MaxCallSession call = new MaxCallSession(UUID.randomUUID(), callerKey, calleeKey, callerCode, code);
		CALLS_BY_ID.put(call.id, call);
		CALL_BY_SCREEN.put(callerKey, call.id);
		CALL_BY_SCREEN.put(calleeKey, call.id);
		synchronized (callerState) {
			callerState.callMenuOpen = true;
			callerState.cameraPickerOpen = false;
			callerState.callContactPickerOpen = false;
			callerState.focusPeer = false;
			callerState.focusSelf = false;
			callerState.focusedPeerKey = null;
			callerState.version++;
		}
		MaxRuntimeState calleeState = ensureState(server, calleeComponent);
		if (calleeState != null) {
			synchronized (calleeState) {
				calleeState.callMenuOpen = true;
				calleeState.cameraPickerOpen = false;
				calleeState.callContactPickerOpen = false;
				calleeState.focusPeer = false;
				calleeState.focusSelf = false;
				calleeState.focusedPeerKey = null;
				calleeState.version++;
			}
		}
		refreshConnectedSpeakersNow(server, calleeComponent);
		requestRuntimeRender(server, callerKey);
		requestRuntimeRender(server, calleeKey);
	}

	private static boolean inviteContactToCall(MinecraftServer server, ScreenRuntimeKey inviterKey, String targetCode) {
		if (server == null || inviterKey == null) {
			return false;
		}
		MaxCallSession call = currentCall(inviterKey);
		MaxRuntimeState inviterState = MAX_STATES.get(inviterKey);
		String code = normalizeAccountCode(targetCode);
		ScreenRuntimeKey inviteeKey = ACCOUNT_INDEX.get(code);
		ScreenComponent inviteeComponent = inviteeKey != null ? resolveScreenComponent(server, inviteeKey) : null;
		if (call == null || !call.isAccepted(inviterKey) || inviterState == null) {
			return false;
		}
		if (inviteeKey == null || inviteeComponent == null || !inviteeComponent.powered()) {
			synchronized (inviterState) {
				inviterState.statusText = "Контакт недоступен";
				inviterState.callContactPickerOpen = false;
				inviterState.version++;
			}
			requestRuntimeRender(server, inviterKey);
			return false;
		}
		UUID previousCallId = CALL_BY_SCREEN.get(inviteeKey);
		if (previousCallId != null && !Objects.equals(previousCallId, call.id)) {
			endCall(server, inviteeKey);
		}
		if (!call.addInvitee(inviterKey, inviteeKey, code)) {
			synchronized (inviterState) {
				inviterState.statusText = "Контакт уже в звонке";
				inviterState.callContactPickerOpen = false;
				inviterState.version++;
			}
			requestRuntimeRender(server, inviterKey);
			return false;
		}
		CALL_BY_SCREEN.put(inviteeKey, call.id);
		synchronized (inviterState) {
			inviterState.callContactPickerOpen = false;
			inviterState.version++;
		}
		MaxRuntimeState inviteeState = ensureState(server, inviteeComponent);
		if (inviteeState != null) {
			synchronized (inviteeState) {
				inviteeState.callMenuOpen = true;
				inviteeState.cameraPickerOpen = false;
				inviteeState.callContactPickerOpen = false;
				inviteeState.focusPeer = false;
				inviteeState.focusSelf = false;
				inviteeState.focusedPeerKey = null;
				inviteeState.version++;
			}
		}
		refreshConnectedSpeakersNow(server, inviteeComponent);
		MicrophoneSystem.onMaxCallStateChanged(server);
		requestRuntimeRender(server, inviterKey);
		requestRuntimeRender(server, inviteeKey);
		for (ScreenRuntimeKey participant : call.acceptedParticipants()) {
			requestRuntimeRender(server, participant);
		}
		return true;
	}

	private static void acceptCall(MinecraftServer server, ScreenRuntimeKey calleeKey) {
		MaxCallSession call = currentCall(calleeKey);
		if (server == null || call == null || !call.isRinging(calleeKey)) {
			return;
		}
		call.accept(calleeKey);
		for (ScreenRuntimeKey participant : call.participants()) {
			setCallMenuState(participant, false);
		}
		ScreenComponent callee = resolveScreenComponent(server, calleeKey);
		if (callee != null) {
			refreshConnectedSpeakersNow(server, callee);
		}
		MicrophoneSystem.onMaxCallStateChanged(server);
		for (ScreenRuntimeKey participant : call.participants()) {
			requestRuntimeRender(server, participant);
		}
	}

	private static void endCall(MinecraftServer server, ScreenRuntimeKey key) {
		if (key == null) {
			return;
		}
		UUID callId = CALL_BY_SCREEN.remove(key);
		if (callId == null) {
			RendererBotCameraSystem.stopLiveStream(maxVideoStreamOwnerId(key));
			RendererBotCameraSystem.stopLiveStream(maxLocalVideoStreamOwnerId(key));
			return;
		}
		MaxCallSession call = CALLS_BY_ID.get(callId);
		if (call == null) {
			CALL_BY_SCREEN.remove(key);
			RendererBotCameraSystem.stopLiveStream(maxVideoStreamOwnerId(key));
			RendererBotCameraSystem.stopLiveStream(maxLocalVideoStreamOwnerId(key));
			return;
		}
		if (call.isRinging(key) && call.acceptedParticipantCount() >= 2) {
			call.removeParticipant(key);
			CALL_BY_SCREEN.remove(key);
			RendererBotCameraSystem.stopLiveStream(maxVideoStreamOwnerId(key));
			RendererBotCameraSystem.stopLiveStream(maxLocalVideoStreamOwnerId(key));
			clearRemoteFrame(key);
			clearLocalFrame(key);
			clearCallUiState(key);
			if (server != null) {
				ScreenComponent component = resolveScreenComponent(server, key);
				if (component != null) {
					refreshConnectedSpeakersNow(server, component);
				}
				requestRuntimeRender(server, key);
				for (ScreenRuntimeKey participant : call.participants()) {
					requestRuntimeRender(server, participant);
				}
				MicrophoneSystem.onMaxCallStateChanged(server);
			}
			return;
		}
		List<ScreenRuntimeKey> participants = call.participants();
		if (call.acceptedParticipantCount() > 2 && call.isAccepted(key)) {
			call.removeParticipant(key);
			CALL_BY_SCREEN.remove(key);
			RendererBotCameraSystem.stopLiveStream(maxVideoStreamOwnerId(key));
			RendererBotCameraSystem.stopLiveStream(maxLocalVideoStreamOwnerId(key));
			clearRemoteFrame(key);
			clearLocalFrame(key);
			clearCallUiState(key);
			if (server != null) {
				ScreenComponent component = resolveScreenComponent(server, key);
				if (component != null) {
					refreshConnectedSpeakersNow(server, component);
				}
				requestRuntimeRender(server, key);
				for (ScreenRuntimeKey participant : call.participants()) {
					requestRuntimeRender(server, participant);
				}
				MicrophoneSystem.onMaxCallStateChanged(server);
			}
			return;
		}
		CALLS_BY_ID.remove(callId);
		for (ScreenRuntimeKey participant : participants) {
			CALL_BY_SCREEN.remove(participant);
			RendererBotCameraSystem.stopLiveStream(maxVideoStreamOwnerId(participant));
			RendererBotCameraSystem.stopLiveStream(maxLocalVideoStreamOwnerId(participant));
			clearRemoteFrame(participant);
			clearLocalFrame(participant);
			clearCallUiState(participant);
		}
		if (server != null) {
			for (ScreenRuntimeKey participant : participants) {
				ScreenComponent participantComponent = resolveScreenComponent(server, participant);
				if (participantComponent != null) {
					refreshConnectedSpeakersNow(server, participantComponent);
				}
				requestRuntimeRender(server, participant);
			}
			MicrophoneSystem.onMaxCallStateChanged(server);
		}
	}

	private static void clearCallUiState(ScreenRuntimeKey key) {
		MaxRuntimeState state = MAX_STATES.get(key);
		if (state == null) {
			return;
		}
		synchronized (state) {
			state.callMenuOpen = false;
			state.cameraPickerOpen = false;
			state.callMiniParticipantScroll = 0;
			state.callContactPickerOpen = false;
			state.callMiniParticipantsHidden = false;
			state.focusPeer = false;
			state.focusSelf = false;
			state.focusedPeerKey = null;
			state.version++;
		}
	}

	private static void toggleCamera(MinecraftServer server, ScreenRuntimeKey key) {
		MaxRuntimeState state = MAX_STATES.get(key);
		if (state == null) {
			return;
		}
		synchronized (state) {
			state.cameraEnabled = !state.cameraEnabled;
			state.version++;
		}
		persistState(server, key, state);
		RendererBotCameraSystem.stopLiveStream(maxLocalVideoStreamOwnerId(key));
		clearLocalFrame(key);
		requestRuntimeRender(server, key);
		requestPeerRenderAfterLocalCameraChange(server, key);
	}

	private static void setCallMenuState(ScreenRuntimeKey key, boolean open) {
		MaxRuntimeState state = MAX_STATES.get(key);
		if (state == null) {
			return;
		}
		synchronized (state) {
			state.callMenuOpen = open;
			state.version++;
		}
	}

	private static boolean isCallMenuVisible(MaxRuntimeState state) {
		if (state == null) {
			return false;
		}
		synchronized (state) {
			return state.callMenuOpen;
		}
	}

	private static boolean callFocused(MaxRuntimeState state) {
		if (state == null) {
			return false;
		}
		synchronized (state) {
			return state.focusSelf || state.focusPeer;
		}
	}

	private static boolean callFocusSelf(MaxRuntimeState state) {
		if (state == null) {
			return false;
		}
		synchronized (state) {
			return state.focusSelf;
		}
	}

	private static boolean callFocusPeer(MaxRuntimeState state) {
		if (state == null) {
			return false;
		}
		synchronized (state) {
			return state.focusPeer;
		}
	}

	private static boolean callMiniParticipantsHidden(MaxRuntimeState state) {
		if (state == null) {
			return false;
		}
		synchronized (state) {
			return state.callMiniParticipantsHidden;
		}
	}

	private static int callMiniParticipantScroll(MaxRuntimeState state) {
		if (state == null) {
			return 0;
		}
		synchronized (state) {
			return state.callMiniParticipantScroll;
		}
	}

	private static void setCallMiniParticipantScroll(MinecraftServer server, ScreenRuntimeKey key, MaxRuntimeState state, UiLayout layout, int participantCount, int scroll) {
		if (server == null || key == null || state == null || layout == null) {
			return;
		}
		boolean changed = false;
		synchronized (state) {
			int nextScroll = clampInt(scroll, 0, maxCallMiniParticipantScroll(layout, participantCount));
			if (nextScroll != state.callMiniParticipantScroll) {
				state.callMiniParticipantScroll = nextScroll;
				state.version++;
				changed = true;
			}
		}
		if (changed) {
			requestRuntimeRender(server, key);
		}
	}

	private static void scrollCallMiniParticipants(MinecraftServer server, ScreenRuntimeKey key, MaxRuntimeState state, UiLayout layout, int participantCount, int delta) {
		if (server == null || key == null || state == null || layout == null || delta == 0) {
			return;
		}
		boolean changed = false;
		synchronized (state) {
			int nextScroll = clampInt(state.callMiniParticipantScroll + delta, 0, maxCallMiniParticipantScroll(layout, participantCount));
			if (nextScroll != state.callMiniParticipantScroll) {
				state.callMiniParticipantScroll = nextScroll;
				state.version++;
				changed = true;
			}
		}
		if (changed) {
			requestRuntimeRender(server, key);
		}
	}

	private static ScreenRuntimeKey focusedCallParticipantKey(ScreenRuntimeKey selfKey, MaxRuntimeState state, MaxCallSession call) {
		if (selfKey == null || state == null || call == null) {
			return null;
		}
		synchronized (state) {
			if (state.focusSelf) {
				return selfKey;
			}
			if (state.focusPeer && state.focusedPeerKey != null && call.isParticipant(state.focusedPeerKey)) {
				return state.focusedPeerKey;
			}
			if (state.focusPeer) {
				return call.peer(selfKey);
			}
		}
		return null;
	}

	private static List<ScreenRuntimeKey> callMiniParticipantKeys(ScreenRuntimeKey selfKey, MaxRuntimeState state, MaxCallSession call) {
		if (call == null) {
			return List.of();
		}
		ScreenRuntimeKey focusedKey = focusedCallParticipantKey(selfKey, state, call);
		List<ScreenRuntimeKey> participants = call.participants();
		if (participants.isEmpty()) {
			return List.of();
		}
		List<ScreenRuntimeKey> miniParticipants = new ArrayList<>(participants.size());
		for (ScreenRuntimeKey participant : participants) {
			if (!Objects.equals(participant, focusedKey)) {
				miniParticipants.add(participant);
			}
		}
		return List.copyOf(miniParticipants);
	}

	private static void toggleCallMenu(MinecraftServer server, ScreenRuntimeKey key) {
		MaxRuntimeState state = MAX_STATES.get(key);
		if (state == null) {
			return;
		}
		synchronized (state) {
			state.callMenuOpen = !state.callMenuOpen;
			state.version++;
		}
		requestRuntimeRender(server, key);
	}

	private static void focusCallParticipant(MinecraftServer server, ScreenRuntimeKey key, boolean self) {
		MaxRuntimeState state = MAX_STATES.get(key);
		if (state == null) {
			return;
		}
		synchronized (state) {
			state.focusSelf = self;
			state.focusPeer = !self;
			state.callMiniParticipantScroll = 0;
			state.callMiniParticipantsHidden = false;
			state.focusedPeerKey = null;
			state.callMenuOpen = true;
			state.version++;
		}
		requestRuntimeRender(server, key);
	}

	private static void focusCallParticipant(MinecraftServer server, ScreenRuntimeKey key, ScreenRuntimeKey participantKey) {
		MaxRuntimeState state = MAX_STATES.get(key);
		if (state == null || participantKey == null) {
			return;
		}
		synchronized (state) {
			state.focusSelf = false;
			state.focusPeer = true;
			state.callMiniParticipantScroll = 0;
			state.callMiniParticipantsHidden = false;
			state.focusedPeerKey = participantKey;
			state.callMenuOpen = true;
			state.version++;
		}
		requestRuntimeRender(server, key);
	}

	private static void clearCallFocus(MinecraftServer server, ScreenRuntimeKey key) {
		MaxRuntimeState state = MAX_STATES.get(key);
		if (state == null) {
			return;
		}
		synchronized (state) {
			state.focusSelf = false;
			state.focusPeer = false;
			state.callMiniParticipantScroll = 0;
			state.callMiniParticipantsHidden = false;
			state.focusedPeerKey = null;
			state.callMenuOpen = true;
			state.version++;
		}
		requestRuntimeRender(server, key);
	}

	private static void toggleCallMiniParticipantsHidden(MinecraftServer server, ScreenRuntimeKey key) {
		MaxRuntimeState state = MAX_STATES.get(key);
		if (state == null) {
			return;
		}
		synchronized (state) {
			state.callMiniParticipantsHidden = !state.callMiniParticipantsHidden;
			state.version++;
		}
		requestRuntimeRender(server, key);
	}

	private static void toggleMicrophone(MinecraftServer server, ScreenRuntimeKey key) {
		MaxRuntimeState state = MAX_STATES.get(key);
		if (state == null) {
			return;
		}
		synchronized (state) {
			state.microphoneEnabled = !state.microphoneEnabled;
			state.version++;
		}
		persistState(server, key, state);
		requestRuntimeRender(server, key);
		requestPeerRender(server, key);
		MicrophoneSystem.onMaxCallStateChanged(server);
	}

	private static void cycleSelectedMicrophone(MinecraftServer server, ScreenRuntimeKey key) {
		MaxRuntimeState state = MAX_STATES.get(key);
		if (state == null) {
			return;
		}
		List<MicrophoneSystem.ScreenMicrophoneDevice> microphones = MicrophoneSystem.connectedMicrophoneDevices(server, key);
		int count = microphones.size();
		synchronized (state) {
			int currentIndex = normalizeSelectedMicrophoneLocked(state, microphones);
			if (count > 1) {
				int nextIndex = currentIndex < 0 ? 0 : Math.floorMod(currentIndex + 1, count);
				state.selectedMicrophoneIndex = nextIndex;
				state.selectedMicrophoneKey = microphoneDeviceKey(microphones.get(nextIndex));
			} else {
				state.selectedMicrophoneIndex = -1;
				state.selectedMicrophoneKey = "";
			}
			state.version++;
		}
		persistState(server, key, state);
		requestRuntimeRender(server, key);
		requestPeerRender(server, key);
		MicrophoneSystem.onMaxCallStateChanged(server);
	}

	private static void openCameraPicker(MinecraftServer server, ScreenRuntimeKey key) {
		MaxRuntimeState state = MAX_STATES.get(key);
		if (state == null) {
			return;
		}
		synchronized (state) {
			state.cameraPickerOpen = true;
			state.cameraPickerScroll = 0;
			state.microphonePickerScroll = 0;
			state.callContactPickerOpen = false;
			state.callMenuOpen = true;
			state.version++;
		}
		requestRuntimeRender(server, key);
	}

	private static void openCallContactPicker(MinecraftServer server, ScreenRuntimeKey key) {
		MaxRuntimeState state = MAX_STATES.get(key);
		MaxCallSession call = currentCall(key);
		if (state == null || call == null || callPhase(call, key) != MaxCallPhase.ACTIVE) {
			return;
		}
		synchronized (state) {
			state.callContactPickerOpen = true;
			state.callContactPickerScroll = 0;
			state.cameraPickerOpen = false;
			state.callMenuOpen = true;
			state.version++;
		}
		requestRuntimeRender(server, key);
	}

	private static void cycleSelectedCamera(MinecraftServer server, ScreenComponent component) {
		if (server == null || component == null) {
			return;
		}
		MaxRuntimeState state = MAX_STATES.get(component.runtimeKey());
		if (state == null) {
			return;
		}
		List<LiveCameraReference> cameras = connectedCameraReferences(server, component);
		if (cameras.isEmpty()) {
			synchronized (state) {
				state.selectedCameraUrl = "";
				state.statusText = "Нет подключённых камер";
				state.version++;
			}
			requestRuntimeRender(server, component.runtimeKey());
			return;
		}
		List<String> urls = cameras.stream().map(MonitorScreenLiveSources::liveCameraGalleryUrl).toList();
		synchronized (state) {
			int index = urls.indexOf(state.selectedCameraUrl);
			state.selectedCameraUrl = urls.get(Math.floorMod(index + 1, urls.size()));
			state.statusText = "";
			state.version++;
		}
		persistState(server, component.runtimeKey(), state);
		RendererBotCameraSystem.stopLiveStream(maxLocalVideoStreamOwnerId(component.runtimeKey()));
		clearLocalFrame(component.runtimeKey());
		requestRuntimeRender(server, component.runtimeKey());
		requestPeerRenderAfterLocalCameraChange(server, component.runtimeKey());
	}

	private static void selectCamera(MinecraftServer server, ScreenComponent component, MaxRuntimeState state, MaxCameraOptionSnapshot option) {
		if (server == null || component == null || state == null || option == null) {
			return;
		}
		synchronized (state) {
			state.selectedCameraUrl = option.url();
			state.cameraPickerOpen = false;
			state.statusText = "";
			state.version++;
		}
		persistState(server, component.runtimeKey(), state);
		RendererBotCameraSystem.stopLiveStream(maxLocalVideoStreamOwnerId(component.runtimeKey()));
		clearLocalFrame(component.runtimeKey());
		requestRuntimeRender(server, component.runtimeKey());
		requestPeerRenderAfterLocalCameraChange(server, component.runtimeKey());
	}

	private static void selectMicrophone(MinecraftServer server, ScreenRuntimeKey key, MaxRuntimeState state, MaxMicrophoneOptionSnapshot option) {
		if (server == null || key == null || state == null || option == null) {
			return;
		}
		synchronized (state) {
			state.selectedMicrophoneIndex = option.index();
			state.selectedMicrophoneKey = option.deviceKey() == null ? "" : option.deviceKey();
			state.cameraPickerOpen = false;
			state.statusText = "";
			state.version++;
		}
		persistState(server, key, state);
		requestRuntimeRender(server, key);
		requestPeerRender(server, key);
		MicrophoneSystem.onMaxCallStateChanged(server);
	}

	private static void scrollCallCameraPicker(MinecraftServer server, ScreenRuntimeKey key, MaxRuntimeState state, UiLayout layout, int cameraCount, int microphoneCount, int delta) {
		if (server == null || key == null || state == null || layout == null || delta == 0) {
			return;
		}
		boolean changed = false;
		synchronized (state) {
			normalizeCallDevicePickerScrollLocked(state, layout, cameraCount, microphoneCount);
			int nextScroll = clampInt(state.cameraPickerScroll + delta, 0, maxCallDeviceCameraScroll(layout, cameraCount));
			if (nextScroll != state.cameraPickerScroll) {
				state.cameraPickerScroll = nextScroll;
				state.version++;
				changed = true;
			}
		}
		if (changed) {
			requestRuntimeRender(server, key);
		}
	}

	private static void scrollCallMicrophonePicker(MinecraftServer server, ScreenRuntimeKey key, MaxRuntimeState state, UiLayout layout, int cameraCount, int microphoneCount, int delta) {
		if (server == null || key == null || state == null || layout == null || delta == 0) {
			return;
		}
		boolean changed = false;
		synchronized (state) {
			normalizeCallDevicePickerScrollLocked(state, layout, cameraCount, microphoneCount);
			int nextScroll = clampInt(state.microphonePickerScroll + delta, 0, maxCallDeviceMicrophoneScroll(layout, microphoneCount));
			if (nextScroll != state.microphonePickerScroll) {
				state.microphonePickerScroll = nextScroll;
				state.version++;
				changed = true;
			}
		}
		if (changed) {
			requestRuntimeRender(server, key);
		}
	}

	private static void normalizeCallDevicePickerScrollLocked(MaxRuntimeState state, UiLayout layout, int cameraCount, int microphoneCount) {
		if (state == null || layout == null) {
			return;
		}
		state.cameraPickerScroll = clampInt(state.cameraPickerScroll, 0, maxCallDeviceCameraScroll(layout, cameraCount));
		state.microphonePickerScroll = clampInt(state.microphonePickerScroll, 0, maxCallDeviceMicrophoneScroll(layout, microphoneCount));
	}

	private static void normalizeAvatarPickerScrollLocked(MaxRuntimeState state, UiLayout layout, int candidateCount) {
		if (state == null || layout == null) {
			return;
		}
		int columns = maxAvatarPickerColumns(layout);
		int nextScroll = clampInt(state.avatarPickerScroll, 0, maxAvatarPickerScroll(layout, candidateCount));
		if (columns > 1) {
			nextScroll -= Math.floorMod(nextScroll, columns);
		}
		state.avatarPickerScroll = nextScroll;
	}

	private static void normalizeRingtonePickerScrollLocked(MaxRuntimeState state, UiLayout layout, int candidateCount) {
		if (state == null || layout == null) {
			return;
		}
		state.ringtonePickerScroll = clampInt(state.ringtonePickerScroll, 0, maxRingtonePickerScroll(layout, candidateCount));
	}

	private static void normalizeCallContactPickerScrollLocked(MaxRuntimeState state, UiLayout layout, int contactCount) {
		if (state == null || layout == null) {
			return;
		}
		state.callContactPickerScroll = clampInt(state.callContactPickerScroll, 0, maxCallContactPickerScroll(layout, contactCount));
	}

	private static void normalizeFileSharePickerScrollLocked(MaxRuntimeState state, UiLayout layout, int contactCount) {
		if (state == null || layout == null) {
			return;
		}
		state.fileSharePickerScroll = clampInt(state.fileSharePickerScroll, 0, maxContactPickerScroll(layout, contactCount));
	}

	private static void normalizeNotificationScrollLocked(MaxRuntimeState state, UiLayout layout) {
		if (state == null || layout == null) {
			return;
		}
		state.notificationScroll = clampInt(state.notificationScroll, 0, maxNotificationScroll(layout, maxNotificationRawIndexesForActiveContactLocked(state).size()));
	}

	private static void scrollAvatarPicker(MinecraftServer server, ScreenRuntimeKey key, MaxRuntimeState state, UiLayout layout, int candidateCount, int deltaRows) {
		if (server == null || key == null || state == null || layout == null || deltaRows == 0) {
			return;
		}
		boolean changed = false;
		synchronized (state) {
			normalizeAvatarPickerScrollLocked(state, layout, candidateCount);
			int step = maxAvatarPickerColumns(layout);
			int nextScroll = clampInt(state.avatarPickerScroll + deltaRows * step, 0, maxAvatarPickerScroll(layout, candidateCount));
			if (nextScroll != state.avatarPickerScroll) {
				state.avatarPickerScroll = nextScroll;
				state.version++;
				changed = true;
			}
		}
		if (changed) {
			requestRuntimeRender(server, key);
		}
	}

	private static void scrollRingtonePicker(MinecraftServer server, ScreenRuntimeKey key, MaxRuntimeState state, UiLayout layout, int candidateCount, int delta) {
		if (server == null || key == null || state == null || layout == null || delta == 0) {
			return;
		}
		boolean changed = false;
		synchronized (state) {
			normalizeRingtonePickerScrollLocked(state, layout, candidateCount);
			int nextScroll = clampInt(state.ringtonePickerScroll + delta, 0, maxRingtonePickerScroll(layout, candidateCount));
			if (nextScroll != state.ringtonePickerScroll) {
				state.ringtonePickerScroll = nextScroll;
				state.version++;
				changed = true;
			}
		}
		if (changed) {
			requestRuntimeRender(server, key);
		}
	}

	private static void scrollCallContactPicker(MinecraftServer server, ScreenRuntimeKey key, MaxRuntimeState state, UiLayout layout, int contactCount, int delta) {
		if (server == null || key == null || state == null || layout == null || delta == 0) {
			return;
		}
		boolean changed = false;
		synchronized (state) {
			normalizeCallContactPickerScrollLocked(state, layout, contactCount);
			int nextScroll = clampInt(state.callContactPickerScroll + delta, 0, maxCallContactPickerScroll(layout, contactCount));
			if (nextScroll != state.callContactPickerScroll) {
				state.callContactPickerScroll = nextScroll;
				state.version++;
				changed = true;
			}
		}
		if (changed) {
			requestRuntimeRender(server, key);
		}
	}

	private static void scrollFileSharePicker(MinecraftServer server, ScreenRuntimeKey key, MaxRuntimeState state, UiLayout layout, int contactCount, int delta) {
		if (server == null || key == null || state == null || layout == null || delta == 0) {
			return;
		}
		boolean changed = false;
		synchronized (state) {
			normalizeFileSharePickerScrollLocked(state, layout, contactCount);
			int nextScroll = clampInt(state.fileSharePickerScroll + delta, 0, maxContactPickerScroll(layout, contactCount));
			if (nextScroll != state.fileSharePickerScroll) {
				state.fileSharePickerScroll = nextScroll;
				state.version++;
				changed = true;
			}
		}
		if (changed) {
			requestRuntimeRender(server, key);
		}
	}

	private static void scrollNotifications(MinecraftServer server, ScreenRuntimeKey key, MaxRuntimeState state, int maxScroll, int deltaPixels) {
		if (server == null || key == null || state == null || deltaPixels == 0) {
			return;
		}
		boolean changed = false;
		synchronized (state) {
			int nextScroll = clampInt(state.notificationScroll + deltaPixels, 0, Math.max(0, maxScroll));
			if (nextScroll != state.notificationScroll) {
				state.notificationScroll = nextScroll;
				state.version++;
				changed = true;
			}
		}
		if (changed) {
			requestRuntimeRender(server, key);
		}
	}

	private static <T> List<T> visibleOverlaySlice(List<T> items, int scroll, int capacity) {
		if (items == null || items.isEmpty()) {
			return List.of();
		}
		int safeCapacity = Math.max(1, capacity);
		int safeScroll = clampInt(scroll, 0, Math.max(0, items.size() - safeCapacity));
		return List.copyOf(items.subList(safeScroll, Math.min(items.size(), safeScroll + safeCapacity)));
	}

	private static void requestPeerRenderAfterLocalCameraChange(MinecraftServer server, ScreenRuntimeKey key) {
		MaxCallSession call = currentCall(key);
		List<ScreenRuntimeKey> peers = call != null ? call.otherParticipants(key) : List.of();
		if (peers.isEmpty()) {
			return;
		}
		for (ScreenRuntimeKey peer : peers) {
			RendererBotCameraSystem.stopLiveStream(maxVideoStreamOwnerId(peer));
			clearRemoteFrame(peer);
			requestRuntimeRender(server, peer);
		}
	}

	private static void requestPeerRender(MinecraftServer server, ScreenRuntimeKey key) {
		MaxCallSession call = currentCall(key);
		List<ScreenRuntimeKey> peers = call != null ? call.otherParticipants(key) : List.of();
		for (ScreenRuntimeKey peer : peers) {
			requestRuntimeRender(server, peer);
		}
	}

	private static void refreshRemoteVideo(MinecraftServer server, ScreenComponent viewerComponent, MaxRuntimeState peerState) {
		if (server == null || viewerComponent == null || peerState == null) {
			return;
		}
		boolean cameraEnabled;
		String cameraUrl;
		synchronized (peerState) {
			cameraEnabled = peerState.cameraEnabled;
			cameraUrl = peerState.selectedCameraUrl;
		}
		if (!cameraEnabled || cameraUrl == null || cameraUrl.isBlank()) {
			RendererBotCameraSystem.stopLiveStream(maxVideoStreamOwnerId(viewerComponent.runtimeKey()));
			clearRemoteFrame(viewerComponent.runtimeKey());
			return;
		}
		LiveCameraReference cameraRef = liveCameraGalleryReference(cameraUrl, viewerComponent.runtimeKey().dimension());
		if (cameraRef == null) {
			RendererBotCameraSystem.stopLiveStream(maxVideoStreamOwnerId(viewerComponent.runtimeKey()));
			clearRemoteFrame(viewerComponent.runtimeKey());
			return;
		}
		startRemoteVideoStream(server, viewerComponent, cameraRef, cameraUrl);
	}

	private static void refreshLocalVideo(MinecraftServer server, ScreenComponent component, MaxRuntimeState state) {
		if (server == null || component == null || state == null) {
			return;
		}
		boolean cameraEnabled;
		String cameraUrl;
		synchronized (state) {
			cameraEnabled = state.cameraEnabled;
			cameraUrl = state.selectedCameraUrl;
		}
		if (!cameraEnabled || cameraUrl == null || cameraUrl.isBlank()) {
			RendererBotCameraSystem.stopLiveStream(maxLocalVideoStreamOwnerId(component.runtimeKey()));
			clearLocalFrame(component.runtimeKey());
			return;
		}
		LiveCameraReference cameraRef = liveCameraGalleryReference(cameraUrl, component.runtimeKey().dimension());
		if (cameraRef == null) {
			RendererBotCameraSystem.stopLiveStream(maxLocalVideoStreamOwnerId(component.runtimeKey()));
			clearLocalFrame(component.runtimeKey());
			return;
		}
		startVideoStream(server, component, cameraRef, cameraUrl, true);
	}

	private static void startRemoteVideoStream(MinecraftServer server, ScreenComponent viewerComponent, LiveCameraReference cameraRef, String sourceUrl) {
		startVideoStream(server, viewerComponent, cameraRef, sourceUrl, false);
	}

	private static void startVideoStream(MinecraftServer server, ScreenComponent viewerComponent, LiveCameraReference cameraRef, String sourceUrl, boolean localStream) {
		ServerLevel screenLevel = server.getLevel(viewerComponent.runtimeKey().dimension());
		if (screenLevel == null || cameraRef == null) {
			clearVideoFrame(viewerComponent.runtimeKey(), localStream);
			return;
		}
		int fullWidth = Math.max(1, viewerComponent.width()) * MAP_SIZE;
		int fullHeight = Math.max(1, viewerComponent.height()) * MAP_SIZE;
		String ownerId = localStream ? maxLocalVideoStreamOwnerId(viewerComponent.runtimeKey()) : maxVideoStreamOwnerId(viewerComponent.runtimeKey());
		if (cameraRef.sourceType() == LiveCameraSourceType.DRONE) {
			DroneSystem.DroneLiveFeedState droneState = cameraRef.sourceUuid() != null
					? DroneSystem.resolveLiveFeedState(server, cameraRef.sourceUuid(), cameraRef.dimension(), cameraRef.pos())
					: null;
			if (droneState == null) {
				clearVideoFrame(viewerComponent.runtimeKey(), localStream);
				return;
			}
			ServerLevel droneLevel = server.getLevel(droneState.dimension());
			if (droneLevel == null) {
				clearVideoFrame(viewerComponent.runtimeKey(), localStream);
				return;
			}
			RendererBotCameraSystem.ensureLiveStream(
					ownerId,
					droneLevel,
					null,
					droneState.expectedX(),
					droneState.expectedY(),
					droneState.expectedZ(),
					droneState.yaw(),
					droneState.pitch(),
					droneState.followEntityUuid(),
					droneState.hiddenEntityUuids(),
					droneState.omnidirectionalChunkLoading(),
					fullWidth,
					fullHeight,
					LIVE_CAMERA_FOV_DEGREES,
					LIVE_CAMERA_TARGET_FPS,
					frame -> onVideoFrame(server, viewerComponent.runtimeKey(), sourceUrl, fullWidth, fullHeight, frame.pixels(), localStream),
					error -> onVideoFailure(server, viewerComponent.runtimeKey(), error, localStream)
			);
			return;
		}
		ServerLevel cameraLevel = server.getLevel(cameraRef.dimension());
		BlockPos cameraPos = cameraRef.pos();
		CameraRelocationSystem.MobileCameraFeed mobileFeed = cameraLevel != null
				? CameraRelocationSystem.mobileCameraFeed(cameraLevel, cameraPos)
				: null;
		if (mobileFeed != null) {
			RendererBotCameraSystem.ensureLiveStream(
					ownerId,
					cameraLevel,
					null,
					mobileFeed.expectedX(),
					mobileFeed.expectedY(),
					mobileFeed.expectedZ(),
					mobileFeed.yaw(),
					mobileFeed.pitch(),
					mobileFeed.followEntityUuid(),
					mobileFeed.hiddenEntityUuids(),
					true,
					fullWidth,
					fullHeight,
					LIVE_CAMERA_FOV_DEGREES,
					LIVE_CAMERA_TARGET_FPS,
					frame -> onVideoFrame(server, viewerComponent.runtimeKey(), sourceUrl, fullWidth, fullHeight, frame.pixels(), localStream),
					error -> onVideoFailure(server, viewerComponent.runtimeKey(), error, localStream)
			);
			return;
		}
		if (cameraLevel == null || cameraPos == null || !cameraLevel.hasChunkAt(cameraPos) || !isCameraBlock(cameraLevel, cameraPos)) {
			clearVideoFrame(viewerComponent.runtimeKey(), localStream);
			return;
		}
		BlockState cameraState = cameraLevel.getBlockState(cameraPos);
		LiveCameraPose pose = liveCameraCapturePose(cameraLevel, cameraPos, cameraState);
		Vec3 origin = pose.origin();
		RendererBotCameraSystem.ensureLiveStream(
				ownerId,
				cameraLevel,
				cameraPos,
				origin.x,
				origin.y - RENDERER_BOT_EYE_HEIGHT,
				origin.z,
				pose.yaw(),
				pose.pitch(),
				fullWidth,
				fullHeight,
				LIVE_CAMERA_FOV_DEGREES,
				LIVE_CAMERA_TARGET_FPS,
				frame -> onVideoFrame(server, viewerComponent.runtimeKey(), sourceUrl, fullWidth, fullHeight, frame.pixels(), localStream),
				error -> onVideoFailure(server, viewerComponent.runtimeKey(), error, localStream)
		);
	}

	private static void onRemoteVideoFrame(MinecraftServer server, ScreenRuntimeKey key, String sourceUrl, int width, int height, byte[] pixels) {
		onVideoFrame(server, key, sourceUrl, width, height, pixels, false);
	}

	private static void onVideoFrame(MinecraftServer server, ScreenRuntimeKey key, String sourceUrl, int width, int height, byte[] pixels, boolean localStream) {
		if (server == null || key == null || pixels == null || pixels.length == 0) {
			return;
		}
		ensureExecutors();
		liveCameraExecutor.execute(() -> {
			BufferedImage image = mapPaletteImage(pixels, width, height);
			server.execute(() -> {
				MaxRuntimeState state = MAX_STATES.get(key);
				MaxCallSession call = currentCall(key);
				if (state == null || call == null) {
					RendererBotCameraSystem.stopLiveStream(localStream ? maxLocalVideoStreamOwnerId(key) : maxVideoStreamOwnerId(key));
					return;
				}
				synchronized (state) {
					if (localStream && (!state.cameraEnabled || !Objects.equals(state.selectedCameraUrl, sourceUrl))) {
						return;
					}
					if (localStream) {
						state.localVideoUrl = sourceUrl;
						state.localFrame = image;
					} else {
						state.remoteVideoUrl = sourceUrl;
						state.remoteFrame = image;
					}
					state.version++;
				}
				requestRuntimeRender(server, key);
			});
		});
	}

	private static void onRemoteVideoFailure(MinecraftServer server, ScreenRuntimeKey key, String error) {
		onVideoFailure(server, key, error, false);
	}

	private static void onVideoFailure(MinecraftServer server, ScreenRuntimeKey key, String error, boolean localStream) {
		MaxRuntimeState state = MAX_STATES.get(key);
		if (state == null) {
			return;
		}
		synchronized (state) {
			state.statusText = error == null || error.isBlank() ? "Видео недоступно" : error;
			if (localStream) {
				state.localFrame = null;
				state.localVideoUrl = "";
			} else {
				state.remoteFrame = null;
				state.remoteVideoUrl = "";
			}
			state.version++;
		}
		requestRuntimeRender(server, key);
	}

	private static void clearRemoteFrame(ScreenRuntimeKey key) {
		clearVideoFrame(key, false);
	}

	private static void clearLocalFrame(ScreenRuntimeKey key) {
		clearVideoFrame(key, true);
	}

	private static void clearVideoFrame(ScreenRuntimeKey key, boolean localStream) {
		MaxRuntimeState state = MAX_STATES.get(key);
		if (state == null) {
			return;
		}
		synchronized (state) {
			BufferedImage frame = localStream ? state.localFrame : state.remoteFrame;
			String url = localStream ? state.localVideoUrl : state.remoteVideoUrl;
			if (frame == null && (url == null || url.isBlank())) {
				return;
			}
			if (localStream) {
				state.localFrame = null;
				state.localVideoUrl = "";
			} else {
				state.remoteFrame = null;
				state.remoteVideoUrl = "";
			}
			state.version++;
		}
	}

	private static boolean handleAvatarPickerTouch(
			MinecraftServer server,
			ScreenComponent component,
			MaxRuntimeState state,
			UiLayout layout,
			UiPoint touchPoint
	) {
		if (maxOverlayCloseRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			synchronized (state) {
				state.avatarPickerOpen = false;
				state.version++;
			}
			requestRuntimeRender(server, component.runtimeKey());
			return true;
		}
		if (!maxAvatarPickerPanelRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			synchronized (state) {
				state.avatarPickerOpen = false;
				state.version++;
			}
			requestRuntimeRender(server, component.runtimeKey());
			return true;
		}
		List<MaxAvatarCandidateSnapshot> candidates = avatarCandidates(component);
		int avatarScroll;
		synchronized (state) {
			normalizeAvatarPickerScrollLocked(state, layout, candidates.size());
			avatarScroll = state.avatarPickerScroll;
		}
		int index = maxAvatarCandidateIndexAt(layout, candidates.size(), avatarScroll, touchPoint);
		if (index >= 0 && index < candidates.size()) {
			MaxAvatarCandidateSnapshot candidate = candidates.get(index);
			MonitorMediaApp.LoadedMedia avatarMedia = loadAvatarMedia(component, candidate.url(), candidate.localMediaKey());
			BufferedImage avatarFrame = avatarFrame(avatarMedia, 0, candidate.preview());
			synchronized (state) {
				state.avatarUrl = candidate.url();
				state.avatarLocalMediaKey = candidate.localMediaKey();
				state.avatarMedia = avatarMedia;
				state.avatarFrame = avatarFrame;
				state.avatarAnimationStartedAtMillis = System.currentTimeMillis();
				state.avatarRenderScheduled = false;
				state.avatarPickerOpen = false;
				state.statusText = "Аватар обновлён";
				state.version++;
			}
			persistState(server, component.runtimeKey(), state);
			requestRuntimeRender(server, component.runtimeKey());
			return true;
		}
		return true;
	}

	private static boolean handleRingtonePickerTouch(
			MinecraftServer server,
			ScreenComponent component,
			MaxRuntimeState state,
			UiLayout layout,
			UiPoint touchPoint
	) {
		if (maxOverlayCloseRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			synchronized (state) {
				state.ringtonePickerOpen = false;
				state.version++;
			}
			requestRuntimeRender(server, component.runtimeKey());
			return true;
		}
		if (!maxAvatarPickerPanelRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			synchronized (state) {
				state.ringtonePickerOpen = false;
				state.version++;
			}
			requestRuntimeRender(server, component.runtimeKey());
			return true;
		}
		List<MaxRingtoneCandidateSnapshot> candidates = ringtoneCandidates(component, state);
		int ringtoneScroll;
		synchronized (state) {
			normalizeRingtonePickerScrollLocked(state, layout, candidates.size());
			ringtoneScroll = state.ringtonePickerScroll;
		}
		int index = maxRingtoneCandidateIndexAt(layout, candidates.size(), ringtoneScroll, touchPoint);
		if (index < 0 || index >= candidates.size()) {
			return true;
		}
		MaxRingtoneCandidateSnapshot candidate = candidates.get(index);
		UiRect row = maxRingtoneCandidateRect(layout, index - ringtoneScroll);
		if (maxRingtoneCandidatePlayRect(row, layout).contains(touchPoint.x(), touchPoint.y())) {
			toggleRingtonePreview(server, component, state, candidate);
			return true;
		}
		if (maxRingtoneCandidateSelectRect(row, layout).contains(touchPoint.x(), touchPoint.y())) {
			selectRingtone(server, component, state, candidate);
			return true;
		}
		return true;
	}

	private static void toggleSelectedRingtonePreview(MinecraftServer server, ScreenComponent component, MaxRuntimeState state) {
		if (component == null || state == null) {
			return;
		}
		MaxRingtoneCandidateSnapshot selected;
		synchronized (state) {
			String url = state.ringtoneUrl == null || state.ringtoneUrl.isBlank() ? MAX_DEFAULT_RINGTONE_URL : state.ringtoneUrl;
			String title = state.ringtoneTitle == null || state.ringtoneTitle.isBlank() ? "Стандартный рингтон" : state.ringtoneTitle;
			selected = ringtoneCandidate(state, title, "MAX", url, state.ringtoneLocalMediaKey);
		}
		toggleRingtonePreview(server, component, state, selected);
	}

	private static void toggleRingtonePreview(MinecraftServer server, ScreenComponent component, MaxRuntimeState state, MaxRingtoneCandidateSnapshot candidate) {
		if (component == null || state == null || candidate == null) {
			return;
		}
		synchronized (state) {
			boolean same = state.ringtonePreviewPlaying
					&& Objects.equals(Objects.toString(state.ringtonePreviewUrl, MAX_DEFAULT_RINGTONE_URL), Objects.toString(candidate.url(), MAX_DEFAULT_RINGTONE_URL))
					&& Objects.equals(Objects.toString(state.ringtonePreviewLocalMediaKey, ""), Objects.toString(candidate.localMediaKey(), ""));
			if (same) {
				state.ringtonePreviewPlaying = false;
			} else {
				state.ringtonePreviewPlaying = true;
				state.ringtonePreviewUrl = candidate.url() == null || candidate.url().isBlank() ? MAX_DEFAULT_RINGTONE_URL : candidate.url();
				state.ringtonePreviewLocalMediaKey = candidate.localMediaKey() == null ? "" : candidate.localMediaKey();
				state.ringtonePreviewTitle = candidate.title() == null ? "" : candidate.title();
				state.ringtonePreviewStartedAtMillis = System.currentTimeMillis();
			}
			state.version++;
		}
		refreshConnectedSpeakersNow(server, component);
		requestRuntimeRender(server, component.runtimeKey());
	}

	private static void selectRingtone(MinecraftServer server, ScreenComponent component, MaxRuntimeState state, MaxRingtoneCandidateSnapshot candidate) {
		if (component == null || state == null || candidate == null) {
			return;
		}
		synchronized (state) {
			boolean defaultRingtone = candidate.url() == null || candidate.url().isBlank() || Objects.equals(candidate.url(), MAX_DEFAULT_RINGTONE_URL);
			state.ringtoneUrl = defaultRingtone ? "" : candidate.url();
			state.ringtoneLocalMediaKey = defaultRingtone || candidate.localMediaKey() == null ? "" : candidate.localMediaKey();
			state.ringtoneTitle = defaultRingtone || candidate.title() == null ? "" : candidate.title();
			state.ringtonePickerOpen = false;
			state.statusText = "Рингтон выбран";
			state.version++;
		}
		persistState(server, component.runtimeKey(), state);
		requestRuntimeRender(server, component.runtimeKey());
	}

	private static void removeContact(MinecraftServer server, ScreenRuntimeKey key, MaxRuntimeState state, String contactCode) {
		if (key == null || state == null || contactCode == null || contactCode.isBlank()) {
			return;
		}
		boolean removed;
		synchronized (state) {
			removed = state.contacts.remove(contactCode);
			if (removed) {
				state.statusText = "Контакт удалён";
				state.version++;
			}
		}
		if (removed) {
			persistState(server, key, state);
			requestRuntimeRender(server, key);
		}
	}

	private static boolean handleCallCameraPickerTouch(
			MinecraftServer server,
			ScreenComponent component,
			MaxRuntimeState state,
			UiLayout layout,
			UiPoint touchPoint
	) {
		if (maxOverlayCloseRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			synchronized (state) {
				state.cameraPickerOpen = false;
				state.version++;
			}
			requestRuntimeRender(server, component.runtimeKey());
			return true;
		}
		if (!maxAvatarPickerPanelRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			synchronized (state) {
				state.cameraPickerOpen = false;
				state.version++;
			}
			requestRuntimeRender(server, component.runtimeKey());
			return true;
		}
		List<LiveCameraReference> cameraRefs = connectedCameraReferences(server, component);
		List<MicrophoneSystem.ScreenMicrophoneDevice> microphones = MicrophoneSystem.connectedMicrophoneDevices(server, component.runtimeKey());
		if (maxCallDeviceCameraScrollLeftRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			scrollCallCameraPicker(server, component.runtimeKey(), state, layout, cameraRefs.size(), microphones.size(), -maxCallDeviceCameraScrollStep(layout));
			return true;
		}
		if (maxCallDeviceCameraScrollRightRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			scrollCallCameraPicker(server, component.runtimeKey(), state, layout, cameraRefs.size(), microphones.size(), maxCallDeviceCameraScrollStep(layout));
			return true;
		}
		if (maxCallDeviceMicrophoneScrollUpRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			scrollCallMicrophonePicker(server, component.runtimeKey(), state, layout, cameraRefs.size(), microphones.size(), -1);
			return true;
		}
		if (maxCallDeviceMicrophoneScrollDownRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			scrollCallMicrophonePicker(server, component.runtimeKey(), state, layout, cameraRefs.size(), microphones.size(), 1);
			return true;
		}
		int cameraScroll;
		int microphoneScroll;
		synchronized (state) {
			normalizeSelectedMicrophoneLocked(state, microphones);
			normalizeCallDevicePickerScrollLocked(state, layout, cameraRefs.size(), microphones.size());
			cameraScroll = state.cameraPickerScroll;
			microphoneScroll = state.microphonePickerScroll;
		}
		List<MaxCameraOptionSnapshot> options = cameraOptions(server, state, cameraRefs);
		int index = maxCallDeviceCameraIndexAt(layout, options.size(), cameraScroll, touchPoint);
		if (index >= 0 && index < options.size()) {
			selectCamera(server, component, state, options.get(index));
			return true;
		}
		List<MaxMicrophoneOptionSnapshot> microphoneOptions = microphoneOptions(state, microphones);
		int microphoneIndex = maxCallDeviceMicrophoneIndexAt(layout, microphoneOptions.size(), microphoneScroll, touchPoint);
		if (microphoneIndex >= 0 && microphoneIndex < microphoneOptions.size()) {
			selectMicrophone(server, component.runtimeKey(), state, microphoneOptions.get(microphoneIndex));
			return true;
		}
		return true;
	}

	private static boolean handleCallContactPickerTouch(
			ServerPlayer player,
			MinecraftServer server,
			ScreenComponent component,
			MaxRuntimeState state,
			MaxCallSession call,
			UiLayout layout,
			UiPoint touchPoint
	) {
		if (maxCallContactPickerCloseRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			synchronized (state) {
				state.callContactPickerOpen = false;
				state.version++;
			}
			requestRuntimeRender(server, component.runtimeKey());
			return true;
		}
		if (!maxAvatarPickerPanelRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			synchronized (state) {
				state.callContactPickerOpen = false;
				state.version++;
			}
			requestRuntimeRender(server, component.runtimeKey());
			return true;
		}
		if (player != null && maxCallContactPickerAddRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			PENDING_CALL_CONTACT_INVITES.put(player.getUUID(), component.runtimeKey());
			PENDING_CONTACT_CODE_INPUTS.remove(player.getUUID());
			PENDING_ACCOUNT_NAME_INPUTS.remove(player.getUUID());
			synchronized (state) {
				state.statusText = MAX_PENDING_CALL_INVITE_STATUS;
				state.version++;
			}
			player.displayClientMessage(maxCallContactPromptMessage(player), true);
			requestRuntimeRender(server, component.runtimeKey());
			return true;
		}
		List<MaxContactSnapshot> contacts = contactInviteCandidates(server, state, call, component.runtimeKey());
		int contactScroll;
		synchronized (state) {
			normalizeCallContactPickerScrollLocked(state, layout, contacts.size());
			contactScroll = state.callContactPickerScroll;
		}
		int index = maxCallContactPickerIndexAt(layout, contacts.size(), contactScroll, touchPoint);
		if (index >= 0 && index < contacts.size()) {
			inviteContactToCall(server, component.runtimeKey(), contacts.get(index).code());
			return true;
		}
		return true;
	}

	private static boolean handleFileSharePickerTouch(
			MinecraftServer server,
			ScreenComponent component,
			MaxRuntimeState state,
			UiLayout layout,
			UiPoint touchPoint
	) {
		if (maxOverlayCloseRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			synchronized (state) {
				clearFileShareDraftLocked(state);
				state.version++;
			}
			requestRuntimeRender(server, component.runtimeKey());
			return true;
		}
		if (!maxAvatarPickerPanelRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			synchronized (state) {
				clearFileShareDraftLocked(state);
				state.version++;
			}
			requestRuntimeRender(server, component.runtimeKey());
			return true;
		}
		if (maxFileShareSendRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			sendPendingFilesToSelectedContacts(server, component.runtimeKey());
			return true;
		}
		List<MaxFileShareContactSnapshot> contacts;
		synchronized (state) {
			contacts = fileShareContacts(server, state, component.runtimeKey());
		}
		int contactScroll;
		synchronized (state) {
			normalizeFileSharePickerScrollLocked(state, layout, contacts.size());
			contactScroll = state.fileSharePickerScroll;
		}
		int index = maxContactPickerIndexAt(layout, contacts.size(), contactScroll, touchPoint);
		if (index >= 0 && index < contacts.size()) {
			String code = contacts.get(index).code();
			synchronized (state) {
				if (state.fileShareSelectedContacts.contains(code)) {
					state.fileShareSelectedContacts.remove(code);
				} else {
					state.fileShareSelectedContacts.add(code);
				}
				state.statusText = "";
				state.version++;
			}
			requestRuntimeRender(server, component.runtimeKey());
		}
		return true;
	}

	private static boolean handleNotificationsTouch(
			MinecraftServer server,
			ScreenComponent component,
			MaxRuntimeState state,
		UiLayout layout,
		UiPoint touchPoint
	) {
		if (maxNotificationPopupCloseRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			synchronized (state) {
				closeNotificationsLocked(state);
				state.version++;
			}
			refreshConnectedSpeakersNow(server, component.runtimeKey());
			requestRuntimeRender(server, component.runtimeKey());
			return true;
		}
		if (!maxNotificationPopupRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			synchronized (state) {
				closeNotificationsLocked(state);
				state.version++;
			}
			refreshConnectedSpeakersNow(server, component.runtimeKey());
			requestRuntimeRender(server, component.runtimeKey());
			return true;
		}
		MaxNotificationHit hit = maxNotificationHitAt(layout, state, touchPoint);
		if (hit != null) {
			if (hit.acceptRect().contains(touchPoint.x(), touchPoint.y())) {
				acceptIncomingFile(server, component.runtimeKey(), hit.index());
				return true;
			}
			if (hit.declineRect().contains(touchPoint.x(), touchPoint.y())) {
				ignoreIncomingFile(server, component.runtimeKey(), hit.index());
				return true;
			}
			if (hit.previewRect().contains(touchPoint.x(), touchPoint.y())) {
				toggleNotificationPreview(server, component.runtimeKey(), state, hit.index());
				return true;
			}
			return true;
		}
		return true;
	}

	private static void clearFileShareDraftLocked(MaxRuntimeState state) {
		if (state == null) {
			return;
		}
		state.fileSharePickerOpen = false;
		state.fileSharePickerScroll = 0;
		state.fileShareFiles.clear();
		state.fileShareSelectedContacts.clear();
		state.statusText = "";
	}

	private static void sendPendingFilesToSelectedContacts(MinecraftServer server, ScreenRuntimeKey senderKey) {
		if (server == null || senderKey == null) {
			return;
		}
		MaxRuntimeState senderState = MAX_STATES.get(senderKey);
		if (senderState == null) {
			return;
		}
			String senderCode;
			String senderDisplayName;
			String senderAvatarUrl;
			String senderAvatarLocalMediaKey;
			List<MaxSharedGalleryFile> files;
			List<String> recipients;
			synchronized (senderState) {
				senderCode = normalizeAccountCode(senderState.accountCode);
			if (senderCode.isBlank()) {
				senderState.statusText = "Аккаунт MAX не готов";
				senderState.version++;
				requestRuntimeRender(server, senderKey);
				return;
				}
				senderDisplayName = senderState.accountName == null || senderState.accountName.isBlank() ? defaultAccountName(senderCode) : senderState.accountName;
				senderAvatarUrl = senderState.avatarUrl == null ? "" : senderState.avatarUrl;
				senderAvatarLocalMediaKey = senderState.avatarLocalMediaKey == null ? "" : senderState.avatarLocalMediaKey;
				files = List.copyOf(senderState.fileShareFiles);
				recipients = List.copyOf(senderState.fileShareSelectedContacts);
			if (files.isEmpty()) {
				senderState.statusText = "Нет файлов для отправки";
				senderState.version++;
				requestRuntimeRender(server, senderKey);
				return;
			}
			if (recipients.isEmpty()) {
				senderState.statusText = "Выбери хотя бы один контакт";
				senderState.version++;
				requestRuntimeRender(server, senderKey);
				return;
			}
			senderState.statusText = "Отправка...";
			senderState.version++;
		}
			requestRuntimeRender(server, senderKey);
			ensureExecutors();
			CompletableFuture
					.supplyAsync(() -> prepareFileDeliveries(senderCode, senderDisplayName, senderAvatarUrl, senderAvatarLocalMediaKey, recipients, files), mediaIoExecutor)
					.thenAccept(deliveries -> server.execute(() -> applyPreparedFileDeliveries(server, senderKey, deliveries)));
		}

		private static List<MaxPreparedFileDelivery> prepareFileDeliveries(
				String senderCode,
				String senderDisplayName,
				String senderAvatarUrl,
				String senderAvatarLocalMediaKey,
				List<String> recipients,
				List<MaxSharedGalleryFile> files
		) {
			String normalizedSenderCode = normalizeAccountCode(senderCode);
			if (normalizedSenderCode.isBlank() || recipients == null || recipients.isEmpty() || files == null || files.isEmpty()) {
				return List.of();
		}
		List<MaxPreparedFileDelivery> deliveries = new ArrayList<>();
		for (String recipient : recipients) {
			String normalizedRecipient = normalizeAccountCode(recipient);
			if (normalizedRecipient.isBlank()) {
				continue;
			}
			for (MaxSharedGalleryFile file : files) {
				if (file == null) {
					continue;
				}
				String id = UUID.randomUUID().toString();
				String transferLocalMediaKey = copyTransferLocalMedia(file, id);
				String url = !transferLocalMediaKey.isBlank() ? MAX_TRANSFER_URL_PREFIX + transferLocalMediaKey : file.url();
				deliveries.add(new MaxPreparedFileDelivery(
						normalizedRecipient,
						new MaxIncomingFile(
									id,
									normalizedSenderCode,
									senderDisplayName == null || senderDisplayName.isBlank() ? defaultAccountName(normalizedSenderCode) : senderDisplayName,
									senderAvatarUrl == null ? "" : senderAvatarUrl,
									senderAvatarLocalMediaKey == null ? "" : senderAvatarLocalMediaKey,
									file.title(),
								file.subtitle(),
								url,
								transferLocalMediaKey.isBlank() ? file.localMediaKey() : transferLocalMediaKey,
								file.kind(),
								System.currentTimeMillis()
						)
				));
			}
		}
		return deliveries.isEmpty() ? List.of() : List.copyOf(deliveries);
	}

	private static String copyTransferLocalMedia(MaxSharedGalleryFile file, String id) {
		if (file == null || id == null || id.isBlank()) {
			return "";
		}
		String sourceLocalMediaKey = file.localMediaKey() == null ? "" : file.localMediaKey().trim();
		if (sourceLocalMediaKey.isBlank()) {
			return "";
		}
		Path sourcePath = MonitorMediaApp.savedGalleryMediaFile(sourceLocalMediaKey);
		if (sourcePath == null) {
			return "";
		}
		try {
			return MonitorMediaApp.persistLocalGalleryFile(MAX_TRANSFER_LOCAL_KEY_PREFIX + id, sourcePath);
		} catch (Exception exception) {
			Lg2.LOGGER.debug("Failed to prepare MAX file transfer {}: {}", sourceLocalMediaKey, sanitizeMediaError(exception.getMessage()));
			return "";
		}
	}

	private static void applyPreparedFileDeliveries(MinecraftServer server, ScreenRuntimeKey senderKey, List<MaxPreparedFileDelivery> deliveries) {
		MaxRuntimeState senderState = MAX_STATES.get(senderKey);
		if (server == null || senderKey == null || senderState == null) {
			for (MaxPreparedFileDelivery delivery : deliveries == null ? List.<MaxPreparedFileDelivery>of() : deliveries) {
				if (delivery != null) {
					deleteTransferLocalMediaIfTemporary(delivery.file());
				}
			}
			return;
		}
		int delivered = 0;
		Set<ScreenRuntimeKey> changedRecipients = new HashSet<>();
		for (MaxPreparedFileDelivery delivery : deliveries == null ? List.<MaxPreparedFileDelivery>of() : deliveries) {
			if (delivery == null || delivery.file() == null) {
				continue;
			}
			String recipientCode = normalizeAccountCode(delivery.recipientCode());
			if (recipientCode.isBlank()) {
				deleteTransferLocalMediaIfTemporary(delivery.file());
				continue;
			}
			ScreenRuntimeKey recipientKey = ACCOUNT_INDEX.get(recipientCode);
			ScreenComponent recipientComponent = recipientKey != null ? resolveScreenComponent(server, recipientKey) : null;
			if (recipientKey == null || recipientComponent == null || !recipientComponent.powered()) {
				deleteTransferLocalMediaIfTemporary(delivery.file());
				continue;
			}
			MaxRuntimeState recipientState = ensureState(server, recipientComponent);
			if (recipientState == null) {
				deleteTransferLocalMediaIfTemporary(delivery.file());
				continue;
				}
				synchronized (recipientState) {
					String senderCode = normalizeAccountCode(delivery.file().senderCode());
					if (!senderCode.isBlank() && !Objects.equals(senderCode, recipientState.accountCode) && !recipientState.contacts.contains(senderCode)) {
						recipientState.contacts.add(senderCode);
					}
					recipientState.incomingFiles.add(delivery.file());
					recipientState.version++;
				}
			persistState(server, recipientKey, recipientState);
			playNotificationSound(server, recipientComponent);
			requestRuntimeRender(server, recipientKey);
			changedRecipients.add(recipientKey);
			delivered++;
		}
		synchronized (senderState) {
			clearFileShareDraftLocked(senderState);
			senderState.statusText = delivered > 0 ? "Отправлено: " + delivered : "Получатели недоступны";
			senderState.version++;
		}
		requestRuntimeRender(server, senderKey);
		for (ScreenRuntimeKey recipientKey : changedRecipients) {
			requestRuntimeRender(server, recipientKey);
		}
	}

	private static void acceptIncomingFile(MinecraftServer server, ScreenRuntimeKey key, int index) {
		if (server == null || key == null) {
			return;
		}
		MaxRuntimeState state = MAX_STATES.get(key);
		if (state == null) {
			return;
		}
		ScreenComponent component = resolveScreenComponent(server, key);
		UiLayout layout = component != null ? createUiLayout(component.width(), component.height()) : null;
		MaxIncomingFile incoming;
			synchronized (state) {
				if (state.incomingFiles.isEmpty()) {
					state.statusText = "Нет уведомлений";
					closeNotificationsLocked(state);
					state.version++;
					requestRuntimeRender(server, key);
					refreshConnectedSpeakersNow(server, key);
					return;
				}
			int safeIndex = clampInt(index, 0, state.incomingFiles.size() - 1);
			incoming = state.incomingFiles.remove(safeIndex);
			if (Objects.equals(notificationFileId(incoming), state.notificationPreviewFileId)) {
				clearNotificationPreviewLocked(state);
			}
			pruneIncomingPreviewCacheLocked(state);
			state.statusText = saveIncomingFileToGallery(server, key, incoming) ? "Файл сохранён" : "Не удалось сохранить файл";
			if (state.incomingFiles.isEmpty() || incomingCountForSenderLocked(state, state.notificationContactCode) <= 0) {
				closeNotificationsLocked(state);
			} else if (layout != null) {
				normalizeNotificationScrollLocked(state, layout);
			}
			state.version++;
		}
		persistState(server, key, state);
		refreshConnectedSpeakersNow(server, key);
		requestRuntimeRender(server, key);
	}

	private static void ignoreIncomingFile(MinecraftServer server, ScreenRuntimeKey key, int index) {
		if (server == null || key == null) {
			return;
		}
		MaxRuntimeState state = MAX_STATES.get(key);
		if (state == null) {
			return;
		}
		ScreenComponent component = resolveScreenComponent(server, key);
		UiLayout layout = component != null ? createUiLayout(component.width(), component.height()) : null;
		MaxIncomingFile ignored;
			synchronized (state) {
				if (state.incomingFiles.isEmpty()) {
					state.statusText = "Нет уведомлений";
					closeNotificationsLocked(state);
					state.version++;
					requestRuntimeRender(server, key);
					refreshConnectedSpeakersNow(server, key);
					return;
				}
			int safeIndex = clampInt(index, 0, state.incomingFiles.size() - 1);
			ignored = state.incomingFiles.remove(safeIndex);
			if (Objects.equals(notificationFileId(ignored), state.notificationPreviewFileId)) {
				clearNotificationPreviewLocked(state);
			}
			pruneIncomingPreviewCacheLocked(state);
			state.statusText = "Файл отклонён";
			if (state.incomingFiles.isEmpty() || incomingCountForSenderLocked(state, state.notificationContactCode) <= 0) {
				closeNotificationsLocked(state);
			} else if (layout != null) {
				normalizeNotificationScrollLocked(state, layout);
			}
			state.version++;
		}
		deleteTransferLocalMediaIfTemporary(ignored);
		persistState(server, key, state);
		refreshConnectedSpeakersNow(server, key);
		requestRuntimeRender(server, key);
	}

	private static boolean saveIncomingFileToGallery(MinecraftServer server, ScreenRuntimeKey key, MaxIncomingFile incoming) {
		if (server == null || key == null || incoming == null) {
			return false;
		}
		String url = incoming.url() == null || incoming.url().isBlank()
				? MAX_TRANSFER_URL_PREFIX + Objects.toString(incoming.localMediaKey(), "")
				: incoming.url();
		if (url == null || url.isBlank()) {
			return false;
		}
		GalleryItemKind kind = effectiveGalleryItemKind(url, incoming.localMediaKey(), incoming.kind());
		PersistedGalleryItem persisted = new PersistedGalleryItem(
				incoming.title() == null || incoming.title().isBlank() ? defaultSharedFileTitle(kind) : incoming.title(),
				incoming.subtitle() == null ? "" : incoming.subtitle(),
				url,
				kind,
				incoming.localMediaKey() == null ? "" : incoming.localMediaKey()
		);
		MediaRuntimeState mediaState = MEDIA_STATES.computeIfAbsent(
				key,
				ignored -> MediaRuntimeState.fresh(ScreenViewMode.GALLERY, "", () -> onMediaProgressChanged(server, key))
		);
		ScreenComponent component = resolveScreenComponent(server, key);
		if (component != null) {
			ensureGalleryStateHydrated(server, key, mediaState);
		}
		synchronized (mediaState) {
			int existingIndex = resolveGalleryItemIndex(mediaState, persisted.url(), -1);
			if (existingIndex < 0) {
				BufferedImage preview = persistedGalleryPreviewForDisplay(persisted, kind);
				mediaState.galleryItems.add(new GalleryItem(
						persisted.title(),
						persisted.subtitle(),
						persisted.url(),
						persisted.localMediaKey(),
						null,
						preview,
						kind
				));
				mediaState.galleryHydrated = true;
				mediaState.statusText = "Файл сохранён";
				mediaState.version++;
			}
		}
		persistGalleryState(server, key, mediaState);
		requestRuntimeRender(server, key);
		return true;
	}

	private static void deleteTransferLocalMediaIfTemporary(MaxIncomingFile incoming) {
		if (incoming == null || incoming.localMediaKey() == null || incoming.id() == null) {
			return;
		}
		String ownedTransferPrefix = MAX_TRANSFER_LOCAL_KEY_PREFIX + incoming.id();
		if (!incoming.localMediaKey().startsWith(ownedTransferPrefix)) {
			return;
		}
		MonitorMediaApp.deleteSavedGalleryMedia(incoming.localMediaKey());
	}

	private static void playNotificationSound(MinecraftServer server, ScreenComponent component) {
		if (server == null || component == null || component.runtimeKey() == null) {
			return;
		}
		ServerLevel level = server.getLevel(component.runtimeKey().dimension());
		if (level == null) {
			return;
		}
		Vec3 center = screenCenter(component);
		long seed = level.random.nextLong();
		double rangeSqr = 16.0D * 16.0D;
		for (ServerPlayer viewer : level.players()) {
			if (viewer.distanceToSqr(center) > rangeSqr) {
				continue;
			}
			boolean hasPack = PolymerResourcePackUtils.hasMainPack(viewer);
			Holder<SoundEvent> sound = hasPack
					? Holder.direct(MAX_NOTIFICATION_SOUND)
					: BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.NOTE_BLOCK_BELL.value());
			viewer.connection.send(new ClientboundSoundPacket(
					sound,
					SoundSource.BLOCKS,
					center.x,
					center.y,
					center.z,
					0.9F,
					hasPack ? 1.0F : 1.35F,
					seed
			));
		}
	}

	private static Vec3 screenCenter(ScreenComponent component) {
		if (component == null || component.frameCoords().isEmpty()) {
			return Vec3.ZERO;
		}
		double x = 0.0D;
		double y = 0.0D;
		double z = 0.0D;
		int count = 0;
		for (ItemFrame frame : component.frameCoords().keySet()) {
			if (frame == null) {
				continue;
			}
			x += frame.getX();
			y += frame.getY();
			z += frame.getZ();
			count++;
		}
		if (count <= 0) {
			return Vec3.ZERO;
		}
		return new Vec3(x / count, y / count, z / count);
	}

	private static List<MaxAvatarCandidateSnapshot> avatarCandidates(ScreenComponent component) {
		List<PersistedGalleryItem> persisted = resolvePersistedGalleryState(component);
		if (persisted.isEmpty()) {
			return List.of();
		}
		List<MaxAvatarCandidateSnapshot> candidates = new ArrayList<>();
		for (PersistedGalleryItem item : persisted) {
			if (item == null || effectiveGalleryItemKind(item) != GalleryItemKind.MEDIA || item.url() == null || item.url().isBlank()) {
				continue;
			}
			BufferedImage preview = persistedGalleryPreviewForDisplay(item, GalleryItemKind.MEDIA);
			if (preview == null) {
				continue;
			}
			candidates.add(new MaxAvatarCandidateSnapshot(
					item.title() == null || item.title().isBlank() ? "Аватар" : item.title(),
					item.url(),
					item.localMediaKey(),
					preview
			));
		}
		return candidates.isEmpty() ? List.of() : List.copyOf(candidates);
	}

	private static List<MaxRingtoneCandidateSnapshot> ringtoneCandidates(ScreenComponent component, MaxRuntimeState state) {
		List<MaxRingtoneCandidateSnapshot> candidates = new ArrayList<>();
		candidates.add(ringtoneCandidate(state, "Стандартный рингтон", "MAX", MAX_DEFAULT_RINGTONE_URL, ""));
		for (PersistedGalleryItem item : resolvePersistedGalleryState(component)) {
			if (item == null || effectiveGalleryItemKind(item) != GalleryItemKind.AUDIO) {
				continue;
			}
			String title = item.title() == null || item.title().isBlank() ? "Аудиотрек" : item.title();
			String subtitle = item.subtitle() == null || item.subtitle().isBlank() ? "Галерея" : item.subtitle();
			String url = item.url() == null || item.url().isBlank() ? "max:gallery:" + Objects.toString(item.localMediaKey(), title) : item.url();
			candidates.add(ringtoneCandidate(state, title, subtitle, url, item.localMediaKey()));
		}
		return List.copyOf(candidates);
	}

	private static MaxRingtoneCandidateSnapshot ringtoneCandidate(MaxRuntimeState state, String title, String subtitle, String url, String localMediaKey) {
		boolean selected;
		boolean playing;
		float fraction;
		synchronized (state) {
			String stateUrl = state.ringtoneUrl == null || state.ringtoneUrl.isBlank() ? MAX_DEFAULT_RINGTONE_URL : state.ringtoneUrl;
			String candidateUrl = url == null || url.isBlank() ? MAX_DEFAULT_RINGTONE_URL : url;
			selected = Objects.equals(stateUrl, candidateUrl)
					&& Objects.equals(Objects.toString(state.ringtoneLocalMediaKey, ""), Objects.toString(localMediaKey, ""));
			playing = state.ringtonePreviewPlaying
					&& Objects.equals(Objects.toString(state.ringtonePreviewUrl, MAX_DEFAULT_RINGTONE_URL), candidateUrl)
					&& Objects.equals(Objects.toString(state.ringtonePreviewLocalMediaKey, ""), Objects.toString(localMediaKey, ""));
			long elapsed = playing ? Math.max(0L, System.currentTimeMillis() - state.ringtonePreviewStartedAtMillis) : 0L;
			fraction = playing ? (elapsed % MAX_RINGTONE_PREVIEW_TIMELINE_MS) / (float) MAX_RINGTONE_PREVIEW_TIMELINE_MS : 0.0F;
		}
		return new MaxRingtoneCandidateSnapshot(title, subtitle, url, localMediaKey, selected, playing, fraction);
	}

	private static MonitorMediaApp.LoadedMedia loadAvatarMedia(ScreenComponent component, String avatarUrl, String localMediaKey) {
		String localKey = localMediaKey != null ? localMediaKey.trim() : "";
		if (localKey.isBlank() && avatarUrl != null && !avatarUrl.isBlank()) {
			for (PersistedGalleryItem item : resolvePersistedGalleryState(component)) {
				if (item != null && Objects.equals(item.url(), avatarUrl)) {
					localKey = item.localMediaKey() != null ? item.localMediaKey().trim() : "";
					break;
				}
			}
		}
		if (localKey.isBlank()) {
			return null;
		}
		try {
			return MonitorMediaApp.loadSavedGalleryMedia(localKey, null);
		} catch (Exception exception) {
			Lg2.LOGGER.debug("Failed to load MAX avatar {}: {}", localKey, sanitizeMediaError(exception.getMessage()));
			return null;
		}
	}

	private static BufferedImage avatarFrame(MonitorMediaApp.LoadedMedia media, int index, BufferedImage fallback) {
		BufferedImage frame = media != null && media.frameCount() > 0 ? media.frame(index) : null;
		return frame != null ? frame : fallback;
	}

	private static BufferedImage currentAvatarFrameLocked(MaxRuntimeState state) {
		if (state == null) {
			return null;
		}
		MonitorMediaApp.LoadedMedia media = state.avatarMedia;
		if (media == null || media.frameCount() <= 0) {
			return state.avatarFrame;
		}
		return avatarFrame(media, currentAvatarFrameIndexLocked(state, System.currentTimeMillis()), state.avatarFrame);
	}

	private static Color stableAvatarAccentLocked(MaxRuntimeState state, String fallbackCode) {
		String code = state != null && state.accountCode != null && !state.accountCode.isBlank()
				? state.accountCode
				: fallbackCode;
		return participantAccent(code, state != null ? state.avatarFrame : null);
	}

	private static int currentAvatarFrameIndexLocked(MaxRuntimeState state, long nowMillis) {
		MonitorMediaApp.LoadedMedia media = state != null ? state.avatarMedia : null;
		if (media == null || media.frameCount() <= 1) {
			return 0;
		}
		long cycleMillis = avatarCycleMillis(media);
		if (cycleMillis <= 0L) {
			return 0;
		}
		long elapsedMillis = Math.max(0L, nowMillis - state.avatarAnimationStartedAtMillis);
		long cyclePosition = elapsedMillis % cycleMillis;
		long cursor = 0L;
		for (int index = 0; index < media.frameCount(); index++) {
			cursor += Math.max(20, media.delayMillis(index));
			if (cyclePosition < cursor) {
				return index;
			}
		}
		return Math.max(0, media.frameCount() - 1);
	}

	private static long avatarCycleMillis(MonitorMediaApp.LoadedMedia media) {
		if (media == null || media.frameCount() <= 0) {
			return 0L;
		}
		long total = 0L;
		for (int index = 0; index < media.frameCount(); index++) {
			total += Math.max(20, media.delayMillis(index));
		}
		return total;
	}

	private static boolean avatarAnimatedLocked(MaxRuntimeState state) {
		return state != null && state.avatarMedia != null && state.avatarMedia.frameCount() > 1;
	}

	private static boolean maxAnimatedAvatarsVisible(
			ScreenComponent component,
			MaxRuntimeState state,
			List<MaxContactSnapshot> contacts,
			MaxCallVisualSnapshot call,
			List<MaxIncomingFileSnapshot> incomingFiles
	) {
		if (component == null) {
			return false;
		}
		boolean maxVisible = component.viewMode() == ScreenViewMode.MAX || hasVisibleCall(component.runtimeKey());
		if (!maxVisible) {
			return false;
		}
		if (avatarAnimatedLocked(state)) {
			return true;
		}
		if (contacts != null) {
			for (MaxContactSnapshot contact : contacts) {
				if (contact != null && contact.avatarAnimated()) {
					return true;
				}
			}
		}
		if (incomingFiles != null) {
			for (MaxIncomingFileSnapshot incomingFile : incomingFiles) {
				if (incomingFile != null && incomingFile.senderAvatarAnimated()) {
					return true;
				}
			}
		}
		if (call != null) {
			if (call.peerAvatarAnimated()) {
				return true;
			}
			for (MaxCallParticipantSnapshot participant : call.participants()) {
				if (participant != null && participant.avatarAnimated()) {
					return true;
				}
			}
		}
		return false;
	}

	private static void scheduleAvatarAnimationRender(MinecraftServer server, ScreenRuntimeKey key, MaxRuntimeState state) {
		if (server == null || key == null || state == null) {
			return;
		}
		synchronized (state) {
			if (state.avatarRenderScheduled) {
				return;
			}
			state.avatarRenderScheduled = true;
		}
		ensureExecutors();
		mediaScheduler.schedule(() -> server.execute(() -> {
			MaxRuntimeState current = MAX_STATES.get(key);
			if (current != null) {
				synchronized (current) {
					current.avatarRenderScheduled = false;
				}
			}
			ScreenComponent component = resolveScreenComponent(server, key);
			if (component != null && (component.viewMode() == ScreenViewMode.MAX || hasVisibleCall(key))) {
				requestRuntimeRender(server, key);
			}
		}), MAX_AVATAR_ANIMATION_RENDER_DELAY_MS, TimeUnit.MILLISECONDS);
	}

	private static List<LiveCameraReference> connectedCameraReferences(MinecraftServer server, ScreenComponent component) {
		if (server == null || component == null || component.runtimeKey() == null) {
			return List.of();
		}
		ServerLevel level = server.getLevel(component.runtimeKey().dimension());
		if (level == null) {
			return List.of();
		}
		return collectConnectedCameraPositions(level, component);
	}

	private static int connectedCameraCount(List<LiveCameraReference> cameras) {
		if (cameras == null || cameras.isEmpty()) {
			return 0;
		}
		int count = 0;
		for (LiveCameraReference camera : cameras) {
			if (camera != null && camera.sourceType() != LiveCameraSourceType.DRONE) {
				count++;
			}
		}
		return count;
	}

	private static int connectedDroneCount(List<LiveCameraReference> cameras) {
		if (cameras == null || cameras.isEmpty()) {
			return 0;
		}
		int count = 0;
		for (LiveCameraReference camera : cameras) {
			if (camera != null && camera.sourceType() == LiveCameraSourceType.DRONE) {
				count++;
			}
		}
		return count;
	}

	private static List<MaxCameraOptionSnapshot> cameraOptions(MinecraftServer server, MaxRuntimeState state, List<LiveCameraReference> cameras) {
		if (cameras == null || cameras.isEmpty()) {
			return List.of();
		}
		String selected;
		synchronized (state) {
			selected = state.selectedCameraUrl;
		}
		List<MaxCameraOptionSnapshot> options = new ArrayList<>(cameras.size());
		for (LiveCameraReference camera : cameras) {
			String url = liveCameraGalleryUrl(camera);
			BlockPos pos = camera.pos();
			String title = liveCameraDeviceTitle(server, camera);
			String subtitle = pos != null ? formatLiveSourceCoordinates(pos) : "live";
			boolean online = isLiveCameraOnline(server, camera);
			options.add(new MaxCameraOptionSnapshot(
					title,
					subtitle,
					url,
					createLiveCameraPlaceholderPreview(title, subtitle, online, camera.sourceType()),
					Objects.equals(url, selected),
					online
			));
		}
		return List.copyOf(options);
	}

	private static List<MaxMicrophoneOptionSnapshot> microphoneOptions(MaxRuntimeState state, List<MicrophoneSystem.ScreenMicrophoneDevice> microphones) {
		synchronized (state) {
			return microphoneOptionsLocked(state, microphones);
		}
	}

	private static List<MaxMicrophoneOptionSnapshot> microphoneOptionsLocked(MaxRuntimeState state, List<MicrophoneSystem.ScreenMicrophoneDevice> microphones) {
		if (state == null || microphones == null || microphones.isEmpty()) {
			return List.of();
		}
		List<MaxMicrophoneOptionSnapshot> options = new ArrayList<>(microphones.size());
		String selectedKey = state.selectedMicrophoneKey;
		int selectedIndex = state.selectedMicrophoneIndex;
		for (MicrophoneSystem.ScreenMicrophoneDevice microphone : microphones) {
			String deviceKey = microphoneDeviceKey(microphone);
			boolean selected = selectedKey != null && !selectedKey.isBlank()
					? Objects.equals(selectedKey, deviceKey)
					: microphone.index() == selectedIndex;
			options.add(new MaxMicrophoneOptionSnapshot(
					microphone.index(),
					microphone.title(),
					microphone.subtitle(),
					deviceKey,
					selected,
					true
			));
		}
		return List.copyOf(options);
	}

	private static int selectedCameraIndex(MaxRuntimeState state, List<MaxCameraOptionSnapshot> options) {
		if (state == null || options == null || options.isEmpty()) {
			return -1;
		}
		String selected;
		synchronized (state) {
			selected = state.selectedCameraUrl;
		}
		for (int index = 0; index < options.size(); index++) {
			if (Objects.equals(options.get(index).url(), selected)) {
				return index;
			}
		}
		return -1;
	}

	private static int normalizeSelectedMicrophoneLocked(MaxRuntimeState state, List<MicrophoneSystem.ScreenMicrophoneDevice> microphones) {
		if (state == null || microphones == null || microphones.isEmpty()) {
			if (state != null) {
				state.selectedMicrophoneKey = "";
				state.selectedMicrophoneIndex = -1;
			}
			return -1;
		}
		if ((state.selectedMicrophoneKey == null || state.selectedMicrophoneKey.isBlank())
				&& state.selectedMicrophoneIndex >= 0
				&& state.selectedMicrophoneIndex < microphones.size()) {
			state.selectedMicrophoneKey = microphoneDeviceKey(microphones.get(state.selectedMicrophoneIndex));
		}
		if (state.selectedMicrophoneKey != null && !state.selectedMicrophoneKey.isBlank()) {
			for (MicrophoneSystem.ScreenMicrophoneDevice microphone : microphones) {
				if (Objects.equals(state.selectedMicrophoneKey, microphoneDeviceKey(microphone))) {
					state.selectedMicrophoneIndex = microphone.index();
					return state.selectedMicrophoneIndex;
				}
			}
			state.selectedMicrophoneKey = "";
			state.selectedMicrophoneIndex = -1;
			return -1;
		}
		state.selectedMicrophoneIndex = -1;
		return -1;
	}

	private static String microphoneDeviceKey(MicrophoneSystem.ScreenMicrophoneDevice microphone) {
		if (microphone == null || microphone.dimension() == null || microphone.pos() == null) {
			return "";
		}
		BlockPos pos = microphone.pos();
		return microphone.dimension().identifier() + ":" + pos.getX() + ":" + pos.getY() + ":" + pos.getZ();
	}

	private static MaxCallSession currentCall(ScreenRuntimeKey key) {
		UUID callId = key != null ? CALL_BY_SCREEN.get(key) : null;
		return callId != null ? CALLS_BY_ID.get(callId) : null;
	}

	private static MaxCallPhase callPhase(MaxCallSession call, ScreenRuntimeKey key) {
		if (call == null || key == null) {
			return MaxCallPhase.IDLE;
		}
		if (call.isRinging(key)) {
			return MaxCallPhase.INCOMING;
		}
		if (call.accepted && call.isAccepted(key)) {
			return MaxCallPhase.ACTIVE;
		}
		return Objects.equals(call.caller, key) ? MaxCallPhase.OUTGOING : MaxCallPhase.INCOMING;
	}

	private static String generateAccountCode() {
		for (int attempt = 0; attempt < 100; attempt++) {
			String code = "MAX-" + String.format(Locale.ROOT, "%06d", java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000));
			if (!ACCOUNT_INDEX.containsKey(code)) {
				return code;
			}
		}
		return "MAX-" + String.format(Locale.ROOT, "%06d", Math.floorMod(UUID.randomUUID().hashCode(), 1_000_000));
	}

	private static String normalizeAccountCode(String code) {
		if (code == null) {
			return "";
		}
		String compact = code.trim().toUpperCase(Locale.ROOT).replace(" ", "").replace("#", "");
		if (compact.matches("\\d{6}")) {
			return "MAX-" + compact;
		}
		if (compact.matches("MAX\\d{6}")) {
			return "MAX-" + compact.substring(3);
		}
		if (compact.matches("MAX-\\d{6}")) {
			return compact;
		}
		return "";
	}

	private static boolean sanitizeContactsLocked(MaxRuntimeState state) {
		if (state == null || state.contacts.isEmpty()) {
			return false;
		}
		boolean changed = false;
		Set<String> seen = new HashSet<>();
		for (int index = 0; index < state.contacts.size(); ) {
			String normalized = normalizeAccountCode(state.contacts.get(index));
			if (normalized.isBlank() || Objects.equals(normalized, state.accountCode) || !seen.add(normalized)) {
				state.contacts.remove(index);
				changed = true;
				continue;
			}
			if (!Objects.equals(normalized, state.contacts.get(index))) {
				state.contacts.set(index, normalized);
				changed = true;
			}
			index++;
		}
		return changed;
	}

	private static boolean ensureIncomingSendersAsContactsLocked(MaxRuntimeState state) {
		if (state == null || state.incomingFiles.isEmpty()) {
			return false;
		}
		boolean changed = false;
		for (MaxIncomingFile incoming : state.incomingFiles) {
			String senderCode = incoming == null ? "" : normalizeAccountCode(incoming.senderCode());
			if (senderCode.isBlank() || Objects.equals(senderCode, state.accountCode) || state.contacts.contains(senderCode)) {
				continue;
			}
			state.contacts.add(senderCode);
			changed = true;
		}
		return changed;
	}

	private static String displayAccountCode(String code) {
		String normalized = normalizeAccountCode(code);
		if (normalized.startsWith("MAX-") && normalized.length() == 10) {
			return normalized.substring(4);
		}
		return code == null ? "" : code;
	}

	private static String defaultAccountName(String accountCode) {
		String displayCode = displayAccountCode(accountCode);
		return displayCode == null || displayCode.isBlank() ? "MAX" : displayCode;
	}

	private static String sanitizeAccountName(String accountName) {
		if (accountName == null) {
			return "";
		}
		String stripped = accountName.strip();
		if (stripped.isEmpty()) {
			return "";
		}
		StringBuilder builder = new StringBuilder(stripped.length());
		for (int offset = 0; offset < stripped.length(); ) {
			int codePoint = stripped.codePointAt(offset);
			offset += Character.charCount(codePoint);
			if (codePoint == '\n' || codePoint == '\r' || Character.isISOControl(codePoint)) {
				continue;
			}
			builder.appendCodePoint(codePoint);
		}
		return builder.toString().strip();
	}

	private static int accountNameCodePointLength(String accountName) {
		return accountName == null ? 0 : accountName.codePointCount(0, accountName.length());
	}

	private static String truncateAccountName(String accountName, int maxCodePoints) {
		if (accountName == null || accountName.isBlank() || maxCodePoints <= 0) {
			return "";
		}
		StringBuilder builder = new StringBuilder(accountName.length());
		int count = 0;
		for (int offset = 0; offset < accountName.length() && count < maxCodePoints; ) {
			int codePoint = accountName.codePointAt(offset);
			builder.appendCodePoint(codePoint);
			offset += Character.charCount(codePoint);
			count++;
		}
		return builder.toString();
	}

	private static String accountNameLookupKey(String accountName) {
		String sanitized = sanitizeAccountName(accountName);
		return sanitized.isBlank() ? "" : sanitized.toLowerCase(Locale.ROOT);
	}

	private static boolean accountNameTakenByOther(String accountName, ScreenRuntimeKey selfKey) {
		String lookupKey = accountNameLookupKey(accountName);
		if (lookupKey.isBlank()) {
			return false;
		}
		ScreenRuntimeKey existing = ACCOUNT_NAME_INDEX.get(lookupKey);
		return existing != null && !Objects.equals(existing, selfKey);
	}

	private static boolean accountNameConflictsWithAccountCode(String accountName, ScreenRuntimeKey selfKey) {
		String normalizedCode = normalizeAccountCode(accountName);
		if (normalizedCode.isBlank()) {
			return false;
		}
		ScreenRuntimeKey existing = ACCOUNT_INDEX.get(normalizedCode);
		return existing != null && !Objects.equals(existing, selfKey);
	}

	private static String resolveHydratedAccountName(String persistedAccountName, String accountCode, ScreenRuntimeKey selfKey) {
		String fallback = defaultAccountName(accountCode);
		String sanitized = sanitizeAccountName(persistedAccountName);
		if (sanitized.isBlank()) {
			return fallback;
		}
		if (accountNameCodePointLength(sanitized) > MAX_ACCOUNT_NAME_MAX_LENGTH) {
			sanitized = truncateAccountName(sanitized, MAX_ACCOUNT_NAME_MAX_LENGTH);
		}
		if (sanitized.isBlank() || accountNameTakenByOther(sanitized, selfKey) || accountNameConflictsWithAccountCode(sanitized, selfKey)) {
			return fallback;
		}
		return sanitized;
	}

	private static String resolveAccountCode(MinecraftServer server, String rawInput) {
		String normalizedCode = normalizeAccountCode(rawInput);
		if (!normalizedCode.isBlank()) {
			ScreenRuntimeKey key = ACCOUNT_INDEX.get(normalizedCode);
			if (key != null) {
				return normalizedCode;
			}
		}
		String nameKey = accountNameLookupKey(rawInput);
		if (nameKey.isBlank()) {
			return "";
		}
		ScreenRuntimeKey key = ACCOUNT_NAME_INDEX.get(nameKey);
		if (key == null) {
			return "";
		}
		ScreenComponent component = server != null ? resolveScreenComponent(server, key) : null;
		MaxRuntimeState state = component != null ? ensureState(server, component) : MAX_STATES.get(key);
		if (state == null) {
			return "";
		}
		synchronized (state) {
			return state.accountCode == null ? "" : state.accountCode;
		}
	}

	private static void requestAllMaxRenders(MinecraftServer server) {
		if (server == null) {
			return;
		}
		for (ScreenRuntimeKey key : new ArrayList<>(MAX_STATES.keySet())) {
			requestRuntimeRender(server, key);
		}
	}

	private static Component copyableAccountNameComponent(String accountName) {
		String value = sanitizeAccountName(accountName);
		return Component.literal(value)
				.withStyle(style -> style
						.withItalic(false)
						.withColor(ChatFormatting.BLUE)
						.withUnderlined(true)
						.withClickEvent(new ClickEvent.CopyToClipboard(value)));
	}

	private static void sendAccountRenamePrompt(ServerPlayer player, String accountName) {
		if (player == null) {
			return;
		}
		Component copyableName = copyableAccountNameComponent(accountName);
		String locale = MonitorScreenMessages.locale(player);
		Component message;
		if (locale.startsWith("ja")) {
			message = Component.empty()
					.append(MonitorScreenMessages.literal("現在のニックネーム: "))
					.append(copyableName)
					.append(MonitorScreenMessages.literal("。変更するには新しいニックネームをチャットに入力してください"));
		} else if (locale.startsWith("uk")) {
			message = Component.empty()
					.append(MonitorScreenMessages.literal("Поточний нік: "))
					.append(copyableName)
					.append(MonitorScreenMessages.literal(". Напиши в чат новий нікнейм, щоб змінити його"));
		} else if (locale.startsWith("rpr")) {
			message = Component.empty()
					.append(MonitorScreenMessages.literal("Текущiй никъ: "))
					.append(copyableName)
					.append(MonitorScreenMessages.literal(". Напиши въ чатъ новый никнеймъ, дабы его изменить"));
		} else if (locale.startsWith("ru")) {
			message = Component.empty()
					.append(MonitorScreenMessages.literal("Текущий ник: "))
					.append(copyableName)
					.append(MonitorScreenMessages.literal(". Напиши в чат новый никнейм, чтобы изменить его"));
		} else {
			message = Component.empty()
					.append(MonitorScreenMessages.literal("Current nickname: "))
					.append(copyableName)
					.append(MonitorScreenMessages.literal(". Type a new nickname in chat to change it"));
		}
		player.displayClientMessage(message, true);
	}

	private static Component maxAddContactPromptMessage(ServerPlayer player) {
		String locale = MonitorScreenMessages.locale(player);
		if (locale.startsWith("ja")) {
			return MonitorScreenMessages.literal("MAX の id またはニックネームをチャットに入力してください");
		}
		if (locale.startsWith("uk")) {
			return MonitorScreenMessages.literal("Напиши в чат MAX id або нік");
		}
		if (locale.startsWith("rpr")) {
			return MonitorScreenMessages.literal("Напиши въ чатъ MAX id или никъ");
		}
		if (locale.startsWith("ru")) {
			return MonitorScreenMessages.literal("Напиши в чат MAX id или ник");
		}
		return MonitorScreenMessages.literal("Type a MAX id or nickname in chat");
	}

	private static Component maxCallContactPromptMessage(ServerPlayer player) {
		String locale = MonitorScreenMessages.locale(player);
		if (locale.startsWith("ja")) {
			return MonitorScreenMessages.literal("チャットに MAX id またはニックネームを入力すると、連絡先に追加して通話へ招待します");
		}
		if (locale.startsWith("uk")) {
			return MonitorScreenMessages.literal("Напиши в чат MAX id або нік, щоб додати контакт і запросити його в дзвінок");
		}
		if (locale.startsWith("rpr")) {
			return MonitorScreenMessages.literal("Напиши въ чатъ MAX id или никъ, дабы прибавить контактъ и призвать его въ звонокъ");
		}
		if (locale.startsWith("ru")) {
			return MonitorScreenMessages.literal("Напиши в чат MAX id или ник, чтобы добавить контакт и пригласить его в звонок");
		}
		return MonitorScreenMessages.literal("Type a MAX id or nickname to add the contact and invite them to the call");
	}

	private static Component maxContactNotFoundMessage(ServerPlayer player) {
		String locale = MonitorScreenMessages.locale(player);
		if (locale.startsWith("ja")) {
			return MonitorScreenMessages.literal("MAX: 連絡先が見つかりません");
		}
		if (locale.startsWith("uk")) {
			return MonitorScreenMessages.literal("MAX: контакт не знайдено");
		}
		if (locale.startsWith("rpr")) {
			return MonitorScreenMessages.literal("MAX: контактъ не обрѣтенъ");
		}
		if (locale.startsWith("ru")) {
			return MonitorScreenMessages.literal("MAX: контакт не найден");
		}
		return MonitorScreenMessages.literal("MAX: contact not found");
	}

	private static Component maxContactFeedbackMessage(ServerPlayer player, String feedbackKey) {
		String locale = MonitorScreenMessages.locale(player);
		String key = feedbackKey == null ? "" : feedbackKey;
		if (locale.startsWith("ja")) {
			return switch (key) {
				case "self" -> MonitorScreenMessages.literal("これはこの画面の MAX id です");
				case "added" -> MonitorScreenMessages.literal("連絡先を追加しました");
				case "duplicate" -> MonitorScreenMessages.literal("連絡先はすでに追加されています");
				default -> MonitorScreenMessages.literal("MAX");
			};
		}
		if (locale.startsWith("uk")) {
			return switch (key) {
				case "self" -> MonitorScreenMessages.literal("Це id цього екрана");
				case "added" -> MonitorScreenMessages.literal("Контакт додано");
				case "duplicate" -> MonitorScreenMessages.literal("Контакт уже додано");
				default -> MonitorScreenMessages.literal("MAX");
			};
		}
		if (locale.startsWith("rpr")) {
			return switch (key) {
				case "self" -> MonitorScreenMessages.literal("Се id сего экрана");
				case "added" -> MonitorScreenMessages.literal("Контактъ прибавленъ");
				case "duplicate" -> MonitorScreenMessages.literal("Контактъ уже прибавленъ");
				default -> MonitorScreenMessages.literal("MAX");
			};
		}
		if (locale.startsWith("ru")) {
			return switch (key) {
				case "self" -> MonitorScreenMessages.literal("Это id этого экрана");
				case "added" -> MonitorScreenMessages.literal("Контакт добавлен");
				case "duplicate" -> MonitorScreenMessages.literal("Контакт уже добавлен");
				default -> MonitorScreenMessages.literal("MAX");
			};
		}
		return switch (key) {
			case "self" -> MonitorScreenMessages.literal("This is this screen's id");
			case "added" -> MonitorScreenMessages.literal("Contact added");
			case "duplicate" -> MonitorScreenMessages.literal("Contact already added");
			default -> MonitorScreenMessages.literal("MAX");
		};
	}

	private static Component maxCallInviteFeedbackMessage(ServerPlayer player, boolean added) {
		String locale = MonitorScreenMessages.locale(player);
		if (locale.startsWith("ja")) {
			return MonitorScreenMessages.literal(added ? "連絡先を追加して通話に招待しました" : "連絡先を通話に招待しました");
		}
		if (locale.startsWith("uk")) {
			return MonitorScreenMessages.literal(added ? "Контакт додано та запрошено в дзвінок" : "Контакт запрошено в дзвінок");
		}
		if (locale.startsWith("rpr")) {
			return MonitorScreenMessages.literal(added ? "Контактъ прибавленъ и призванъ въ звонокъ" : "Контактъ призванъ въ звонокъ");
		}
		if (locale.startsWith("ru")) {
			return MonitorScreenMessages.literal(added ? "Контакт добавлен и приглашён в звонок" : "Контакт приглашён в звонок");
		}
		return MonitorScreenMessages.literal(added ? "Contact added and invited to the call" : "Contact invited to the call");
	}

	private static Component maxAccountNameBlankMessage(ServerPlayer player) {
		String locale = MonitorScreenMessages.locale(player);
		if (locale.startsWith("ja")) {
			return MonitorScreenMessages.literal("MAX: ニックネームは空にできません");
		}
		if (locale.startsWith("uk")) {
			return MonitorScreenMessages.literal("MAX: нік не може бути порожнім");
		}
		if (locale.startsWith("rpr")) {
			return MonitorScreenMessages.literal("MAX: никъ не можетъ быть пустымъ");
		}
		if (locale.startsWith("ru")) {
			return MonitorScreenMessages.literal("MAX: ник не может быть пустым");
		}
		return MonitorScreenMessages.literal("MAX: nickname cannot be empty");
	}

	private static Component maxAccountNameTooLongMessage(ServerPlayer player) {
		String locale = MonitorScreenMessages.locale(player);
		if (locale.startsWith("ja")) {
			return MonitorScreenMessages.literal("MAX: ニックネームは " + MAX_ACCOUNT_NAME_MAX_LENGTH + " 文字までです");
		}
		if (locale.startsWith("uk")) {
			return MonitorScreenMessages.literal("MAX: нік може містити до " + MAX_ACCOUNT_NAME_MAX_LENGTH + " символів");
		}
		if (locale.startsWith("rpr")) {
			return MonitorScreenMessages.literal("MAX: никъ можетъ содержати до " + MAX_ACCOUNT_NAME_MAX_LENGTH + " символовъ");
		}
		if (locale.startsWith("ru")) {
			return MonitorScreenMessages.literal("MAX: ник может содержать до " + MAX_ACCOUNT_NAME_MAX_LENGTH + " символов");
		}
		return MonitorScreenMessages.literal("MAX: nickname can be at most " + MAX_ACCOUNT_NAME_MAX_LENGTH + " characters");
	}

	private static Component maxAccountNameTakenMessage(ServerPlayer player) {
		String locale = MonitorScreenMessages.locale(player);
		if (locale.startsWith("ja")) {
			return MonitorScreenMessages.literal("MAX: このニックネームは既に使われています");
		}
		if (locale.startsWith("uk")) {
			return MonitorScreenMessages.literal("MAX: цей нік уже зайнятий");
		}
		if (locale.startsWith("rpr")) {
			return MonitorScreenMessages.literal("MAX: сей никъ уже занятъ");
		}
		if (locale.startsWith("ru")) {
			return MonitorScreenMessages.literal("MAX: этот ник уже занят");
		}
		return MonitorScreenMessages.literal("MAX: that nickname is already in use");
	}

	private static Component maxAccountNameUpdatedMessage(ServerPlayer player) {
		String locale = MonitorScreenMessages.locale(player);
		if (locale.startsWith("ja")) {
			return MonitorScreenMessages.literal("ニックネームを更新しました");
		}
		if (locale.startsWith("uk")) {
			return MonitorScreenMessages.literal("Нік оновлено");
		}
		if (locale.startsWith("rpr")) {
			return MonitorScreenMessages.literal("Никъ обновлёнъ");
		}
		if (locale.startsWith("ru")) {
			return MonitorScreenMessages.literal("Ник обновлён");
		}
		return MonitorScreenMessages.literal("Nickname updated");
	}

	private static Component maxAccountNameUnchangedMessage(ServerPlayer player) {
		String locale = MonitorScreenMessages.locale(player);
		if (locale.startsWith("ja")) {
			return MonitorScreenMessages.literal("ニックネームは変わっていません");
		}
		if (locale.startsWith("uk")) {
			return MonitorScreenMessages.literal("Нік не змінився");
		}
		if (locale.startsWith("rpr")) {
			return MonitorScreenMessages.literal("Никъ не измѣнился");
		}
		if (locale.startsWith("ru")) {
			return MonitorScreenMessages.literal("Ник не изменился");
		}
		return MonitorScreenMessages.literal("Nickname did not change");
	}

	private static Path defaultRingtonePath() {
		if (Files.isRegularFile(DEFAULT_PROJECT_RINGTONE)) {
			return DEFAULT_PROJECT_RINGTONE;
		}
		if (Files.isRegularFile(DEFAULT_SOURCE_RINGTONE)) {
			return DEFAULT_SOURCE_RINGTONE;
		}
		return null;
	}

	private static String ringtoneSourceForScreen(ScreenRuntimeKey key) {
		MaxRuntimeState state = key != null ? MAX_STATES.get(key) : null;
		if (state != null) {
			synchronized (state) {
				String source = ringtoneSource(state.ringtoneUrl, state.ringtoneLocalMediaKey);
				if (source != null && !source.isBlank()) {
					return source;
				}
			}
		}
		Path fallback = defaultRingtonePath();
		return fallback != null ? fallback.toString() : "";
	}

	private static String ringtoneSource(String url, String localMediaKey) {
		String localKey = localMediaKey != null ? localMediaKey.trim() : "";
		if (!localKey.isBlank()) {
			Path saved = MonitorMediaApp.savedGalleryMediaFile(localKey);
			if (saved != null) {
				return saved.toAbsolutePath().toString();
			}
		}
		String normalizedUrl = url != null ? url.trim() : "";
		if (normalizedUrl.isBlank() || Objects.equals(normalizedUrl, MAX_DEFAULT_RINGTONE_URL)) {
			Path fallback = defaultRingtonePath();
			return fallback != null ? fallback.toString() : "";
		}
		if (normalizedUrl.startsWith("max:gallery:")) {
			return "";
		}
		return normalizedUrl;
	}

	private static SpeakerAudioSource previewRingtoneSource(ScreenRuntimeKey key, MaxRuntimeState state) {
		if (key == null || state == null) {
			return null;
		}
		boolean playing;
		String url;
		String localMediaKey;
		long startedAtMillis;
		synchronized (state) {
			playing = state.ringtonePreviewPlaying;
			url = state.ringtonePreviewUrl;
			localMediaKey = state.ringtonePreviewLocalMediaKey;
			startedAtMillis = state.ringtonePreviewStartedAtMillis;
		}
		if (!playing) {
			return null;
		}
		String source = ringtoneSource(url, localMediaKey);
		if (source == null || source.isBlank()) {
			return null;
		}
		String sourceKey = MAX_RINGTONE_PREVIEW_SOURCE_PREFIX + componentGroupId(key);
		return new SpeakerAudioSource(
				sourceKey,
				sourceKey,
				source,
				0L,
				startedAtMillis,
				false,
				false,
				false,
				false,
				true
		);
	}

	private static SpeakerAudioSource notificationPreviewSource(ScreenRuntimeKey key, MaxRuntimeState state) {
		if (key == null || state == null) {
			return null;
		}
		String fileId;
		String audioInput;
		long positionMs;
		long syncToken;
		synchronized (state) {
			if (!state.notificationsOpen
					|| !state.notificationPreviewPlaying
					|| state.notificationPreviewLoading
					|| state.notificationPreviewAudioInput == null
					|| state.notificationPreviewAudioInput.isBlank()) {
				return null;
			}
			fileId = state.notificationPreviewFileId;
			audioInput = state.notificationPreviewAudioInput;
			positionMs = notificationPreviewPositionMillisLocked(state, System.currentTimeMillis());
			syncToken = state.notificationPreviewStartedAtMillis;
		}
		if (fileId == null || fileId.isBlank() || audioInput == null || audioInput.isBlank()) {
			return null;
		}
		String sourceKey = MAX_NOTIFICATION_PREVIEW_SOURCE_PREFIX + componentGroupId(key) + ":" + fileId;
		return new SpeakerAudioSource(
				sourceKey,
				sourceKey,
				audioInput,
				positionMs,
				syncToken,
				false,
				false,
				false,
				true,
				false
		);
	}

	private static String maxVideoStreamOwnerId(ScreenRuntimeKey key) {
		return "max-call|" + liveCameraStreamOwnerId(key);
	}

	private static String maxLocalVideoStreamOwnerId(ScreenRuntimeKey key) {
		return "max-call-local|" + liveCameraStreamOwnerId(key);
	}

	private static void drawMaxFeedScreen(Graphics2D graphics, UiLayout layout, MonitorApp app, MaxVisualSnapshot state) {
		UiRect canvas = mediaCanvasRect(layout);
		graphics.setColor(new Color(246, 248, 251));
		graphics.fillRect(canvas.x(), canvas.y(), canvas.width(), canvas.height());
		UiRect close = mediaCloseRect(layout);
		fillRoundedRect(graphics, close, close.height(), new Color(235, 238, 242));
		drawPlayerUiIcon(graphics, mediaChromeIconRect(close, layout), PlayerUiIcon.CLOSE, new Color(92, 101, 114));

		boolean singleTile = maxSingleTileLayout(layout);
		UiRect title = maxContactsTitleRect(layout);
		drawVerticalText(graphics, "Контакты", title, new Color(22, 27, 34), Font.BOLD, singleTile ? 9 : clampInt(layout.unit() + 3, 15, 24));
		drawMaxAddContactButton(graphics, maxAddContactRect(layout), layout);

		UiRect profile = maxProfilePanelRect(layout);
		int profileArc = singleTile ? 9 : clampInt(layout.unit() * 2, 14, 24);
		fillRoundedRect(graphics, profile, profileArc, Color.WHITE);
		strokeRoundedRect(graphics, profile, profileArc, singleTile ? 0.8F : 1.0F, new Color(220, 225, 232));
		drawMaxAvatar(graphics, maxProfileAvatarRect(layout), state.avatarFrame(), state.accountCode(), layout);
		drawEllipsizedVerticalText(graphics, state.accountName(), maxAppTitleRect(layout), new Color(22, 27, 34), Font.BOLD, singleTile ? 8 : clampInt(layout.unit(), 10, 17));
		if (!singleTile) {
			drawEllipsizedVerticalText(graphics, "Нажми, чтобы изменить профиль", maxProfileCodeRect(layout), new Color(116, 124, 136), Font.PLAIN, clampInt(layout.unit() - 2, 7, 11));
		}
		drawMaxRingtoneControls(graphics, layout, state);

		UiRect listRect = maxContactListRect(layout);
		WindowedSnapshot<MaxContactSnapshot> contactsWindow = state.contacts() != null ? state.contacts() : WindowedSnapshot.empty();
		List<MaxContactSnapshot> contacts = contactsWindow.items();
		if (contacts.isEmpty()) {
			drawMaxFeedEmptyContacts(graphics, layout, listRect);
		} else {
			int count = Math.min(contacts.size(), maxVisibleContactRows(layout));
			for (int index = 0; index < count; index++) {
				drawMaxFeedContactRow(graphics, layout, maxContactRowRect(layout, index), contacts.get(index));
			}
			drawMaxContactFeedScrollbar(graphics, layout, contactsWindow);
		}
	}

	private static void drawMaxCallScreen(Graphics2D graphics, UiLayout layout, ScreenRuntimeKey runtimeKey, MaxVisualSnapshot state) {
		MaxCallVisualSnapshot call = state.call();
		if (call.phase() == MaxCallPhase.INCOMING) {
			drawMaxIncomingCallScreen(graphics, layout, call);
			return;
		}
		if (call.phase() == MaxCallPhase.OUTGOING) {
			drawMaxOutgoingCallScreen(graphics, layout, call);
			return;
		}
		drawMaxActiveCallScreen(graphics, layout, runtimeKey, state, call);
	}

	private static void drawMaxIncomingCallScreen(Graphics2D graphics, UiLayout layout, MaxCallVisualSnapshot call) {
		UiRect canvas = mediaCanvasRect(layout);
		drawAvatarBackdrop(graphics, canvas, call.peerAvatarFrame(), call.peerCode());
		UiRect avatar = maxIncomingAvatarRect(layout);
		drawMaxAvatar(graphics, avatar, call.peerAvatarFrame(), call.peerCode(), layout);
		drawCenteredTextFitted(graphics, call.peerDisplayName(), maxIncomingCodeRect(layout), new Color(248, 251, 255, 246), Font.BOLD, clampInt(layout.unit() + 4, 16, 28), 8);
		drawCenteredTextFitted(graphics, "Входящий вызов MAX", maxIncomingSubtitleRect(layout), new Color(221, 235, 244, 224), Font.PLAIN, clampInt(layout.unit(), 10, 15), 6);
		drawRoundCallButton(graphics, maxIncomingAcceptRect(layout), PlayerUiIcon.CALL_ACCEPT, new Color(74, 214, 142), new Color(8, 18, 13, 238), layout);
		drawRoundCallButton(graphics, maxIncomingDeclineRect(layout), PlayerUiIcon.CALL_DECLINE, new Color(240, 88, 96), new Color(255, 248, 248, 246), layout);
	}

	private static void drawMaxOutgoingCallScreen(Graphics2D graphics, UiLayout layout, MaxCallVisualSnapshot call) {
		UiRect canvas = mediaCanvasRect(layout);
		drawAvatarBackdrop(graphics, canvas, call.peerAvatarFrame(), call.peerCode());
		drawMaxAvatar(graphics, maxIncomingAvatarRect(layout), call.peerAvatarFrame(), call.peerCode(), layout);
		drawCenteredTextFitted(graphics, call.peerDisplayName(), maxIncomingCodeRect(layout), new Color(248, 251, 255, 246), Font.BOLD, clampInt(layout.unit() + 4, 16, 28), 8);
		drawCenteredTextFitted(graphics, "Ожидание ответа", maxIncomingSubtitleRect(layout), new Color(221, 235, 244, 224), Font.PLAIN, clampInt(layout.unit(), 10, 15), 6);
		drawRoundCallButton(graphics, maxOutgoingCancelRect(layout), PlayerUiIcon.CALL_DECLINE, new Color(240, 88, 96), new Color(255, 248, 248, 246), layout);
	}

	private static void drawMaxActiveCallScreen(Graphics2D graphics, UiLayout layout, ScreenRuntimeKey runtimeKey, MaxVisualSnapshot state, MaxCallVisualSnapshot call) {
		UiRect canvas = mediaCanvasRect(layout);
		graphics.setPaint(new GradientPaint(canvas.x(), canvas.y(), new Color(10, 12, 18), canvas.right(), canvas.bottom(), new Color(20, 26, 36)));
		graphics.fillRect(canvas.x(), canvas.y(), canvas.width(), canvas.height());
		List<MaxCallParticipantSnapshot> participants = maxCallParticipants(state, call);
		boolean focused = call.selfFocused() || call.peerFocused();
		if (focused) {
			MaxCallParticipantSnapshot focusedParticipant = maxCallFocusedParticipant(state, call, participants);
			UiRect focusedRect = maxCallFocusedTileRect(layout);
			drawMaxParticipantTile(graphics, layout, focusedRect, focusedParticipant, true, MediaScaleMode.FILL);
			List<MaxCallParticipantSnapshot> miniParticipants = participants.stream()
					.filter(participant -> !sameMaxParticipant(participant, focusedParticipant))
					.toList();
			if (!call.miniParticipantsHidden() && !miniParticipants.isEmpty()) {
				int visibleRows = maxCallMiniParticipantVisibleRows(layout, miniParticipants.size());
				int scroll = clampInt(call.miniParticipantScroll(), 0, maxCallMiniParticipantScroll(layout, miniParticipants.size()));
				MonitorScrollAnimationSystem.ScrollVisualState visualScroll = MonitorScrollAnimationSystem.sample(
						runtimeKey,
						MonitorScrollAnimationSystem.ScrollChannel.MAX_CALL_MINI_PARTICIPANTS,
						scroll,
						maxCallMiniParticipantScroll(layout, miniParticipants.size())
				);
				int baseIndex = visualScroll.anchorIndex();
				int yOffset = -(int) Math.round(visualScroll.fraction() * maxCallMiniParticipantStride(layout, miniParticipants.size()));
				int count = Math.min(
						Math.max(0, miniParticipants.size() - baseIndex),
						visibleRows + (visualScroll.animated() && baseIndex + visibleRows < miniParticipants.size() ? 1 : 0)
				);
				UiRect listRect = maxCallMiniStripListRect(layout, miniParticipants.size());
				Shape previousClip = graphics.getClip();
				graphics.clipRect(listRect.x(), listRect.y(), listRect.width(), listRect.height());
				for (int visibleIndex = 0; visibleIndex < count; visibleIndex++) {
					drawMaxParticipantTile(
							graphics,
							layout,
							offsetRect(maxCallMiniParticipantRect(layout, visibleIndex, miniParticipants.size()), 0, yOffset),
							miniParticipants.get(baseIndex + visibleIndex),
							false,
							false,
							MediaScaleMode.FILL
					);
				}
				graphics.setClip(previousClip);
				drawQueueScrollbar(graphics, maxCallMiniStripTrackRect(layout, miniParticipants.size()), visualScroll.displayValue(), visibleRows, miniParticipants.size(), layout);
			}
			if (call.menuOpen()) {
				drawMaxCallFocusControls(graphics, layout, miniParticipants.size(), call.miniParticipantsHidden());
			}
		} else {
			int count = participants.size();
			for (int index = 0; index < count; index++) {
				drawMaxParticipantTile(graphics, layout, maxCallParticipantTileRect(layout, count, index, call.menuOpen()), participants.get(index), false, MediaScaleMode.FILL);
			}
		}
		if (call.menuOpen()) {
			drawMaxCallMenu(graphics, layout, call);
		}
		if (call.cameraPickerOpen()) {
			drawMaxCameraPicker(graphics, layout, runtimeKey, call);
		} else if (call.contactPickerOpen()) {
			drawMaxContactPicker(graphics, layout, runtimeKey, state, call);
		}
	}

	private static List<MaxCallParticipantSnapshot> maxCallParticipants(MaxVisualSnapshot state, MaxCallVisualSnapshot call) {
		if (call.participants() != null && !call.participants().isEmpty()) {
			return call.participants();
		}
		List<MaxCallParticipantSnapshot> fallback = new ArrayList<>(2);
		fallback.add(new MaxCallParticipantSnapshot(
				state.accountCode(),
				state.accountName(),
				state.avatarFrame(),
				null,
				state.animatedAvatars(),
				call.localPreviewFrame(),
				true,
				call.cameraEnabled(),
				call.microphoneEnabled(),
				true,
				false
		));
		if (call.peerCode() != null && !call.peerCode().isBlank()) {
			fallback.add(new MaxCallParticipantSnapshot(
					call.peerCode(),
					call.peerDisplayName(),
					call.peerAvatarFrame(),
					null,
					call.peerAvatarAnimated(),
					call.remoteFrame(),
					false,
					call.remoteFrame() != null,
					true,
					true,
					false
			));
		}
		return List.copyOf(fallback);
	}

	private static MaxCallParticipantSnapshot maxCallFocusedParticipant(MaxVisualSnapshot state, MaxCallVisualSnapshot call, List<MaxCallParticipantSnapshot> participants) {
		String focusedCode = call.focusedParticipantCode();
		if (focusedCode != null && !focusedCode.isBlank()) {
			for (MaxCallParticipantSnapshot participant : participants) {
				if (participant != null && Objects.equals(participant.code(), focusedCode)) {
					return participant;
				}
			}
		}
		if (call.selfFocused()) {
			for (MaxCallParticipantSnapshot participant : participants) {
				if (participant != null && participant.self()) {
					return participant;
				}
			}
		}
		for (MaxCallParticipantSnapshot participant : participants) {
			if (participant != null && !participant.self()) {
				return participant;
			}
		}
		return new MaxCallParticipantSnapshot(state.accountCode(), state.accountName(), state.avatarFrame(), null, state.animatedAvatars(), call.localPreviewFrame(), true, call.cameraEnabled(), call.microphoneEnabled(), true, false);
	}

	private static boolean sameMaxParticipant(MaxCallParticipantSnapshot left, MaxCallParticipantSnapshot right) {
		if (left == null || right == null) {
			return false;
		}
		return left.self() == right.self() && Objects.equals(left.code(), right.code());
	}

	private static void drawMaxParticipantTile(
			Graphics2D graphics,
			UiLayout layout,
			UiRect rect,
			MaxCallParticipantSnapshot participant,
			boolean focused,
			MediaScaleMode scaleMode
	) {
		drawMaxParticipantTile(graphics, layout, rect, participant, focused, true, scaleMode);
	}

	private static void drawMaxParticipantTile(
			Graphics2D graphics,
			UiLayout layout,
			UiRect rect,
			MaxCallParticipantSnapshot participant,
			boolean focused,
			boolean allowAddContact,
			MediaScaleMode scaleMode
	) {
		if (participant == null) {
			return;
		}
		drawMaxParticipantTile(
				graphics,
				layout,
				rect,
				participant.code(),
				participant.displayName(),
				participant.avatarFrame(),
				participant.accentColor(),
				participant.videoFrame(),
				focused,
				participant.cameraEnabled(),
				participant.microphoneEnabled(),
				allowAddContact && !participant.self() && !participant.savedContact(),
				scaleMode
		);
	}

	private static void drawMaxParticipantTile(
			Graphics2D graphics,
			UiLayout layout,
			UiRect rect,
			String code,
			String displayName,
			BufferedImage avatar,
			Color accent,
			BufferedImage video,
			boolean focused,
			boolean cameraEnabled,
			boolean microphoneEnabled,
			boolean showAddContact,
			MediaScaleMode scaleMode
	) {
		if (rect.width() <= 1 || rect.height() <= 1) {
			return;
		}
		int arc = clampInt(focused ? layout.unit() * 2 : layout.unit() + 8, 12, focused ? 30 : 22);
		Shape previousClip = graphics.getClip();
		Shape shape = roundedRectShape(rect, arc, arc, arc, arc);
		Color accentColor = accent != null ? accent : participantAccent(code, avatar);
		boolean videoVisible = cameraEnabled && video != null;
		graphics.setClip(shape);
		if (videoVisible) {
			drawScaledImage(graphics, video, rect, scaleMode);
		} else {
			graphics.setColor(accentColor);
			graphics.fillRect(rect.x(), rect.y(), rect.width(), rect.height());
			int maxAvatar = Math.max(12, Math.min(rect.width(), rect.height()) - 6);
			int avatarSize = clampInt(Math.min(rect.width(), rect.height()) / (focused ? 4 : 3), Math.min(18, maxAvatar), Math.min(focused ? 116 : 72, maxAvatar));
			UiRect avatarRect = new UiRect(rect.x() + (rect.width() - avatarSize) / 2, rect.y() + (rect.height() - avatarSize) / 2, avatarSize, avatarSize);
			drawMaxAvatar(graphics, avatarRect, avatar, code, layout);
		}
		graphics.setClip(previousClip);
		drawMaxParticipantLabel(graphics, layout, rect, displayName, focused);
		if (showAddContact) {
			drawMaxParticipantAddContactButton(graphics, maxCallParticipantAddContactRect(rect, layout), layout);
		}
		if (!microphoneEnabled && rect.width() >= 20 && rect.height() >= 20) {
			int micSize = clampInt(Math.min(rect.width(), rect.height()) / 5, 12, 30);
			int inset = Math.max(2, layout.unit() / 2);
			int micY = rect.y() + inset;
			if (showAddContact) {
				UiRect addContact = maxCallParticipantAddContactRect(rect, layout);
				int belowAdd = addContact.bottom() + Math.max(2, inset / 2);
				micY = belowAdd + micSize <= rect.bottom() - inset ? belowAdd : Math.max(rect.y() + inset, rect.bottom() - inset - micSize);
			}
			UiRect micOff = new UiRect(rect.right() - inset - micSize, micY, micSize, micSize);
			fillRoundedRect(graphics, micOff, micOff.height(), new Color(0, 0, 0, 108));
			drawPlayerUiIcon(graphics, mediaChromeIconRect(micOff, layout), PlayerUiIcon.MIC_OFF, new Color(248, 251, 255, 230));
		}
	}

	private static void drawMaxParticipantLabel(Graphics2D graphics, UiLayout layout, UiRect tile, String code, boolean focused) {
		if (code == null || code.isBlank() || tile.width() < 16 || tile.height() < 12) {
			return;
		}
		int textSize = clampInt(layout.unit() - (focused ? 0 : 2), tile.height() < 32 ? 6 : 7, focused ? 13 : 11);
		Font font = new Font(Font.SANS_SERIF, Font.BOLD, textSize);
		FontMetrics metrics = graphics.getFontMetrics(font);
		int padX = Math.max(3, layout.unit() / 3);
		int padY = Math.max(2, layout.unit() / 5);
		int maxWidth = Math.max(8, tile.width() - padX * 4);
		String labelText = truncateWithEllipsis(metrics, code, maxWidth);
		int labelWidth = Math.min(tile.width() - padX * 2, metrics.stringWidth(labelText) + padX * 2);
		int labelHeight = Math.min(tile.height(), metrics.getHeight() + padY * 2);
		int x = tile.x() + padX;
		int y = tile.bottom() - labelHeight - padX;
		UiRect label = new UiRect(x, Math.max(tile.y(), y), Math.max(1, labelWidth), Math.max(1, labelHeight));
		graphics.setFont(font);
		graphics.setColor(new Color(0, 0, 0, 120));
		graphics.drawString(labelText, label.x() + padX + 1, label.y() + (label.height() - metrics.getHeight()) / 2 + metrics.getAscent() + 1);
		graphics.setColor(new Color(248, 251, 255, 238));
		graphics.drawString(labelText, label.x() + padX, label.y() + (label.height() - metrics.getHeight()) / 2 + metrics.getAscent());
	}

	private static void drawEllipsizedVerticalText(Graphics2D graphics, String text, UiRect rect, Color color, int style, int size) {
		if (graphics == null || rect == null || rect.width() <= 0 || rect.height() <= 0) {
			return;
		}
		graphics.setColor(color);
		graphics.setFont(new Font(Font.SANS_SERIF, style, size));
		FontMetrics metrics = graphics.getFontMetrics();
		String labelText = truncateWithEllipsis(metrics, text == null ? "" : text, rect.width());
		int textY = rect.y() + (rect.height() - metrics.getHeight()) / 2 + metrics.getAscent();
		graphics.drawString(labelText, rect.x(), textY);
	}

	private static void drawMaxContactPickerHeaderButton(Graphics2D graphics, UiRect rect, UiLayout layout, PlayerUiIcon icon) {
		Color color = drawMediaHeaderControlBase(graphics, rect, MediaButtonSegment.SINGLE);
		drawPlayerUiIcon(graphics, mediaChromeIconRect(rect, layout), icon, color);
	}

	private static void drawMaxCallMenu(Graphics2D graphics, UiLayout layout, MaxCallVisualSnapshot call) {
		drawCallMenuIconButton(
				graphics,
				maxCallMicrophoneToggleRect(layout, call),
				call.microphoneEnabled() ? PlayerUiIcon.MIC : PlayerUiIcon.MIC_OFF,
				call.microphoneEnabled() ? new Color(248, 251, 255, 236) : new Color(248, 251, 255, 148),
				layout
		);
		if (call.microphoneCount() > 1) {
			drawCallMenuIconButton(graphics, maxCallMicrophoneSelectRect(layout, call), PlayerUiIcon.DEVICE_SELECT, new Color(248, 251, 255, 186), layout);
		}
		drawCallMenuIconButton(
				graphics,
				maxCallCameraToggleRect(layout, call),
				call.cameraEnabled() ? PlayerUiIcon.VIDEO_CAMERA : PlayerUiIcon.VIDEO_CAMERA_OFF,
				call.cameraEnabled() ? new Color(248, 251, 255, 236) : new Color(248, 251, 255, 148),
				layout
		);
		drawCallMenuIconButton(graphics, maxCallCameraSelectRect(layout, call), PlayerUiIcon.DEVICE_SELECT, new Color(248, 251, 255, 186), layout);
		drawCallMenuIconButton(graphics, maxCallInviteRect(layout, call), PlayerUiIcon.USER_ADD, new Color(248, 251, 255, 226), layout);
		drawCallMenuIconButton(graphics, maxCallLeaveRect(layout, call), PlayerUiIcon.CALL_DECLINE, new Color(240, 88, 96, 246), layout);
	}

	private static void drawCallSegmentButton(Graphics2D graphics, UiRect rect, PlayerUiIcon icon, boolean active, MediaButtonSegment segment, UiLayout layout) {
		Color iconColor = drawSmallMediaButtonBase(graphics, rect, segment, active, mediaChromeStrokeWidth(rect), active ? null : new Color(255, 255, 255, 0));
		drawPlayerUiIcon(graphics, mediaChromeIconRect(rect, layout), icon, iconColor);
	}

	private static void drawCallMenuIconButton(Graphics2D graphics, UiRect rect, PlayerUiIcon icon, Color iconColor, UiLayout layout) {
		drawPlayerUiIcon(graphics, mediaChromeIconRect(rect, layout), icon, iconColor);
	}

	private static void drawCallSegmentButtonRotated(Graphics2D graphics, UiRect rect, PlayerUiIcon icon, boolean active, MediaButtonSegment segment, UiLayout layout, double rotationRadians) {
		Color iconColor = drawSmallMediaButtonBase(graphics, rect, segment, active, mediaChromeStrokeWidth(rect), active ? null : new Color(255, 255, 255, 0));
		drawRotatedPlayerUiIcon(graphics, mediaChromeIconRect(rect, layout), icon, iconColor, rotationRadians);
	}

	private static void drawRoundCallButton(Graphics2D graphics, UiRect rect, PlayerUiIcon icon, Color fill, Color iconColor, UiLayout layout) {
		fillRoundedRect(graphics, rect, rect.height(), fill);
		strokeRoundedRect(graphics, rect, rect.height(), 1.0F, new Color(255, 255, 255, 80));
		drawPlayerUiIcon(graphics, mediaChromeIconRect(rect, layout), icon, iconColor);
	}

	private static void drawMaxCallFocusControls(Graphics2D graphics, UiLayout layout, int miniParticipantCount, boolean miniParticipantsHidden) {
		UiRect toggleRect = maxCallMiniStripToggleRect(layout, miniParticipantCount, miniParticipantsHidden);
		if (toggleRect.width() > 0 && toggleRect.height() > 0) {
			drawCallSegmentButtonRotated(
					graphics,
					toggleRect,
					PlayerUiIcon.DROPDOWN,
					false,
					MediaButtonSegment.LEFT,
					layout,
					miniParticipantsHidden ? -Math.PI / 2.0D : Math.PI / 2.0D
			);
		}
		drawCallSegmentButton(
				graphics,
				maxCallGridExitRect(layout, miniParticipantCount, miniParticipantsHidden),
				PlayerUiIcon.GRID_FILL,
				false,
				toggleRect.width() > 0 ? MediaButtonSegment.RIGHT : MediaButtonSegment.SINGLE,
				layout
		);
	}

	private static void drawAvatarBackdrop(Graphics2D graphics, UiRect canvas, BufferedImage avatar, String code) {
		Color accent = participantAccent(code, avatar);
		if (avatar != null) {
			drawScaledImage(graphics, avatar, canvas, MediaScaleMode.FILL);
			graphics.setColor(new Color(0, 0, 0, 134));
			graphics.fillRect(canvas.x(), canvas.y(), canvas.width(), canvas.height());
		}
		graphics.setPaint(new GradientPaint(canvas.x(), canvas.y(), withAlpha(brighten(accent, 14), avatar != null ? 144 : 255), canvas.right(), canvas.bottom(), withAlpha(darken(accent, 50), avatar != null ? 178 : 255)));
		graphics.fillRect(canvas.x(), canvas.y(), canvas.width(), canvas.height());
		graphics.setPaint(new GradientPaint(canvas.x(), canvas.y(), new Color(255, 255, 255, 34), canvas.x(), canvas.bottom(), new Color(0, 0, 0, 106)));
		graphics.fillRect(canvas.x(), canvas.y(), canvas.width(), canvas.height());
	}

	private static Color participantAccent(String code, BufferedImage avatar) {
		if (avatar != null) {
			long r = 0L;
			long g = 0L;
			long b = 0L;
			long count = 0L;
			int stepX = Math.max(1, avatar.getWidth() / 12);
			int stepY = Math.max(1, avatar.getHeight() / 12);
			for (int y = 0; y < avatar.getHeight(); y += stepY) {
				for (int x = 0; x < avatar.getWidth(); x += stepX) {
					int argb = avatar.getRGB(x, y);
					int alpha = (argb >>> 24) & 0xFF;
					if (alpha < 32) {
						continue;
					}
					r += (argb >>> 16) & 0xFF;
					g += (argb >>> 8) & 0xFF;
					b += argb & 0xFF;
					count++;
				}
			}
			if (count > 0L) {
				return new Color(clampInt((int) (r / count), 38, 210), clampInt((int) (g / count), 42, 210), clampInt((int) (b / count), 52, 220));
			}
		}
		int hash = Math.abs(Objects.toString(code, "MAX").hashCode());
		float hue = (hash % 360) / 360.0F;
		return Color.getHSBColor(hue, 0.48F, 0.64F);
	}

	private static Color brighten(Color color, int amount) {
		return new Color(clampInt(color.getRed() + amount, 0, 255), clampInt(color.getGreen() + amount, 0, 255), clampInt(color.getBlue() + amount, 0, 255), color.getAlpha());
	}

	private static Color darken(Color color, int amount) {
		return new Color(clampInt(color.getRed() - amount, 0, 255), clampInt(color.getGreen() - amount, 0, 255), clampInt(color.getBlue() - amount, 0, 255), color.getAlpha());
	}

	private static Color withAlpha(Color color, int alpha) {
		return new Color(color.getRed(), color.getGreen(), color.getBlue(), clampInt(alpha, 0, 255));
	}

	private static void drawMaxAvatarPicker(Graphics2D graphics, UiLayout layout, ScreenRuntimeKey runtimeKey, MaxVisualSnapshot state) {
		UiRect panel = maxAvatarPickerPanelRect(layout);
		fillRoundedRect(graphics, panel, clampInt(layout.unit() * 2, 14, 28), new Color(6, 10, 14, 224));
		strokeRoundedRect(graphics, panel, clampInt(layout.unit() * 2, 14, 28), 1.0F, new Color(255, 255, 255, 50));
		drawOverlayCloseButton(graphics, maxOverlayCloseRect(layout), layout);
		drawVerticalText(graphics, "ВЫБЕРИ АВАТАР", maxAvatarPickerTitleRect(layout), new Color(248, 251, 255, 236), Font.BOLD, clampInt(layout.unit(), 10, 16));
		WindowedSnapshot<MaxAvatarCandidateSnapshot> candidatesWindow = state.avatarCandidates() != null ? state.avatarCandidates() : WindowedSnapshot.empty();
		List<MaxAvatarCandidateSnapshot> candidates = candidatesWindow.items();
		int totalCandidateCount = candidatesWindow.totalCount();
		if (totalCandidateCount <= 0) {
			drawCenteredText(graphics, "В галерее нет картинок или GIF", maxAvatarPickerGridRect(layout), new Color(210, 224, 236, 224), Font.BOLD, clampInt(layout.unit(), 9, 14));
			return;
		}
		int columns = maxAvatarPickerColumns(layout);
		int visibleRows = maxAvatarPickerVisibleRows(layout);
		int totalRows = Math.max(0, (totalCandidateCount + columns - 1) / columns);
		int scroll = clampInt(state.avatarPickerScroll(), 0, maxAvatarPickerScroll(layout, totalCandidateCount));
		MonitorScrollAnimationSystem.ScrollVisualState visualScroll = MonitorScrollAnimationSystem.sample(
				runtimeKey,
				MonitorScrollAnimationSystem.ScrollChannel.MAX_AVATAR_PICKER,
				scroll,
				maxAvatarPickerScroll(layout, totalCandidateCount)
		);
		double displayRow = visualScroll.displayValue() / Math.max(1, columns);
		int baseRow = clampInt((int) Math.floor(displayRow + 1.0E-6D), 0, Math.max(0, totalRows - visibleRows));
		double rowFraction = clampDouble(displayRow - baseRow, 0.0D, 0.999999D);
		int rowOffset = -(int) Math.round(rowFraction * maxAvatarPickerRowStep(layout));
		int rowCount = Math.min(
				Math.max(0, totalRows - baseRow),
				visibleRows + (rowFraction > 1.0E-4D && baseRow + visibleRows < totalRows ? 1 : 0)
		);
		Shape previousClip = graphics.getClip();
		UiRect grid = maxAvatarPickerGridRect(layout);
			graphics.setClip(grid.x(), grid.y(), grid.width(), grid.height());
			for (int visibleRow = 0; visibleRow < rowCount; visibleRow++) {
				for (int column = 0; column < columns; column++) {
					int absoluteIndex = (baseRow + visibleRow) * columns + column;
					int index = absoluteIndex - candidatesWindow.windowStartIndex();
					if (index < 0 || index >= candidates.size()) {
						continue;
					}
					UiRect rect = offsetRect(maxAvatarCandidateRect(layout, visibleRow * columns + column), 0, rowOffset);
				MaxAvatarCandidateSnapshot candidate = candidates.get(index);
				fillRoundedRect(graphics, rect, clampInt(layout.unit(), 8, 16), new Color(255, 255, 255, 18));
				if (candidate.preview() != null) {
					drawScaledImage(graphics, candidate.preview(), rect.inset(Math.max(2, layout.unit() / 4)), MediaScaleMode.FILL);
				}
			}
		}
		graphics.setClip(previousClip);
	}

	private static void drawMaxCameraPicker(Graphics2D graphics, UiLayout layout, ScreenRuntimeKey runtimeKey, MaxCallVisualSnapshot call) {
		UiRect panel = maxAvatarPickerPanelRect(layout);
		boolean ultra = ultraCompactScreenLayout(layout);
		fillRoundedRect(graphics, panel, ultra ? clampInt(layout.unit(), 6, 10) : clampInt(layout.unit() * 2, 14, 28), new Color(6, 10, 14, 230));
		strokeRoundedRect(graphics, panel, ultra ? clampInt(layout.unit(), 6, 10) : clampInt(layout.unit() * 2, 14, 28), ultra ? 0.85F : 1.0F, new Color(255, 255, 255, 54));
		drawOverlayCloseButton(graphics, maxOverlayCloseRect(layout), layout);
		UiRect title = maxAvatarPickerTitleRect(layout);
		if (!ultra) {
			drawVerticalText(graphics, "УСТРОЙСТВА", new UiRect(title.x(), title.y(), title.width() / 2, title.height()), new Color(248, 251, 255, 236), Font.BOLD, clampInt(layout.unit(), 10, 16));
			drawCenteredTextFitted(graphics, maxCallDeviceCountLabel(call), new UiRect(title.x() + title.width() / 2, title.y(), title.width() / 2, title.height()), new Color(188, 204, 218, 224), Font.PLAIN, clampInt(layout.unit() - 2, 7, 11), 5);
		}

		UiRect cameraTitle = maxCallDeviceCameraTitleRect(layout);
		if (!ultra) {
			drawVerticalText(graphics, "КАМЕРЫ " + call.connectedCameraCount() + " · ДРОНЫ " + call.connectedDroneCount(), cameraTitle, new Color(188, 204, 218, 224), Font.BOLD, clampInt(layout.unit() - 2, 7, 11));
		}
		List<MaxCameraOptionSnapshot> cameras = call.cameras();
		int cameraCapacity = maxCallDeviceCameraCapacity(layout);
		int cameraScroll = clampInt(call.cameraScroll(), 0, Math.max(0, (cameras == null ? 0 : cameras.size()) - cameraCapacity));
		MonitorScrollAnimationSystem.ScrollVisualState cameraVisualScroll = MonitorScrollAnimationSystem.sample(
				runtimeKey,
				MonitorScrollAnimationSystem.ScrollChannel.MAX_CALL_CAMERA_PICKER,
				cameraScroll,
				Math.max(0, (cameras == null ? 0 : cameras.size()) - cameraCapacity)
		);
		if (cameras != null && cameras.size() > cameraCapacity) {
			drawMaxDeviceScrollStatus(graphics, cameraTitle, layout, cameraScroll, cameraCapacity, cameras.size());
			drawMaxDeviceScrollButton(graphics, maxCallDeviceCameraScrollLeftRect(layout), Math.PI / 2.0D, layout, cameraScroll > 0);
			drawMaxDeviceScrollButton(graphics, maxCallDeviceCameraScrollRightRect(layout), -Math.PI / 2.0D, layout, cameraScroll + cameraCapacity < cameras.size());
		}
		if (cameras == null || cameras.isEmpty()) {
			if (!ultra) {
				drawCenteredText(graphics, "Подключи камеру или дрон к экрану", maxCallDeviceCameraGridRect(layout), new Color(210, 224, 236, 224), Font.BOLD, clampInt(layout.unit(), 9, 14));
			}
		} else {
			int baseIndex = cameraVisualScroll.anchorIndex();
			int xOffset = -(int) Math.round(cameraVisualScroll.fraction() * maxCallDeviceCameraCellStep(layout));
			int count = Math.min(
					Math.max(0, cameras.size() - baseIndex),
					cameraCapacity + (cameraVisualScroll.animated() && baseIndex + cameraCapacity < cameras.size() ? 1 : 0)
			);
			Shape previousClip = graphics.getClip();
			UiRect grid = maxCallDeviceCameraGridRect(layout);
			graphics.setClip(grid.x(), grid.y(), grid.width(), grid.height());
			for (int visibleIndex = 0; visibleIndex < count; visibleIndex++) {
				int index = baseIndex + visibleIndex;
				MaxCameraOptionSnapshot camera = cameras.get(index);
				UiRect rect = offsetRect(maxCallDeviceCameraRect(layout, visibleIndex), xOffset, 0);
				fillRoundedRect(graphics, rect, clampInt(layout.unit(), 8, 16), new Color(255, 255, 255, camera.selected() ? 34 : 18));
				if (camera.preview() != null) {
					drawScaledImage(graphics, camera.preview(), rect.inset(Math.max(2, layout.unit() / 4)), MediaScaleMode.FILL);
				}
				if (camera.selected()) {
					strokeRoundedRect(graphics, rect, clampInt(layout.unit(), 8, 16), 1.5F, new Color(255, 255, 255, 172));
				}
				if (!ultra) {
					UiRect label = new UiRect(rect.x() + layout.unit() / 2, rect.bottom() - clampInt(layout.unit() * 3, 24, 38), rect.width() - layout.unit(), clampInt(layout.unit() * 2, 18, 28));
					fillRoundedRect(graphics, label, label.height(), new Color(0, 0, 0, 112));
					drawCenteredTextFitted(graphics, camera.title() + " " + camera.subtitle(), label.inset(2), camera.online() ? new Color(248, 251, 255, 238) : new Color(248, 251, 255, 136), Font.BOLD, clampInt(layout.unit() - 2, 7, 11), 6);
				}
			}
			graphics.setClip(previousClip);
		}

		UiRect microphoneTitle = maxCallDeviceMicrophoneTitleRect(layout);
		if (!ultra) {
			drawVerticalText(graphics, "МИКРОФОНЫ", microphoneTitle, new Color(188, 204, 218, 224), Font.BOLD, clampInt(layout.unit() - 2, 7, 11));
		}
		List<MaxMicrophoneOptionSnapshot> microphones = call.microphones();
		int microphoneCapacity = maxCallDeviceMicrophoneCapacity(layout);
		int microphoneScroll = clampInt(call.microphoneScroll(), 0, Math.max(0, (microphones == null ? 0 : microphones.size()) - microphoneCapacity));
		MonitorScrollAnimationSystem.ScrollVisualState microphoneVisualScroll = MonitorScrollAnimationSystem.sample(
				runtimeKey,
				MonitorScrollAnimationSystem.ScrollChannel.MAX_CALL_MICROPHONE_PICKER,
				microphoneScroll,
				Math.max(0, (microphones == null ? 0 : microphones.size()) - microphoneCapacity)
		);
		if (microphones != null && microphones.size() > microphoneCapacity) {
			drawMaxDeviceScrollStatus(graphics, microphoneTitle, layout, microphoneScroll, microphoneCapacity, microphones.size());
			drawMaxDeviceScrollButton(graphics, maxCallDeviceMicrophoneScrollUpRect(layout), Math.PI, layout, microphoneScroll > 0);
			drawMaxDeviceScrollButton(graphics, maxCallDeviceMicrophoneScrollDownRect(layout), 0.0D, layout, microphoneScroll + microphoneCapacity < microphones.size());
		}
		if (microphones == null || microphones.isEmpty()) {
			if (!ultra) {
				drawCenteredText(graphics, "Подключи микрофон к экрану", maxCallDeviceMicrophoneListRect(layout), new Color(210, 224, 236, 224), Font.BOLD, clampInt(layout.unit(), 9, 14));
			}
			return;
		}
		int microphoneBaseIndex = microphoneVisualScroll.anchorIndex();
		int yOffset = -(int) Math.round(microphoneVisualScroll.fraction() * maxCallDeviceMicrophoneRowStep(layout));
		int microphoneCount = Math.min(
				Math.max(0, microphones.size() - microphoneBaseIndex),
				microphoneCapacity + (microphoneVisualScroll.animated() && microphoneBaseIndex + microphoneCapacity < microphones.size() ? 1 : 0)
		);
		Shape previousClip = graphics.getClip();
		UiRect microphoneList = maxCallDeviceMicrophoneListRect(layout);
		graphics.setClip(microphoneList.x(), microphoneList.y(), microphoneList.width(), microphoneList.height());
		for (int visibleIndex = 0; visibleIndex < microphoneCount; visibleIndex++) {
			int index = microphoneBaseIndex + visibleIndex;
			drawMaxDeviceMicrophoneRow(graphics, layout, offsetRect(maxCallDeviceMicrophoneRowRect(layout, visibleIndex), 0, yOffset), microphones.get(index));
		}
		graphics.setClip(previousClip);
	}

	private static void drawMaxDeviceMicrophoneRow(Graphics2D graphics, UiLayout layout, UiRect rect, MaxMicrophoneOptionSnapshot microphone) {
		boolean ultra = ultraCompactScreenLayout(layout);
		int arc = ultra ? clampInt(layout.unit(), 5, 8) : clampInt(layout.unit(), 8, 16);
		fillRoundedRect(graphics, rect, arc, new Color(255, 255, 255, microphone.selected() ? 30 : 14));
		strokeRoundedRect(graphics, rect, arc, ultra ? 0.85F : 1.0F, microphone.selected() ? new Color(255, 255, 255, 128) : new Color(255, 255, 255, 42));
		int iconSize = ultra ? Math.max(8, Math.min(12, rect.height() - 2)) : clampInt(layout.unit() * 2, 18, 28);
		int gap = maxCallDeviceGap(layout);
		UiRect check = new UiRect(rect.x() + gap, rect.y() + (rect.height() - iconSize) / 2, iconSize, iconSize);
		Color checkColor = drawSmallMediaButtonBase(graphics, check, MediaButtonSegment.SINGLE, microphone.selected(), mediaChromeStrokeWidth(check));
		if (microphone.selected()) {
			drawPlayerUiIcon(graphics, mediaChromeIconRect(check, layout), PlayerUiIcon.CHECK, checkColor);
		}
		UiRect micIcon = new UiRect(check.right() + gap, rect.y() + (rect.height() - iconSize) / 2, iconSize, iconSize);
		drawPlayerUiIcon(graphics, micIcon.inset(Math.max(2, layout.unit() / 5)), PlayerUiIcon.MIC, new Color(238, 244, 250, 214));
		if (ultra) {
			return;
		}
		UiRect textRect = new UiRect(micIcon.right() + gap, rect.y() + Math.max(2, layout.unit() / 4), Math.max(8, rect.right() - micIcon.right() - gap * 2), rect.height() - Math.max(4, layout.unit() / 2));
		int titleHeight = Math.max(10, textRect.height() / 2);
		drawWrappedText(graphics, microphone.title(), new UiRect(textRect.x(), textRect.y(), textRect.width(), titleHeight), new Color(248, 251, 255, 232), Font.BOLD, clampInt(layout.unit() - 1, 8, 13), 1);
		drawWrappedText(graphics, microphone.subtitle(), new UiRect(textRect.x(), textRect.y() + titleHeight, textRect.width(), textRect.height() - titleHeight), new Color(178, 194, 210, 214), Font.PLAIN, clampInt(layout.unit() - 2, 7, 11), 1);
	}

	private static void drawMaxDeviceScrollButton(Graphics2D graphics, UiRect rect, double rotationRadians, UiLayout layout, boolean enabled) {
		drawOverlayChevronButton(graphics, rect, layout, rotationRadians, enabled);
	}

	private static void drawMaxDeviceScrollStatus(Graphics2D graphics, UiRect titleRect, UiLayout layout, int offset, int visibleCount, int totalCount) {
		if (totalCount <= visibleCount || visibleCount <= 0) {
			return;
		}
		if (ultraCompactScreenLayout(layout)) {
			return;
		}
		int width = clampInt(layout.unit() * 4, 30, 56);
		UiRect statusRect = new UiRect(titleRect.right() - width - clampInt(layout.unit() * 4, 28, 44), titleRect.y(), width, titleRect.height());
		int from = offset + 1;
		int to = Math.min(totalCount, offset + visibleCount);
		drawCenteredText(graphics, from + "-" + to, statusRect, new Color(188, 204, 218, 208), Font.PLAIN, clampInt(layout.unit() - 2, 7, 11));
	}

	private static String maxCallDeviceCountLabel(MaxCallVisualSnapshot call) {
		int cameras = call != null ? Math.max(0, call.connectedCameraCount()) : 0;
		int drones = call != null ? Math.max(0, call.connectedDroneCount()) : 0;
		int microphones = call != null && call.microphones() != null ? call.microphones().size() : 0;
		return "Камер: " + cameras + " · Дронов: " + drones + " · Микрофонов: " + microphones;
	}

	private static void drawMaxRingtonePicker(Graphics2D graphics, UiLayout layout, ScreenRuntimeKey runtimeKey, MaxVisualSnapshot state) {
		UiRect panel = maxAvatarPickerPanelRect(layout);
		fillRoundedRect(graphics, panel, clampInt(layout.unit() * 2, 14, 28), new Color(6, 10, 14, 232));
		strokeRoundedRect(graphics, panel, clampInt(layout.unit() * 2, 14, 28), 1.0F, new Color(255, 255, 255, 54));
		drawOverlayCloseButton(graphics, maxOverlayCloseRect(layout), layout);
		drawVerticalText(graphics, "РИНГТОН", maxAvatarPickerTitleRect(layout), new Color(248, 251, 255, 236), Font.BOLD, clampInt(layout.unit(), 10, 16));
		WindowedSnapshot<MaxRingtoneCandidateSnapshot> candidatesWindow = state.ringtoneCandidates() != null ? state.ringtoneCandidates() : WindowedSnapshot.empty();
		List<MaxRingtoneCandidateSnapshot> candidates = candidatesWindow.items();
		int totalCandidateCount = candidatesWindow.totalCount();
		if (totalCandidateCount <= 0) {
			drawCenteredText(graphics, "В галерее нет аудиофайлов", maxAvatarPickerGridRect(layout), new Color(210, 224, 236, 224), Font.BOLD, clampInt(layout.unit(), 9, 14));
			return;
		}
		int visibleRows = maxRingtonePickerCapacity(layout);
		int scroll = clampInt(state.ringtonePickerScroll(), 0, maxRingtonePickerScroll(layout, totalCandidateCount));
		MonitorScrollAnimationSystem.ScrollVisualState visualScroll = MonitorScrollAnimationSystem.sample(
				runtimeKey,
				MonitorScrollAnimationSystem.ScrollChannel.MAX_RINGTONE_PICKER,
				scroll,
				maxRingtonePickerScroll(layout, totalCandidateCount)
		);
		int baseIndex = visualScroll.anchorIndex();
		int yOffset = -(int) Math.round(visualScroll.fraction() * maxRingtonePickerRowStep(layout));
		int count = Math.min(
				Math.max(0, totalCandidateCount - baseIndex),
				visibleRows + (visualScroll.animated() && baseIndex + visibleRows < totalCandidateCount ? 1 : 0)
		);
		Shape previousClip = graphics.getClip();
		UiRect list = maxAvatarPickerGridRect(layout);
		graphics.setClip(list.x(), list.y(), list.width(), list.height());
		for (int index = 0; index < count; index++) {
			int absoluteIndex = baseIndex + index;
			int windowIndex = absoluteIndex - candidatesWindow.windowStartIndex();
			if (windowIndex < 0 || windowIndex >= candidates.size()) {
				continue;
			}
			drawMaxRingtoneCandidate(graphics, layout, offsetRect(maxRingtoneCandidateRect(layout, index), 0, yOffset), candidates.get(windowIndex));
		}
		graphics.setClip(previousClip);
	}

	private static void drawMaxRingtoneCandidate(Graphics2D graphics, UiLayout layout, UiRect rect, MaxRingtoneCandidateSnapshot candidate) {
		fillRoundedRect(graphics, rect, clampInt(layout.unit() * 2, 12, 22), new Color(255, 255, 255, candidate.selected() ? 30 : 16));
		strokeRoundedRect(graphics, rect, clampInt(layout.unit() * 2, 12, 22), 1.0F, candidate.selected() ? new Color(255, 255, 255, 92) : new Color(255, 255, 255, 36));
		UiRect play = maxRingtoneCandidatePlayRect(rect, layout);
		Color playColor = drawMediaHeaderControlBase(graphics, play, MediaButtonSegment.SINGLE);
		drawPlayerUiIcon(graphics, mediaChromeIconRect(play, layout), candidate.playing() ? PlayerUiIcon.PAUSE : PlayerUiIcon.PLAY, playColor);
		UiRect select = maxRingtoneCandidateSelectRect(rect, layout);
		Color selectColor = drawMediaHeaderControlBase(graphics, select, MediaButtonSegment.SINGLE);
		drawPlayerUiIcon(graphics, mediaChromeIconRect(select, layout), PlayerUiIcon.CHECK, selectColor);
		UiRect text = new UiRect(play.right() + layout.unit(), rect.y() + layout.unit() / 2, select.x() - play.right() - layout.unit() * 2, rect.height() / 2);
		drawVerticalText(graphics, candidate.title(), text, new Color(248, 251, 255, 238), Font.BOLD, clampInt(layout.unit(), 9, 14));
		drawVerticalText(graphics, candidate.subtitle(), new UiRect(text.x(), text.bottom(), text.width(), rect.height() / 3), new Color(176, 200, 216, 216), Font.PLAIN, clampInt(layout.unit() - 2, 7, 11));
	}

	private static void drawMaxContactPicker(Graphics2D graphics, UiLayout layout, ScreenRuntimeKey runtimeKey, MaxVisualSnapshot state, MaxCallVisualSnapshot call) {
		UiRect panel = maxAvatarPickerPanelRect(layout);
		fillRoundedRect(graphics, panel, clampInt(layout.unit() * 2, 14, 28), new Color(6, 10, 14, 230));
		strokeRoundedRect(graphics, panel, clampInt(layout.unit() * 2, 14, 28), 1.0F, new Color(255, 255, 255, 54));
		drawMaxContactPickerHeaderButton(graphics, maxCallContactPickerAddRect(layout), layout, PlayerUiIcon.USER_ADD);
		drawOverlayCloseButton(graphics, maxCallContactPickerCloseRect(layout), layout);
		drawVerticalText(graphics, "ДОБАВИТЬ В ЗВОНОК", maxCallContactPickerTitleRect(layout), new Color(248, 251, 255, 236), Font.BOLD, clampInt(layout.unit(), 10, 16));
		Set<String> participantCodes = new HashSet<>();
		for (MaxCallParticipantSnapshot participant : call.participants()) {
			if (participant != null && participant.code() != null && !participant.code().isBlank()) {
				participantCodes.add(participant.code());
			}
		}
		WindowedSnapshot<MaxContactSnapshot> candidatesWindow = state.callContactCandidates() != null ? state.callContactCandidates() : WindowedSnapshot.empty();
		List<MaxContactSnapshot> candidates = candidatesWindow.items();
		int totalCandidateCount = candidatesWindow.totalCount();
		if (totalCandidateCount <= 0) {
			drawCenteredText(graphics, "Нет контактов для приглашения", maxCallContactPickerGridRect(layout), new Color(210, 224, 236, 224), Font.BOLD, clampInt(layout.unit(), 9, 14));
			return;
		}
		int contactScroll = clampInt(call.contactPickerScroll(), 0, maxCallContactPickerScroll(layout, totalCandidateCount));
		MonitorScrollAnimationSystem.ScrollVisualState visualScroll = MonitorScrollAnimationSystem.sample(
				runtimeKey,
				MonitorScrollAnimationSystem.ScrollChannel.MAX_CALL_CONTACT_PICKER,
				contactScroll,
				maxCallContactPickerScroll(layout, totalCandidateCount)
		);
		int baseIndex = visualScroll.anchorIndex();
		int yOffset = -(int) Math.round(visualScroll.fraction() * maxContactPickerRowStep(layout));
		int count = Math.min(
				Math.max(0, totalCandidateCount - baseIndex),
				maxCallContactPickerCapacity(layout) + (visualScroll.animated() && baseIndex + maxCallContactPickerCapacity(layout) < totalCandidateCount ? 1 : 0)
		);
		Shape previousClip = graphics.getClip();
		UiRect list = maxCallContactPickerGridRect(layout);
		graphics.setClip(list.x(), list.y(), list.width(), list.height());
		for (int index = 0; index < count; index++) {
			int absoluteIndex = baseIndex + index;
			int windowIndex = absoluteIndex - candidatesWindow.windowStartIndex();
			if (windowIndex < 0 || windowIndex >= candidates.size()) {
				continue;
			}
			drawMaxContactRow(graphics, layout, offsetRect(maxCallContactPickerRowRect(layout, index), 0, yOffset), candidates.get(windowIndex), false);
		}
		graphics.setClip(previousClip);
	}

	private static void drawMaxFileSharePicker(Graphics2D graphics, UiLayout layout, ScreenRuntimeKey runtimeKey, MaxVisualSnapshot state) {
		UiRect panel = maxAvatarPickerPanelRect(layout);
		fillRoundedRect(graphics, panel, clampInt(layout.unit() * 2, 14, 28), new Color(6, 10, 14, 232));
		strokeRoundedRect(graphics, panel, clampInt(layout.unit() * 2, 14, 28), 1.0F, new Color(255, 255, 255, 54));
		drawOverlayCloseButton(graphics, maxOverlayCloseRect(layout), layout);
		String title = state.fileShareFileCount() <= 1 ? "ОТПРАВИТЬ ФАЙЛ" : "ОТПРАВИТЬ ФАЙЛЫ";
		drawVerticalText(graphics, title, maxFileShareTitleRect(layout), new Color(248, 251, 255, 236), Font.BOLD, clampInt(layout.unit(), 10, 16));
		UiRect send = maxFileShareSendRect(layout);
		Color sendColor = drawMediaHeaderControlBase(graphics, send, MediaButtonSegment.SINGLE);
		drawPlayerUiIcon(graphics, mediaChromeIconRect(send, layout), PlayerUiIcon.SEND_PLANE, sendColor);

		WindowedSnapshot<MaxFileShareContactSnapshot> contactsWindow = state.fileShareContacts() != null ? state.fileShareContacts() : WindowedSnapshot.empty();
		List<MaxFileShareContactSnapshot> contacts = contactsWindow.items();
		int totalContactCount = contactsWindow.totalCount();
		if (totalContactCount <= 0) {
			drawCenteredText(graphics, "Добавь контакты в MAX", maxContactPickerListRect(layout), new Color(210, 224, 236, 224), Font.BOLD, clampInt(layout.unit(), 9, 14));
			return;
		}
		UiRect hint = maxFileShareHintRect(layout);
		String fileLabel = state.fileShareFileCount() <= 1 ? state.fileShareTitle() : "Файлов: " + state.fileShareFileCount();
		drawCenteredTextFitted(graphics, fileLabel, hint, new Color(176, 202, 220, 224), Font.BOLD, clampInt(layout.unit() - 2, 7, 11), 6);
		int scroll = clampInt(state.fileSharePickerScroll(), 0, maxContactPickerScroll(layout, totalContactCount));
		MonitorScrollAnimationSystem.ScrollVisualState visualScroll = MonitorScrollAnimationSystem.sample(
				runtimeKey,
				MonitorScrollAnimationSystem.ScrollChannel.MAX_FILE_SHARE_PICKER,
				scroll,
				maxContactPickerScroll(layout, totalContactCount)
		);
		int baseIndex = visualScroll.anchorIndex();
		int yOffset = -(int) Math.round(visualScroll.fraction() * maxContactPickerRowStep(layout));
		int count = Math.min(
				Math.max(0, totalContactCount - baseIndex),
				maxContactPickerCapacity(layout) + (visualScroll.animated() && baseIndex + maxContactPickerCapacity(layout) < totalContactCount ? 1 : 0)
		);
		Shape previousClip = graphics.getClip();
		UiRect list = maxContactPickerListRect(layout);
		graphics.setClip(list.x(), list.y(), list.width(), list.height());
		for (int index = 0; index < count; index++) {
			int absoluteIndex = baseIndex + index;
			int windowIndex = absoluteIndex - contactsWindow.windowStartIndex();
			if (windowIndex < 0 || windowIndex >= contacts.size()) {
				continue;
			}
			drawMaxFileShareContactRow(graphics, layout, offsetRect(maxContactPickerRowRect(layout, index), 0, yOffset), contacts.get(windowIndex));
		}
		graphics.setClip(previousClip);
	}

	private static void drawMaxFileShareContactRow(Graphics2D graphics, UiLayout layout, UiRect rect, MaxFileShareContactSnapshot contact) {
		fillRoundedRect(graphics, rect, clampInt(layout.unit() * 2, 12, 24), new Color(8, 12, 16, contact.selected() ? 206 : 174));
		strokeRoundedRect(graphics, rect, clampInt(layout.unit() * 2, 12, 24), contact.selected() ? 1.4F : 1.0F, contact.selected() ? new Color(255, 255, 255, 112) : contact.online() ? new Color(255, 255, 255, 58) : new Color(255, 255, 255, 24));
		UiRect avatarRect = new UiRect(rect.x() + layout.unit(), rect.y() + layout.unit() / 2, rect.height() - layout.unit(), rect.height() - layout.unit());
		drawMaxAvatar(graphics, avatarRect, contact.avatarFrame(), contact.code(), layout);
		UiRect checkRect = maxFileShareContactCheckRect(rect, layout);
		int textRight = checkRect.x();
		UiRect codeRect = new UiRect(avatarRect.right() + layout.unit(), rect.y() + layout.unit() / 3, Math.max(1, textRight - avatarRect.right() - layout.unit() * 2), rect.height() / 2);
		drawEllipsizedVerticalText(graphics, contact.displayName(), codeRect, new Color(248, 251, 255, 238), Font.BOLD, clampInt(layout.unit(), 10, 16));
		drawVerticalText(graphics, contact.online() ? "доступен" : "недоступен", new UiRect(codeRect.x(), codeRect.bottom(), codeRect.width(), rect.height() / 3), new Color(178, 202, 218, 218), Font.PLAIN, clampInt(layout.unit() - 2, 7, 11));
		fillRoundedRect(graphics, checkRect, checkRect.height(), contact.selected() ? new Color(248, 251, 255, 232) : new Color(255, 255, 255, 16));
		strokeRoundedRect(graphics, checkRect, checkRect.height(), 1.0F, new Color(255, 255, 255, contact.selected() ? 150 : 48));
		drawPlayerUiIcon(graphics, mediaChromeIconRect(checkRect, layout), contact.selected() ? PlayerUiIcon.CHECKBOX_FILL : PlayerUiIcon.CHECKBOX_LINE, contact.selected() ? new Color(20, 24, 30, 238) : new Color(248, 251, 255, 220));
	}

	private static void drawMaxNotificationsScreen(Graphics2D graphics, UiLayout layout, ScreenRuntimeKey runtimeKey, MaxVisualSnapshot state) {
		UiRect panel = maxNotificationPopupRect(layout);
		fillRoundedRect(graphics, panel, clampInt(layout.unit(), 8, 18), new Color(6, 10, 14, 236));
		WindowedSnapshot<MaxIncomingFileSnapshot> incomingFilesWindow = state.incomingFiles() != null ? state.incomingFiles() : WindowedSnapshot.empty();
		List<MaxIncomingFileSnapshot> incomingFiles = incomingFilesWindow.items();
		if (!incomingFiles.isEmpty()) {
			drawMaxNotificationPopupHeader(graphics, layout, incomingFiles.get(0));
		} else {
			drawMaxNotificationPopupHeader(graphics, layout, null);
			return;
		}
		UiRect feed = maxNotificationFeedRect(layout);
		int maxScroll = maxNotificationScroll(layout, incomingFilesWindow.totalCount());
		MonitorScrollAnimationSystem.ScrollVisualState visualScroll = MonitorScrollAnimationSystem.sample(
				runtimeKey,
				MonitorScrollAnimationSystem.ScrollChannel.MAX_NOTIFICATION_FEED,
				clampInt(state.notificationScroll(), 0, maxScroll),
				maxScroll
		);
		Shape previousClip = graphics.getClip();
		graphics.setClip(feed.x(), feed.y(), feed.width(), feed.height());
		int itemHeight = maxNotificationItemHeight(layout);
		int itemGap = maxNotificationItemGap(layout);
		int y = feed.y() - (int) Math.round(visualScroll.displayValue()) + incomingFilesWindow.windowStartIndex() * (itemHeight + itemGap);
		for (MaxIncomingFileSnapshot incoming : incomingFiles) {
			UiRect itemRect = new UiRect(feed.x(), y, feed.width(), itemHeight);
			if (rectIntersects(itemRect, feed)) {
				drawMaxNotificationItem(graphics, layout, itemRect, incoming);
			}
			y += itemHeight + itemGap;
		}
		graphics.setClip(previousClip);
	}

	private static void drawMaxNotificationPopupHeader(Graphics2D graphics, UiLayout layout, MaxIncomingFileSnapshot sender) {
		UiRect header = maxNotificationPopupHeaderRect(layout);
		UiRect close = maxNotificationPopupCloseRect(layout);
		int pad = maxNotificationPopupPadding(layout);
		int avatarSize = clampInt(header.height() - pad * 2, 18, 34);
		UiRect avatar = new UiRect(header.x() + pad, header.y() + (header.height() - avatarSize) / 2, avatarSize, avatarSize);
		drawMaxAvatar(graphics, avatar, sender != null ? sender.senderAvatarFrame() : null, sender != null ? sender.senderCode() : "MAX", layout);
		UiRect name = new UiRect(
				avatar.right() + Math.max(4, layout.unit() / 2),
				header.y(),
				Math.max(1, close.x() - avatar.right() - pad),
				header.height()
		);
		drawEllipsizedVerticalText(graphics, sender != null ? sender.senderDisplayName() : "", name, new Color(248, 251, 255, 238), Font.BOLD, clampInt(layout.unit(), 9, 15));
		Color closeColor = drawMediaHeaderControlBase(graphics, close, MediaButtonSegment.SINGLE);
		drawPlayerUiIcon(graphics, mediaChromeIconRect(close, layout), PlayerUiIcon.CLOSE, closeColor);
	}

	private static void drawMaxNotificationItem(Graphics2D graphics, UiLayout layout, UiRect item, MaxIncomingFileSnapshot incoming) {
		if (graphics == null || layout == null || item == null || incoming == null || item.width() <= 0 || item.height() <= 0) {
			return;
		}
		UiRect accept = maxNotificationItemAcceptRect(item, layout);
		UiRect decline = maxNotificationItemDeclineRect(item, layout);
		UiRect preview = maxNotificationItemPreviewRect(item, incoming.previewFrame(), incoming.squarePreviewFallback(), layout);
		drawQueueThumbnail(graphics, preview, incoming.previewFrame(), incoming.squarePreviewFallback(), false, layout);
		drawNotificationPreviewTitle(graphics, layout, preview, incoming.fileName());
		if (incoming.previewActive() && incoming.previewPlayable()) {
			drawNotificationPreviewOverlay(graphics, layout, preview, incoming.previewPlaying(), incoming.previewLoading());
		}
		Color acceptColor = drawMediaHeaderControlBase(graphics, accept, MediaButtonSegment.SINGLE);
		drawPlayerUiIcon(graphics, mediaChromeIconRect(accept, layout), PlayerUiIcon.CHECK, acceptColor);
		Color declineColor = drawMediaHeaderControlBase(graphics, decline, MediaButtonSegment.SINGLE);
		drawPlayerUiIcon(graphics, mediaChromeIconRect(decline, layout), PlayerUiIcon.CLOSE, declineColor);
	}

	private static void drawNotificationPreviewTitle(Graphics2D graphics, UiLayout layout, UiRect preview, String title) {
		if (graphics == null || layout == null || preview == null || preview.width() <= 0 || preview.height() <= 0) {
			return;
		}
		int titleHeight = clampInt(layout.unit() * 2, 16, 28);
		UiRect titleRect = new UiRect(preview.x(), preview.bottom() - titleHeight, preview.width(), titleHeight);
		fillRoundedRect(graphics, titleRect, clampInt(Math.min(titleRect.width(), titleRect.height()) / 3, 5, 8), new Color(0, 0, 0, 132));
		drawEllipsizedVerticalText(graphics, title == null ? "" : title, titleRect.inset(Math.max(2, layout.unit() / 4)), new Color(248, 251, 255, 236), Font.BOLD, clampInt(layout.unit() - 1, 8, 13));
	}

	private static void drawNotificationPreviewOverlay(Graphics2D graphics, UiLayout layout, UiRect preview, boolean playing, boolean loading) {
		if (graphics == null || layout == null || preview == null || preview.width() <= 0 || preview.height() <= 0) {
			return;
		}
		int size = clampInt(Math.min(preview.width(), preview.height()) / 3, 24, 44);
		UiRect button = new UiRect(preview.x() + (preview.width() - size) / 2, preview.y() + (preview.height() - size) / 2, size, size);
		fillRoundedRect(graphics, button, size, new Color(0, 0, 0, loading ? 118 : 146));
		drawPlayerUiIcon(graphics, mediaChromeIconRect(button, layout), playing ? PlayerUiIcon.PAUSE : PlayerUiIcon.PLAY, new Color(248, 251, 255, loading ? 188 : 236));
	}

	private static void drawMaxAtmosphere(Graphics2D graphics, UiRect canvas, UiLayout layout) {
		graphics.setPaint(new GradientPaint(canvas.x(), canvas.y(), new Color(0, 184, 255, 40), canvas.right(), canvas.bottom(), new Color(255, 255, 255, 8)));
		graphics.fillRect(canvas.x(), canvas.y(), canvas.width(), canvas.height());
		graphics.setColor(new Color(255, 255, 255, 8));
		int step = clampInt(layout.unit() * 3, 18, 42);
		for (int x = canvas.x(); x < canvas.right(); x += step) {
			graphics.drawLine(x, canvas.y(), x, canvas.bottom());
		}
	}

	private static void drawMaxAvatar(Graphics2D graphics, UiRect rect, BufferedImage avatar, String seed, UiLayout layout) {
		if (avatar != null) {
			drawAvatar(graphics, rect, avatar, layout);
			return;
		}
		Shape previousClip = graphics.getClip();
		Ellipse2D.Float circle = new Ellipse2D.Float(rect.x(), rect.y(), rect.width(), rect.height());
		graphics.setClip(circle);
		graphics.setColor(maxDefaultAvatarColor(seed));
		graphics.fillOval(rect.x(), rect.y(), rect.width(), rect.height());
		int inset = Math.max(2, (int) Math.round(Math.min(rect.width(), rect.height()) * 0.22D));
		drawPlayerUiIcon(graphics, rect.inset(inset), PlayerUiIcon.USER_DEFAULT, new Color(255, 255, 255, 244));
		graphics.setClip(previousClip);
	}

	private static Color maxDefaultAvatarColor(String seed) {
		int hash = Objects.toString(seed, "MAX").hashCode();
		float hue = Math.floorMod(hash, 360) / 360.0F;
		return Color.getHSBColor(hue, 0.58F, 0.90F);
	}

	private static void drawAvatar(Graphics2D graphics, UiRect rect, BufferedImage avatar, UiLayout layout) {
		drawAvatar(graphics, rect, avatar, layout, true);
	}

	private static void drawAvatarNoStroke(Graphics2D graphics, UiRect rect, BufferedImage avatar, UiLayout layout) {
		drawAvatar(graphics, rect, avatar, layout, false);
	}

	private static void drawAvatar(Graphics2D graphics, UiRect rect, BufferedImage avatar, UiLayout layout, boolean stroke) {
		Shape previousClip = graphics.getClip();
		Ellipse2D.Float circle = new Ellipse2D.Float(rect.x(), rect.y(), rect.width(), rect.height());
		graphics.setClip(circle);
		if (avatar != null) {
			drawScaledImage(graphics, avatar, rect, MediaScaleMode.FILL);
		} else {
			graphics.setPaint(new GradientPaint(rect.x(), rect.y(), new Color(255, 255, 255, 36), rect.right(), rect.bottom(), new Color(255, 255, 255, 10)));
			graphics.fillOval(rect.x(), rect.y(), rect.width(), rect.height());
		}
		graphics.setClip(previousClip);
		if (!stroke) {
			return;
		}
		Stroke previousStroke = graphics.getStroke();
		graphics.setStroke(new BasicStroke(Math.max(1.0F, layout.unit() / 8.0F)));
		graphics.setColor(new Color(255, 255, 255, 86));
		graphics.drawOval(rect.x(), rect.y(), rect.width(), rect.height());
		graphics.setStroke(previousStroke);
	}

	private static void drawMaxAddContactButton(Graphics2D graphics, UiRect rect, UiLayout layout) {
		fillRoundedRect(graphics, rect, rect.height(), new Color(16, 142, 255));
		drawPlayerUiIcon(graphics, maxInlineActionIconRect(rect), PlayerUiIcon.USER_ADD, Color.WHITE);
	}

	private static UiRect maxInlineActionIconRect(UiRect button) {
		int size = clampInt((int) Math.round(Math.min(button.width(), button.height()) * 0.62D), 10, 24);
		return new UiRect(button.x() + (button.width() - size) / 2, button.y() + (button.height() - size) / 2, size, size);
	}

	private static void drawMaxParticipantAddContactButton(Graphics2D graphics, UiRect rect, UiLayout layout) {
		Color iconColor = drawSmallMediaButtonBase(
				graphics,
				rect,
				MediaButtonSegment.SINGLE,
				false,
				mediaChromeStrokeWidth(rect),
				new Color(255, 255, 255, 0)
		);
		drawPlayerUiIcon(graphics, mediaChromeIconRect(rect, layout), PlayerUiIcon.USER_ADD, iconColor);
	}

	private static void drawMaxRingtoneControls(Graphics2D graphics, UiLayout layout, MaxVisualSnapshot state) {
		UiRect play = maxRingtonePreviewRect(layout);
		UiRect picker = maxRingtonePickerOpenRect(layout);
		fillRoundedRect(graphics, play, play.height(), new Color(236, 244, 252));
		drawPlayerUiIcon(graphics, maxInlineActionIconRect(play), state.ringtonePreviewPlaying() ? PlayerUiIcon.PAUSE : PlayerUiIcon.PLAY, new Color(16, 142, 255));
		fillRoundedRect(graphics, picker, picker.height(), new Color(236, 244, 252));
		drawPlayerUiIcon(graphics, maxInlineActionIconRect(picker), PlayerUiIcon.FILE_MUSIC, new Color(16, 142, 255));
	}

	private static void drawMaxPresenceBadge(Graphics2D graphics, UiRect avatarRect, UiLayout layout) {
		int outer = clampInt((int) Math.round(Math.min(avatarRect.width(), avatarRect.height()) * 0.28D), 8, 13);
		int overlap = Math.max(1, outer / 4);
		int x = avatarRect.right() - outer + overlap;
		int y = avatarRect.bottom() - outer + overlap;
		graphics.setColor(Color.WHITE);
		graphics.fillOval(x, y, outer, outer);
		int ring = clampInt(outer / 5, 2, 3);
		graphics.setColor(new Color(45, 201, 116));
		graphics.fillOval(x + ring, y + ring, Math.max(2, outer - ring * 2), Math.max(2, outer - ring * 2));
	}

	private static void drawMaxFeedContactRow(Graphics2D graphics, UiLayout layout, UiRect rect, MaxContactSnapshot contact) {
		boolean singleTile = maxSingleTileLayout(layout);
		fillRoundedRect(graphics, rect, singleTile ? 8 : clampInt(layout.unit() * 2, 12, 22), Color.WHITE);
		int avatarInset = singleTile ? 3 : layout.unit();
		int avatarSize = singleTile ? Math.max(18, rect.height() - 6) : rect.height() - layout.unit();
		UiRect avatarRect = new UiRect(rect.x() + avatarInset, rect.y() + (rect.height() - avatarSize) / 2, avatarSize, avatarSize);
		drawMaxAvatar(graphics, avatarRect, contact.avatarFrame(), contact.code(), layout);
		if (contact.online()) {
			drawMaxPresenceBadge(graphics, avatarRect, layout);
		}

		UiRect deleteRect = maxContactDeleteRect(rect, layout);
		UiRect notificationRect = contact.notificationCount() > 0 ? maxContactNotificationRect(rect, layout, contact.notificationCount(), contact.savedContact()) : null;
		UiRect callRect = maxContactCallGlyphRect(rect, layout, notificationRect, contact.savedContact());
		int textGap = singleTile ? 2 : Math.max(4, layout.unit() / 2);
		int textX = avatarRect.right() + (singleTile ? 4 : layout.unit());
		int textRight = callRect.x() - textGap;
		UiRect nameRect = new UiRect(textX, rect.y() + (singleTile ? 3 : layout.unit() / 3), Math.max(1, textRight - textX), singleTile ? 12 : rect.height() / 2);
		drawEllipsizedVerticalText(graphics, contact.displayName(), nameRect, new Color(22, 27, 34), Font.BOLD, singleTile ? 7 : clampInt(layout.unit(), 10, 16));
		String status = contact.active() ? "в вызове" : contact.ringing() ? "звонит" : contact.online() ? "в сети" : "не в сети";
		Color statusColor = contact.online() ? new Color(45, 154, 94) : new Color(126, 134, 145);
		UiRect statusRect = singleTile
				? new UiRect(nameRect.x(), nameRect.bottom(), nameRect.width(), Math.max(8, rect.bottom() - nameRect.bottom() - 2))
				: new UiRect(nameRect.x(), nameRect.bottom(), nameRect.width(), rect.height() / 3);
		drawVerticalText(graphics, status, statusRect, statusColor, Font.PLAIN, singleTile ? 6 : clampInt(layout.unit() - 2, 7, 11));
		drawPlayerUiIcon(graphics, maxInlineActionIconRect(callRect), PlayerUiIcon.PHONE_CALL, new Color(16, 142, 255));
		if (notificationRect != null) {
			fillRoundedRect(graphics, notificationRect, notificationRect.height(), new Color(16, 142, 255));
			drawCenteredTextFitted(graphics, Integer.toString(Math.max(0, contact.notificationCount())), notificationRect.inset(singleTile ? 2 : Math.max(2, layout.unit() / 5)), Color.WHITE, Font.BOLD, singleTile ? 7 : clampInt(layout.unit() - 1, 8, 13), 6);
		}
		if (contact.savedContact()) {
			drawPlayerUiIcon(graphics, maxInlineActionIconRect(deleteRect), PlayerUiIcon.TRASH, new Color(167, 174, 184));
		}
	}

	private static void drawMaxContactFeedScrollbar(Graphics2D graphics, UiLayout layout, WindowedSnapshot<MaxContactSnapshot> contacts) {
		if (contacts == null || contacts.totalCount() <= contacts.items().size() || contacts.items().isEmpty()) {
			return;
		}
		UiRect list = maxContactListRect(layout);
		int trackWidth = clampInt(layout.unit() / 3, 2, 5);
		int inset = Math.max(2, layout.unit() / 4);
		UiRect track = new UiRect(list.right() - trackWidth, list.y() + inset, trackWidth, Math.max(1, list.height() - inset * 2));
		fillRoundedRect(graphics, track, trackWidth, new Color(220, 225, 232));
		int visible = contacts.items().size();
		int thumbHeight = Math.max(trackWidth * 2, (int) Math.round(track.height() * (visible / (double) contacts.totalCount())));
		int maxStart = Math.max(1, contacts.totalCount() - visible);
		int travel = Math.max(0, track.height() - thumbHeight);
		int thumbY = track.y() + (int) Math.round(travel * (contacts.windowStartIndex() / (double) maxStart));
		fillRoundedRect(graphics, new UiRect(track.x(), thumbY, track.width(), Math.min(thumbHeight, track.bottom() - thumbY)), trackWidth, new Color(149, 159, 173));
	}

	private static void drawMaxFeedEmptyContacts(Graphics2D graphics, UiLayout layout, UiRect rect) {
		if (maxSingleTileLayout(layout)) {
			int size = clampInt(Math.min(rect.width(), rect.height()) / 2, 24, 30);
			UiRect icon = new UiRect(rect.x() + (rect.width() - size) / 2, rect.y() + 3, size, size);
			graphics.setPaint(new GradientPaint(icon.x(), icon.y(), new Color(18, 145, 255), icon.right(), icon.bottom(), new Color(112, 88, 245)));
			graphics.fillOval(icon.x(), icon.y(), icon.width(), icon.height());
			drawPlayerUiIcon(graphics, icon.inset(Math.max(5, icon.width() / 5)), PlayerUiIcon.CONTACTS, Color.WHITE);
			UiRect title = new UiRect(rect.x() + 3, icon.bottom() + 2, rect.width() - 6, 12);
			drawCenteredTextFitted(graphics, "Нет контактов", title, new Color(22, 27, 34), Font.BOLD, 8, 6);
			UiRect subtitle = new UiRect(title.x(), title.bottom(), title.width(), Math.max(8, rect.bottom() - title.bottom()));
			drawCenteredTextFitted(graphics, "+ чтобы добавить", subtitle, new Color(116, 124, 136), Font.PLAIN, 6, 5);
			return;
		}
		int size = clampInt(Math.min(rect.width(), rect.height()) / 3, 44, 106);
		UiRect icon = new UiRect(rect.x() + (rect.width() - size) / 2, rect.y() + Math.max(layout.unit(), rect.height() / 8), size, size);
		graphics.setPaint(new GradientPaint(icon.x(), icon.y(), new Color(18, 145, 255), icon.right(), icon.bottom(), new Color(112, 88, 245)));
		graphics.fillOval(icon.x(), icon.y(), icon.width(), icon.height());
		drawPlayerUiIcon(graphics, icon.inset(Math.max(8, icon.width() / 5)), PlayerUiIcon.CONTACTS, Color.WHITE);
		UiRect title = new UiRect(rect.x() + layout.unit(), icon.bottom() + layout.unit(), rect.width() - layout.unit() * 2, clampInt(layout.unit() * 3, 26, 44));
		drawCenteredTextFitted(graphics, "Здесь пока нет контактов", title, new Color(22, 27, 34), Font.BOLD, clampInt(layout.unit() + 1, 11, 18), 8);
		UiRect subtitle = new UiRect(title.x(), title.bottom(), title.width(), clampInt(layout.unit() * 4, 34, 58));
		drawCenteredTextFitted(graphics, "Нажми +, добавь пользователя по MAX id или нику и начни звонок", subtitle, new Color(116, 124, 136), Font.PLAIN, clampInt(layout.unit() - 1, 8, 13), 6);
	}


	private static void drawMaxContactRow(Graphics2D graphics, UiLayout layout, UiRect rect, MaxContactSnapshot contact, boolean deleteVisible) {
		fillRoundedRect(graphics, rect, clampInt(layout.unit() * 2, 12, 24), new Color(8, 12, 16, 174));
		strokeRoundedRect(graphics, rect, clampInt(layout.unit() * 2, 12, 24), 1.0F, contact.online() ? new Color(255, 255, 255, 58) : new Color(255, 255, 255, 24));
		UiRect avatarRect = new UiRect(rect.x() + layout.unit(), rect.y() + layout.unit() / 2, rect.height() - layout.unit(), rect.height() - layout.unit());
		drawMaxAvatar(graphics, avatarRect, contact.avatarFrame(), contact.code(), layout);
		UiRect deleteRect = maxContactDeleteRect(rect, layout);
		boolean showDelete = deleteVisible && contact.savedContact();
		UiRect notificationRect = deleteVisible && contact.notificationCount() > 0 ? maxContactNotificationRect(rect, layout, contact.notificationCount(), showDelete) : null;
		int textRight = rect.right() - layout.unit();
		if (showDelete) {
			textRight = deleteRect.x();
		}
		if (notificationRect != null) {
			textRight = notificationRect.x();
		}
		UiRect codeRect = new UiRect(avatarRect.right() + layout.unit(), rect.y() + layout.unit() / 3, Math.max(1, textRight - avatarRect.right() - layout.unit() * 2), rect.height() / 2);
		drawEllipsizedVerticalText(graphics, contact.displayName(), codeRect, new Color(248, 251, 255, 238), Font.BOLD, clampInt(layout.unit(), 10, 16));
		String status = contact.active() ? "в вызове" : contact.ringing() ? "звонит" : contact.online() ? "доступен" : "недоступен";
		drawVerticalText(graphics, status, new UiRect(codeRect.x(), codeRect.bottom(), codeRect.width(), rect.height() / 3), new Color(178, 202, 218, 218), Font.PLAIN, clampInt(layout.unit() - 2, 7, 11));
		if (notificationRect != null) {
			drawMaxContactNotificationButton(graphics, notificationRect, contact.notificationCount(), layout);
		}
		if (showDelete) {
			Color deleteColor = drawMediaHeaderControlBase(graphics, deleteRect, MediaButtonSegment.SINGLE);
			drawPlayerUiIcon(graphics, mediaChromeIconRect(deleteRect, layout), PlayerUiIcon.TRASH, deleteColor);
		}
	}

	private static void drawMaxContactNotificationButton(Graphics2D graphics, UiRect rect, int count, UiLayout layout) {
		Color color = drawMediaHeaderControlBase(graphics, rect, MediaButtonSegment.SINGLE);
		drawCenteredTextFitted(graphics, Integer.toString(Math.max(0, count)), rect.inset(Math.max(1, layout.unit() / 6)), color, Font.BOLD, clampInt(layout.unit(), 9, 14), 6);
	}


	private static void drawMaxCallPillButton(Graphics2D graphics, UiRect rect, String label, Color fill, Color text, UiLayout layout, boolean accept) {
		fillRoundedRect(graphics, rect, rect.height(), fill != null ? fill : new Color(255, 255, 255, 0));
		strokeRoundedRect(graphics, rect, rect.height(), 1.1F, new Color(255, 255, 255, accept ? 136 : 76));
		drawCenteredTextFitted(graphics, label, rect, text, Font.BOLD, clampInt(layout.unit(), 10, 15), 7);
	}

	private static void drawMaxToggleButton(Graphics2D graphics, UiRect rect, String label, boolean active, UiLayout layout) {
		Color icon = drawSmallMediaButtonBase(graphics, rect, MediaButtonSegment.SINGLE, active, mediaChromeStrokeWidth(rect), active ? null : new Color(255, 255, 255, 0));
		drawCenteredTextFitted(graphics, label, rect, icon, Font.BOLD, clampInt(layout.unit() - 1, 8, 12), 6);
	}

	private static void drawMaxCameraSelect(Graphics2D graphics, UiRect rect, MaxCallVisualSnapshot call, UiLayout layout) {
		Color color = drawMediaHeaderControlBase(graphics, rect, MediaButtonSegment.SINGLE);
		String label = "КАМЕРА";
		if (call.cameras() != null && call.selectedCameraIndex() >= 0 && call.selectedCameraIndex() < call.cameras().size()) {
			MaxCameraOptionSnapshot selected = call.cameras().get(call.selectedCameraIndex());
			label = selected.title() + " " + selected.subtitle();
		}
		drawPlayerUiIcon(graphics, new UiRect(rect.x() + clampInt(layout.unit() / 2, 5, 8), rect.y() + rect.height() / 4, rect.height() / 2, rect.height() / 2), PlayerUiIcon.CAMERA, color);
		drawVerticalText(graphics, label, new UiRect(rect.x() + rect.height(), rect.y(), rect.width() - rect.height() - layout.unit() / 2, rect.height()), color, Font.BOLD, clampInt(layout.unit() - 2, 7, 11));
	}


	private static boolean maxSingleTileLayout(UiLayout layout) {
		return layout != null && layout.canvasWidth() <= MAP_SIZE && layout.canvasHeight() <= MAP_SIZE;
	}

	private static UiRect maxContactsTitleRect(UiLayout layout) {
		UiRect canvas = mediaCanvasRect(layout);
		UiRect close = mediaCloseRect(layout);
		UiRect add = maxAddContactRect(layout);
		if (maxSingleTileLayout(layout)) {
			int top = canvas.y() + 2;
			int left = close.right() + 4;
			return new UiRect(left, top, Math.max(1, add.x() - left - 3), 16);
		}
		int top = canvas.y() + Math.max(3, layout.unit() / 2);
		int height = clampInt(layout.unit() * 3, 30, 46);
		int left = close.right() + Math.max(6, layout.unit());
		return new UiRect(left, top, Math.max(1, add.x() - left - layout.unit()), height);
	}

	private static UiRect maxProfilePanelRect(UiLayout layout) {
		UiRect canvas = mediaCanvasRect(layout);
		UiRect title = maxContactsTitleRect(layout);
		if (maxSingleTileLayout(layout)) {
			int top = title.bottom() + 2;
			return new UiRect(canvas.x() + 2, top, Math.max(1, canvas.width() - 4), 28);
		}
		int top = title.bottom() + Math.max(4, layout.unit() / 2);
		return new UiRect(canvas.x() + layout.unit(), top, canvas.width() - layout.unit() * 2, clampInt(layout.unit() * 6, 56, 82));
	}

	private static UiRect maxProfileAvatarRect(UiLayout layout) {
		UiRect panel = maxProfilePanelRect(layout);
		int inset = maxSingleTileLayout(layout) ? 3 : Math.max(5, layout.unit() / 2);
		int size = panel.height() - inset * 2;
		return new UiRect(panel.x() + inset, panel.y() + inset, size, size);
	}

	private static UiRect maxAppTitleRect(UiLayout layout) {
		UiRect avatar = maxProfileAvatarRect(layout);
		UiRect panel = maxProfilePanelRect(layout);
		UiRect controls = maxRingtoneControlsRect(layout);
		if (maxSingleTileLayout(layout)) {
			int x = avatar.right() + 4;
			return new UiRect(x, panel.y() + 3, Math.max(1, controls.x() - x - 3), panel.height() - 6);
		}
		int x = avatar.right() + layout.unit();
		return new UiRect(x, panel.y() + layout.unit() / 2, Math.max(1, controls.x() - x - layout.unit()), panel.height() / 2);
	}

	private static UiRect maxProfileCodeRect(UiLayout layout) {
		if (maxSingleTileLayout(layout)) {
			return emptyRect();
		}
		UiRect title = maxAppTitleRect(layout);
		UiRect panel = maxProfilePanelRect(layout);
		return new UiRect(title.x(), title.bottom() - Math.max(1, layout.unit() / 5), title.width(), panel.height() / 3);
	}

	private static UiRect maxAddContactRect(UiLayout layout) {
		UiRect canvas = mediaCanvasRect(layout);
		if (maxSingleTileLayout(layout)) {
			int size = 18;
			return new UiRect(canvas.right() - size - 2, canvas.y() + 1, size, size);
		}
		int size = clampInt(layout.unit() * 2 + 4, 24, 34);
		int top = canvas.y() + Math.max(4, layout.unit() / 2) + Math.max(0, (clampInt(layout.unit() * 3, 30, 46) - size) / 2);
		return new UiRect(canvas.right() - size - layout.unit(), top, size, size);
	}

	private static UiRect maxRingtoneControlsRect(UiLayout layout) {
		UiRect panel = maxProfilePanelRect(layout);
		if (maxSingleTileLayout(layout)) {
			int size = 18;
			int gap = 2;
			int width = size * 2 + gap;
			return new UiRect(panel.right() - width - 3, panel.y() + (panel.height() - size) / 2, width, size);
		}
		int size = clampInt(layout.unit() * 2 + 4, 24, 34);
		int gap = Math.max(4, layout.unit() / 2);
		int width = size * 2 + gap;
		return new UiRect(panel.right() - width - layout.unit(), panel.y() + (panel.height() - size) / 2, width, size);
	}

	private static UiRect maxRingtonePreviewRect(UiLayout layout) {
		UiRect controls = maxRingtoneControlsRect(layout);
		return new UiRect(controls.x(), controls.y(), controls.height(), controls.height());
	}

	private static UiRect maxRingtonePickerOpenRect(UiLayout layout) {
		UiRect controls = maxRingtoneControlsRect(layout);
		int gap = maxSingleTileLayout(layout) ? 2 : Math.max(4, layout.unit() / 2);
		return new UiRect(controls.x() + controls.height() + gap, controls.y(), controls.height(), controls.height());
	}

	private static UiRect maxContactListRect(UiLayout layout) {
		UiRect canvas = mediaCanvasRect(layout);
		UiRect profile = maxProfilePanelRect(layout);
		if (maxSingleTileLayout(layout)) {
			int y = profile.bottom() + 2;
			return new UiRect(canvas.x() + 2, y, Math.max(1, canvas.width() - 4), Math.max(1, canvas.bottom() - y - 2));
		}
		int y = profile.bottom() + Math.max(4, layout.unit() / 2);
		return new UiRect(canvas.x() + layout.unit(), y, canvas.width() - layout.unit() * 2, Math.max(1, canvas.bottom() - y - layout.unit()));
	}

	private static int maxVisibleContactRows(UiLayout layout) {
		UiRect list = maxContactListRect(layout);
		int row = maxContactRowHeight(layout) + layout.unit() / 2;
		return Math.max(1, list.height() / Math.max(1, row));
	}

	private static int maxContactRowHeight(UiLayout layout) {
		return maxSingleTileLayout(layout) ? 30 : clampInt(layout.unit() * 5, 46, 70);
	}

	private static UiRect maxContactRowRect(UiLayout layout, int index) {
		UiRect list = maxContactListRect(layout);
		int gap = maxSingleTileLayout(layout) ? 2 : Math.max(4, layout.unit() / 2);
		int height = maxContactRowHeight(layout);
		return new UiRect(list.x(), list.y() + index * (height + gap), list.width(), height);
	}

	private static UiRect maxContactDeleteRect(UiLayout layout, int index) {
		return maxContactDeleteRect(maxContactRowRect(layout, index), layout);
	}

	private static UiRect maxContactDeleteRect(UiRect row, UiLayout layout) {
		int size = maxSingleTileLayout(layout) ? 14 : clampInt(layout.unit() * 2 + 4, 24, 34);
		int inset = maxSingleTileLayout(layout) ? 2 : layout.unit();
		return new UiRect(row.right() - size - inset, row.y() + (row.height() - size) / 2, size, size);
	}

	private static UiRect maxContactNotificationRect(UiRect row, UiLayout layout, int count, boolean deleteVisible) {
		boolean singleTile = maxSingleTileLayout(layout);
		int size = singleTile ? 16 : clampInt(layout.unit() * 2 + 4, 24, 34);
		int digits = Integer.toString(Math.max(0, count)).length();
		int width = size + Math.max(0, digits - 2) * (singleTile ? 3 : Math.max(4, layout.unit() / 2));
		int gap = singleTile ? 2 : Math.max(4, layout.unit() / 2);
		int right = deleteVisible ? maxContactDeleteRect(row, layout).x() - gap : row.right() - (singleTile ? 2 : layout.unit());
		return new UiRect(right - width, row.y() + (row.height() - size) / 2, width, size);
	}

	private static UiRect maxContactCallGlyphRect(UiRect row, UiLayout layout, UiRect notificationRect, boolean deleteVisible) {
		boolean singleTile = maxSingleTileLayout(layout);
		int size = singleTile ? 16 : clampInt(layout.unit() * 2 + 4, 24, 34);
		int gap = singleTile ? 2 : Math.max(4, layout.unit() / 2);
		int right;
		if (notificationRect != null) {
			right = notificationRect.x() - gap;
		} else if (deleteVisible) {
			right = maxContactDeleteRect(row, layout).x() - gap;
		} else {
			right = row.right() - (singleTile ? 2 : layout.unit());
		}
		return new UiRect(right - size, row.y() + (row.height() - size) / 2, size, size);
	}

	private static int maxContactIndexAt(UiLayout layout, int contactCount, UiPoint point) {
		int count = Math.min(contactCount, maxVisibleContactRows(layout));
		for (int index = 0; index < count; index++) {
			if (maxContactRowRect(layout, index).contains(point.x(), point.y())) {
				return index;
			}
		}
		return -1;
	}


	private static UiRect maxIncomingAvatarRect(UiLayout layout) {
		UiRect canvas = mediaCanvasRect(layout);
		int size = clampInt(Math.min(canvas.width(), canvas.height()) / 4, 30, 132);
		return new UiRect(canvas.x() + (canvas.width() - size) / 2, canvas.y() + canvas.height() / 2 - size, size, size);
	}

	private static UiRect maxIncomingCodeRect(UiLayout layout) {
		UiRect avatar = maxIncomingAvatarRect(layout);
		return new UiRect(mediaCanvasRect(layout).x() + layout.unit(), avatar.bottom() + layout.unit(), mediaCanvasRect(layout).width() - layout.unit() * 2, clampInt(layout.unit() * 3, 28, 48));
	}

	private static UiRect maxIncomingSubtitleRect(UiLayout layout) {
		UiRect code = maxIncomingCodeRect(layout);
		return new UiRect(code.x(), code.bottom(), code.width(), clampInt(layout.unit() * 2, 18, 30));
	}

	private static UiRect maxIncomingAcceptRect(UiLayout layout) {
		UiRect canvas = mediaCanvasRect(layout);
		int gap = clampInt(layout.unit(), 6, 20);
		int fit = Math.max(14, (canvas.width() - gap - clampInt(layout.unit(), 4, 12) * 2) / 2);
		int size = Math.min(clampInt(Math.min(canvas.width(), canvas.height()) / 5, 18, 58), fit);
		int y = canvas.bottom() - size - clampInt(layout.unit() * 2, 10, 34);
		return new UiRect(canvas.x() + (canvas.width() - size * 2 - gap) / 2, y, size, size);
	}

	private static UiRect maxIncomingDeclineRect(UiLayout layout) {
		UiRect accept = maxIncomingAcceptRect(layout);
		return new UiRect(accept.right() + clampInt(layout.unit(), 6, 20), accept.y(), accept.width(), accept.height());
	}

	private static UiRect maxOutgoingCancelRect(UiLayout layout) {
		UiRect canvas = mediaCanvasRect(layout);
		int size = clampInt(Math.min(canvas.width(), canvas.height()) / 5, 18, 58);
		return new UiRect(canvas.x() + (canvas.width() - size) / 2, canvas.bottom() - size - clampInt(layout.unit() * 2, 10, 34), size, size);
	}

	private static UiRect maxCallFocusedTileRect(UiLayout layout) {
		UiRect canvas = mediaCanvasRect(layout);
		int inset = clampInt(layout.unit() / 2, 1, 6);
		return new UiRect(canvas.x() + inset, canvas.y() + inset, canvas.width() - inset * 2, canvas.height() - inset * 2);
	}

	private static UiRect maxCallParticipantAddContactRect(UiRect tile, UiLayout layout) {
		if (tile == null || tile.width() <= 0 || tile.height() <= 0) {
			return emptyRect();
		}
		int inset = clampInt(layout.unit() / 2, 2, 8);
		int size = maxCallMenuButtonSize(layout);
		return new UiRect(tile.right() - inset - size, tile.y() + inset, size, size);
	}

	private static UiRect maxCallParticipantsAreaRect(UiLayout layout, boolean menuOpen) {
		UiRect canvas = mediaCanvasRect(layout);
		int inset = clampInt(layout.unit() / 2, 1, 6);
		int bottom = canvas.bottom() - inset;
		if (menuOpen) {
			bottom -= maxCallMenuButtonSize(layout) + clampInt(layout.unit(), 4, 10);
		}
		int top = canvas.y() + inset;
		if (bottom <= top + 8) {
			bottom = canvas.bottom() - inset;
		}
		return new UiRect(canvas.x() + inset, top, Math.max(1, canvas.width() - inset * 2), Math.max(1, bottom - top));
	}

	private static int maxCallGridGap(UiLayout layout) {
		return clampInt(layout.unit() / 2, 2, 8);
	}

	private static int maxCallGridColumns(int count, UiRect area, int gap) {
		if (count <= 1) {
			return 1;
		}
		int bestColumns = 1;
		double bestScore = Double.MAX_VALUE;
		for (int columns = 1; columns <= count; columns++) {
			int rows = (count + columns - 1) / columns;
			int cellWidth = (area.width() - gap * (columns - 1)) / columns;
			int cellHeight = (area.height() - gap * (rows - 1)) / rows;
			if (cellWidth <= 0 || cellHeight <= 0) {
				continue;
			}
			double aspect = cellWidth / (double) cellHeight;
			double score = Math.abs(Math.log(Math.max(0.01D, aspect))) - Math.min(cellWidth, cellHeight) * 0.002D;
			if (score < bestScore) {
				bestScore = score;
				bestColumns = columns;
			}
		}
		return Math.max(1, bestColumns);
	}

	private static UiRect maxCallParticipantTileRect(UiLayout layout, int participantCount, int index, boolean menuOpen) {
		if (participantCount <= 0 || index < 0 || index >= participantCount) {
			return emptyRect();
		}
		UiRect area = maxCallParticipantsAreaRect(layout, menuOpen);
		int gap = maxCallGridGap(layout);
		int columns = maxCallGridColumns(participantCount, area, gap);
		int rows = (participantCount + columns - 1) / columns;
		int cellWidth = Math.max(1, (area.width() - gap * (columns - 1)) / columns);
		int cellHeight = Math.max(1, (area.height() - gap * (rows - 1)) / rows);
		int row = index / columns;
		int itemInRow = index % columns;
		int itemsInRow = Math.min(columns, participantCount - row * columns);
		int rowWidth = itemsInRow * cellWidth + Math.max(0, itemsInRow - 1) * gap;
		int gridHeight = rows * cellHeight + Math.max(0, rows - 1) * gap;
		int x = area.x() + (area.width() - rowWidth) / 2 + itemInRow * (cellWidth + gap);
		int y = area.y() + (area.height() - gridHeight) / 2 + row * (cellHeight + gap);
		return new UiRect(x, y, cellWidth, cellHeight);
	}

	private static int maxCallParticipantIndexAt(UiLayout layout, int participantCount, boolean menuOpen, UiPoint point) {
		for (int index = 0; index < participantCount; index++) {
			if (maxCallParticipantTileRect(layout, participantCount, index, menuOpen).contains(point.x(), point.y())) {
				return index;
			}
		}
		return -1;
	}

	private static int maxCallMiniParticipantWidth(UiLayout layout) {
		UiRect canvas = mediaCanvasRect(layout);
		int preferred = canvas.width() <= 640
				? Math.round(canvas.width() / 5.3F)
				: Math.round(canvas.width() / 4.0F);
		return clampInt(preferred, 24, 220);
	}

	private static UiRect maxCallMiniStripViewportRect(UiLayout layout, int participantCount) {
		if (participantCount <= 0) {
			return emptyRect();
		}
		UiRect canvas = mediaCanvasRect(layout);
		int inset = clampInt(layout.unit() / 2, 2, 8);
		int gap = maxCallGridGap(layout);
		int buttonSize = maxCallMenuButtonSize(layout);
		int controlGroupWidth = buttonSize * 2;
		int preferredWidth = maxCallMiniParticipantWidth(layout) + scrollbarGutterWidth(layout);
		int availableWidth = Math.max(24, canvas.width() - inset * 2 - controlGroupWidth - gap);
		int viewportWidth = Math.min(preferredWidth, availableWidth);
		int x = canvas.x() + inset;
		int top = canvas.y() + inset;
		int bottom = maxCallMenuDockRect(layout, false, true).y() - gap;
		return new UiRect(x, top, Math.max(24, viewportWidth), Math.max(20, bottom - top));
	}

	private static UiRect maxCallMiniStripListRect(UiLayout layout, int participantCount) {
		return scrollContentRect(maxCallMiniStripViewportRect(layout, participantCount), layout);
	}

	private static UiRect maxCallMiniStripTrackRect(UiLayout layout, int participantCount) {
		return scrollTrackRect(maxCallMiniStripViewportRect(layout, participantCount), layout);
	}

	private static int maxCallMiniParticipantHeight(UiLayout layout, int participantCount) {
		UiRect list = maxCallMiniStripListRect(layout, participantCount);
		return clampInt((int) Math.round(list.width() * 9.0D / 16.0D), 18, 128);
	}

	private static int maxCallMiniParticipantStride(UiLayout layout, int participantCount) {
		return maxCallMiniParticipantHeight(layout, participantCount) + maxCallGridGap(layout);
	}

	private static int maxCallMiniParticipantVisibleRows(UiLayout layout, int participantCount) {
		UiRect list = maxCallMiniStripListRect(layout, participantCount);
		return Math.max(1, list.height() / Math.max(1, maxCallMiniParticipantStride(layout, participantCount)));
	}

	private static int maxCallMiniParticipantScroll(UiLayout layout, int participantCount) {
		return Math.max(0, participantCount - maxCallMiniParticipantVisibleRows(layout, participantCount));
	}

	private static UiRect maxCallMiniParticipantRect(UiLayout layout, int visibleIndex, int participantCount) {
		UiRect list = maxCallMiniStripListRect(layout, participantCount);
		int height = maxCallMiniParticipantHeight(layout, participantCount);
		int stride = maxCallMiniParticipantStride(layout, participantCount);
		return new UiRect(list.x(), list.y() + visibleIndex * stride, list.width(), height);
	}

	private static int maxCallMiniParticipantIndexAt(UiLayout layout, int participantCount, int scroll, UiPoint point) {
		int rowCount = Math.min(maxCallMiniParticipantVisibleRows(layout, participantCount) + 1, Math.max(0, participantCount - clampInt(scroll, 0, maxCallMiniParticipantScroll(layout, participantCount))));
		for (int visibleIndex = 0; visibleIndex < rowCount; visibleIndex++) {
			if (maxCallMiniParticipantRect(layout, visibleIndex, participantCount).contains(point.x(), point.y())) {
				return clampInt(scroll, 0, maxCallMiniParticipantScroll(layout, participantCount)) + visibleIndex;
			}
		}
		return -1;
	}

	private static UiRect maxCallFocusedTopControlsGroupRect(UiLayout layout, int miniParticipantCount, boolean miniParticipantsHidden) {
		UiRect canvas = mediaCanvasRect(layout);
		int inset = clampInt(layout.unit() / 2, 2, 8);
		int gap = maxCallGridGap(layout);
		int size = maxCallMenuButtonSize(layout);
		boolean showMiniToggle = miniParticipantCount > 0;
		int groupWidth = showMiniToggle ? size * 2 : size;
		int x;
		if (showMiniToggle && !miniParticipantsHidden) {
			x = maxCallMiniStripViewportRect(layout, miniParticipantCount).right() + gap;
		} else {
			x = canvas.x() + inset;
		}
		return new UiRect(x, canvas.y() + inset, groupWidth, size);
	}

	private static UiRect maxCallMiniStripToggleRect(UiLayout layout, int miniParticipantCount, boolean miniParticipantsHidden) {
		if (miniParticipantCount <= 0) {
			return emptyRect();
		}
		UiRect group = maxCallFocusedTopControlsGroupRect(layout, miniParticipantCount, miniParticipantsHidden);
		int size = maxCallMenuButtonSize(layout);
		return new UiRect(group.x(), group.y(), size, size);
	}

	private static UiRect maxCallGridExitRect(UiLayout layout, int miniParticipantCount, boolean miniParticipantsHidden) {
		UiRect group = maxCallFocusedTopControlsGroupRect(layout, miniParticipantCount, miniParticipantsHidden);
		int size = maxCallMenuButtonSize(layout);
		boolean showMiniToggle = miniParticipantCount > 0;
		return new UiRect(showMiniToggle ? group.right() - size : group.x(), group.y(), size, size);
	}

	private static UiRect maxCallMenuDockRect(UiLayout layout, MaxCallVisualSnapshot call) {
		return maxCallMenuDockRect(layout, call.microphoneCount() > 1, call.selfFocused() || call.peerFocused());
	}

	private static UiRect maxCallMenuDockRect(UiLayout layout, boolean multiMicrophone, boolean focused) {
		UiRect canvas = mediaCanvasRect(layout);
		int size = maxCallMenuButtonSize(layout);
		int gap = maxCallGridGap(layout);
		int padding = clampInt(layout.unit() / 2, 2, 6);
		int micWidth = multiMicrophone ? size * 2 : size;
		int width = micWidth + gap + size * 2 + gap + size + gap + size;
		int dockWidth = Math.min(canvas.width() - padding * 2, width + padding * 2);
		int dockHeight = size + padding * 2;
		int x = canvas.x() + (canvas.width() - dockWidth) / 2;
		int y = canvas.bottom() - dockHeight - clampInt(layout.unit() / 2, 2, 8);
		return new UiRect(x, y, Math.max(1, dockWidth), Math.max(1, dockHeight));
	}

	private static int maxCallMenuButtonSize(UiLayout layout) {
		UiRect canvas = mediaCanvasRect(layout);
		int gap = maxCallGridGap(layout);
		int padding = clampInt(layout.unit() / 2, 2, 6);
		int fit = (canvas.width() - padding * 2 - gap * 3) / 6;
		int desired = clampInt(layout.unit() * 2, 16, 34);
		return Math.max(10, Math.min(desired, fit));
	}

	private static UiRect maxCallMicrophoneToggleRect(UiLayout layout, MaxCallVisualSnapshot call) {
		return maxCallMicrophoneToggleRect(layout, call.microphoneCount() > 1, call.selfFocused() || call.peerFocused());
	}

	private static UiRect maxCallMicrophoneToggleRect(UiLayout layout, boolean multiMicrophone) {
		return maxCallMicrophoneToggleRect(layout, multiMicrophone, false);
	}

	private static UiRect maxCallMicrophoneToggleRect(UiLayout layout, boolean multiMicrophone, boolean focused) {
		UiRect dock = maxCallMenuDockRect(layout, multiMicrophone, focused);
		int size = maxCallMenuButtonSize(layout);
		int padding = Math.max(1, (dock.height() - size) / 2);
		return new UiRect(dock.x() + padding, dock.y() + padding, size, size);
	}

	private static UiRect maxCallMicrophoneSelectRect(UiLayout layout, MaxCallVisualSnapshot call) {
		return maxCallMicrophoneSelectRect(layout, call.selfFocused() || call.peerFocused());
	}

	private static UiRect maxCallMicrophoneSelectRect(UiLayout layout) {
		return maxCallMicrophoneSelectRect(layout, false);
	}

	private static UiRect maxCallMicrophoneSelectRect(UiLayout layout, boolean focused) {
		UiRect mic = maxCallMicrophoneToggleRect(layout, true, focused);
		return new UiRect(mic.right(), mic.y(), mic.width(), mic.height());
	}

	private static UiRect maxCallCameraToggleRect(UiLayout layout) {
		return maxCallCameraToggleRect(layout, true);
	}

	private static UiRect maxCallCameraToggleRect(UiLayout layout, MaxCallVisualSnapshot call) {
		return maxCallCameraToggleRect(layout, call.microphoneCount() > 1, call.selfFocused() || call.peerFocused());
	}

	private static UiRect maxCallCameraToggleRect(UiLayout layout, boolean multiMicrophone) {
		return maxCallCameraToggleRect(layout, multiMicrophone, false);
	}

	private static UiRect maxCallCameraToggleRect(UiLayout layout, boolean multiMicrophone, boolean focused) {
		UiRect dock = maxCallMenuDockRect(layout, multiMicrophone, focused);
		int size = maxCallMenuButtonSize(layout);
		int gap = maxCallGridGap(layout);
		UiRect mic = multiMicrophone ? maxCallMicrophoneSelectRect(layout, focused) : maxCallMicrophoneToggleRect(layout, false, focused);
		return new UiRect(mic.right() + gap, dock.y() + Math.max(1, (dock.height() - size) / 2), size, size);
	}

	private static UiRect maxCallCameraSelectRect(UiLayout layout) {
		return maxCallCameraSelectRect(layout, true);
	}

	private static UiRect maxCallCameraSelectRect(UiLayout layout, MaxCallVisualSnapshot call) {
		return maxCallCameraSelectRect(layout, call.microphoneCount() > 1, call.selfFocused() || call.peerFocused());
	}

	private static UiRect maxCallCameraSelectRect(UiLayout layout, boolean multiMicrophone) {
		return maxCallCameraSelectRect(layout, multiMicrophone, false);
	}

	private static UiRect maxCallCameraSelectRect(UiLayout layout, boolean multiMicrophone, boolean focused) {
		UiRect camera = maxCallCameraToggleRect(layout, multiMicrophone, focused);
		return new UiRect(camera.right(), camera.y(), camera.width(), camera.height());
	}

	private static UiRect maxCallInviteRect(UiLayout layout, MaxCallVisualSnapshot call) {
		return maxCallInviteRect(layout, call.microphoneCount() > 1, call.selfFocused() || call.peerFocused());
	}

	private static UiRect maxCallInviteRect(UiLayout layout, boolean multiMicrophone, boolean focused) {
		int gap = maxCallGridGap(layout);
		UiRect camera = maxCallCameraSelectRect(layout, multiMicrophone, focused);
		return new UiRect(camera.right() + gap, camera.y(), camera.width(), camera.height());
	}

	private static UiRect maxCallLeaveRect(UiLayout layout) {
		return maxCallLeaveRect(layout, true, true);
	}

	private static UiRect maxCallLeaveRect(UiLayout layout, MaxCallVisualSnapshot call) {
		return maxCallLeaveRect(layout, call.microphoneCount() > 1, call.selfFocused() || call.peerFocused());
	}

	private static UiRect maxCallLeaveRect(UiLayout layout, boolean multiMicrophone, boolean focused) {
		int size = maxCallMenuButtonSize(layout);
		int gap = maxCallGridGap(layout);
		UiRect previous = maxCallInviteRect(layout, multiMicrophone, focused);
		return new UiRect(previous.right() + gap, previous.y(), size, size);
	}

	private static UiRect maxCallExitFullscreenRect(UiLayout layout, MaxCallVisualSnapshot call) {
		return maxCallExitFullscreenRect(layout, call.microphoneCount() > 1, call.selfFocused() || call.peerFocused());
	}

	private static UiRect maxCallExitFullscreenRect(UiLayout layout, boolean multiMicrophone, boolean focused) {
		if (!focused) {
			return emptyRect();
		}
		int gap = maxCallGridGap(layout);
		UiRect invite = maxCallInviteRect(layout, multiMicrophone, true);
		return new UiRect(invite.right() + gap, invite.y(), invite.width(), invite.height());
	}

	private static UiRect emptyRect() {
		return new UiRect(0, 0, 0, 0);
	}

	private static UiRect maxAvatarPickerPanelRect(UiLayout layout) {
		UiRect canvas = mediaCanvasRect(layout);
		if (ultraCompactScreenLayout(layout)) {
			int inset = Math.max(2, layout.unit() / 3);
			return new UiRect(canvas.x() + inset, canvas.y() + inset, canvas.width() - inset * 2, canvas.height() - inset * 2);
		}
		return new UiRect(canvas.x() + layout.unit(), canvas.y() + clampInt(layout.unit() * 3, 24, 48), canvas.width() - layout.unit() * 2, canvas.height() - clampInt(layout.unit() * 4, 32, 60));
	}

	private static UiRect maxOverlayCloseRect(UiLayout layout) {
		return overlayPanelCloseRect(maxAvatarPickerPanelRect(layout), layout);
	}

	private static UiRect maxCallContactPickerCloseRect(UiLayout layout) {
		return overlayPanelCloseRect(maxAvatarPickerPanelRect(layout), layout);
	}

	private static UiRect maxCallContactPickerAddRect(UiLayout layout) {
		UiRect close = maxCallContactPickerCloseRect(layout);
		int gap = Math.max(4, layout.unit() / 2);
		return new UiRect(close.x() - close.width() - gap, close.y(), close.width(), close.height());
	}

	private static UiRect maxAvatarPickerTitleRect(UiLayout layout) {
		return overlayPanelTitleRect(maxAvatarPickerPanelRect(layout), maxOverlayCloseRect(layout), layout);
	}

	private static UiRect maxCallContactPickerTitleRect(UiLayout layout) {
		UiRect panel = maxAvatarPickerPanelRect(layout);
		UiRect add = maxCallContactPickerAddRect(layout);
		return new UiRect(panel.x() + layout.unit(), add.y(), Math.max(1, add.x() - panel.x() - layout.unit() * 2), add.height());
	}

	private static UiRect maxAvatarPickerGridRect(UiLayout layout) {
		return overlayPanelContentRect(maxAvatarPickerPanelRect(layout), maxOverlayCloseRect(layout), layout);
	}

	private static UiRect maxCallContactPickerGridRect(UiLayout layout) {
		UiRect panel = maxAvatarPickerPanelRect(layout);
		UiRect close = maxCallContactPickerCloseRect(layout);
		int y = close.bottom() + layout.unit();
		return new UiRect(panel.x() + layout.unit(), y, panel.width() - layout.unit() * 2, panel.bottom() - y - layout.unit());
	}

	private static int maxAvatarPickerColumns(UiLayout layout) {
		return compactScreenLayout(layout) ? 3 : 4;
	}

	private static int maxAvatarPickerCapacity(UiLayout layout) {
		UiRect grid = maxAvatarPickerGridRect(layout);
		int columns = maxAvatarPickerColumns(layout);
		int gap = Math.max(4, layout.unit() / 2);
		int cell = Math.max(1, (grid.width() - gap * (columns - 1)) / columns);
		int rows = clippedListCapacity(grid, cell, gap, false);
		return rows * columns;
	}

	private static UiRect maxAvatarCandidateRect(UiLayout layout, int index) {
		UiRect grid = maxAvatarPickerGridRect(layout);
		int columns = maxAvatarPickerColumns(layout);
		int gap = Math.max(4, layout.unit() / 2);
		int cell = Math.max(1, (grid.width() - gap * (columns - 1)) / columns);
		int row = index / columns;
		int column = index % columns;
		return new UiRect(grid.x() + column * (cell + gap), grid.y() + row * (cell + gap), cell, cell);
	}

	private static int maxAvatarPickerRowStep(UiLayout layout) {
		UiRect grid = maxAvatarPickerGridRect(layout);
		int columns = maxAvatarPickerColumns(layout);
		int gap = Math.max(4, layout.unit() / 2);
		int cell = Math.max(1, (grid.width() - gap * (columns - 1)) / columns);
		return cell + gap;
	}

	private static int maxAvatarCandidateIndexAt(UiLayout layout, int candidateCount, int scroll, UiPoint point) {
		UiRect grid = maxAvatarPickerGridRect(layout);
		if (point == null || !grid.contains(point.x(), point.y())) {
			return -1;
		}
		int count = Math.min(Math.max(0, candidateCount - scroll), maxAvatarPickerCapacity(layout));
		for (int index = 0; index < count; index++) {
			if (maxAvatarCandidateRect(layout, index).contains(point.x(), point.y())) {
				return scroll + index;
			}
		}
		return -1;
	}

	private static int maxAvatarPickerVisibleRows(UiLayout layout) {
		return Math.max(1, maxAvatarPickerCapacity(layout) / Math.max(1, maxAvatarPickerColumns(layout)));
	}

	private static int maxAvatarPickerScroll(UiLayout layout, int candidateCount) {
		int columns = Math.max(1, maxAvatarPickerColumns(layout));
		int totalRows = Math.max(1, (int) Math.ceil(Math.max(0, candidateCount) / (double) columns));
		int maxRowScroll = Math.max(0, totalRows - maxAvatarPickerVisibleRows(layout));
		return maxRowScroll * columns;
	}

	private static UiRect maxCallDeviceContentRect(UiLayout layout) {
		return overlayPanelContentRect(maxAvatarPickerPanelRect(layout), maxOverlayCloseRect(layout), layout);
	}

	private static UiRect maxCallDeviceCameraTitleRect(UiLayout layout) {
		UiRect content = maxCallDeviceContentRect(layout);
		int height = ultraCompactScreenLayout(layout) ? clampInt(layout.unit() + 3, 7, 8) : clampInt(layout.unit() + 4, 14, 22);
		return new UiRect(content.x(), content.y(), content.width(), height);
	}

	private static UiRect maxCallDeviceCameraGridRect(UiLayout layout) {
		UiRect content = maxCallDeviceContentRect(layout);
		UiRect title = maxCallDeviceCameraTitleRect(layout);
		int gap = maxCallDeviceGap(layout);
		int height = maxCallDeviceCameraHeight(layout);
		return new UiRect(content.x(), title.bottom() + gap, content.width(), height);
	}

	private static UiRect maxCallDeviceMicrophoneTitleRect(UiLayout layout) {
		UiRect cameraGrid = maxCallDeviceCameraGridRect(layout);
		UiRect content = maxCallDeviceContentRect(layout);
		int gap = maxCallDeviceGap(layout);
		int height = ultraCompactScreenLayout(layout) ? clampInt(layout.unit() + 3, 7, 8) : clampInt(layout.unit() + 4, 14, 22);
		return new UiRect(content.x(), cameraGrid.bottom() + gap, content.width(), height);
	}

	private static UiRect maxCallDeviceMicrophoneListRect(UiLayout layout) {
		UiRect content = maxCallDeviceContentRect(layout);
		UiRect title = maxCallDeviceMicrophoneTitleRect(layout);
		int gap = maxCallDeviceGap(layout);
		return new UiRect(content.x(), title.bottom() + gap, content.width(), Math.max(18, content.bottom() - title.bottom() - gap));
	}

	private static UiRect maxCallDeviceScrollButtonRect(UiRect titleRect, int indexFromRight, UiLayout layout) {
		int gap = maxCallDeviceGap(layout);
		int size = Math.min(titleRect.height(), ultraCompactScreenLayout(layout) ? clampInt(layout.unit() + 3, 7, 8) : clampInt(layout.unit() * 2, 18, 30));
		int x = titleRect.right() - size - indexFromRight * (size + gap);
		return new UiRect(x, titleRect.y(), size, titleRect.height());
	}

	private static UiRect maxCallDeviceCameraScrollLeftRect(UiLayout layout) {
		return maxCallDeviceScrollButtonRect(maxCallDeviceCameraTitleRect(layout), 1, layout);
	}

	private static UiRect maxCallDeviceCameraScrollRightRect(UiLayout layout) {
		return maxCallDeviceScrollButtonRect(maxCallDeviceCameraTitleRect(layout), 0, layout);
	}

	private static UiRect maxCallDeviceMicrophoneScrollUpRect(UiLayout layout) {
		return maxCallDeviceScrollButtonRect(maxCallDeviceMicrophoneTitleRect(layout), 1, layout);
	}

	private static UiRect maxCallDeviceMicrophoneScrollDownRect(UiLayout layout) {
		return maxCallDeviceScrollButtonRect(maxCallDeviceMicrophoneTitleRect(layout), 0, layout);
	}

	private static int maxCallDeviceGap(UiLayout layout) {
		return ultraCompactScreenLayout(layout) ? Math.max(1, layout.unit() / 4) : Math.max(4, layout.unit() / 2);
	}

	private static int maxCallDeviceCameraHeight(UiLayout layout) {
		UiRect content = maxCallDeviceContentRect(layout);
		UiRect title = maxCallDeviceCameraTitleRect(layout);
		int gap = maxCallDeviceGap(layout);
		if (ultraCompactScreenLayout(layout)) {
			int microphoneTitleHeight = clampInt(layout.unit() + 3, 7, 8);
			int available = Math.max(36, content.height() - title.height() - microphoneTitleHeight - gap * 3);
			int preferred = (int) Math.round(available * 0.44D);
			return clampInt(preferred, 28, Math.max(28, available - 18));
		}
		int preferred = Math.max(clampInt(layout.unit() * 7, 56, 112), (content.height() - title.height() - gap * 3) / 2);
		return Math.min(preferred, Math.max(18, content.bottom() - title.bottom() - gap));
	}

	private static int maxCallDeviceCameraCapacity(UiLayout layout) {
		UiRect grid = maxCallDeviceCameraGridRect(layout);
		int gap = maxCallDeviceGap(layout);
		int cell = Math.max(1, maxCallDeviceCameraHeight(layout));
		return clippedListCapacity(grid, cell, gap, true);
	}

	private static UiRect maxCallDeviceCameraRect(UiLayout layout, int index) {
		UiRect grid = maxCallDeviceCameraGridRect(layout);
		int gap = maxCallDeviceGap(layout);
		int cell = Math.max(1, maxCallDeviceCameraHeight(layout));
		return new UiRect(grid.x() + index * (cell + gap), grid.y(), cell, cell);
	}

	private static int maxCallDeviceCameraCellStep(UiLayout layout) {
		return Math.max(1, maxCallDeviceCameraHeight(layout)) + maxCallDeviceGap(layout);
	}

	private static int maxCallDeviceCameraIndexAt(UiLayout layout, int cameraCount, int cameraScroll, UiPoint point) {
		UiRect grid = maxCallDeviceCameraGridRect(layout);
		if (point == null || !grid.contains(point.x(), point.y())) {
			return -1;
		}
		int count = Math.min(Math.max(0, cameraCount - cameraScroll), maxCallDeviceCameraCapacity(layout));
		for (int visibleIndex = 0; visibleIndex < count; visibleIndex++) {
			if (maxCallDeviceCameraRect(layout, visibleIndex).contains(point.x(), point.y())) {
				return cameraScroll + visibleIndex;
			}
		}
		return -1;
	}

	private static int maxCallDeviceCameraScrollStep(UiLayout layout) {
		return 1;
	}

	private static int maxCallDeviceCameraScroll(UiLayout layout, int cameraCount) {
		return Math.max(0, cameraCount - maxCallDeviceCameraCapacity(layout));
	}

	private static int maxCallDeviceMicrophoneCapacity(UiLayout layout) {
		UiRect list = maxCallDeviceMicrophoneListRect(layout);
		int gap = maxCallDeviceGap(layout);
		int rowHeight = maxCallDeviceMicrophoneRowHeight(layout);
		return clippedListCapacity(list, rowHeight, gap, false);
	}

	private static int maxCallDeviceMicrophoneRowHeight(UiLayout layout) {
		return ultraCompactScreenLayout(layout) ? clampInt(layout.unit() * 3 + 1, 16, 18) : clampInt(layout.unit() * 3, 28, 44);
	}

	private static UiRect maxCallDeviceMicrophoneRowRect(UiLayout layout, int index) {
		UiRect list = maxCallDeviceMicrophoneListRect(layout);
		int gap = maxCallDeviceGap(layout);
		int height = maxCallDeviceMicrophoneRowHeight(layout);
		return new UiRect(list.x(), list.y() + index * (height + gap), list.width(), height);
	}

	private static int maxCallDeviceMicrophoneRowStep(UiLayout layout) {
		return maxCallDeviceMicrophoneRowHeight(layout) + maxCallDeviceGap(layout);
	}

	private static int maxCallDeviceMicrophoneIndexAt(UiLayout layout, int microphoneCount, int microphoneScroll, UiPoint point) {
		UiRect list = maxCallDeviceMicrophoneListRect(layout);
		if (point == null || !list.contains(point.x(), point.y())) {
			return -1;
		}
		int count = Math.min(Math.max(0, microphoneCount - microphoneScroll), maxCallDeviceMicrophoneCapacity(layout));
		for (int visibleIndex = 0; visibleIndex < count; visibleIndex++) {
			if (maxCallDeviceMicrophoneRowRect(layout, visibleIndex).contains(point.x(), point.y())) {
				return microphoneScroll + visibleIndex;
			}
		}
		return -1;
	}

	private static int maxCallDeviceMicrophoneScroll(UiLayout layout, int microphoneCount) {
		return Math.max(0, microphoneCount - maxCallDeviceMicrophoneCapacity(layout));
	}

	private static int maxRingtonePickerCapacity(UiLayout layout) {
		UiRect list = maxAvatarPickerGridRect(layout);
		int row = maxRingtoneCandidateHeight(layout);
		int gap = Math.max(4, layout.unit() / 2);
		return clippedListCapacity(list, row, gap, false);
	}

	private static int maxRingtoneCandidateHeight(UiLayout layout) {
		return clampInt(layout.unit() * 5, 44, 64);
	}

	private static UiRect maxRingtoneCandidateRect(UiLayout layout, int index) {
		UiRect list = maxAvatarPickerGridRect(layout);
		int gap = Math.max(4, layout.unit() / 2);
		int height = maxRingtoneCandidateHeight(layout);
		return new UiRect(list.x(), list.y() + index * (height + gap), list.width(), height);
	}

	private static int maxRingtonePickerRowStep(UiLayout layout) {
		return maxRingtoneCandidateHeight(layout) + Math.max(4, layout.unit() / 2);
	}

	private static UiRect maxRingtoneCandidatePlayRect(UiRect row, UiLayout layout) {
		int size = clampInt(layout.unit() * 2 + 4, 24, 34);
		return new UiRect(row.x() + layout.unit(), row.y() + (row.height() - size) / 2, size, size);
	}

	private static UiRect maxRingtoneCandidateSelectRect(UiRect row, UiLayout layout) {
		int size = clampInt(layout.unit() * 2 + 4, 24, 34);
		return new UiRect(row.right() - size - layout.unit(), row.y() + (row.height() - size) / 2, size, size);
	}

	private static int maxRingtoneCandidateIndexAt(UiLayout layout, int candidateCount, int scroll, UiPoint point) {
		UiRect list = maxAvatarPickerGridRect(layout);
		if (point == null || !list.contains(point.x(), point.y())) {
			return -1;
		}
		int count = Math.min(Math.max(0, candidateCount - scroll), maxRingtonePickerCapacity(layout));
		for (int index = 0; index < count; index++) {
			if (maxRingtoneCandidateRect(layout, index).contains(point.x(), point.y())) {
				return scroll + index;
			}
		}
		return -1;
	}

	private static int maxRingtonePickerScroll(UiLayout layout, int candidateCount) {
		return Math.max(0, candidateCount - maxRingtonePickerCapacity(layout));
	}

	private static UiRect maxContactPickerListRect(UiLayout layout) {
		return maxAvatarPickerGridRect(layout);
	}

	private static UiRect maxCallContactPickerListRect(UiLayout layout) {
		return maxCallContactPickerGridRect(layout);
	}

	private static UiRect maxFileShareSendRect(UiLayout layout) {
		UiRect close = maxOverlayCloseRect(layout);
		int gap = Math.max(4, layout.unit() / 2);
		return new UiRect(close.x() - close.width() - gap, close.y(), close.width(), close.height());
	}

	private static UiRect maxFileShareTitleRect(UiLayout layout) {
		UiRect panel = maxAvatarPickerPanelRect(layout);
		UiRect send = maxFileShareSendRect(layout);
		int inset = ultraCompactScreenLayout(layout)
				? Math.max(2, layout.unit() / 3)
				: layout.unit();
		return new UiRect(panel.x() + inset, send.y(), Math.max(1, send.x() - panel.x() - inset * 2), send.height());
	}

	private static UiRect maxFileShareHintRect(UiLayout layout) {
		UiRect title = maxFileShareTitleRect(layout);
		UiRect send = maxFileShareSendRect(layout);
		return new UiRect(title.x(), title.bottom(), Math.max(1, send.x() - title.x() - layout.unit()), clampInt(layout.unit() * 2, 16, 24));
	}

	private static UiRect maxFileShareContactCheckRect(UiRect row, UiLayout layout) {
		int size = clampInt(layout.unit() * 2 + 4, 24, 34);
		return new UiRect(row.right() - size - layout.unit(), row.y() + (row.height() - size) / 2, size, size);
	}

	private static UiRect maxNotificationPopupRect(UiLayout layout) {
		UiRect canvas = mediaCanvasRect(layout);
		if (ultraCompactScreenLayout(layout)) {
			return canvas;
		}
		int inset = clampInt(layout.unit(), 8, 18);
		int availableWidth = Math.max(1, canvas.width() - inset * 2);
		int availableHeight = Math.max(1, canvas.height() - inset * 2);
		int width = Math.min(availableWidth, clampInt(layout.unit() * 24, 190, 340));
		int height = Math.min(availableHeight, clampInt(layout.unit() * 28, 190, 380));
		return new UiRect(canvas.right() - inset - width, canvas.y() + inset, width, height);
	}

	private static int maxNotificationPopupPadding(UiLayout layout) {
		return ultraCompactScreenLayout(layout) ? Math.max(2, layout.unit() / 4) : clampInt(layout.unit() / 2, 5, 9);
	}

	private static UiRect maxNotificationPopupHeaderRect(UiLayout layout) {
		UiRect panel = maxNotificationPopupRect(layout);
		int pad = maxNotificationPopupPadding(layout);
		int height = ultraCompactScreenLayout(layout)
				? clampInt(layout.unit() * 3, 24, 34)
				: clampInt(layout.unit() * 4, 34, 52);
		return new UiRect(panel.x() + pad, panel.y() + pad, Math.max(1, panel.width() - pad * 2), Math.min(height, Math.max(1, panel.height() - pad * 2)));
	}

	private static UiRect maxNotificationPopupCloseRect(UiLayout layout) {
		UiRect header = maxNotificationPopupHeaderRect(layout);
		int size = clampInt(layout.unit() * 2 + 4, 24, 34);
		size = Math.min(size, Math.max(1, header.height() - Math.max(2, layout.unit() / 4)));
		return new UiRect(header.right() - size, header.y() + (header.height() - size) / 2, size, size);
	}

	private static UiRect maxNotificationFeedRect(UiLayout layout) {
		UiRect panel = maxNotificationPopupRect(layout);
		UiRect header = maxNotificationPopupHeaderRect(layout);
		int pad = maxNotificationPopupPadding(layout);
		int gap = maxNotificationItemGap(layout);
		int y = header.bottom() + gap;
		return new UiRect(panel.x() + pad, y, Math.max(1, panel.width() - pad * 2), Math.max(1, panel.bottom() - y - pad));
	}

	private static int maxNotificationItemHeight(UiLayout layout) {
		UiRect feed = maxNotificationFeedRect(layout);
		int preferred = ultraCompactScreenLayout(layout)
				? clampInt(layout.unit() * 10, 74, 120)
				: clampInt(layout.unit() * 12, 104, 168);
		return Math.min(preferred, Math.max(58, feed.height()));
	}

	private static int maxNotificationItemGap(UiLayout layout) {
		return Math.max(4, layout.unit() / 2);
	}

	private static int maxNotificationScrollDelta(UiLayout layout) {
		return Math.max(24, maxNotificationItemHeight(layout) / 2);
	}

	private static int maxNotificationContentHeight(UiLayout layout, int itemCount) {
		if (layout == null || itemCount <= 0) {
			return 0;
		}
		return itemCount * maxNotificationItemHeight(layout) + Math.max(0, itemCount - 1) * maxNotificationItemGap(layout);
	}

	private static int maxNotificationScroll(UiLayout layout, int itemCount) {
		if (layout == null) {
			return 0;
		}
		return Math.max(0, maxNotificationContentHeight(layout, itemCount) - maxNotificationFeedRect(layout).height());
	}

	private static UiRect maxNotificationItemAcceptRect(UiRect item, UiLayout layout) {
		int inset = clampInt(layout.unit() / 2, 4, 8);
		int size = clampInt(layout.unit() * 2 + 2, 24, 34);
		int gap = Math.max(4, layout.unit() / 2);
		int totalHeight = size * 2 + gap;
		int y = item.y() + Math.max(inset, (item.height() - totalHeight) / 2);
		return new UiRect(item.right() - inset - size, y, size, size);
	}

	private static UiRect maxNotificationItemDeclineRect(UiRect item, UiLayout layout) {
		UiRect accept = maxNotificationItemAcceptRect(item, layout);
		return new UiRect(accept.x(), accept.bottom() + Math.max(4, layout.unit() / 2), accept.width(), accept.height());
	}

	private static UiRect maxNotificationItemPreviewRect(UiRect item, BufferedImage preview, boolean squareFallback, UiLayout layout) {
		int inset = clampInt(layout.unit() / 2, 4, 8);
		UiRect accept = maxNotificationItemAcceptRect(item, layout);
		int availableWidth = Math.max(12, accept.x() - item.x() - inset * 2 - Math.max(4, layout.unit() / 2));
		double aspect = queueThumbnailAspect(preview, squareFallback);
		int maxHeight = Math.max(20, item.height() - inset * 2);
		int width = Math.max(24, (int) Math.round(maxHeight * aspect));
		int height = maxHeight;
		if (width > availableWidth) {
			width = availableWidth;
			height = Math.max(20, (int) Math.round(width / aspect));
		}
		return new UiRect(item.x() + inset, item.y() + (item.height() - height) / 2, width, height);
	}

	private static MaxNotificationHit maxNotificationHitAt(UiLayout layout, MaxRuntimeState state, UiPoint touchPoint) {
		if (layout == null || state == null || touchPoint == null || !maxNotificationFeedRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			return null;
		}
		List<Integer> indexes;
		int scroll;
		synchronized (state) {
			indexes = maxNotificationRawIndexesForActiveContactLocked(state);
			scroll = clampInt(state.notificationScroll, 0, maxNotificationScroll(layout, indexes.size()));
		}
		UiRect feed = maxNotificationFeedRect(layout);
		int y = feed.y() - scroll;
		int itemHeight = maxNotificationItemHeight(layout);
		int itemGap = maxNotificationItemGap(layout);
		for (int itemIndex : indexes) {
			UiRect itemRect = new UiRect(feed.x(), y, feed.width(), itemHeight);
			if (itemRect.contains(touchPoint.x(), touchPoint.y())) {
				return new MaxNotificationHit(
						itemIndex,
						maxNotificationItemPreviewRect(itemRect, null, notificationFileSquareFallbackLocked(state, itemIndex), layout),
						maxNotificationItemAcceptRect(itemRect, layout),
						maxNotificationItemDeclineRect(itemRect, layout)
				);
			}
			y += itemHeight + itemGap;
		}
		return null;
	}

	private static List<Integer> maxNotificationRawIndexesForActiveContactLocked(MaxRuntimeState state) {
		if (state == null || state.incomingFiles.isEmpty()) {
			return List.of();
		}
		String senderCode = normalizeAccountCode(state.notificationContactCode);
		if (senderCode.isBlank()) {
			return List.of();
		}
		List<Integer> indexes = new ArrayList<>();
		for (int index = 0; index < state.incomingFiles.size(); index++) {
			MaxIncomingFile incoming = state.incomingFiles.get(index);
			if (incoming != null && Objects.equals(senderCode, normalizeAccountCode(incoming.senderCode()))) {
				indexes.add(index);
			}
		}
		return indexes.isEmpty() ? List.of() : List.copyOf(indexes);
	}

	private static boolean notificationFileSquareFallbackLocked(MaxRuntimeState state, int index) {
		if (state == null || index < 0 || index >= state.incomingFiles.size()) {
			return false;
		}
		MaxIncomingFile incoming = state.incomingFiles.get(index);
		return incoming != null && incoming.kind() == GalleryItemKind.AUDIO;
	}

	private static boolean rectIntersects(UiRect first, UiRect second) {
		return first != null
				&& second != null
				&& first.right() > second.x()
				&& first.x() < second.right()
				&& first.bottom() > second.y()
				&& first.y() < second.bottom();
	}

	private static int maxContactPickerCapacity(UiLayout layout) {
		UiRect list = maxContactPickerListRect(layout);
		int gap = Math.max(4, layout.unit() / 2);
		int row = maxContactRowHeight(layout);
		return clippedListCapacity(list, row, gap, false);
	}

	private static int maxCallContactPickerCapacity(UiLayout layout) {
		UiRect list = maxCallContactPickerListRect(layout);
		int gap = Math.max(4, layout.unit() / 2);
		int row = maxContactRowHeight(layout);
		return clippedListCapacity(list, row, gap, false);
	}

	private static UiRect maxContactPickerRowRect(UiLayout layout, int index) {
		UiRect list = maxContactPickerListRect(layout);
		int gap = Math.max(4, layout.unit() / 2);
		int height = maxContactRowHeight(layout);
		return new UiRect(list.x(), list.y() + index * (height + gap), list.width(), height);
	}

	private static int maxContactPickerRowStep(UiLayout layout) {
		return maxContactRowHeight(layout) + Math.max(4, layout.unit() / 2);
	}

	private static UiRect maxCallContactPickerRowRect(UiLayout layout, int index) {
		UiRect list = maxCallContactPickerListRect(layout);
		int gap = Math.max(4, layout.unit() / 2);
		int height = maxContactRowHeight(layout);
		return new UiRect(list.x(), list.y() + index * (height + gap), list.width(), height);
	}

	private static int maxContactPickerIndexAt(UiLayout layout, int contactCount, int scroll, UiPoint point) {
		UiRect list = maxContactPickerListRect(layout);
		if (point == null || !list.contains(point.x(), point.y())) {
			return -1;
		}
		int count = Math.min(Math.max(0, contactCount - scroll), maxContactPickerCapacity(layout));
		for (int index = 0; index < count; index++) {
			if (maxContactPickerRowRect(layout, index).contains(point.x(), point.y())) {
				return scroll + index;
			}
		}
		return -1;
	}

	private static int maxCallContactPickerIndexAt(UiLayout layout, int contactCount, int scroll, UiPoint point) {
		UiRect list = maxCallContactPickerListRect(layout);
		if (point == null || !list.contains(point.x(), point.y())) {
			return -1;
		}
		int count = Math.min(Math.max(0, contactCount - scroll), maxCallContactPickerCapacity(layout));
		for (int index = 0; index < count; index++) {
			if (maxCallContactPickerRowRect(layout, index).contains(point.x(), point.y())) {
				return scroll + index;
			}
		}
		return -1;
	}

	private static int maxContactPickerScroll(UiLayout layout, int contactCount) {
		return Math.max(0, contactCount - maxContactPickerCapacity(layout));
	}

	private static int maxCallContactPickerScroll(UiLayout layout, int contactCount) {
		return Math.max(0, contactCount - maxCallContactPickerCapacity(layout));
	}

	private record MaxStoredAccountProfile(
			String accountCode,
			String displayName,
			String avatarUrl,
			String avatarLocalMediaKey,
			BufferedImage avatarFrame,
			boolean avatarAnimated
	) {
		boolean hasVisualIdentity() {
			return (displayName != null && !displayName.isBlank())
					|| avatarFrame != null
					|| (avatarUrl != null && !avatarUrl.isBlank())
					|| (avatarLocalMediaKey != null && !avatarLocalMediaKey.isBlank());
		}
	}

	private record PersistedMaxState(
			String accountCode,
			String accountName,
			String avatarUrl,
			String avatarLocalMediaKey,
			List<String> contacts,
			String selectedCameraUrl,
			String selectedMicrophoneKey,
			int selectedMicrophoneIndex,
			boolean cameraEnabled,
			boolean microphoneEnabled,
			String ringtoneUrl,
			String ringtoneLocalMediaKey,
			String ringtoneTitle,
			List<MaxIncomingFile> incomingFiles
	) {
	}

	private static final class MaxRuntimeState {
		private boolean hydrated;
		private long version;
		private String accountCode = "";
		private String accountName = "";
		private String avatarUrl = "";
		private String avatarLocalMediaKey = "";
		private BufferedImage avatarFrame;
		private MonitorMediaApp.LoadedMedia avatarMedia;
		private long avatarAnimationStartedAtMillis;
		private boolean avatarRenderScheduled;
		private final List<String> contacts = new ArrayList<>();
		private int contactScroll;
		private String selectedCameraUrl = "";
		private String selectedMicrophoneKey = "";
		private int selectedMicrophoneIndex = -1;
		private String ringtoneUrl = "";
		private String ringtoneLocalMediaKey = "";
		private String ringtoneTitle = "";
		private boolean cameraEnabled = true;
		private boolean microphoneEnabled = true;
		private boolean avatarPickerOpen;
		private int avatarPickerScroll;
		private boolean ringtonePickerOpen;
		private int ringtonePickerScroll;
		private boolean ringtonePreviewPlaying;
		private String ringtonePreviewUrl = MAX_DEFAULT_RINGTONE_URL;
		private String ringtonePreviewLocalMediaKey = "";
		private String ringtonePreviewTitle = "";
		private long ringtonePreviewStartedAtMillis;
		private boolean callMenuOpen;
		private boolean cameraPickerOpen;
		private int cameraPickerScroll;
		private int microphonePickerScroll;
		private int callMiniParticipantScroll;
		private boolean callContactPickerOpen;
		private int callContactPickerScroll;
		private boolean callMiniParticipantsHidden;
		private boolean focusSelf;
		private boolean focusPeer;
		private ScreenRuntimeKey focusedPeerKey;
		private String statusText = "";
		private BufferedImage localFrame;
		private String localVideoUrl = "";
		private BufferedImage remoteFrame;
		private String remoteVideoUrl = "";
		private final List<MaxIncomingFile> incomingFiles = new ArrayList<>();
		private boolean notificationsOpen;
		private String notificationContactCode = "";
		private int notificationScroll;
		private String notificationPreviewFileId = "";
		private boolean notificationPreviewLoading;
		private boolean notificationPreviewPlaying;
		private String notificationPreviewAudioInput = "";
		private MonitorMediaApp.LoadedMedia notificationPreviewMedia;
		private int notificationPreviewFrameIndex;
		private long notificationPreviewStartedAtMillis;
		private long notificationPreviewPausedPositionMs;
		private long notificationPreviewLoadGeneration;
		private boolean notificationPreviewRenderScheduled;
		private final Map<String, MaxIncomingPreviewCacheEntry> incomingPreviewCache = new HashMap<>();
		private boolean fileSharePickerOpen;
		private int fileSharePickerScroll;
		private final List<MaxSharedGalleryFile> fileShareFiles = new ArrayList<>();
		private final Set<String> fileShareSelectedContacts = new LinkedHashSet<>();
	}

	private record MaxSharedGalleryFile(
			String title,
			String subtitle,
			String url,
			String localMediaKey,
			GalleryItemKind kind
	) {
	}

	private record MaxIncomingFile(
			String id,
			String senderCode,
			String senderDisplayName,
			String senderAvatarUrl,
			String senderAvatarLocalMediaKey,
			String title,
			String subtitle,
			String url,
			String localMediaKey,
			GalleryItemKind kind,
			long createdAtMillis
	) {
	}

	private record MaxPreparedFileDelivery(String recipientCode, MaxIncomingFile file) {
	}

	private record MaxNotificationPreviewLoadResult(
			ScreenRuntimeKey key,
			String fileId,
			long generation,
			MonitorMediaApp.LoadedMedia media,
			String audioInput,
			String error
	) {
	}

	private record MaxNotificationHit(int index, UiRect previewRect, UiRect acceptRect, UiRect declineRect) {
	}

	private record MaxNotificationPreviewVisualState(
			String fileId,
			BufferedImage frame,
			boolean playing,
			boolean loading
	) {
	}

	private record MaxIncomingPreviewLoadResult(
			ScreenRuntimeKey key,
			String fileId,
			BufferedImage previewFrame
	) {
	}

	private static final class MaxIncomingPreviewCacheEntry {
		private BufferedImage previewFrame;
		private boolean loading;
		private boolean resolved;
	}

	private record ObservedCallUiTarget(ScreenComponent component, UiLayout layout, UiPoint touchPoint) {
	}

	private static final class MaxCallSession {
		private final UUID id;
		private final ScreenRuntimeKey caller;
		private final ScreenRuntimeKey callee;
		private final String callerCode;
		private final String calleeCode;
		private final long createdAtMillis;
		private final LinkedHashSet<ScreenRuntimeKey> participants = new LinkedHashSet<>();
		private final Set<ScreenRuntimeKey> acceptedParticipants = new LinkedHashSet<>();
		private final Set<ScreenRuntimeKey> ringingParticipants = new LinkedHashSet<>();
		private final Map<ScreenRuntimeKey, String> participantCodes = new ConcurrentHashMap<>();
		private final Map<ScreenRuntimeKey, ScreenRuntimeKey> ringingInviters = new ConcurrentHashMap<>();
		private volatile boolean accepted;
		private volatile long acceptedAtMillis;

		private MaxCallSession(UUID id, ScreenRuntimeKey caller, ScreenRuntimeKey callee, String callerCode, String calleeCode) {
			this.id = id;
			this.caller = caller;
			this.callee = callee;
			this.callerCode = callerCode;
			this.calleeCode = calleeCode;
			this.createdAtMillis = System.currentTimeMillis();
			this.participants.add(caller);
			this.participants.add(callee);
			this.acceptedParticipants.add(caller);
			this.ringingParticipants.add(callee);
			this.participantCodes.put(caller, callerCode);
			this.participantCodes.put(callee, calleeCode);
			this.ringingInviters.put(callee, caller);
		}

		private synchronized boolean addInvitee(ScreenRuntimeKey inviter, ScreenRuntimeKey invitee, String code) {
			if (inviter == null || invitee == null || !this.acceptedParticipants.contains(inviter) || this.participants.contains(invitee)) {
				return false;
			}
			this.participants.add(invitee);
			this.ringingParticipants.add(invitee);
			this.participantCodes.put(invitee, code);
			this.ringingInviters.put(invitee, inviter);
			return true;
		}

		private synchronized void accept(ScreenRuntimeKey key) {
			if (key == null || !this.participants.contains(key)) {
				return;
			}
			this.ringingParticipants.remove(key);
			this.ringingInviters.remove(key);
			this.acceptedParticipants.add(key);
			if (this.acceptedParticipants.size() >= 2) {
				this.accepted = true;
				if (this.acceptedAtMillis <= 0L) {
					this.acceptedAtMillis = System.currentTimeMillis();
				}
			}
		}

		private synchronized void removeParticipant(ScreenRuntimeKey key) {
			if (key == null) {
				return;
			}
			this.participants.remove(key);
			this.acceptedParticipants.remove(key);
			this.ringingParticipants.remove(key);
			this.participantCodes.remove(key);
			this.ringingInviters.remove(key);
			this.accepted = this.acceptedParticipants.size() >= 2;
		}

		private synchronized ScreenRuntimeKey inviterFor(ScreenRuntimeKey key) {
			ScreenRuntimeKey inviter = this.ringingInviters.get(key);
			return inviter != null ? inviter : this.caller;
		}

		private synchronized String participantCode(ScreenRuntimeKey key) {
			String code = this.participantCodes.get(key);
			if (code != null && !code.isBlank()) {
				return code;
			}
			if (Objects.equals(this.caller, key)) {
				return this.callerCode;
			}
			if (Objects.equals(this.callee, key)) {
				return this.calleeCode;
			}
			return "";
		}

		private synchronized boolean isParticipant(ScreenRuntimeKey key) {
			return key != null && this.participants.contains(key);
		}

		private synchronized boolean isRinging(ScreenRuntimeKey key) {
			return key != null && this.ringingParticipants.contains(key);
		}

		private synchronized boolean isAccepted(ScreenRuntimeKey key) {
			return key != null && this.acceptedParticipants.contains(key);
		}

		private synchronized int acceptedParticipantCount() {
			return this.acceptedParticipants.size();
		}

		private synchronized List<ScreenRuntimeKey> participants() {
			return List.copyOf(this.participants);
		}

		private synchronized List<ScreenRuntimeKey> acceptedParticipants() {
			return List.copyOf(this.acceptedParticipants);
		}

		private synchronized List<ScreenRuntimeKey> otherParticipants(ScreenRuntimeKey key) {
			List<ScreenRuntimeKey> others = new ArrayList<>();
			for (ScreenRuntimeKey participant : this.participants) {
				if (!Objects.equals(participant, key)) {
					others.add(participant);
				}
			}
			return List.copyOf(others);
		}

		private synchronized ScreenRuntimeKey peer(ScreenRuntimeKey key) {
			ScreenRuntimeKey fallback = null;
			for (ScreenRuntimeKey participant : this.participants) {
				if (Objects.equals(participant, key)) {
					continue;
				}
				if (fallback == null) {
					fallback = participant;
				}
				if (this.acceptedParticipants.contains(participant)) {
					return participant;
				}
			}
			return fallback;
		}

		private synchronized String peerCode(ScreenRuntimeKey key) {
			ScreenRuntimeKey peer = peer(key);
			if (peer != null) {
				String code = this.participantCodes.get(peer);
				if (code != null && !code.isBlank()) {
					return code;
				}
			}
			return Objects.equals(this.caller, key) ? this.calleeCode : this.callerCode;
		}
	}
}
