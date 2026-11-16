package dev.bzgraph.core.trade;

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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The player's recorded trades, kept forever in {@code config/bzgraph/trades.json}, per Minecraft account.
 * Also remembers which log files were imported and the time windows in which live capture was running, so a
 * trade is never counted twice (once from chat, once from the log of the same session). Thread-safe.
 */
public final class TradeStore {
	private static final int FORMAT = 1;

	private static final class Account {
		final List<Trade> trades = new ArrayList<>();
		final List<TradeLedger.OpenOrder> open = new ArrayList<>();
		/** [startMillis, endMillis] windows when this mod captured chat live. */
		final List<long[]> liveWindows = new ArrayList<>();
	}

	private final Path file;
	private final Map<String, Account> accounts = new LinkedHashMap<>();
	/** Log file key -> time up to which its trades were imported (Long.MAX_VALUE = whole file). */
	private final Map<String, Long> importedUntil = new HashMap<>();
	private long version;

	public TradeStore(Path file) {
		this.file = file;
	}

	private static String key(String account) {
		return account == null ? "?" : account.toLowerCase(Locale.ROOT);
	}

	private Account acc(String account) {
		return accounts.computeIfAbsent(key(account), k -> new Account());
	}

	/** Adds a trade, or replaces an earlier version of it (a price correction has the same time/item/amount). */
	public synchronized void put(String account, Trade t) {
		List<Trade> list = acc(account).trades;
		for (int i = list.size() - 1; i >= 0 && i >= list.size() - 500; i--) {
			Trade o = list.get(i);
			if (o.time().equals(t.time()) && o.side() == t.side() && o.kind() == t.kind() && o.amount() == t.amount()
					&& TradeParser.nameKey(o.item()).equals(TradeParser.nameKey(t.item()))) {
				list.set(i, t);
				version++;
				return;
			}
		}
		list.add(t);
		version++;
	}

	/** Changes whenever trades change (for caching). */
	public synchronized long version() {
		return version;
	}

	/** All trades of an account, oldest first. */
	public synchronized List<Trade> trades(String account) {
		Account a = accounts.get(key(account));
		if (a == null) return List.of();
		List<Trade> out = new ArrayList<>(a.trades);
		out.sort(Comparator.comparing(Trade::time));
		return out;
	}

	public synchronized int count(String account) {
		Account a = accounts.get(key(account));
		return a == null ? 0 : a.trades.size();
	}

	public synchronized List<TradeLedger.OpenOrder> openOrders(String account) {
		return new ArrayList<>(acc(account).open);
	}

	public synchronized void setOpenOrders(String account, List<TradeLedger.OpenOrder> orders) {
		Account a = acc(account);
		a.open.clear();
		a.open.addAll(orders);
	}

	/** Starts (or extends) a live-capture window. */
	public synchronized void markLive(String account, Instant sessionStart, Instant now) {
		List<long[]> w = acc(account).liveWindows;
		long s = sessionStart.toEpochMilli();
		for (long[] win : w) {
			if (win[0] == s) {
				win[1] = Math.max(win[1], now.toEpochMilli());
				return;
			}
		}
		w.add(new long[]{s, now.toEpochMilli()});
	}

	/** True if live capture was running for this account at {@code t} (so a log line at t is already recorded). */
	public synchronized boolean wasLive(String account, Instant t) {
		Account a = accounts.get(key(account));
		if (a == null) return false;
		long ms = t.toEpochMilli();
		for (long[] win : a.liveWindows) if (ms >= win[0] - 5_000 && ms <= win[1] + 5_000) return true;
		return false;
	}

	/** Epoch millis up to which a log file was imported, or Long.MIN_VALUE if never. */
	public synchronized long importedUntil(String fileKey) {
		return importedUntil.getOrDefault(fileKey, Long.MIN_VALUE);
	}

	public synchronized void setImportedUntil(String fileKey, long millis) {
		importedUntil.merge(fileKey, millis, Math::max);
	}

	// ------------------------------------------------------------------ persistence

