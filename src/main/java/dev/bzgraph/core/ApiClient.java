package dev.bzgraph.core;

import com.google.gson.JsonParseException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP client for SkyCofl bazaar history and the Hypixel bazaar product list.
 * Every request goes through one {@link RequestQueue}; requests never run on the caller's thread.
 */
public final class ApiClient implements HistorySource, LiveSource {
	public static final String COFL_BASE = "https://sky.coflnet.com";
	public static final String HYPIXEL_BAZAAR = "https://api.hypixel.net/v2/skyblock/bazaar";
	static final int MAX_RATE_LIMIT_RETRIES = 4;
	static final long DEFAULT_RETRY_AFTER_SECONDS = 5;
	static final long MAX_RETRY_AFTER_SECONDS = 120;

	private record Response(int status, String body, String retryAfter) {
	}

	private final HttpClient http;
	private final RequestQueue queue;
	private final String userAgent;
	private final String coflBase;

	public ApiClient(RequestQueue queue, String userAgent) {
		this(HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(10))
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build(), queue, userAgent, COFL_BASE);
	}

	public ApiClient(HttpClient http, RequestQueue queue, String userAgent, String coflBase) {
		this.http = http;
		this.queue = queue;
		this.userAgent = userAgent;
		this.coflBase = coflBase;
	}

	static String isoSeconds(Instant i) {
		return i.truncatedTo(ChronoUnit.SECONDS).toString();
	}

	public URI historyUri(String itemId, Instant start, Instant end) {
		return URI.create(coflBase + "/api/bazaar/" + URLEncoder.encode(itemId, StandardCharsets.UTF_8)
				+ "/history?start=" + isoSeconds(start) + "&end=" + isoSeconds(end));
	}

	@Override
	public CompletableFuture<List<PricePoint>> history(String itemId, Instant start, Instant end, Listener listener) {
		URI uri = historyUri(itemId, start, end);
		return get(uri, listener, MAX_RATE_LIMIT_RETRIES).thenApply(r -> {
			if (r.status == 404) throw new ApiException(ApiException.Kind.NOT_FOUND, "Item not found: " + itemId);
			checkOk(r);
			try {
				return HistoryParser.parse(r.body);
			} catch (JsonParseException | IllegalStateException e) {
				throw new ApiException(ApiException.Kind.BAD_RESPONSE, "Malformed history response", e);
			}
		});
	}

	public URI snapshotUri(String itemId) {
		return URI.create(coflBase + "/api/bazaar/" + URLEncoder.encode(itemId, StandardCharsets.UTF_8) + "/snapshot");
	}

	@Override
	public CompletableFuture<PricePoint> snapshot(String itemId) {
		return get(snapshotUri(itemId), Listener.NONE, MAX_RATE_LIMIT_RETRIES).thenApply(r -> {
			if (r.status == 404) throw new ApiException(ApiException.Kind.NOT_FOUND, "Item not found: " + itemId);
			checkOk(r);
			try {
				return HistoryParser.parseSnapshot(r.body);
			} catch (JsonParseException | IllegalStateException e) {
				throw new ApiException(ApiException.Kind.BAD_RESPONSE, "Malformed snapshot response", e);
			}
		});
	}

	public CompletableFuture<Set<String>> bazaarProductIds() {
		return get(URI.create(HYPIXEL_BAZAAR), Listener.NONE, MAX_RATE_LIMIT_RETRIES).thenApply(r -> {
			checkOk(r);
			try {
				return BazaarProducts.parseProductIds(r.body);
			} catch (JsonParseException | IllegalStateException e) {
				throw new ApiException(ApiException.Kind.BAD_RESPONSE, "Malformed bazaar response", e);
			}
		});
	}

	/** Plain GET through the shared queue; returns the body of a 2xx response. */
	public CompletableFuture<String> getText(URI uri) {
		return get(uri, Listener.NONE, MAX_RATE_LIMIT_RETRIES).thenApply(r -> {
			if (r.status == 404) throw new ApiException(ApiException.Kind.NOT_FOUND, "Not found: " + uri);
			checkOk(r);
			return r.body;
		});
	}

	private static void checkOk(Response r) {
		if (r.status / 100 != 2) throw new ApiException(ApiException.Kind.HTTP_ERROR, "HTTP " + r.status);
	}

	private CompletableFuture<Response> get(URI uri, Listener listener, int retriesLeft) {
		return queue.submit(() -> send(uri)).thenCompose(r -> {
			if (r.status != 429) return CompletableFuture.completedFuture(r);
			if (retriesLeft <= 0) {
				return CompletableFuture.failedFuture(new ApiException(ApiException.Kind.RATE_LIMITED, "Rate limited"));
			}
			long wait = parseRetryAfter(r.retryAfter, Instant.now());
			queue.pauseFor(Duration.ofSeconds(wait));
			listener.onRateLimited(wait);
			return get(uri, listener, retriesLeft - 1);
		});
	}

	private Response send(URI uri) {
		HttpRequest req = HttpRequest.newBuilder(uri)
				.timeout(Duration.ofSeconds(30))
				.header("User-Agent", userAgent)
				.header("Accept", "application/json")
				.GET()
				.build();
		try {
			HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			return new Response(resp.statusCode(), resp.body(), resp.headers().firstValue("Retry-After").orElse(null));
		} catch (IOException e) {
			throw new ApiException(ApiException.Kind.OFFLINE, "Network error: " + e.getClass().getSimpleName(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ApiException(ApiException.Kind.OFFLINE, "Interrupted", e);
		}
	}

	/** Parses a Retry-After header (delta-seconds or HTTP-date), clamped to [1, 120] seconds. */
	static long parseRetryAfter(String header, Instant now) {
		long secs = DEFAULT_RETRY_AFTER_SECONDS;
		if (header != null && !header.isBlank()) {
			String h = header.trim();
			try {
				secs = Long.parseLong(h);
			} catch (NumberFormatException e) {
				try {
					secs = Duration.between(now, ZonedDateTime.parse(h, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toSeconds();
				} catch (Exception ignored) {
					secs = DEFAULT_RETRY_AFTER_SECONDS;
				}
			}
		}
		return Math.max(1, Math.min(MAX_RETRY_AFTER_SECONDS, secs));
	}
}
