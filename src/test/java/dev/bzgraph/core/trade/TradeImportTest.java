package dev.bzgraph.core.trade;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class TradeImportTest {
	private static final ZoneId UTC = ZoneOffset.UTC;

	private static void gz(Path p, String... lines) throws Exception {
		try (OutputStream o = new GZIPOutputStream(Files.newOutputStream(p))) {
			o.write(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
		}
	}

	@Test
	void parsesLinesWithAccountAndMidnightRollover() {
		List<LogImporter.LogEvent> ev = LogImporter.parseLines(List.of(
				"[23:59:50] [Render thread/INFO]: Setting user: tobi_test",
				"[23:59:55] [Render thread/INFO]: [CHAT] [Bazaar] Sold 1x Mithril for 5 coins!",
				"[00:00:05] [Render thread/INFO]: [CHAT] [Bazaar] Sold 2x Mithril for 10 coins!",
				"    at some.stack.Trace(line)",
				"[00:00:06] [Render thread/INFO]: [CHAT] [Bazaar] Couldn't find any buyers for Gravel!",
				"[00:00:07] [Render thread/INFO]: [CHAT] [346] ツ [MVP+] x: [Bazaar] Sold 9x Mithril for 9 coins!"
		), LocalDate.parse("2026-11-15"), UTC, "fallback");
		assertEquals(2, ev.size());
		assertEquals("tobi_test", ev.getFirst().account());
		assertEquals(Instant.parse("2026-11-15T23:59:55Z"), ev.get(0).time());
		assertEquals(Instant.parse("2026-11-16T00:00:05Z"), ev.get(1).time());
	}

	@Test
	void filesAreOrderedOldestFirstAndLatestLast(@TempDir Path dir) throws Exception {
		for (String n : List.of("2026-11-16-5.log.gz", "2026-11-16-10.log.gz", "2026-08-02-1.log.gz", "latest.log", "debug.log")) {
			Files.writeString(dir.resolve(n), "");
		}
		List<String> names = LogImporter.listFiles(dir, true).stream().map(f -> f.path().getFileName().toString()).toList();
		assertEquals(List.of("2026-08-02-1.log.gz", "2026-11-16-5.log.gz", "2026-11-16-10.log.gz", "latest.log"), names);
		assertEquals(3, LogImporter.listFiles(dir, false).size());
	}

	@Test
	void importAcrossFilesMatchesOffersAndNeverDoubleCounts(@TempDir Path dir) throws Exception {
		Path logs = Files.createDirectory(dir.resolve("logs"));
		gz(logs.resolve("2026-11-14-1.log.gz"),
				"[10:00:00] [Render thread/INFO]: Setting user: Tobi",
				"[10:00:01] [Render thread/INFO]: [CHAT] [Bazaar] Sell Offer Setup! 1x Tungsten Plate for 12,000,000 coins.",
				"[10:05:00] [Render thread/INFO]: [CHAT] [Bazaar] Sold 32x  Fine Citrine Gemstone for 842,982 coins!");
		gz(logs.resolve("2026-11-15-1.log.gz"),
				"[09:00:00] [Render thread/INFO]: Setting user: Tobi",
				"[09:30:00] [Render thread/INFO]: [CHAT] [Bazaar] Your Sell Offer for 1x Tungsten Plate was filled! [Go To Orders]");

		TradeStore store = new TradeStore(dir.resolve("trades.json"));
		TradeService svc = new TradeService(store, () -> Instant.parse("2026-11-16T12:00:00Z"), Throwable::printStackTrace);
		TradeService.ImportResult r = svc.importFolder(logs, "fallback", UTC, false);
		assertEquals(2, r.files());
		assertEquals(2, r.trades());
		List<Trade> trades = store.trades("tobi");
		assertEquals(2, trades.size());
		assertEquals(12_000_000, trades.get(1).unitPrice(), 1e-6, "offer placed in one file, filled in the next");

		// importing again does nothing
		assertEquals(0, svc.importFolder(logs, "fallback", UTC, false).files());
		assertEquals(2, store.trades("tobi").size());

		// survives a restart
		TradeStore reloaded = new TradeStore(dir.resolve("trades.json"));
		reloaded.load();
		assertEquals(2, reloaded.trades("Tobi").size());
		assertEquals(Long.MAX_VALUE, reloaded.importedUntil(LogImporter.listFiles(logs, false).getFirst().key()));
	}

	@Test
	void liveCapturedSessionsAreSkippedByImport(@TempDir Path dir) throws Exception {
		AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-16T10:00:00Z"));
		TradeStore store = new TradeStore(dir.resolve("trades.json"));
		TradeService svc = new TradeService(store, now::get, Throwable::printStackTrace); // session starts 10:00
		now.set(Instant.parse("2026-11-16T10:05:00Z"));
		assertTrue(svc.onChat("Tobi", "[Bazaar] Sold 32x  Fine Citrine Gemstone for 842,982 coins!"));
		assertFalse(svc.onChat("Tobi", "[Bazaar] Seems like a volatile market!"));

		// next launch: the log of that session gets imported, but its trade was already captured live
		Path logs = Files.createDirectory(dir.resolve("logs"));
		gz(logs.resolve("2026-11-16-1.log.gz"),
				"[10:00:00] [Render thread/INFO]: Setting user: Tobi",
				"[10:05:00] [Render thread/INFO]: [CHAT] [Bazaar] Sold 32x  Fine Citrine Gemstone for 842,982 coins!",
				"[10:05:00] [Render thread/INFO]: [CHAT] [Bazaar] Sold 32x  Fine Citrine Gemstone for 842,982 coins! (2)");
		TradeService.ImportResult r = svc.importFolder(logs, "Tobi", UTC, false);
		assertEquals(2, r.skippedAsLive());
		assertEquals(1, store.trades("Tobi").size());
	}

	@Test
	void latestLogIsImportedIncrementally(@TempDir Path dir) throws Exception {
		Path logs = Files.createDirectory(dir.resolve("logs"));
		Path latest = logs.resolve("latest.log");
		Files.writeString(latest, String.join("\n",
				"[10:00:00] [Render thread/INFO]: Setting user: Tobi",
				"[10:01:00] [Render thread/INFO]: [CHAT] [Bazaar] Sold 1x Mithril for 5 coins!"));
		TradeStore store = new TradeStore(null);
		TradeService svc = new TradeService(store, Instant::now, Throwable::printStackTrace);
		assertEquals(1, svc.importFolder(logs, "x", ZoneId.systemDefault(), true).trades());
		Files.writeString(latest, String.join("\n",
				"[10:00:00] [Render thread/INFO]: Setting user: Tobi",
				"[10:01:00] [Render thread/INFO]: [CHAT] [Bazaar] Sold 1x Mithril for 5 coins!",
				"[10:02:00] [Render thread/INFO]: [CHAT] [Bazaar] Sold 2x Mithril for 10 coins!"));
		assertEquals(1, svc.importFolder(logs, "x", ZoneId.systemDefault(), true).trades(), "only the new line");
		assertEquals(2, store.trades("tobi").size());
	}

	@Test
	void forProductFiltersByMappedName() {
		TradeStore store = new TradeStore(null);
		TradeService svc = new TradeService(store, () -> Instant.parse("2026-11-16T10:00:00Z"), t -> {});
		svc.onChat("Tobi", "[Bazaar] Sold 32x  Fine Citrine Gemstone for 842,982 coins!");
		svc.onChat("Tobi", "[Bazaar] Sold 80x Mithril for 400.0 coins!");
		assertTrue(svc.forProduct("Tobi", "FINE_CITRINE_GEM").isEmpty(), "no names loaded yet");
		svc.setNames(ProductNames.build(Set.of("FINE_CITRINE_GEM", "MITHRIL_ORE"),
				Map.of("FINE_CITRINE_GEM", "Fine Citrine Gemstone", "MITHRIL_ORE", "Mithril")));
		assertEquals(1, svc.forProduct("Tobi", "FINE_CITRINE_GEM").size());
		assertEquals(1, svc.forProduct("tobi", "MITHRIL_ORE").size());
		assertTrue(svc.forProduct("Someone", "MITHRIL_ORE").isEmpty(), "per account");
	}
}
