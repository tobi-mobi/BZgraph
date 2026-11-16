package dev.bzgraph.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Maps SkyBlock item data to bazaar product ID candidates. The caller picks the first candidate that is
 * present in the bazaar product list; if none is, the item is not a bazaar item.
 */
public final class ItemIdMapper {
	private static final Pattern FORMATTING = Pattern.compile("§.");
	private static final Pattern LEADING_COUNT = Pattern.compile("^\\d+x\\s+");
	private static final Pattern NON_WORD = Pattern.compile("[^A-Za-z0-9 ]");
	private static final String[] ROMAN = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};

	private ItemIdMapper() {
	}

	/**
	 * @param skyblockId   the custom-data "id" (may be null, e.g. for bazaar menu display items)
	 * @param enchantments the custom-data "enchantments" compound (may be null/empty)
	 * @param displayName  plain or §-formatted item name (fallback, may be null)
	 */
	public static List<String> candidates(String skyblockId, Map<String, Integer> enchantments, String displayName) {
		Set<String> out = new LinkedHashSet<>();
		if (skyblockId != null && !skyblockId.isBlank()) {
			String id = skyblockId.trim().toUpperCase(Locale.ROOT);
			if (id.equals("ENCHANTED_BOOK") && enchantments != null && enchantments.size() == 1) {
				var e = enchantments.entrySet().iterator().next();
				out.add(enchantmentProduct(e.getKey(), e.getValue()));
			}
			out.add(id);
		}
		if (displayName != null) out.addAll(fromDisplayName(displayName));
		return new ArrayList<>(out);
	}

	/** ultimate_wise, 5 → ENCHANTMENT_ULTIMATE_WISE_5 */
	public static String enchantmentProduct(String enchant, int level) {
		return "ENCHANTMENT_" + enchant.trim().toUpperCase(Locale.ROOT) + "_" + level;
	}

	/** Picks the first candidate contained in {@code products}, or null. */
	public static String pick(List<String> candidates, Set<String> products) {
		for (String c : candidates) {
			if (products.contains(c)) return c;
		}
		return null;
	}

	/** Heuristic guesses from a display name: "Enchanted Diamond" → ENCHANTED_DIAMOND, etc. */
	static List<String> fromDisplayName(String name) {
		List<String> out = new ArrayList<>();
		String clean = FORMATTING.matcher(name).replaceAll("").trim();
		clean = LEADING_COUNT.matcher(clean).replaceFirst("");
		clean = NON_WORD.matcher(clean).replaceAll("").trim().replaceAll("\\s+", " ");
		if (clean.isEmpty()) return out;
		String[] words = clean.toUpperCase(Locale.ROOT).split(" ");

		// "Ultimate Wise V" → ENCHANTMENT_ULTIMATE_WISE_5
		if (words.length >= 2) {
			int lvl = romanToInt(words[words.length - 1]);
			if (lvl > 0) {
				out.add("ENCHANTMENT_" + String.join("_", java.util.Arrays.copyOf(words, words.length - 1)) + "_" + lvl);
			}
		}
		// "Wither Essence" → ESSENCE_WITHER
		if (words.length >= 2 && words[words.length - 1].equals("ESSENCE")) {
			out.add("ESSENCE_" + String.join("_", java.util.Arrays.copyOf(words, words.length - 1)));
		}
		out.add(String.join("_", words));
		return out;
	}

	static int romanToInt(String s) {
		for (int i = 0; i < ROMAN.length; i++) {
			if (ROMAN[i].equals(s)) return i + 1;
		}
		return -1;
	}
}
