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
