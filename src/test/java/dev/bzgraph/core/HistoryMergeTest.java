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

		// 1970..now, then 1970..oldest-of-first, plus the fine 12h tail
		assertEquals(3, src.calls.size());
		Instant firstOldest = src.newestAvailable.minusSeconds(86400L * 1999);
		assertTrue(hasCall(src.calls, TimeRange.EPOCH_START, now));
		assertTrue(hasCall(src.calls, TimeRange.EPOCH_START, firstOldest), "second request ends at the first's oldest point");
		assertTrue(hasCall(src.calls, now.minus(HistoryFetcher.FINE_WINDOW), now), "fine tail");

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
		Instant now = Instant.now();
		assertTrue(HistoryFetcher.fetch(empty, "X", TimeRange.ALL, now, HistorySource.Listener.NONE).join().isEmpty());
		assertEquals(2, calls.size(), "first ALL request + tail, no second ALL request");
		assertTrue(calls.stream().allMatch(now::equals));
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

	private static boolean hasCall(List<Instant[]> calls, Instant start, Instant end) {
		return calls.stream().anyMatch(c -> c[0].equals(start) && c[1].equals(end));
	}

	/**
	 * Mimics SkyCofl: windows longer than 12h come back as 2-hour buckets that stop at the last full bucket;
	 * shorter windows come back at 5-minute resolution up to 10 minutes ago.
	 */
	private static final class ResolutionSource implements HistorySource {
		final List<Instant[]> calls = new ArrayList<>();
		final Instant now;

		ResolutionSource(Instant now) {
			this.now = now;
		}

		@Override
		public CompletableFuture<List<PricePoint>> history(String itemId, Instant start, Instant end, Listener l) {
			calls.add(new Instant[]{start, end});
			boolean fine = !start.plus(HistoryFetcher.FINE_WINDOW).isBefore(end);
			long step = fine ? 300 : 7200;
			Instant newest = fine ? now.minusSeconds(600) : now.minusSeconds(4 * 3600); // coarse lags hours
			List<PricePoint> out = new ArrayList<>();
			for (Instant t = Instant.ofEpochSecond(newest.getEpochSecond() / step * step); !t.isBefore(start); t = t.minusSeconds(step)) {
				if (!t.isAfter(end)) out.add(new PricePoint(t, fine ? 1 : 2, 1, 0, 0)); // buy 1 = fine, 2 = coarse
			}
			return CompletableFuture.completedFuture(out);
		}
	}

	@Test
	void dayRangeIsTwoFineHalvesReachingRecentPrices() {
		Instant now = Instant.parse("2026-11-16T16:46:00Z");
		ResolutionSource src = new ResolutionSource(now);
		List<PricePoint> r = HistoryFetcher.fetch(src, "X", TimeRange.DAY, now, HistorySource.Listener.NONE).join();

		assertEquals(2, src.calls.size());
		Instant mid = now.minus(HistoryFetcher.FINE_WINDOW);
		assertTrue(hasCall(src.calls, now.minusSeconds(86400), mid));
		assertTrue(hasCall(src.calls, mid, now));
		assertTrue(r.stream().allMatch(p -> p.buy() == 1), "only fine-resolution points");
		assertTrue(r.getLast().timestamp().isAfter(now.minusSeconds(15 * 60)), "reaches ~10 min ago, was " + r.getLast().timestamp());
		assertTrue(r.size() > 250, "full day at 5-minute steps, got " + r.size());
		for (int i = 1; i < r.size(); i++) assertTrue(r.get(i - 1).timestamp().isBefore(r.get(i).timestamp()));
	}

	@Test
	void longerRangesGetAFineTailThatReplacesOverlappingCoarseBuckets() {
		Instant now = Instant.parse("2026-11-16T16:46:00Z");
		ResolutionSource src = new ResolutionSource(now);
		List<PricePoint> r = HistoryFetcher.fetch(src, "X", TimeRange.WEEK, now, HistorySource.Listener.NONE).join();

		assertEquals(2, src.calls.size());
		assertTrue(hasCall(src.calls, now.minus(java.time.Duration.ofDays(7)), now));
		assertTrue(hasCall(src.calls, now.minus(HistoryFetcher.FINE_WINDOW), now));
		Instant tailStart = r.stream().filter(p -> p.buy() == 1).findFirst().orElseThrow().timestamp();
		assertTrue(r.stream().noneMatch(p -> p.buy() == 2 && !p.timestamp().isBefore(tailStart)),
				"no coarse bucket inside the fine tail's time span");
		assertTrue(r.stream().anyMatch(p -> p.buy() == 2), "older part still coarse");
		assertTrue(r.getLast().timestamp().isAfter(now.minusSeconds(15 * 60)));
	}

	@Test
	void tailFailureKeepsCoarseData() {
		Instant now = Instant.parse("2026-11-16T16:46:00Z");
		HistorySource src = (id, s, e, l) -> s.plus(HistoryFetcher.FINE_WINDOW).isBefore(e)
				? CompletableFuture.completedFuture(List.of(pt("2026-11-16T12:00:00Z", 2), pt("2026-11-16T10:00:00Z", 1)))
				: CompletableFuture.failedFuture(new ApiException(ApiException.Kind.OFFLINE, "x"));
		List<PricePoint> r = HistoryFetcher.fetch(src, "X", TimeRange.MONTH, now, HistorySource.Listener.NONE).join();
		assertEquals(2, r.size());
		assertEquals(1, r.getFirst().buy());
	}

	@Test
	void dayRangeSurvivesOlderHalfFailing() {
		Instant now = Instant.parse("2026-11-16T16:46:00Z");
		Instant mid = now.minus(HistoryFetcher.FINE_WINDOW);
		HistorySource src = (id, s, e, l) -> s.equals(mid)
				? CompletableFuture.completedFuture(List.of(pt("2026-11-16T16:30:00Z", 5)))
				: CompletableFuture.failedFuture(new ApiException(ApiException.Kind.OFFLINE, "x"));
		assertEquals(1, HistoryFetcher.fetch(src, "X", TimeRange.DAY, now, HistorySource.Listener.NONE).join().size());
	}

	@Test
	void withTailHandlesEmptyTail() {
		List<PricePoint> main = List.of(pt("2026-11-16T12:00:00Z", 2), pt("2026-11-16T10:00:00Z", 1));
		assertEquals(2, HistoryMerger.withTail(main, List.of()).size());
	}
}
