package dev.bzgraph.core;

import com.google.gson.JsonParseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static dev.bzgraph.core.PatchIndexRepository.UrlPolicy.*;
import static org.junit.jupiter.api.Assertions.*;

class PatchIndexTest {
	private static final String INDEX = """
			{
			  "version": 1,
			  "updated": "2026-11-16T00:00:00Z",
			  "patches": [
			    {"id": "t2", "date": "2026-10-27T14:35:04Z", "version": "0.27.1", "title": "  [October 27] Ghost Visitors ",
			     "summary": "Halloween visitors.", "url": "https://hypixel.net/threads/x.2/",
			     "autoItems": ["ECTOPLASM", "LOTUS"], "items": ["enchanted_diamond"], "excludeItems": ["LOTUS"]},
			    {"id": "t1", "date": "2026-10-13T16:00:26Z", "title": "[October 13] SkyBlock Patch Notes",
			     "url": "http://insecure.example/", "autoItems": ["GLOSSY_GEMSTONE"]},
			    {"id": "t1", "date": "2026-10-14T00:00:00Z", "title": "duplicate id"},
			    {"id": "t3", "date": "not a date", "title": "bad date"},
			    {"id": "t4", "date": "2026-10-01T00:00:00Z"},
			    "garbage"
			  ]
			}""";

	@Test
	void parsesAndAppliesItemOverrides() {
		PatchIndex idx = PatchIndex.parse(INDEX);
		assertEquals(Instant.parse("2026-11-16T00:00:00Z"), idx.updated());
		assertEquals(2, idx.patches().size(), "invalid / duplicate entries skipped");
		PatchNote first = idx.patches().get(0);
		assertEquals("t1", first.id(), "sorted ascending by date");
		assertNull(first.url(), "non-https links dropped");
		assertEquals("", first.summary());

		PatchNote ghost = idx.patches().get(1);
		assertEquals("[October 27] Ghost Visitors", ghost.title());
		assertEquals("0.27.1", ghost.version());
		assertEquals(Set.of("ECTOPLASM", "ENCHANTED_DIAMOND"), ghost.items());
		assertTrue(ghost.affects("ECTOPLASM"));
		assertFalse(ghost.affects("LOTUS"));
	}

