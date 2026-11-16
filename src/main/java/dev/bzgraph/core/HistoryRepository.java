package dev.bzgraph.core;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Stale-while-revalidate loading: memory LRU → disk cache → network.
 * Cached data is published to the handle immediately; if it is older than the range's TTL a background
 * refresh runs and replaces it. Concurrent loads of the same item/range share one network request.
 */
public final class HistoryRepository {
	private final HistorySource source;
	private final DiskCache disk;
	private final LruCache<String, CachedHistory> memory;
	private final Executor io;
	private final Supplier<Instant> clock;
	private final Consumer<Throwable> errorLog;
	private final Map<String, CopyOnWriteArrayList<HistoryHandle>> inFlight = new HashMap<>();

	public HistoryRepository(HistorySource source, DiskCache disk, int memoryCapacity, Executor io,
							 Supplier<Instant> clock, Consumer<Throwable> errorLog) {
		this.source = source;
		this.disk = disk;
		this.memory = new LruCache<>(memoryCapacity);
		this.io = io;
		this.clock = clock;
		this.errorLog = errorLog;
	}

	static String key(String itemId, TimeRange range) {
		return itemId + "|" + range.name();
	}

	public HistoryHandle load(String itemId, TimeRange range) {
		HistoryHandle handle = new HistoryHandle(itemId, range);
		CachedHistory mem = memory.get(key(itemId, range));
		if (mem != null) {
			handle.setData(mem);
			if (mem.isFresh(range, clock.get())) {
				handle.set(HistoryHandle.Status.READY, "");
			} else {
				refresh(handle);
			}
			return handle;
		}
		CompletableFuture.runAsync(() -> {
			CachedHistory fromDisk = disk == null ? null : disk.read(itemId, range);
			if (fromDisk != null) {
				memory.put(key(itemId, range), fromDisk);
				handle.setData(fromDisk);
				if (fromDisk.isFresh(range, clock.get())) {
					handle.set(HistoryHandle.Status.READY, "");
					return;
				}
			}
			refresh(handle);
		}, io).exceptionally(t -> {
			errorLog.accept(t);
			refresh(handle);
			return null;
		});
		return handle;
	}

	/** Forces a network refresh regardless of TTL. */
	public void refresh(HistoryHandle handle) {
		handle.startRequest();
		String key = key(handle.itemId(), handle.range());
		CopyOnWriteArrayList<HistoryHandle> waiters;
		// Lock so a handle can't join a request whose completion already iterated the waiters.
		synchronized (inFlight) {
			CopyOnWriteArrayList<HistoryHandle> existing = inFlight.get(key);
			if (existing != null) {
				existing.add(handle);
				return;
			}
			waiters = new CopyOnWriteArrayList<>();
			waiters.add(handle);
			inFlight.put(key, waiters);
		}

		Instant now = clock.get();
		HistorySource.Listener listener = secs -> waiters.forEach(h -> h.rateLimited(secs));
		CompletableFuture<List<PricePoint>> f;
		try {
			f = HistoryFetcher.fetch(source, handle.itemId(), handle.range(), now, listener);
		} catch (RuntimeException e) {
			f = CompletableFuture.failedFuture(e);
		}
		f.whenComplete((points, err) -> {
			synchronized (inFlight) {
				inFlight.remove(key);
			}
			if (err == null) {
				CachedHistory h = new CachedHistory(clock.get(), points);
				memory.put(key, h);
				if (disk != null) {
					io.execute(() -> {
						try {
							disk.write(handle.itemId(), handle.range(), h);
						} catch (Exception e) {
							errorLog.accept(e);
						}
					});
				}
				for (HistoryHandle w : waiters) {
					w.setData(h);
					w.set(HistoryHandle.Status.READY, "");
				}
			} else {
				ApiException api = ApiException.find(err);
				HistoryHandle.Status s;
				String msg;
				if (api == null) {
					errorLog.accept(err);
					s = HistoryHandle.Status.ERROR;
					msg = "Unexpected error: " + err.getClass().getSimpleName();
				} else {
					s = switch (api.kind()) {
						case OFFLINE -> HistoryHandle.Status.OFFLINE;
						case NOT_FOUND -> HistoryHandle.Status.NOT_FOUND;
						case RATE_LIMITED -> HistoryHandle.Status.RATE_LIMITED;
						default -> HistoryHandle.Status.ERROR;
					};
					msg = switch (api.kind()) {
						case OFFLINE -> "Offline - couldn't reach sky.coflnet.com";
						case NOT_FOUND -> "Item not found on the bazaar";
						case RATE_LIMITED -> "Rate limited - try again in a minute";
						default -> api.getMessage();
					};
				}
				for (HistoryHandle w : waiters) w.set(s, msg);
			}
		});
	}

	public void clearMemory() {
		memory.clear();
	}
}
