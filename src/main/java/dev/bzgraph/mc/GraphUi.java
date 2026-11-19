package dev.bzgraph.mc;

import dev.bzgraph.core.TimeRange;
import dev.bzgraph.core.TimeTicks;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/** Drawing pieces shared by the item graph and the purse graph, so both look and read the same. */
final class GraphUi {
	static final int COLOR_TEXT = 0xFFFFFFFF;
	static final int COLOR_DIM = 0xFFAAAAAA;
	static final int COLOR_FAINT = 0xFF808080;
	static final int COLOR_FOOTER = 0xFFA0A0A0;
	static final int COLOR_ACCENT = 0xFFFFD25A;
	static final int COLOR_OFF = 0xFF7A7A7A;
	static final int COLOR_GRID = 0x30FFFFFF;
	static final int COLOR_AXIS = 0x80FFFFFF;
	static final int COLOR_UP = 0xFF55FF55;
	static final int COLOR_DOWN = 0xFFFF5555;
	/** Your trades: shape says buy (▲) or sell (▼); the colour stays neutral so a sell doesn't read as a loss. */
	static final int COLOR_TRADE = 0xFFF0F0F0;
	private static final int COLOR_PANEL_BG = 0x101014;
	private static final int COLOR_PANEL_BORDER = 0xFF3A3A44;

	private GraphUi() {
	}

	// ---------------------------------------------------------------- panel

	/** Dims the world behind the screen, then draws the (mostly opaque) panel so game text doesn't show through. */
	static void panel(GuiGraphicsExtractor g, BzConfig cfg, int screenW, int screenH, int x0, int y0, int x1, int y1) {
		double pct = Math.max(0, Math.min(100, cfg.backgroundOpacity)) / 100.0;
		g.fill(0, 0, screenW, screenH, (int) Math.round(pct * 0x60) << 24);
		int alpha = (int) Math.min(255, Math.round(pct * 255 * 1.27)); // the default 65% gives ~82%: slightly see-through
		g.fill(x0, y0, x1, y1, (alpha << 24) | COLOR_PANEL_BG);
		g.outline(x0, y0, x1 - x0, y1 - y0, COLOR_PANEL_BORDER);
	}

	static void plotBackground(GuiGraphicsExtractor g, BzConfig cfg, int gx0, int gy0, int gx1, int gy1) {
		g.fill(gx0, gy0, gx1 + 1, gy1, cfg.background(0x000000, 0.5));
	}

	// ---------------------------------------------------------------- buttons

	/** Every range button stays clickable; the selected one gets the accent colour (and an outline, see {@link #drawSelected}). */
	static void rangeLabels(List<Button> buttons, TimeRange selected, boolean enabled) {
		TimeRange[] all = TimeRange.values();
		for (int i = 0; i < buttons.size(); i++) {
			Button b = buttons.get(i);
			b.active = enabled;
			b.setMessage(Component.literal(all[i].label()).withColor(all[i] == selected ? COLOR_ACCENT : 0xFFE0E0E0));
		}
	}

	static void drawSelected(GuiGraphicsExtractor g, List<Button> buttons, TimeRange selected) {
		Button b = buttons.get(selected.ordinal());
		if (!b.visible || !b.active) return;
		g.outline(b.getX() - 1, b.getY() - 1, b.getWidth() + 2, b.getHeight() + 2, COLOR_ACCENT);
		g.fill(b.getX() + 4, b.getY() + b.getHeight() - 3, b.getX() + b.getWidth() - 4, b.getY() + b.getHeight() - 2, COLOR_ACCENT);
	}

	/** Label for an on/off button; the box in front of it is drawn by {@link #drawToggleBox}. */
	static Component toggleLabel(String text, boolean on, int color) {
		return Component.literal("  " + text).withColor(on ? color : COLOR_OFF);
	}

	/** Filled box = on, hollow box = off. */
	static void drawToggleBox(GuiGraphicsExtractor g, Font font, Button b, boolean on, int color) {
		if (b == null || !b.visible) return;
		int tw = font.width(b.getMessage());
		int x = b.getX() + (b.getWidth() - tw) / 2;
		int y = b.getY() + (b.getHeight() - 7) / 2;
		int c = !b.active ? 0xFF555555 : on ? color : COLOR_OFF;
		g.outline(x, y, 6, 6, c);
		if (on) g.fill(x + 1, y + 1, x + 5, y + 5, c);
	}

