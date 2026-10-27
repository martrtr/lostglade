package com.lostglade.server;

/**
 * Determines whether a shadow-world still frame is usable.  A photo must wait
 * for the current shadow content to be rendered and for every section in the
 * current camera view to be compiled.  Work queued for sections behind walls
 * or outside the frame must not delay a small enclosed photo.
 */
public final class CameraCaptureReadinessPolicy {
	private CameraCaptureReadinessPolicy() {
	}

	/** Alternate contending render classes so both GPU upload queues advance. */
	public static boolean choosePhoto(boolean photoDue, boolean videoDue, boolean previousWasPhoto) {
		return photoDue && (!videoDue || !previousWasPhoto);
	}

	/** Scheduling a build clears dirty, but does not install the resulting mesh. */
	public static boolean sectionPending(boolean dirty, boolean uncompiled) {
		return dirty || uncompiled;
	}

	public static boolean isUsable(
			boolean contentReady,
			boolean currentContentRendered,
			int visibleSections,
			int dirtyVisibleSections
	) {
		return contentReady && currentContentRendered && visibleSections > 0 && dirtyVisibleSections == 0;
	}
}
