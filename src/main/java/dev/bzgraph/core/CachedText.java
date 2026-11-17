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
import java.util.function.Function;

/** A rarely changing text resource (e.g. Hypixel's collections list) cached on disk for a fixed time. */
public final class CachedText {
	private final Function<URI, CompletableFuture<String>> fetcher;
	private final Path file;
	private final Duration ttl;
	private final Executor io;

	public CachedText(Function<URI, CompletableFuture<String>> fetcher, Path file, Duration ttl, Executor io) {
		this.fetcher = fetcher;
		this.file = file;
		this.ttl = ttl;
		this.io = io;
	}

	/** The cached body if younger than the TTL, else a fresh download (which is then cached). */
	public CompletableFuture<String> get(URI uri) {
		return CompletableFuture.supplyAsync(this::readFresh, io).thenCompose(cached -> cached != null
				? CompletableFuture.completedFuture(cached)
				: fetcher.apply(uri).thenApply(body -> {
					io.execute(() -> write(body));
					return body;
				}));
	}

	private String readFresh() {
		try {
			if (!Files.isRegularFile(file)) return null;
			JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			if (Instant.ofEpochMilli(o.get("fetchedAt").getAsLong()).plus(ttl).isBefore(Instant.now())) return null;
			return o.get("body").getAsString();
		} catch (Exception e) {
			return null;
		}
	}

	private void write(String body) {
		try {
			JsonObject o = new JsonObject();
			o.addProperty("fetchedAt", System.currentTimeMillis());
			o.addProperty("body", body);
			Files.createDirectories(file.getParent());
			Files.writeString(file, o.toString(), StandardCharsets.UTF_8);
		} catch (Exception ignored) {
			// cache is best-effort
		}
	}
}
