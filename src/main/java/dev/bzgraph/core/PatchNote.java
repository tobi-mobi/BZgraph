package dev.bzgraph.core;

import java.time.Instant;
import java.util.Set;

/**
 * One patch-note entry from the hosted index.
 *
 * @param items effective bazaar product IDs: autoItems ∪ items − excludeItems
 * @param url   https link to the full post, or null if missing/invalid
 */
public record PatchNote(String id, Instant date, String version, String title, String summary, String url,
						Set<String> items) {
	public PatchNote {
		items = Set.copyOf(items);
		summary = summary == null ? "" : summary;
	}

	public boolean affects(String productId) {
		return productId != null && items.contains(productId);
	}
}
