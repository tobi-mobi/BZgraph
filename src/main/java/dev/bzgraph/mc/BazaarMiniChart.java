package dev.bzgraph.mc;

import dev.bzgraph.BzGraphClient;
import dev.bzgraph.core.BazaarPage;
import dev.bzgraph.core.CachedHistory;
import dev.bzgraph.core.CoinFormat;
import dev.bzgraph.core.Downsampler;
import dev.bzgraph.core.HistoryHandle;
import dev.bzgraph.core.ItemIdMapper;
import dev.bzgraph.core.PricePoint;
import dev.bzgraph.core.TimeRange;
import dev.bzgraph.core.trade.ProductNames;
import dev.bzgraph.mixin.AbstractContainerScreenAccessor;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A small price chart drawn left of Hypixel's bazaar window while you're on a product's page. Left-click it to
 * open the full graph, right-click to cycle its range.
 */
public final class BazaarMiniChart {
	private static final int COLOR_BUY = 0xFFFFAA00;
	private static final int COLOR_SELL = 0xFF55FFFF;
	private static final int COLOR_BG = 0xE0101014;
	private static final int COLOR_BORDER = 0xFF3A3A44;
	private static final int COLOR_DIM = 0xFFAAAAAA;
	private static final int MIN_WIDTH = 110;
	private static final int MAX_WIDTH = 340;
	private static final int DETECT_EVERY_FRAMES = 10;
	private static final TimeRange[] RANGES = TimeRange.values();

	private final AbstractContainerScreen<?> screen;
	private int frame;
	private String productId;
	private String productName;
	private HistoryHandle handle;
	private TimeRange range;
	// layout of the last frame (0 width = hidden)
	private int x0, y0, x1, y1;

	private BazaarMiniChart(AbstractContainerScreen<?> screen) {
		this.screen = screen;
		this.range = rangeFromConfig();
	}

