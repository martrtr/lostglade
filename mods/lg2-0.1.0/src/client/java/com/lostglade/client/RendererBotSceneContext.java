package com.lostglade.client;

/** Scoped routing for vanilla helpers that otherwise reach into Minecraft's live world. */
public final class RendererBotSceneContext implements AutoCloseable {
	private static final ThreadLocal<RendererBotShadowLevel> ACTIVE = new ThreadLocal<>();
	private final RendererBotShadowLevel previous;

	private RendererBotSceneContext(RendererBotShadowLevel level) {
		previous = ACTIVE.get();
		ACTIVE.set(level);
	}

	public static RendererBotSceneContext enter(RendererBotShadowLevel level) {
		return new RendererBotSceneContext(level);
	}

	public static RendererBotShadowLevel level() { return ACTIVE.get(); }

	@Override
	public void close() {
		if (previous == null) ACTIVE.remove();
		else ACTIVE.set(previous);
	}
}