	public synchronized void load() {
		if (file == null || !Files.isRegularFile(file)) return;
		try {
			JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			if (root.get("v").getAsInt() != FORMAT) return;
			for (Map.Entry<String, JsonElement> f : root.getAsJsonObject("imported").entrySet()) {
				importedUntil.put(f.getKey(), f.getValue().getAsLong());
			}
			for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("accounts").entrySet()) {
				Account a = acc(e.getKey());
				JsonObject o = e.getValue().getAsJsonObject();
				for (JsonElement t : o.getAsJsonArray("trades")) a.trades.add(readTrade(t.getAsJsonArray()));
				for (JsonElement t : o.getAsJsonArray("open")) a.open.add(readOrder(t.getAsJsonArray()));
				for (JsonElement t : o.getAsJsonArray("live")) {
					JsonArray w = t.getAsJsonArray();
					a.liveWindows.add(new long[]{w.get(0).getAsLong(), w.get(1).getAsLong()});
				}
			}
		} catch (Exception e) {
			// Never lose the file over a parse problem: keep a copy and start empty.
			try {
				Files.copy(file, file.resolveSibling(file.getFileName() + ".broken"), StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException ignored) {
			}
			accounts.clear();
			importedUntil.clear();
		}
	}

	public void save() throws IOException {
		if (file == null) return;
		String json;
		synchronized (this) {
			JsonObject root = new JsonObject();
			root.addProperty("v", FORMAT);
			JsonObject imp = new JsonObject();
			importedUntil.entrySet().stream().sorted(Map.Entry.comparingByKey())
					.forEach(e -> imp.addProperty(e.getKey(), e.getValue()));
			root.add("imported", imp);
			JsonObject accs = new JsonObject();
			for (Map.Entry<String, Account> e : accounts.entrySet()) {
				Account a = e.getValue();
				JsonObject o = new JsonObject();
				JsonArray ts = new JsonArray();
				a.trades.stream().sorted(Comparator.comparing(Trade::time)).forEach(t -> ts.add(writeTrade(t)));
				o.add("trades", ts);
				JsonArray os = new JsonArray();
				a.open.forEach(x -> os.add(writeOrder(x)));
				o.add("open", os);
				JsonArray lw = new JsonArray();
				for (long[] w : a.liveWindows) {
					JsonArray x = new JsonArray();
					x.add(w[0]);
					x.add(w[1]);
					lw.add(x);
				}
				o.add("live", lw);
				accs.add(e.getKey(), o);
			}
			root.add("accounts", accs);
			json = root.toString();
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

	// [time, item, side, kind, amount, unitPrice, coins]
	private static JsonArray writeTrade(Trade t) {
		JsonArray a = new JsonArray();
		a.add(t.time().toEpochMilli());
		a.add(t.item());
		a.add(t.side().name());
		a.add(t.kind().name());
		a.add(t.amount());
		a.add(num(t.unitPrice()));
		a.add(num(t.coins()));
		return a;
	}

	private static Trade readTrade(JsonArray a) {
		return new Trade(Instant.ofEpochMilli(a.get(0).getAsLong()), a.get(1).getAsString(),
				Trade.Side.valueOf(a.get(2).getAsString()), Trade.Kind.valueOf(a.get(3).getAsString()),
				a.get(4).getAsLong(), dbl(a.get(5)), dbl(a.get(6)));
	}

	// [side, item, amount, unitPrice, placedAt, filledAt]
	private static JsonArray writeOrder(TradeLedger.OpenOrder o) {
		JsonArray a = new JsonArray();
		a.add(o.side.name());
		a.add(o.item);
		a.add(o.amount);
		a.add(num(o.unitPrice));
		a.add(o.placedAt.toEpochMilli());
		a.add(o.filledAt == null ? JsonNull.INSTANCE : new JsonPrimitive(o.filledAt.toEpochMilli()));
		return a;
	}

	private static TradeLedger.OpenOrder readOrder(JsonArray a) {
		return new TradeLedger.OpenOrder(Trade.Side.valueOf(a.get(0).getAsString()), a.get(1).getAsString(),
				a.get(2).getAsLong(), dbl(a.get(3)), Instant.ofEpochMilli(a.get(4).getAsLong()),
				a.get(5).isJsonNull() ? null : Instant.ofEpochMilli(a.get(5).getAsLong()));
	}

	private static JsonElement num(double v) {
		return Double.isNaN(v) || Double.isInfinite(v) ? JsonNull.INSTANCE : new JsonPrimitive(v);
	}

	private static double dbl(JsonElement e) {
		return e == null || e.isJsonNull() ? Double.NaN : e.getAsDouble();
	}
}
