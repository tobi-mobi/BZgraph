package dev.bzgraph.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Stores histories as {@code <dir>/{ITEM_ID}_{range}.json}. All methods do blocking IO;
 * call them off the render thread.
 */
public final class DiskCache {
	private static final int FORMAT_VERSION = 1;
	private final Path dir;

	public DiskCache(Path dir) {
		this.dir = dir;
	}

	public Path dir() {
		return dir;
	}

	public Path fileFor(String itemId, TimeRange range) {
		return dir.resolve(safeName(itemId) + "_" + range.label() + ".json");
	}

	/** Item IDs may contain ':' (e.g. INK_SACK:3), which is not allowed in Windows file names. */
	static String safeName(String itemId) {
		StringBuilder sb = new StringBuilder(itemId.length());
		for (char c : itemId.toCharArray()) {
			sb.append(Character.isLetterOrDigit(c) || c == '_' || c == '-' ? c : '~');
		}
		return sb.toString();
	}

	/** Returns the cached history or null if absent/corrupt. */
	public CachedHistory read(String itemId, TimeRange range) {
		Path f = fileFor(itemId, range);
		if (!Files.isRegularFile(f)) return null;
		try {
			JsonObject root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
			if (root.get("v").getAsInt() != FORMAT_VERSION) return null;
			Instant fetchedAt = Instant.ofEpochMilli(root.get("fetchedAt").getAsLong());
			JsonArray pts = root.getAsJsonArray("points");
			List<PricePoint> points = new ArrayList<>(pts.size());
			for (JsonElement e : pts) {
				JsonArray a = e.getAsJsonArray();
				points.add(new PricePoint(Instant.ofEpochMilli(a.get(0).getAsLong()),
						d(a.get(1)), d(a.get(2)), d(a.get(3)), d(a.get(4))));
			}
			return new CachedHistory(fetchedAt, points);
		} catch (Exception e) {
			return null;
		}
	}

	public void write(String itemId, TimeRange range, CachedHistory history) throws IOException {
		Files.createDirectories(dir);
		JsonObject root = new JsonObject();
		root.addProperty("v", FORMAT_VERSION);
		root.addProperty("item", itemId);
		root.addProperty("range", range.label());
		root.addProperty("fetchedAt", history.fetchedAt().toEpochMilli());
		JsonArray pts = new JsonArray(history.points().size());
		for (PricePoint p : history.points()) {
			JsonArray a = new JsonArray(5);
			a.add(p.timestamp().toEpochMilli());
			a.add(j(p.buy()));
			a.add(j(p.sell()));
			a.add(j(p.buyVolume()));
			a.add(j(p.sellVolume()));
			pts.add(a);
		}
		root.add("points", pts);

		Path target = fileFor(itemId, range);
		Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
		Files.writeString(tmp, root.toString(), StandardCharsets.UTF_8);
		try {
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static double d(JsonElement e) {
		return e == null || e.isJsonNull() ? Double.NaN : e.getAsDouble();
	}

	private static JsonElement j(double v) {
		return Double.isNaN(v) || Double.isInfinite(v) ? JsonNull.INSTANCE : new JsonPrimitive(v);
	}
}