	@Test
	void realIndexFileParses() throws Exception {
		Path file = Path.of("patch-index", "index.json");
		org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.exists(file), "no index in repo");
		PatchIndex idx = PatchIndex.parse(java.nio.file.Files.readString(file));
		assertFalse(idx.patches().isEmpty());
		for (PatchNote p : idx.patches()) {
			assertNotNull(p.url(), p.id() + " has a usable https link");
			assertTrue(p.date().isAfter(Instant.parse("2020-01-01T00:00:00Z")), p.id());
		}
	}

	@Test
	void rejectsUnusableDocuments() {
		assertThrows(JsonParseException.class, () -> PatchIndex.parse("[]"));
		assertThrows(JsonParseException.class, () -> PatchIndex.parse("{\"version\":2,\"patches\":[]}"));
		assertThrows(JsonParseException.class, () -> PatchIndex.parse("{\"version\":1}"));
		assertTrue(PatchIndex.parse("{\"version\":1,\"patches\":[]}").patches().isEmpty());
	}

	@Test
	void queries() {
		PatchIndex idx = PatchIndex.parse(INDEX);
		assertEquals(1, idx.between(Instant.parse("2026-10-20T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z")).size());
		assertEquals(List.of("t2"), idx.affecting("ECTOPLASM").stream().map(PatchNote::id).toList());
		assertEquals(List.of("t2", "t1"), idx.search("", null).stream().map(PatchNote::id).toList(), "newest first");
		assertEquals(List.of("t2"), idx.search("ghost", null).stream().map(PatchNote::id).toList());
		assertEquals(List.of("t2"), idx.search("halloween", null).stream().map(PatchNote::id).toList(), "searches summary");
		assertEquals(List.of("t1"), idx.search("glossy gem", null).stream().map(PatchNote::id).toList(), "searches item ids");
		assertEquals(List.of("t2"), idx.search("", "ENCHANTED_DIAMOND").stream().map(PatchNote::id).toList());
	}

	@Test
	void urlPolicy() {
		assertEquals(OK, PatchIndexRepository.checkUrl("https://raw.githubusercontent.com/a/b/main/patch-index/index.json"));
		assertEquals(OK, PatchIndexRepository.checkUrl("https://pi.example.com/index.json"));
		assertEquals(INSECURE_LAN, PatchIndexRepository.checkUrl("http://192.168.1.50/index.json"));
		assertEquals(INSECURE_LAN, PatchIndexRepository.checkUrl("http://raspberrypi.local:8080/index.json"));
		assertEquals(INSECURE_LAN, PatchIndexRepository.checkUrl("http://172.20.0.2/index.json"));
		assertEquals(INSECURE_LAN, PatchIndexRepository.checkUrl("http://localhost:8000/index.json"));
		assertEquals(REJECTED, PatchIndexRepository.checkUrl("http://172.32.0.2/index.json"));
		assertEquals(REJECTED, PatchIndexRepository.checkUrl("http://example.com/index.json"));
		assertEquals(REJECTED, PatchIndexRepository.checkUrl("file:///etc/passwd"));
		assertEquals(REJECTED, PatchIndexRepository.checkUrl(""));
		assertEquals(REJECTED, PatchIndexRepository.checkUrl(null));
		assertEquals(REJECTED, PatchIndexRepository.checkUrl("not a url"));
	}

	@Test
	void priceImpactUsesMediansOnEachSide() {
		Instant at = Instant.parse("2026-10-27T12:00:00Z");
		List<PricePoint> pts = new ArrayList<>();
		// before: 100, 100, 1000 (spike) -> median 100; after: 150, 150, 150 -> 150
		pts.add(new PricePoint(at.minusSeconds(3 * 3600), 100, 90, 0, 0));
		pts.add(new PricePoint(at.minusSeconds(2 * 3600), 1000, 90, 0, 0));
		pts.add(new PricePoint(at.minusSeconds(3600), 100, 90, 0, 0));
		pts.add(new PricePoint(at.plusSeconds(3600), 150, Double.NaN, 0, 0));
		pts.add(new PricePoint(at.plusSeconds(2 * 3600), 150, Double.NaN, 0, 0));
		pts.add(new PricePoint(at.plusSeconds(3 * 3600), 150, Double.NaN, 0, 0));
		pts.add(new PricePoint(at.plus(Duration.ofDays(3)), 9999, 9999, 0, 0)); // outside window

		PriceImpact pi = PriceImpact.of(pts, at, Duration.ofHours(4));
		assertEquals(100, pi.buyBefore());
		assertEquals(150, pi.buyAfter());
		assertEquals(50.0, pi.buyPercent(), 1e-9);
		assertEquals(90, pi.sellBefore());
		assertTrue(Double.isNaN(pi.sellAfter()));
		assertTrue(Double.isNaN(pi.sellPercent()));
		assertEquals(Duration.ofDays(7), PriceImpact.windowFor(TimeRange.ALL));
		assertEquals(Duration.ofHours(2), PriceImpact.windowFor(TimeRange.DAY));
	}

	@Test
	void repositoryPrefersFreshDownloadThenCacheThenBundled(@TempDir Path dir) {
		Path cache = dir.resolve("patch_index.json");
		String url = "https://example.com/index.json";
		AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-16T12:00:00Z"));
		AtomicInteger downloads = new AtomicInteger();
		String remote = INDEX.replace("2026-11-16T00:00:00Z", "2026-11-16T06:00:00Z");
		Function<URI, CompletableFuture<String>> ok = u -> {
			downloads.incrementAndGet();
			return CompletableFuture.completedFuture(remote);
		};
		String bundled = "{\"version\":1,\"updated\":\"2026-01-01T00:00:00Z\",\"patches\":[]}";

		PatchIndexRepository repo = new PatchIndexRepository(ok, cache, () -> bundled, Runnable::run, now::get, w -> {});
		repo.init(url).join();
		assertEquals(1, downloads.get());
		assertEquals(2, repo.current().patches().size());
		assertEquals("downloaded copy", repo.source());

		// new session within TTL: served from disk cache, no download
		PatchIndexRepository repo2 = new PatchIndexRepository(ok, cache, () -> bundled, Runnable::run, now::get, w -> {});
		repo2.init(url).join();
		assertEquals(1, downloads.get());
		assertEquals(2, repo2.current().patches().size());

		// changed URL (e.g. moved to a Raspberry Pi) forces a download
		repo2.init("https://pi.example.com/index.json").join();
		assertEquals(2, downloads.get());

		// after TTL, offline: keep cached copy and report problem
		now.set(now.get().plus(Duration.ofHours(25)));
		Function<URI, CompletableFuture<String>> offline = u -> CompletableFuture.failedFuture(new ApiException(ApiException.Kind.OFFLINE, "x"));
		PatchIndexRepository repo3 = new PatchIndexRepository(offline, cache, () -> bundled, Runnable::run, now::get, w -> {});
		repo3.init("https://pi.example.com/index.json").join();
		assertEquals(2, repo3.current().patches().size());
		assertTrue(repo3.problem().startsWith("Offline"));
	}

	@Test
	void repositoryFallsBackToBundledAndPrefersNewerBundle(@TempDir Path dir) {
		Function<URI, CompletableFuture<String>> offline = u -> CompletableFuture.failedFuture(new ApiException(ApiException.Kind.OFFLINE, "x"));
		PatchIndexRepository repo = new PatchIndexRepository(offline, dir.resolve("c.json"), () -> INDEX, Runnable::run, Instant::now, w -> {});
		repo.init("https://example.com/i.json").join();
		assertEquals("built-in copy", repo.source());
		assertEquals(2, repo.current().patches().size());

		// bad URL: nothing downloaded, bundled still used
		PatchIndexRepository bad = new PatchIndexRepository(u -> fail("must not download"), null, () -> INDEX, Runnable::run, Instant::now, w -> {});
		bad.init("http://example.com/index.json").join();
		assertEquals(2, bad.current().patches().size());
		assertNotNull(bad.problem());
	}
}
