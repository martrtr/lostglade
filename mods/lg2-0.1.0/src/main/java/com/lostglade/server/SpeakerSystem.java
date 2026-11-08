package com.lostglade.server;

import com.lostglade.Lg2;
import com.lostglade.block.ModBlocks;
import com.lostglade.block.SpeakerBlock;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.VolumeCategory;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class SpeakerSystem {
	private static final int HOTBAR_SLOT_COUNT = 9;
	private static final long REFRESH_INTERVAL_TICKS = 5L;
	private static final String SPEAKER_VOLUME_CATEGORY_ID = "lg_speakers";
	private static final String SPEAKER_VOLUME_CATEGORY_NAME = "Speakers";
	private static final String SPEAKER_VOLUME_CATEGORY_DESCRIPTION = "LG2 Speakers";
	private static final float MIN_SPEAKER_DISTANCE = 3.0F;
	private static final float MAX_SPEAKER_DISTANCE = 48.0F;
	private static final float MAX_SPEAKER_GAIN = 0.28F;
	private static final float SPEAKER_GAIN_EXPONENT = 1.75F;
	private static final float DISTANCE_COMPENSATION_AT_MAX = 0.84F;
	private static final float DISTANCE_COMPENSATION_EXPONENT = 1.35F;
	private static final int AUDIO_SAMPLE_RATE = 48_000;
	private static final int AUDIO_FRAME_SAMPLES = 960;
	private static final int AUDIO_FRAME_BYTES = AUDIO_FRAME_SAMPLES * 2;
	private static final long AUDIO_FRAME_DURATION_MS = AUDIO_FRAME_SAMPLES * 1000L / AUDIO_SAMPLE_RATE;
	private static final long AUDIO_FRAME_NANOS = TimeUnit.MILLISECONDS.toNanos(AUDIO_FRAME_DURATION_MS);
	private static final int SHARED_SOURCE_FRAME_BUFFER_CAPACITY = 512;
	private static final long AUDIO_RESYNC_TOLERANCE_MS = 500L;
	private static final int SHARED_SOURCE_STARTUP_BUFFER_FRAMES = 3;
	private static final int SHARED_SOURCE_PLAYBACK_LEAD_FRAMES = 2;
	private static final int SHARED_SOURCE_TARGET_LEAD_FRAMES = 256;
	private static final int MAX_STARTUP_CATCH_UP_FRAMES = 75;
	private static final long PROCESS_STARTUP_TIMEOUT_MS = 4_000L;
	private static final long PROCESS_STALL_TIMEOUT_MS = 3_000L;
	private static final long PROCESS_EOF_EXIT_TIMEOUT_MS = 1_000L;
	private static final long PROCESS_SHUTDOWN_TIMEOUT_MS = 200L;
	private static final long CONNECTED_SPEAKER_CACHE_TTL_TICKS = 4L;
	private static final short[] SILENCE_FRAME = new short[AUDIO_FRAME_SAMPLES];
	private static final Set<SpeakerKey> KNOWN_SPEAKERS = ConcurrentHashMap.newKeySet();
	private static final ConcurrentHashMap<SpeakerKey, SpeakerRuntime> ACTIVE_SPEAKERS = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<String, SharedSourceFeed> ACTIVE_SOURCE_FEEDS = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<SpeakerConnectionCacheKey, ConnectedSpeakerCacheEntry> CONNECTED_SPEAKER_CACHE = new ConcurrentHashMap<>();
	private static volatile boolean speakerVolumeCategoryRegistered = false;
	private static volatile long tickCounter = 0L;

	private SpeakerSystem() {
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(SpeakerSystem::tick);
		ServerChunkEvents.CHUNK_LOAD.register(SpeakerSystem::onChunkLoad);
		ServerChunkEvents.CHUNK_UNLOAD.register(SpeakerSystem::onChunkUnload);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> shutdownAll());
	}

	public static void trackSpeaker(ServerLevel level, BlockPos pos) {
		if (level == null || pos == null) {
			return;
		}
		if (KNOWN_SPEAKERS.add(new SpeakerKey(level.dimension(), pos.immutable()))) {
			invalidateConnectedSpeakerCache();
		}
	}

	public static void untrackSpeaker(ServerLevel level, BlockPos pos) {
		if (level == null || pos == null) {
			return;
		}
		SpeakerKey key = new SpeakerKey(level.dimension(), pos.immutable());
		if (KNOWN_SPEAKERS.remove(key)) {
			invalidateConnectedSpeakerCache();
		}
		stopRuntime(key);
	}

	public static void onSpeakerStateChanged(ServerLevel level, BlockPos pos) {
		if (level == null || pos == null) {
			return;
		}
		trackSpeaker(level, pos);
		invalidateConnectedSpeakerCache();
	}

	public static void refreshConnectedSpeakersNow(MinecraftServer server, ServerLevel level, BlockPos originPos) {
		if (server == null || level == null || originPos == null || !level.hasChunkAt(originPos)) {
			return;
		}
		for (BlockPos speakerPos : findConnectedPoweredSpeakerPositions(level, originPos)) {
			trackSpeaker(level, speakerPos);
			refreshSpeaker(server, new SpeakerKey(level.dimension(), speakerPos.immutable()));
		}
	}

	public static boolean onPlayerHotbarScroll(ServerPlayer player, int previousSlot, int currentSlot) {
		if (player == null || !(player.level() instanceof ServerLevel level)) {
			return false;
		}
		HitResult hit = player.pick(6.0D, 0.0F, false);
		if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
			return false;
		}
		BlockPos pos = blockHit.getBlockPos();
		BlockState state = level.getBlockState(pos);
		if (!state.is(ModBlocks.SPEAKER)) {
			return false;
		}
		int delta = normalizeHotbarDelta(previousSlot, currentSlot);
		if (delta == 0 || !SpeakerBlock.adjustVolumeByScroll(level, pos, state, player, delta)) {
			return false;
		}
		return true;
	}

	private static int normalizeHotbarDelta(int previousSlot, int currentSlot) {
		if (previousSlot == currentSlot) {
			return 0;
		}
		int upwardSteps = Math.floorMod(previousSlot - currentSlot, HOTBAR_SLOT_COUNT);
		if (upwardSteps >= 1 && upwardSteps <= 2) {
			return upwardSteps;
		}
		int downwardSteps = Math.floorMod(currentSlot - previousSlot, HOTBAR_SLOT_COUNT);
		if (downwardSteps >= 1 && downwardSteps <= 2) {
			return -downwardSteps;
		}
		return 0;
	}

	private static void onChunkLoad(ServerLevel level, LevelChunk chunk) {
		scanChunkForSpeakers(level, chunk);
		invalidateConnectedSpeakerCache();
	}

	private static void onChunkUnload(ServerLevel level, LevelChunk chunk) {
		if (level == null || chunk == null) {
			return;
		}
		int chunkX = chunk.getPos().x;
		int chunkZ = chunk.getPos().z;
		for (SpeakerKey key : new ArrayList<>(KNOWN_SPEAKERS)) {
			if (!key.dimension().equals(level.dimension())) {
				continue;
			}
			if (SectionPos.blockToSectionCoord(key.pos().getX()) != chunkX || SectionPos.blockToSectionCoord(key.pos().getZ()) != chunkZ) {
				continue;
			}
			KNOWN_SPEAKERS.remove(key);
			stopRuntime(key);
		}
		invalidateConnectedSpeakerCache();
	}

	private static void scanChunkForSpeakers(ServerLevel level, LevelChunk chunk) {
		if (level == null || chunk == null) {
			return;
		}
		int chunkMinX = chunk.getPos().getMinBlockX();
		int chunkMinZ = chunk.getPos().getMinBlockZ();
		LevelChunkSection[] sections = chunk.getSections();
		for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
			LevelChunkSection section = sections[sectionIndex];
			if (section == null || section.hasOnlyAir()) {
				continue;
			}
			int sectionY = level.getSectionYFromSectionIndex(sectionIndex);
			int sectionMinY = SectionPos.sectionToBlockCoord(sectionY);
			for (int localY = 0; localY < 16; localY++) {
				for (int localZ = 0; localZ < 16; localZ++) {
					for (int localX = 0; localX < 16; localX++) {
						if (!section.getBlockState(localX, localY, localZ).is(ModBlocks.SPEAKER)) {
							continue;
						}
						trackSpeaker(level, new BlockPos(chunkMinX + localX, sectionMinY + localY, chunkMinZ + localZ));
					}
				}
			}
		}
	}

	private static void tick(MinecraftServer server) {
		if (server == null) {
			return;
		}
		tickCounter++;
		if ((tickCounter % REFRESH_INTERVAL_TICKS) != 0L) {
			return;
		}

		Set<SpeakerKey> staleKeys = new HashSet<>(ACTIVE_SPEAKERS.keySet());
		for (SpeakerKey key : new ArrayList<>(KNOWN_SPEAKERS)) {
			boolean keepKnown = refreshSpeaker(server, key);
			if (!keepKnown) {
				KNOWN_SPEAKERS.remove(key);
			}
			staleKeys.remove(key);
		}
		for (SpeakerKey staleKey : staleKeys) {
			stopRuntime(staleKey);
		}
	}

	private static boolean refreshSpeaker(MinecraftServer server, SpeakerKey key) {
		ServerLevel level = server.getLevel(key.dimension());
		if (level == null || !level.hasChunkAt(key.pos())) {
			stopRuntime(key);
			return true;
		}

		BlockState state = level.getBlockState(key.pos());
		if (!state.is(ModBlocks.SPEAKER)) {
			stopRuntime(key);
			return false;
		}

		boolean rawPowered = hasSpeakerPower(level, key.pos());
		boolean connectedToPoweredMonitor = rawPowered && MonitorScreenSystem.hasPoweredConnectedMonitor(level, key.pos());
		if (SpeakerBlock.isLit(state) != connectedToPoweredMonitor) {
			state = state.setValue(BlockStateProperties.LIT, connectedToPoweredMonitor);
			level.setBlock(key.pos(), state, 3);
		}

		if (!rawPowered || SpeakerBlock.readVolumePercent(state) <= 0) {
			stopRuntime(key);
			return true;
		}

		List<SpeakerAudioSource> connectedSources = connectedToPoweredMonitor
				? MonitorScreenSystem.findSpeakerAudioSources(level, key.pos())
				: List.of();

		List<SpeakerAudioSource> playableSources = connectedSources.stream()
				.filter(source -> source != null && source.audioStreamUrl() != null && !source.audioStreamUrl().isBlank())
				.toList();
		VoicechatApi voicechatApi = ServerVoicechatIntegration.getApi();
		VoicechatServerApi voicechatServerApi = ServerVoicechatIntegration.getServerApi();
		if (!ServerVoicechatIntegration.isLoaded() || voicechatApi == null || voicechatServerApi == null) {
			stopRuntime(key);
			return true;
		}

		SpeakerRuntime runtime = ACTIVE_SPEAKERS.get(key);
		if (playableSources.isEmpty()) {
			if (runtime != null && connectedToPoweredMonitor) {
				if (!runtime.update(level, state, List.of(), voicechatApi, voicechatServerApi)) {
					stopRuntime(key);
				}
			} else {
				stopRuntime(key);
			}
			return true;
		}

		if (runtime == null) {
			runtime = new SpeakerRuntime(key);
			if (!runtime.start(level, state, playableSources, voicechatApi, voicechatServerApi)) {
				runtime.close();
				return true;
			}
			ACTIVE_SPEAKERS.put(key, runtime);
			return true;
		}

		if (!runtime.update(level, state, playableSources, voicechatApi, voicechatServerApi)) {
			stopRuntime(key);
		}
		return true;
	}

	private static boolean hasSpeakerPower(ServerLevel level, BlockPos pos) {
		return level != null && pos != null
				&& (level.hasNeighborSignal(pos) || level.getBestNeighborSignal(pos) > 0);
	}

	private static void stopRuntime(SpeakerKey key) {
		SpeakerRuntime runtime = ACTIVE_SPEAKERS.remove(key);
		if (runtime != null) {
			runtime.close();
		}
	}

	private static void shutdownAll() {
		for (SpeakerRuntime runtime : ACTIVE_SPEAKERS.values()) {
			runtime.close();
		}
		ACTIVE_SPEAKERS.clear();
		for (SharedSourceFeed feed : ACTIVE_SOURCE_FEEDS.values()) {
			feed.close();
		}
		ACTIVE_SOURCE_FEEDS.clear();
		VoicechatServerApi voicechatServerApi = ServerVoicechatIntegration.getServerApi();
		if (voicechatServerApi != null && speakerVolumeCategoryRegistered) {
			try {
				voicechatServerApi.unregisterVolumeCategory(SPEAKER_VOLUME_CATEGORY_ID);
			} catch (Exception exception) {
				Lg2.LOGGER.debug("Failed to unregister Simple Voice Chat speaker category", exception);
			}
		}
		speakerVolumeCategoryRegistered = false;
	}

	private static SharedSourceFeed acquireSharedSourceFeed(SpeakerKey speakerKey, SpeakerAudioSource source) {
		if (speakerKey == null || source == null || source.sourceKey() == null || source.sourceKey().isBlank()) {
			return null;
		}
		return ACTIVE_SOURCE_FEEDS.compute(source.sourceKey(), (ignored, existing) -> {
			SharedSourceFeed feed = existing;
			if (feed == null || feed.isClosed()) {
				feed = new SharedSourceFeed(source.sourceKey());
			}
			feed.addSpeaker(speakerKey);
			if (!feed.update(source)) {
				feed.removeSpeaker(speakerKey);
				feed.close();
				return null;
			}
			return feed;
		});
	}

	private static void releaseSharedSourceFeed(SpeakerKey speakerKey, String sourceKey, SharedSourceFeed expectedFeed) {
		if (sourceKey == null || sourceKey.isBlank()) {
			return;
		}
		ACTIVE_SOURCE_FEEDS.computeIfPresent(sourceKey, (ignored, existing) -> {
			if (expectedFeed != null && existing != expectedFeed) {
				expectedFeed.removeSpeaker(speakerKey);
				if (expectedFeed.isUnused()) {
					expectedFeed.close();
				}
				if (existing.isUnused()) {
					existing.close();
					return null;
				}
				return existing;
			}
			existing.removeSpeaker(speakerKey);
			if (existing.isUnused()) {
				existing.close();
				return null;
			}
			return existing;
		});
	}

	private static String ffmpegBinary() {
		String property = System.getProperty("lg2.ffmpegBin");
		if (property != null && !property.isBlank()) {
			return property.trim();
		}
		String environment = System.getenv("FFMPEG_BIN");
		if (environment != null && !environment.isBlank()) {
			return environment.trim();
		}
		return "ffmpeg";
	}

	private static boolean isNetworkAudioInput(String input) {
		if (input == null) {
			return false;
		}
		String normalized = input.trim().toLowerCase(java.util.Locale.ROOT);
		return normalized.startsWith("http://") || normalized.startsWith("https://");
	}

	static float volumeFactor(int volumePercent) {
		float normalized = Math.max(0.0F, Math.min(1.0F, volumePercent / 100.0F));
		if (normalized <= 0.0F) {
			return 0.0F;
		}
		return MAX_SPEAKER_GAIN * (float) Math.pow(normalized, SPEAKER_GAIN_EXPONENT);
	}

	static float maximumVolumeFactor() {
		return MAX_SPEAKER_GAIN;
	}

	static float audibleDistance(int volumePercent) {
		int clamped = Math.max(1, Math.min(100, volumePercent));
		if (clamped <= 1) {
			return MIN_SPEAKER_DISTANCE;
		}
		float normalized = (clamped - 1) / 99.0F;
		float designedDistance = MIN_SPEAKER_DISTANCE + (MAX_SPEAKER_DISTANCE - MIN_SPEAKER_DISTANCE) * normalized;
		// Simple Voice Chat's client attenuation feels longer than the raw distance value,
		// so we compensate at higher volumes to make the practical cutoff match the block range better.
		float compensation = 1.0F - (1.0F - DISTANCE_COMPENSATION_AT_MAX)
				* (float) Math.pow(normalized, DISTANCE_COMPENSATION_EXPONENT);
		return MIN_SPEAKER_DISTANCE + (designedDistance - MIN_SPEAKER_DISTANCE) * compensation;
	}

	static boolean ensureSpeakerVolumeCategoryRegistered(VoicechatApi voicechatApi, VoicechatServerApi voicechatServerApi) {
		if (speakerVolumeCategoryRegistered) {
			return true;
		}
		if (voicechatApi == null || voicechatServerApi == null) {
			return false;
		}
		try {
			VolumeCategory category = voicechatApi.volumeCategoryBuilder()
					.setId(SPEAKER_VOLUME_CATEGORY_ID)
					.setName(SPEAKER_VOLUME_CATEGORY_NAME)
					.setDescription(SPEAKER_VOLUME_CATEGORY_DESCRIPTION)
					.build();
			voicechatServerApi.registerVolumeCategory(category);
			speakerVolumeCategoryRegistered = true;
			return true;
		} catch (Exception exception) {
			Lg2.LOGGER.warn("Failed to register Simple Voice Chat speaker category, falling back to default category", exception);
			speakerVolumeCategoryRegistered = false;
			return false;
		}
	}

	private static boolean readFully(InputStream input, byte[] buffer) throws IOException {
		int offset = 0;
		while (offset < buffer.length) {
			int read = input.read(buffer, offset, buffer.length - offset);
			if (read < 0) {
				return false;
			}
			offset += read;
		}
		return true;
	}

	private static short[] decodePcmFrame(byte[] bytes) {
		short[] frame = new short[AUDIO_FRAME_SAMPLES];
		for (int index = 0; index < AUDIO_FRAME_SAMPLES; index++) {
			int byteIndex = index * 2;
			int low = bytes[byteIndex] & 0xFF;
			int high = bytes[byteIndex + 1];
			frame[index] = (short) ((high << 8) | low);
		}
		return frame;
	}

	private record SpeakerKey(ResourceKey<Level> dimension, BlockPos pos) {
	}

	private record SpeakerConnectionCacheKey(ResourceKey<Level> dimension, BlockPos originPos) {
	}

	private record ConnectedSpeakerCacheEntry(long expiresAtGameTime, List<BlockPos> speakerPositions) {
	}

	private static final class SpeakerRuntime {
		private final SpeakerKey key;
		private final ConcurrentHashMap<String, SourceSubscriptionRuntime> sourceFeeds;
		private volatile int volumePercent;
		private volatile boolean closed;
		private volatile UUID channelId;
		private OpusEncoder encoder;
		private LocationalAudioChannel channel;
		private AudioPlayer player;

		private SpeakerRuntime(SpeakerKey key) {
			this.key = key;
			this.channelId = UUID.randomUUID();
			this.sourceFeeds = new ConcurrentHashMap<>();
			this.volumePercent = 50;
			this.closed = false;
		}

		private boolean start(
				ServerLevel level,
				BlockState state,
				List<SpeakerAudioSource> sources,
				VoicechatApi voicechatApi,
				VoicechatServerApi voicechatServerApi
		) {
			this.volumePercent = SpeakerBlock.readVolumePercent(state);
			if (!ensureVoicechatPlayer(level, voicechatApi, voicechatServerApi)) {
				return false;
			}
			boolean playbackResyncNeeded = synchronizeSources(sources);
			if (playbackResyncNeeded && !restartVoicechatPlayer(level, voicechatApi, voicechatServerApi)) {
				return false;
			}
			return true;
		}

		private boolean update(
				ServerLevel level,
				BlockState state,
				List<SpeakerAudioSource> sources,
				VoicechatApi voicechatApi,
				VoicechatServerApi voicechatServerApi
		) {
			if (this.closed) {
				return false;
			}
			this.volumePercent = SpeakerBlock.readVolumePercent(state);
			if (!ensureVoicechatPlayer(level, voicechatApi, voicechatServerApi)) {
				return false;
			}
			boolean playbackResyncNeeded = synchronizeSources(sources);
			if (playbackResyncNeeded && !restartVoicechatPlayer(level, voicechatApi, voicechatServerApi)) {
				return false;
			}
			return true;
		}

		private boolean ensureVoicechatPlayer(ServerLevel level, VoicechatApi voicechatApi, VoicechatServerApi voicechatServerApi) {
			if (voicechatApi == null || voicechatServerApi == null || level == null) {
				return false;
			}
			ensureSpeakerVolumeCategoryRegistered(voicechatApi, voicechatServerApi);
			if (this.player != null && !this.player.isStopped()) {
				updateChannelProperties(level, voicechatApi, voicechatServerApi);
				return true;
			}
			OpusEncoder createdEncoder = null;
			try {
				createdEncoder = voicechatApi.createEncoder();
				LocationalAudioChannel createdChannel = voicechatServerApi.createLocationalAudioChannel(
						this.channelId,
						voicechatApi.fromServerLevel(level),
						voicechatApi.createPosition(this.key.pos().getX() + 0.5D, this.key.pos().getY() + 0.5D, this.key.pos().getZ() + 0.5D)
				);
				this.encoder = createdEncoder;
				this.channel = createdChannel;
				updateChannelProperties(level, voicechatApi, voicechatServerApi);
				AudioPlayer createdPlayer = voicechatServerApi.createAudioPlayer(createdChannel, createdEncoder, this::nextFrame);
				createdPlayer.setOnStopped(() -> onPlayerStopped(createdPlayer, createdChannel));
				this.player = createdPlayer;
				createdPlayer.startPlaying();
				return true;
			} catch (RuntimeException exception) {
				if (createdEncoder != null && !createdEncoder.isClosed()) {
					createdEncoder.close();
				}
				this.encoder = null;
				this.channel = null;
				this.player = null;
				throw exception;
			}
		}

		private void onPlayerStopped(AudioPlayer stoppedPlayer, LocationalAudioChannel stoppedChannel) {
			boolean clearedPlayer = false;
			if (this.player == stoppedPlayer) {
				this.player = null;
				clearedPlayer = true;
			}
			if (this.channel == stoppedChannel) {
				this.channel = null;
			}
			if (clearedPlayer) {
				this.encoder = null;
			}
		}

		private void updateChannelProperties(ServerLevel level, VoicechatApi voicechatApi, VoicechatServerApi voicechatServerApi) {
			if (this.channel == null) {
				return;
			}
			this.channel.updateLocation(voicechatApi.createPosition(this.key.pos().getX() + 0.5D, this.key.pos().getY() + 0.5D, this.key.pos().getZ() + 0.5D));
			this.channel.setDistance(audibleDistance(this.volumePercent));
			if (speakerVolumeCategoryRegistered) {
				this.channel.setCategory(SPEAKER_VOLUME_CATEGORY_ID);
			}
		}

		private boolean restartVoicechatPlayer(ServerLevel level, VoicechatApi voicechatApi, VoicechatServerApi voicechatServerApi) {
			if (this.closed) {
				return false;
			}
			AudioPlayer currentPlayer = this.player;
			this.player = null;
			this.channel = null;
			this.encoder = null;
			this.channelId = UUID.randomUUID();
			if (currentPlayer != null && !currentPlayer.isStopped()) {
				currentPlayer.stopPlaying();
			}
			return ensureVoicechatPlayer(level, voicechatApi, voicechatServerApi);
		}

		private boolean synchronizeSources(List<SpeakerAudioSource> sources) {
			Set<String> keepKeys = new HashSet<>();
			boolean[] playbackResyncNeeded = new boolean[] {false};
			for (SpeakerAudioSource source : sources) {
				if (source == null || source.sourceKey() == null || source.sourceKey().isBlank()) {
					continue;
				}
				keepKeys.add(source.sourceKey());
				this.sourceFeeds.compute(source.sourceKey(), (key, existing) -> {
					SharedSourceFeed feed = acquireSharedSourceFeed(this.key, source);
					if (feed == null) {
						if (existing != null) {
							existing.close(this.key);
						}
						playbackResyncNeeded[0] = true;
						return null;
					}
					if (existing != null && existing.feed() == feed) {
						if (existing.updateGeneration(feed.generation())) {
							playbackResyncNeeded[0] = true;
						}
						return existing;
					}
					if (existing != null) {
						existing.close(this.key);
					}
					playbackResyncNeeded[0] = true;
					return new SourceSubscriptionRuntime(source.sourceKey(), feed, feed.generation());
				});
			}
			for (Map.Entry<String, SourceSubscriptionRuntime> entry : new ArrayList<>(this.sourceFeeds.entrySet())) {
				if (keepKeys.contains(entry.getKey())) {
					continue;
				}
				SourceSubscriptionRuntime removed = this.sourceFeeds.remove(entry.getKey());
				if (removed != null) {
					removed.close(this.key);
					playbackResyncNeeded[0] = true;
				}
			}
			return playbackResyncNeeded[0];
		}

		private short[] nextFrame() {
			if (this.sourceFeeds.isEmpty()) {
				return SILENCE_FRAME;
			}
			long nowNanos = System.nanoTime();
			float[] mixed = null;
			int contributors = 0;
			for (SourceSubscriptionRuntime sourceFeed : this.sourceFeeds.values()) {
				short[] frame = sourceFeed.frameAt(nowNanos);
				if (frame == null) {
					continue;
				}
				if (mixed == null) {
					mixed = new float[AUDIO_FRAME_SAMPLES];
				}
				for (int index = 0; index < frame.length; index++) {
					mixed[index] += frame[index];
				}
				contributors++;
			}
			if (mixed == null || contributors <= 0) {
				return SILENCE_FRAME;
			}
			float factor = volumeFactor(this.volumePercent);
			if (factor <= 0.0F) {
				return SILENCE_FRAME;
			}
			short[] output = new short[AUDIO_FRAME_SAMPLES];
			for (int index = 0; index < output.length; index++) {
				float averaged = mixed[index] / (float) contributors;
				float scaled = averaged * factor;
				output[index] = softLimitSample(scaled);
			}
			MicrophoneSystem.offerSpeakerAudio(this.key.dimension(), this.key.pos(), audibleDistance(this.volumePercent), output);
			return output;
		}

		private void close() {
			this.closed = true;
			for (SourceSubscriptionRuntime sourceFeed : this.sourceFeeds.values()) {
				sourceFeed.close(this.key);
			}
			this.sourceFeeds.clear();
			AudioPlayer currentPlayer = this.player;
			this.player = null;
			this.encoder = null;
			// AudioPlayer closes its encoder on its own worker thread. Closing it here races with that thread
			// and produces intermittent "Encoder is closed" noise in the logs.
			if (currentPlayer != null && !currentPlayer.isStopped()) {
				currentPlayer.stopPlaying();
			}
			this.channel = null;
		}
	}

	static short softLimitSample(float sample) {
		float normalized = Math.max(-4.0F, Math.min(4.0F, sample / (float) Short.MAX_VALUE));
		float limited = (float) Math.tanh(normalized);
		return (short) Math.round(limited * Short.MAX_VALUE);
	}

	static boolean isSpeakerVolumeCategoryRegistered() {
		return speakerVolumeCategoryRegistered;
	}

	static String speakerVolumeCategoryId() {
		return SPEAKER_VOLUME_CATEGORY_ID;
	}

	public static List<BlockPos> findConnectedPoweredSpeakerPositions(ServerLevel level, BlockPos originPos) {
		if (level == null || originPos == null || !level.hasChunkAt(originPos)) {
			return List.of();
		}
		SpeakerConnectionCacheKey cacheKey = new SpeakerConnectionCacheKey(level.dimension(), originPos.immutable());
		long gameTime = level.getGameTime();
		ConnectedSpeakerCacheEntry cached = CONNECTED_SPEAKER_CACHE.get(cacheKey);
		if (cached != null && cached.expiresAtGameTime() >= gameTime) {
			return cached.speakerPositions();
		}
		Set<BlockPos> wireNetwork = collectWireNetwork(level, originPos);
		List<BlockPos> connected = new ArrayList<>();
		for (SpeakerKey key : KNOWN_SPEAKERS) {
			if (!Objects.equals(key.dimension(), level.dimension()) || !level.hasChunkAt(key.pos())) {
				continue;
			}
			BlockState state = level.getBlockState(key.pos());
			if (!state.is(ModBlocks.SPEAKER) || !hasSpeakerPower(level, key.pos()) || SpeakerBlock.readVolumePercent(state) <= 0) {
				continue;
			}
			if (!isConnectedToOrigin(originPos, key.pos(), wireNetwork)) {
				continue;
			}
			connected.add(key.pos().immutable());
		}
		BluetoothLinkSystem.Endpoint originEndpoint = BluetoothLinkSystem.resolveBlockEndpoint(level, originPos);
		if (originEndpoint != null) {
			for (BluetoothLinkSystem.Endpoint linked : BluetoothLinkSystem.linkedEndpoints(originEndpoint)) {
				if (linked.type() != BluetoothLinkSystem.EndpointType.SPEAKER) {
					continue;
				}
				ServerLevel linkedLevel = level.getServer() == null ? null : level.getServer().getLevel(linked.dimension());
				if (linkedLevel == null || !linkedLevel.hasChunkAt(linked.pos())) {
					continue;
				}
				BlockState linkedState = linkedLevel.getBlockState(linked.pos());
				if (!linkedState.is(ModBlocks.SPEAKER)
						|| !hasSpeakerPower(linkedLevel, linked.pos())
						|| SpeakerBlock.readVolumePercent(linkedState) <= 0) {
					continue;
				}
				if (Objects.equals(linked.dimension(), level.dimension())) {
					BlockPos wirelessPos = linked.pos().immutable();
					if (!connected.contains(wirelessPos)) {
						connected.add(wirelessPos);
					}
				} else {
					trackSpeaker(linkedLevel, linked.pos());
					refreshSpeaker(level.getServer(), new SpeakerKey(linked.dimension(), linked.pos().immutable()));
				}
			}
		}
		List<BlockPos> cachedPositions = List.copyOf(connected);
		CONNECTED_SPEAKER_CACHE.put(cacheKey, new ConnectedSpeakerCacheEntry(gameTime + CONNECTED_SPEAKER_CACHE_TTL_TICKS, cachedPositions));
		return cachedPositions;
	}

	private static void invalidateConnectedSpeakerCache() {
		CONNECTED_SPEAKER_CACHE.clear();
	}

	private static boolean isConnectedToOrigin(BlockPos originPos, BlockPos speakerPos, Set<BlockPos> wireNetwork) {
		if (originPos == null || speakerPos == null) {
			return false;
		}
		if (areBlocksAdjacent(originPos, speakerPos)) {
			return true;
		}
		if (wireNetwork.isEmpty()) {
			return false;
		}
		for (BlockPos touchPos : redstoneTouchPoints(speakerPos)) {
			if (wireNetwork.contains(touchPos)) {
				return true;
			}
		}
		return false;
	}

	private static Set<BlockPos> collectWireNetwork(ServerLevel level, BlockPos originPos) {
		Set<BlockPos> visited = new HashSet<>();
		ArrayList<BlockPos> queue = new ArrayList<>();
		for (BlockPos touchPos : redstoneTouchPoints(originPos)) {
			if (!isRedstoneWire(level, touchPos)) {
				continue;
			}
			BlockPos immutable = touchPos.immutable();
			if (!visited.add(immutable)) {
				continue;
			}
			queue.add(immutable);
		}
		for (int index = 0; index < queue.size(); index++) {
			BlockPos current = queue.get(index);
			for (BlockPos neighbor : redstoneWireNeighbors(current)) {
				if (!isRedstoneWire(level, neighbor)) {
					continue;
				}
				BlockPos immutable = neighbor.immutable();
				if (!visited.add(immutable)) {
					continue;
				}
				queue.add(immutable);
			}
		}
		return visited;
	}

	private static boolean isRedstoneWire(ServerLevel level, BlockPos pos) {
		return level != null && pos != null && level.hasChunkAt(pos) && level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.REDSTONE_WIRE);
	}

	private static List<BlockPos> redstoneTouchPoints(BlockPos pos) {
		return List.of(
				pos,
				pos.above(),
				pos.below(),
				pos.north(),
				pos.south(),
				pos.east(),
				pos.west(),
				pos.above().north(),
				pos.above().south(),
				pos.above().east(),
				pos.above().west(),
				pos.below().north(),
				pos.below().south(),
				pos.below().east(),
				pos.below().west()
		);
	}

	private static List<BlockPos> redstoneWireNeighbors(BlockPos pos) {
		List<BlockPos> neighbors = new ArrayList<>(14);
		neighbors.add(pos.north());
		neighbors.add(pos.south());
		neighbors.add(pos.east());
		neighbors.add(pos.west());
		neighbors.add(pos.above());
		neighbors.add(pos.below());
		neighbors.add(pos.above().north());
		neighbors.add(pos.above().south());
		neighbors.add(pos.above().east());
		neighbors.add(pos.above().west());
		neighbors.add(pos.below().north());
		neighbors.add(pos.below().south());
		neighbors.add(pos.below().east());
		neighbors.add(pos.below().west());
		return neighbors;
	}

	private static boolean areBlocksAdjacent(BlockPos first, BlockPos second) {
		if (first == null || second == null) {
			return false;
		}
		return Math.abs(first.getX() - second.getX()) + Math.abs(first.getY() - second.getY()) + Math.abs(first.getZ() - second.getZ()) <= 1;
	}

	private static final class SourceSubscriptionRuntime {
		private final String sourceKey;
		private final SharedSourceFeed feed;
		private long feedGeneration;

		private SourceSubscriptionRuntime(String sourceKey, SharedSourceFeed feed, long feedGeneration) {
			this.sourceKey = sourceKey;
			this.feed = feed;
			this.feedGeneration = feedGeneration;
		}

		private SharedSourceFeed feed() {
			return this.feed;
		}

		private boolean updateGeneration(long generation) {
			if (this.feedGeneration == generation) {
				return false;
			}
			this.feedGeneration = generation;
			return true;
		}

		private short[] frameAt(long nowNanos) {
			return this.feed != null ? this.feed.frameAt(nowNanos) : null;
		}

		private void close(SpeakerKey speakerKey) {
			releaseSharedSourceFeed(speakerKey, this.sourceKey, this.feed);
		}
	}

	private static final class SharedSourceFeed {
		private final String sourceKey;
		private final Object lock = new Object();
		private final Set<SpeakerKey> speakers = ConcurrentHashMap.newKeySet();
		private final NavigableMap<Long, short[]> frameBuffer = new TreeMap<>();
		private volatile boolean closed;
		private Process process;
		private Thread readerThread;
		private String relaySessionId;
		private String audioStreamUrl;
		private boolean liveStream;
		private boolean loop;
		private boolean paused;
		private long processBasePositionMs;
		private long audioSyncToken;
		private long nextFrameSequence;
		private long playbackEpochNanos;
		private long processStartedAtMillis;
		private long lastFrameAtMillis;
		private long generation;
		private boolean processEndedCleanly;
		private long startupFramesToDiscard;

		private SharedSourceFeed(String sourceKey) {
			this.sourceKey = sourceKey;
		}

		private void addSpeaker(SpeakerKey speakerKey) {
			if (speakerKey == null) {
				return;
			}
			boolean added = this.speakers.add(speakerKey);
			if (!added) {
				return;
			}
			synchronized (this.lock) {
				// When a speaker rejoins an already-playing shared feed, Simple Voice Chat gives the
				// new locational channel its own startup latency. Bumping the generation forces every
				// subscribed speaker on this feed to recreate its channel so they realign together.
				if (!this.closed && this.process != null && this.speakers.size() > 1) {
					this.generation++;
				}
			}
		}

		private void removeSpeaker(SpeakerKey speakerKey) {
			if (speakerKey != null) {
				this.speakers.remove(speakerKey);
			}
		}

		private boolean isUnused() {
			return this.speakers.isEmpty();
		}

		private boolean isClosed() {
			return this.closed;
		}

		private boolean update(SpeakerAudioSource source) {
			synchronized (this.lock) {
				if (this.closed || source == null || source.audioStreamUrl() == null || source.audioStreamUrl().isBlank()) {
					return false;
				}
				if (source.paused()) {
					return suspendProcessLocked(source);
				}
				if (this.paused) {
					return restartProcessLocked(source);
				}
				if (shouldRestartLocked(source)) {
					return restartProcessLocked(source);
				}
				return this.process != null || this.processEndedCleanly;
			}
		}

		private short[] frameAt(long nowNanos) {
			synchronized (this.lock) {
				if (this.paused) {
					return null;
				}
				if (this.frameBuffer.isEmpty()) {
					return null;
				}
				if (this.playbackEpochNanos == 0L) {
					return null;
				}
				Map.Entry<Long, short[]> first = this.frameBuffer.firstEntry();
				Map.Entry<Long, short[]> last = this.frameBuffer.lastEntry();
				if (first == null || last == null) {
					return null;
				}
				long targetSequence = targetSequenceLocked(nowNanos);
				if (targetSequence < first.getKey()) {
					return first.getValue();
				}
				if (targetSequence > last.getKey()) {
					// If the decoder/network falls behind, stop advancing the playback clock and let
					// readLoop re-anchor once a small lead is buffered again instead of outputting
					// long random silence in the middle of a track.
					this.playbackEpochNanos = 0L;
					return null;
				}
				Map.Entry<Long, short[]> frameEntry = this.frameBuffer.floorEntry(targetSequence);
				return frameEntry != null ? frameEntry.getValue() : null;
			}
		}

		private boolean shouldRestartLocked(SpeakerAudioSource source) {
			if (this.process == null || !this.process.isAlive()) {
				return SpeakerAudioPlaybackPolicy.shouldRestartAfterProcessExit(
						this.processEndedCleanly,
						isNetworkAudioInput(this.audioStreamUrl),
						this.liveStream,
						this.loop,
						this.relaySessionId,
						source.relaySessionId(),
						this.audioStreamUrl,
						source.audioStreamUrl(),
						this.audioSyncToken,
						source.audioSyncToken()
				);
			}
			if (!Objects.equals(this.relaySessionId, source.relaySessionId())
					|| !Objects.equals(this.audioStreamUrl, source.audioStreamUrl())
					|| this.liveStream != source.liveStream()
					|| this.loop != source.loop()
					|| this.audioSyncToken != source.audioSyncToken()) {
				return true;
			}
			if (decoderStartupTimedOutLocked() || decoderStalledBeforePlaybackLocked()) {
				return true;
			}
			return SpeakerAudioPlaybackPolicy.shouldResyncPosition(
					this.liveStream,
					source.loading(),
					source.positionAuthoritative(),
					expectedPositionMsLocked(),
					source.positionMs(),
					AUDIO_RESYNC_TOLERANCE_MS
			);
		}

		private boolean decoderStartupTimedOutLocked() {
			long nowMillis = System.currentTimeMillis();
			return this.processStartedAtMillis > 0L
					&& this.lastFrameAtMillis == 0L
					&& nowMillis - this.processStartedAtMillis > PROCESS_STARTUP_TIMEOUT_MS;
		}

		private boolean decoderStalledBeforePlaybackLocked() {
			long nowMillis = System.currentTimeMillis();
			return this.processStartedAtMillis > 0L
					&& this.lastFrameAtMillis > 0L
					&& this.playbackEpochNanos == 0L
					&& nowMillis - this.lastFrameAtMillis > PROCESS_STALL_TIMEOUT_MS;
		}

		private long expectedPositionMsLocked() {
			return this.processBasePositionMs + targetSequenceLocked(System.nanoTime()) * AUDIO_FRAME_DURATION_MS;
		}

		private long targetSequenceLocked(long nowNanos) {
			if (this.playbackEpochNanos == 0L || nowNanos <= this.playbackEpochNanos) {
				return 0L;
			}
			return Math.max(0L, (nowNanos - this.playbackEpochNanos) / AUDIO_FRAME_NANOS);
		}

		private boolean restartProcessLocked(SpeakerAudioSource source) {
			stopProcessLocked();
			this.frameBuffer.clear();
			this.relaySessionId = source.relaySessionId();
			this.audioStreamUrl = source.audioStreamUrl();
			this.liveStream = source.liveStream();
			this.loop = source.loop();
			this.paused = false;
			this.processBasePositionMs = Math.max(0L, source.positionMs());
			this.audioSyncToken = source.audioSyncToken();
			this.nextFrameSequence = 0L;
			this.playbackEpochNanos = 0L;
			this.processStartedAtMillis = 0L;
			this.lastFrameAtMillis = 0L;
			this.processEndedCleanly = false;
			this.startupFramesToDiscard = -1L;
			this.generation++;

			List<String> command = new ArrayList<>();
			command.add(ffmpegBinary());
			command.add("-hide_banner");
			command.add("-loglevel");
			command.add("error");
			command.add("-nostdin");
			if (!source.liveStream() && source.positionMs() > 0L) {
				command.add("-ss");
				command.add(String.format(java.util.Locale.ROOT, "%.3f", source.positionMs() / 1000.0D));
			}
			if (source.loop()) {
				command.add("-stream_loop");
				command.add("-1");
			}
			if (isNetworkAudioInput(source.audioStreamUrl())) {
				command.add("-reconnect");
				command.add("1");
				command.add("-reconnect_streamed");
				command.add("1");
				command.add("-reconnect_at_eof");
				command.add("1");
				command.add("-reconnect_delay_max");
				command.add("3");
				command.add("-rw_timeout");
				command.add("15000000");
			}
			command.add("-i");
			command.add(source.audioStreamUrl());
			command.add("-vn");
			command.add("-ac");
			command.add("1");
			command.add("-ar");
			command.add(Integer.toString(AUDIO_SAMPLE_RATE));
			command.add("-f");
			command.add("s16le");
			command.add("-acodec");
			command.add("pcm_s16le");
			command.add("-");

			try {
				this.process = new ProcessBuilder(command)
						.redirectError(ProcessBuilder.Redirect.DISCARD)
						.start();
				this.processStartedAtMillis = System.currentTimeMillis();
			} catch (IOException exception) {
				Lg2.LOGGER.warn("Failed to start shared speaker ffmpeg process for source {}", this.sourceKey, exception);
				this.process = null;
				this.processStartedAtMillis = 0L;
				return false;
			}

			Process currentProcess = this.process;
			// Start the playback clock only after ffmpeg has produced a small PCM lead.
			// Starting it immediately makes targetSequence race ahead during network/process startup,
			// which causes long silence after seek/resume until the decoder "catches up".
			this.playbackEpochNanos = 0L;
			Thread thread = new Thread(() -> readLoop(currentProcess), "lg2-speaker-shared-" + Integer.toHexString(this.sourceKey.hashCode()));
			thread.setDaemon(true);
			this.readerThread = thread;
			thread.start();
			return true;
		}

		private boolean suspendProcessLocked(SpeakerAudioSource source) {
			stopProcessLocked();
			this.frameBuffer.clear();
			this.relaySessionId = source.relaySessionId();
			this.audioStreamUrl = source.audioStreamUrl();
			this.liveStream = source.liveStream();
			this.loop = source.loop();
			this.paused = true;
			this.processBasePositionMs = Math.max(0L, source.positionMs());
			this.audioSyncToken = source.audioSyncToken();
			this.nextFrameSequence = 0L;
			this.playbackEpochNanos = 0L;
			this.processStartedAtMillis = 0L;
			this.lastFrameAtMillis = 0L;
			this.processEndedCleanly = false;
			this.startupFramesToDiscard = -1L;
			return true;
		}

		private long generation() {
			return this.generation;
		}

		private void readLoop(Process processToRead) {
			byte[] buffer = new byte[AUDIO_FRAME_BYTES];
			boolean exhaustedInput = false;
			try (InputStream input = processToRead.getInputStream()) {
				while (!this.closed) {
					if (!readFully(input, buffer)) {
						exhaustedInput = true;
						break;
					}
					short[] frame = decodePcmFrame(buffer);
					long sleepMillis = 0L;
					synchronized (this.lock) {
						if (this.process != processToRead || this.closed) {
							return;
						}
						this.lastFrameAtMillis = System.currentTimeMillis();
						if (!this.liveStream && this.startupFramesToDiscard < 0L) {
							long startupDelayMs = Math.max(0L, this.lastFrameAtMillis - this.processStartedAtMillis);
							this.startupFramesToDiscard = Math.min(
									MAX_STARTUP_CATCH_UP_FRAMES,
									startupDelayMs / AUDIO_FRAME_DURATION_MS
							);
						}
						if (this.startupFramesToDiscard > 0L) {
							// The monitor timeline has continued while the remote audio
							// connection/decoder started. Consume the corresponding PCM lead
							// so playback joins the already-running video instead of trailing it.
							this.startupFramesToDiscard--;
							this.nextFrameSequence++;
							continue;
						}
						long frameSequence = this.nextFrameSequence++;
						this.frameBuffer.put(frameSequence, frame);
						if (this.playbackEpochNanos == 0L && this.frameBuffer.size() >= SHARED_SOURCE_STARTUP_BUFFER_FRAMES) {
							long anchorSequence = Math.max(0L, frameSequence - SHARED_SOURCE_PLAYBACK_LEAD_FRAMES);
							this.playbackEpochNanos = System.nanoTime() - anchorSequence * AUDIO_FRAME_NANOS;
						}
						while (this.frameBuffer.size() > SHARED_SOURCE_FRAME_BUFFER_CAPACITY) {
							this.frameBuffer.pollFirstEntry();
						}
						long leadFrames = frameSequence - targetSequenceLocked(System.nanoTime());
						if (leadFrames > SHARED_SOURCE_TARGET_LEAD_FRAMES) {
							sleepMillis = Math.min(
									100L,
									(leadFrames - SHARED_SOURCE_TARGET_LEAD_FRAMES) * AUDIO_FRAME_DURATION_MS
							);
						}
					}
					if (sleepMillis > 0L) {
						try {
							Thread.sleep(sleepMillis);
						} catch (InterruptedException exception) {
							Thread.currentThread().interrupt();
							return;
						}
					}
				}
			} catch (IOException ignored) {
			} finally {
				boolean processEndedCleanly = false;
				if (exhaustedInput) {
					try {
						processEndedCleanly = processToRead.waitFor(PROCESS_EOF_EXIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
								&& processToRead.exitValue() == 0;
					} catch (InterruptedException exception) {
						Thread.currentThread().interrupt();
					} catch (Exception ignored) {
					}
				}
				synchronized (this.lock) {
					if (this.process == processToRead) {
						this.processEndedCleanly = processEndedCleanly;
						this.process = null;
						this.readerThread = null;
						this.processStartedAtMillis = 0L;
						this.lastFrameAtMillis = 0L;
					}
				}
				processToRead.destroy();
			}
		}

		private void stopProcessLocked() {
			Process currentProcess = this.process;
			this.process = null;
			this.readerThread = null;
			this.processStartedAtMillis = 0L;
			this.lastFrameAtMillis = 0L;
			this.processEndedCleanly = false;
			if (currentProcess == null) {
				return;
			}
			try {
				currentProcess.destroy();
				if (!currentProcess.waitFor(PROCESS_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
					currentProcess.destroyForcibly();
					currentProcess.waitFor(PROCESS_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				currentProcess.destroyForcibly();
			} catch (Exception ignored) {
				currentProcess.destroyForcibly();
			}
		}

		private void close() {
			synchronized (this.lock) {
				this.closed = true;
				this.speakers.clear();
				stopProcessLocked();
				this.frameBuffer.clear();
			}
		}
	}
}
