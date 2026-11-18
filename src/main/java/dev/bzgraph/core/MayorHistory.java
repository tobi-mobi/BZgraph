package dev.bzgraph.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * SkyBlock mayor terms from SkyCofl's {@code /api/mayor} (one term per SkyBlock year, ~5 days 4 hours). Mayors
 * and ministers move many prices (Cole's Mining Fiesta, Diana, Derpy...), so the graph shows who was in office.
 */
public final class MayorHistory {
	/**
	 * One term. {@code category}/{@code ministerCategory} are Hypixel's mayor keys ("mining", "farming", ...);
	 * older terms in the data have none, so they're filled in from the mayor's name.
	 */
	public record Term(Instant start, Instant end, String mayor, String category, List<String> perks,
					   String minister, String ministerCategory, String ministerPerk) {
		public boolean contains(Instant t) {
			return !t.isBefore(start) && t.isBefore(end);
		}
	}

	public static final MayorHistory EMPTY = new MayorHistory(List.of());

	private static final Pattern FORMATTING = Pattern.compile("§.");
	/** Mayor type by name, for terms recorded without a key. */
	private static final java.util.Map<String, String> TYPE_BY_NAME = java.util.Map.ofEntries(
			java.util.Map.entry("aatrox", "slayer"), java.util.Map.entry("barry", "wizard"), java.util.Map.entry("cole", "mining"),
			java.util.Map.entry("derpy", "derp"), java.util.Map.entry("diana", "pets"), java.util.Map.entry("diaz", "economist"),
			java.util.Map.entry("finnegan", "farming"), java.util.Map.entry("foxy", "events"), java.util.Map.entry("jerry", "jerry"),
			java.util.Map.entry("marina", "fishing"), java.util.Map.entry("paul", "dungeons"), java.util.Map.entry("scorpius", "shady"),
			java.util.Map.entry("aura", "aura"));
	private static final DateTimeFormatter WITH_OFFSET = DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss xxx", Locale.ROOT);
	private static final DateTimeFormatter PLAIN = DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss", Locale.ROOT);

	private final List<Term> terms;

	private MayorHistory(List<Term> terms) {
		List<Term> sorted = new ArrayList<>(terms);
		sorted.sort(Comparator.comparing(Term::start));
		this.terms = List.copyOf(sorted);
	}

	public List<Term> terms() {
		return terms;
	}

	/** The term in office at {@code t}, or null. */
	public Term at(Instant t) {
		int lo = 0, hi = terms.size() - 1;
		while (lo <= hi) {
			int mid = (lo + hi) >>> 1;
			Term m = terms.get(mid);
			if (t.isBefore(m.start())) hi = mid - 1;
			else if (!t.isBefore(m.end())) lo = mid + 1;
			else return m;
		}
		return null;
	}

	/** Terms overlapping [from, to], oldest first. */
	public List<Term> between(Instant from, Instant to) {
		List<Term> out = new ArrayList<>();
		for (Term t : terms) if (t.end().isAfter(from) && t.start().isBefore(to)) out.add(t);
		return out;
	}

	/** Parses the JSON array; entries without a winner or with unreadable dates are skipped. */
	public static MayorHistory parse(String json) {
		JsonElement root = JsonParser.parseString(json);
		if (!root.isJsonArray()) throw new JsonParseException("Mayor history must be an array");
		List<Term> out = new ArrayList<>();
		for (JsonElement el : (JsonArray) root) {
			if (!el.isJsonObject()) continue;
			JsonObject o = el.getAsJsonObject();
			Instant start = time(str(o, "start"));
			Instant end = time(str(o, "end"));
			JsonObject w = obj(o, "winner");
			if (start == null || end == null || !end.isAfter(start) || w == null || str(w, "name") == null) continue;
			List<String> perks = new ArrayList<>();
			JsonElement ps = w.get("perks");
			if (ps != null && ps.isJsonArray()) {
				for (JsonElement p : ps.getAsJsonArray()) {
					if (p.isJsonObject() && str(p.getAsJsonObject(), "name") != null) perks.add(clean(str(p.getAsJsonObject(), "name")));
				}
			}
			JsonObject min = obj(w, "minister");
			String minister = min == null ? null : clean(str(min, "name"));
			JsonObject minPerk = min == null ? null : obj(min, "perk");
			String ministerPerk = minPerk == null ? null : clean(str(minPerk, "name"));
			String mayor = clean(str(w, "name"));
			out.add(new Term(start, end, mayor, type(str(w, "key"), mayor), perks,
					minister, minister == null ? null : type(str(min, "key"), minister), ministerPerk));
		}
		return new MayorHistory(out);
	}

	/** The mayor key, or the one known for this mayor's name when the data has none. */
	static String type(String key, String name) {
		if (key != null && !key.isBlank()) return key.toLowerCase(Locale.ROOT);
		return name == null ? "" : TYPE_BY_NAME.getOrDefault(name.toLowerCase(Locale.ROOT), "");
	}

	/** "10/30/2026 23:15:00 +00:00" (start) or "11/05/2026 03:15:00" (end, UTC). */
	static Instant time(String s) {
		if (s == null) return null;
		try {
			return OffsetDateTime.parse(s.strip(), WITH_OFFSET).toInstant();
		} catch (DateTimeParseException e) {
			try {
				return LocalDateTime.parse(s.strip(), PLAIN).toInstant(ZoneOffset.UTC);
			} catch (DateTimeParseException e2) {
				return null;
			}
		}
	}

	private static String clean(String s) {
		return s == null ? null : FORMATTING.matcher(s).replaceAll("").strip();
	}

	private static String str(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? null : e.getAsString();
	}

	private static JsonObject obj(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
	}
}
