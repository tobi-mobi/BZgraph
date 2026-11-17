package dev.bzgraph.core.trade;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Ties trade tracking together: live chat capture, log import, persistence and per-item lookup.
 * All methods are thread-safe; {@link #importFolder} does blocking IO and belongs on a background thread.
 */
public final class TradeService {
	public record ImportResult(int files, int trades, int skippedAsLive) {
	}

	private final TradeStore store;
	private final Supplier<Instant> clock;
	private final Consumer<Throwable> errorLog;
	private final Instant sessionStart;
	private final Map<String, TradeLedger> ledgers = new HashMap<>();
	private volatile ProductNames names;
	private volatile boolean dirty;

	private record CacheKey(String account, String productId, long version, ProductNames names) {
	}

	private volatile CacheKey cacheKey;
	private volatile List<Trade> cache = List.of();

	public TradeService(TradeStore store, Supplier<Instant> clock, Consumer<Throwable> errorLog) {
		this.store = store;
		this.clock = clock;
		this.errorLog = errorLog;
		this.sessionStart = clock.get();
	}

	public TradeStore store() {
		return store;
	}

	public void setNames(ProductNames names) {
		this.names = names;
	}

	public boolean hasNames() {
		return names != null;
	}

	/** The chat-name lookup, or null until loaded. */
	public ProductNames names() {
		return names;
	}

	private synchronized TradeLedger ledger(String account) {
		return ledgers.computeIfAbsent(account.toLowerCase(java.util.Locale.ROOT), k -> {
			TradeLedger l = new TradeLedger();
			l.restore(store.openOrders(account));
			return l;
		});
	}

	/** Handles one incoming chat message. Returns true if it was a bazaar trade message. */
	public boolean onChat(String account, String message) {
		TradeEvent e = TradeParser.parse(message);
		if (e == null) return false;
		Instant now = clock.get();
		synchronized (this) {
			TradeLedger l = ledger(account);
			l.accept(e, now, t -> store.put(account, t));
			store.setOpenOrders(account, new ArrayList<>(l.openOrders()));
			store.markLive(account, sessionStart, now);
		}
		dirty = true;
		return true;
	}

	/** Saves if something changed since the last save. */
	public void saveIfDirty() {
		if (!dirty) return;
		dirty = false;
		try {
			store.save();
		} catch (IOException e) {
			dirty = true;
			errorLog.accept(e);
		}
	}

	/**
	 * Imports trades from a Minecraft logs folder. Each file is only read past the point already imported, and
	 * lines from sessions this mod captured live are skipped, so nothing is counted twice.
	 */
	public ImportResult importFolder(Path logsDir, String fallbackAccount, ZoneId zone, boolean includeLatest)
			throws IOException {
		int files = 0, trades = 0, skipped = 0;
		Map<String, TradeLedger> importLedgers = new HashMap<>();
		for (LogImporter.LogFile f : LogImporter.listFiles(logsDir, includeLatest)) {
			long until = store.importedUntil(f.key());
			if (until == Long.MAX_VALUE) continue;
			List<LogImporter.LogEvent> events;
			try {
				events = LogImporter.read(f, zone, fallbackAccount);
			} catch (IOException | RuntimeException e) {
				errorLog.accept(e);
				continue; // unreadable file: skip, try again next time
			}
			files++;
			long last = until;
			for (LogImporter.LogEvent le : events) {
				long ms = le.time().toEpochMilli();
				if (ms <= until) continue;
				last = Math.max(last, ms);
				if (store.wasLive(le.account(), le.time())) {
					skipped++;
					continue;
				}
				int[] n = {0};
				TradeLedger l = importLedgers.computeIfAbsent(le.account().toLowerCase(java.util.Locale.ROOT), k -> new TradeLedger());
				l.accept(le.event(), le.time(), t -> {
					store.put(le.account(), t);
					n[0]++;
				});
				trades += n[0];
			}
			// latest.log can still grow: remember how far we got. Rotated .gz files are final.
			store.setImportedUntil(f.key(), f.startDate() == null ? last : Long.MAX_VALUE);
		}
		// Offers still open at the end of the logs may be filled later: hand them to the live ledgers.
		synchronized (this) {
			for (Map.Entry<String, TradeLedger> e : importLedgers.entrySet()) {
				List<TradeLedger.OpenOrder> merged = new ArrayList<>(store.openOrders(e.getKey()));
				for (TradeLedger.OpenOrder o : e.getValue().openOrders()) {
					boolean dup = merged.stream().anyMatch(x -> x.side == o.side && x.amount == o.amount
							&& x.placedAt.equals(o.placedAt) && TradeParser.nameKey(x.item).equals(TradeParser.nameKey(o.item)));
					if (!dup) merged.add(o);
				}
				store.setOpenOrders(e.getKey(), merged);
				TradeLedger live = ledgers.get(e.getKey());
				if (live != null) live.restore(merged);
			}
		}
		dirty = true;
		saveIfDirty();
		return new ImportResult(files, trades, skipped);
	}

	/** The account's trades of one bazaar product, oldest first (empty until item names are loaded). */
	public List<Trade> forProduct(String account, String productId) {
		ProductNames n = names;
		if (n == null || productId == null || account == null) return List.of();
		CacheKey key = new CacheKey(account, productId, store.version(), n);
		if (Objects.equals(key, cacheKey)) return cache;
		List<Trade> out = new ArrayList<>();
		for (Trade t : store.trades(account)) if (productId.equals(n.idFor(t.item()))) out.add(t);
		List<Trade> result = List.copyOf(out);
		cache = result;
		cacheKey = key;
		return result;
	}
}
