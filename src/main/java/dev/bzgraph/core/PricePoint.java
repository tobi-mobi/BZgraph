package dev.bzgraph.core;

import java.time.Instant;

/**
 * One bazaar price sample. Missing numeric values are {@link Double#NaN}.
 */
public record PricePoint(Instant timestamp, double buy, double sell, double buyVolume, double sellVolume) {
	public boolean hasBuy() {
		return !Double.isNaN(buy);
	}

	public boolean hasSell() {
		return !Double.isNaN(sell);
	}

	/** Combined traded volume, NaN-safe. */
	public double volume() {
		double v = 0;
		if (!Double.isNaN(buyVolume)) v += buyVolume;
		if (!Double.isNaN(sellVolume)) v += sellVolume;
		return v;
	}
}
