package dev.bzgraph.mc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.bzgraph.BzGraphClient;
import dev.bzgraph.core.ApiClient;
import dev.bzgraph.core.trade.ProductNames;
import dev.bzgraph.core.trade.Trade;
import dev.bzgraph.core.trade.TradeService;
import dev.bzgraph.core.trade.TradeStore;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Records the player's bazaar trades from chat, imports old ones from Minecraft's log files, and serves them
 * to the graph. Everything stays in {@code config/bzgraph/} on this PC.
 */
public final class TradeTracker {
	private static final String ITEMS_URL = "https://api.hypixel.net/v2/resources/skyblock/items";
	private static final Duration NAMES_TTL = Duration.ofDays(7);
	private static final int SAVE_EVERY_TICKS = 20 * 30;

	private final TradeService service;
	private final ApiClient api;
	private final Executor io;
	private final Path namesCache;
	private final AtomicBoolean namesLoading = new AtomicBoolean();
	private int ticks;

	public TradeTracker(Path configDir, ApiClient api, Executor io) {
		this.api = api;
		this.io = io;
		this.namesCache = configDir.resolve("cache").resolve("item_names.json");
		this.service = new TradeService(new TradeStore(configDir.resolve("trades.json")), Instant::now,
				t -> BzGraphClient.LOGGER.warn("BZgraph trade tracking: {}", t.toString()));
	}

	public TradeService service() {
		return service;
	}

	/** Loads saved trades, then (if enabled) imports this instance's older log files in the background. */
	public void start(boolean importOwnLogs) {
		io.execute(() -> {
			service.store().load();
			if (!importOwnLogs) return;
			Path logs = FabricLoader.getInstance().getGameDir().resolve("logs");
			if (!Files.isDirectory(logs)) return;
			try {
				// latest.log is the running session, which live capture already records.
				TradeService.ImportResult r = service.importFolder(logs, account(), ZoneId.systemDefault(), false);
				if (r.files() > 0) {
					BzGraphClient.LOGGER.info("Imported {} bazaar trades from {} log files", r.trades(), r.files());
				}
			} catch (Exception e) {
				BzGraphClient.LOGGER.warn("Could not import trades from logs", e);
			}
		});
	}

	public static String account() {
		return Minecraft.getInstance().getUser().getName();
	}

	/** Every incoming chat/system message passes through here (cheap unless it starts with "[Bazaar]"). */
	public void onMessage(Component message) {
		service.onChat(account(), message.getString());
	}

	public void tick() {
		if (++ticks % SAVE_EVERY_TICKS == 0) io.execute(service::saveIfDirty);
	}

	public void stop() {
		service.saveIfDirty();
	}

	/** Imports trades from another logs folder (e.g. an old launcher profile). */
	public CompletableFuture<TradeService.ImportResult> importFolder(Path logsDir) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				return service.importFolder(logsDir, account(), ZoneId.systemDefault(), true);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}, io);
	}

	public List<Trade> forProduct(String productId) {
		return service.forProduct(account(), productId);
	}

	/**
	 * Builds the chat-name -> product-ID lookup once the bazaar product list is known. Item names come from
	 * Hypixel's public item list (no API key), cached on disk for a week.
	 */
	public void ensureNames(Set<String> products) {
		if (products == null || service.hasNames() || !namesLoading.compareAndSet(false, true)) return;
		CompletableFuture.supplyAsync(this::readNamesCache, io).thenCompose(cached -> cached != null
				? CompletableFuture.completedFuture(cached)
				: api.getText(URI.create(ITEMS_URL)).thenApply(body -> {
					Map<String, String> names = parseItemNames(body, products);
					writeNamesCache(names);
					return names;
				})).whenComplete((names, err) -> {
			if (err != null) {
				BzGraphClient.LOGGER.warn("Could not load item names for trade markers: {}", err.toString());
				namesLoading.set(false); // try again next time a graph opens
			} else {
				service.setNames(ProductNames.build(products, names));
			}
		});
	}

	private Map<String, String> readNamesCache() {
		try {
			if (!Files.isRegularFile(namesCache)) return null;
			JsonObject o = JsonParser.parseString(Files.readString(namesCache, StandardCharsets.UTF_8)).getAsJsonObject();
			if (Instant.ofEpochMilli(o.get("fetchedAt").getAsLong()).plus(NAMES_TTL).isBefore(Instant.now())) return null;
			Map<String, String> m = new HashMap<>();
			for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("names").entrySet()) m.put(e.getKey(), e.getValue().getAsString());
			return m;
		} catch (Exception e) {
			return null;
		}
	}

	private void writeNamesCache(Map<String, String> names) {
		try {
			JsonObject o = new JsonObject();
			o.addProperty("fetchedAt", System.currentTimeMillis());
			JsonObject n = new JsonObject();
			names.forEach(n::addProperty);
			o.add("names", n);
			Files.createDirectories(namesCache.getParent());
			Files.writeString(namesCache, o.toString(), StandardCharsets.UTF_8);
		} catch (Exception e) {
			BzGraphClient.LOGGER.warn("Could not cache item names: {}", e.toString());
		}
	}

	/** Keeps only bazaar products (the full list is ~5 MB). */
	static Map<String, String> parseItemNames(String body, Set<String> products) {
		Map<String, String> m = new HashMap<>();
		for (JsonElement e : JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("items")) {
			JsonObject o = e.getAsJsonObject();
			if (!o.has("id") || !o.has("name")) continue;
			String id = o.get("id").getAsString();
			if (products.contains(id)) m.put(id, o.get("name").getAsString());
		}
		return m;
	}
}
