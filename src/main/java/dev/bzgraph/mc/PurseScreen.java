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
import net.minecraft.client.input.MouseButtonEvent;
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
	private static final int COLOR_TEXT = GraphUi.COLOR_TEXT;
	private static final int COLOR_DIM = GraphUi.COLOR_DIM;
	private static final int COLOR_GRID = GraphUi.COLOR_GRID;
	private static final int PAD = 8;
	private static final int Y_AXIS_W = 42;
	private static final int HEADER_ROW = 22;
	private static final int BUTTONS_Y = 38;
	private static final int TRADE_CLUSTER_PX = 4;
	private static final DateTimeFormatter FMT_TOOLTIP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

	private final Screen root;
	private final Screen back;
	private TimeRange range;
	private String profile;
	private final List<Button> rangeButtons = new ArrayList<>();
	private Button profileButton;
	private Button tradesButton;
	private boolean helpOpen;

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
		int buttonsY = py0 + BUTTONS_Y;
		gx0 = px0 + PAD + Y_AXIS_W;
		gx1 = px1 - PAD - 2;
		gy0 = buttonsY + 30;
		gy1 = py1 - 26;

		rangeButtons.clear();
		int x = px0 + PAD;
		for (TimeRange r : TimeRange.values()) {
			rangeButtons.add(addRenderableWidget(Button.builder(Component.literal(r.label()), btn -> selectRange(r))
					.bounds(x, buttonsY, 32, 20).build()));
			x += 34;
		}
		GraphUi.rangeLabels(rangeButtons, range, true);
		int bx = px1 - PAD;
		tradesButton = addRenderableWidget(Button.builder(Component.empty(), b -> toggleTrades())
				.bounds(bx -= 56, buttonsY, 56, 20).build());
		tradesButton.setTooltip(Tooltip.create(Component.literal("Your bazaar trades (all items): ▲ buys, ▼ sells")));
		profileButton = addRenderableWidget(Button.builder(Component.empty(), b -> cycleProfile())
				.bounds(bx -= 2 + 130, buttonsY, 130, 20).build());
		profileButton.setTooltip(Tooltip.create(Component.literal("SkyBlock profile whose purse is shown (click to switch)")));
		Button backBtn = addRenderableWidget(Button.builder(Component.literal("<"), b -> goBack())
				.bounds(px0 + PAD - 2, py0 + 4, 14, 14).build());
		backBtn.active = back != null;
		Button help = addRenderableWidget(Button.builder(Component.literal("?"), b -> helpOpen = !helpOpen)
				.bounds(px1 - PAD - 14, py0 + 4, 14, 14).build());
		help.setTooltip(Tooltip.create(Component.literal("What the line and markers mean")));
		updateLabels();
	}

	private void updateLabels() {
		List<String> profiles = BzGraphClient.get().purse().store().profiles(TradeTracker.account());
		boolean cycles = profiles.size() > 1 || (profile != null && !profiles.contains(profile) && !profiles.isEmpty());
		String name = profile == null ? "none yet" : font.plainSubstrByWidth(profile, 70);
		profileButton.setMessage(Component.literal("Profile: " + name + (cycles ? " ▾" : "")));
		profileButton.active = cycles;
		tradesButton.setMessage(GraphUi.toggleLabel("Trades", BzGraphClient.get().config().showTrades, COLOR_TEXT));
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
		GraphUi.rangeLabels(rangeButtons, r, true);
		BzConfig cfg = BzGraphClient.get().config();
		cfg.purseRange = r.label();
		cfg.save();
	}

	private void goBack() {
		if (back != null) minecraft.gui.setScreen(back);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (helpOpen) {
			helpOpen = false;
			return true;
		}
		int k = event.key();
		if (k == GLFW.GLFW_KEY_SLASH || k == GLFW.GLFW_KEY_H) {
			helpOpen = true;
			return true;
		}
		if (BzGraphClient.get().openKey().matches(event)) {
			onClose();
			return true;
		}
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
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (helpOpen) {
			helpOpen = false;
			return true;
		}
		return super.mouseClicked(event, doubleClick);
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
		GraphUi.panel(g, cfg, width, height, px0, py0, px1, py1);
		GraphUi.plotBackground(g, cfg, gx0, gy0, gx1, gy1);
		g.text(font, "Purse", px0 + PAD + 18, py0 + PAD, COLOR_TEXT);
		String hint = "1-5 range · ? help & sources";
		g.text(font, hint, px1 - PAD - font.width(hint), py1 - 12, GraphUi.COLOR_FOOTER);

		List<PurseStore.Point> all = BzGraphClient.get().purse().store().points(TradeTracker.account(), profile);
		long now = System.currentTimeMillis();
		if (!all.isEmpty()) {
			g.text(font, "Last purse change " + GraphUi.ago(Instant.ofEpochMilli(all.getLast().time()), Instant.ofEpochMilli(now)),
					px0 + PAD, py1 - 12, GraphUi.COLOR_FAINT);
		}
		Instant start = range.start(Instant.ofEpochMilli(now));
		if (all.isEmpty()) {
			int cx = (gx0 + gx1) / 2, cy = (gy0 + gy1) / 2;
			if (cfg.trackPurse) {
				g.centeredText(font, "No purse data yet", cx, cy - 8, COLOR_DIM);
				g.centeredText(font, "It's recorded from the sidebar while you play SkyBlock.", cx, cy + 4, GraphUi.COLOR_FAINT);
			} else {
				g.centeredText(font, "Purse tracking is off", cx, cy - 8, COLOR_DIM);
				g.centeredText(font, "Set \"trackPurse\" to true in config/bzgraph/config.json", cx, cy + 4, GraphUi.COLOR_FAINT);
			}
			finishFrame(g, mx, my, partialTick);
			return;
		}
		long tMin = Math.max(start.toEpochMilli(), all.getFirst().time());
		long tMax = Math.max(now, tMin + 60_000);
		// visible steps: the value in force at tMin, then every change after it, then "now"
		List<Long> ts = new ArrayList<>();
		List<Double> vals = new ArrayList<>();
		double startVal = PurseStore.valueAt(all, Instant.ofEpochMilli(tMin));
		if (!Double.isNaN(startVal)) {
			ts.add(tMin);
			vals.add(startVal);
		}
		for (PurseStore.Point p : all) {
			if (p.time() <= tMin || p.time() > tMax) continue;
			ts.add(p.time());
			vals.add(p.coins());
		}
		long[] times = new long[ts.size()];
		double[] values = new double[ts.size()];
		for (int i = 0; i < ts.size(); i++) {
			times[i] = ts.get(i);
			values[i] = vals.get(i);
		}
		drawHeader(g, values, tMin, tMax);
		drawGraph(g, mx, my, times, values, tMin, tMax);
		finishFrame(g, mx, my, partialTick);
	}

	private void finishFrame(GuiGraphicsExtractor g, int mx, int my, float partialTick) {
		super.extractRenderState(g, mx, my, partialTick);
		GraphUi.drawSelected(g, rangeButtons, range);
		GraphUi.drawToggleBox(g, font, tradesButton, BzGraphClient.get().config().showTrades, COLOR_TEXT);
		if (helpOpen) GraphUi.drawHelp(g, font, (gx0 + gx1) / 2, (gy0 + gy1) / 2, true, 0, 0);
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

	/** Big: the purse now. Medium: change over the range. Small, grey: range low/high. Bazaar totals top right. */
	private void drawHeader(GuiGraphicsExtractor g, double[] values, long tMin, long tMax) {
		if (values.length == 0) return;
		double first = values[0], cur = values[values.length - 1];
		double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
		for (double v : values) {
			min = Math.min(min, v);
			max = Math.max(max, v);
		}
		int x = px0 + PAD, y = py0 + HEADER_ROW;
		x += GraphUi.scaledText(g, font, Component.literal("Purse ").withColor(COLOR_LINE)
				.append(Component.literal(CoinFormat.compact(cur)).withColor(COLOR_TEXT)), x, y, 1.5f) + 6;
		double change = cur - first;
		String ch = (change > 0 ? "+" : "") + CoinFormat.compact(change)
				+ (first != 0 ? String.format(Locale.ROOT, " (%+.1f%%)", change / first * 100) : "");
		g.text(font, ch, x, y + 3, GraphUi.changeColor(change));
		x += font.width(ch) + 12;
		String lowHigh = range.label() + " low " + CoinFormat.compact(min) + " · high " + CoinFormat.compact(max);
		g.text(font, lowHigh, x, y + 3, GraphUi.COLOR_FAINT);

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
					.append(Component.literal(CoinFormat.compact(in)).withColor(in > 0 ? GraphUi.COLOR_UP : COLOR_DIM))
					.append(Component.literal(" · bought ").withColor(COLOR_DIM))
					.append(Component.literal(CoinFormat.compact(out)).withColor(out > 0 ? GraphUi.COLOR_DOWN : COLOR_DIM))
					.append(Component.literal(" · " + n + " trades").withColor(COLOR_DIM));
			int w = font.width(b);
			g.text(font, b, px1 - PAD - 18 - w, py0 + PAD, COLOR_TEXT);
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
			GraphUi.yLabel(g, font, CoinFormat.compact(t), gx0, gy1, y);
		}
		GraphUi.xAxis(g, font, gx0, gy0, gx1, gy1, gy1 + 3, tMin, tMax);

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
		GraphUi.dot(g, gx1, yFor(values[values.length - 1], lo, hi), COLOR_LINE);

		drawTrades(g, times, values, tMin, tMax, lo, hi);
		if (helpOpen) return;
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
		for (TradeCluster c : out) GraphUi.tradeMarker(g, c.x(), c.y(), c.side() == Trade.Side.SELL, c.trades().size() > 1);
	}

	/** Sells sit above the purse line (▼ pointing down at it), buys below it (▲ pointing up). */
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
		double bestD = 5;
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
		if (c.trades().size() > 1) {
			lines.add(Component.literal((sell ? "▼ " : "▲ ") + c.trades().size() + (sell ? " sells" : " buys"))
					.withColor(GraphUi.COLOR_TRADE)
					.append(Component.literal("  " + (sell ? "+" : "-") + CoinFormat.compact(coins) + " coins").withColor(COLOR_DIM)));
		}
		int shown = Math.min(8, c.trades().size());
		boolean offer = false;
		for (int i = 0; i < shown; i++) {
			Trade t = c.trades().get(i);
			offer |= t.kind() != Trade.Kind.INSTANT;
			// "Sold 64× Enchanted Umber @ 15.2k"
			net.minecraft.network.chat.MutableComponent line = Component.literal(
					(c.trades().size() == 1 ? (sell ? "▼ " : "▲ ") : "") + (sell ? "Sold " : "Bought ") + t.amount() + "× ")
					.withColor(c.trades().size() == 1 ? GraphUi.COLOR_TRADE : 0xFFDDDDDD)
					.append(Component.literal(t.item()).withColor(COLOR_TEXT));
			if (t.hasPrice()) line.append(Component.literal(" @ " + CoinFormat.compact(t.unitPrice())).withColor(0xFFDDDDDD));
			if (!Double.isNaN(t.coins())) line.append(Component.literal("  (" + CoinFormat.compact(t.coins()) + ")").withColor(COLOR_DIM));
			lines.add(line);
		}
		if (c.trades().size() > shown) lines.add(Component.literal("+" + (c.trades().size() - shown) + " more").withColor(COLOR_DIM));
		lines.add(Component.literal(FMT_TOOLTIP.format(c.trades().getFirst().time().atZone(zone))).withColor(COLOR_DIM));
		if (offer) {
			// why the purse may not move right at the marker
			lines.add(Component.literal(sell ? "Offer filled: the coins reach your purse when you claim them"
					: "Order filled: the coins left your purse when you placed the order").withColor(GraphUi.COLOR_FAINT));
		}
		g.setComponentTooltipForNextFrame(font, lines, mx, my);
	}

	private void drawCrosshair(GuiGraphicsExtractor g, int mx, int my, long[] times, double[] values, long tMin, long tMax,
							   double lo, double hi) {
		long t = tMin + Math.round((double) (mx - gx0) / (gx1 - gx0) * (tMax - tMin));
		int i = -1;
		for (int k = 0; k < times.length && times[k] <= t; k++) i = k;
		if (i < 0) return;
		g.verticalLine(mx, gy0, gy1, 0x90FFFFFF);
		GraphUi.dot(g, mx, yFor(values[i], lo, hi), COLOR_LINE);
		List<Component> lines = new ArrayList<>();
		lines.add(Component.literal(FMT_TOOLTIP.format(Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()))).withColor(COLOR_TEXT));
		lines.add(Component.literal("Purse: ").withColor(COLOR_LINE).append(Component.literal(CoinFormat.price(values[i])).withColor(COLOR_TEXT)));
		if (i > 0) {
			double d = values[i] - values[i - 1];
			lines.add(Component.literal("last change ").withColor(COLOR_DIM)
					.append(Component.literal((d > 0 ? "+" : "") + CoinFormat.compact(d)).withColor(GraphUi.changeColor(d)))
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
}
