package dev.bzgraph.core;

import java.time.Instant;

public final class CachePolicy {
	private CachePolicy() {
	}

	/** True if data fetched at {@code fetchedAt} is still within the range's TTL at {@code now}. */
	public static boolean isFresh(Instant fetchedAt, TimeRange range, Instant now) {
		if (fetchedAt == null) return false;
		if (fetchedAt.isAfter(now)) return false; // clock went backwards / tampered: refetch
		return now.isBefore(fetchedAt.plus(range.ttl()));
	}
}
