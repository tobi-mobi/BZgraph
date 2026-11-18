package dev.bzgraph.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CompareMathTest {
	private static final long MIN = 60_000L;

	@Test
	void percentFromCommonStart() {
		long[] t = {0, 10 * MIN, 20 * MIN, 30 * MIN};
		double[] v = {Double.NaN, 200, 250, 150};
		double base = CompareMath.baseline(t, v, 5 * MIN);
		assertEquals(200, base);
		double[] p = CompareMath.percent(v, base);
		assertTrue(Double.isNaN(p[0]));
		assertEquals(0, p[1], 1e-9);
		assertEquals(25, p[2], 1e-9);
		assertEquals(-25, p[3], 1e-9);
		assertTrue(Double.isNaN(CompareMath.percent(v, 0)[1]), "no division by zero");
	}

	@Test
	void ratioUsesLatestSampleOfTheOtherItem() {
		long[] ta = {0, 10 * MIN, 20 * MIN, 100 * MIN};
		double[] a = {160, 170, 180, 200};
		long[] tb = {-2 * MIN, 12 * MIN};
		double[] b = {1, 2};
		double[] r = CompareMath.ratio(ta, a, tb, b, 15 * MIN);
		assertEquals(160, r[0], 1e-9);
		assertEquals(170, r[1], 1e-9, "B's sample from 12 min ago is still older than this point -> uses 1");
		assertEquals(90, r[2], 1e-9);
		assertTrue(Double.isNaN(r[3]), "B's last sample is too old");
	}

	@Test
	void coarseBucketsHoldUntilTheNextSampleEvenWhenMostPointsAreFine() {
		// 1W/1M shape: a few 2-hour buckets, then a 12-hour tail at 3-minute steps (so the typical spacing is 3 min)
		long h2 = 120 * MIN;
		long[] t = new long[4 + 240];
		double[] v = new double[t.length];
		for (int i = 0; i < 4; i++) {
			t[i] = i * h2;
			v[i] = 100 + i;
		}
		for (int i = 0; i < 240; i++) {
			t[4 + i] = 4 * h2 + i * 3 * MIN;
			v[4 + i] = 200;
		}
		long gap = CompareMath.maxGap(t);
		assertEquals(600_000L, gap);
		assertEquals(101, CompareMath.valueAt(t, v, h2 + 90 * MIN, gap), "90 min into a 2-hour bucket still has a price");
		assertEquals(200, CompareMath.valueAt(t, v, t[t.length - 1] + 4 * MIN, gap));
		assertTrue(Double.isNaN(CompareMath.valueAt(t, v, t[t.length - 1] + 60 * MIN, gap)), "nothing after the data ends");
		assertTrue(Double.isNaN(CompareMath.valueAt(t, v, -MIN, gap)), "nothing before it starts");
	}

	@Test
	void maxGapFollowsSampleSpacing() {
		assertEquals(600_000L, CompareMath.maxGap(new long[]{0, 3 * MIN, 6 * MIN}));
		long day = 86_400_000L;
		assertEquals(3 * day, CompareMath.maxGap(new long[]{0, day, 2 * day, 3 * day}));
	}
}
