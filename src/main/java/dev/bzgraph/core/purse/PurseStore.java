package dev.bzgraph.core.purse;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Purse history per Minecraft account and SkyBlock profile, kept forever in {@code config/bzgraph/purse.json}.
 * Points older than two weeks are thinned to the first/lowest/highest/last value of each hour. Thread-safe.
 */
public final class PurseStore {
	private static final int FORMAT = 1;
	static final Duration KEEP_FULL = Duration.ofDays(14);
	private static final long HOUR = 3_600_000L;

	/** Purse value from {@code time} on (a step). */
	public record Point(long time, double coins) {
	}

	private final Path file;
	/** "account|profile" -> points, oldest first. */
	private final Map<String, List<Point>> series = new LinkedHashMap<>();
	/** account -> last profile seen (used until the next profile message after a restart). */
	private final Map<String, String> lastProfile = new HashMap<>();
	private boolean dirty;

	public PurseStore(Path file) {
		this.file = file;
	}

	static String key(String account, String profile) {
		return (account == null ? "?" : account.toLowerCase(Locale.ROOT)) + "|" + (profile == null ? "?" : profile);
	}

	public synchronized void add(String account, String profile, long time, double coins) {
		List<Point> l = series.computeIfAbsent(key(account, profile), k -> new ArrayList<>());
		if (!l.isEmpty() && l.getLast().time() > time) return; // clock went backwards; keep it monotonic
		l.add(new Point(time, coins));
		dirty = true;
	}

	public synchronized Point last(String account, String profile) {
		List<Point> l = series.get(key(account, profile));
		return l == null || l.isEmpty() ? null : l.getLast();
	}

	public synchronized List<Point> points(String account, String profile) {
		List<Point> l = series.get(key(account, profile));
		return l == null ? List.of() : List.copyOf(l);
	}

	/** Profiles with purse data for this account, most recently updated first. */
	public synchronized List<String> profiles(String account) {
		String prefix = (account == null ? "?" : account.toLowerCase(Locale.ROOT)) + "|";
		List<Map.Entry<String, List<Point>>> es = new ArrayList<>();
		for (Map.Entry<String, List<Point>> e : series.entrySet()) {
			if (e.getKey().startsWith(prefix) && !e.getValue().isEmpty()) es.add(e);
		}
		es.sort((a, b) -> Long.compare(b.getValue().getLast().time(), a.getValue().getLast().time()));
		List<String> out = new ArrayList<>();
		for (Map.Entry<String, List<Point>> e : es) out.add(e.getKey().substring(prefix.length()));
		return out;
	}

	public synchronized String lastProfile(String account) {
		return lastProfile.get(account == null ? "?" : account.toLowerCase(Locale.ROOT));
	}

	public synchronized void setLastProfile(String account, String profile) {
		String k = account == null ? "?" : account.toLowerCase(Locale.ROOT);
		if (profile != null && !profile.equals(lastProfile.put(k, profile))) dirty = true;
	}

	/** Thins points older than {@link #KEEP_FULL}: per hour keep the first, lowest, highest and last value. */
	public synchronized void compact(long now) {
		long cutoff = now - KEEP_FULL.toMillis();
		for (Map.Entry<String, List<Point>> e : series.entrySet()) {
			List<Point> thinned = thin(e.getValue(), cutoff);
			if (thinned.size() != e.getValue().size()) {
				e.setValue(thinned);
				dirty = true;
			}
		}
	}

	static List<Point> thin(List<Point> pts, long cutoff) {
		List<Point> out = new ArrayList<>(pts.size());
		int i = 0;
		while (i < pts.size() && pts.get(i).time() < cutoff) {
			long hour = Math.floorDiv(pts.get(i).time(), HOUR);
			int j = i;
			int lo = i, hi = i;
			while (j < pts.size() && pts.get(j).time() < cutoff && Math.floorDiv(pts.get(j).time(), HOUR) == hour) {
				if (pts.get(j).coins() < pts.get(lo).coins()) lo = j;
				if (pts.get(j).coins() > pts.get(hi).coins()) hi = j;
				j++;
			}
			int last = j - 1;
			int[] keep = {i, Math.min(lo, hi), Math.max(lo, hi), last};
			int prev = -1;
			for (int k : keep) {
				if (k != prev) out.add(pts.get(k));
				prev = k;
			}
			i = j;
		}
		out.addAll(pts.subList(i, pts.size()));
		return out;
	}

	// ------------------------------------------------------------------ persistence

	public synchronized void load() {
		if (file == null || !Files.isRegularFile(file)) return;
		try {
			JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			if (root.get("v").getAsInt() != FORMAT) return;
			for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("lastProfile").entrySet()) {
				lastProfile.put(e.getKey(), e.getValue().getAsString());
			}
			for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("series").entrySet()) {
				List<Point> l = new ArrayList<>();
				// flat [t0, v0, t1, v1, ...] keeps the file small
				JsonArray a = e.getValue().getAsJsonArray();
				for (int i = 0; i + 1 < a.size(); i += 2) l.add(new Point(a.get(i).getAsLong(), a.get(i + 1).getAsDouble()));
				series.put(e.getKey(), l);
			}
		} catch (Exception e) {
			try {
				Files.copy(file, file.resolveSibling(file.getFileName() + ".broken"), StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException ignored) {
			}
			series.clear();
			lastProfile.clear();
		}
	}

	public void saveIfDirty() throws IOException {
		String json;
		synchronized (this) {
			if (!dirty || file == null) return;
			JsonObject root = new JsonObject();
			root.addProperty("v", FORMAT);
			JsonObject lp = new JsonObject();
			lastProfile.forEach(lp::addProperty);
			root.add("lastProfile", lp);
			JsonObject s = new JsonObject();
			for (Map.Entry<String, List<Point>> e : series.entrySet()) {
				JsonArray a = new JsonArray();
				for (Point p : e.getValue()) {
					a.add(p.time());
					a.add(p.coins() == Math.rint(p.coins()) ? (Number) (long) p.coins() : (Number) p.coins());
				}
				s.add(e.getKey(), a);
			}
			root.add("series", s);
			json = root.toString();
			dirty = false;
		}
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, json, StandardCharsets.UTF_8);
		try {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** For tests / the screen: points as of an instant. */
	public static double valueAt(List<Point> pts, Instant t) {
		long ms = t.toEpochMilli();
		double v = Double.NaN;
		for (Point p : pts) {
			if (p.time() > ms) break;
			v = p.coins();
		}
		return v;
	}
}
