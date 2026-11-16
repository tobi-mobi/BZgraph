package dev.bzgraph.core.trade;

import java.time.Instant;

/**
 * One completed bazaar trade of the player.
 *
 * @param time      when the items changed hands (fill time for offers; claim time for partial fills)
 * @param unitPrice coins per item before tax, or NaN if unknown (filled offer whose setup we never saw)
 * @param coins     total coins, or NaN if unknown
 */
public record Trade(Instant time, String item, Side side, Kind kind, long amount, double unitPrice, double coins) {
	public enum Side {SELL, BUY}

	public enum Kind {
		/** Instant sell / instant buy. */
		INSTANT,
		/** Your sell offer / buy order was completely filled. */
		OFFER,
		/** Coins/items claimed from an offer that was only partly filled (fill time unknown; this is claim time). */
		PARTIAL
	}

	public boolean hasPrice() {
		return !Double.isNaN(unitPrice);
	}

	public Trade withPrice(double unit) {
		return new Trade(time, item, side, kind, amount, unit, unit * amount);
	}
}
