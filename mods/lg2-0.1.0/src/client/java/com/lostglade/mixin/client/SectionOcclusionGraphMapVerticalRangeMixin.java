package com.lostglade.mixin.client;

import com.lostglade.client.maprender.YandexMapRenderWorld;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.ViewArea;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Vanilla SectionOcclusionGraph normally uses the horizontal view distance as
 * a vertical traversal limit too. A gameplay camera is inside the world, so
 * that is fine; a top-down map camera starts above it and needs to traverse the
 * full build height. Only the vertical check in getRelativeFrom is widened.
 * Horizontal tracking/view-area size remains completely vanilla.
 */
@Mixin(SectionOcclusionGraph.class)
public abstract class SectionOcclusionGraphMapVerticalRangeMixin {
    @Shadow
    private ViewArea viewArea;

    @Redirect(
            method = "getRelativeFrom",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/ViewArea;getViewDistance()I"
            )
    )
    private int lg2$mapVerticalTraversalDistance(ViewArea area) {
        if (this.viewArea != null && this.viewArea.getLevelHeightAccessor() instanceof YandexMapRenderWorld mapWorld) {
            // Enough for a camera one/two sections above max build height to
            // traverse through every vanilla world section down to minY.
            return Math.max(area.getViewDistance(), mapWorld.getSectionsCount() + 4);
        }
        return area.getViewDistance();
    }
}