	/** Attaches a mini chart to every container screen (it only shows on bazaar product pages). */
	public static void install() {
		ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
			if (!(screen instanceof AbstractContainerScreen<?> cs)) return;
			BazaarMiniChart chart = new BazaarMiniChart(cs);
			ScreenEvents.afterExtract(screen).register((s, g, mx, my, pt) -> chart.render(g, mx, my));
			ScreenMouseEvents.allowMouseClick(screen).register((s, e) -> !chart.click(e));
		});
	}

	private static TimeRange rangeFromConfig() {
		TimeRange r = TimeRange.fromLabel(BzGraphClient.get().config().miniChartRange);
		return r == null ? TimeRange.WEEK : r;
	}

	private static boolean enabled() {
		BzGraphClient c = BzGraphClient.get();
		return c.config().miniChart && c.isActive();
	}

	// ---------------------------------------------------------------- detection

	private void detect() {
		AbstractContainerMenu menu = screen.getMenu();
		int containerSlots = Math.max(0, menu.slots.size() - 36); // the rest is the player's inventory
		List<String> names = new ArrayList<>(containerSlots);
		for (int i = 0; i < containerSlots; i++) {
			Slot s = menu.slots.get(i);
			names.add(s.hasItem() ? s.getItem().getHoverName().getString() : "");
		}
		String center = containerSlots > 13 && menu.slots.get(13).hasItem() ? menu.slots.get(13).getItem().getHoverName().getString() : null;
		String name = BazaarPage.productName(screen.getTitle().getString(), names, center);
		String id = name == null ? null : idFor(name);
		if (id == null) {
			productId = null;
			productName = name;
			handle = null;
			return;
		}
		if (!id.equals(productId)) {
			productId = id;
			productName = name;
			handle = BzGraphClient.get().repository().load(id, range);
			BzGraphClient.LOGGER.info("Mini chart: bazaar page '{}' -> {}", name, id);
		}
	}

	/** Display name -> bazaar product ID (exact item-list match, else the name heuristics). */
	private static String idFor(String name) {
		BzGraphClient c = BzGraphClient.get();
		Set<String> products = c.products();
		if (products == null) {
			c.ensureProducts();
			return null;
		}
		c.trades().ensureNames(products);
		ProductNames names = c.trades().service().names();
		String id = names == null ? null : names.idFor(name);
		return id != null ? id : ItemIdMapper.pick(ItemIdMapper.candidates(null, null, name), products);
	}

	// ---------------------------------------------------------------- input

	/** @return true if the click was on the chart (and is consumed). */
	private boolean click(MouseButtonEvent e) {
		if (x1 <= x0 || productId == null || e.x() < x0 || e.x() > x1 || e.y() < y0 || e.y() > y1) return false;
		Minecraft mc = Minecraft.getInstance();
		if (e.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			mc.gui.setScreen(new GraphScreen(screen, productId, productName));
			return true;
		}
		if (e.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
			range = RANGES[(range.ordinal() + 1) % RANGES.length];
			BzConfig cfg = BzGraphClient.get().config();
			cfg.miniChartRange = range.label();
			cfg.save();
			handle = BzGraphClient.get().repository().load(productId, range);
			return true;
		}
		return false;
	}

	// ---------------------------------------------------------------- rendering

	private void render(GuiGraphicsExtractor g, int mx, int my) {
		x0 = x1 = 0;
		if (!enabled()) return;
		if (frame++ % DETECT_EVERY_FRAMES == 0) detect();
		if (productId == null) return;

		AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) screen;
		int space = acc.bzgraph$getLeftPos() - 14; // keep a few pixels to the screen edge
		if (space < MIN_WIDTH) return; // not enough room left of the window
		int w = Math.min(MAX_WIDTH, space);
		x1 = acc.bzgraph$getLeftPos() - 6;
		x0 = x1 - w;
		y0 = acc.bzgraph$getTopPos();
		y1 = y0 + Math.max(110, Math.min(Math.max(150, w * 2 / 3), acc.bzgraph$getImageHeight()));
		Font font = Minecraft.getInstance().font;
		boolean hover = mx >= x0 && mx <= x1 && my >= y0 && my <= y1;

		BzConfig cfg = BzGraphClient.get().config();
		g.fill(x0, y0, x1, y1, cfg.background(COLOR_BG, 1.0));
		int border = hover ? 0xFF6A6A78 : COLOR_BORDER;
		g.horizontalLine(x0, x1 - 1, y0, border);
		g.horizontalLine(x0, x1 - 1, y1 - 1, border);
		g.verticalLine(x0, y0, y1 - 1, border);
		g.verticalLine(x1 - 1, y0, y1 - 1, border);

		String label = range.label();
		g.text(font, label, x1 - 5 - font.width(label), y0 + 5, COLOR_DIM);
		g.text(font, font.plainSubstrByWidth(productName, x1 - x0 - 14 - font.width(label)), x0 + 5, y0 + 5, 0xFFFFFFFF);

		List<PricePoint> pts = points();
		int cx0 = x0 + 5, cx1 = x1 - 6, cy0 = y0 + 30, cy1 = y1 - 14;
		if (pts.size() < 2) {
			String msg = handle == null || handle.isBusy() ? "Loading…" : handle.status() == HistoryHandle.Status.READY ? "No data" : handle.message();
			g.centeredText(font, font.plainSubstrByWidth(msg, x1 - x0 - 10), (x0 + x1) / 2, (cy0 + cy1) / 2 - 4, COLOR_DIM);
		} else {
			drawPrices(g, font, pts);
			drawChart(g, pts, cx0, cy0, cx1, cy1);
		}
		String hint = hover ? "Click: full graph  ·  Right: range" : "Click for full graph";
		g.text(font, font.plainSubstrByWidth(hint, x1 - x0 - 10), x0 + 5, y1 - 11, 0xFF666666);
	}

	/** History plus the latest-price snapshot (if newer). */
	private List<PricePoint> points() {
		CachedHistory d = handle == null ? null : handle.data();
		if (d == null) return List.of();
		List<PricePoint> pts = dev.bzgraph.core.Resampler.resample(d.points());
		PricePoint live = handle.live();
		if (live != null && !pts.isEmpty() && live.timestamp().isAfter(pts.getLast().timestamp())) {
			List<PricePoint> withLive = new ArrayList<>(pts);
			withLive.add(live);
			return withLive;
		}
		return pts;
	}

	private void drawPrices(GuiGraphicsExtractor g, Font font, List<PricePoint> pts) {
		PricePoint last = pts.getLast();
		int x = x0 + 5, y = y0 + 17;
		x = priceLabel(g, font, "B ", last.buy(), change(pts, true), COLOR_BUY, x, y) + 8;
		priceLabel(g, font, "S ", last.sell(), change(pts, false), COLOR_SELL, x, y);
	}

	private static int priceLabel(GuiGraphicsExtractor g, Font font, String tag, double v, double pct, int color, int x, int y) {
		g.text(font, tag, x, y, color);
		x += font.width(tag);
		String s = CoinFormat.compact(v);
		g.text(font, s, x, y, 0xFFFFFFFF);
		x += font.width(s);
		if (!Double.isNaN(pct)) {
			String p = String.format(Locale.ROOT, " %+.1f%%", pct);
			g.text(font, p, x, y, pct >= 0 ? 0xFF55FF55 : 0xFFFF5555);
			x += font.width(p);
		}
		return x;
	}

	/** Change from the first to the last point of the range, in percent. */
	private static double change(List<PricePoint> pts, boolean buy) {
		double first = Double.NaN, last = Double.NaN;
		for (PricePoint p : pts) {
			double v = buy ? p.buy() : p.sell();
			if (Double.isNaN(v)) continue;
			if (Double.isNaN(first)) first = v;
			last = v;
		}
		return Double.isNaN(first) || first == 0 ? Double.NaN : (last - first) / first * 100;
	}

	private void drawChart(GuiGraphicsExtractor g, List<PricePoint> pts, int cx0, int cy0, int cx1, int cy1) {
		int n = pts.size();
		long[] t = new long[n];
		double[] buy = new double[n], sell = new double[n], minB = new double[n], maxB = new double[n], minS = new double[n], maxS = new double[n];
		double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
		for (int i = 0; i < n; i++) {
			PricePoint p = pts.get(i);
			t[i] = p.timestamp().toEpochMilli();
			buy[i] = p.buy();
			sell[i] = p.sell();
			minB[i] = p.minBuy();
			maxB[i] = p.maxBuy();
			minS[i] = p.minSell();
			maxS[i] = p.maxSell();
			for (double v : new double[]{buy[i], sell[i]}) {
				if (Double.isNaN(v)) continue;
				lo = Math.min(lo, v);
				hi = Math.max(hi, v);
			}
		}
		if (lo == Double.POSITIVE_INFINITY) return;
		double pad = Math.max((hi - lo) * 0.08, Math.max(Math.abs(hi) * 0.01, 0.01));
		lo -= pad;
		hi += pad;
		long tMin = t[0], tMax = Math.max(t[n - 1], tMin + 1);
		int w = cx1 - cx0 + 1;
		g.fill(cx0, cy0, cx1 + 1, cy1 + 1, BzGraphClient.get().config().background(0x000000, 0.4));
		if (BzGraphClient.get().config().showBands) {
			band(g, t, minS, maxS, tMin, tMax, w, cx0, cy0, cy1, lo, hi, COLOR_SELL);
			band(g, t, minB, maxB, tMin, tMax, w, cx0, cy0, cy1, lo, hi, COLOR_BUY);
		}
		line(g, Downsampler.columns(t, sell, tMin, tMax, w), cx0, cy0, cy1, lo, hi, COLOR_SELL);
		line(g, Downsampler.columns(t, buy, tMin, tMax, w), cx0, cy0, cy1, lo, hi, COLOR_BUY);
	}

	private static int y(double v, double lo, double hi, int cy0, int cy1) {
		return cy1 - (int) Math.round((v - lo) / (hi - lo) * (cy1 - cy0));
	}

	/** Same polyline technique as the full graph: per-column min/max plus connecting segments. */
	private static void line(GuiGraphicsExtractor g, Downsampler.Columns c, int cx0, int cy0, int cy1, double lo, double hi, int color) {
		int prevCol = -1;
		double prevVal = Double.NaN;
		for (int col = 0; col < c.width(); col++) {
			if (c.count()[col] == 0) continue;
			if (prevCol >= 0) {
				int span = col - prevCol;
				int yPrev = y(prevVal, lo, hi, cy0, cy1);
				for (int k = 1; k <= span; k++) {
					int yy = y(prevVal + (c.first()[col] - prevVal) * k / span, lo, hi, cy0, cy1);
					vline(g, cx0 + prevCol + k, yPrev, yy, color);
					yPrev = yy;
				}
			}
			vline(g, cx0 + col, y(c.min()[col], lo, hi, cy0, cy1), y(c.max()[col], lo, hi, cy0, cy1), color);
			prevCol = col;
			prevVal = c.last()[col];
		}
	}

	private static void band(GuiGraphicsExtractor g, long[] t, double[] mins, double[] maxs, long tMin, long tMax, int w,
							 int cx0, int cy0, int cy1, double lo, double hi, int color) {
		int fill = (0x22 << 24) | (color & 0xFFFFFF);
		for (int col = 0; col < w; col++) {
			long tc = tMin + (long) ((double) col / Math.max(1, w - 1) * (tMax - tMin));
			int i = java.util.Arrays.binarySearch(t, tc);
			if (i < 0) i = -i - 2;
			if (i < 0 || Double.isNaN(mins[i]) || Double.isNaN(maxs[i])) continue;
			int ya = Math.max(cy0, y(maxs[i], lo, hi, cy0, cy1));
			int yb = Math.min(cy1, y(mins[i], lo, hi, cy0, cy1));
			if (ya > cy1 || yb < cy0) continue;
			g.fill(cx0 + col, ya, cx0 + col + 1, Math.max(ya, yb) + 1, fill);
		}
	}

	private static void vline(GuiGraphicsExtractor g, int x, int ya, int yb, int color) {
		g.fill(x, Math.min(ya, yb), x + 1, Math.max(ya, yb) + 1, color);
	}
}
