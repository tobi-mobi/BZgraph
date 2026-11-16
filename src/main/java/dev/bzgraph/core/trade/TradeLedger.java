package dev.bzgraph.core.trade;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Turns bazaar chat events into completed trades.
 *
 * <ul>
 *   <li>Instant sells/buys are trades right away.</li>
 *   <li>Placing an offer only remembers it (price per item). "Your Sell Offer ... was filled!" has no price, so
 *       it's matched to the newest open offer for that item (same amount preferred).</li>
 *   <li>Cancelling (e.g. after being undercut) removes the open offer and produces nothing, so a relisted
 *       offer's fill is matched to the new price.</li>
 *   <li>A claim confirms the exact price. A claim with no matching fill is a partly filled offer and becomes a
 *       {@link Trade.Kind#PARTIAL} trade at claim time.</li>
 *   <li>A fill whose offer was placed before tracking began has no price until its claim arrives; the trade is
 *       then re-emitted with the price (same time, so the store replaces it).</li>
 * </ul>
 */
public final class TradeLedger {
	/** Open offers older than this are assumed gone (cancelled while untracked, etc.). */
	static final Duration STALE = Duration.ofDays(60);
	static final int MAX_OPEN = 200;

	/** An offer we saw being placed and that hasn't been fully claimed or cancelled yet. */
	public static final class OpenOrder {
		public final Trade.Side side;
		public final String item;
		public long amount;
		public final double unitPrice;
		public final Instant placedAt;
		/** Time of the "was filled" message, if any (then only the claim is still missing). */
		public Instant filledAt;

		public OpenOrder(Trade.Side side, String item, long amount, double unitPrice, Instant placedAt, Instant filledAt) {
			this.side = side;
			this.item = item;
			this.amount = amount;
			this.unitPrice = unitPrice;
			this.placedAt = placedAt;
			this.filledAt = filledAt;
		}
	}

	/** A fill we recorded without a price, waiting for its claim. */
	private record Unpriced(Trade trade) {
	}

	private final List<OpenOrder> open = new ArrayList<>();
	private final List<Unpriced> unpriced = new ArrayList<>();

	public List<OpenOrder> openOrders() {
		return open;
	}

	public void restore(List<OpenOrder> orders) {
		open.clear();
		open.addAll(orders);
	}

	/**
	 * Applies one event that happened at {@code at}. New or corrected trades are passed to {@code out}
	 * (a corrected trade has the same time/item/side/kind/amount as the one it replaces).
	 */
	public void accept(TradeEvent e, Instant at, Consumer<Trade> out) {
		prune(at);
		switch (e.type()) {
			case INSTANT_SELL -> out.accept(instant(e, at, Trade.Side.SELL));
			case INSTANT_BUY -> out.accept(instant(e, at, Trade.Side.BUY));
			case SELL_SETUP -> place(Trade.Side.SELL, e, at);
			case BUY_SETUP -> place(Trade.Side.BUY, e, at);
			case SELL_FILLED -> filled(Trade.Side.SELL, e, at, out);
			case BUY_FILLED -> filled(Trade.Side.BUY, e, at, out);
			case SELL_CLAIMED -> claimed(Trade.Side.SELL, e, at, out);
			case BUY_CLAIMED -> claimed(Trade.Side.BUY, e, at, out);
			case SELL_CANCELLED -> cancelSell(e);
			case BUY_CANCELLED -> cancelBuy(e);
		}
	}

	private static Trade instant(TradeEvent e, Instant at, Trade.Side side) {
		return new Trade(at, e.item(), side, Trade.Kind.INSTANT, e.amount(), e.coins() / e.amount(), e.coins());
	}

	private void place(Trade.Side side, TradeEvent e, Instant at) {
		open.add(new OpenOrder(side, e.item(), e.amount(), e.coins() / e.amount(), at, null));
		while (open.size() > MAX_OPEN) open.removeFirst();
	}

	private static boolean sameItem(String a, String b) {
		return TradeParser.nameKey(a).equals(TradeParser.nameKey(b));
	}

	/** Newest open order for side+item, preferring an exact amount; optionally only unfilled ones. */
	private OpenOrder find(Trade.Side side, String item, long amount, boolean unfilledOnly, boolean filledOnly) {
		OpenOrder any = null;
		for (int i = open.size() - 1; i >= 0; i--) {
			OpenOrder o = open.get(i);
			if (o.side != side || !sameItem(o.item, item)) continue;
			if (unfilledOnly && o.filledAt != null) continue;
			if (filledOnly && o.filledAt == null) continue;
			if (o.amount == amount) return o;
			if (any == null) any = o;
		}
		return any;
	}

	private void filled(Trade.Side side, TradeEvent e, Instant at, Consumer<Trade> out) {
		OpenOrder o = find(side, e.item(), e.amount(), true, false);
		if (o != null) {
			o.filledAt = at;
			out.accept(new Trade(at, e.item(), side, Trade.Kind.OFFER, e.amount(), o.unitPrice, o.unitPrice * e.amount()));
		} else {
			Trade t = new Trade(at, e.item(), side, Trade.Kind.OFFER, e.amount(), Double.NaN, Double.NaN);
			unpriced.add(new Unpriced(t));
			out.accept(t);
		}
	}

	private void claimed(Trade.Side side, TradeEvent e, Instant at, Consumer<Trade> out) {
		double unit = e.unitPrice();
		// 1) a fill we couldn't price yet
		for (int i = unpriced.size() - 1; i >= 0; i--) {
			Trade t = unpriced.get(i).trade();
			if (t.side() == side && t.amount() == e.amount() && sameItem(t.item(), e.item())) {
				unpriced.remove(i);
				out.accept(t.withPrice(unit));
				return;
			}
		}
		// 2) a filled offer: the claim completes it; correct the price if the setup price was off
		OpenOrder filled = find(side, e.item(), e.amount(), false, true);
		if (filled != null && filled.amount == e.amount()) {
			open.remove(filled);
			if (Math.abs(filled.unitPrice - unit) > Math.max(0.01, unit * 0.001)) {
				out.accept(new Trade(filled.filledAt, filled.item, side, Trade.Kind.OFFER, e.amount(), unit, unit * e.amount()));
			}
			return;
		}
		// 3) no fill message: partly filled offer (claimed while still open, or before cancelling)
		out.accept(new Trade(at, e.item(), side, Trade.Kind.PARTIAL, e.amount(), unit, unit * e.amount()));
		OpenOrder o = find(side, e.item(), e.amount(), true, false);
		if (o != null) {
			o.amount -= e.amount();
			if (o.amount <= 0) open.remove(o);
		}
	}

	private void cancelSell(TradeEvent e) {
		OpenOrder o = find(Trade.Side.SELL, e.item(), e.amount(), true, false);
		if (o != null) open.remove(o);
	}

	/** "Refunded N coins from cancelling Buy Order!" has no item: match by remaining value, else newest. */
	private void cancelBuy(TradeEvent e) {
		OpenOrder best = null;
		for (int i = open.size() - 1; i >= 0; i--) {
			OpenOrder o = open.get(i);
			if (o.side != Trade.Side.BUY || o.filledAt != null) continue;
			if (Math.abs(o.unitPrice * o.amount - e.coins()) <= Math.max(1, e.coins() * 0.01)) {
				best = o;
				break;
			}
			if (best == null) best = o;
		}
		if (best != null) open.remove(best);
	}

	private void prune(Instant now) {
		open.removeIf(o -> o.placedAt.isBefore(now.minus(STALE)));
		unpriced.removeIf(u -> u.trade().time().isBefore(now.minus(STALE)));
	}
}
