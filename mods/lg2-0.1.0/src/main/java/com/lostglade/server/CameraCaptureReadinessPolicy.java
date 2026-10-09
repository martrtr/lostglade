package com.lostglade.server;

/**
 * Determines whether a shadow-world still frame is usable.  A photo must wait
 * for the current shadow content to be rendered and for the section compiler
 * belonging to that shadow world to drain.  This is deliberately not a timer:
 * a small enclosed scene finishes as soon as its few sections are compiled,
 * while a distant scene stays alive until its visible terrain is actually on
 * the GPU.
 */
public final class CameraCaptureReadinessPolicy {
	private CameraCaptureReadinessPolicy() {
	}

	public static boolean isUsable(
			boolean contentReady,
			boolean currentContentRendered,
			int visibleSections,
			boolean allSectionsRendered
	) {
		return contentReady && currentContentRendered && visibleSections > 0 && allSectionsRendered;
	}
}
