package com.lostglade.mixin;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Gives the login fallback the connection's Netty event loop. */
@Mixin(Connection.class)
public interface ConnectionChannelAccessor {
	@Accessor("channel")
	Channel lg2$getChannel();
}
