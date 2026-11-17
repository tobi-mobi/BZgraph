package dev.bzgraph.core;

import java.util.Collection;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Recognises Hypixel's bazaar product page (the screen with "Buy Instantly" / "Sell Instantly" / "Create Buy
 * Order" / "Create Sell Offer") from its title and slot names, and works out which product it shows.
 */
public final class BazaarPage {
	private static final Pattern FORMATTING = Pattern.compile("§.");
	private static final String BUY_INSTANTLY = "Buy Instantly";
	private static final Set<String> OTHER_BUTTONS = Set.of("Sell Instantly", "Create Buy Order", "Create Sell Offer");
	private static final Set<String> NOT_A_PRODUCT = Set.of(BUY_INSTANTLY, "Sell Instantly", "Create Buy Order",
			"Create Sell Offer", "Go Back", "Close", "View Graphs", "Manage Orders", "");

	private BazaarPage() {
	}

	/** True if these slot names belong to a product page. */
	public static boolean isProductPage(Collection<String> slotNames) {
		boolean buy = false, other = false;
		for (String raw : slotNames) {
			String n = clean(raw);
			if (n.equals(BUY_INSTANTLY)) buy = true;
			else if (OTHER_BUTTONS.contains(n)) other = true;
		}
		return buy && other;
	}

	/**
	 * The product's display name, or null if this isn't a product page.
	 *
	 * @param title          the window title, e.g. "Diamond ➜ Enchanted Diamond"
	 * @param slotNames      display names of the container's slots (not the player inventory)
	 * @param centerSlotName display name of the product item shown on the page (fallback), may be null
	 */
	public static String productName(String title, Collection<String> slotNames, String centerSlotName) {
		if (!isProductPage(slotNames)) return null;
		String t = clean(title);
		int arrow = Math.max(t.lastIndexOf('➜'), t.lastIndexOf("->") >= 0 ? t.lastIndexOf("->") + 1 : -1);
		if (arrow >= 0 && arrow + 1 < t.length()) {
			String afterArrow = t.substring(arrow + 1).strip();
			if (!afterArrow.isEmpty()) return afterArrow;
		}
		String c = centerSlotName == null ? "" : clean(centerSlotName);
		if (!NOT_A_PRODUCT.contains(c)) return c;
		return t.isEmpty() || t.equalsIgnoreCase("Bazaar") ? null : t;
	}

	private static String clean(String s) {
		return s == null ? "" : FORMATTING.matcher(s).replaceAll("").strip();
	}
}
