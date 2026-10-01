package com.lostglade.network;

import com.lostglade.Lg2;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.concurrent.atomic.AtomicBoolean;

public final class Lg2Payloads {
	private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);
	private static final AtomicBoolean CLIENT_AUTH_REGISTERED = new AtomicBoolean(false);

	private Lg2Payloads() {
	}

	public static void registerPayloadTypes() {
		if (!REGISTERED.compareAndSet(false, true)) {
			return;
		}

		PayloadTypeRegistry.playS2C().register(MilkPocketVoidFadeS2CPayload.TYPE, MilkPocketVoidFadeS2CPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(RaceAbilityStateS2CPayload.TYPE, RaceAbilityStateS2CPayload.STREAM_CODEC);
		PayloadTypeRegistry.playC2S().register(RaceAbilityC2SPayload.TYPE, RaceAbilityC2SPayload.STREAM_CODEC);
		PayloadTypeRegistry.playC2S().register(RaceAbilityStateRequestC2SPayload.TYPE, RaceAbilityStateRequestC2SPayload.STREAM_CODEC);
		PayloadTypeRegistry.playC2S().register(AuthTokenC2SPayload.TYPE, AuthTokenC2SPayload.STREAM_CODEC);
	}

	/**
	 * The distributable client registers its race payloads from the dedicated
	 * race-client entrypoint.  Register only the new auth payload here, so the
	 * two wire-compatible race registrations are not duplicated client-side.
	 */
	public static void registerClientPayloadTypes() {
		if (CLIENT_AUTH_REGISTERED.compareAndSet(false, true)) {
			PayloadTypeRegistry.playC2S().register(AuthTokenC2SPayload.TYPE, AuthTokenC2SPayload.STREAM_CODEC);
		}
	}

	/** A request from the optional LG2 client UI to activate one of the four race actions. */
	public record RaceAbilityC2SPayload(int slot) implements CustomPacketPayload {
		public static final Type<RaceAbilityC2SPayload> TYPE = new Type<>(id("race_ability"));
		public static final StreamCodec<FriendlyByteBuf, RaceAbilityC2SPayload> STREAM_CODEC =
				CustomPacketPayload.codec(RaceAbilityC2SPayload::write, RaceAbilityC2SPayload::new);

		public RaceAbilityC2SPayload(FriendlyByteBuf buffer) {
			this(buffer.readVarInt());
		}

		private void write(FriendlyByteBuf buffer) {
			buffer.writeVarInt(this.slot);
		}

		@Override
		public Type<RaceAbilityC2SPayload> type() {
			return TYPE;
		}
	}

	/** Requests the current four-bit ability purchase state for the optional lightweight client menu. */
	public record RaceAbilityStateRequestC2SPayload() implements CustomPacketPayload {
		public static final Type<RaceAbilityStateRequestC2SPayload> TYPE = new Type<>(id("race_ability_state_request"));
		public static final StreamCodec<FriendlyByteBuf, RaceAbilityStateRequestC2SPayload> STREAM_CODEC =
				StreamCodec.unit(new RaceAbilityStateRequestC2SPayload());

		@Override
		public Type<RaceAbilityStateRequestC2SPayload> type() {
			return TYPE;
		}
	}

	/** Four-bit purchase state sent only to clients that explicitly support the optional race menu. */
	public record RaceAbilityStateS2CPayload(int unlockedMask) implements CustomPacketPayload {
		public static final Type<RaceAbilityStateS2CPayload> TYPE = new Type<>(id("race_ability_state"));
		public static final StreamCodec<FriendlyByteBuf, RaceAbilityStateS2CPayload> STREAM_CODEC =
				CustomPacketPayload.codec(RaceAbilityStateS2CPayload::write, RaceAbilityStateS2CPayload::new);

		public RaceAbilityStateS2CPayload(FriendlyByteBuf buffer) {
			this(buffer.readVarInt());
		}

		private void write(FriendlyByteBuf buffer) {
			buffer.writeVarInt(this.unlockedMask);
		}

		@Override
		public Type<RaceAbilityStateS2CPayload> type() {
			return TYPE;
		}
	}

	public record MilkPocketVoidFadeS2CPayload(float alpha) implements CustomPacketPayload {
		public static final Type<MilkPocketVoidFadeS2CPayload> TYPE = new Type<>(id("milk_pocket_void_fade"));
		public static final StreamCodec<FriendlyByteBuf, MilkPocketVoidFadeS2CPayload> STREAM_CODEC =
				CustomPacketPayload.codec(MilkPocketVoidFadeS2CPayload::write, MilkPocketVoidFadeS2CPayload::new);

		public MilkPocketVoidFadeS2CPayload(FriendlyByteBuf buffer) {
			this(buffer.readFloat());
		}

		private void write(FriendlyByteBuf buffer) {
			buffer.writeFloat(this.alpha);
		}

		@Override
		public Type<MilkPocketVoidFadeS2CPayload> type() {
			return TYPE;
		}
	}

	/**
	 * A per-installation secret held only by the optional LG2 client.  The
	 * server stores a SHA-256 digest, never this value itself.
	 */
	public record AuthTokenC2SPayload(String token) implements CustomPacketPayload {
		public static final Type<AuthTokenC2SPayload> TYPE = new Type<>(id("auth_token"));
		public static final StreamCodec<FriendlyByteBuf, AuthTokenC2SPayload> STREAM_CODEC =
				CustomPacketPayload.codec(AuthTokenC2SPayload::write, AuthTokenC2SPayload::new);

		public AuthTokenC2SPayload(FriendlyByteBuf buffer) {
			this(buffer.readUtf(128));
		}

		private void write(FriendlyByteBuf buffer) {
			buffer.writeUtf(this.token, 128);
		}

		@Override
		public Type<AuthTokenC2SPayload> type() {
			return TYPE;
		}
	}

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(Lg2.MOD_ID, path);
	}
}
