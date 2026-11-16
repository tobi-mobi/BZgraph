package dev.bzgraph.core;

import java.util.Arrays;

/**
 * Reduces a series to at most one min/max/first/last bucket per pixel column so drawing cost is bounded
 * by screen width while keeping spikes visible.
 */
public final class Downsampler {
	/** Per-column aggregates; {@code count[c] == 0} means the column has no samples. */
	public record Columns(int width, int[] count, double[] first, double[] last, double[] min, double[] max) {
	}

	private Downsampler() {
	}

	/**
	 * @param times  ascending epoch millis
	 * @param values values (NaN entries are skipped)
	 * @param tMin   time at column 0
	 * @param tMax   time at column width-1
	 */
	public static Columns columns(long[] times, double[] values, long tMin, long tMax, int width) {
		int[] count = new int[width];
		double[] first = new double[width], last = new double[width], min = new double[width], max = new double[width];
		Arrays.fill(min, Double.POSITIVE_INFINITY);
		Arrays.fill(max, Double.NEGATIVE_INFINITY);
		double span = Math.max(1, tMax - tMin);
		for (int i = 0; i < times.length; i++) {
			double v = values[i];
			if (Double.isNaN(v) || times[i] < tMin || times[i] > tMax) continue;
			int c = (int) Math.round((times[i] - tMin) / span * (width - 1));
			c = Math.max(0, Math.min(width - 1, c));
			if (count[c]++ == 0) first[c] = v;
			last[c] = v;
			if (v < min[c]) min[c] = v;
			if (v > max[c]) max[c] = v;
		}
		return new Columns(width, count, first, last, min, max);
	}
}
