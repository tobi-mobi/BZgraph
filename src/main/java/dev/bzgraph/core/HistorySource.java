package dev.bzgraph.core;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Something that can fetch one raw history window (newest first, as returned by the API). */
@FunctionalInterface
public interface HistorySource {
	/** Progress callbacks; may be invoked from any thread. */
	interface Listener {
		Listener NONE = seconds -> {};

		/** The request was rate limited and will be retried after {@code retryAfterSeconds}. */
		void onRateLimited(long retryAfterSeconds);
	}

	CompletableFuture<List<PricePoint>> history(String itemId, Instant start, Instant end, Listener listener);
}
