package dev.bzgraph.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One patch-note entry from the hosted index.
 *
 * @param items     effective bazaar product IDs this patch affects; entries ending in {@code *} are prefixes
 *                  (e.g. {@code ENCHANTMENT_KARMA_*} matches every Karma level)
 * @param itemNotes what changed for a specific item, keyed like {@code items} (may use {@code *} prefixes)
 * @param url       https link to the full post, or null if missing/invalid
 */
public record PatchNote(String id, Instant date, String version, String title, String summary, String url,
						Set<String> items, Map<String, String> itemNotes) {
	public PatchNote {
		items = Set.copyOf(items);
		itemNotes = Map.copyOf(itemNotes);
		summary = summary == null ? "" : summary;
	}

	public PatchNote(String id, Instant date, String version, String title, String summary, String url, Set<String> items) {
		this(id, date, version, title, summary, url, items, Map.of());
	}

	public boolean affects(String productId) {
		if (productId == null) return false;
		if (items.contains(productId)) return true;
		for (String i : items) {
			if (isPrefix(i) && productId.startsWith(i.substring(0, i.length() - 1))) return true;
		}
		return false;
	}

	/** Item-specific note for {@code productId} (exact key first, then the longest matching prefix), or null. */
	public String noteFor(String productId) {
		if (productId == null || itemNotes.isEmpty()) return null;
		String exact = itemNotes.get(productId);
		if (exact != null) return exact;
		String best = null;
		int bestLen = -1;
		for (Map.Entry<String, String> e : itemNotes.entrySet()) {
			String k = e.getKey();
			if (isPrefix(k) && productId.startsWith(k.substring(0, k.length() - 1)) && k.length() > bestLen) {
				best = e.getValue();
				bestLen = k.length();
			}
		}
		return best;
	}

	/** Item notes in display order: sorted by key. */
	public List<Map.Entry<String, String>> sortedNotes() {
		List<Map.Entry<String, String>> out = new ArrayList<>(new LinkedHashMap<>(itemNotes).entrySet());
		out.sort(Map.Entry.comparingByKey());
		return out;
	}

	static boolean isPrefix(String key) {
		return key.length() > 1 && key.endsWith("*");
	}
}
