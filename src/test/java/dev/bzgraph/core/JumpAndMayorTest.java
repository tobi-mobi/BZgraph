package dev.bzgraph.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class JumpAndMayorTest {
	private static final Instant T0 = Instant.parse("2026-02-01T00:00:00Z");
	private static final long DAY = Duration.ofDays(1).toMillis();

	private static long[] days(int n) {
		long[] t = new long[n];
		for (int i = 0; i < n; i++) t[i] = T0.toEpochMilli() + i * DAY;
		return t;
	}

	private static double[] noisy(int n, double level, double noisePct, long seed) {
		Random r = new Random(seed);
		double[] v = new double[n];
		for (int i = 0; i < n; i++) v[i] = level * (1 + (r.nextDouble() * 2 - 1) * noisePct / 100);
		return v;
	}

	@Test
	void levelShiftIsDetectedOnceAtItsStart() {
		double[] v = noisy(60, 25_000_000, 1, 1);
		for (int i = 30; i < 60; i++) v[i] *= 1.30; // +30% from day 30 on (like the Skeleton Key)
		List<JumpDetector.Jump> j = JumpDetector.detect(days(60), v, 8);
		assertEquals(1, j.size(), j.toString());
		assertEquals(30, j.getFirst().index());
		assertTrue(j.getFirst().pct() > 25, "pct " + j.getFirst().pct());
		assertEquals(T0.plusMillis(30 * DAY), j.getFirst().time());
	}

	@Test
	void plainNoiseIsNotAJump() {
		assertTrue(JumpDetector.detect(days(90), noisy(90, 1000, 3, 2), 8).isEmpty());
	}

	@Test
	void volatileItemsNeedBiggerMoves() {
		double[] v = noisy(60, 1000, 4, 3); // typical step ~3-4% -> threshold ~20%
		v[40] *= 1.12;                      // +12% is normal for this item
		assertTrue(JumpDetector.detect(days(60), v, 8).isEmpty());
		v[45] *= 1.6;                       // +60% spike is not
		List<JumpDetector.Jump> j = JumpDetector.detect(days(60), v, 8);
		assertEquals(1, j.size());
		assertEquals(45, j.getFirst().index());
	}

	@Test
	void dropsAndRisesAndLimit() {
		double[] v = noisy(120, 500, 0.5, 4);
		for (int i = 20; i < 120; i++) v[i] *= 0.7;  // crash
		for (int i = 60; i < 120; i++) v[i] *= 1.5;  // recovery
		for (int i = 100; i < 120; i++) v[i] *= 1.3; // another rise
		List<JumpDetector.Jump> all = JumpDetector.detect(days(120), v, 8);
		assertEquals(3, all.size(), all.toString());
		assertTrue(all.get(0).pct() < 0);
		assertTrue(all.get(1).pct() > 0);
		List<JumpDetector.Jump> top = JumpDetector.detect(days(120), v, 1);
		assertEquals(1, top.size());
		assertEquals(60, top.getFirst().index(), "largest move kept");
	}

	private static final String MAYORS = """
			[{"candidates":null,"end":"02/15/2026 03:15:00","start":"02/09/2026 23:15:00 +00:00","year":500,
			  "winner":{"key":"economist","name":"Diaz","perks":[{"name":"Shopping Spree"}]}},
			 {"candidates":null,"end":"02/20/2026 07:15:00","start":"02/15/2026 03:15:00 +00:00","year":501,
			  "winner":{"key":"mining","name":"Cole","perks":[{"name":"Mining Fiesta"},{"name":"§aProspection"}],
			            "minister":{"key":"dungeons","name":"Paul","perk":{"name":"EZPZ","minister":true}}}},
			 {"candidates":null,"end":"02/25/2026 11:15:00","start":"02/20/2026 07:15:00 +00:00","year":502},
			 {"end":"garbage","start":"02/25/2026 11:15:00 +00:00","winner":{"name":"X"}}]""";

	@Test
	void mayorHistoryParsesAndLooksUp() {
		MayorHistory h = MayorHistory.parse(MAYORS);
		assertEquals(2, h.terms().size(), "no-winner and bad-date terms skipped");
		MayorHistory.Term cole = h.at(Instant.parse("2026-02-17T05:00:00Z"));
		assertEquals("Cole", cole.mayor());
		assertEquals("mining", cole.category());
		assertEquals(List.of("Mining Fiesta", "Prospection"), cole.perks());
		assertEquals("Paul", cole.minister());
		assertEquals("EZPZ", cole.ministerPerk());
		assertEquals(Instant.parse("2026-02-20T07:15:00Z"), cole.end());
		assertEquals("Diaz", h.at(Instant.parse("2026-02-10T00:00:00Z")).mayor());
		assertNull(h.at(Instant.parse("2026-03-01T00:00:00Z")));
		assertEquals(1, h.startedBetween(Instant.parse("2026-02-14T00:00:00Z"), Instant.parse("2026-02-16T00:00:00Z")).size());
		assertEquals(2, h.between(Instant.parse("2026-02-14T00:00:00Z"), Instant.parse("2026-02-16T00:00:00Z")).size());
	}

	private static final String FOXY_THEN_PAUL = """
			[{"end":"06/24/2025 01:15:00","start":"06/18/2025 21:15:00 +00:00",
			  "winner":{"key":"mining","name":"Cole","perks":[],"minister":{"key":"economist","name":"Diaz","perk":{"name":"Volume Trading"}}}},
			 {"end":"06/29/2025 05:15:00","start":"06/24/2025 01:15:00 +00:00",
			  "winner":{"key":"events","name":"Foxy","perks":[{"name":"Sweet Benevolence"}],
			            "minister":{"key":"economist","name":"Diaz","perk":{"name":"Long Term Investment"}}}},
			 {"end":"07/04/2025 09:15:00","start":"06/29/2025 05:15:00 +00:00",
			  "winner":{"key":"dungeons","name":"Paul","perks":[],"minister":{"key":"mining","name":"Cole","perk":{"name":"Mining Fiesta"}}}},
			 {"end":"07/09/2025 13:15:00","start":"07/04/2025 09:15:00 +00:00",
			  "winner":{"key":"fishing","name":"Marina","perks":[],"minister":{"key":"mining","name":"Cole","perk":{"name":"Mining Fiesta"}}}}]""";

	@Test
	void irrelevantMayorsAreNotCauses() {
		MayorHistory m = MayorHistory.parse(FOXY_THEN_PAUL);
		Set<ItemAreas.Area> mining = ItemAreas.keywordsOnly().of("SKELETON_KEY");
		PatchIndex foraging = new PatchIndex(null, List.of(new PatchNote("t", Instant.parse("2025-06-23T15:00:00Z"), "0.23",
				"Hypixel SkyBlock 0.23 - The Foraging Update", "", null, Set.of())));
		// the screenshot case: Foxy (events) + minister Diaz (economist) on a mining item
		JumpCauses.Explanation e = JumpCauses.explain(Instant.parse("2025-06-24T05:00:00Z"), "SKELETON_KEY", mining, foraging, m);
		assertNull(e.change(), "Foxy/Diaz don't affect mining items");
		assertNull(e.mayorAtTime());
		assertEquals(1, e.patches().size(), "the big Foraging Update stays (players moved off mining)");

		// minister Cole arriving with Paul is a mining cause...
		JumpCauses.Explanation paul = JumpCauses.explain(Instant.parse("2025-06-30T00:00:00Z"), "SKELETON_KEY", mining, PatchIndex.EMPTY, m);
		assertFalse(paul.change().mayorRelevant());
		assertTrue(paul.change().ministerRelevant());
		// ...but Cole simply staying minister under Marina is not a new cause
		JumpCauses.Explanation marina = JumpCauses.explain(Instant.parse("2025-07-05T00:00:00Z"), "SKELETON_KEY", mining, PatchIndex.EMPTY, m);
		assertNull(marina.change());
		assertEquals("Marina", marina.mayorAtTime().mayor(), "still shown as context: Cole is minister then");
	}

	@Test
	void itemAreas() {
		ItemAreas a = ItemAreas.fromCollections("""
				{"collections":{"MINING":{"items":{"DIAMOND":{},"MYCEL":{},"INK_SACK:4":{}}},
				  "FARMING":{"items":{"CARROT_ITEM":{},"INK_SACK:3":{},"EGG":{}}},
				  "FISHING":{"items":{"CLAY_BALL":{}}}}}""");
		assertEquals(Set.of(ItemAreas.Area.MINING), a.of("ENCHANTED_DIAMOND_BLOCK"));
		assertEquals(Set.of(ItemAreas.Area.MINING), a.of("SKELETON_KEY"));
		assertEquals(Set.of(ItemAreas.Area.MINING), a.of("FINE_CITRINE_GEM"));
		assertEquals(Set.of(ItemAreas.Area.MINING), a.of("GOBLIN_EGG_BLUE"), "not farming because of EGG");
		assertEquals(Set.of(ItemAreas.Area.FARMING), a.of("INK_SACK:3"), "cocoa beans");
		assertEquals(Set.of(ItemAreas.Area.MINING), a.of("INK_SACK:4"), "lapis");
		assertEquals(Set.of(ItemAreas.Area.FARMING), a.of("ENCHANTED_CARROT"));
		assertEquals(Set.of(ItemAreas.Area.FARMING), a.of("ASHWREATH"));
		assertTrue(a.of("ENCHANTMENT_SHARPNESS_5").isEmpty());
		assertTrue(ItemAreas.relevant("derp", Set.of()), "Derpy is market-wide");
		assertFalse(ItemAreas.relevant("economist", Set.of(ItemAreas.Area.MINING)));
		assertTrue(ItemAreas.relevant("mining", Set.of(ItemAreas.Area.MINING)));
		assertEquals("mining", MayorHistory.type("", "Cole"), "old terms without key");
	}

	@Test
	void causesPreferPatchesForTheItemThenCloseOnes() {
		Instant jump = Instant.parse("2026-02-17T06:00:00Z");
		PatchIndex idx = new PatchIndex(null, List.of(
				new PatchNote("far", jump.minus(Duration.ofDays(10)), null, "Too early", "", null, Set.of()),
				new PatchNote("other", jump.minus(Duration.ofHours(2)), null, "Unrelated fixes", "", null, Set.of()),
				new PatchNote("rc", jump.minus(Duration.ofHours(30)), null, "0.24.2 RC", "", null, Set.of("SKELETON_KEY"),
						Map.of("SKELETON_KEY", "Corpse loot rework"))));
		MayorHistory mayors = MayorHistory.parse(MAYORS);
		Set<ItemAreas.Area> mining = ItemAreas.keywordsOnly().of("SKELETON_KEY");
		JumpCauses.Explanation e = JumpCauses.explain(jump, "SKELETON_KEY", mining, idx, mayors);
		assertEquals(List.of("rc"), e.patches().stream().map(PatchNote::id).toList(),
				"small untagged bug-fix post isn't offered");
		assertEquals("Cole", e.change().term().mayor(), "Cole (mining) took office 2 days before");
		assertTrue(e.change().mayorRelevant());
		assertFalse(e.change().ministerRelevant(), "Paul (dungeons) as minister doesn't touch a mining item");
		assertEquals("Cole", e.mayorAtTime().mayor());

		// same jump on a farming item: Cole/Paul are irrelevant, so no mayor cause at all
		JumpCauses.Explanation farm = JumpCauses.explain(jump, "ENCHANTED_CARROT", ItemAreas.keywordsOnly().of("ENCHANTED_CARROT"), idx, mayors);
		assertNull(farm.change());
		assertNull(farm.mayorAtTime());

		JumpCauses.Explanation none = JumpCauses.explain(Instant.parse("2025-01-01T00:00:00Z"), "X", Set.of(), idx, MayorHistory.EMPTY);
		assertTrue(none.isEmpty());
	}
}
