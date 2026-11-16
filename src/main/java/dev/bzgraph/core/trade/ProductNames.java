package dev.bzgraph.core.trade;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps the item names used in bazaar chat ("Fine Citrine Gemstone", "Overload IV", "Fossil Essence") to bazaar
 * product IDs ({@code FINE_CITRINE_GEM}, {@code ENCHANTMENT_OVERLOAD_4}, {@code ESSENCE_FOSSIL}).
 * Built from the bazaar product list plus Hypixel's public item list (ID -> display name).
 */
public final class ProductNames {
	private static final Pattern FORMATTING = Pattern.compile("§.");
	private static final Pattern ENCHANT = Pattern.compile("ENCHANTMENT_(.+)_(\\d+)");
	private static final String[] ROMAN = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};

	private final Map<String, String> byName;

	private ProductNames(Map<String, String> byName) {
		this.byName = byName;
	}

	public static ProductNames build(Set<String> products, Map<String, String> idToName) {
		Map<String, String> m = new HashMap<>();
		Map<String, String> aliases = new HashMap<>(); // added last, so a real name always wins over an alias
		for (String id : products) {
			String name = idToName.get(id);
			if (name != null && !name.isBlank()) {
				m.putIfAbsent(TradeParser.nameKey(FORMATTING.matcher(name).replaceAll("")), id);
				continue;
			}
			Matcher e = ENCHANT.matcher(id);
			if (e.matches()) {
				int lvl = Integer.parseInt(e.group(2));
				String level = lvl >= 1 && lvl <= ROMAN.length ? ROMAN[lvl - 1] : String.valueOf(lvl);
				String base = title(e.group(1));
				m.putIfAbsent(key(base + " " + level), id);
				// Ultimate enchants are named without "Ultimate" in chat: "Legion I" = ENCHANTMENT_ULTIMATE_LEGION_1
				if (e.group(1).startsWith("ULTIMATE_")) aliases.put(key(title(e.group(1).substring(9)) + " " + level), id);
			} else if (id.startsWith("ESSENCE_")) {
				m.putIfAbsent(key(title(id.substring(8)) + " Essence"), id);
			} else if (id.startsWith("SHARD_")) {
				m.putIfAbsent(key(title(id.substring(6)) + " Shard"), id);
			} else {
				m.putIfAbsent(key(title(id)), id);
			}
		}
		aliases.forEach(m::putIfAbsent);
		return new ProductNames(m);
	}

	/** Product ID for a chat item name, or null if it isn't a known bazaar product. */
	public String idFor(String itemName) {
		return itemName == null ? null : byName.get(TradeParser.nameKey(itemName));
	}

	public int size() {
		return byName.size();
	}

	private static String key(String s) {
		return TradeParser.nameKey(s);
	}

	private static String title(String idPart) {
		StringBuilder sb = new StringBuilder();
		for (String w : idPart.toLowerCase(Locale.ROOT).split("_")) {
			if (w.isEmpty()) continue;
			if (!sb.isEmpty()) sb.append(' ');
			sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
		}
		return sb.toString();
	}
}
