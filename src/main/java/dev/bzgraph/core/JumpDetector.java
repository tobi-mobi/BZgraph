package dev.bzgraph.core;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Finds unusually large price moves in a series, so the graph can flag them and list possible causes.
 *
 * <p>Each point is compared with the median of the points in a window before it. A move counts when it's both
 * big in absolute terms ({@link #MIN_PCT}) and big for this item (several times its typical step-to-step
 * change), so calm items flag smaller moves than volatile ones. Consecutive flagged points become one jump.
 */
public final class JumpDetector {
	public static final double MIN_PCT = 8.0;
	/** The baseline always uses at least this many previous points (daily data has few points per window). */
	static final int MIN_BASELINE_POINTS = 5;
	static final double VOLATILITY_FACTOR = 6.0;

	/** A detected move: starts at {@code index}/{@code time}; {@code pct} is the peak deviation from the baseline. */
	public record Jump(int index, Instant time, double fromPrice, double toPrice, double pct) {
	}

	private JumpDetector() {
	}

	/**
	 * @param times   ascending epoch millis
	 * @param values  prices (NaN skipped)
	 * @param maxJumps keep at most this many (the largest), returned oldest first
	 */
	public static List<Jump> detect(long[] times, double[] values, int maxJumps) {
		int n = times.length;
		if (n < 6) return List.of();
		long span = times[n - 1] - times[0];
		long window = Math.max(span / 40, Duration.ofHours(2).toMillis());
		double threshold = Math.max(MIN_PCT, VOLATILITY_FACTOR * typicalStepPct(values));

		List<Jump> jumps = new ArrayList<>();
		int start = -1;
		double startBase = 0, peak = 0, peakPrice = 0;
		int windowStart = 0;
		for (int i = 0; i < n; i++) {
			while (windowStart < i && times[windowStart] < times[i] - window) windowStart++;
			double base = median(values, Math.min(windowStart, Math.max(0, i - MIN_BASELINE_POINTS)), i);
			double v = values[i];
			double dev = Double.isNaN(base) || Double.isNaN(v) || base == 0 ? 0 : (v - base) / base * 100;
			boolean flagged = Math.abs(dev) >= threshold;
			boolean sameDirection = start >= 0 && Math.signum(dev) == Math.signum(peak);
			if (flagged && (start < 0 || sameDirection)) {
				if (start < 0) {
					start = i;
					startBase = base;
					peak = dev;
					peakPrice = v;
				} else if (Math.abs(dev) > Math.abs(peak)) {
					peak = dev;
					peakPrice = v;
				}
				continue;
			}
			if (start >= 0) {
				jumps.add(new Jump(start, Instant.ofEpochMilli(times[start]), startBase, peakPrice, peak));
				start = -1;
			}
			if (flagged) { // direction changed: start a new jump here
				start = i;
				startBase = base;
				peak = dev;
				peakPrice = v;
			}
		}
		if (start >= 0) jumps.add(new Jump(start, Instant.ofEpochMilli(times[start]), startBase, peakPrice, peak));

		// keep the largest, at least span/25 apart, then return oldest first
		jumps.sort(Comparator.comparingDouble((Jump j) -> -Math.abs(j.pct())));
		long minGap = span / 25;
		List<Jump> kept = new ArrayList<>();
		for (Jump j : jumps) {
			if (kept.size() >= maxJumps) break;
			boolean tooClose = kept.stream().anyMatch(k -> Math.abs(k.time().toEpochMilli() - j.time().toEpochMilli()) < minGap);
			if (!tooClose) kept.add(j);
		}
		kept.sort(Comparator.comparing(Jump::time));
		return kept;
	}

	/** Median absolute percent change between consecutive valid points. */
	static double typicalStepPct(double[] values) {
		double[] steps = new double[values.length];
		int k = 0;
		double prev = Double.NaN;
		for (double v : values) {
			if (Double.isNaN(v)) continue;
			if (!Double.isNaN(prev) && prev != 0) steps[k++] = Math.abs((v - prev) / prev * 100);
			prev = v;
		}
		if (k == 0) return 0;
		double[] s = Arrays.copyOf(steps, k);
		Arrays.sort(s);
		return s[k / 2];
	}

	/** Median of values[from, to) ignoring NaN; NaN if fewer than 2 values. */
	static double median(double[] values, int from, int to) {
		int count = 0;
		double[] buf = new double[Math.max(0, to - from)];
		for (int i = from; i < to; i++) if (!Double.isNaN(values[i])) buf[count++] = values[i];
		if (count < 2) return Double.NaN;
		Arrays.sort(buf, 0, count);
		return count % 2 == 1 ? buf[count / 2] : (buf[count / 2 - 1] + buf[count / 2]) / 2;
	}
}
