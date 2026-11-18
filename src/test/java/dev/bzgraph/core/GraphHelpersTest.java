package dev.bzgraph.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GraphHelpersTest {
	private static final long MIN = 60_000L, HOUR = 60 * MIN, DAY = 24 * HOUR;
	private static final ZoneId UTC = ZoneOffset.UTC;

	@Test
	void dayTicksAreEvenlySpacedAcrossMonthEnds() {
		long tMin = LocalDate.of(2026, 10, 19).atStartOfDay(UTC).toInstant().toEpochMilli() + 5 * HOUR;
		long tMax = tMin + 25 * DAY;
		List<TimeTicks.Tick> ticks = TimeTicks.ticks(tMin, tMax, 7, UTC);
		assertTrue(ticks.size() >= 5 && ticks.size() <= 7, ticks.toString());
		for (int i = 1; i < ticks.size(); i++) {
			assertEquals(4 * DAY, ticks.get(i).time() - ticks.get(i - 1).time(), "same gap everywhere, incl. Oct -> Nov");
		}
		assertTrue(ticks.getFirst().time() >= tMin);
		assertTrue(ticks.getLast().time() <= tMax);
	}

	@Test
	void hourTicksLabelMidnightWithTheDate() {
		long tMin = LocalDate.of(2026, 11, 17).atStartOfDay(UTC).toInstant().toEpochMilli() + 7 * HOUR + 13 * MIN;
		List<TimeTicks.Tick> ticks = TimeTicks.ticks(tMin, tMin + DAY, 8, UTC);
		assertEquals("09:00", ticks.getFirst().label());
		assertTrue(ticks.stream().anyMatch(t -> t.label().equals("Nov 18")), ticks.toString());
		for (int i = 1; i < ticks.size(); i++) assertEquals(3 * HOUR, ticks.get(i).time() - ticks.get(i - 1).time());
	}

	@Test
	void monthTicksShowTheYearInJanuary() {
		long tMin = LocalDate.of(2025, 11, 20).atStartOfDay(UTC).toInstant().toEpochMilli();
		List<TimeTicks.Tick> ticks = TimeTicks.ticks(tMin, tMin + 365 * DAY, 7, UTC);
		assertTrue(ticks.stream().anyMatch(t -> t.label().equals("2026")), ticks.toString());
		assertTrue(ticks.stream().anyMatch(t -> t.label().equals("Mar")), ticks.toString());
	}

	@Test
	void resamplingMergesTheFineTailIntoEqualBuckets() {
		List<PricePoint> pts = new ArrayList<>();
		long t0 = 1_700_000_000_000L / (2 * HOUR) * (2 * HOUR);
		for (int i = 0; i < 80; i++) pts.add(new PricePoint(Instant.ofEpochMilli(t0 + i * 2 * HOUR), 100, 90, 1, 1));
		long fine = t0 + 80 * 2 * HOUR;
		for (int i = 0; i < 240; i++) {
			double buy = i % 2 == 0 ? 110 : 130; // noisy
			pts.add(new PricePoint(Instant.ofEpochMilli(fine + i * 3 * MIN), buy, 95, 1, 1));
		}
		long bucket = Resampler.bucketMs(pts);
		assertEquals(2 * HOUR, bucket);
		List<PricePoint> out = Resampler.resample(pts, bucket);
		assertEquals(86, out.size(), "80 coarse + 12h of fine data in 6 buckets");
		PricePoint merged = out.get(81);
		assertEquals(120, merged.buy(), 1e-9);
		assertEquals(110, merged.minBuy(), 1e-9);
		assertEquals(130, merged.maxBuy(), 1e-9);
		assertEquals(pts.getLast().timestamp(), out.getLast().timestamp(), "still ends at the newest sample");
		assertSame(pts.get(3), out.get(3), "single samples are kept as they are");
	}

	@Test
	void resamplingLeavesEvenDataAlone() {
		List<PricePoint> pts = new ArrayList<>();
		for (int i = 0; i < 100; i++) pts.add(new PricePoint(Instant.ofEpochMilli(i * DAY), i, i, 1, 1));
		assertEquals(100, Resampler.resample(pts).size());
	}

	@Test
	void percentileAndRoundedPrices() {
		assertEquals(9, Resampler.percentile(new double[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, Double.NaN}, 0.9), 1e-9);
		assertTrue(Double.isNaN(Resampler.percentile(new double[]{Double.NaN}, 0.5)));
		assertEquals("12,540", CoinFormat.price(12_539.8));
		assertEquals("152.4", CoinFormat.price(152.42));
		assertEquals("3.45", CoinFormat.price(3.449));
		assertEquals("-1,500", CoinFormat.price(-1500));
	}
}
