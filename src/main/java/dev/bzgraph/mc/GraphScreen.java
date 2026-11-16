package dev.bzgraph.mc;

import dev.bzgraph.BzGraphClient;
import dev.bzgraph.core.CachedHistory;
import dev.bzgraph.core.CoinFormat;
import dev.bzgraph.core.Downsampler;
import dev.bzgraph.core.HistoryHandle;
import dev.bzgraph.core.PatchNote;
import dev.bzgraph.core.PriceImpact;
import dev.bzgraph.core.PricePoint;
import dev.bzgraph.core.TimeRange;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Full-screen bazaar price-history graph. */
public final class GraphScreen extends Screen {
	private static final String ATTRIBUTION = "Data: SkyCofl (sky.coflnet.com)";

	private static final int COLOR_BUY = 0xFFFFAA00;
	private static final int COLOR_SELL = 0xFF55FFFF;
	private static final int COLOR_TEXT = 0xFFFFFFFF;
	private static final int COLOR_DIM = 0xFFAAAAAA;
	private static final int COLOR_GRID = 0x30FFFFFF;
	private static final int COLOR_AXIS = 0x80FFFFFF;
	private static final int COLOR_PANEL = 0xA0000000;
	private static final int COLOR_ERROR = 0xFFFF5555;
	private static final int COLOR_WARN = 0xFFFFFF55;
	private static final int COLOR_LINK = 0xFF55AAFF;
	private static final int Y_AXIS_W = 42;
	private static final int COLOR_PATCH = 0xFFFF55FF;
	private static final int COLOR_PATCH_LINE = 0x90FF55FF;
	private static final int COLOR_PATCH_OTHER = 0x40FFFFFF;
	/** Markers closer than this many pixels are merged into one cluster. */
	private static final int CLUSTER_PX = 5;

