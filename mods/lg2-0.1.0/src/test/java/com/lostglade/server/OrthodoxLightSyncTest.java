package com.lostglade.server;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public final class OrthodoxLightSyncTest {
	public static void main(String[] args) throws Exception {
		movementBeyondReportedBounds();
		overlapAndDimensions();
		concurrentDrain();
		var mask = OrthodoxLightSync.sectionMask(Set.of(-6, -5, -4, 0, 19, 20, 21), -5, 26);
		require(mask.equals(java.util.BitSet.valueOf(new long[]{1L | 2L | 1L << 5 | 1L << 24 | 1L << 25})),
				"Packet section indices must include negative heights and both padding sections");
		System.out.println("Orthodox light sync tests passed");
	}

	private static void movementBeyondReportedBounds() {
		var queue = new OrthodoxLightSync.PendingUpdates<String>();
		// Cross the reported rectangle, chunk boundaries and the world origin in both directions.
		int[][] positions = {{-177, -433}, {-223, -547}, {-224, -548}, {-160, -416}, {-1024, -1024},
				{0, 0}, {-1, -1}, {15, 15}, {16, 16}, {2048, 2048}};
		for (int[] position : positions) {
			int x = position[0], z = position[1];
			int cx = Math.floorDiv(x, 16), cz = Math.floorDiv(z, 16);
			queue.retain("world", x, z);
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					queue.mark("world", cx + dx, 4, cz + dz);
					queue.mark("world", cx + dx, 4, cz + dz);
					queue.mark("world", cx + dx, 5, cz + dz);
				}
			}
			queue.mark("world", cx + 2, 4, cz);
			var updates = queue.drain();
			require(updates.size() == 9, "Updates must follow the source's current chunk, not a fixed area");
			for (var sections : updates.values()) require(sections.equals(Set.of(4, 5)), "Batch duplicate sections");
			// A cleanup notification already queued must survive releasing its old source.
			queue.mark("world", cx, 4, cz);
			queue.release("world", x, z);
			require(queue.drain().size() == 1, "Do not drop the final light-removal packet");
			queue.mark("world", cx, 4, cz);
			require(queue.drain().isEmpty(), "Stop watching old areas after cleanup");
		}
	}

	private static void overlapAndDimensions() {
		var queue = new OrthodoxLightSync.PendingUpdates<String>();
		queue.retain("world", 0, 0);
		queue.retain("world", 16, 0);
		queue.retain("end", 0, 0);
		queue.release("world", 0, 0);
		queue.mark("world", 0, 5, 0);
		queue.mark("world", -1, 5, 0);
		queue.mark("end", -1, 5, 0);
		queue.mark("nether", 0, 5, 0);
		var updates = queue.drain();
		require(updates.size() == 2, "Overlapping players must retain lighting; dimensions must stay isolated");
		require(updates.containsKey(new OrthodoxLightSync.ChunkKey<>("world", 0, 0)), "Remaining owner's source");
		require(updates.containsKey(new OrthodoxLightSync.ChunkKey<>("end", -1, 0)), "Other dimension's source");
		queue.clear();
		queue.mark("end", -1, 5, 0);
		require(queue.drain().isEmpty(), "Shutdown clears all watches");
	}

	private static void concurrentDrain() throws Exception {
		var queue = new OrthodoxLightSync.PendingUpdates<String>();
		queue.retain("world", 0, 0);
		var failure = new AtomicReference<Throwable>();
		Thread lighting = new Thread(() -> {
			try {
				for (int i = 0; i < 10000; i++) queue.mark("world", 0, i, 0);
			} catch (Throwable error) {
				failure.set(error);
			}
		}, "test-lighting");
		Set<Integer> received = new HashSet<>();
		lighting.start();
		while (lighting.isAlive()) {
			queue.drain().values().forEach(received::addAll);
			Thread.yield();
		}
		lighting.join();
		queue.drain().values().forEach(received::addAll);
		require(failure.get() == null && received.size() == 10000, "Concurrent packet batching must not lose updates");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
