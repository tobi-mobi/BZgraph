package dev.bzgraph.mc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.bzgraph.BzGraphClient;
import dev.bzgraph.core.TimeRange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Plain JSON config at config/bzgraph/config.json. */
public final class BzConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Only react to the keybind / command while connected to a server whose address contains "hypixel.net". */
	public boolean onlyOnHypixel = true;
	public boolean showBuy = true;
	public boolean showSell = true;
	/** One of 1D, 1W, 1M, 1Y, ALL. */
	public String defaultRange = "1W";
	/** Patch-note markers on graphs: RELEVANT (only patches mentioning the item), ALL, or OFF. */
	public String patchMarkers = "RELEVANT";
	/**
	 * Patch index URL override (e.g. your Raspberry Pi). Empty = the default baked into the mod.
	 * Must be https://, or http:// to a LAN host (192.168.x.x, *.local, ...).
	 */
	public String patchIndexUrl = "";
	/** Show your own bazaar trades (from chat and imported logs) as markers on graphs. */
	public boolean showTrades = true;
	/** Shade each point's low-high price range behind the lines (full graph and mini chart). */
	public boolean showBands = true;
	/** Small chart left of the bazaar window while you're on an item's page. */
	public boolean miniChart = true;
	/** Range shown by the mini chart (1D, 1W, 1M, 1Y, ALL); right-click the mini chart to change it. */
	public String miniChartRange = "1W";
	/** Background opacity of the graph and mini chart, 0 (invisible) to 100 (solid). */
	public int backgroundOpacity = 65;
	/** Strip under the graph showing who was mayor (and minister) when (M). Data: SkyCofl. */
	public boolean showMayors = true;
	/** Record the purse from SkyBlock's sidebar (per account and profile) for the purse graph. */
	public boolean trackPurse = true;
	/** Range of the purse graph (1D, 1W, 1M, 1Y, ALL). */
	public String purseRange = "1W";
	/** How compared items are drawn: PERCENT (change from the start of the range), PRICE, or RATIO (two items). */
	public String compareMode = "PERCENT";
	/** Which price compared items are drawn with: BUY or SELL (one line per item). */
	public String compareSide = "BUY";
	/** Draw both the buy and the sell line of every compared item (sell lines faded). */
	public boolean compareBoth = false;
	/** The marker/shortcut overview was shown once on the first graph opened. */
	public boolean seenHelp = false;
	/**
	 * Favourite items in your own categories, shown as mini charts by G / /bzgraph favorites. Organise them on that
	 * screen (drag charts, right-click for menus).
	 */
	public dev.bzgraph.core.Favorites favoriteGroups = new dev.bzgraph.core.Favorites();
	/** Old flat favourites list (before categories); moved into {@link #favoriteGroups} on load. */
	public java.util.List<String> favorites;

	public dev.bzgraph.core.Favorites favorites() {
		if (favoriteGroups == null) favoriteGroups = new dev.bzgraph.core.Favorites();
		if (favorites != null) {
			for (String id : favorites) favoriteGroups.add(id);
			favorites = null;
		}
		return favoriteGroups;
	}

	public boolean isFavorite(String productId) {
		return favorites().contains(productId);
	}

	/** Adds or removes a favourite; returns whether it is one now. */
	public boolean toggleFavorite(String productId) {
		boolean now = favorites().toggle(productId);
		save();
		return now;
	}
	/** On startup, import trades from this Minecraft instance's older log files (each file only once). */
	public boolean importLogsOnStartup = true;

	private transient Path file;

	public static BzConfig load(Path file) {
		BzConfig cfg = null;
		if (Files.isRegularFile(file)) {
			try {
				cfg = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), BzConfig.class);
			} catch (Exception e) {
				BzGraphClient.LOGGER.warn("Invalid config {}, using defaults", file, e);
			}
		}
		if (cfg == null) cfg = new BzConfig();
		cfg.file = file;
		cfg.save();
		return cfg;
	}

	public TimeRange defaultRange() {
		TimeRange r = TimeRange.fromLabel(defaultRange);
		return r == null ? TimeRange.WEEK : r;
	}

	public enum Markers { RELEVANT, ALL, OFF }

	/** {@code rgb} with this config's background opacity (scaled by {@code factor}, e.g. 0.5 for half as opaque). */
	public int background(int rgb, double factor) {
		int pct = Math.max(0, Math.min(100, backgroundOpacity));
		int alpha = (int) Math.round(pct / 100.0 * 255 * factor);
		return (Math.max(0, Math.min(255, alpha)) << 24) | (rgb & 0xFFFFFF);
	}

	public enum CompareMode { PERCENT, PRICE, RATIO }

	public CompareMode compareMode() {
		try {
			return CompareMode.valueOf(compareMode.trim().toUpperCase(java.util.Locale.ROOT));
		} catch (RuntimeException e) {
			return CompareMode.PERCENT;
		}
	}

	public TimeRange purseRange() {
		TimeRange r = TimeRange.fromLabel(purseRange);
		return r == null ? TimeRange.WEEK : r;
	}

	public Markers patchMarkers() {
		try {
			return Markers.valueOf(patchMarkers.trim().toUpperCase(java.util.Locale.ROOT));
		} catch (RuntimeException e) {
			return Markers.RELEVANT;
		}
	}

	public void save() {
		if (file == null) return;
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
		} catch (IOException e) {
			BzGraphClient.LOGGER.warn("Could not save config {}", file, e);
		}
	}
}