	private static final DateTimeFormatter FMT_TOOLTIP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);
	private static final DateTimeFormatter FMT_TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
	private static final DateTimeFormatter FMT_DAY = DateTimeFormatter.ofPattern("MMM d", Locale.ROOT);
	private static final DateTimeFormatter FMT_MONTH = DateTimeFormatter.ofPattern("MMM yy", Locale.ROOT);
	private static final DateTimeFormatter FMT_YEAR = DateTimeFormatter.ofPattern("yyyy", Locale.ROOT);

	private final Screen parent;
	private final String productId;
	private final String displayName;
	/** Fixed error shown instead of a graph (e.g. hovered item isn't a bazaar product). */
	private final String fixedError;

	private TimeRange range;
	private HistoryHandle handle;
	private boolean showBuy;
	private boolean showSell;

	// Arrays derived from the handle's data, rebuilt when data or range changes.
	private CachedHistory builtFrom;
	private long[] times = new long[0];
	private double[] buys = new double[0];
	private double[] sells = new double[0];
	private double[] vols = new double[0];

	private final List<Button> rangeButtons = new ArrayList<>();
	private Button buyButton;
	private Button sellButton;
	private Button patchButton;

	/** Patch markers drawn in the last frame (for hover/click). */
	private record Cluster(int x, List<PatchNote> patches, boolean relevant) {
	}

	private List<Cluster> clusters = List.of();

	// Layout
	private int gx0, gy0, gx1, gy1;
	private int attrX, attrY, attrW;

	public GraphScreen(Screen parent, String productId, String displayName) {
		this(parent, productId, displayName, null);
	}

	private GraphScreen(Screen parent, String productId, String displayName, String fixedError) {
		super(Component.literal("BZgraph"));
		this.parent = parent;
		this.productId = productId;
		this.displayName = displayName;
		this.fixedError = fixedError;
		BzConfig cfg = BzGraphClient.get().config();
		this.range = cfg.defaultRange();
		this.showBuy = cfg.showBuy;
		this.showSell = cfg.showSell;
	}

	public static GraphScreen notOnBazaar(Screen parent, String displayName) {
		return new GraphScreen(parent, null, displayName, "\"" + displayName + "\" is not a bazaar item");
	}

	@Override
	protected void init() {
		int top = 8;
		int buttonsY = top + 34;
		gx0 = 10 + Y_AXIS_W;
		gx1 = width - 12;
		gy0 = buttonsY + 28;
		gy1 = height - 32;

		rangeButtons.clear();
		int x = 10;
		for (TimeRange r : TimeRange.values()) {
			Button b = Button.builder(Component.literal(r.label()), btn -> selectRange(r))
					.bounds(x, buttonsY, 32, 20).build();
			b.active = r != range;
			rangeButtons.add(addRenderableWidget(b));
			x += 34;
		}
		sellButton = addRenderableWidget(Button.builder(Component.empty(), b -> toggle(false))
				.bounds(width - 10 - 64, buttonsY, 64, 20).build());
		buyButton = addRenderableWidget(Button.builder(Component.empty(), b -> toggle(true))
				.bounds(width - 10 - 64 - 2 - 64, buttonsY, 64, 20).build());
		patchButton = addRenderableWidget(Button.builder(Component.empty(), b -> cycleMarkers())
				.bounds(width - 10 - 64 - 2 - 64 - 2 - 84, buttonsY, 84, 20).build());
		updateToggleLabels();
		boolean hasProduct = productId != null;
		buyButton.active = sellButton.active = patchButton.active = hasProduct;
		if (hasProduct) BzGraphClient.get().ensurePatches();
		rangeButtons.forEach(b -> b.active &= hasProduct);

		attrW = font.width(ATTRIBUTION);
		attrX = 10;
		attrY = height - 14;

		if (hasProduct && (handle == null || handle.range() != range)) {
			handle = BzGraphClient.get().repository().load(productId, range);
		}
	}

	private void selectRange(TimeRange r) {
		if (r == range) return;
		range = r;
		for (int i = 0; i < rangeButtons.size(); i++) rangeButtons.get(i).active = TimeRange.values()[i] != r;
		handle = BzGraphClient.get().repository().load(productId, range);
		BzConfig cfg = BzGraphClient.get().config();
		cfg.defaultRange = r.label();
		cfg.save();
	}

	private void toggle(boolean buy) {
		if (buy) showBuy = !showBuy;
		else showSell = !showSell;
		if (!showBuy && !showSell) { // never hide both
			if (buy) showSell = true;
			else showBuy = true;
		}
		updateToggleLabels();
		BzConfig cfg = BzGraphClient.get().config();
		cfg.showBuy = showBuy;
		cfg.showSell = showSell;
		cfg.save();
	}

	private void cycleMarkers() {
		BzConfig cfg = BzGraphClient.get().config();
		BzConfig.Markers next = BzConfig.Markers.values()[(cfg.patchMarkers().ordinal() + 1) % BzConfig.Markers.values().length];
		cfg.patchMarkers = next.name();
		cfg.save();
		updateToggleLabels();
	}

	private void updateToggleLabels() {
		BzConfig.Markers m = BzGraphClient.get().config().patchMarkers();
		String label = switch (m) {
			case RELEVANT -> "Patches: Item";
			case ALL -> "Patches: All";
			case OFF -> "Patches: Off";
		};
		patchButton.setMessage(Component.literal(label).withColor(m == BzConfig.Markers.OFF ? COLOR_DIM : COLOR_PATCH));
		buyButton.setMessage(Component.literal((showBuy ? "■ " : "□ ") + "Buy").withColor(showBuy ? COLOR_BUY : COLOR_DIM));
		sellButton.setMessage(Component.literal((showSell ? "■ " : "□ ") + "Sell").withColor(showSell ? COLOR_SELL : COLOR_DIM));
	}

	// ---------------------------------------------------------------- input

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (BzGraphClient.get().openKey().matches(event)) {
			onClose();
			return true;
		}
		int k = event.key();
		if (productId != null && k >= GLFW.GLFW_KEY_1 && k <= GLFW.GLFW_KEY_5) {
			selectRange(TimeRange.values()[k - GLFW.GLFW_KEY_1]);
			return true;
		}
		if (productId != null && k == GLFW.GLFW_KEY_P) {
			openPatchList(null);
			return true;
		}
		if (productId != null && k == GLFW.GLFW_KEY_R && handle != null && !handle.isBusy()) {
			BzGraphClient.get().repository().refresh(handle);
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == 0 && overAttribution(event.x(), event.y())) {
			ConfirmLinkScreen.confirmLinkNow(this, BzGraphClient.COFL_URL);
			return true;
		}
		Cluster c = event.button() == 0 ? hoveredCluster(event.x(), event.y()) : null;
		if (c != null) {
			if (c.patches().size() == 1) {
				minecraft.gui.setScreen(new PatchDetailScreen(this, c.patches().getFirst(), productId, historyPoints(), range));
			} else {
				openPatchList(c.patches());
			}
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	private List<PricePoint> historyPoints() {
		CachedHistory d = handle == null ? null : handle.data();
		return d == null ? List.of() : d.points();
	}

	private void openPatchList(List<PatchNote> preset) {
		minecraft.gui.setScreen(new PatchListScreen(this, preset, productId, historyPoints(), range));
	}

	private Cluster hoveredCluster(double mx, double my) {
		for (Cluster c : clusters) {
			double dx = Math.abs(mx - c.x());
			if (dx <= 3 && my >= gy0 - 8 && my <= gy0 + 3) return c;
			if (dx <= 1 && my >= gy0 && my <= gy1) return c;
		}
		return null;
	}

	private boolean overAttribution(double mx, double my) {
		return mx >= attrX && mx <= attrX + attrW && my >= attrY - 1 && my <= attrY + font.lineHeight;
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// ---------------------------------------------------------------- rendering

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		g.fill(gx0 - Y_AXIS_W - 2, gy0 - 4, gx1 + 2, gy1 + 14, COLOR_PANEL);
		drawHeader(g);
		drawAttribution(g, mouseX, mouseY);

		if (fixedError != null) {
			drawCentered(g, fixedError, COLOR_ERROR);
			super.extractRenderState(g, mouseX, mouseY, partialTick);
			return;
		}

		HistoryHandle h = handle;
		CachedHistory data = h == null ? null : h.data();
		rebuildArrays(data);
		HistoryHandle.Status status = h == null ? HistoryHandle.Status.LOADING : h.status();

		if (times.length == 0) {
			if (status == HistoryHandle.Status.LOADING || status == HistoryHandle.Status.REFRESHING) {
				drawSpinner(g, (gx0 + gx1) / 2, (gy0 + gy1) / 2 - 6);
				g.centeredText(font, "Loading " + range.label() + " history…", (gx0 + gx1) / 2, (gy0 + gy1) / 2 + 12, COLOR_DIM);
			} else if (status == HistoryHandle.Status.RATE_LIMITED) {
				drawSpinner(g, (gx0 + gx1) / 2, (gy0 + gy1) / 2 - 6);
				g.centeredText(font, h.message(), (gx0 + gx1) / 2, (gy0 + gy1) / 2 + 12, COLOR_WARN);
			} else if (status == HistoryHandle.Status.READY) {
				drawCentered(g, "No data for this range", COLOR_DIM);
			} else {
				drawCentered(g, h.message(), COLOR_ERROR);
			}
		} else {
			drawGraph(g, mouseX, mouseY);
			drawStatusBadge(g, h);
		}
		super.extractRenderState(g, mouseX, mouseY, partialTick);
	}

	private void drawHeader(GuiGraphicsExtractor g) {
		String name = displayName != null && !displayName.isBlank() ? displayName : productId;
		String title = name == null ? "BZgraph" : name;
		if (productId != null && displayName != null && !productId.equals(displayName)) {
			g.text(font, Component.literal(title).append(Component.literal("  " + productId).withStyle(ChatFormatting.DARK_GRAY)), 10, 8, COLOR_TEXT);
		} else {
			g.text(font, title, 10, 8, COLOR_TEXT);
		}
		if (times.length == 0) return;
		int y = 20;
		int x = 10;
		if (showBuy) x = drawStats(g, "Buy", buys, COLOR_BUY, x, y) + 16;
		if (showSell) drawStats(g, "Sell", sells, COLOR_SELL, x, y);
	}

	private int drawStats(GuiGraphicsExtractor g, String label, double[] vals, int color, int x, int y) {
		double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY, cur = Double.NaN;
		for (double v : vals) {
			if (Double.isNaN(v)) continue;
			min = Math.min(min, v);
			max = Math.max(max, v);
			cur = v;
		}
		if (Double.isNaN(cur)) return x;
		Component c = Component.literal(label + " ").withColor(color)
				.append(Component.literal(CoinFormat.compact(cur)).withColor(COLOR_TEXT))
				.append(Component.literal("  min ").withColor(COLOR_DIM))
				.append(Component.literal(CoinFormat.compact(min)).withColor(COLOR_TEXT))
				.append(Component.literal("  max ").withColor(COLOR_DIM))
				.append(Component.literal(CoinFormat.compact(max)).withColor(COLOR_TEXT));
		g.text(font, c, x, y, COLOR_TEXT);
		return x + font.width(c);
	}

	private void drawAttribution(GuiGraphicsExtractor g, int mx, int my) {
		boolean hover = overAttribution(mx, my);
		Component c = Component.literal(ATTRIBUTION).withStyle(s -> s.withUnderlined(hover));
		g.text(font, c, attrX, attrY, hover ? COLOR_TEXT : COLOR_LINK);
		String hint = productId == null ? "" : "1-5: range   P: patch notes   R: refresh   Esc: close";
		g.text(font, hint, width - 10 - font.width(hint), attrY, 0xFF666666);
	}

	private void drawCentered(GuiGraphicsExtractor g, String msg, int color) {
		g.centeredText(font, msg, (gx0 + gx1) / 2, (gy0 + gy1) / 2 - 4, color);
	}

	private void drawSpinner(GuiGraphicsExtractor g, int cx, int cy) {
		long t = System.currentTimeMillis();
		int n = 8;
		int head = (int) ((t / 90) % n);
		for (int i = 0; i < n; i++) {
			double a = 2 * Math.PI * i / n;
			int x = cx + (int) Math.round(Math.cos(a) * 8);
			int y = cy + (int) Math.round(Math.sin(a) * 8);
			int age = (head - i + n) % n;
			int alpha = Math.max(40, 255 - age * 30);
			g.fill(x - 1, y - 1, x + 2, y + 2, (alpha << 24) | 0xFFFFFF);
		}
	}

	private void drawStatusBadge(GuiGraphicsExtractor g, HistoryHandle h) {
		if (h == null) return;
		String msg;
		int color;
		switch (h.status()) {
			case REFRESHING, LOADING -> {
				msg = "Refreshing…";
				color = COLOR_DIM;
			}
			case RATE_LIMITED -> {
				msg = h.message();
				color = COLOR_WARN;
			}
			case OFFLINE, ERROR, NOT_FOUND -> {
				msg = h.message() + " (showing cached data)";
				color = COLOR_ERROR;
			}
			default -> {
				return;
			}
		}
		int w = font.width(msg);
		g.text(font, msg, width - 10 - w, 8, color);
		if (h.isBusy()) drawSpinnerSmall(g, width - 10 - w - 8, 11);
	}

	private void drawSpinnerSmall(GuiGraphicsExtractor g, int cx, int cy) {
		int phase = (int) ((System.currentTimeMillis() / 120) % 4);
		int[][] pos = {{0, -3}, {3, 0}, {0, 3}, {-3, 0}};
		for (int i = 0; i < 4; i++) {
			int col = i == phase ? 0xFFFFFFFF : 0x60FFFFFF;
			g.fill(cx + pos[i][0] - 1, cy + pos[i][1] - 1, cx + pos[i][0] + 1, cy + pos[i][1] + 1, col);
		}
	}

	private void rebuildArrays(CachedHistory data) {
		if (data == builtFrom) return;
		builtFrom = data;
		if (data == null) {
			times = new long[0];
			buys = sells = vols = new double[0];
			return;
		}
		List<PricePoint> pts = data.points();
		int n = pts.size();
		times = new long[n];
		buys = new double[n];
		sells = new double[n];
		vols = new double[n];
		for (int i = 0; i < n; i++) {
			PricePoint p = pts.get(i);
			times[i] = p.timestamp().toEpochMilli();
			buys[i] = p.buy();
			sells[i] = p.sell();
			vols[i] = p.volume();
		}
	}

	private void drawGraph(GuiGraphicsExtractor g, int mx, int my) {
		long tMin = times[0];
		long tMax = times[times.length - 1];
		if (tMax == tMin) tMax = tMin + 1;

		double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
		for (int i = 0; i < times.length; i++) {
			if (showBuy && !Double.isNaN(buys[i])) {
				lo = Math.min(lo, buys[i]);
				hi = Math.max(hi, buys[i]);
			}
			if (showSell && !Double.isNaN(sells[i])) {
				lo = Math.min(lo, sells[i]);
				hi = Math.max(hi, sells[i]);
			}
		}
		if (lo == Double.POSITIVE_INFINITY) {
			drawCentered(g, "No price data", COLOR_DIM);
			return;
		}
		if (hi - lo < 1e-9) {
			double pad = Math.max(Math.abs(hi) * 0.05, 0.1);
			lo -= pad;
			hi += pad;
		}
		double[] ticks = niceTicks(lo, hi, Math.max(2, (gy1 - gy0) / 30));
		lo = Math.min(lo, ticks[0]);
		hi = Math.max(hi, ticks[ticks.length - 1]);

		// grid + y labels
		for (double t : ticks) {
			int y = yFor(t, lo, hi);
			g.horizontalLine(gx0, gx1, y, COLOR_GRID);
			String s = CoinFormat.compact(t);
			g.text(font, s, gx0 - 4 - font.width(s), y - font.lineHeight / 2, COLOR_DIM);
		}
		// x labels
		int labels = Math.max(2, (gx1 - gx0) / 70);
		DateTimeFormatter fmt = xFormatter(tMax - tMin);
		for (int i = 0; i <= labels; i++) {
			int x = gx0 + (gx1 - gx0) * i / labels;
			long t = tMin + (long) ((tMax - tMin) * (double) i / labels);
			String s = fmt.format(Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()));
			int w = font.width(s);
			int tx = Math.max(gx0 - w / 2, Math.min(x - w / 2, gx1 - w));
			g.verticalLine(x, gy0, gy1, COLOR_GRID);
			g.text(font, s, tx, gy1 + 3, COLOR_DIM);
		}
		g.verticalLine(gx0, gy0, gy1, COLOR_AXIS);
		g.horizontalLine(gx0, gx1, gy1, COLOR_AXIS);

		int w = gx1 - gx0 + 1;
		if (showSell) drawSeries(g, Downsampler.columns(times, sells, tMin, tMax, w), lo, hi, COLOR_SELL);
		if (showBuy) drawSeries(g, Downsampler.columns(times, buys, tMin, tMax, w), lo, hi, COLOR_BUY);

		drawPatchMarkers(g, tMin, tMax);
		Cluster hc = hoveredCluster(mx, my);
		if (hc != null) {
			drawPatchTooltip(g, hc, mx, my);
		} else if (mx >= gx0 && mx <= gx1 && my >= gy0 && my <= gy1) {
			drawCrosshair(g, mx, my, tMin, tMax, lo, hi);
		}
	}

	private void drawPatchMarkers(GuiGraphicsExtractor g, long tMin, long tMax) {
		BzConfig.Markers mode = BzGraphClient.get().config().patchMarkers();
		if (mode == BzConfig.Markers.OFF) {
			clusters = List.of();
			return;
		}
		List<PatchNote> visible = BzGraphClient.get().patches().current()
				.between(Instant.ofEpochMilli(tMin), Instant.ofEpochMilli(tMax));
		List<Cluster> out = new ArrayList<>();
		List<PatchNote> cur = new ArrayList<>();
		int curX = Integer.MIN_VALUE;
		boolean curRel = false;
		for (PatchNote p : visible) {
			boolean rel = p.affects(productId);
			if (mode == BzConfig.Markers.RELEVANT && !rel) continue;
			int x = gx0 + (int) Math.round((double) (p.date().toEpochMilli() - tMin) / (tMax - tMin) * (gx1 - gx0));
			if (!cur.isEmpty() && x - curX > CLUSTER_PX) {
				out.add(new Cluster(curX, List.copyOf(cur), curRel));
				cur.clear();
				curRel = false;
			}
			if (cur.isEmpty()) curX = x;
			cur.add(p);
			curRel |= rel;
		}
		if (!cur.isEmpty()) out.add(new Cluster(curX, List.copyOf(cur), curRel));
		clusters = out;

		for (Cluster c : out) {
			int x = c.x();
			g.verticalLine(x, gy0, gy1, c.relevant() ? COLOR_PATCH_LINE : COLOR_PATCH_OTHER);
			int col = c.relevant() ? COLOR_PATCH : 0xFFAAAAAA;
			// small flag above the plot; wider with a dark centre when several patches are merged
			int half = c.patches().size() > 1 ? 3 : 2;
			g.fill(x - half, gy0 - 7, x + half + 1, gy0 - 2, col);
			if (c.patches().size() > 1) g.fill(x - 1, gy0 - 6, x + 2, gy0 - 3, 0xFF000000);
		}
	}

	private void drawPatchTooltip(GuiGraphicsExtractor g, Cluster c, int mx, int my) {
		g.verticalLine(c.x(), gy0, gy1, 0xFFFFFFFF);
		List<FormattedCharSequence> lines = new ArrayList<>();
		int wrap = Math.max(160, Math.min(260, width / 3));
		if (c.patches().size() == 1) {
			PatchNote p = c.patches().getFirst();
			lines.addAll(font.split(Component.literal(p.title()).withColor(c.relevant() ? COLOR_PATCH : COLOR_TEXT), wrap));
			lines.add(Component.literal(PatchDetailScreen.DATE.format(p.date().atZone(ZoneId.systemDefault()))
					+ (p.version() != null ? "  v" + p.version() : "")).withColor(COLOR_DIM).getVisualOrderText());
			if (!p.summary().isEmpty()) {
				List<FormattedCharSequence> sum = font.split(Component.literal(p.summary()).withColor(0xFFDDDDDD), wrap);
				lines.addAll(sum.subList(0, Math.min(3, sum.size())));
				if (sum.size() > 3) lines.add(Component.literal("...").withColor(COLOR_DIM).getVisualOrderText());
			}
			List<PricePoint> pts = historyPoints();
			if (!pts.isEmpty()) {
				Duration win = PriceImpact.windowFor(range);
				PriceImpact pi = PriceImpact.of(pts, p.date(), win);
				lines.add(Component.literal("Median " + PatchDetailScreen.windowLabel(win) + " before -> after:").withColor(COLOR_DIM).getVisualOrderText());
				if (showBuy) lines.add(PatchDetailScreen.impactLine("Buy ", COLOR_BUY, pi.buyBefore(), pi.buyAfter()).getVisualOrderText());
				if (showSell) lines.add(PatchDetailScreen.impactLine("Sell", COLOR_SELL, pi.sellBefore(), pi.sellAfter()).getVisualOrderText());
			}
			lines.add(Component.literal("Click for details").withColor(0xFF777777).getVisualOrderText());
		} else {
			lines.add(Component.literal(c.patches().size() + " patch notes").withColor(c.relevant() ? COLOR_PATCH : COLOR_TEXT).getVisualOrderText());
			int shown = Math.min(6, c.patches().size());
			for (int i = 0; i < shown; i++) {
				PatchNote p = c.patches().get(i);
				String t = font.plainSubstrByWidth(p.title(), wrap);
				lines.add(Component.literal(t).withColor(p.affects(productId) ? COLOR_PATCH : 0xFFDDDDDD).getVisualOrderText());
			}
			if (c.patches().size() > shown) lines.add(Component.literal("+" + (c.patches().size() - shown) + " more").withColor(COLOR_DIM).getVisualOrderText());
			lines.add(Component.literal("Click to list").withColor(0xFF777777).getVisualOrderText());
		}
		g.setTooltipForNextFrame(font, lines, mx, my);
	}

	private int yFor(double v, double lo, double hi) {
		return gy1 - (int) Math.round((v - lo) / (hi - lo) * (gy1 - gy0));
	}

	/** Draws a 1px polyline from per-column aggregates using only axis-aligned fills. */
	private void drawSeries(GuiGraphicsExtractor g, Downsampler.Columns c, double lo, double hi, int color) {
		int prevCol = -1;
		double prevVal = Double.NaN;
		for (int col = 0; col < c.width(); col++) {
			if (c.count()[col] == 0) continue;
			int x = gx0 + col;
			if (prevCol >= 0) {
				// connect previous column's last value to this column's first value
				double from = prevVal, to = c.first()[col];
				int span = col - prevCol;
				int yPrev = yFor(from, lo, hi);
				for (int k = 1; k <= span; k++) {
					double v = from + (to - from) * k / span;
					int y = yFor(v, lo, hi);
					vline(g, gx0 + prevCol + k, yPrev, y, color);
					yPrev = y;
				}
			}
			vline(g, x, yFor(c.min()[col], lo, hi), yFor(c.max()[col], lo, hi), color);
			prevCol = col;
			prevVal = c.last()[col];
		}
	}

	private static void vline(GuiGraphicsExtractor g, int x, int y0, int y1, int color) {
		g.fill(x, Math.min(y0, y1), x + 1, Math.max(y0, y1) + 1, color);
	}

	private void drawCrosshair(GuiGraphicsExtractor g, int mx, int my, long tMin, long tMax, double lo, double hi) {
		long t = tMin + Math.round((double) (mx - gx0) / (gx1 - gx0) * (tMax - tMin));
		int idx = Arrays.binarySearch(times, t);
		if (idx < 0) {
			int ins = -idx - 1;
			if (ins <= 0) idx = 0;
			else if (ins >= times.length) idx = times.length - 1;
			else idx = (t - times[ins - 1] <= times[ins] - t) ? ins - 1 : ins;
		}
		int px = gx0 + (int) Math.round((double) (times[idx] - tMin) / (tMax - tMin) * (gx1 - gx0));
		g.verticalLine(px, gy0, gy1, 0x90FFFFFF);
		g.horizontalLine(gx0, gx1, my, 0x40FFFFFF);
		if (showBuy && !Double.isNaN(buys[idx])) dot(g, px, yFor(buys[idx], lo, hi), COLOR_BUY);
		if (showSell && !Double.isNaN(sells[idx])) dot(g, px, yFor(sells[idx], lo, hi), COLOR_SELL);

		List<Component> lines = new ArrayList<>();
		lines.add(Component.literal(FMT_TOOLTIP.format(Instant.ofEpochMilli(times[idx]).atZone(ZoneId.systemDefault()))).withColor(COLOR_TEXT));
		lines.add(Component.literal("Buy:  ").withColor(COLOR_BUY).append(Component.literal(CoinFormat.full(buys[idx])).withColor(COLOR_TEXT)));
		lines.add(Component.literal("Sell: ").withColor(COLOR_SELL).append(Component.literal(CoinFormat.full(sells[idx])).withColor(COLOR_TEXT)));
		lines.add(Component.literal("Volume: ").withColor(COLOR_DIM).append(Component.literal(CoinFormat.compact(vols[idx])).withColor(COLOR_TEXT)));
		g.setComponentTooltipForNextFrame(font, lines, mx, my);
	}

	private static void dot(GuiGraphicsExtractor g, int x, int y, int color) {
		g.fill(x - 2, y - 2, x + 3, y + 3, 0xFF000000);
		g.fill(x - 1, y - 1, x + 2, y + 2, color);
	}

	private static DateTimeFormatter xFormatter(long spanMillis) {
		long day = 86_400_000L;
		if (spanMillis <= 2 * day) return FMT_TIME;
		if (spanMillis <= 90 * day) return FMT_DAY;
		if (spanMillis <= 3 * 365 * day) return FMT_MONTH;
		return FMT_YEAR;
	}

	/** "Nice" tick values (1/2/5 × 10^n) covering [lo, hi]. */
	static double[] niceTicks(double lo, double hi, int target) {
		double raw = (hi - lo) / Math.max(1, target);
		double mag = Math.pow(10, Math.floor(Math.log10(raw)));
		double norm = raw / mag;
		double step = (norm < 1.5 ? 1 : norm < 3 ? 2 : norm < 7 ? 5 : 10) * mag;
		double start = Math.floor(lo / step) * step;
		double end = Math.ceil(hi / step) * step;
		int n = (int) Math.round((end - start) / step) + 1;
		double[] out = new double[Math.max(2, Math.min(n, 50))];
		for (int i = 0; i < out.length; i++) out[i] = start + i * step;
		return out;
	}
}
