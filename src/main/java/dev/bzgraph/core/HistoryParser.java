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
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/** Parses SkyCofl {@code /api/bazaar/{id}/history} responses. */
public final class HistoryParser {
	private HistoryParser() {
	}

	/**
	 * Parses the JSON array. Points with unparseable timestamps are skipped.
	 * The returned list keeps the response order (newest first).
	 *
	 * @throws JsonParseException if the body is not a JSON array
	 */
	public static List<PricePoint> parse(String json) {
		JsonElement root = JsonParser.parseString(json);
		if (!root.isJsonArray()) {
			throw new JsonParseException("Expected JSON array, got " + (root.isJsonNull() ? "null" : root.getClass().getSimpleName()));
		}
		JsonArray arr = root.getAsJsonArray();
		List<PricePoint> out = new ArrayList<>(arr.size());
		for (JsonElement el : arr) {
			if (!el.isJsonObject()) continue;
			JsonObject o = el.getAsJsonObject();
			Instant ts = parseTimestamp(str(o, "timestamp"));
			if (ts == null) continue;
			out.add(new PricePoint(ts, num(o, "buy"), num(o, "sell"), num(o, "buyVolume"), num(o, "sellVolume")));
		}
		return out;
	}

	/**
	 * Parses an ISO-8601 timestamp. Timestamps without zone information are treated as UTC.
	 * Fractional seconds of any precision are accepted. Returns null if unparseable.
	 */
	public static Instant parseTimestamp(String s) {
		if (s == null || s.isBlank()) return null;
		s = s.trim();
		try {
			if (s.endsWith("Z") || s.endsWith("z")) {
				return OffsetDateTime.parse(s.substring(0, s.length() - 1) + "Z").toInstant();
			}
			// Offset like +01:00 / -05:00 after the time part
			int tIdx = s.indexOf('T');
			if (tIdx > 0) {
				String time = s.substring(tIdx);
				if (time.indexOf('+') >= 0 || time.indexOf('-') >= 0) {
					return OffsetDateTime.parse(s).toInstant();
				}
			}
			return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC);
		} catch (DateTimeParseException e) {
			return null;
		}
	}

	private static String str(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? null : e.getAsString();
	}

	private static double num(JsonObject o, String key) {
		JsonElement e = o.get(key);
		if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return Double.NaN;
		try {
			return e.getAsDouble();
		} catch (NumberFormatException ex) {
			return Double.NaN;
		}
	}
}
