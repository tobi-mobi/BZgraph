package dev.bzgraph.core;

import java.time.Instant;
import java.util.List;

/** Price history plus the time it was fetched. Points are sorted ascending. */
public record CachedHistory(Instant fetchedAt, List<PricePoint> points) {
	public CachedHistory {
		points = List.copyOf(points);
	}

	public boolean isFresh(TimeRange range, Instant now) {
		return CachePolicy.isFresh(fetchedAt, range, now);
	}
}