	// ---------------------------------------------------------------- text

	/** Text drawn {@code scale} times larger; returns its width on screen. */
	static int scaledText(GuiGraphicsExtractor g, Font font, Component c, int x, int y, float scale) {
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(scale, scale);
		g.text(font, c, 0, 0, COLOR_TEXT);
		g.pose().popMatrix();
		return Math.round(font.width(c) * scale);
	}

	/** Green for up, red for down, grey for no change. */
	static int changeColor(double v) {
		return Double.isNaN(v) || Math.abs(v) < 1e-9 ? COLOR_DIM : v > 0 ? COLOR_UP : COLOR_DOWN;
	}

	static String ago(Instant then, Instant now) {
		if (then == null) return "";
		long s = Math.max(0, Duration.between(then, now).getSeconds());
		if (s < 60) return "just now";
		if (s < 3600) return (s / 60) + " min ago";
		if (s < 86_400) return (s / 3600) + " h ago";
		return (s / 86_400) + " d ago";
	}

	// ---------------------------------------------------------------- axes

	/** Time axis with round, evenly spaced ticks; labels stay inside the plot's width and never overlap. */
	static void xAxis(GuiGraphicsExtractor g, Font font, int gx0, int gy0, int gx1, int gy1, int labelY, long tMin, long tMax) {
		int maxTicks = Math.max(2, (gx1 - gx0) / 70);
		int lastRight = Integer.MIN_VALUE;
		for (TimeTicks.Tick t : TimeTicks.ticks(tMin, tMax, maxTicks, ZoneId.systemDefault())) {
			int x = gx0 + (int) Math.round((double) (t.time() - tMin) / Math.max(1, tMax - tMin) * (gx1 - gx0));
			g.verticalLine(x, gy0, gy1, COLOR_GRID);
			int w = font.width(t.label());
			int tx = Math.max(gx0, Math.min(x - w / 2, gx1 - w));
			if (tx < lastRight + 6) continue;
			g.text(font, t.label(), tx, labelY, COLOR_DIM);
			lastRight = tx + w;
		}
		g.verticalLine(gx0, gy0, gy1, COLOR_AXIS);
		g.horizontalLine(gx0, gx1, gy1, COLOR_AXIS);
	}

	/** Y label right-aligned against the plot; kept above the time labels. */
	static void yLabel(GuiGraphicsExtractor g, Font font, String s, int gx0, int gy1, int y) {
		int ty = Math.min(y - font.lineHeight / 2, gy1 - font.lineHeight + 1);
		g.text(font, s, gx0 - 4 - font.width(s), ty, COLOR_DIM);
	}

	// ---------------------------------------------------------------- markers

	/** ▲ for your buys (tip on the price, pointing up at it from below), ▼ for your sells (from above). */
	static void tradeMarker(GuiGraphicsExtractor g, int x, int y, boolean sell, boolean several) {
		int size = several ? 4 : 3;
		triangle(g, x, y, size + 1, sell, 0xFF000000);
		triangle(g, x, y + (sell ? -1 : 1), size, sell, COLOR_TRADE);
	}

	static void triangle(GuiGraphicsExtractor g, int x, int y, int size, boolean down, int color) {
		for (int i = 0; i < size; i++) {
			int row = down ? y - i : y + i;
			g.fill(x - i, row, x + i + 1, row + 1, color);
		}
	}

	static void dot(GuiGraphicsExtractor g, int x, int y, int color) {
		g.fill(x - 2, y - 2, x + 3, y + 3, 0xFF000000);
		g.fill(x - 1, y - 1, x + 2, y + 2, color);
	}

	// ---------------------------------------------------------------- help

	/** Clickable link line of the help box drawn last frame: x0, y0, x1, y1 (or null). */
	static int[] helpLink;

