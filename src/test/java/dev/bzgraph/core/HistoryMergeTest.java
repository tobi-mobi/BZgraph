package dev.bzgraph.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class HistoryMergeTest {
	private static PricePoint pt(String ts, double buy) {
		return new PricePoint(Instant.parse(ts), buy, buy - 1, 0, 0);
	}

	@Test
	void mergeDedupesAndSortsAscending() {
		// Both responses newest first, overlapping at 2021-03-23.
		List<PricePoint> recent = List.of(pt("2021-03-25T00:00:00Z", 30), pt("2021-03-24T00:00:00Z", 20), pt("2021-03-23T00:00:00Z", 10));
		List<PricePoint> older = List.of(pt("2021-03-23T00:00:00Z", 999), pt("2020-03-13T00:00:00Z", 2), pt("2020-03-12T00:00:00Z", 1));
		List<PricePoint> merged = HistoryMerger.merge(recent, older);

		assertEquals(5, merged.size());
		for (int i = 1; i < merged.size(); i++) {
			assertTrue(merged.get(i - 1).timestamp().isBefore(merged.get(i).timestamp()));
		}
		assertEquals(Instant.parse("2020-03-12T00:00:00Z"), merged.getFirst().timestamp());
		assertEquals(Instant.parse("2021-03-25T00:00:00Z"), merged.getLast().timestamp());
		// first occurrence (from the newer response) wins on duplicates
		assertEquals(10, merged.get(2).buy());
	}

	@Test
	void mergeHandlesEmptyAndNull() {
		assertTrue(HistoryMerger.merge(List.of(), List.of()).isEmpty());
		assertEquals(1, HistoryMerger.merge(List.of(pt("2021-01-01T00:00:00Z", 1)), null).size());
	}

	@Test
	void oldest() {
		assertNull(HistoryMerger.oldest(List.of()));
		assertEquals(Instant.parse("2020-01-01T00:00:00Z"),
				HistoryMerger.oldest(List.of(pt("2021-01-01T00:00:00Z", 1), pt("2020-01-01T00:00:00Z", 1), pt("2022-01-01T00:00:00Z", 1))));
	}

	/** Fake API: newest-first daily data between 2020-03-12 and 2026-11-15, capped at 2000 points per call. */
	private static final class FakeSource implements HistorySource {
		final List<Instant[]> calls = new ArrayList<>();
		final Instant oldestAvailable = Instant.parse("2020-03-12T00:00:00Z");
		final Instant newestAvailable = Instant.parse("2026-11-15T00:00:00Z");

		@Override
		public CompletableFuture<List<PricePoint>> history(String itemId, Instant start, Instant end, Listener l) {
			calls.add(new Instant[]{start, end});
			List<PricePoint> out = new ArrayList<>();
			// include the end day itself (API end is inclusive-ish), to exercise dedupe
			for (Instant t = newestAvailable; !t.isBefore(oldestAvailable) && out.size() < 2000; t = t.minusSeconds(86400)) {
				if (t.isAfter(end) || t.isBefore(start)) continue;
				out.add(new PricePoint(t, 1, 1, 1, 1));
			}
			return CompletableFuture.completedFuture(out);
		}
	}

	@Test
	void allRangeMakesTwoRequestsAndCoversEverything() {
		FakeSource src = new FakeSource();
		Instant now = Instant.parse("2026-11-16T00:00:00Z");
		List<PricePoint> all = HistoryFetcher.fetch(src, "X", TimeRange.ALL, now, HistorySource.Listener.NONE).join();

		assertEquals(2, src.calls.size());
		assertEquals(TimeRange.EPOCH_START, src.calls.get(0)[0]);
		assertEquals(now, src.calls.get(0)[1]);
		// second request ends at the oldest timestamp of the first one
		Instant firstOldest = src.newestAvailable.minusSeconds(86400L * 1999);
		assertEquals(firstOldest, src.calls.get(1)[1]);

		assertEquals(src.oldestAvailable, all.getFirst().timestamp());
		assertEquals(src.newestAvailable, all.getLast().timestamp());
		long expectedDays = (src.newestAvailable.getEpochSecond() - src.oldestAvailable.getEpochSecond()) / 86400 + 1;
		assertEquals(expectedDays, all.size()); // no duplicates, no gaps
	}

	@Test
	void allRangeSkipsSecondRequestWhenFirstIsEmpty() {
		List<Instant> calls = new ArrayList<>();
		HistorySource empty = (id, s, e, l) -> {
			calls.add(e);
			return CompletableFuture.completedFuture(List.of());
		};
		assertTrue(HistoryFetcher.fetch(empty, "X", TimeRange.ALL, Instant.now(), HistorySource.Listener.NONE).join().isEmpty());
		assertEquals(1, calls.size());
	}

	@Test
	void allRangeKeepsFirstPartIfSecondFails() {
		int[] n = {0};
		HistorySource flaky = (id, s, e, l) -> n[0]++ == 0
				? CompletableFuture.completedFuture(List.of(pt("2021-03-24T00:00:00Z", 2), pt("2021-03-23T00:00:00Z", 1)))
				: CompletableFuture.failedFuture(new ApiException(ApiException.Kind.OFFLINE, "x"));
		List<PricePoint> r = HistoryFetcher.fetch(flaky, "X", TimeRange.ALL, Instant.now(), HistorySource.Listener.NONE).join();
		assertEquals(2, r.size());
		assertEquals(1, r.getFirst().buy());
	}

	@Test
	void nonAllRangeUsesSingleRequestAndSorts() {
		List<Instant[]> calls = new ArrayList<>();
		HistorySource src = (id, s, e, l) -> {
			calls.add(new Instant[]{s, e});
			return CompletableFuture.completedFuture(List.of(pt("2026-11-16T00:00:00Z", 2), pt("2026-11-15T12:00:00Z", 1)));
		};
		Instant now = Instant.parse("2026-11-16T00:00:00Z");
		List<PricePoint> r = HistoryFetcher.fetch(src, "X", TimeRange.DAY, now, HistorySource.Listener.NONE).join();
		assertEquals(1, calls.size());
		assertEquals(Instant.parse("2026-11-15T00:00:00Z"), calls.getFirst()[0]);
		assertEquals(1, r.getFirst().buy());
	}
}
