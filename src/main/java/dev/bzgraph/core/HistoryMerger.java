package dev.bzgraph.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeMap;

public final class HistoryMerger {
	private HistoryMerger() {
	}

	/**
	 * Merges several responses, dedupes by timestamp (first occurrence wins) and sorts ascending.
	 */
	@SafeVarargs
	public static List<PricePoint> merge(Collection<PricePoint>... parts) {
		TreeMap<Instant, PricePoint> byTime = new TreeMap<>();
		for (Collection<PricePoint> part : parts) {
			if (part == null) continue;
			for (PricePoint p : part) byTime.putIfAbsent(p.timestamp(), p);
		}
		return new ArrayList<>(byTime.values());
	}

	/** Sorts a single response ascending (and dedupes). */
	public static List<PricePoint> sortAscending(Collection<PricePoint> points) {
		return merge(points);
	}

	/**
	 * Appends a fine-resolution tail to coarse data: coarse points at or after the tail's first timestamp
	 * are dropped (they cover the same time with averaged buckets), then the tail is merged in.
	 */
	public static List<PricePoint> withTail(Collection<PricePoint> main, Collection<PricePoint> tail) {
		Instant tailStart = oldest(tail);
		if (tailStart == null) return merge(main);
		List<PricePoint> kept = new ArrayList<>(main.size());
		for (PricePoint p : main) if (p.timestamp().isBefore(tailStart)) kept.add(p);
		return merge(tail, kept);
	}

	/** Oldest timestamp in an unsorted list, or null if empty. */
	public static Instant oldest(Collection<PricePoint> points) {
		Instant min = null;
		for (PricePoint p : points) {
			if (min == null || p.timestamp().isBefore(min)) min = p.timestamp();
		}
		return min;
	}
}
