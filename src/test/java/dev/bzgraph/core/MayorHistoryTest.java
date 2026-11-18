package dev.bzgraph.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MayorHistoryTest {
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
		assertEquals(2, h.between(Instant.parse("2026-02-14T00:00:00Z"), Instant.parse("2026-02-16T00:00:00Z")).size());
		assertEquals("mining", MayorHistory.type("", "Cole"), "old terms without key");
	}
}
