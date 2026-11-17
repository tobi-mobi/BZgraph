package dev.bzgraph.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Mayor history from SkyCofl, cached on disk for 6 hours (a term lasts ~5 days). */
public final class MayorRepository {
	public static final URI URL = URI.create("https://sky.coflnet.com/api/mayor");
	public static final Duration TTL = Duration.ofHours(6);

	private final Function<URI, CompletableFuture<String>> fetcher;
	private final Path cacheFile;
	private final Executor io;
	private final Supplier<Instant> clock;
	private final Consumer<String> warn;
	private final AtomicBoolean started = new AtomicBoolean();
	private volatile MayorHistory current = MayorHistory.EMPTY;

	public MayorRepository(Function<URI, CompletableFuture<String>> fetcher, Path cacheFile, Executor io,
						   Supplier<Instant> clock, Consumer<String> warn) {
		this.fetcher = fetcher;
		this.cacheFile = cacheFile;
		this.io = io;
		this.clock = clock;
		this.warn = warn;
	}

	public MayorHistory current() {
		return current;
	}

	/** Loads the cache and refreshes it if stale; only the first call does anything. */
	public CompletableFuture<Void> ensure() {
		if (!started.compareAndSet(false, true)) return CompletableFuture.completedFuture(null);
		return CompletableFuture.supplyAsync(this::readCache, io).thenCompose(fetchedAt -> {
			if (fetchedAt != null && clock.get().isBefore(fetchedAt.plus(TTL))) return CompletableFuture.completedFuture(null);
			return fetcher.apply(URL).handle((body, err) -> {
				if (err != null) {
					warn.accept("Could not load mayor history: " + err);
					started.set(false); // retry next time a graph opens
					return null;
				}
				try {
					current = MayorHistory.parse(body);
					writeCache(body);
				} catch (RuntimeException e) {
					warn.accept("Mayor history response was invalid: " + e.getMessage());
				}
				return null;
			});
		});
	}

	/** @return when the cached copy was fetched, or null if there's none. */
	private Instant readCache() {
		if (cacheFile == null || !Files.isRegularFile(cacheFile)) return null;
		try {
			JsonObject o = JsonParser.parseString(Files.readString(cacheFile, StandardCharsets.UTF_8)).getAsJsonObject();
			current = MayorHistory.parse(o.get("body").getAsString());
			return Instant.ofEpochMilli(o.get("fetchedAt").getAsLong());
		} catch (Exception e) {
			return null;
		}
	}

	private void writeCache(String body) {
		if (cacheFile == null) return;
		io.execute(() -> {
			try {
				JsonObject o = new JsonObject();
				o.addProperty("fetchedAt", clock.get().toEpochMilli());
				o.addProperty("body", body);
				Files.createDirectories(cacheFile.getParent());
				Files.writeString(cacheFile, o.toString(), StandardCharsets.UTF_8);
			} catch (Exception e) {
				warn.accept("Could not cache mayor history: " + e);
			}
		});
	}
}
