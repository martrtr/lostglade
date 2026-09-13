package com.lostglade.client;

import com.lostglade.Lg2;
import com.lostglade.mixin.client.SoundEngineBufferAccessor;
import com.lostglade.network.RendererBotPayloads;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.FiniteAudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;

import javax.sound.sampled.AudioFormat;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

public final class RendererBotClientAudioCapture {
	private static final int SAMPLE_RATE = 48_000;
	private static final int FRAME_SAMPLES = 960;
	private static final long FRAME_NANOS = TimeUnit.SECONDS.toNanos(1L) * FRAME_SAMPLES / SAMPLE_RATE;
	private static final int MAX_ACTIVE_SOUNDS_PER_SESSION = 128;
	private static final float MIX_GAIN = floatProperty("lg2.rendererBotAudioVanillaGain", 0.55F);
	private static final float WEATHER_GAIN = floatProperty("lg2.rendererBotAudioWeatherGain", 0.5F);
	private static final Map<UUID, AudioSession> SESSIONS = new ConcurrentHashMap<>();
	private static final Map<Identifier, CompletableFuture<DecodedSound>> DECODE_CACHE = new ConcurrentHashMap<>();

	private RendererBotClientAudioCapture() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(RendererBotClientAudioCapture::onClientTick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
		ClientPlayNetworking.registerGlobalReceiver(
				RendererBotPayloads.RendererBotAudioCaptureStartS2CPayload.TYPE,
				(payload, context) -> context.client().execute(() -> startSession(payload))
		);
		ClientPlayNetworking.registerGlobalReceiver(
				RendererBotPayloads.RendererBotAudioCaptureStopS2CPayload.TYPE,
				(payload, context) -> context.client().execute(() -> stopSession(payload.audioId()))
		);
	}

	public static void onSoundStarted(SoundEngine engine, SoundInstance instance) {
		if (engine == null || instance == null || SESSIONS.isEmpty()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		ClientLevel level = RendererBotSceneContext.level();
		if (client == null || level == null) {
			return;
		}
		UUID renderSessionId = RendererBotShadowWorldManager.sessionIdForLevel(level);
		if (renderSessionId == null) {
			return;
		}
		Sound sound = instance.getSound();
		if (sound == null || sound == SoundManager.EMPTY_SOUND || sound == SoundManager.INTENTIONALLY_EMPTY_SOUND) {
			return;
		}
		SoundBufferLibrary soundBuffers = ((SoundEngineBufferAccessor) engine).lg2$getSoundBuffers();
		for (AudioSession session : SESSIONS.values()) {
			if (session != null && session.renderSessionId().equals(renderSessionId)) {
				session.captureSound(instance, sound, soundBuffers);
			}
		}
	}

	public static void onSoundStopped(SoundInstance instance) {
		if (instance == null || SESSIONS.isEmpty()) {
			return;
		}
		for (AudioSession session : SESSIONS.values()) {
			if (session != null) {
				session.stopSound(instance);
			}
		}
	}

	public static void onAllSoundsStopped() {
		for (AudioSession session : SESSIONS.values()) {
			if (session != null) {
				session.stopAllSounds();
			}
		}
	}

	public static void onSoundStopById(Identifier identifier, SoundSource source) {
		for (AudioSession session : SESSIONS.values()) {
			if (session != null) {
				session.stopSoundsById(identifier, source);
			}
		}
	}

	private static void startSession(RendererBotPayloads.RendererBotAudioCaptureStartS2CPayload payload) {
		if (payload == null || payload.audioId() == null || payload.renderSessionId() == null) {
			return;
		}
		if (payload.sampleRate() != SAMPLE_RATE || payload.frameSamples() != FRAME_SAMPLES) {
			sendFailure(payload.audioId(), "Unsupported renderer bot audio format");
			return;
		}
		AudioSession created = new AudioSession(payload);
		AudioSession previous = SESSIONS.put(payload.audioId(), created);
		if (previous != null) {
			previous.close();
		}
		created.start();
	}

	private static void stopSession(UUID audioId) {
		if (audioId == null) {
			return;
		}
		AudioSession removed = SESSIONS.remove(audioId);
		if (removed != null) {
			removed.close();
		}
	}

	private static void clear() {
		for (AudioSession session : SESSIONS.values()) {
			if (session != null) {
				session.close();
			}
		}
		SESSIONS.clear();
	}

	private static void onClientTick(Minecraft client) {
		if (client == null || SESSIONS.isEmpty()) {
			return;
		}
		for (AudioSession session : SESSIONS.values()) {
			if (session != null) {
				session.tickOnClientThread();
			}
		}
	}

	private static CompletableFuture<DecodedSound> decodeSound(SoundBufferLibrary soundBuffers, Identifier path) {
		if (soundBuffers == null || path == null) {
			return CompletableFuture.completedFuture(DecodedSound.EMPTY);
		}
		return DECODE_CACHE.computeIfAbsent(path, ignored -> soundBuffers.getStream(path, false)
				.thenApply(RendererBotClientAudioCapture::decodeStream)
				.exceptionally(throwable -> {
					Lg2.LOGGER.debug("Renderer bot failed to decode captured sound {}", path, throwable);
					return DecodedSound.EMPTY;
				}));
	}

	private static DecodedSound decodeStream(AudioStream stream) {
		if (stream == null) {
			return DecodedSound.EMPTY;
		}
		try (stream) {
			AudioFormat format = stream.getFormat();
			if (format == null || format.getSampleSizeInBits() != 16) {
				return DecodedSound.EMPTY;
			}
			ByteBuffer data = stream instanceof FiniteAudioStream finite
					? finite.readAll()
					: readStreamFully(stream);
			return decodePcmBuffer(format, data);
		} catch (IOException exception) {
			throw new CompletionException(exception);
		}
	}

	private static ByteBuffer readStreamFully(AudioStream stream) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		while (true) {
			ByteBuffer chunk = stream.read(16_384);
			if (chunk == null || !chunk.hasRemaining()) {
				break;
			}
			byte[] bytes = new byte[chunk.remaining()];
			chunk.get(bytes);
			output.write(bytes);
		}
		return ByteBuffer.wrap(output.toByteArray());
	}

