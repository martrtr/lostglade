package com.lostglade.mixin.client;

import net.minecraft.client.renderer.SectionOcclusionGraph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.Future;

/** Diagnostic/readiness view of vanilla's asynchronous visibility graph state. */
@Mixin(SectionOcclusionGraph.class)
public interface SectionOcclusionGraphAccessor {
    @Accessor("needsFrustumUpdate")
    java.util.concurrent.atomic.AtomicBoolean lg2$getNeedsFrustumUpdate();

    @Accessor("needsFullUpdate")
    boolean lg2$needsFullUpdate();

    @Accessor("fullUpdateTask")
    Future<?> lg2$getFullUpdateTask();
}
