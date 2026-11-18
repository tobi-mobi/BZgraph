package dev.bzgraph.mc;

import dev.bzgraph.BzGraphClient;
import dev.bzgraph.core.CoinFormat;
import dev.bzgraph.core.TimeRange;
import dev.bzgraph.core.purse.PurseStore;
import dev.bzgraph.core.trade.Trade;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Your purse over time (from the SkyBlock sidebar), with your bazaar trades marked on it. */
public final class PurseScreen extends Screen implements BzScreen {
	private static final int COLOR_LINE = 0xFFFFD700;
	private static final int COLOR_TEXT = 0xFFFFFFFF;
	private static final int COLOR_DIM = 0xFFAAAAAA;
	private static final int COLOR_GRID = 0x30FFFFFF;
	private static final int COLOR_AXIS = 0x80FFFFFF;
	private static final int COLOR_PANEL_BG = 0xE0101014;
	private static final int COLOR_PANEL = 0xA0000000;
	private static final int COLOR_PANEL_BORDER = 0xFF3A3A44;
	private static final int COLOR_UP = 0xFF55FF55;
	private static final int COLOR_DOWN = 0xFFFF5555;
	private static final int PAD = 8;
	private static final int Y_AXIS_W = 42;
	private static final int TRADE_CLUSTER_PX = 4;
	private static final DateTimeFormatter FMT_TOOLTIP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);
	private static final DateTimeFormatter FMT_TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
	private static final DateTimeFormatter FMT_DAY = DateTimeFormatter.ofPattern("MMM d", Locale.ROOT);
	private static final DateTimeFormatter FMT_MONTH = DateTimeFormatter.ofPattern("MMM yy", Locale.ROOT);
	private static final DateTimeFormatter FMT_YEAR = DateTimeFormatter.ofPattern("yyyy", Locale.ROOT);

	private final Screen root;
	private final Screen back;
	private TimeRange range;
	private String profile;
	private final List<Button> rangeButtons = new ArrayList<>();
	private Button profileButton;
	private Button tradesButton;

	private int px0, py0, px1, py1, gx0, gy0, gx1, gy1;

	private record TradeCluster(Trade.Side side, int x, int y, List<Trade> trades) {
	}

	private List<TradeCluster> tradeClusters = List.of();

	/**
	 * @param root screen Esc returns to (null = game)
	 * @param back screen the back arrow returns to (e.g. the item graph), or null
	 */
	public PurseScreen(Screen root, Screen back) {
		super(Component.literal("BZgraph purse"));
		this.root = root;
		this.back = back;
		this.range = BzGraphClient.get().config().purseRange();
		PurseStore store = BzGraphClient.get().purse().store();
		String acc = TradeTracker.account();
		String last = store.lastProfile(acc);
		List<String> profiles = store.profiles(acc);
		this.profile = last != null ? last : profiles.isEmpty() ? null : profiles.getFirst();
	}

	@Override
	protected void init() {
		int pw = Math.min(width - 16, Math.max(470, Math.min(600, Math.round(width * 0.72f))));
		int ph = Math.min(height - 16, Math.max(240, Math.min(330, Math.round(height * 0.78f))));
		px0 = (width - pw) / 2;
		py0 = (height - ph) / 2;
		px1 = px0 + pw;
		py1 = py0 + ph;
		int buttonsY = py0 + 32;
		gx0 = px0 + PAD + Y_AXIS_W;
		gx1 = px1 - PAD - 2;
		gy0 = buttonsY + 30;
		gy1 = py1 - 26;

		rangeButtons.clear();
		int x = px0 + PAD;
		for (TimeRange r : TimeRange.values()) {
			Button b = Button.builder(Component.literal(r.label()), btn -> selectRange(r)).bounds(x, buttonsY, 32, 20).build();
			b.active = r != range;
			rangeButtons.add(addRenderableWidget(b));
			x += 34;
		}
		int bx = px1 - PAD;
		tradesButton = addRenderableWidget(Button.builder(Component.empty(), b -> toggleTrades())
				.bounds(bx -= 56, buttonsY, 56, 20).build());
		tradesButton.setTooltip(Tooltip.create(Component.literal("Your bazaar trades (all items): ▼ sells, ▲ buys")));
		profileButton = addRenderableWidget(Button.builder(Component.empty(), b -> cycleProfile())
				.bounds(bx -= 2 + 100, buttonsY, 100, 20).build());
		profileButton.setTooltip(Tooltip.create(Component.literal("SkyBlock profile (click to switch)")));
		Button backBtn = addRenderableWidget(Button.builder(Component.literal("<"), b -> goBack())
				.bounds(px0 + PAD - 2, py0 + 4, 14, 14).build());
		backBtn.active = back != null;
		updateLabels();
	}

	private void updateLabels() {
		List<String> profiles = BzGraphClient.get().purse().store().profiles(TradeTracker.account());
		profileButton.setMessage(Component.literal(profile == null ? "No profile" : profile));
		profileButton.active = profiles.size() > 1 || (profile != null && !profiles.contains(profile) && !profiles.isEmpty());
		boolean trades = BzGraphClient.get().config().showTrades;
		tradesButton.setMessage(Component.literal((trades ? "■ " : "□ ") + "Trades").withColor(trades ? COLOR_TEXT : COLOR_DIM));
	}

	private void cycleProfile() {
		List<String> profiles = BzGraphClient.get().purse().store().profiles(TradeTracker.account());
		if (profiles.isEmpty()) return;
		int i = profiles.indexOf(profile);
		profile = profiles.get((i + 1) % profiles.size());
		updateLabels();
	}

	private void toggleTrades() {
		BzConfig cfg = BzGraphClient.get().config();
		cfg.showTrades = !cfg.showTrades;
		cfg.save();
		updateLabels();
	}

	private void selectRange(TimeRange r) {
		range = r;
		for (int i = 0; i < rangeButtons.size(); i++) rangeButtons.get(i).active = TimeRange.values()[i] != r;
		BzConfig cfg = BzGraphClient.get().config();
		cfg.purseRange = r.label();
		cfg.save();
	}

	private void goBack() {
		if (back != null) minecraft.gui.setScreen(back);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (BzGraphClient.get().openKey().matches(event)) {
			onClose();
			return true;
		}
		int k = event.key();
		if (k >= GLFW.GLFW_KEY_1 && k <= GLFW.GLFW_KEY_5) {
			selectRange(TimeRange.values()[k - GLFW.GLFW_KEY_1]);
			return true;
		}
		if (k == GLFW.GLFW_KEY_LEFT && back != null) {
			goBack();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(root);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// ---------------------------------------------------------------- rendering

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partialTick) {
		BzConfig cfg = BzGraphClient.get().config();
		g.fill(px0, py0, px1, py1, cfg.background(COLOR_PANEL_BG, 1.0));
		g.horizontalLine(px0, px1 - 1, py0, COLOR_PANEL_BORDER);
		g.horizontalLine(px0, px1 - 1, py1 - 1, COLOR_PANEL_BORDER);
		g.verticalLine(px0, py0, py1 - 1, COLOR_PANEL_BORDER);
		g.verticalLine(px1 - 1, py0, py1 - 1, COLOR_PANEL_BORDER);
		g.fill(gx0, gy0, gx1 + 1, gy1, cfg.background(COLOR_PANEL, 0.5));
		g.text(font, "Purse", px0 + PAD + 18, py0 + PAD, COLOR_TEXT);
		String hint = "1-5 range · from your SkyBlock sidebar";
		g.text(font, hint, px1 - PAD - font.width(hint), py1 - 12, 0xFF666666);

		List<PurseStore.Point> all = BzGraphClient.get().purse().store().points(TradeTracker.account(), profile);
		long now = System.currentTimeMillis();
		Instant start = range.start(Instant.ofEpochMilli(now));
		if (all.isEmpty()) {
			String msg = cfg.trackPurse ? "No purse data yet. It's recorded from the sidebar while you play SkyBlock."
					: "Purse tracking is off (\"trackPurse\" in config/bzgraph/config.json).";
			g.centeredText(font, msg, (gx0 + gx1) / 2, (gy0 + gy1) / 2 - 4, COLOR_DIM);
			super.extractRenderState(g, mx, my, partialTick);
			return;
		}
		long tMin = Math.max(start.toEpochMilli(), all.getFirst().time());
		long tMax = Math.max(now, tMin + 60_000);
		// visible steps: the value in force at tMin, then every change after it, then "now"
		List<Double> vals = new ArrayList<>();
		double startVal = PurseStore.valueAt(all, Instant.ofEpochMilli(tMin));
		long[] times;
		double[] values;
		{
			List<Long> ts = new ArrayList<>();
			if (!Double.isNaN(startVal)) {
				ts.add(tMin);
				vals.add(startVal);
			}
			for (PurseStore.Point p : all) {
				if (p.time() <= tMin || p.time() > tMax) continue;
				ts.add(p.time());
				vals.add(p.coins());
			}
			times = new long[ts.size()];
			values = new double[ts.size()];
			for (int i = 0; i < ts.size(); i++) {
				times[i] = ts.get(i);
				values[i] = vals.get(i);
			}
		}
		drawHeader(g, times, values, tMin, tMax);
		drawGraph(g, mx, my, times, values, tMin, tMax);
		super.extractRenderState(g, mx, my, partialTick);
	}

	private List<Trade> allTrades = List.of();
	private long allTradesVersion = -1;

	private List<Trade> tradesBetween(long tMin, long tMax) {
		long v = BzGraphClient.get().trades().service().store().version();
		if (v != allTradesVersion) {
			allTrades = BzGraphClient.get().trades().all();
			allTradesVersion = v;
		}
		List<Trade> out = new ArrayList<>();
		for (Trade t : allTrades) {
			long ms = t.time().toEpochMilli();
			if (ms >= tMin && ms <= tMax) out.add(t);
		}
		return out;
	}

	private void drawHeader(GuiGraphicsExtractor g, long[] times, double[] values, long tMin, long tMax) {
		if (values.length == 0) return;
		double first = values[0], cur = values[values.length - 1];
		double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
		for (double v : values) {
			min = Math.min(min, v);
			max = Math.max(max, v);
		}
		double change = cur - first;
		int y = py0 + PAD + 11;
		Component c = Component.literal("Now ").withColor(COLOR_LINE)
				.append(Component.literal(CoinFormat.compact(cur)).withColor(COLOR_TEXT))
				.append(Component.literal("  change ").withColor(COLOR_DIM))
				.append(Component.literal((change >= 0 ? "+" : "") + CoinFormat.compact(change)
						+ (first != 0 ? String.format(Locale.ROOT, " (%+.1f%%)", change / first * 100) : ""))
						.withColor(change >= 0 ? COLOR_UP : COLOR_DOWN))
				.append(Component.literal("  min ").withColor(COLOR_DIM))
				.append(Component.literal(CoinFormat.compact(min)).withColor(COLOR_TEXT))
				.append(Component.literal("  max ").withColor(COLOR_DIM))
				.append(Component.literal(CoinFormat.compact(max)).withColor(COLOR_TEXT));
		g.text(font, c, px0 + PAD, y, COLOR_TEXT);

		// bazaar coins in/out over the same period (all profiles: trades aren't tied to a profile)
		double in = 0, out = 0;
		int n = 0;
		for (Trade t : tradesBetween(tMin, tMax)) {
			if (Double.isNaN(t.coins())) continue;
			n++;
			if (t.side() == Trade.Side.SELL) in += t.coins();
			else out += t.coins();
		}
		if (n > 0) {
			Component b = Component.literal("Bazaar: sold ").withColor(COLOR_DIM)
					.append(Component.literal(CoinFormat.compact(in)).withColor(COLOR_UP))
					.append(Component.literal(" · bought ").withColor(COLOR_DIM))
					.append(Component.literal(CoinFormat.compact(out)).withColor(COLOR_DOWN))
					.append(Component.literal(" · " + n + " trades").withColor(COLOR_DIM));
			int w = font.width(b);
			g.text(font, b, px1 - PAD - w, py0 + PAD, COLOR_TEXT);
		}
	}

	private void drawGraph(GuiGraphicsExtractor g, int mx, int my, long[] times, double[] values, long tMin, long tMax) {
		if (values.length == 0) {
			g.centeredText(font, "No purse data in this range", (gx0 + gx1) / 2, (gy0 + gy1) / 2 - 4, COLOR_DIM);
			return;
		}
		double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
		for (double v : values) {
			lo = Math.min(lo, v);
			hi = Math.max(hi, v);
		}
		if (hi - lo < 1e-9) {
			double pad = Math.max(Math.abs(hi) * 0.05, 1);
			lo -= pad;
			hi += pad;
		}
		double[] ticks = GraphScreen.niceTicks(lo, hi, Math.max(2, (gy1 - gy0) / 30));
		lo = Math.min(lo, ticks[0]);
		hi = Math.max(hi, ticks[ticks.length - 1]);
		for (double t : ticks) {
			int y = yFor(t, lo, hi);
			g.horizontalLine(gx0, gx1, y, COLOR_GRID);
			String s = CoinFormat.compact(t);
			g.text(font, s, gx0 - 4 - font.width(s), y - font.lineHeight / 2, COLOR_DIM);
		}
		int labels = Math.max(2, (gx1 - gx0) / 70);
		DateTimeFormatter fmt = xFormatter(tMax - tMin);
		for (int i = 0; i <= labels; i++) {
			int x = gx0 + (gx1 - gx0) * i / labels;
			long t = tMin + (long) ((tMax - tMin) * (double) i / labels);
			String s = fmt.format(Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()));
			int w = font.width(s);
			g.verticalLine(x, gy0, gy1, COLOR_GRID);
			g.text(font, s, Math.max(gx0 - w / 2, Math.min(x - w / 2, gx1 - w)), gy1 + 3, COLOR_DIM);
		}
		g.verticalLine(gx0, gy0, gy1, COLOR_AXIS);
		g.horizontalLine(gx0, gx1, gy1, COLOR_AXIS);

		// Step line: each column spans the lowest-highest value in force during it.
		int w = gx1 - gx0 + 1;
		int i = 0;
		int prevY = Integer.MIN_VALUE;
		for (int col = 0; col < w; col++) {
			long c0 = tMin + (long) ((double) col / w * (tMax - tMin));
			long c1 = tMin + (long) ((double) (col + 1) / w * (tMax - tMin));
			while (i + 1 < times.length && times[i + 1] <= c0) i++;
			if (times[i] > c1) continue; // before the first point
			double a = values[i], b = values[i];
			int j = i + 1;
			while (j < times.length && times[j] < c1) {
				a = Math.min(a, values[j]);
				b = Math.max(b, values[j]);
				j++;
			}
			int y0 = yFor(b, lo, hi), y1 = yFor(a, lo, hi);
			if (prevY != Integer.MIN_VALUE) {
				y0 = Math.min(y0, prevY);
				y1 = Math.max(y1, prevY);
			}
			g.fill(gx0 + col, y0, gx0 + col + 1, y1 + 1, COLOR_LINE);
			prevY = yFor(values[Math.max(i, j - 1)], lo, hi);
		}
		// "now" dot
		dot(g, gx1, yFor(values[values.length - 1], lo, hi), COLOR_LINE);

		drawTrades(g, times, values, tMin, tMax, lo, hi);
		TradeCluster tc = hoveredTrade(mx, my);
		if (tc != null) drawTradeTooltip(g, tc, mx, my);
		else if (mx >= gx0 && mx <= gx1 && my >= gy0 && my <= gy1) drawCrosshair(g, mx, my, times, values, tMin, tMax, lo, hi);
	}

	private void drawTrades(GuiGraphicsExtractor g, long[] times, double[] values, long tMin, long tMax, double lo, double hi) {
		tradeClusters = List.of();
		if (!BzGraphClient.get().config().showTrades) return;
		List<Trade> trades = tradesBetween(tMin, tMax);
		List<TradeCluster> out = new ArrayList<>();
		for (Trade.Side side : Trade.Side.values()) {
			List<Trade> cur = new ArrayList<>();
			int curCol = Integer.MIN_VALUE;
			for (Trade t : trades) {
				if (t.side() != side) continue;
				int x = xFor(t.time().toEpochMilli(), tMin, tMax);
				int col = (x - gx0) / TRADE_CLUSTER_PX;
				if (!cur.isEmpty() && col != curCol) {
					out.add(cluster(side, cur, times, values, tMin, tMax, lo, hi));
					cur = new ArrayList<>();
				}
				curCol = col;
				cur.add(t);
			}
			if (!cur.isEmpty()) out.add(cluster(side, cur, times, values, tMin, tMax, lo, hi));
		}
		tradeClusters = out;
		for (TradeCluster c : out) {
			boolean sell = c.side() == Trade.Side.SELL;
			int size = c.trades().size() > 1 ? 4 : 3;
			triangle(g, c.x(), c.y(), size + 1, sell, 0xFF000000);
			triangle(g, c.x(), c.y() + (sell ? -1 : 1), size, sell, sell ? COLOR_DOWN : COLOR_UP);
		}
	}

	/** Sells sit above the purse line (pointing down at it), buys below it. */
	private TradeCluster cluster(Trade.Side side, List<Trade> ts, long[] times, double[] values, long tMin, long tMax,
								 double lo, double hi) {
		long ms = ts.getLast().time().toEpochMilli();
		int x = xFor(ms, tMin, tMax);
		double v = valueAt(times, values, ms);
		int y = Double.isNaN(v) ? (gy0 + gy1) / 2 : yFor(v, lo, hi);
		y += side == Trade.Side.SELL ? -3 : 3;
		return new TradeCluster(side, x, Math.max(gy0 + 5, Math.min(gy1 - 5, y)), List.copyOf(ts));
	}

	private static double valueAt(long[] times, double[] values, long ms) {
		double v = Double.NaN;
		for (int i = 0; i < times.length && times[i] <= ms; i++) v = values[i];
		return v;
	}

	private TradeCluster hoveredTrade(double mx, double my) {
		TradeCluster best = null;
		double bestD = 7;
		for (TradeCluster c : tradeClusters) {
			double d = Math.hypot(mx - c.x(), my - (c.y() + (c.side() == Trade.Side.SELL ? -2 : 2)));
			if (d < bestD) {
				bestD = d;
				best = c;
			}
		}
		return best;
	}

	private void drawTradeTooltip(GuiGraphicsExtractor g, TradeCluster c, int mx, int my) {
		boolean sell = c.side() == Trade.Side.SELL;
		List<Component> lines = new ArrayList<>();
		ZoneId zone = ZoneId.systemDefault();
		double coins = 0;
		for (Trade t : c.trades()) if (!Double.isNaN(t.coins())) coins += t.coins();
		lines.add(Component.literal(c.trades().size() == 1 ? (sell ? "Sold" : "Bought") : c.trades().size() + (sell ? " sells" : " buys"))
				.withColor(sell ? COLOR_DOWN : COLOR_UP)
				.append(Component.literal("  " + (sell ? "+" : "-") + CoinFormat.compact(coins) + " coins").withColor(COLOR_TEXT)));
		int shown = Math.min(8, c.trades().size());
		for (int i = 0; i < shown; i++) {
			Trade t = c.trades().get(i);
			lines.add(Component.literal(t.amount() + "x " + t.item()).withColor(0xFFDDDDDD)
					.append(Component.literal(Double.isNaN(t.coins()) ? "" : "  " + CoinFormat.compact(t.coins())).withColor(COLOR_DIM)));
		}
		if (c.trades().size() > shown) lines.add(Component.literal("+" + (c.trades().size() - shown) + " more").withColor(COLOR_DIM));
		lines.add(Component.literal(FMT_TOOLTIP.format(c.trades().getFirst().time().atZone(zone))).withColor(COLOR_DIM));
		g.setComponentTooltipForNextFrame(font, lines, mx, my);
	}

	private void drawCrosshair(GuiGraphicsExtractor g, int mx, int my, long[] times, double[] values, long tMin, long tMax,
							   double lo, double hi) {
		long t = tMin + Math.round((double) (mx - gx0) / (gx1 - gx0) * (tMax - tMin));
		int i = -1;
		for (int k = 0; k < times.length && times[k] <= t; k++) i = k;
		if (i < 0) return;
		g.verticalLine(mx, gy0, gy1, 0x90FFFFFF);
		dot(g, mx, yFor(values[i], lo, hi), COLOR_LINE);
		List<Component> lines = new ArrayList<>();
		lines.add(Component.literal(FMT_TOOLTIP.format(Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()))).withColor(COLOR_TEXT));
		lines.add(Component.literal("Purse: ").withColor(COLOR_LINE).append(Component.literal(CoinFormat.full(values[i])).withColor(COLOR_TEXT)));
		if (i > 0) {
			double d = values[i] - values[i - 1];
			lines.add(Component.literal("last change ").withColor(COLOR_DIM)
					.append(Component.literal((d >= 0 ? "+" : "") + CoinFormat.compact(d)).withColor(d >= 0 ? COLOR_UP : COLOR_DOWN))
					.append(Component.literal("  at " + FMT_TOOLTIP.format(Instant.ofEpochMilli(times[i]).atZone(ZoneId.systemDefault()))).withColor(COLOR_DIM)));
		}
		g.setComponentTooltipForNextFrame(font, lines, mx, my);
	}

	private int xFor(long ms, long tMin, long tMax) {
		return gx0 + (int) Math.round((double) (ms - tMin) / (tMax - tMin) * (gx1 - gx0));
	}

	private int yFor(double v, double lo, double hi) {
		return gy1 - (int) Math.round((v - lo) / (hi - lo) * (gy1 - gy0));
	}

	private static void dot(GuiGraphicsExtractor g, int x, int y, int color) {
		g.fill(x - 2, y - 2, x + 3, y + 3, 0xFF000000);
		g.fill(x - 1, y - 1, x + 2, y + 2, color);
	}

	private static void triangle(GuiGraphicsExtractor g, int x, int y, int size, boolean down, int color) {
		for (int i = 0; i < size; i++) {
			int row = down ? y - i : y + i;
			g.fill(x - i, row, x + i + 1, row + 1, color);
		}
	}

	private static DateTimeFormatter xFormatter(long spanMillis) {
		long day = 86_400_000L;
		if (spanMillis <= 2 * day) return FMT_TIME;
		if (spanMillis <= 90 * day) return FMT_DAY;
		if (spanMillis <= 3 * 365 * day) return FMT_MONTH;
		return FMT_YEAR;
	}
}
