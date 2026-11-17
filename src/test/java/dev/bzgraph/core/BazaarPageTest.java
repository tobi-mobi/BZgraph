package dev.bzgraph.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BazaarPageTest {
	private static final List<String> PRODUCT_SLOTS = List.of("", "§aBuy Instantly", "§6Sell Instantly", "",
			"§aEnchanted Diamond", "", "§aCreate Buy Order", "§6Create Sell Offer", "§aGo Back");

	@Test
	void productPageFromTitle() {
		assertEquals("Enchanted Diamond", BazaarPage.productName("Diamond ➜ Enchanted Diamond", PRODUCT_SLOTS, "§aEnchanted Diamond"));
		assertEquals("Enchanted Diamond", BazaarPage.productName("§8Diamond ➜ §8Enchanted Diamond", PRODUCT_SLOTS, null));
		assertEquals("Fine Citrine Gemstone", BazaarPage.productName("Gemstones -> Fine Citrine Gemstone", PRODUCT_SLOTS, null));
	}

	@Test
	void fallsBackToTheProductSlot() {
		assertEquals("Enchanted Diamond", BazaarPage.productName("Bazaar", PRODUCT_SLOTS, "§aEnchanted Diamond"));
		assertEquals("Enchanted Diamond", BazaarPage.productName("Enchanted Diamond", PRODUCT_SLOTS, "§aGo Back"));
		assertNull(BazaarPage.productName("Bazaar", PRODUCT_SLOTS, "§aGo Back"));
	}

	@Test
	void otherBazaarScreensAreNotProductPages() {
		// category / sub-category pages: lots of items but no Buy/Sell Instantly buttons
		assertNull(BazaarPage.productName("Bazaar ➜ Mining", List.of("§aDiamond", "§aEnchanted Diamond", "§aGo Back"), "§aDiamond"));
		// a random chest with an item that happens to be called "Buy Instantly" alone
		assertFalse(BazaarPage.isProductPage(List.of("Buy Instantly", "Stone")));
		assertTrue(BazaarPage.isProductPage(List.of("Buy Instantly", "Create Buy Order")));
	}
}
