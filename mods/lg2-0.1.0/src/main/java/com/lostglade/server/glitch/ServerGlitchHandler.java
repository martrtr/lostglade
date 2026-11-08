package com.lostglade.server.glitch;

import com.lostglade.config.GlitchConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RandomSource;

public interface ServerGlitchHandler {
	String id();

	GlitchConfig.GlitchEntry defaultEntry();

	boolean sanitizeSettings(GlitchConfig.GlitchEntry entry);

	default boolean isPeriodic() {
		return true;
	}

	boolean trigger(MinecraftServer server, RandomSource random, GlitchConfig.GlitchEntry entry, double stabilityPercent);
}
