package dev.bzgraph.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CacheTest {
	private static final Instant T0 = Instant.parse("2026-11-16T12:00:00Z");

	@Test
	void ttlPerRange() {
		assertEquals(Duration.ofMinutes(2), TimeRange.DAY.ttl());
		assertEquals(Duration.ofMinutes(10), TimeRange.WEEK.ttl());
		assertEquals(Duration.ofHours(1), TimeRange.MONTH.ttl());
		assertEquals(Duration.ofHours(6), TimeRange.YEAR.ttl());
		assertEquals(Duration.ofHours(6), TimeRange.ALL.ttl());
	}

	@Test
	void freshnessBoundaries() {
		for (TimeRange r : TimeRange.values()) {
			assertTrue(CachePolicy.isFresh(T0, r, T0), r + " fresh at fetch time");
			assertTrue(CachePolicy.isFresh(T0, r, T0.plus(r.ttl()).minusMillis(1)), r + " fresh just before TTL");
			assertFalse(CachePolicy.isFresh(T0, r, T0.plus(r.ttl())), r + " stale at TTL");
			assertFalse(CachePolicy.isFresh(T0, r, T0.plus(r.ttl()).plusSeconds(60)), r + " stale after TTL");
		}
		assertTrue(CachePolicy.isFresh(T0, TimeRange.DAY, T0.plusSeconds(119)));
		assertFalse(CachePolicy.isFresh(T0, TimeRange.DAY, T0.plusSeconds(121)));
		assertTrue(CachePolicy.isFresh(T0, TimeRange.ALL, T0.plus(Duration.ofHours(5))));
		assertFalse(CachePolicy.isFresh(null, TimeRange.DAY, T0));
		assertFalse(CachePolicy.isFresh(T0.plusSeconds(10), TimeRange.DAY, T0), "future fetch time treated as stale");
	}

	@Test
	void diskRoundTripIncludingNaNAndColonIds(@TempDir Path dir) throws Exception {
		DiskCache cache = new DiskCache(dir);
		CachedHistory h = new CachedHistory(T0, List.of(
				new PricePoint(Instant.parse("2026-11-16T11:00:00Z"), 1.5, Double.NaN, 10, 20),
				new PricePoint(Instant.parse("2026-11-16T11:05:00.250Z"), 2.5, 2.0, Double.NaN, 1, 2.0, 3.0, 1.5, 2.25)));
		cache.write("INK_SACK:3", TimeRange.WEEK, h);
		assertTrue(Files.exists(dir.resolve("INK_SACK~3_1W.json")));
		assertTrue(Files.exists(cache.fileFor("ENCHANTED_DIAMOND", TimeRange.DAY).getParent()));
		assertEquals("ENCHANTED_DIAMOND_1D.json", cache.fileFor("ENCHANTED_DIAMOND", TimeRange.DAY).getFileName().toString());

		CachedHistory back = cache.read("INK_SACK:3", TimeRange.WEEK);
		assertNotNull(back);
		assertEquals(T0, back.fetchedAt());
		assertEquals(2, back.points().size());
		assertTrue(Double.isNaN(back.points().get(0).sell()));
		assertEquals(h.points().get(1).timestamp(), back.points().get(1).timestamp());
		assertEquals(h.points().get(1), back.points().get(1), "min/max ranges survive the round trip");
		assertTrue(Double.isNaN(back.points().get(0).minSell()));
		assertNull(cache.read("INK_SACK:3", TimeRange.DAY));
	}

	@Test
	void corruptDiskFileIsIgnored(@TempDir Path dir) throws Exception {
		DiskCache cache = new DiskCache(dir);
		Files.writeString(cache.fileFor("X", TimeRange.DAY), "{not json");
		assertNull(cache.read("X", TimeRange.DAY));
	}

	@Test
	void lruEvictsLeastRecentlyUsed() {
		LruCache<String, Integer> lru = new LruCache<>(2);
		lru.put("a", 1);
		lru.put("b", 2);
		assertEquals(1, lru.get("a")); // a is now most recent
		lru.put("c", 3);
		assertNull(lru.get("b"));
		assertEquals(1, lru.get("a"));
		assertEquals(3, lru.get("c"));
		assertEquals(2, lru.size());
	}

	@Test
	void repositoryServesFreshCacheWithoutNetworkAndRefreshesStale(@TempDir Path dir) throws Exception {
		AtomicReference<Instant> now = new AtomicReference<>(T0);
		AtomicInteger calls = new AtomicInteger(); // counts fetch rounds (the recent 12h half of each 1D fetch)
		HistorySource src = (id, s, e, l) -> {
			if (e.equals(now.get()) && s.equals(e.minus(HistoryFetcher.FINE_WINDOW))) calls.incrementAndGet();
			return CompletableFuture.completedFuture(List.of(new PricePoint(e, calls.get(), 1, 0, 0))); // one point at window end
		};
		DiskCache disk = new DiskCache(dir);
		HistoryRepository repo = new HistoryRepository(src, disk, 8, Runnable::run, now::get, Throwable::printStackTrace);

		HistoryHandle h1 = repo.load("X", TimeRange.DAY);
		assertEquals(HistoryHandle.Status.READY, h1.status());
		assertEquals(1, calls.get());
		assertTrue(Files.exists(disk.fileFor("X", TimeRange.DAY)));

		// within TTL: served from memory, no request
		now.set(T0.plusSeconds(60));
		HistoryHandle h2 = repo.load("X", TimeRange.DAY);
		assertEquals(HistoryHandle.Status.READY, h2.status());
		assertEquals(1, calls.get());

		// new session (empty memory) within TTL: served from disk, no request
		HistoryRepository repo2 = new HistoryRepository(src, disk, 8, Runnable::run, now::get, Throwable::printStackTrace);
		assertEquals(HistoryHandle.Status.READY, repo2.load("X", TimeRange.DAY).status());
		assertEquals(1, calls.get());

		// past TTL: refresh
		now.set(T0.plus(Duration.ofMinutes(3)));
		HistoryHandle h3 = repo2.load("X", TimeRange.DAY);
		assertEquals(2, calls.get());
		assertEquals(HistoryHandle.Status.READY, h3.status());
		assertEquals(2, h3.data().points().getLast().buy());
	}

	@Test
	void staleDataShownWhileRefreshing(@TempDir Path dir) throws Exception {
		DiskCache disk = new DiskCache(dir);
		disk.write("X", TimeRange.DAY, new CachedHistory(T0.minus(Duration.ofHours(1)),
				List.of(new PricePoint(T0.minusSeconds(4000), 7, 7, 0, 0))));
		CompletableFuture<List<PricePoint>> pending = new CompletableFuture<>();
		HistoryRepository repo = new HistoryRepository((id, s, e, l) -> pending, disk, 8, Runnable::run, () -> T0, t -> {});

		HistoryHandle h = repo.load("X", TimeRange.DAY);
		assertEquals(HistoryHandle.Status.REFRESHING, h.status());
		assertEquals(7, h.data().points().getFirst().buy());

		pending.complete(List.of(new PricePoint(T0, 8, 8, 0, 0)));
		assertEquals(HistoryHandle.Status.READY, h.status());
		assertEquals(8, h.data().points().getFirst().buy());
	}

	@Test
	void liveSnapshotIsFetchedOnLoadAndCachedForAMinute() {
		AtomicReference<Instant> now = new AtomicReference<>(T0);
		AtomicInteger snaps = new AtomicInteger();
		LiveSource live = id -> {
			snaps.incrementAndGet();
			return CompletableFuture.completedFuture(new PricePoint(now.get(), 42, 41, 0, 0));
		};
		HistorySource src = (id, s, e, l) -> CompletableFuture.completedFuture(List.of(new PricePoint(T0.minusSeconds(600), 1, 1, 0, 0)));
		HistoryRepository repo = new HistoryRepository(src, live, null, 8, Runnable::run, now::get, t -> {});

		HistoryHandle h = repo.load("X", TimeRange.DAY);
		assertEquals(1, snaps.get());
		assertEquals(42, h.live().buy());

		now.set(T0.plusSeconds(30)); // within LIVE_TTL: reused, even for another range
		assertEquals(42, repo.load("X", TimeRange.WEEK).live().buy());
		assertEquals(1, snaps.get());

		repo.refresh(h); // R key: forced
		assertEquals(2, snaps.get());

		now.set(T0.plus(HistoryRepository.LIVE_TTL).plusSeconds(31));
		repo.load("X", TimeRange.DAY);
		assertEquals(3, snaps.get());
	}

	@Test
	void liveFailureIsHarmless() {
		HistorySource src = (id, s, e, l) -> CompletableFuture.completedFuture(List.of(new PricePoint(T0, 1, 1, 0, 0)));
		LiveSource broken = id -> CompletableFuture.failedFuture(new ApiException(ApiException.Kind.OFFLINE, "x"));
		HistoryHandle h = new HistoryRepository(src, broken, null, 8, Runnable::run, () -> T0, t -> {}).load("X", TimeRange.DAY);
		assertEquals(HistoryHandle.Status.READY, h.status());
		assertNull(h.live());
	}

	@Test
	void errorsMapToStatuses() {
		HistoryRepository off = new HistoryRepository((id, s, e, l) ->
				CompletableFuture.failedFuture(new ApiException(ApiException.Kind.OFFLINE, "x")), null, 8, Runnable::run, () -> T0, t -> {});
		assertEquals(HistoryHandle.Status.OFFLINE, off.load("X", TimeRange.DAY).status());

		HistoryRepository nf = new HistoryRepository((id, s, e, l) ->
				CompletableFuture.failedFuture(new ApiException(ApiException.Kind.NOT_FOUND, "x")), null, 8, Runnable::run, () -> T0, t -> {});
		assertEquals(HistoryHandle.Status.NOT_FOUND, nf.load("X", TimeRange.DAY).status());

		CompletableFuture<List<PricePoint>> pending = new CompletableFuture<>();
		HistoryRepository rl = new HistoryRepository((id, s, e, l) -> {
			l.onRateLimited(12);
			return pending;
		}, null, 8, Runnable::run, () -> T0, t -> {});
		HistoryHandle h = rl.load("X", TimeRange.DAY);
		assertEquals(HistoryHandle.Status.RATE_LIMITED, h.status());
		assertTrue(h.message().contains("12s"));
	}
}
