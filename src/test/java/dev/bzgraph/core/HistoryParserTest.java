package dev.bzgraph.core;

import com.google.gson.JsonParseException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HistoryParserTest {
	private static final String SAMPLE = """
			[
			  {"timestamp":"2026-11-15T02:00:00","buy":1299.3,"sell":1245.3,"minBuy":1290,"maxBuy":1310,
			   "minSell":1240,"maxSell":1250,"buyVolume":123456,"sellVolume":654321,
			   "buyMovingWeek":1000000,"sellMovingWeek":2000000},
			  {"timestamp":"2026-11-15T01:55:00.1234567","buy":1298.0,"sell":1244.0,"buyVolume":1,"sellVolume":2},
			  {"timestamp":"2026-11-15T01:50:00.5","buy":null,"sell":1243.0}
			]""";

	@Test
	void parsesPointsInResponseOrder() {
		List<PricePoint> pts = HistoryParser.parse(SAMPLE);
		assertEquals(3, pts.size());
		PricePoint p = pts.getFirst();
		assertEquals(Instant.parse("2026-11-15T02:00:00Z"), p.timestamp());
		assertEquals(1299.3, p.buy(), 1e-9);
		assertEquals(1245.3, p.sell(), 1e-9);
		assertEquals(123456, p.buyVolume(), 1e-9);
		assertEquals(654321, p.sellVolume(), 1e-9);
		assertEquals(777777, p.volume(), 1e-9);
	}

	@Test
	void missingOrNullValuesBecomeNaN() {
		PricePoint p = HistoryParser.parse(SAMPLE).get(2);
		assertFalse(p.hasBuy());
		assertTrue(p.hasSell());
		assertTrue(Double.isNaN(p.buyVolume()));
		assertEquals(0, p.volume());
	}

	@Test
	void timestampsWithoutZoneAreUtc() {
		assertEquals(Instant.parse("2026-11-15T02:00:00Z"), HistoryParser.parseTimestamp("2026-11-15T02:00:00"));
	}

	@Test
	void fractionalSecondsOfAnyPrecision() {
		assertEquals(Instant.parse("2026-11-15T01:50:00.500Z"), HistoryParser.parseTimestamp("2026-11-15T01:50:00.5"));
		assertEquals(Instant.parse("2026-11-15T01:55:00.1234567Z"), HistoryParser.parseTimestamp("2026-11-15T01:55:00.1234567"));
		assertEquals(Instant.parse("2026-11-15T01:55:00.123456789Z"), HistoryParser.parseTimestamp("2026-11-15T01:55:00.123456789"));
	}

	@Test
	void explicitZonesAreRespected() {
		assertEquals(Instant.parse("2026-11-15T02:00:00Z"), HistoryParser.parseTimestamp("2026-11-15T02:00:00Z"));
		assertEquals(Instant.parse("2026-11-15T01:00:00Z"), HistoryParser.parseTimestamp("2026-11-15T02:00:00+01:00"));
		assertEquals(Instant.parse("2026-11-15T07:00:00Z"), HistoryParser.parseTimestamp("2026-11-15T02:00:00-05:00"));
	}

	@Test
	void badTimestampsAreSkipped() {
		assertNull(HistoryParser.parseTimestamp("not a date"));
		assertNull(HistoryParser.parseTimestamp(""));
		assertNull(HistoryParser.parseTimestamp(null));
		List<PricePoint> pts = HistoryParser.parse("[{\"timestamp\":\"garbage\",\"buy\":1},{\"buy\":2},{\"timestamp\":\"2026-01-01T00:00:00\",\"buy\":3}]");
		assertEquals(1, pts.size());
		assertEquals(3, pts.getFirst().buy());
	}

	@Test
	void emptyArrayIsNoDataNotError() {
		assertTrue(HistoryParser.parse("[]").isEmpty());
		assertTrue(HistoryParser.parse("  [ ]  ").isEmpty());
	}

	@Test
	void nonArrayIsRejected() {
		assertThrows(JsonParseException.class, () -> HistoryParser.parse("{\"error\":\"nope\"}"));
		assertThrows(JsonParseException.class, () -> HistoryParser.parse("null"));
	}

	@Test
	void snapshotParsed() {
		PricePoint p = HistoryParser.parseSnapshot("""
				{"productId":"ENCHANTED_DIAMOND","buyPrice":1281.8,"buyVolume":6113072,"buyMovingWeek":5236513,
				 "sellPrice":1257.9,"sellVolume":4842062,"timeStamp":"2026-11-16T16:46:48.979",
				 "buyOrders":[{"amount":505,"pricePerUnit":1281.8,"orders":3}]}""");
		assertEquals(Instant.parse("2026-11-16T16:46:48.979Z"), p.timestamp());
		assertEquals(1281.8, p.buy(), 1e-9);
		assertEquals(1257.9, p.sell(), 1e-9);
		assertEquals(6113072, p.buyVolume(), 1e-9);

		PricePoint noSellers = HistoryParser.parseSnapshot("{\"buyPrice\":0,\"sellPrice\":5,\"timeStamp\":\"2026-11-16T16:46:48\"}");
		assertTrue(Double.isNaN(noSellers.buy()), "0 = no orders, not a price of 0");
		assertThrows(JsonParseException.class, () -> HistoryParser.parseSnapshot("{\"buyPrice\":1}"));
		assertThrows(JsonParseException.class, () -> HistoryParser.parseSnapshot("[]"));
	}

	@Test
	void bazaarProductIdsParsed() {
		var ids = BazaarProducts.parseProductIds("{\"success\":true,\"products\":{\"ENCHANTED_DIAMOND\":{},\"INK_SACK:3\":{}}}");
		assertEquals(java.util.Set.of("ENCHANTED_DIAMOND", "INK_SACK:3"), ids);
		assertThrows(ApiException.class, () -> BazaarProducts.parseProductIds("{\"success\":false}"));
	}

	@Test
	void retryAfterParsing() {
		Instant now = Instant.parse("2026-11-16T12:00:00Z");
		assertEquals(7, ApiClient.parseRetryAfter("7", now));
		assertEquals(ApiClient.DEFAULT_RETRY_AFTER_SECONDS, ApiClient.parseRetryAfter(null, now));
		assertEquals(1, ApiClient.parseRetryAfter("0", now));
		assertEquals(ApiClient.MAX_RETRY_AFTER_SECONDS, ApiClient.parseRetryAfter("99999", now));
		assertEquals(30, ApiClient.parseRetryAfter("Mon, 16 Nov 2026 12:00:30 GMT", now));
	}

	@Test
	void historyUriFormat() {
		ApiClient c = new ApiClient(null, null, "test", ApiClient.COFL_BASE);
		String uri = c.historyUri("INK_SACK:3", Instant.parse("1970-01-01T00:00:00Z"), Instant.parse("2026-11-16T12:34:56.789Z")).toString();
		assertEquals("https://sky.coflnet.com/api/bazaar/INK_SACK%3A3/history?start=1970-01-01T00:00:00Z&end=2026-11-16T12:34:56Z", uri);
		assertEquals("https://sky.coflnet.com/api/bazaar/INK_SACK%3A3/snapshot", c.snapshotUri("INK_SACK:3").toString());
	}
}
