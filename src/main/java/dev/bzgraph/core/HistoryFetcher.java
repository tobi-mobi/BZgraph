package dev.bzgraph.core;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Turns a {@link TimeRange} into API calls and returns ascending, deduped points.
 *
 * <p>SkyCofl picks the resolution from the window length: windows of up to ~12 hours come back at
 * ~3-minute resolution and reach ~10 minutes ago, while a 1-day window comes back as 2-hour buckets
 * whose newest bucket can be hours old (longer windows are even coarser). So:
 * <ul>
 *   <li>1D is fetched as two 12-hour windows (full day at fine resolution);</li>
 *   <li>every longer range also fetches the last 12 hours as a fine "tail" that replaces the coarse
 *       buckets it overlaps, so the line always runs up to recent prices.</li>
 * </ul>
 */
public final class HistoryFetcher {
	/** Longest window SkyCofl still returns at fine (~3 minute) resolution. */
	public static final Duration FINE_WINDOW = Duration.ofHours(12);

	private HistoryFetcher() {
	}

	public static CompletableFuture<List<PricePoint>> fetch(HistorySource source, String itemId, TimeRange range,
															Instant now, HistorySource.Listener listener) {
		Instant tailStart = now.minus(FINE_WINDOW);
		if (range == TimeRange.DAY) {
			CompletableFuture<List<PricePoint>> older = source.history(itemId, range.start(now), tailStart, listener);
			CompletableFuture<List<PricePoint>> recent = source.history(itemId, tailStart, now, listener);
			// The recent half is required; if only the older half fails, show what we have.
			return recent.thenCompose(r -> older.handle((o, err) -> err != null
					? HistoryMerger.merge(r) : HistoryMerger.merge(o, r)));
		}
		CompletableFuture<List<PricePoint>> main = range == TimeRange.ALL
				? fetchAll(source, itemId, now, listener)
				: source.history(itemId, range.start(now), now, listener).thenApply(HistoryMerger::sortAscending);
		CompletableFuture<List<PricePoint>> tail = source.history(itemId, tailStart, now, listener);
		// A failed tail just means the line ends where the coarse data ends.
		return main.thenCompose(m -> tail.handle((t, err) -> err != null ? m : HistoryMerger.withTail(m, t)));
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
