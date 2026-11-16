package dev.bzgraph.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Parsed patch index; patches sorted by date ascending. */
public record PatchIndex(Instant updated, List<PatchNote> patches) {
	public static final int SUPPORTED_VERSION = 1;
	public static final PatchIndex EMPTY = new PatchIndex(null, List.of());

	public PatchIndex {
		List<PatchNote> sorted = new ArrayList<>(patches);
		sorted.sort(Comparator.comparing(PatchNote::date).thenComparing(PatchNote::id));
		patches = List.copyOf(sorted);
	}

	/** Patches dated within [from, to] (inclusive), ascending. */
	public List<PatchNote> between(Instant from, Instant to) {
		List<PatchNote> out = new ArrayList<>();
		for (PatchNote p : patches) {
			if (!p.date().isBefore(from) && !p.date().isAfter(to)) out.add(p);
		}
		return out;
	}

	public List<PatchNote> affecting(String productId) {
		return patches.stream().filter(p -> p.affects(productId)).toList();
	}
	/** Case-insensitive search over title, summary, version and item IDs. Newest first. */
	public List<PatchNote> search(String query, String onlyProductId) {
		String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
		List<PatchNote> out = new ArrayList<>();
		for (int i = patches.size() - 1; i >= 0; i--) {
			PatchNote p = patches.get(i);
			if (onlyProductId != null && !p.affects(onlyProductId)) continue;
			if (!q.isEmpty() && !matches(p, q)) continue;
			out.add(p);
		}
		return out;
	}

	private static boolean matches(PatchNote p, String q) {
		if (p.title().toLowerCase(Locale.ROOT).contains(q)) return true;
		if (p.summary().toLowerCase(Locale.ROOT).contains(q)) return true;
		if (p.version() != null && p.version().toLowerCase(Locale.ROOT).contains(q)) return true;
		String qid = q.replace(' ', '_');
		for (String id : p.items()) if (id.toLowerCase(Locale.ROOT).contains(qid)) return true;
		for (String note : p.itemNotes().values()) if (note.toLowerCase(Locale.ROOT).contains(q)) return true;
		return false;
	}

	/**
	 * Parses the hosted index JSON. Invalid entries are skipped rather than failing the whole index.
	 *
	 * @throws JsonParseException if the document itself is unusable or has an unsupported version
	 */
	public static PatchIndex parse(String json) {
		JsonElement root = JsonParser.parseString(json);
		if (!root.isJsonObject()) throw new JsonParseException("Patch index must be a JSON object");
		JsonObject o = root.getAsJsonObject();
		int version = o.has("version") && o.get("version").isJsonPrimitive() ? o.get("version").getAsInt() : -1;
		if (version != SUPPORTED_VERSION) throw new JsonParseException("Unsupported patch index version " + version);
		Instant updated = HistoryParser.parseTimestamp(str(o, "updated"));
		JsonElement arr = o.get("patches");
		if (arr == null || !arr.isJsonArray()) throw new JsonParseException("Patch index has no patches array");

		List<PatchNote> out = new ArrayList<>();
		Set<String> seenIds = new HashSet<>();
		for (JsonElement el : arr.getAsJsonArray()) {
			if (!el.isJsonObject()) continue;
			JsonObject p = el.getAsJsonObject();
			String id = str(p, "id");
			String title = str(p, "title");
			Instant date = HistoryParser.parseTimestamp(str(p, "date"));
			if (id == null || title == null || date == null || !seenIds.add(id)) continue;
			String url = str(p, "url");
			if (url != null && !url.startsWith("https://")) url = null;
			// An entry with an "itemNotes" object (even empty) has been curated by hand: only the items it
			// names are relevant, and the automatic name matches in "autoItems" are ignored.
			Map<String, String> notes = notes(p);
			Set<String> items = new HashSet<>(notes == null ? strings(p, "autoItems") : notes.keySet());
			items.addAll(strings(p, "items"));
			items.removeAll(strings(p, "excludeItems"));
			out.add(new PatchNote(id, date, str(p, "version"), title.strip(), strOr(p, "summary").strip(), url, items,
					notes == null ? Map.of() : notes));
		}
		return new PatchIndex(updated, out);
	}

	private static String str(JsonObject o, String key) {
		JsonElement e = o.get(key);
		if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return null;
		String s = e.getAsString();
		return s.isBlank() ? null : s;
	}

	private static String strOr(JsonObject o, String key) {
		String s = str(o, key);
		return s == null ? "" : s;
	}

	/** The "itemNotes" object with upper-cased keys and blank notes dropped, or null if absent. */
	private static Map<String, String> notes(JsonObject o) {
		JsonElement e = o.get("itemNotes");
		if (e == null || !e.isJsonObject()) return null;
		Map<String, String> out = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> n : e.getAsJsonObject().entrySet()) {
			String key = n.getKey().trim().toUpperCase(Locale.ROOT);
			JsonElement v = n.getValue();
			if (key.isEmpty() || v == null || !v.isJsonPrimitive()) continue;
			String note = v.getAsString().strip();
			if (!note.isEmpty()) out.put(key, note);
		}
		return out;
	}

	private static List<String> strings(JsonObject o, String key) {
		JsonElement e = o.get(key);
		if (e == null || !e.isJsonArray()) return List.of();
		List<String> out = new ArrayList<>();
		for (JsonElement x : (JsonArray) e) {
			if (x.isJsonPrimitive()) out.add(x.getAsString().trim().toUpperCase(Locale.ROOT));
		}
		return out;
	}
}
