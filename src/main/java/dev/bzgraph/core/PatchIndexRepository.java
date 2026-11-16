package dev.bzgraph.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Holds the current patch index. Sources, in order of preference: a fresh download (≤24 h old, cached
 * on disk), the stale disk cache, and the copy bundled in the mod jar — whichever has the newest
 * {@code updated} stamp wins when the download isn't available.
 */
public final class PatchIndexRepository {
	public static final Duration TTL = Duration.ofHours(24);

	public enum UrlPolicy {
		/** https:// */
		OK,
		/** http:// to a LAN / localhost host (e.g. a Raspberry Pi at home) – allowed with a warning */
		INSECURE_LAN,
		/** anything else: not used */
		REJECTED
	}

	private final Function<URI, CompletableFuture<String>> fetcher;
	private final Path cacheFile;
	private final Supplier<String> bundled;
	private final Executor io;
	private final Supplier<Instant> clock;
	private final Consumer<String> warn;
	private final AtomicBoolean refreshing = new AtomicBoolean();

	private volatile PatchIndex current = PatchIndex.EMPTY;
	private volatile String source = "none";
	private volatile String problem;
	private volatile Instant fetchedAt;
	private volatile String fetchedFrom;

	public PatchIndexRepository(Function<URI, CompletableFuture<String>> fetcher, Path cacheFile, Supplier<String> bundled,
								Executor io, Supplier<Instant> clock, Consumer<String> warn) {
		this.fetcher = fetcher;
		this.cacheFile = cacheFile;
		this.bundled = bundled;
		this.io = io;
		this.clock = clock;
		this.warn = warn;
	}

	public PatchIndex current() {
		return current;
	}

	/** Human-readable description of where the current index came from. */
	public String source() {
		return source;
	}

	/** Last problem (bad URL, offline, malformed index), or null. */
	public String problem() {
		return problem;
	}

	public static UrlPolicy checkUrl(String url) {
		if (url == null || url.isBlank()) return UrlPolicy.REJECTED;
		URI uri;
		try {
			uri = URI.create(url.trim());
		} catch (IllegalArgumentException e) {
			return UrlPolicy.REJECTED;
		}
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
		if (host.isEmpty()) return UrlPolicy.REJECTED;
		if (scheme.equals("https")) return UrlPolicy.OK;
		if (scheme.equals("http") && isLanHost(host)) return UrlPolicy.INSECURE_LAN;
		return UrlPolicy.REJECTED;
	}

	static boolean isLanHost(String host) {
		if (host.equals("localhost") || host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home.arpa")) return true;
		if (host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.")) return true;
		if (host.startsWith("172.")) {
			String[] parts = host.split("\\.");
			try {
				int b = Integer.parseInt(parts[1]);
				return parts.length == 4 && b >= 16 && b <= 31;
			} catch (RuntimeException e) {
				return false;
			}
		}
		return false;
	}

	/**
	 * Loads cache/bundled copy immediately (off-thread) and downloads a new copy if the cache is
	 * missing, older than {@link #TTL}, or came from a different URL.
	 */
	public CompletableFuture<Void> init(String url) {
		return CompletableFuture.runAsync(() -> {
			loadLocal();
			if (needsRefresh(url)) refresh(url);
		}, io);
	}

	boolean needsRefresh(String url) {
		return fetchedAt == null || fetchedFrom == null || !fetchedFrom.equals(url)
				|| !clock.get().isBefore(fetchedAt.plus(TTL));
	}

	private void loadLocal() {
		PatchIndex cached = null;
		if (cacheFile != null && Files.isRegularFile(cacheFile)) {
			try {
				JsonObject o = JsonParser.parseString(Files.readString(cacheFile, StandardCharsets.UTF_8)).getAsJsonObject();
				cached = PatchIndex.parse(o.get("index").getAsString());
				fetchedAt = Instant.ofEpochMilli(o.get("fetchedAt").getAsLong());
				fetchedFrom = o.get("url").getAsString();
			} catch (Exception e) {
				warn.accept("Ignoring corrupt patch index cache: " + e);
			}
		}
		PatchIndex packaged = null;
		try {
			String b = bundled.get();
			if (b != null) packaged = PatchIndex.parse(b);
		} catch (Exception e) {
			warn.accept("Bundled patch index is invalid: " + e);
		}
		if (cached != null && (packaged == null || !isNewer(packaged, cached))) {
			set(cached, "downloaded copy");
		} else if (packaged != null) {
			set(packaged, "built-in copy");
		}
	}

	private static boolean isNewer(PatchIndex a, PatchIndex b) {
		if (a.updated() == null) return false;
		return b.updated() == null || a.updated().isAfter(b.updated());
	}

	private void set(PatchIndex idx, String src) {
		current = idx;
		source = src;
	}

	/** Downloads the index now (no-op if a download is already running). */
	public CompletableFuture<Boolean> refresh(String url) {
		UrlPolicy policy = checkUrl(url);
		if (policy == UrlPolicy.REJECTED) {
			problem = url == null || url.isBlank()
					? "No patch index URL configured; using " + source
					: "Patch index URL must be https:// (http:// only on your local network)";
			return CompletableFuture.completedFuture(false);
		}
		if (policy == UrlPolicy.INSECURE_LAN) warn.accept("Patch index uses plain http (LAN host): " + url);
		if (!refreshing.compareAndSet(false, true)) return CompletableFuture.completedFuture(false);
		return fetcher.apply(URI.create(url.trim())).handle((body, err) -> {
			try {
				if (err != null) {
					ApiException api = ApiException.find(err);
					problem = (api != null && api.kind() == ApiException.Kind.OFFLINE ? "Offline" : "Couldn't download patch index")
							+ "; using " + source;
					return false;
				}
				PatchIndex idx;
				try {
					idx = PatchIndex.parse(body);
				} catch (RuntimeException e) {
					problem = "Downloaded patch index is invalid; using " + source;
					warn.accept(problem + ": " + e.getMessage());
					return false;
				}
				Instant now = clock.get();
				fetchedAt = now;
				fetchedFrom = url;
				set(idx, "downloaded copy");
				problem = policy == UrlPolicy.INSECURE_LAN ? "Patch index loaded over plain http" : null;
				writeCache(body, url, now);
				return true;
			} finally {
				refreshing.set(false);
			}
		});
	}

	private void writeCache(String body, String url, Instant now) {
		if (cacheFile == null) return;
		io.execute(() -> {
			try {
				JsonObject o = new JsonObject();
				o.addProperty("fetchedAt", now.toEpochMilli());
				o.addProperty("url", url);
				o.addProperty("index", body);
				Files.createDirectories(cacheFile.getParent());
				Files.writeString(cacheFile, o.toString(), StandardCharsets.UTF_8);
			} catch (Exception e) {
				warn.accept("Couldn't write patch index cache: " + e);
			}
		});
	}
}
