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
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

		String hint = hover ? "Click: full graph  \u00b7  Right: range" : "Click for full graph";
		drawCard(g, font, x0, y0, x1, y1, productName, handle, range, hint, hover, 0, mx, my);
	}

	/**
	 * A mini chart card: item name, range, current prices with their change, the chart, and an optional
	 * hint line. Shared by the bazaar page and the favourites grid. With the mouse ({@code mx}, {@code my}) over
	 * the chart it shows the price there; pass -1 to skip that.
	 */
	static void drawCard(GuiGraphicsExtractor g, Font font, int x0, int y0, int x1, int y1, String name,
						 HistoryHandle handle, TimeRange range, String hint, boolean hover,
						 int rightInset, int mx, int my) {
		BzConfig cfg = BzGraphClient.get().config();
		g.fill(x0, y0, x1, y1, cfg.background(COLOR_BG, 1.0));
		g.outline(x0, y0, x1 - x0, y1 - y0, hover ? 0xFF6A6A78 : COLOR_BORDER);
		int tx = x0 + 5;
		String label = range.label();
		// rightInset keeps the top-right corner free (the favourites grid puts its remove button there)
		g.text(font, label, x1 - 5 - rightInset - font.width(label), y0 + 5, COLOR_DIM);
		g.text(font, font.plainSubstrByWidth(name == null ? "" : name, x1 - tx - 10 - rightInset - font.width(label)), tx, y0 + 5, 0xFFFFFFFF);

		int cx0 = x0 + 5, cx1 = x1 - 6, cy0 = y0 + 28, cy1 = y1 - (hint == null ? 6 : 14);
		Built b = built(handle, cx1 - cx0 + 1, cy1 - cy0);
		if (b.n < 2) {
			String msg = handle == null || handle.isBusy() ? "Loading…" : handle.status() == HistoryHandle.Status.READY ? "No data" : handle.message();
			g.centeredText(font, font.plainSubstrByWidth(msg, x1 - x0 - 10), (x0 + x1) / 2, (cy0 + cy1) / 2 - 4, COLOR_DIM);
		} else {
			int x = priceLabel(g, font, "B ", b.buyText, b.buyPct, b.buyPctColor, COLOR_BUY, tx, y0 + 16) + 8;
			priceLabel(g, font, "S ", b.sellText, b.sellPct, b.sellPctColor, COLOR_SELL, x, y0 + 16);
			g.fill(cx0, cy0, cx1 + 1, cy1 + 1, cfg.background(0x000000, 0.4));
			// the whole chart is one texture: one draw call, however busy the price history is
			if (b.texture != null) g.blit(RenderPipelines.GUI_TEXTURED, b.textureId, cx0, cy0, 0, 0, b.w, b.h + 1, b.w, b.h + 1);
			if (mx >= cx0 && mx <= cx1 && my >= cy0 && my <= cy1) drawHover(g, font, b, cx0, cy0, mx, my);
		}
		if (hint != null) g.text(font, font.plainSubstrByWidth(hint, x1 - x0 - 10), x0 + 5, y1 - 11, 0xFF888888);
	}

	private static final java.time.format.DateTimeFormatter FMT_HOVER =
			java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

	/** Crosshair at the nearest sample with its date, buy, sell and spread. */
	private static void drawHover(GuiGraphicsExtractor g, Font font, Built b, int cx0, int cy0, int mx, int my) {
		long tAt = b.tMin + Math.round((double) (mx - cx0) / Math.max(1, b.w - 1) * (b.tMax - b.tMin));
		int i = java.util.Arrays.binarySearch(b.t, tAt);
		if (i < 0) {
			int ins = -i - 1;
			i = ins <= 0 ? 0 : ins >= b.t.length ? b.t.length - 1 : (tAt - b.t[ins - 1] <= b.t[ins] - tAt ? ins - 1 : ins);
		}
		int px = cx0 + (int) Math.round((double) (b.t[i] - b.tMin) / Math.max(1, b.tMax - b.tMin) * (b.w - 1));
		g.verticalLine(px, cy0, cy0 + b.h, 0x90FFFFFF);
		if (!Double.isNaN(b.sell[i])) GraphUi.dot(g, px, cy0 + y(b.sell[i], b.lo, b.hi, b.h), COLOR_SELL);
		if (!Double.isNaN(b.buy[i])) GraphUi.dot(g, px, cy0 + y(b.buy[i], b.lo, b.hi, b.h), COLOR_BUY);
		List<net.minecraft.network.chat.Component> lines = new ArrayList<>();
		lines.add(net.minecraft.network.chat.Component.literal(FMT_HOVER.format(
				java.time.Instant.ofEpochMilli(b.t[i]).atZone(java.time.ZoneId.systemDefault()))).withColor(0xFFFFFFFF));
		lines.add(net.minecraft.network.chat.Component.literal("Buy:  ").withColor(COLOR_BUY)
				.append(net.minecraft.network.chat.Component.literal(CoinFormat.price(b.buy[i])).withColor(0xFFFFFFFF)));
		lines.add(net.minecraft.network.chat.Component.literal("Sell: ").withColor(COLOR_SELL)
				.append(net.minecraft.network.chat.Component.literal(CoinFormat.price(b.sell[i])).withColor(0xFFFFFFFF)));
		if (!Double.isNaN(b.buy[i]) && !Double.isNaN(b.sell[i]) && b.sell[i] > 0) {
			double sp = b.buy[i] - b.sell[i];
			lines.add(net.minecraft.network.chat.Component.literal("Spread: ").withColor(COLOR_DIM)
					.append(net.minecraft.network.chat.Component.literal(CoinFormat.compact(sp)
							+ String.format(Locale.ROOT, " (%.1f%%)", sp / b.sell[i] * 100)).withColor(0xFFFFFFFF)));
		}
		g.setComponentTooltipForNextFrame(font, lines, mx, my);
	}

	// ---------------------------------------------------------------- cached chart texture

	/**
	 * Everything a card needs from its data, worked out once whenever the data, the size or the band setting
	 * changes: the price labels, the samples (for hovering) and the chart itself, painted into a small texture.
	 * Drawing a card is then a few texts and one image.
	 */
	private static final class Built {
		CachedHistory data;
		PricePoint live;
		int w, h;
		boolean bands;
		int n;
		String buyText = "", sellText = "", buyPct = "", sellPct = "";
		int buyPctColor, sellPctColor;
		long[] t = new long[0];
		double[] buy = new double[0], sell = new double[0];
		double lo, hi;
		long tMin, tMax;
		net.minecraft.client.renderer.texture.DynamicTexture texture;
		net.minecraft.resources.Identifier textureId;
		long lastUsed;

		void release() {
			if (texture != null) Minecraft.getInstance().getTextureManager().release(textureId);
			texture = null;
		}
	}

	private static final Built EMPTY = new Built();
	/** Per history; textures of charts not drawn for a while are freed (see {@link #sweep}). */
	private static final Map<HistoryHandle, Built> CACHE = new java.util.HashMap<>();
	private static long lastSweep;
	private static int textureCounter;

	private static Built built(HistoryHandle handle, int w, int h) {
		if (handle == null || w < 2 || h < 2) return EMPTY;
		long now = System.currentTimeMillis();
		sweep(now);
		CachedHistory d = handle.data();
		PricePoint live = handle.live();
		boolean bands = BzGraphClient.get().config().showBands;
		Built b = CACHE.get(handle);
		if (b != null && b.data == d && b.live == live && b.w == w && b.h == h && b.bands == bands) {
			b.lastUsed = now;
			return b;
		}
		if (b != null) b.release();
		b = new Built();
		b.lastUsed = now;
		b.data = d;
		b.live = live;
		b.w = w;
		b.h = h;
		b.bands = bands;
		List<PricePoint> pts = points(d, live);
		b.n = pts.size();
		if (b.n >= 2) {
			PricePoint last = pts.getLast();
			b.buyText = CoinFormat.compact(last.buy());
			b.sellText = CoinFormat.compact(last.sell());
			double cb = change(pts, true), cs = change(pts, false);
			b.buyPct = Double.isNaN(cb) ? "" : String.format(Locale.ROOT, " %+.1f%%", cb);
			b.sellPct = Double.isNaN(cs) ? "" : String.format(Locale.ROOT, " %+.1f%%", cs);
			b.buyPctColor = cb >= 0 ? 0xFF55FF55 : 0xFFFF5555;
			b.sellPctColor = cs >= 0 ? 0xFF55FF55 : 0xFFFF5555;
			paint(b, pts);
		}
		CACHE.put(handle, b);
		return b;
	}

	/** Frees the textures of charts that haven't been drawn for 10 seconds (closed screens, old ranges). */
	private static void sweep(long now) {
		if (now - lastSweep < 5000) return;
		lastSweep = now;
		CACHE.values().removeIf(b -> {
			if (now - b.lastUsed < 10_000) return false;
			b.release();
			return true;
		});
	}

	/** History plus the latest-price snapshot (if newer), at an even level of detail. */
	private static List<PricePoint> points(CachedHistory d, PricePoint live) {
		if (d == null) return List.of();
		List<PricePoint> pts = dev.bzgraph.core.Resampler.resample(d.points());
		if (live != null && !pts.isEmpty() && live.timestamp().isAfter(pts.getLast().timestamp())) {
			List<PricePoint> withLive = new ArrayList<>(pts);
			withLive.add(live);
			return withLive;
		}
		return pts;
	}

	private static int priceLabel(GuiGraphicsExtractor g, Font font, String tag, String value, String pct, int pctColor, int color, int x, int y) {
		g.text(font, tag, x, y, color);
		x += font.width(tag);
		g.text(font, value, x, y, 0xFFFFFFFF);
		x += font.width(value);
		if (!pct.isEmpty()) {
			g.text(font, pct, x, y, pctColor);
			x += font.width(pct);
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

	/** Paints bands then lines into the card's texture (one pixel per GUI pixel, y = 0 at the top). */
	private static void paint(Built b, List<PricePoint> pts) {
		int n = pts.size(), w = b.w, h = b.h;
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
		b.t = t;
		b.buy = buy;
		b.sell = sell;
		b.lo = lo;
		b.hi = hi;
		b.tMin = tMin;
		b.tMax = tMax;

		Canvas c = new Canvas(w, h + 1);
		if (b.bands) {
			band(c, t, minS, maxS, tMin, tMax, w, h, lo, hi, (0x22 << 24) | (COLOR_SELL & 0xFFFFFF));
			band(c, t, minB, maxB, tMin, tMax, w, h, lo, hi, (0x22 << 24) | (COLOR_BUY & 0xFFFFFF));
		}
		line(c, Downsampler.columns(t, sell, tMin, tMax, w), h, lo, hi, COLOR_SELL);
		line(c, Downsampler.columns(t, buy, tMin, tMax, w), h, lo, hi, COLOR_BUY);

		b.textureId = net.minecraft.resources.Identifier.fromNamespaceAndPath(BzGraphClient.MOD_ID, "minichart/" + (textureCounter++));
		b.texture = new net.minecraft.client.renderer.texture.DynamicTexture(() -> "bzgraph mini chart", w, h + 1, false);
		com.mojang.blaze3d.platform.NativeImage img = b.texture.getPixels();
		for (int y = 0; y <= h; y++) for (int x = 0; x < w; x++) img.setPixel(x, y, c.px[y * w + x]);
		b.texture.upload();
		Minecraft.getInstance().getTextureManager().register(b.textureId, b.texture);
	}

	/** ARGB pixels with "paint over" blending, so the two bands mix the way overlapping fills do on screen. */
	private static final class Canvas {
		final int w, h;
		final int[] px;

		Canvas(int w, int h) {
			this.w = w;
			this.h = h;
			this.px = new int[w * h];
		}

		void vline(int x, int ya, int yb, int color) {
			fill(x, Math.min(ya, yb), x + 1, Math.max(ya, yb) + 1, color);
		}

		void fill(int x0, int y0, int x1, int y1, int color) {
			x0 = Math.max(0, x0);
			y0 = Math.max(0, y0);
			x1 = Math.min(w, x1);
			y1 = Math.min(h, y1);
			int sa = color >>> 24;
			for (int y = y0; y < y1; y++) {
				for (int x = x0; x < x1; x++) {
					int i = y * w + x;
					px[i] = sa == 255 ? color : over(color, px[i]);
				}
			}
		}

		private static int over(int src, int dst) {
			int sa = src >>> 24, da = dst >>> 24;
			int oa = sa + da * (255 - sa) / 255;
			if (oa == 0) return 0;
			int r = (((src >> 16) & 0xFF) * sa + ((dst >> 16) & 0xFF) * da * (255 - sa) / 255) / oa;
			int gr = (((src >> 8) & 0xFF) * sa + ((dst >> 8) & 0xFF) * da * (255 - sa) / 255) / oa;
			int bl = ((src & 0xFF) * sa + (dst & 0xFF) * da * (255 - sa) / 255) / oa;
			return oa << 24 | r << 16 | gr << 8 | bl;
		}
	}

	private static int y(double v, double lo, double hi, int h) {
		return h - (int) Math.round((v - lo) / (hi - lo) * h);
	}

	/** Same polyline technique as the full graph: per-column min/max plus connecting segments. */
	private static void line(Canvas out, Downsampler.Columns c, int h, double lo, double hi, int color) {
		int prevCol = -1;
		double prevVal = Double.NaN;
		for (int col = 0; col < c.width(); col++) {
			if (c.count()[col] == 0) continue;
			if (prevCol >= 0) {
				int span = col - prevCol;
				int yPrev = y(prevVal, lo, hi, h);
				for (int k = 1; k <= span; k++) {
					int yy = y(prevVal + (c.first()[col] - prevVal) * k / span, lo, hi, h);
					out.vline(prevCol + k, yPrev, yy, color);
					yPrev = yy;
				}
			}
			out.vline(col, y(c.min()[col], lo, hi, h), y(c.max()[col], lo, hi, h), color);
			prevCol = col;
			prevVal = c.last()[col];
		}
	}

	private static void band(Canvas out, long[] t, double[] mins, double[] maxs, long tMin, long tMax, int w, int h,
							 double lo, double hi, int fill) {
		for (int col = 0; col < w; col++) {
			long tc = tMin + (long) ((double) col / Math.max(1, w - 1) * (tMax - tMin));
			int i = java.util.Arrays.binarySearch(t, tc);
			if (i < 0) i = -i - 2;
			if (i < 0 || Double.isNaN(mins[i]) || Double.isNaN(maxs[i])) continue;
			int ya = Math.max(0, y(maxs[i], lo, hi, h));
			int yb = Math.min(h, y(mins[i], lo, hi, h));
			if (ya > h || yb < 0) continue;
			out.fill(col, ya, col + 1, Math.max(ya, yb) + 1, fill);
		}
	}
}
