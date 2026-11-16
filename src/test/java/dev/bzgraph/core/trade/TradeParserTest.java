package dev.bzgraph.core.trade;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static dev.bzgraph.core.trade.TradeEvent.Type.*;
import static org.junit.jupiter.api.Assertions.*;

/** Message shapes taken from real Hypixel logs. */
class TradeParserTest {
	private static TradeEvent p(String s) {
		return TradeParser.parse(s);
	}

	@Test
	void instantSellAndBuy() {
		TradeEvent e = p("[Bazaar] Sold 80x Mithril for 400.0 coins!");
		assertEquals(INSTANT_SELL, e.type());
		assertEquals(80, e.amount());
		assertEquals("Mithril", e.item());
		assertEquals(400.0, e.coins());

		e = p("[Bazaar] Bought 3x Tungsten Key for 8,555,250 coins!");
		assertEquals(INSTANT_BUY, e.type());
		assertEquals(8_555_250, e.coins());
		assertEquals(3_250, p("[Bazaar] Sold 3,250x  Rough Aquamarine Gemstone for 1,300 coins!").amount());
	}

	@Test
	void gemstoneGlyphsAreStripped() {
		assertEquals("Fine Citrine Gemstone", p("[Bazaar] Sold 3x  Fine Citrine Gemstone for 78,000 coins!").item());
		assertEquals("Rough Amethyst Gemstone", p("[Bazaar] Sold 22,736x ❈ Rough Amethyst Gemstone for 15,915 coins!").item());
		assertEquals("Rough Ruby Gemstone", p("[Bazaar] Sold 1,912x ❤ Rough Ruby Gemstone for 1,912 coins!").item());
		assertEquals("Fine Citrine Gemstone", p("[Bazaar] Sold 32x  Fine Citrine Gemstone for 842,982 coins!").item());
	}

	@Test
	void repeatCountersAndFormattingAreIgnored() {
		assertEquals(39, p("[Bazaar] Bought 39x Ascension Rope for 15,534 coins! (2)").amount());
		assertEquals(1, p("[Bazaar] Sold 1x Suspicious Scrap for 177,360 coins! (×2)").amount());
		assertEquals("Mithril", p("§6[Bazaar] §7Sold §a80§8x §2Mithril §7for §6400.0 coins§7!").item());
	}

	@Test
	void offersClaimsAndCancels() {
		TradeEvent e = p("[Bazaar] Sell Offer Setup! 1x Tungsten Plate for 12,339,821 coins.");
		assertEquals(SELL_SETUP, e.type());
		assertEquals(12_339_821, e.coins());
		assertEquals(BUY_SETUP, p("[Bazaar] Buy Order Setup! 7,500x Fossil Essence for 15,063,000 coins.").type());

		e = p("[Bazaar] Your Sell Offer for 76x Refined Mineral was filled! [Go To Orders]");
		assertEquals(SELL_FILLED, e.type());
		assertEquals("Refined Mineral", e.item());
		assertEquals(BUY_FILLED, p("[Bazaar] Your Buy Order for 3,265x Fossil Essence was filled! [Go To Orders]").type());

		e = p("[Bazaar] Claimed 4,017,686 coins from selling 1x  Flawless Jasper Gemstone at 4,063,399 each!");
		assertEquals(SELL_CLAIMED, e.type());
		assertEquals("Flawless Jasper Gemstone", e.item());
		assertEquals(4_017_686, e.coins());
		assertEquals(4_063_399, e.unitPrice());

		e = p("[Bazaar] Claimed 3,333x Fossil Essence worth 6,693,997 coins bought for 2,008.4 each!");
		assertEquals(BUY_CLAIMED, e.type());
		assertEquals(3_333, e.amount());
		assertEquals(2_008.4, e.unitPrice());

		e = p("[Bazaar] Cancelled! Refunded 33x Glacite Amalgamation from cancelling Sell Offer! (2)");
		assertEquals(SELL_CANCELLED, e.type());
		assertEquals(33, e.amount());
		e = p("[Bazaar] Cancelled! Refunded 8,369,003 coins from cancelling Buy Order!");
		assertEquals(BUY_CANCELLED, e.type());
		assertEquals(8_369_003, e.coins());
	}

	@Test
	void nonTradesAndOtherPlayersAreRejected() {
		assertNull(p("[Bazaar] Couldn't find any buyers for Hard Stone!"));
		assertNull(p("[Bazaar] Seems like a volatile market!"));
		assertNull(p("[Bazaar] You cannot afford this!"));
		assertNull(p("This server is too laggy to use the Bazaar, sorry!"));
		assertNull(p("[SkyHanni] Bazaar buy order cancelled. Click to re-order."));
		// another player typing a fake message in chat
		assertNull(p("[346] ツ [MVP+] you_2204: [Bazaar] Buy Order Setup! 4,486x Silver Magmafish for 30,010,891 coins."));
		assertNull(p("Guild > Someone: [Bazaar] Sold 999x Enchanted Diamond for 1 coins!"));
		assertNull(p(null));
	}

	@Test
	void productNamesMapChatNamesToIds() {
		ProductNames n = ProductNames.build(
				Set.of("FINE_CITRINE_GEM", "ENCHANTMENT_OVERLOAD_4", "ESSENCE_FOSSIL", "SHARD_SCATHA", "MITHRIL_ORE", "INK_SACK:3",
						"ENCHANTMENT_ULTIMATE_LEGION_1", "ENCHANTMENT_TURBO_CARROT_1"),
				Map.of("FINE_CITRINE_GEM", "§5⸕ Fine Citrine Gemstone", "MITHRIL_ORE", "Mithril", "INK_SACK:3", "Cocoa Beans"));
		assertEquals("FINE_CITRINE_GEM", n.idFor("Fine Citrine Gemstone"));
		assertEquals("FINE_CITRINE_GEM", n.idFor("fine  citrine gemstone"));
		assertEquals("ENCHANTMENT_OVERLOAD_4", n.idFor("Overload IV"));
		assertEquals("ESSENCE_FOSSIL", n.idFor("Fossil Essence"));
		assertEquals("SHARD_SCATHA", n.idFor("Scatha Shard"));
		assertEquals("MITHRIL_ORE", n.idFor("Mithril"));
		assertEquals("INK_SACK:3", n.idFor("Cocoa Beans"));
		assertEquals("ENCHANTMENT_ULTIMATE_LEGION_1", n.idFor("Legion I"), "ultimate enchants drop the prefix in chat");
		assertEquals("ENCHANTMENT_ULTIMATE_LEGION_1", n.idFor("Ultimate Legion I"));
		assertEquals("ENCHANTMENT_TURBO_CARROT_1", n.idFor("Turbo-Carrot I"));
		assertNull(n.idFor("Hyperion"));
	}
}
