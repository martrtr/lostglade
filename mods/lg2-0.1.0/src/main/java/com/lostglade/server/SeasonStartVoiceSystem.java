package com.lostglade.server;

import com.lostglade.Lg2;
import com.lostglade.config.SeasonStartConfig;
import com.lostglade.config.SeasonStartConfig.VoiceCue;
import de.maxhenkel.voicechat.api.Position;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.VoiceDistanceEvent;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import de.maxhenkel.voicechat.plugins.impl.packets.LocationalSoundPacketImpl;
import de.maxhenkel.voicechat.voice.common.LocationSoundPacket;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public final class SeasonStartVoiceSystem {
	private static final int AUDIO_SAMPLE_RATE = 48_000;
	private static final int AUDIO_FRAME_SAMPLES = 960;
	private static final long AUDIO_FRAME_DURATION_MS = 20L;
	private static final int NARRATION_INTERRUPT_FADE_SAMPLES = AUDIO_FRAME_SAMPLES;
	private static final int NARRATION_START_FADE_SAMPLES = 240;
	private static final int NARRATION_END_FADE_SAMPLES = 240;
	private static final long REALTIME_INTERRUPT_GRACE_TICKS = 18L;
	private static final long FEEDBACK_PREEMPT_GRACE_TICKS = 6L;
	private static final long FEEDBACK_QUEUE_TTL_TICKS = 20L * 3L;
	private static final long GUIDANCE_QUEUE_TTL_TICKS = 20L * 4L;
	private static final long URGENT_QUEUE_TTL_TICKS = 20L * 2L;
	private static final UUID SERVER_VOICE_SOURCE_ID = UUID.nameUUIDFromBytes("lg2:season_start_server_voice".getBytes(StandardCharsets.UTF_8));
	private static final Map<String, Deque<QueuedCue>> CHANNEL_QUEUES = new HashMap<>();
	private static final Map<String, ActiveCuePlayback> ACTIVE_PLAYBACKS = new ConcurrentHashMap<>();
	private static final Map<UUID, Set<String>> PLAYER_COMPLETED_CUES = new HashMap<>();
	private static final Set<String> GLOBAL_COMPLETED_CUES = new HashSet<>();
	private static final Map<UUID, Long> LIVE_VOICE_SEQUENCES = new HashMap<>();
	private static final Map<Path, PcmClip> CLIP_CACHE = new ConcurrentHashMap<>();
	private static long nextPlaybackId = 1L;

	private static ScheduledExecutorService playbackExecutor = createPlaybackExecutor();

	private SeasonStartVoiceSystem() {
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			resetRuntimeState();
			ensurePlaybackExecutor();
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> shutdown());
		ServerTickEvents.END_SERVER_TICK.register(SeasonStartVoiceSystem::tick);
	}

	public static void resetSceneState() {
		cancelAllActivePlaybacks();
		CHANNEL_QUEUES.clear();
		ACTIVE_PLAYBACKS.clear();
		PLAYER_COMPLETED_CUES.clear();
		GLOBAL_COMPLETED_CUES.clear();
		LIVE_VOICE_SEQUENCES.clear();
	}

	public static void fireTrigger(MinecraftServer server, String trigger, ServerPlayer focusPlayer) {
		if (server == null || trigger == null || trigger.isBlank()) {
			return;
		}
		long nowTick = server.overworld().getGameTime();
		for (VoiceCue cue : SeasonStartConfig.get().cues) {
			if (cue == null || !trigger.equals(cue.trigger)) {
				continue;
			}
			queueCue(server, cue, focusPlayer, nowTick + cue.delayTicks);
		}
	}

	public static void clearPlayerChannel(ServerPlayer player) {
		if (player == null) {
			return;
		}
		clearChannel("player:" + player.getUUID());
	}

	/** Stops every queued or playing line before a terminal server-wide announcement. */
	public static void stopAllNarration() {
		for (ActiveCuePlayback playback : new ArrayList<>(ACTIVE_PLAYBACKS.values())) {
			cancelActivePlayback(playback);
		}
		CHANNEL_QUEUES.clear();
	}

	/**
	 * Progress narration is a live status, not a historical log. A later value
	 * replaces any earlier percentage line that has not finished yet.
	 */
	public static void replaceSharedLaunchProgressNarration(MinecraftServer server, String trigger) {
		clearSharedLaunchProgressNarration();
		fireTrigger(server, trigger, null);
	}

	/** Stops only obsolete 10..90% launch commentary without touching other global scenes. */
	public static void clearSharedLaunchProgressNarration() {
		Deque<QueuedCue> queue = CHANNEL_QUEUES.get("global");
		if (queue != null) {
			queue.removeIf(queuedCue -> isSharedLaunchProgressCue(queuedCue == null ? null : queuedCue.cue));
			if (queue.isEmpty()) {
				CHANNEL_QUEUES.remove("global");
			}
		}
		ActiveCuePlayback active = ACTIVE_PLAYBACKS.get("global");
		if (active != null && isSharedLaunchProgressCue(active.queuedCue == null ? null : active.queuedCue.cue)) {
			interruptActivePlayback("global");
		}
	}

	private static boolean isSharedLaunchProgressCue(VoiceCue cue) {
		if (cue == null || cue.trigger == null) {
			return false;
		}
		return switch (cue.trigger) {
			case "shared_launch_ten", "shared_launch_twenty", "shared_launch_thirty", "shared_launch_forty",
					"shared_launch_halfway", "shared_launch_sixty", "shared_launch_seventy", "shared_launch_eighty",
					"shared_launch_ninety" -> true;
			default -> false;
		};
	}

	public static void onMicrophonePacket(MicrophonePacketEvent event) {
		if (event == null || !ServerVoicechatIntegration.isLoaded()) {
			return;
		}
		VoicechatApi voicechatApi = ServerVoicechatIntegration.getApi();
		VoicechatServerApi voicechatServerApi = ServerVoicechatIntegration.getServerApi();
		VoicechatConnection senderConnection = event.getSenderConnection();
		if (voicechatApi == null || voicechatServerApi == null || senderConnection == null || senderConnection.getPlayer() == null) {
			return;
		}
		Object rawPlayer = senderConnection.getPlayer().getPlayer();
		if (!(rawPlayer instanceof ServerPlayer senderPlayer) || !SeasonStartSystem.isLiveVoiceControlled(senderPlayer)) {
			return;
		}

		event.cancel();
		if (!SeasonStartSystem.canRelayLiveVoice(senderPlayer) || event.getPacket() == null) {
			return;
		}

		byte[] opusData = event.getPacket().getOpusEncodedData();
		if (opusData == null || opusData.length == 0) {
			return;
		}
		Position position = senderConnection.getPlayer().getPosition();
		if (position == null) {
			return;
		}

		Vec3 origin = new Vec3(position.getX(), position.getY(), position.getZ());
		float distance = (float) Math.max(
				1.0D,
				voicechatApi.getVoiceChatDistance() * (event.getPacket().isWhispering() ? 0.5D : 1.0D)
		);
		long sequence = LIVE_VOICE_SEQUENCES.getOrDefault(senderPlayer.getUUID(), 0L);
		LIVE_VOICE_SEQUENCES.put(senderPlayer.getUUID(), sequence + 1L);
		UUID channelId = UUID.nameUUIDFromBytes(("lg2:season_start_live:" + senderPlayer.getUUID()).getBytes(StandardCharsets.UTF_8));
		LocationSoundPacket packet = new LocationSoundPacket(
				channelId,
				senderPlayer.getUUID(),
				origin,
				opusData.clone(),
				sequence,
				distance,
				null
		);
		LocationalSoundPacketImpl wrappedPacket = new LocationalSoundPacketImpl(packet);
		for (de.maxhenkel.voicechat.api.ServerPlayer nearby : voicechatServerApi.getPlayersInRange(
				voicechatApi.fromServerLevel(senderPlayer.level()),
				voicechatApi.createPosition(origin.x, origin.y, origin.z),
				distance,
				player -> resolveRawServerPlayer(player) instanceof ServerPlayer receiver
						&& SeasonStartSystem.canHearLiveVoice(senderPlayer, receiver)
		)) {
			if (nearby == null) {
				continue;
			}
			VoicechatConnection receiverConnection = voicechatServerApi.getConnectionOf(nearby.getUuid());
			if (receiverConnection != null) {
				voicechatServerApi.sendLocationalSoundPacketTo(receiverConnection, wrappedPacket);
			}
		}
	}

	public static void onVoiceDistance(VoiceDistanceEvent event) {
		if (event == null || event.getSenderConnection() == null || event.getSenderConnection().getPlayer() == null) {
			return;
		}
		Object rawPlayer = event.getSenderConnection().getPlayer().getPlayer();
		if (rawPlayer instanceof ServerPlayer senderPlayer && SeasonStartSystem.isLiveVoiceControlled(senderPlayer)) {
			event.setDistance(0.0F);
		}
	}

	private static void queueCue(MinecraftServer server, VoiceCue cue, ServerPlayer focusPlayer, long executeTick) {
		if (server == null || cue == null || !shouldQueueCue(cue, focusPlayer)) {
			return;
		}
		String channelKey = resolveChannelKey(cue, focusPlayer);
		if (channelKey == null) {
			return;
		}

		long nowTick = server.overworld().getGameTime();
		QueuedCue queuedCue = new QueuedCue(cue, focusPlayer == null ? null : focusPlayer.getUUID(), executeTick, channelKey);
		Deque<QueuedCue> queue = CHANNEL_QUEUES.computeIfAbsent(channelKey, ignored -> new ArrayDeque<>());
		pruneExpiredQueuedCues(queue, nowTick);
		CueRole cueRole = classifyCue(cue);
		queue.removeIf(existing -> existing != null && shouldReplaceQueuedCue(existing.cue, cueRole));

		ActiveCuePlayback activePlayback = ACTIVE_PLAYBACKS.get(channelKey);
		if (shouldInterruptTransientPlayback(activePlayback, queuedCue, nowTick)) {
			interruptActivePlayback(channelKey);
			activePlayback = null;
		}
		if (cue.interruptCurrent && shouldInterruptImmediately(cue)) {
			if (activePlayback != null
					&& executeTick - activePlayback.startedTick < REALTIME_INTERRUPT_GRACE_TICKS
					&& !shouldInterruptWithoutGrace(cue)) {
				for (QueuedCue existing : queue) {
					if (sameCue(existing, queuedCue)) {
						return;
					}
				}
				queue.addLast(queuedCue);
				return;
			}
			queue.clear();
			interruptActivePlayback(channelKey);
		}
		for (QueuedCue existing : queue) {
			if (sameCue(existing, queuedCue)) {
				return;
			}
		}
		ActiveCuePlayback active = ACTIVE_PLAYBACKS.get(channelKey);
		if (active != null && sameCue(active.queuedCue, queuedCue)) {
			return;
		}
		insertQueuedCue(queue, queuedCue);
	}

	private static void interruptActivePlayback(String channelKey) {
		if (channelKey == null || channelKey.isBlank()) {
			return;
		}
		ActiveCuePlayback active = ACTIVE_PLAYBACKS.get(channelKey);
		if (active == null) {
			return;
		}
		cancelActivePlayback(active);
	}

	private static void clearChannel(String channelKey) {
		if (channelKey == null || channelKey.isBlank()) {
			return;
		}
		Deque<QueuedCue> queue = CHANNEL_QUEUES.get(channelKey);
		if (queue != null) {
			queue.clear();
			CHANNEL_QUEUES.remove(channelKey);
		}
		interruptActivePlayback(channelKey);
	}

	private static void cancelAllActivePlaybacks() {
		for (ActiveCuePlayback active : new ArrayList<>(ACTIVE_PLAYBACKS.values())) {
			cancelActivePlayback(active);
		}
	}

	private static void cancelActivePlayback(ActiveCuePlayback active) {
		if (active == null || active.future == null || active.future.isDone()) {
			return;
		}
		// Stopping an Opus stream in the middle of a non-zero PCM wave creates an
		// audible click in Simple Voice Chat. Keep the channel alive just long
		// enough for its sender to emit a short tail down to silence.
		if (active.voiceStreamStarted) {
			active.requestFadeOut();
			return;
		}
		active.future.complete(null);
	}

	private static boolean shouldQueueCue(VoiceCue cue, ServerPlayer focusPlayer) {
		if (cue == null) {
			return false;
		}
		if ("player".equalsIgnoreCase(cue.audience) && focusPlayer == null) {
			return false;
		}
		if (cue.onceGlobal && GLOBAL_COMPLETED_CUES.contains(cue.id)) {
			return false;
		}
		if (cue.oncePerPlayer && focusPlayer != null) {
			Set<String> completed = PLAYER_COMPLETED_CUES.get(focusPlayer.getUUID());
			if (completed != null && completed.contains(cue.id)) {
				return false;
			}
		}
		if (cue.requires == null || cue.requires.isEmpty()) {
			return true;
		}
		Set<String> playerCompleted = focusPlayer == null
				? Set.of()
				: PLAYER_COMPLETED_CUES.getOrDefault(focusPlayer.getUUID(), Set.of());
		for (String required : cue.requires) {
			if (required == null || required.isBlank()) {
				continue;
			}
			if (!GLOBAL_COMPLETED_CUES.contains(required) && !playerCompleted.contains(required)) {
				return false;
			}
		}
		return true;
	}

	private static String resolveChannelKey(VoiceCue cue, ServerPlayer focusPlayer) {
		if (cue == null) {
			return null;
		}
		if ("player".equalsIgnoreCase(cue.channel)) {
			return focusPlayer == null ? null : "player:" + focusPlayer.getUUID();
		}
		return "global";
	}

	private static boolean sameCue(QueuedCue first, QueuedCue second) {
		return first != null
				&& second != null
				&& Objects.equals(first.cue.id, second.cue.id)
				&& Objects.equals(first.focusPlayerId, second.focusPlayerId);
	}

	private static void tick(MinecraftServer server) {
		if (server == null) {
			return;
		}

		for (Map.Entry<String, ActiveCuePlayback> entry : new ArrayList<>(ACTIVE_PLAYBACKS.entrySet())) {
			ActiveCuePlayback playback = entry.getValue();
			if (playback == null || !playback.future.isDone()) {
				continue;
			}
			markCueCompleted(playback.queuedCue);
			ACTIVE_PLAYBACKS.remove(entry.getKey());
		}

		long nowTick = server.overworld().getGameTime();
		for (Map.Entry<String, Deque<QueuedCue>> entry : CHANNEL_QUEUES.entrySet()) {
			pruneExpiredQueuedCues(entry.getValue(), nowTick);
			if (ACTIVE_PLAYBACKS.containsKey(entry.getKey())) {
				continue;
			}
			while (true) {
				QueuedCue next = entry.getValue().peekFirst();
				if (next == null) {
					break;
				}
				if (isExpired(next, nowTick)) {
					entry.getValue().pollFirst();
					continue;
				}
				if (next.executeTick > nowTick) {
					break;
				}
				if (isBlockedByOverlappingNarration(server, entry.getKey(), next, nowTick)) {
					break;
				}
				entry.getValue().pollFirst();
				long playbackId = nextPlaybackId++;
				CompletableFuture<Void> future = new CompletableFuture<>();
				ActiveCuePlayback playback = new ActiveCuePlayback(next, future, nowTick, playbackId);
				ACTIVE_PLAYBACKS.put(entry.getKey(), playback);
				startPlayback(server, playback);
				break;
			}
		}

		CHANNEL_QUEUES.entrySet().removeIf(entry -> entry.getValue().isEmpty());
	}

	private static void markCueCompleted(QueuedCue queuedCue) {
		if (queuedCue == null || queuedCue.cue == null || queuedCue.cue.id == null || queuedCue.cue.id.isBlank()) {
			return;
		}
		if (queuedCue.cue.onceGlobal) {
			GLOBAL_COMPLETED_CUES.add(queuedCue.cue.id);
		}
		if (queuedCue.focusPlayerId != null && queuedCue.cue.oncePerPlayer) {
			PLAYER_COMPLETED_CUES
					.computeIfAbsent(queuedCue.focusPlayerId, ignored -> new HashSet<>())
					.add(queuedCue.cue.id);
		}
	}

	private static void startPlayback(MinecraftServer server, ActiveCuePlayback playback) {
		if (playback == null || playback.future == null) {
			return;
		}
		QueuedCue queuedCue = playback.queuedCue;
		if (server == null || queuedCue == null || queuedCue.cue == null) {
			playback.future.complete(null);
			return;
		}
		List<ServerPlayer> recipients = resolveRecipients(server, queuedCue.cue, queuedCue.focusPlayerId);
		if (recipients.isEmpty()) {
			bridgePlaybackFuture(playback, delayedFuture(queuedCue.cue.durationTicks));
			return;
		}

		Path audioPath = resolveAudioPath(queuedCue.cue.audioFile);
		PcmClip clip = loadClip(audioPath);
		VoicechatApi voicechatApi = ServerVoicechatIntegration.getApi();
		VoicechatServerApi voicechatServerApi = ServerVoicechatIntegration.getServerApi();
		List<UUID> voiceRecipientIds = new ArrayList<>();
		for (ServerPlayer recipient : recipients) {
			if (recipient == null) {
				continue;
			}
			boolean hasVoice = voicechatServerApi != null && voicechatServerApi.getConnectionOf(recipient.getUUID()) != null;
			if (hasVoice && clip != null && ServerVoicechatIntegration.isLoaded()) {
				voiceRecipientIds.add(recipient.getUUID());
			} else {
				sendChatFallback(recipient, queuedCue.cue);
			}
		}

		if (clip == null || voiceRecipientIds.isEmpty() || voicechatApi == null || voicechatServerApi == null) {
			if (voiceRecipientIds.isEmpty() && !hasChatText(queuedCue.cue)) {
				playback.future.complete(null);
				return;
			}
			bridgePlaybackFuture(playback, delayedFuture(queuedCue.cue.durationTicks));
			return;
		}

		float voiceGain = resolveVoiceGain(queuedCue.cue);
		// Narration must come from the actual server core. A per-player camera origin
		// makes the voice appear to move whenever the player turns or walks.
		Vec3 serverOrigin = SeasonStartSystem.resolveServerVoiceOrigin(server, queuedCue.focusPlayerId == null
				? null
				: server.getPlayerList().getPlayer(queuedCue.focusPlayerId));
		if (serverOrigin == null) {
			// The core can be unavailable only while the scene is being created. Keep a
			// stable fallback for that exceptional moment rather than changing position
			// from frame to frame.
			ServerPlayer fallbackPlayer = server.getPlayerList().getPlayer(voiceRecipientIds.get(0));
			serverOrigin = fallbackPlayer == null ? Vec3.ZERO : fallbackPlayer.position();
		}
		playback.markVoiceStreamStarted();
		bridgePlaybackFuture(playback, playClipToRecipients(
				server,
				voicechatApi,
				voicechatServerApi,
				clip,
				serverOrigin,
				voiceRecipientIds,
				queuedCue.channelKey,
				playback.playbackId,
				voiceGain
		));
	}

	private static List<ServerPlayer> resolveRecipients(MinecraftServer server, VoiceCue cue, UUID focusPlayerId) {
		if (server == null || cue == null) {
			return List.of();
		}
		if ("player".equalsIgnoreCase(cue.audience)) {
			ServerPlayer player = focusPlayerId == null ? null : server.getPlayerList().getPlayer(focusPlayerId);
			return player == null || RendererBotPresenceSystem.isRendererBot(player) ? List.of() : List.of(player);
		}
		List<ServerPlayer> recipients = new ArrayList<>();
		for (ServerPlayer player : AccountAuthSystem.authenticatedPlayers(server)) {
			if (player == null || RendererBotPresenceSystem.isRendererBot(player)) {
				continue;
			}
			if ("shared".equalsIgnoreCase(cue.audience)
					&& (!SeasonStartSystem.isInSharedPhase(player) || !SeasonStartSystem.canReceiveSharedNarration(player))) {
				continue;
			}
			if ("all_active".equalsIgnoreCase(cue.audience) && !SeasonStartSystem.isStartParticipant(player)) {
				continue;
			}
			recipients.add(player);
		}
		return recipients;
	}

	private static CompletableFuture<Void> playClipToRecipients(
			MinecraftServer server,
			VoicechatApi voicechatApi,
			VoicechatServerApi voicechatServerApi,
			PcmClip clip,
			Vec3 origin,
			List<UUID> recipientIds,
			String channelKey,
			long playbackId,
			float voiceGain
	) {
		CompletableFuture<Void> future = new CompletableFuture<>();
		ScheduledExecutorService executor = ensurePlaybackExecutor();
		OpusEncoder encoder;
		try {
			encoder = voicechatApi.createEncoder();
		} catch (RuntimeException exception) {
			Lg2.LOGGER.warn("Failed to create Opus encoder for season-start narration", exception);
			return delayedFuture(clip.durationTicks);
		}

		UUID channelId = UUID.randomUUID();
		float distance = Math.max(4.0F, SeasonStartConfig.get().serverVoiceDistance);
		String category = SpeakerSystem.isSpeakerVolumeCategoryRegistered() ? SpeakerSystem.speakerVolumeCategoryId() : null;
		scheduleNarrationFrame(
				server,
				voicechatServerApi,
				clip,
				encoder,
				channelId,
				origin,
				distance,
				category,
				recipientIds,
				channelKey,
				playbackId,
				voiceGain,
				0,
				System.nanoTime(),
				null,
				future,
				executor
		);
		return future;
	}

	private static void scheduleNarrationFrame(
			MinecraftServer server,
			VoicechatServerApi voicechatServerApi,
			PcmClip clip,
			OpusEncoder encoder,
			UUID channelId,
			Vec3 origin,
			float distance,
			String category,
			List<UUID> recipientIds,
			String channelKey,
			long playbackId,
			float voiceGain,
			int frameIndex,
			long playbackStartNanos,
			short[] previousFrame,
			CompletableFuture<Void> future,
			ScheduledExecutorService executor
	) {
		if (shouldAbortPlayback(channelKey, playbackId, future)) {
			closeQuietly(encoder);
			future.complete(null);
			return;
		}
		if (isFadeOutRequested(channelKey, playbackId)) {
			scheduleNarrationFadeOut(
					server, voicechatServerApi, encoder, channelId, origin, distance, category,
					recipientIds, channelKey, playbackId, frameIndex, previousFrame, future, executor
			);
			return;
		}
		if (frameIndex >= clip.frames.length) {
			closeQuietly(encoder);
			future.complete(null);
			return;
		}

		long targetNanos = playbackStartNanos + TimeUnit.MILLISECONDS.toNanos((long) frameIndex * AUDIO_FRAME_DURATION_MS);
		long delayNanos = Math.max(0L, targetNanos - System.nanoTime());
		executor.schedule(() -> {
			if (shouldAbortPlayback(channelKey, playbackId, future)) {
				closeQuietly(encoder);
				future.complete(null);
				return;
			}
			if (isFadeOutRequested(channelKey, playbackId)) {
				scheduleNarrationFadeOut(
						server, voicechatServerApi, encoder, channelId, origin, distance, category,
						recipientIds, channelKey, playbackId, frameIndex, previousFrame, future, executor
				);
				return;
			}
			try {
				short[] pcmFrame = applyVoiceGain(clip.frames[frameIndex], voiceGain);
				byte[] opus = encoder.encode(pcmFrame);
				LocationSoundPacket soundPacket = new LocationSoundPacket(
						channelId,
						SERVER_VOICE_SOURCE_ID,
						origin,
						opus,
						frameIndex,
						distance,
						category
				);
				LocationalSoundPacketImpl wrappedPacket = new LocationalSoundPacketImpl(soundPacket);
				if (!shouldAbortPlayback(channelKey, playbackId, future)) {
					for (UUID recipientId : recipientIds) {
						ServerPlayer recipient = server.getPlayerList().getPlayer(recipientId);
						if ("global".equals(channelKey) && !SeasonStartSystem.canReceiveSharedNarration(recipient)) {
							continue;
						}
						VoicechatConnection receiverConnection = voicechatServerApi.getConnectionOf(recipientId);
						if (receiverConnection != null) {
							voicechatServerApi.sendLocationalSoundPacketTo(receiverConnection, wrappedPacket);
						}
					}
				}
				scheduleNarrationFrame(
						server,
						voicechatServerApi,
						clip,
						encoder,
						channelId,
						origin,
						distance,
						category,
						recipientIds,
						channelKey,
						playbackId,
						voiceGain,
						frameIndex + 1,
						playbackStartNanos,
						pcmFrame,
						future,
						executor
				);
			} catch (Throwable throwable) {
				closeQuietly(encoder);
				future.completeExceptionally(throwable);
			}
		}, delayNanos, TimeUnit.NANOSECONDS);
	}

	private static void scheduleNarrationFadeOut(
			MinecraftServer server,
			VoicechatServerApi voicechatServerApi,
			OpusEncoder encoder,
			UUID channelId,
			Vec3 origin,
			float distance,
			String category,
			List<UUID> recipientIds,
			String channelKey,
			long playbackId,
			int sequence,
			short[] previousFrame,
			CompletableFuture<Void> future,
			ScheduledExecutorService executor
	) {
		if (previousFrame == null || shouldAbortPlayback(channelKey, playbackId, future)) {
			closeQuietly(encoder);
			future.complete(null);
			return;
		}
		short[] fadeFrame = createInterruptFadeFrame(previousFrame);
		executor.execute(() -> {
			try {
				sendNarrationFrame(server, voicechatServerApi, encoder, channelId, origin, distance, category,
						recipientIds, channelKey, playbackId, sequence, fadeFrame, future);
				// One silent frame lets the client decoder finish the same stream cleanly.
				executor.schedule(() -> {
					try {
						sendNarrationFrame(server, voicechatServerApi, encoder, channelId, origin, distance, category,
								recipientIds, channelKey, playbackId, sequence + 1, new short[AUDIO_FRAME_SAMPLES], future);
					} finally {
						closeQuietly(encoder);
						future.complete(null);
					}
				}, AUDIO_FRAME_DURATION_MS, TimeUnit.MILLISECONDS);
			} catch (Throwable throwable) {
				closeQuietly(encoder);
				future.completeExceptionally(throwable);
			}
		});
	}

	private static void sendNarrationFrame(
			MinecraftServer server,
			VoicechatServerApi voicechatServerApi,
			OpusEncoder encoder,
			UUID channelId,
			Vec3 origin,
			float distance,
			String category,
			List<UUID> recipientIds,
			String channelKey,
			long playbackId,
			int sequence,
			short[] pcmFrame,
			CompletableFuture<Void> future
	) {
		if (shouldAbortPlayback(channelKey, playbackId, future)) {
			return;
		}
		byte[] opus = encoder.encode(pcmFrame);
		LocationSoundPacket soundPacket = new LocationSoundPacket(
				channelId, SERVER_VOICE_SOURCE_ID, origin, opus, sequence, distance, category
		);
		LocationalSoundPacketImpl wrappedPacket = new LocationalSoundPacketImpl(soundPacket);
		for (UUID recipientId : recipientIds) {
			ServerPlayer recipient = server.getPlayerList().getPlayer(recipientId);
			if ("global".equals(channelKey) && !SeasonStartSystem.canReceiveSharedNarration(recipient)) {
				continue;
			}
			VoicechatConnection receiverConnection = voicechatServerApi.getConnectionOf(recipientId);
			if (receiverConnection != null) {
				voicechatServerApi.sendLocationalSoundPacketTo(receiverConnection, wrappedPacket);
			}
		}
	}

	private static short[] createInterruptFadeFrame(short[] previousFrame) {
		short[] fade = new short[AUDIO_FRAME_SAMPLES];
		short lastSample = previousFrame.length == 0 ? 0 : previousFrame[previousFrame.length - 1];
		for (int index = 0; index < fade.length; index++) {
			double gain = 1.0D - Math.min(1.0D, (double) index / (double) NARRATION_INTERRUPT_FADE_SAMPLES);
			fade[index] = (short) Math.round(lastSample * gain);
		}
		return fade;
	}

	private static float resolveVoiceGain(VoiceCue cue) {
		if (cue == null || !Float.isFinite(cue.voiceGain)) {
			return 1.0F;
		}
		return Math.max(0.25F, Math.min(4.0F, cue.voiceGain));
	}

	private static short[] applyVoiceGain(short[] frame, float gain) {
		if (frame == null || gain <= 1.001F) {
			return frame;
		}
		short[] amplified = new short[frame.length];
		double normalizer = Math.tanh(gain);
		for (int index = 0; index < frame.length; index++) {
			double normalized = frame[index] / (double) Short.MAX_VALUE;
			int scaled = (int) Math.round(Short.MAX_VALUE * Math.tanh(normalized * gain) / normalizer);
			amplified[index] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, scaled));
		}
		return amplified;
	}

	private static CompletableFuture<Void> delayedFuture(int durationTicks) {
		CompletableFuture<Void> future = new CompletableFuture<>();
		ensurePlaybackExecutor().schedule(
				() -> future.complete(null),
				Math.max(1L, durationTicks) * 50L,
				TimeUnit.MILLISECONDS
		);
		return future;
	}

	private static CompletableFuture<Void> combineInterruptibleFutures(List<CompletableFuture<Void>> futures) {
		if (futures == null || futures.isEmpty()) {
			return CompletableFuture.completedFuture(null);
		}
		CompletableFuture<Void> combined = new CompletableFuture<>();
		CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
				.whenComplete((ignored, throwable) -> {
					if (throwable != null) {
						combined.completeExceptionally(throwable);
						return;
					}
					combined.complete(null);
				});
		combined.whenComplete((ignored, throwable) -> {
			for (CompletableFuture<Void> future : futures) {
				if (future != null && !future.isDone()) {
					future.complete(null);
				}
			}
		});
		return combined;
	}

	private static void bridgePlaybackFuture(ActiveCuePlayback playback, CompletableFuture<Void> actualFuture) {
		if (playback == null || playback.future == null) {
			return;
		}
		if (actualFuture == null) {
			playback.future.complete(null);
			return;
		}
		actualFuture.whenComplete((ignored, throwable) -> {
			if (throwable != null) {
				playback.future.completeExceptionally(throwable);
				return;
			}
			playback.future.complete(null);
		});
	}

	private static boolean shouldAbortPlayback(String channelKey, long playbackId, CompletableFuture<Void> future) {
		if (future != null && future.isDone()) {
			return true;
		}
		if (channelKey == null || channelKey.isBlank()) {
			return false;
		}
		ActiveCuePlayback active = ACTIVE_PLAYBACKS.get(channelKey);
		return active == null || active.playbackId != playbackId;
	}

	private static boolean isFadeOutRequested(String channelKey, long playbackId) {
		ActiveCuePlayback active = channelKey == null ? null : ACTIVE_PLAYBACKS.get(channelKey);
		return active != null && active.playbackId == playbackId && active.fadeOutRequested;
	}

	private static void sendChatFallback(ServerPlayer recipient, VoiceCue cue) {
		if (recipient == null || cue == null || !hasChatText(cue)) {
			return;
		}
		recipient.sendSystemMessage(Component.literal("[Сервер] " + cue.resolvedChatText()));
	}

	private static boolean hasChatText(VoiceCue cue) {
		if (cue == null) {
			return false;
		}
		String text = cue.resolvedChatText();
		return text != null && !text.isBlank();
	}

	private static Path resolveAudioPath(String relativePath) {
		if (relativePath == null || relativePath.isBlank()) {
			return null;
		}
		return SeasonStartConfig.voiceRoot().resolve(relativePath).normalize();
	}

	private static PcmClip loadClip(Path path) {
		if (path == null) {
			return null;
		}
		if (!Files.exists(path)) {
			return null;
		}
		return CLIP_CACHE.computeIfAbsent(path, SeasonStartVoiceSystem::readClip);
	}

	private static PcmClip readClip(Path path) {
		AudioFormat targetFormat = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, AUDIO_SAMPLE_RATE, 16, 1, 2, AUDIO_SAMPLE_RATE, false);
		try (AudioInputStream rawStream = AudioSystem.getAudioInputStream(path.toFile());
		     AudioInputStream pcmStream = AudioSystem.getAudioInputStream(targetFormat, rawStream);
		     ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			byte[] buffer = new byte[4096];
			int read;
			while ((read = pcmStream.read(buffer)) >= 0) {
				if (read == 0) {
					continue;
				}
				output.write(buffer, 0, read);
			}
			byte[] pcmBytes = output.toByteArray();
			if (pcmBytes.length == 0) {
				return null;
			}
			int sampleCount = pcmBytes.length / 2;
			int frameCount = Math.max(1, (sampleCount + AUDIO_FRAME_SAMPLES - 1) / AUDIO_FRAME_SAMPLES);
			short[][] frames = new short[frameCount][AUDIO_FRAME_SAMPLES];
			for (int sampleIndex = 0; sampleIndex < sampleCount; sampleIndex++) {
				int byteIndex = sampleIndex * 2;
				int low = pcmBytes[byteIndex] & 0xFF;
				int high = pcmBytes[byteIndex + 1];
				frames[sampleIndex / AUDIO_FRAME_SAMPLES][sampleIndex % AUDIO_FRAME_SAMPLES] = (short) ((high << 8) | low);
			}
			applyClipEdgeFades(frames);
			return new PcmClip(frames, frameCount);
		} catch (Exception exception) {
			Lg2.LOGGER.warn("Failed to load season-start voice clip {}", path, exception);
			return null;
		}
	}

	private static void applyClipEdgeFades(short[][] frames) {
		if (frames == null || frames.length == 0) {
			return;
		}
		short[] firstFrame = frames[0];
		if (firstFrame != null) {
			int fadeLength = Math.min(firstFrame.length, NARRATION_START_FADE_SAMPLES);
			for (int index = 0; index < fadeLength; index++) {
				double gain = (double) (index + 1) / (double) fadeLength;
				firstFrame[index] = (short) Math.round(firstFrame[index] * gain);
			}
		}
		short[] finalFrame = frames[frames.length - 1];
		if (finalFrame == null) {
			return;
		}
		int fadeStart = Math.max(0, finalFrame.length - NARRATION_END_FADE_SAMPLES);
		for (int index = fadeStart; index < finalFrame.length; index++) {
			double gain = (double) (finalFrame.length - 1 - index) / Math.max(1, finalFrame.length - fadeStart);
			finalFrame[index] = (short) Math.round(finalFrame[index] * Math.max(0.0D, gain));
		}
	}

	private static ScheduledExecutorService ensurePlaybackExecutor() {
		if (playbackExecutor == null || playbackExecutor.isShutdown()) {
			playbackExecutor = createPlaybackExecutor();
		}
		return playbackExecutor;
	}

	private static ScheduledExecutorService createPlaybackExecutor() {
		ThreadFactory factory = runnable -> {
			Thread thread = new Thread(runnable, "lg2-season-start-voice");
			thread.setDaemon(true);
			return thread;
		};
		return Executors.newScheduledThreadPool(4, factory);
	}

	private static void closeQuietly(OpusEncoder encoder) {
		if (encoder == null || encoder.isClosed()) {
			return;
		}
		try {
			encoder.close();
		} catch (RuntimeException ignored) {
		}
	}

	private static void shutdown() {
		resetRuntimeState();
		if (playbackExecutor != null) {
			playbackExecutor.shutdownNow();
			playbackExecutor = null;
		}
	}

	private static void resetRuntimeState() {
		resetSceneState();
	}

	private static void pruneExpiredQueuedCues(Deque<QueuedCue> queue, long nowTick) {
		if (queue == null || queue.isEmpty()) {
			return;
		}
		queue.removeIf(queuedCue -> isExpired(queuedCue, nowTick));
	}

	private static boolean isExpired(QueuedCue queuedCue, long nowTick) {
		if (queuedCue == null || queuedCue.cue == null) {
			return true;
		}
		long ttlTicks = resolveQueueTtlTicks(queuedCue.cue);
		return ttlTicks != Long.MAX_VALUE && nowTick > queuedCue.executeTick + ttlTicks;
	}

	private static long resolveQueueTtlTicks(VoiceCue cue) {
		return switch (classifyCue(cue)) {
			case FEEDBACK -> FEEDBACK_QUEUE_TTL_TICKS;
			case GUIDANCE -> GUIDANCE_QUEUE_TTL_TICKS;
			case URGENT -> URGENT_QUEUE_TTL_TICKS;
			case STORY -> Long.MAX_VALUE;
		};
	}

	private static boolean shouldReplaceQueuedCue(VoiceCue existingCue, CueRole incomingRole) {
		if (existingCue == null || incomingRole == null) {
			return false;
		}
		CueRole existingRole = classifyCue(existingCue);
		if (incomingRole == CueRole.FEEDBACK) {
			return existingRole == CueRole.FEEDBACK;
		}
		if (incomingRole == CueRole.GUIDANCE || incomingRole == CueRole.URGENT) {
			return existingRole == CueRole.FEEDBACK || existingRole == CueRole.GUIDANCE || existingRole == CueRole.URGENT;
		}
		return false;
	}

	private static boolean shouldInterruptTransientPlayback(ActiveCuePlayback activePlayback, QueuedCue incomingCue, long nowTick) {
		if (activePlayback == null || activePlayback.queuedCue == null || incomingCue == null || incomingCue.cue == null) {
			return false;
		}
		CueRole activeRole = classifyCue(activePlayback.queuedCue.cue);
		CueRole incomingRole = classifyCue(incomingCue.cue);
		if (activeRole != CueRole.FEEDBACK) {
			return false;
		}
		if (incomingRole != CueRole.GUIDANCE && incomingRole != CueRole.URGENT) {
			return false;
		}
		return nowTick - activePlayback.startedTick >= FEEDBACK_PREEMPT_GRACE_TICKS;
	}

	/**
	 * A global cue is one audio stream for several listeners, while a player cue is
	 * private. They used to have independent queues, which let both streams reach
	 * the same listener at once. Do not start either stream while the other one is
	 * still relevant to that listener.
	 */
	private static boolean isBlockedByOverlappingNarration(
			MinecraftServer server,
			String channelKey,
			QueuedCue queuedCue,
			long nowTick
	) {
		if (server == null || channelKey == null || queuedCue == null) {
			return false;
		}
		if ("global".equals(channelKey)) {
			for (ServerPlayer recipient : resolveRecipients(server, queuedCue.cue, queuedCue.focusPlayerId)) {
				if (recipient != null && hasPendingPlayerNarration(recipient.getUUID(), nowTick)) {
					return true;
				}
			}
			return false;
		}
		if (!channelKey.startsWith("player:") || queuedCue.focusPlayerId == null) {
			return false;
		}
		ActiveCuePlayback globalPlayback = ACTIVE_PLAYBACKS.get("global");
		if (globalPlayback == null || globalPlayback.future == null || globalPlayback.future.isDone()) {
			return false;
		}
		for (ServerPlayer recipient : resolveRecipients(server, globalPlayback.queuedCue.cue, globalPlayback.queuedCue.focusPlayerId)) {
			if (recipient != null && queuedCue.focusPlayerId.equals(recipient.getUUID())) {
				return true;
			}
		}
		return false;
	}

	private static boolean hasPendingPlayerNarration(UUID playerId, long nowTick) {
		if (playerId == null) {
			return false;
		}
		String channelKey = "player:" + playerId;
		ActiveCuePlayback activePlayback = ACTIVE_PLAYBACKS.get(channelKey);
		if (activePlayback != null && activePlayback.future != null && !activePlayback.future.isDone()) {
			return true;
		}
		Deque<QueuedCue> queue = CHANNEL_QUEUES.get(channelKey);
		if (queue == null || queue.isEmpty()) {
			return false;
		}
		pruneExpiredQueuedCues(queue, nowTick);
		return !queue.isEmpty();
	}

	private static void insertQueuedCue(Deque<QueuedCue> queue, QueuedCue queuedCue) {
		if (queue == null || queuedCue == null) {
			return;
		}
		if (queue.isEmpty()) {
			queue.addLast(queuedCue);
			return;
		}
		List<QueuedCue> ordered = new ArrayList<>(queue.size() + 1);
		boolean inserted = false;
		while (!queue.isEmpty()) {
			QueuedCue existing = queue.pollFirst();
			if (!inserted && compareQueuedCues(queuedCue, existing) < 0) {
				ordered.add(queuedCue);
				inserted = true;
			}
			ordered.add(existing);
		}
		if (!inserted) {
			ordered.add(queuedCue);
		}
		queue.addAll(ordered);
	}

	private static int compareQueuedCues(QueuedCue first, QueuedCue second) {
		if (first == second) {
			return 0;
		}
		if (first == null) {
			return 1;
		}
		if (second == null) {
			return -1;
		}
		int tickCompare = Long.compare(first.executeTick, second.executeTick);
		if (tickCompare != 0) {
			return tickCompare;
		}
		return Integer.compare(classifyCue(second.cue).priority, classifyCue(first.cue).priority);
	}

	private static CueRole classifyCue(VoiceCue cue) {
		if (cue == null) {
			return CueRole.STORY;
		}
		String id = cue.id == null ? "" : cue.id;
		String trigger = cue.trigger == null ? "" : cue.trigger;
		if (id.startsWith("stability_nonpayment_alarm")) {
			return CueRole.URGENT;
		}
		if (id.startsWith("guide_wrong_way_")
				|| id.startsWith("guide_passed_server_")
				|| id.startsWith("intro_guide_wrong_way_")
				|| id.startsWith("guide_turn_left_recover_")
				|| id.startsWith("guide_turn_right_recover_")) {
			return CueRole.URGENT;
		}
		if (trigger.startsWith("intro_phase1_")
				|| trigger.startsWith("intro_target_")
				|| id.startsWith("guide_locked_on_")
				|| id.startsWith("guide_heading_lost_")
				|| id.startsWith("guide_server_in_sight_")
				|| id.startsWith("guide_close_presence_")
				|| id.startsWith("guide_stall_")
				|| id.startsWith("guide_turn_left_recover_")
				|| id.startsWith("guide_turn_right_recover_")) {
			return CueRole.FEEDBACK;
		}
		if (trigger.startsWith("guide_") || trigger.startsWith("intro_guide_")) {
			return CueRole.GUIDANCE;
		}
		return CueRole.STORY;
	}

	private static boolean shouldInterruptImmediately(VoiceCue cue) {
		if (cue == null || cue.id == null || cue.id.isBlank()) {
			return false;
		}
		return cue.id.startsWith("guide_wrong_way_")
				|| cue.id.startsWith("guide_passed_server_")
				|| cue.id.startsWith("intro_guide_wrong_way_")
				|| cue.id.startsWith("guide_turn_left_recover_")
				|| cue.id.startsWith("guide_turn_right_recover_");
	}

	private static boolean shouldInterruptWithoutGrace(VoiceCue cue) {
		if (cue == null || cue.id == null || cue.id.isBlank()) {
			return false;
		}
		return cue.id.startsWith("guide_turn_left_recover_")
				|| cue.id.startsWith("guide_turn_right_recover_");
	}

	private static Object resolveRawServerPlayer(de.maxhenkel.voicechat.api.ServerPlayer player) {
		return player == null ? null : player.getPlayer();
	}

	private record QueuedCue(VoiceCue cue, UUID focusPlayerId, long executeTick, String channelKey) {
	}

	private static final class ActiveCuePlayback {
		private final QueuedCue queuedCue;
		private final CompletableFuture<Void> future;
		private final long startedTick;
		private final long playbackId;
		private volatile boolean voiceStreamStarted;
		private volatile boolean fadeOutRequested;

		private ActiveCuePlayback(QueuedCue queuedCue, CompletableFuture<Void> future, long startedTick, long playbackId) {
			this.queuedCue = queuedCue;
			this.future = future;
			this.startedTick = startedTick;
			this.playbackId = playbackId;
		}

		private void markVoiceStreamStarted() {
			voiceStreamStarted = true;
		}

		private void requestFadeOut() {
			fadeOutRequested = true;
		}
	}

	private enum CueRole {
		STORY(1),
		FEEDBACK(0),
		GUIDANCE(2),
		URGENT(3);

		private final int priority;

		CueRole(int priority) {
			this.priority = priority;
		}
	}

	private static final class PcmClip {
		private final short[][] frames;
		private final int durationTicks;

		private PcmClip(short[][] frames, int durationTicks) {
			this.frames = frames;
			this.durationTicks = durationTicks;
		}
	}
}
