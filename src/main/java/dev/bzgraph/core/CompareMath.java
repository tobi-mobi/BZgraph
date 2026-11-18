package dev.bzgraph.core;

import java.util.Arrays;

/** Helpers for drawing several items on one graph: % change from a common start and price ratios. */
public final class CompareMath {
	private CompareMath() {
	}

	/** First non-NaN value at or after {@code from}, or NaN. */
	public static double baseline(long[] times, double[] values, long from) {
		for (int i = 0; i < times.length; i++) {
			if (times[i] >= from && !Double.isNaN(values[i])) return values[i];
		}
		return Double.NaN;
	}

	/** Each value as % change from {@code base} (NaN stays NaN; NaN/zero base gives all NaN). */
	public static double[] percent(double[] values, double base) {
		double[] out = new double[values.length];
		for (int i = 0; i < values.length; i++) {
			out[i] = Double.isNaN(base) || base == 0 || Double.isNaN(values[i]) ? Double.NaN : (values[i] / base - 1) * 100;
		}
		return out;
	}

	/**
	 * The latest non-NaN value at or before {@code t}, or NaN if that sample is too old. "Too old" is judged
	 * locally: a sample holds until the next one (2-hour or daily buckets on long ranges, ~3 minutes in the
	 * fresh tail), plus half a step more, and at least {@code maxGap}. {@code times} must be ascending.
	 */
	public static double valueAt(long[] times, double[] values, long t, long maxGap) {
		int i = Arrays.binarySearch(times, t);
		if (i < 0) i = -i - 2;
		for (; i >= 0; i--) {
			if (Double.isNaN(values[i])) continue;
			long step = i + 1 < times.length ? times[i + 1] - times[i] : i > 0 ? times[i] - times[i - 1] : 0;
			return t - times[i] <= Math.max(maxGap, step + step / 2) ? values[i] : Double.NaN;
		}
		return Double.NaN;
	}

	/** A / B at each of A's times, with B looked up by {@link #valueAt}. */
	public static double[] ratio(long[] ta, double[] a, long[] tb, double[] b, long maxGap) {
		double[] out = new double[ta.length];
		for (int i = 0; i < ta.length; i++) {
			double bv = valueAt(tb, b, ta[i], maxGap);
			out[i] = Double.isNaN(a[i]) || Double.isNaN(bv) || bv == 0 ? Double.NaN : a[i] / bv;
		}
		return out;
	}

	/** How stale a looked-up sample may be: three typical sample spacings, at least 10 minutes. */
	public static long maxGap(long[] times) {
		if (times.length < 2) return 600_000L;
		long[] d = new long[times.length - 1];
		for (int i = 1; i < times.length; i++) d[i - 1] = times[i] - times[i - 1];
		Arrays.sort(d);
		return Math.max(600_000L, 3 * d[d.length / 2]);
	}
}
