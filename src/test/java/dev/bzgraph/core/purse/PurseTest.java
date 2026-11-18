package dev.bzgraph.core.purse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PurseTest {
	private static final Instant T0 = Instant.parse("2026-11-01T12:00:00Z");

	@Test
	void parsesSidebarAndProfile() {
		assertEquals(12_345_678, PurseParser.purse(List.of("§711/18/26 §8m12AB", "  §7⏣ §bVillage", "Purse: §612,345,678 §e(+5)")));
		assertEquals(1234.5, PurseParser.purse(List.of("Piggy: §61,234.5")));
		assertEquals(42, PurseParser.purse(List.of("§fPurse: §642")), "emoji/colour padding is ignored");
		assertTrue(Double.isNaN(PurseParser.purse(List.of("Motes: 1,000", "Bits: 300"))), "the Rift has no purse");
		assertEquals("Apple", PurseParser.profileFromChat("§aYou are playing on profile: §eApple §b(Co-op)"));
		assertNull(PurseParser.profileFromChat("[Bazaar] You are playing on profile: Fake"), "must start the message");
		assertEquals("Mango", PurseParser.profileFromTab(List.of("Players (20)", " Profile: Mango ♲")));
	}

	@Test
	void recordsStableReadingsWithThrottle() {
		PurseStore store = new PurseStore(null);
		PurseRecorder r = new PurseRecorder(store);
		r.onProfile("Steve", "Apple");
		Instant t = T0;
		assertFalse(r.onReading("Steve", 1_000_000, t), "needs to be seen twice");
		assertTrue(r.onReading("Steve", 1_000_000, t = t.plusSeconds(1)));
		// a few coins from mobs: not written until a minute has passed
		r.onReading("Steve", 1_000_050, t = t.plusSeconds(1));
		assertFalse(r.onReading("Steve", 1_000_050, t = t.plusSeconds(1)));
		assertEquals(1, store.points("Steve", "Apple").size());
		assertTrue(r.onReading("Steve", 1_000_050, t.plusSeconds(60)));
		// selling for 2M is written right away
		t = t.plusSeconds(61);
		r.onReading("Steve", 3_000_050, t = t.plusSeconds(1));
		assertTrue(r.onReading("Steve", 3_000_050, t.plusSeconds(1)));
		assertEquals(List.of(1_000_000.0, 1_000_050.0, 3_000_050.0), coins(store.points("Steve", "Apple")));
	}

	@Test
	void profilesAndWorldChangesAreSeparate() {
		PurseStore store = new PurseStore(null);
		PurseRecorder r = new PurseRecorder(store);
		r.onProfile("Steve", "Apple");
		r.onReading("Steve", 500, T0);
		r.onReading("Steve", 500, T0.plusSeconds(1));
		r.onWorldChange(T0.plusSeconds(2));
		assertFalse(r.onReading("Steve", 0, T0.plusSeconds(3)), "ignored while the sidebar settles");
		assertFalse(r.onReading("Steve", 0, T0.plusSeconds(4)));
		r.onProfile("Steve", "Banana");
		r.onReading("Steve", 9_000, T0.plusSeconds(6));
		assertTrue(r.onReading("Steve", 9_000, T0.plusSeconds(7)));
		assertEquals(List.of(500.0), coins(store.points("Steve", "Apple")));
		assertEquals(List.of(9_000.0), coins(store.points("Steve", "Banana")));
		assertEquals(List.of("Banana", "Apple"), store.profiles("steve"), "newest first, case-insensitive account");
		assertEquals("Banana", store.lastProfile("Steve"));
	}

	@Test
	void thinsOldPointsAndPersists(@TempDir Path dir) throws Exception {
		PurseStore store = new PurseStore(dir.resolve("purse.json"));
		long old = T0.toEpochMilli() - 30L * 86_400_000L;
		double[] vals = {100, 50, 300, 200, 250};
		for (int i = 0; i < vals.length; i++) store.add("Steve", "Apple", old + i * 60_000L, vals[i]);
		store.add("Steve", "Apple", T0.toEpochMilli(), 999);
		store.compact(T0.toEpochMilli());
		assertEquals(List.of(100.0, 50.0, 300.0, 250.0, 999.0), coins(store.points("Steve", "Apple")),
				"first, lowest, highest and last of the old hour are kept");
		store.setLastProfile("Steve", "Apple");
		store.saveIfDirty();

		PurseStore loaded = new PurseStore(dir.resolve("purse.json"));
		loaded.load();
		assertEquals(store.points("Steve", "Apple"), loaded.points("Steve", "Apple"));
		assertEquals("Apple", loaded.lastProfile("steve"));
		assertEquals(300, PurseStore.valueAt(loaded.points("Steve", "Apple"), Instant.ofEpochMilli(old + 150_000)));
	}

	private static List<Double> coins(List<PurseStore.Point> pts) {
		List<Double> out = new ArrayList<>();
		for (PurseStore.Point p : pts) out.add(p.coins());
		return out;
	}
}