	/**
	 * What the markers and lines mean, the shortcuts and where the data comes from. Drawn over the plot; a click on
	 * the SkyCofl line opens its site, any other click or key closes the box.
	 */
	static void drawHelp(GuiGraphicsExtractor g, Font font, int cx, int cy, boolean purse, int buyColor, int sellColor) {
		String[][] rows = purse ? new String[][]{
				{"line", "Your purse, read from the SkyBlock sidebar (bank not included)"},
				{"buy", "You bought on the bazaar (insta-buy or a filled buy order)"},
				{"sell", "You sold on the bazaar (insta-sell or a filled sell offer)"},
				{"", "Sell offers pay out when you claim them, so the purse can"},
				{"", "move later than the ▼. Buy orders take the coins when placed."},
				{"keys", "1-5 range · ← back · Esc close"},
				{"head", "Data sources"},
				{"src", "Purse: your SkyBlock sidebar, saved on this PC"},
				{"src", "Your trades: your chat and Minecraft log files"},
		} : new String[][]{
				{"buyline", "Buy: insta-buy price (what you pay now; lowest sell offer)"},
				{"sellline", "Sell: insta-sell price (what you get now; highest buy order)"},
				{"band", "Band: lowest to highest price inside each point"},
				{"buy", "You bought this item (insta-buy or a filled buy order)"},
				{"sell", "You sold this item (insta-sell or a filled sell offer)"},
				{"mayor", "Mayor bar: who was mayor then (hover for perks)"},
				{"keys", "1-5 range · S favourite · F search · C compare · M mayors · B band · R refresh"},
				{"head", "Data sources"},
				{"link", "Prices and mayor history: SkyCofl, sky.coflnet.com (click)"},
				{"src", "Bazaar item list: Hypixel API"},
				{"src", "Your trades: your chat and Minecraft log files"},
		};
		String title = purse ? "Reading the purse graph" : "Reading the graph";
		String footer = "Click or press any key to close · ? opens this again";
		int textX = 22; // rows start after their icon
		int w = Math.max(font.width(title), font.width(footer)) + 16;
		for (String[] r : rows) w = Math.max(w, font.width(r[1]) + (r[0].equals("head") ? 8 : textX) + 8);
		int h = 20 + rows.length * 12 + 14 + 6;
		int x0 = cx - w / 2, y0 = cy - h / 2;
		g.fill(x0, y0, x0 + w, y0 + h, 0xF0101014);
		g.outline(x0, y0, w, h, COLOR_ACCENT);
		g.text(font, title, x0 + 8, y0 + 6, COLOR_ACCENT);
		helpLink = null;
		int y = y0 + 20;
		for (String[] r : rows) {
			int ix = x0 + 12, iy = y + 3;
			int color = COLOR_TEXT;
			int tx = x0 + textX;
			switch (r[0]) {
				case "line" -> g.fill(ix - 4, iy, ix + 5, iy + 1, 0xFFFFD700);
				case "buyline" -> g.fill(ix - 4, iy, ix + 5, iy + 1, buyColor);
				case "sellline" -> g.fill(ix - 4, iy, ix + 5, iy + 1, sellColor);
				case "band" -> g.fill(ix - 4, iy - 3, ix + 5, iy + 4, 0x40FFFFFF);
				case "buy" -> tradeMarker(g, ix, iy - 2, false, false);
				case "sell" -> tradeMarker(g, ix, iy + 3, true, false);
				case "mayor" -> g.fill(ix - 4, iy - 2, ix + 5, iy + 3, 0xFFC78B1E);
				case "keys", "src" -> color = COLOR_DIM;
				case "head" -> {
					color = COLOR_ACCENT;
					tx = x0 + 8;
				}
				case "link" -> {
					color = 0xFF55AAFF;
					helpLink = new int[]{tx, y - 1, tx + font.width(r[1]), y + 9};
				}
				default -> {
				}
			}
			g.text(font, r[1], tx, y, color);
			y += 12;
		}
		g.text(font, footer, x0 + 8, y + 4, COLOR_FAINT);
	}

	/** True if (mx, my) is on the help box's SkyCofl link. */
	static boolean overHelpLink(double mx, double my) {
		int[] r = helpLink;
		return r != null && mx >= r[0] && mx < r[2] && my >= r[1] && my < r[3];
	}
}
