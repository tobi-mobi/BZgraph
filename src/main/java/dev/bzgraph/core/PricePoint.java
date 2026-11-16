package dev.bzgraph.core;

import java.time.Instant;

/**
 * One bazaar price sample. Missing numeric values are {@link Double#NaN}.
 *
 * <p>{@code buy}/{@code sell} are the sample's representative prices (what the graph lines show). For longer
 * ranges one sample covers a whole bucket (2 hours, a day); {@code minBuy..maxBuy} and {@code minSell..maxSell}
 * are the lowest/highest prices seen in that bucket, drawn as a band behind each line.
 */
public record PricePoint(Instant timestamp, double buy, double sell, double buyVolume, double sellVolume,
						 double minBuy, double maxBuy, double minSell, double maxSell) {

	/** A sample without a low/high range. */
	public PricePoint(Instant timestamp, double buy, double sell, double buyVolume, double sellVolume) {
		this(timestamp, buy, sell, buyVolume, sellVolume, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
	}

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
