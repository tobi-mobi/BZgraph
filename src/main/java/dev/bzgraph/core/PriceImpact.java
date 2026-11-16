package dev.bzgraph.core;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * Price before vs. after a moment (e.g. a patch release): the median over a window on each side.
 * Medians keep a single spike from dominating. This shows timing only — not causation.
 */
public record PriceImpact(double buyBefore, double buyAfter, double sellBefore, double sellAfter, Duration window) {
	public static PriceImpact of(List<PricePoint> ascending, Instant at, Duration window) {
		return new PriceImpact(
				median(ascending, at.minus(window), at, false, PricePoint::buy),
				median(ascending, at, at.plus(window), true, PricePoint::buy),
				median(ascending, at.minus(window), at, false, PricePoint::sell),
				median(ascending, at, at.plus(window), true, PricePoint::sell),
				window);
	}

	/** Comparison window suited to the graph range. */
	public static Duration windowFor(TimeRange range) {
		return switch (range) {
			case DAY -> Duration.ofHours(2);
			case WEEK -> Duration.ofDays(1);
			case MONTH -> Duration.ofDays(3);
			case YEAR, ALL -> Duration.ofDays(7);
		};
	}

	/** Relative change in percent, or NaN if either side has no data. */
	public static double percent(double before, double after) {
		if (Double.isNaN(before) || Double.isNaN(after) || before == 0) return Double.NaN;
		return (after - before) / before * 100.0;
	}

	public double buyPercent() {
		return percent(buyBefore, buyAfter);
	}

	public double sellPercent() {
		return percent(sellBefore, sellAfter);
	}

	/** Median of values with timestamps in [from, to) (or (from, to] if {@code afterSide}). */
	static double median(List<PricePoint> pts, Instant from, Instant to, boolean afterSide, ToDoubleFunction<PricePoint> f) {
		List<Double> vals = new ArrayList<>();
		for (PricePoint p : pts) {
			Instant t = p.timestamp();
			boolean in = afterSide
					? t.isAfter(from) && !t.isAfter(to)
					: !t.isBefore(from) && t.isBefore(to);
			if (!in) continue;
			double v = f.applyAsDouble(p);
			if (!Double.isNaN(v)) vals.add(v);
		}
		if (vals.isEmpty()) return Double.NaN;
		Collections.sort(vals);
		int n = vals.size();
		return n % 2 == 1 ? vals.get(n / 2) : (vals.get(n / 2 - 1) + vals.get(n / 2)) / 2.0;
	}
}
