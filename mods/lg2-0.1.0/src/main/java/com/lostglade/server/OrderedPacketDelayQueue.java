package com.lostglade.server;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Server-thread FIFO lanes, with independent latency in each network direction. */
final class OrderedPacketDelayQueue<K> {
	private final int capacity;
	private final Map<Lane<K>, ArrayDeque<Entry<K>>> lanes = new HashMap<>();

	OrderedPacketDelayQueue(int capacity) {
		this.capacity = Math.max(1, capacity);
	}

	List<Entry<K>> enqueue(K owner, boolean inbound, long now, int delay, Runnable action) {
		Lane<K> lane = new Lane<>(owner, inbound);
		ArrayDeque<Entry<K>> queue = lanes.computeIfAbsent(lane, ignored -> new ArrayDeque<>());
		List<Entry<K>> overflow = new ArrayList<>();
		// Under pressure, deliver oldest packets early; never discard world actions.
		while (queue.size() >= capacity) overflow.add(queue.removeFirst());
		long due = Math.max(now + delay, queue.isEmpty() ? now : queue.getLast().due());
		queue.addLast(new Entry<>(owner, inbound, due, action));
		return overflow;
	}

	List<Entry<K>> drainDue(long now) {
		List<Entry<K>> ready = new ArrayList<>();
		lanes.values().removeIf(queue -> {
			while (!queue.isEmpty() && queue.getFirst().due() <= now) ready.add(queue.removeFirst());
			return queue.isEmpty();
		});
		return ready;
	}

	List<Entry<K>> drainOwner(K owner) {
		List<Entry<K>> ready = new ArrayList<>();
		lanes.entrySet().removeIf(entry -> {
			if (!entry.getKey().owner().equals(owner)) return false;
			ready.addAll(entry.getValue());
			return true;
		});
		return ready;
	}

	void clear() { lanes.clear(); }
	record Entry<K>(K owner, boolean inbound, long due, Runnable action) { }
	private record Lane<K>(K owner, boolean inbound) { }
}
