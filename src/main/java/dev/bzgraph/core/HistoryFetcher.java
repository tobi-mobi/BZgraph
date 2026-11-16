package dev.bzgraph.core;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Turns a {@link TimeRange} into one or more API calls and returns ascending, deduped points. */
public final class HistoryFetcher {
	private HistoryFetcher() {
	}

	public static CompletableFuture<List<PricePoint>> fetch(HistorySource source, String itemId, TimeRange range,
															Instant now, HistorySource.Listener listener) {
		if (range != TimeRange.ALL) {
			return source.history(itemId, range.start(now), now, listener).thenApply(HistoryMerger::sortAscending);
		}
		return fetchAll(source, itemId, now, listener);
	}

	/**
	 * ALL range: the first request (1970..now) returns up to 2000 daily points (back to ~2021); a second
	 * request ending at the oldest returned timestamp fetches the remainder. If the second request fails,
	 * the first part is still returned.
	 */
	static CompletableFuture<List<PricePoint>> fetchAll(HistorySource source, String itemId, Instant now,
														HistorySource.Listener listener) {
		return source.history(itemId, TimeRange.EPOCH_START, now, listener).thenCompose(first -> {
			Instant oldest = HistoryMerger.oldest(first);
			if (oldest == null || !oldest.isAfter(TimeRange.EPOCH_START)) {
				return CompletableFuture.completedFuture(HistoryMerger.merge(first));
			}
			return source.history(itemId, TimeRange.EPOCH_START, oldest, listener)
					.handle((second, err) -> err != null ? HistoryMerger.merge(first) : HistoryMerger.merge(first, second));
		});
	}
}
