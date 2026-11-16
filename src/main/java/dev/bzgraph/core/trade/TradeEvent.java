package dev.bzgraph.core.trade;

/**
 * One recognised Hypixel bazaar chat message. Fields that a message doesn't carry are NaN / null.
 *
 * @param item      display name without colour codes or icon glyphs (null for a cancelled buy order)
 * @param coins     total coins in the message (after tax for sell claims)
 * @param unitPrice explicit "at/for N each" price, when the message has one
 */
public record TradeEvent(Type type, long amount, String item, double coins, double unitPrice) {
	public enum Type {
		INSTANT_SELL, INSTANT_BUY,
		SELL_SETUP, BUY_SETUP,
		SELL_FILLED, BUY_FILLED,
		SELL_CLAIMED, BUY_CLAIMED,
		SELL_CANCELLED, BUY_CANCELLED
	}
}
