package dev.bzgraph.core.trade;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TradeLedgerTest {
	private final TradeLedger ledger = new TradeLedger();
	private final List<Trade> out = new ArrayList<>();
	private Instant now = Instant.parse("2026-11-16T12:00:00Z");

	private void chat(String msg) {
		TradeEvent e = TradeParser.parse(msg);
		assertNotNull(e, msg);
		ledger.accept(e, now, out::add);
		now = now.plusSeconds(60);
	}

	@Test
	void instantSellIsATradeAtPricePerItem() {
		chat("[Bazaar] Sold 32x  Fine Citrine Gemstone for 842,982 coins!");
		assertEquals(1, out.size());
		Trade t = out.getFirst();
		assertEquals(Trade.Side.SELL, t.side());
		assertEquals(Trade.Kind.INSTANT, t.kind());
		assertEquals(842_982 / 32.0, t.unitPrice(), 1e-9);
	}

	@Test
	void placingAndCancellingAnOfferRecordsNothing() {
		chat("[Bazaar] Sell Offer Setup! 1x Tungsten Plate for 12,339,821 coins.");
		chat("[Bazaar] Cancelled! Refunded 1x Tungsten Plate from cancelling Sell Offer!");
		assertTrue(out.isEmpty());
		assertTrue(ledger.openOrders().isEmpty());
	}

	@Test
	void undercutAndRelistUsesTheNewPrice() {
		chat("[Bazaar] Sell Offer Setup! 2x  Flawless Aquamarine Gemstone for 5,728,504 coins.");
		chat("[Bazaar] Cancelled! Refunded 2x  Flawless Aquamarine Gemstone from cancelling Sell Offer!"); // undercut
		chat("[Bazaar] Sell Offer Setup! 2x  Flawless Aquamarine Gemstone for 5,600,000 coins.");
		Instant fillTime = now;
		chat("[Bazaar] Your Sell Offer for 2x  Flawless Aquamarine Gemstone was filled! [Go To Orders]");
		assertEquals(1, out.size());
		Trade t = out.getFirst();
		assertEquals(Trade.Kind.OFFER, t.kind());
		assertEquals(fillTime, t.time());
		assertEquals(2_800_000, t.unitPrice(), 1e-6);
	}

	@Test
	void claimCorrectsTheAfterTaxSetupPrice() {
		chat("[Bazaar] Sell Offer Setup! 1x  Flawless Jasper Gemstone for 4,017,686 coins.");
		Instant fillTime = now;
		chat("[Bazaar] Your Sell Offer for 1x  Flawless Jasper Gemstone was filled! [Go To Orders]");
		chat("[Bazaar] Claimed 4,017,686 coins from selling 1x  Flawless Jasper Gemstone at 4,063,399 each!");
		assertEquals(2, out.size(), "fill, then corrected fill");
		Trade fixed = out.getLast();
		assertEquals(fillTime, fixed.time(), "correction keeps the fill time so the store replaces it");
		assertEquals(4_063_399, fixed.unitPrice());
		assertTrue(ledger.openOrders().isEmpty());
	}

	@Test
	void fillWithoutKnownSetupGetsPriceFromClaim() {
		chat("[Bazaar] Your Sell Offer for 76x Refined Mineral was filled! [Go To Orders]");
		assertFalse(out.getFirst().hasPrice());
		chat("[Bazaar] Claimed 1,000,000 coins from selling 76x Refined Mineral at 13,300 each!");
		assertEquals(13_300, out.getLast().unitPrice());
		assertEquals(out.getFirst().time(), out.getLast().time());
	}

	@Test
	void partialClaimIsAPartialTradeAndShrinksTheOffer() {
		chat("[Bazaar] Sell Offer Setup! 100x Enchanted Diamond for 128,000 coins.");
		Instant claimTime = now;
		chat("[Bazaar] Claimed 51,000 coins from selling 40x Enchanted Diamond at 1,290 each!");
		assertEquals(1, out.size());
		assertEquals(Trade.Kind.PARTIAL, out.getFirst().kind());
		assertEquals(claimTime, out.getFirst().time());
		assertEquals(60, ledger.openOrders().getFirst().amount);
		chat("[Bazaar] Cancelled! Refunded 60x Enchanted Diamond from cancelling Sell Offer!");
		assertTrue(ledger.openOrders().isEmpty());
		assertEquals(1, out.size());
	}

	@Test
	void buyOrderFlow() {
		chat("[Bazaar] Buy Order Setup! 3,265x Fossil Essence for 6,557,000 coins.");
		chat("[Bazaar] Your Buy Order for 3,265x Fossil Essence was filled! [Go To Orders]");
		chat("[Bazaar] Claimed 3,265x Fossil Essence worth 6,557,000 coins bought for 2,008.3 each!");
		assertEquals(Trade.Side.BUY, out.getFirst().side());
		assertEquals(6_557_000 / 3265.0, out.getFirst().unitPrice(), 1e-6);
		assertTrue(ledger.openOrders().isEmpty());
	}

	@Test
	void cancelledBuyOrderMatchedByRefundValue() {
		chat("[Bazaar] Buy Order Setup! 7,500x Fossil Essence for 15,063,000 coins.");
		chat("[Bazaar] Buy Order Setup! 4,167x Fossil Essence for 8,369,003 coins.");
		chat("[Bazaar] Cancelled! Refunded 8,369,003 coins from cancelling Buy Order!");
		assertEquals(1, ledger.openOrders().size());
		assertEquals(7_500, ledger.openOrders().getFirst().amount);
		assertTrue(out.isEmpty());
	}

	@Test
	void staleOffersAreDropped() {
		chat("[Bazaar] Sell Offer Setup! 1x Tungsten Plate for 12,339,821 coins.");
		now = now.plus(TradeLedger.STALE).plusSeconds(10);
		chat("[Bazaar] Sold 1x Mithril for 5 coins!");
		assertTrue(ledger.openOrders().isEmpty());
	}
}