	private static DecodedSound decodePcmBuffer(AudioFormat format, ByteBuffer data) {
		if (data == null || !data.hasRemaining()) {
			return DecodedSound.EMPTY;
		}
		ByteBuffer buffer = data.slice().order(format.isBigEndian() ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
		int channels = Math.max(1, format.getChannels());
		int frameCount = buffer.remaining() / (Short.BYTES * channels);
		if (frameCount <= 0) {
			return DecodedSound.EMPTY;
		}
		float[] samples = new float[frameCount];
		for (int frame = 0; frame < frameCount; frame++) {
			float mixed = 0.0F;
			for (int channel = 0; channel < channels; channel++) {
				mixed += buffer.getShort() / 32768.0F;
			}
			samples[frame] = mixed / channels;
		}
		return new DecodedSound(Math.max(1.0F, format.getSampleRate()), samples);
	}

	private static byte[] encodePcmFrame(short[] samples) {
		byte[] bytes = new byte[FRAME_SAMPLES * Short.BYTES];
		if (samples == null) {
			return bytes;
		}
		int count = Math.min(FRAME_SAMPLES, samples.length);
		for (int index = 0; index < count; index++) {
			short sample = samples[index];
			int byteIndex = index * 2;
			bytes[byteIndex] = (byte) (sample & 0xFF);
			bytes[byteIndex + 1] = (byte) ((sample >>> 8) & 0xFF);
		}
		return bytes;
	}

	private static void sendFailure(UUID audioId, String message) {
		if (audioId == null) {
			return;
		}
		try {
			ClientPlayNetworking.send(new RendererBotPayloads.RendererBotAudioCaptureFailureC2SPayload(audioId, message == null ? "" : message));
		} catch (RuntimeException exception) {
			Lg2.LOGGER.debug("Renderer bot failed to send audio capture failure {}", audioId, exception);
		}
	}

	private static float floatProperty(String key, float fallback) {
		String raw = System.getProperty(key);
		if (raw == null || raw.isBlank()) {
			return fallback;
		}
		try {
			float value = Float.parseFloat(raw.trim());
			return Float.isFinite(value) ? Math.clamp(value, 0.0F, 4.0F) : fallback;
		} catch (NumberFormatException exception) {
			return fallback;
		}
	}

	private static float clientSoundSourceVolume(SoundSource source) {
		Minecraft client = Minecraft.getInstance();
		if (client == null || client.options == null) {
			return 1.0F;
		}
		try {
			return Math.clamp(client.options.getFinalSoundSourceVolume(source == null ? SoundSource.MASTER : source), 0.0F, 1.0F);
		} catch (RuntimeException exception) {
			return 1.0F;
		}
	}

	private static float sourceCaptureGain(SoundSource source) {
		return source == SoundSource.WEATHER ? WEATHER_GAIN : 1.0F;
	}

	private static final class AudioSession {
		private final UUID audioId;
		private final UUID renderSessionId;
		private final double x;
		private final double y;
		private final double z;
		private final double radiusBlocks;
		private final Map<SoundInstance, CapturedSound> sounds = new ConcurrentHashMap<>();
		private volatile boolean closed;
		private Thread thread;

		private AudioSession(RendererBotPayloads.RendererBotAudioCaptureStartS2CPayload payload) {
			this.audioId = payload.audioId();
			this.renderSessionId = payload.renderSessionId();
			this.x = payload.x();
			this.y = payload.y();
			this.z = payload.z();
			this.radiusBlocks = Math.max(1.0D, payload.radiusBlocks());
		}

		private UUID renderSessionId() {
			return this.renderSessionId;
		}

		private void start() {
			Thread created = new Thread(this::run, "lg2-renderer-bot-audio-capture");
			created.setDaemon(true);
			this.thread = created;
			created.start();
		}

		private void run() {
			long sessionStartNanos = System.nanoTime();
			long nextFrameAt = sessionStartNanos;
			while (!this.closed) {
				RendererBotShadowWorldManager.updateAudioContext(this.renderSessionId, this.x, this.y, this.z);
				long frameIndex = Math.max(0L, Math.floorDiv(nextFrameAt - sessionStartNanos, FRAME_NANOS));
				short[] frame = mixFrame(nextFrameAt);
				try {
					ClientPlayNetworking.send(new RendererBotPayloads.RendererBotAudioFrameC2SPayload(this.audioId, frameIndex, nextFrameAt, encodePcmFrame(frame)));
				} catch (RuntimeException exception) {
					sendFailure(this.audioId, "Renderer bot failed to send audio frame");
					close();
					return;
				}
				nextFrameAt += FRAME_NANOS;
				long sleepNanos = nextFrameAt - System.nanoTime();
				if (sleepNanos > 0L) {
					try {
						TimeUnit.NANOSECONDS.sleep(sleepNanos);
					} catch (InterruptedException exception) {
						if (this.closed) {
							Thread.currentThread().interrupt();
							return;
						}
					}
				} else if (sleepNanos < -FRAME_NANOS * 8L) {
					long now = System.nanoTime();
					long nextFrameIndex = Math.max(frameIndex + 1L, Math.floorDiv(now - sessionStartNanos, FRAME_NANOS));
					nextFrameAt = sessionStartNanos + nextFrameIndex * FRAME_NANOS;
				}
			}
		}

		private void tickOnClientThread() {
			RendererBotShadowWorldManager.updateAudioContext(this.renderSessionId, this.x, this.y, this.z);
			for (CapturedSound sound : new ArrayList<>(this.sounds.values())) {
				if (sound == null || !sound.refreshOnClientThread()) {
					this.sounds.remove(sound.instance(), sound);
				}
			}
		}

		private void captureSound(SoundInstance instance, Sound sound, SoundBufferLibrary soundBuffers) {
			if (this.closed || instance == null || sound == null || soundBuffers == null || this.sounds.size() >= MAX_ACTIVE_SOUNDS_PER_SESSION) {
				return;
			}
			this.sounds.computeIfAbsent(instance, ignored -> new CapturedSound(instance, sound, decodeSound(soundBuffers, sound.getPath())));
		}

		private void stopSound(SoundInstance instance) {
			if (instance != null) {
				this.sounds.remove(instance);
			}
		}

		private void stopAllSounds() {
			this.sounds.clear();
		}

		private void stopSoundsById(Identifier identifier, SoundSource source) {
			if (identifier == null && source == null) {
				stopAllSounds();
				return;
			}
			for (CapturedSound sound : new ArrayList<>(this.sounds.values())) {
				if (sound != null && sound.matchesStop(identifier, source)) {
					this.sounds.remove(sound.instance(), sound);
				}
			}
		}

		private short[] mixFrame(long frameStartNanos) {
			float[] mixed = new float[FRAME_SAMPLES];
			for (CapturedSound sound : new ArrayList<>(this.sounds.values())) {
				if (sound == null) {
					continue;
				}
				if (!sound.mixInto(mixed, frameStartNanos, this)) {
					this.sounds.remove(sound.instance(), sound);
				}
			}
			short[] output = new short[FRAME_SAMPLES];
			for (int index = 0; index < FRAME_SAMPLES; index++) {
				output[index] = softLimit(mixed[index] * MIX_GAIN);
			}
			return output;
		}

		private void close() {
			this.closed = true;
			this.sounds.clear();
			Thread current = this.thread;
			if (current != null) {
				current.interrupt();
			}
		}
	}

	private static final class CapturedSound {
		private final SoundInstance instance;
		private final Identifier identifier;
		private final SoundSource source;
		private final CompletableFuture<DecodedSound> decoded;
		private final long startedAtNanos = System.nanoTime();
		private volatile boolean stopped;
		private volatile boolean relative;
		private volatile boolean looping;
		private volatile double x;
		private volatile double y;
		private volatile double z;
		private volatile float volume;
		private volatile float clientGain;
		private volatile float pitch;
		private volatile float rangeBlocks;
		private volatile SoundInstance.Attenuation attenuation;

		private CapturedSound(SoundInstance instance, Sound sound, CompletableFuture<DecodedSound> decoded) {
			this.instance = instance;
			this.identifier = instance.getIdentifier();
			this.source = instance.getSource();
			this.decoded = decoded;
			refreshFromInstance(sound);
		}

		private SoundInstance instance() {
			return this.instance;
		}

		private boolean refreshOnClientThread() {
			if (this.instance instanceof TickableSoundInstance tickable && tickable.isStopped()) {
				this.stopped = true;
				return false;
			}
			if (!this.instance.canPlaySound()) {
				this.stopped = true;
				return false;
			}
			Sound sound = this.instance.getSound();
			if (sound == null || sound == SoundManager.EMPTY_SOUND || sound == SoundManager.INTENTIONALLY_EMPTY_SOUND) {
				this.stopped = true;
				return false;
			}
			refreshFromInstance(sound);
			return true;
		}

		private void refreshFromInstance(Sound sound) {
			float rawVolume = Math.max(0.0F, this.instance.getVolume());
			this.volume = Math.clamp(rawVolume, 0.0F, 1.0F);
			this.clientGain = clientSoundSourceVolume(this.source) * sourceCaptureGain(this.source);
			this.pitch = Math.clamp(this.instance.getPitch(), 0.5F, 2.0F);
			this.x = this.instance.getX();
			this.y = this.instance.getY();
			this.z = this.instance.getZ();
			this.relative = this.instance.isRelative();
			this.looping = this.instance.isLooping() && this.instance.getDelay() <= 0;
			this.attenuation = this.instance.getAttenuation();
			this.rangeBlocks = Math.max(1.0F, rawVolume) * Math.max(1, sound.getAttenuationDistance());
		}

		private boolean matchesStop(Identifier identifier, SoundSource source) {
			return (identifier == null || Objects.equals(identifier, this.identifier))
					&& (source == null || source == this.source);
		}

		private boolean mixInto(float[] output, long frameStartNanos, AudioSession session) {
			if (this.stopped || output == null || session == null || this.relative) {
				return false;
			}
			DecodedSound sound = this.decoded.getNow(null);
			if (sound == null) {
				return true;
			}
			if (sound == DecodedSound.EMPTY || sound.samples().length == 0) {
				return false;
			}
			float gain = gainFor(session);
			double elapsedSeconds = (frameStartNanos - this.startedAtNanos) / 1_000_000_000.0D;
			double sourceBase = elapsedSeconds * sound.sampleRate() * this.pitch;
			double sourceStep = sound.sampleRate() * this.pitch / SAMPLE_RATE;
			boolean finished = !this.looping && sourceBase >= sound.samples().length;
			if (finished) {
				return false;
			}
			if (gain <= 0.0001F && !this.looping && sourceBase + sourceStep * FRAME_SAMPLES >= sound.samples().length) {
				return false;
			}
			if (gain <= 0.0001F) {
				return true;
			}
			float[] samples = sound.samples();
			int outputStart = 0;
			if (sourceBase < 0.0D) {
				outputStart = Math.min(output.length, (int) Math.ceil(-sourceBase / sourceStep));
			}
			for (int index = outputStart; index < output.length; index++) {
				double sourcePosition = sourceBase + sourceStep * index;
				if (!this.looping && sourcePosition >= samples.length) {
					break;
				}
				int sampleIndex = (int) Math.floor(sourcePosition);
				if (this.looping) {
					sampleIndex = Math.floorMod(sampleIndex, samples.length);
				}
				int nextIndex = sampleIndex + 1;
				if (this.looping) {
					nextIndex %= samples.length;
				} else if (nextIndex >= samples.length) {
					nextIndex = sampleIndex;
				}
				float fraction = (float) (sourcePosition - Math.floor(sourcePosition));
				float sample = samples[sampleIndex] + (samples[nextIndex] - samples[sampleIndex]) * fraction;
				output[index] += sample * gain;
			}
			return true;
		}

		private float gainFor(AudioSession session) {
			double dx = this.x - session.x;
			double dy = this.y - session.y;
			double dz = this.z - session.z;
			double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
			if (distance > session.radiusBlocks) {
				return 0.0F;
			}
			if (this.attenuation != SoundInstance.Attenuation.LINEAR) {
				return this.volume * this.clientGain;
			}
			double maxDistance = Math.max(1.0D, Math.min(session.radiusBlocks, this.rangeBlocks));
			if (distance >= maxDistance) {
				return 0.0F;
			}
			return this.volume * this.clientGain * (float) Math.clamp(1.0D - distance / maxDistance, 0.0D, 1.0D);
		}
	}

	private record DecodedSound(float sampleRate, float[] samples) {
		private static final DecodedSound EMPTY = new DecodedSound(SAMPLE_RATE, new float[0]);
	}

	private static short softLimit(float sample) {
		float limited = (float) Math.tanh(sample);
		return (short) Math.clamp(Math.round(limited * 32767.0F), Short.MIN_VALUE, Short.MAX_VALUE);
	}
}
