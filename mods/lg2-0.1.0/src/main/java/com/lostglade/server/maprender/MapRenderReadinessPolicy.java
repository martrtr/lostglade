package com.lostglade.server.maprender;

/** Pure readiness barrier shared by the isolated client map renderer and tests. */
public final class MapRenderReadinessPolicy {
	private MapRenderReadinessPolicy() {
	}

	public static boolean isSettled(
			boolean allSectionsRendered,
			int compileQueueSize,
			int uploadQueueSize,
			boolean graphNeedsFullUpdate,
			boolean graphTaskPresent,
			boolean graphTaskDone,
			int loadedChunks,
			int lightReadyColumns
	) {
		if (!allSectionsRendered || compileQueueSize != 0 || uploadQueueSize != 0) return false;
		if (graphNeedsFullUpdate || (graphTaskPresent && !graphTaskDone)) return false;
		if (loadedChunks <= 0) return false;
		return lightReadyColumns >= loadedChunks;
	}
}
