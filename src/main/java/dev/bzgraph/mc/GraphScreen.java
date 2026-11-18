package dev.bzgraph.mc;

import dev.bzgraph.BzGraphClient;
import dev.bzgraph.core.CachedHistory;
import dev.bzgraph.core.CoinFormat;
import dev.bzgraph.core.CompareMath;
import dev.bzgraph.core.Downsampler;
import dev.bzgraph.core.HistoryHandle;
import dev.bzgraph.core.MayorHistory;
import dev.bzgraph.core.PatchNote;
import dev.bzgraph.core.PriceImpact;
import dev.bzgraph.core.PricePoint;
import dev.bzgraph.core.Resampler;
import dev.bzgraph.core.TimeRange;
import dev.bzgraph.core.trade.Trade;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
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
import java.util.Set;

/** Full-screen bazaar price-history graph. */
public final class GraphScreen extends Screen implements BzScreen {

	private static final int COLOR_BUY = 0xFFFFAA00;
	private static final int COLOR_SELL = 0xFF55FFFF;
	private static final int COLOR_TEXT = GraphUi.COLOR_TEXT;
	private static final int COLOR_DIM = GraphUi.COLOR_DIM;
	private static final int COLOR_GRID = GraphUi.COLOR_GRID;
	private static final int COLOR_ERROR = 0xFFFF5555;
	private static final int COLOR_WARN = 0xFFFFFF55;
	private static final int COLOR_LINK = 0xFF55AAFF;
	private static final int Y_AXIS_W = 42;
	private static final int PAD = 8;
	private static final int COLOR_PATCH = GraphUi.COLOR_PATCH;
	/** Markers closer than this many pixels are merged into one cluster. */
	private static final int CLUSTER_PX = 5;
	private static final int TRADE_CLUSTER_PX = 4;
	/** Header rows: title, then the big prices; the buttons sit below them. */
	private static final int HEADER_ROW = 22;
	private static final int BUTTONS_Y = 38;
	private static final DateTimeFormatter FMT_TRADE = DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.ROOT);
	private static final DateTimeFormatter FMT_TOOLTIP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);
	static final String BUY_HELP = "Buy = insta-buy price: what you pay to buy right now (the lowest sell offer)";
	static final String SELL_HELP = "Sell = insta-sell price: what you get selling right now (the highest buy order)";

	private final GraphNav nav;
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
	private PricePoint builtLive;
	/** Index of the appended live-snapshot point in the arrays, or -1. */
	private int liveIndex = -1;
	private long[] times = new long[0];
	private double[] buys = new double[0];
	private double[] sells = new double[0];
	private double[] vols = new double[0];
	// Lowest/highest price within each sample's bucket (a day on long ranges), for the band behind each line.
	private double[] minBuys = new double[0], maxBuys = new double[0], minSells = new double[0], maxSells = new double[0];

	private final List<Button> rangeButtons = new ArrayList<>();
	private Button buyButton;
	private Button sellButton;
	private Button patchButton;
	private Button tradesButton;
	private Button bandButton;

	/** Your trades merged per screen column and side, as drawn in the last frame. */
	private record TradeCluster(Trade.Side side, int x, int y, List<Trade> trades) {
	}

	private List<TradeCluster> tradeClusters = List.of();

	private static final int MAYOR_BAR_H = 10;

	/** Mayor-bar segments drawn in the last frame (for hover). */
	private record MayorSegment(int x0, int x1, MayorHistory.Term term) {
	}

	private List<MayorSegment> mayorSegments = List.of();
	private int mayorBarY = -1;
	private Button backButton;
	private Button forwardButton;

	/** Patch markers drawn in the last frame (for hover/click). */
	private record Cluster(int x, List<PatchNote> patches, boolean relevant) {
	}

	private List<Cluster> clusters = List.of();

	// ---- comparing items
	private static final int MAX_COMPARE = 3;
	private static final int COLOR_MAIN = 0xFFFFAA00;
	/** Okabe-Ito based (sky blue, reddish purple, bluish green): tell apart from the orange main line with colour-blindness too. */
	private static final int[] COMPARE_COLORS = {0xFF56B4E9, 0xFFCC79A7, 0xFF2BC79A};

	/** Another item drawn on the same graph. */
	private static final class CompareItem {
		final String id;
		final String name;
		final int color;
		HistoryHandle handle;
		private CachedHistory builtFrom;
		private PricePoint builtLive;
		long[] t = new long[0];
		double[] buy = new double[0], sell = new double[0];

		CompareItem(String id, String name, int color) {
			this.id = id;
			this.name = name;
			this.color = color;
		}

		void build() {
			CachedHistory d = handle == null ? null : handle.data();
			PricePoint live = handle == null ? null : handle.live();
			if (d == builtFrom && live == builtLive) return;
			builtFrom = d;
			builtLive = live;
			List<PricePoint> pts = d == null ? List.of() : Resampler.resample(d.points());
			boolean addLive = live != null && !pts.isEmpty() && live.timestamp().isAfter(pts.getLast().timestamp());
			int n = pts.size() + (addLive ? 1 : 0);
			t = new long[n];
			buy = new double[n];
			sell = new double[n];
			for (int i = 0; i < n; i++) {
				PricePoint p = i < pts.size() ? pts.get(i) : live;
				t[i] = p.timestamp().toEpochMilli();
				buy[i] = p.buy();
				sell[i] = p.sell();
			}
		}
	}

	/** One drawn line in compare mode; {@code raw} is the price behind a % value; faded = the sell line when both are shown. */
	private record Line(String label, int color, boolean faded, long[] t, double[] v, double[] raw, long maxGap) {
		int drawColor() {
			return faded ? (0x90 << 24) | (color & 0xFFFFFF) : color;
		}
	}

	private record LegendHit(int x0, int x1, CompareItem item) {
	}

	private final List<CompareItem> compare = new ArrayList<>();
	private List<LegendHit> legendHits = List.of();
	private Button compareButton;
	private Button modeButton;
	private Button purseButton;
	private Button bothButton;
	private Button helpButton;
	private boolean helpOpen;

	/** Header text the mouse can hover for an explanation, as drawn in the last frame. */
	private record HeaderHit(int x0, int y0, int x1, int y1, String text) {
	}

	private List<HeaderHit> headerHits = new ArrayList<>();

	// Layout: the whole graph lives in a centered panel (px0..px1, py0..py1)
	private int px0, py0, px1, py1;
	private int gx0, gy0, gx1, gy1;
	private int attrX, attrY;

	/** Starts a new graph session that returns to {@code parent} when closed. */
	public GraphScreen(Screen parent, String productId, String displayName) {
		this(startNav(parent, productId, displayName), null);
	}

	/** Shows the nav's current item. */
	public GraphScreen(GraphNav nav) {
		this(nav, null);
	}

	private GraphScreen(GraphNav nav, String fixedError) {
		super(Component.literal("BZgraph"));
		this.nav = nav;
		GraphNav.Entry e = nav.current();
		this.productId = e == null ? null : e.productId();
		this.displayName = e == null ? null : e.displayName();
		this.fixedError = fixedError;
		BzConfig cfg = BzGraphClient.get().config();
		this.range = cfg.defaultRange();
		this.showBuy = cfg.showBuy;
		this.showSell = cfg.showSell;
	}

	private static GraphNav startNav(Screen parent, String productId, String displayName) {
		GraphNav nav = new GraphNav(parent);
		nav.push(productId, displayName);
		return nav;
	}

	public static GraphScreen notOnBazaar(Screen parent, String displayName) {
		return new GraphScreen(new GraphNav(parent), "\"" + displayName + "\" is not a bazaar item");
	}

	/** Opens {@code productId} in the same session (adds it to the back/forward history). */
	public static void navigate(GraphNav nav, String productId, String displayName) {
		nav.push(productId, displayName);
		Minecraft.getInstance().gui.setScreen(new GraphScreen(nav));
	}

	private void goBack() {
		if (!nav.canBack()) return;
		nav.back();
		minecraft.gui.setScreen(new GraphScreen(nav));
	}

	private void goForward() {
		if (!nav.canForward()) return;
		nav.forward();
		minecraft.gui.setScreen(new GraphScreen(nav));
	}

	@Override
	protected void init() {
		// ~3/4 of the screen, but never so small the buttons overlap or so big it feels full-screen
		int pw = Math.min(width - 16, clamp(Math.round(width * 0.72f), 470, 600));
		int ph = Math.min(height - 16, clamp(Math.round(height * 0.78f), 240, 330));
		px0 = (width - pw) / 2;
		py0 = (height - ph) / 2;
		px1 = px0 + pw;
		py1 = py0 + ph;

		int buttonsY = py0 + BUTTONS_Y;
		gx0 = px0 + PAD + Y_AXIS_W;
		gx1 = px1 - PAD - 2;
		gy0 = buttonsY + 30;
		gy1 = py1 - 26 - (BzGraphClient.get().config().showMayors ? MAYOR_BAR_H + 2 : 0);
		mayorBarY = BzGraphClient.get().config().showMayors && productId != null ? gy1 + 2 : -1;

		rangeButtons.clear();
		int x = px0 + PAD;
		for (TimeRange r : TimeRange.values()) {
			Button b = Button.builder(Component.literal(r.label()), btn -> selectRange(r))
					.bounds(x, buttonsY, 32, 20).build();
			rangeButtons.add(addRenderableWidget(b));
			x += 34;
		}
		// right to left: Sell, Buy, Band, Trades, Patches
		int bx = px1 - PAD;
		sellButton = addRenderableWidget(Button.builder(Component.empty(), b -> toggle(false))
				.bounds(bx -= 44, buttonsY, 44, 20).build());
		buyButton = addRenderableWidget(Button.builder(Component.empty(), b -> toggle(true))
				.bounds(bx -= 2 + 44, buttonsY, 44, 20).build());
		bandButton = addRenderableWidget(Button.builder(Component.empty(), b -> toggleBands())
				.bounds(bx -= 2 + 48, buttonsY, 48, 20).build());
		bandButton.setTooltip(Tooltip.create(Component.literal("Shade the lowest-highest price within each point "
				+ "(a whole day on 1M/1Y/ALL) behind the lines (B). Hover a line to see only its band.")));
		tradesButton = addRenderableWidget(Button.builder(Component.empty(), b -> toggleTrades())
				.bounds(bx -= 2 + 56, buttonsY, 56, 20).build());
		// compare mode only, in place of Band + Trades (which belong to a single item)
		bothButton = addRenderableWidget(Button.builder(Component.empty(), b -> toggleCompareBoth())
				.bounds(bx, buttonsY, 56 + 2 + 48, 20).build());
		bothButton.setTooltip(Tooltip.create(Component.literal("Off: one line per item, buy or sell (pick with the Buy/Sell buttons)\n"
				+ "On: both lines per item, the sell line faded")));
		patchButton = addRenderableWidget(Button.builder(Component.empty(), b -> cycleMarkers())
				.bounds(bx -= 2 + 84, buttonsY, 84, 20).build());
		patchButton.setTooltip(Tooltip.create(Component.literal("Click to cycle which patch notes are marked:\n"
				+ "Item = only patches that mention this item, All, or Off. P lists them.")));
		tradesButton.setTooltip(Tooltip.create(Component.literal("Your own bazaar trades on this item: "
				+ "\u25b2 buys, \u25bc sells. Recorded from chat and imported from your log files.")));
		buyButton.setTooltip(Tooltip.create(Component.literal(BUY_HELP)));
		sellButton.setTooltip(Tooltip.create(Component.literal(SELL_HELP)));
		updateToggleLabels();
		boolean hasProduct = productId != null;
		buyButton.active = sellButton.active = patchButton.active = tradesButton.active = bandButton.active = hasProduct;
		if (hasProduct) BzGraphClient.get().mayors().ensure();
		if (hasProduct) {
			Set<String> products = BzGraphClient.get().products();
			if (products != null) BzGraphClient.get().trades().ensureNames(products);
		}
		if (hasProduct) BzGraphClient.get().ensurePatches();
		GraphUi.rangeLabels(rangeButtons, range, hasProduct);

		backButton = addRenderableWidget(Button.builder(Component.literal("<"), b -> goBack())
				.bounds(px0 + PAD - 2, py0 + 4, 14, 14).build());
		forwardButton = addRenderableWidget(Button.builder(Component.literal(">"), b -> goForward())
				.bounds(px0 + PAD + 14, py0 + 4, 14, 14).build());
		backButton.active = nav.canBack();
		forwardButton.active = nav.canForward();
		backButton.setTooltip(Tooltip.create(Component.literal("Previous item (Left arrow)")));
		forwardButton.setTooltip(Tooltip.create(Component.literal("Next item (Right arrow)")));

		attrX = px0 + PAD;
		attrY = py1 - 12;

		// top right: [mode] [+ Compare] [Purse] [?]
		int tx = px1 - PAD;
		helpButton = addRenderableWidget(Button.builder(Component.literal("?"), b -> helpOpen = !helpOpen)
				.bounds(tx -= 14, py0 + 4, 14, 14).build());
		helpButton.setTooltip(Tooltip.create(Component.literal("What the lines, markers and band mean")));
		purseButton = addRenderableWidget(Button.builder(Component.literal("Purse"), b -> openPurse())
				.bounds(tx -= 2 + 40, py0 + 4, 40, 14).build());
		purseButton.setTooltip(Tooltip.create(Component.literal("Your purse over time (from the SkyBlock sidebar)")));
		compareButton = addRenderableWidget(Button.builder(Component.literal("+ Compare"), b -> openCompareSearch())
				.bounds(tx -= 2 + 60, py0 + 4, 60, 14).build());
		compareButton.setTooltip(Tooltip.create(Component.literal("Draw other bazaar items on this graph (C)")));
		modeButton = addRenderableWidget(Button.builder(Component.empty(), b -> cycleCompareMode())
				.bounds(tx -= 2 + 86, py0 + 4, 86, 14).build());
		modeButton.setTooltip(Tooltip.create(Component.literal("What the Y axis shows (click to switch):\n"
				+ "% change: every line starts at 0% at the left edge, so cheap and expensive items compare\n"
				+ "Price: actual prices\nRatio: first item \u00f7 second item (two items only)")));
		updateCompareState();

		BzConfig cfg = BzGraphClient.get().config();
		if (hasProduct && !cfg.seenHelp) {
			helpOpen = true;
			cfg.seenHelp = true;
			cfg.save();
		}

		if (hasProduct && (handle == null || handle.range() != range)) {
			handle = BzGraphClient.get().repository().load(productId, range);
		}
	}

	/** Adds an item to compare with (ignored if it's already shown or the limit is reached). */
	public void addCompare(String id) {
		if (id == null || id.equals(productId) || compare.size() >= MAX_COMPARE) return;
		for (CompareItem c : compare) if (c.id.equals(id)) return;
		int color = COMPARE_COLORS[0];
		for (int col : COMPARE_COLORS) {
			boolean used = false;
			for (CompareItem c : compare) used |= c.color == col;
			if (!used) {
				color = col;
				break;
			}
		}
		CompareItem c = new CompareItem(id, BzGraphClient.get().trades().displayName(id), color);
		c.handle = BzGraphClient.get().repository().load(id, range);
		compare.add(c);
		if (modeButton != null) {
			updateCompareState();
			updateToggleLabels();
		}
	}

	private void removeCompare(CompareItem c) {
		compare.remove(c);
		updateCompareState();
		updateToggleLabels();
	}

	/** Removing the main item: the first compared item takes its place, the others stay. */
	private void removeMain() {
		if (compare.isEmpty()) return;
		CompareItem first = compare.getFirst();
		nav.push(first.id, first.name);
		GraphScreen next = new GraphScreen(nav);
		for (CompareItem c : compare.subList(1, compare.size())) next.addCompare(c.id);
		minecraft.gui.setScreen(next);
	}

	/** Compare mode draws one line per item (buy or sell) unless "Both" is on. */
	private boolean drawBuy() {
		if (compare.isEmpty()) return showBuy;
		BzConfig cfg = BzGraphClient.get().config();
		return cfg.compareBoth || !"SELL".equalsIgnoreCase(cfg.compareSide);
	}

	private boolean drawSell() {
		if (compare.isEmpty()) return showSell;
		BzConfig cfg = BzGraphClient.get().config();
		return cfg.compareBoth || "SELL".equalsIgnoreCase(cfg.compareSide);
	}

	private void toggleCompareBoth() {
		BzConfig cfg = BzGraphClient.get().config();
		cfg.compareBoth = !cfg.compareBoth;
		cfg.save();
		updateToggleLabels();
	}

	private void openCompareSearch() {
		if (productId == null || compare.size() >= MAX_COMPARE) return;
		Set<String> exclude = new java.util.HashSet<>();
		exclude.add(productId);
		for (CompareItem c : compare) exclude.add(c.id);
		minecraft.gui.setScreen(ItemSearchScreen.compare(this, nav.root(), exclude, this::addCompare));
	}

	private void openPurse() {
		minecraft.gui.setScreen(new PurseScreen(nav.root(), this));
	}

	private BzConfig.CompareMode compareMode() {
		BzConfig.CompareMode m = BzGraphClient.get().config().compareMode();
		return m == BzConfig.CompareMode.RATIO && compare.size() != 1 ? BzConfig.CompareMode.PERCENT : m;
	}

	private void cycleCompareMode() {
		BzConfig.CompareMode m = switch (compareMode()) {
			case PERCENT -> BzConfig.CompareMode.PRICE;
			case PRICE -> compare.size() == 1 ? BzConfig.CompareMode.RATIO : BzConfig.CompareMode.PERCENT;
			case RATIO -> BzConfig.CompareMode.PERCENT;
		};
		BzConfig cfg = BzGraphClient.get().config();
		cfg.compareMode = m.name();
		cfg.save();
		updateCompareState();
	}

	private void updateCompareState() {
		boolean comparing = !compare.isEmpty();
		modeButton.visible = comparing;
		modeButton.setMessage(Component.literal(switch (compareMode()) {
			case PERCENT -> "Show: % change ▾";
			case PRICE -> "Show: Price ▾";
			case RATIO -> "Show: Ratio ▾";
		}));
		compareButton.active = productId != null && compare.size() < MAX_COMPARE;
		// bands and your trades belong to a single item's price; compare mode shows "Both lines" there instead
		bandButton.visible = tradesButton.visible = !comparing;
		bothButton.visible = comparing;
		buyButton.setTooltip(Tooltip.create(Component.literal(comparing ? "Compare everything by its buy price\n" + BUY_HELP : BUY_HELP)));
		sellButton.setTooltip(Tooltip.create(Component.literal(comparing ? "Compare everything by its sell price\n" + SELL_HELP : SELL_HELP)));
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	private void selectRange(TimeRange r) {
		if (r == range) return;
		range = r;
		GraphUi.rangeLabels(rangeButtons, r, productId != null);
		handle = BzGraphClient.get().repository().load(productId, range);
		for (CompareItem c : compare) c.handle = BzGraphClient.get().repository().load(c.id, range);
		BzConfig cfg = BzGraphClient.get().config();
		cfg.defaultRange = r.label();
		cfg.save();
	}

	private void toggle(boolean buy) {
		if (!compare.isEmpty()) { // compare mode: pick which price every item is drawn with
			BzConfig cfg = BzGraphClient.get().config();
			cfg.compareSide = buy ? "BUY" : "SELL";
			cfg.compareBoth = false;
			cfg.save();
			updateToggleLabels();
			return;
		}
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

	private void toggleBands() {
		BzConfig cfg = BzGraphClient.get().config();
		cfg.showBands = !cfg.showBands;
		cfg.save();
		updateToggleLabels();
	}

	private void toggleTrades() {
		BzConfig cfg = BzGraphClient.get().config();
		cfg.showTrades = !cfg.showTrades;
		cfg.save();
		updateToggleLabels();
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
			case RELEVANT -> "Patches: Item ▾";
			case ALL -> "Patches: All ▾";
			case OFF -> "Patches: Off ▾";
		};
		patchButton.setMessage(Component.literal(label).withColor(m == BzConfig.Markers.OFF ? GraphUi.COLOR_OFF : COLOR_PATCH));
		BzConfig cfg = BzGraphClient.get().config();
		tradesButton.setMessage(GraphUi.toggleLabel("Trades", cfg.showTrades, COLOR_TEXT));
		bandButton.setMessage(GraphUi.toggleLabel("Band", cfg.showBands, COLOR_TEXT));
		bothButton.setMessage(GraphUi.toggleLabel("Both lines", cfg.compareBoth, COLOR_TEXT));
		buyButton.setMessage(GraphUi.toggleLabel("Buy", drawBuy(), COLOR_BUY));
		sellButton.setMessage(GraphUi.toggleLabel("Sell", drawSell(), COLOR_SELL));
	}

	/** The on/off boxes and the selected range, drawn over the buttons. */
	private void drawButtonStates(GuiGraphicsExtractor g) {
		BzConfig cfg = BzGraphClient.get().config();
		GraphUi.drawSelected(g, rangeButtons, range);
		GraphUi.drawToggleBox(g, font, tradesButton, cfg.showTrades, COLOR_TEXT);
		GraphUi.drawToggleBox(g, font, bandButton, cfg.showBands, COLOR_TEXT);
		GraphUi.drawToggleBox(g, font, bothButton, cfg.compareBoth, COLOR_TEXT);
		GraphUi.drawToggleBox(g, font, buyButton, drawBuy(), COLOR_BUY);
		GraphUi.drawToggleBox(g, font, sellButton, drawSell(), COLOR_SELL);
	}

	// ---------------------------------------------------------------- input

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (helpOpen) { // any key just closes the overview
			helpOpen = false;
			return true;
		}
		if (event.key() == GLFW.GLFW_KEY_SLASH || event.key() == GLFW.GLFW_KEY_H) {
			helpOpen = true;
			return true;
		}
		if (BzGraphClient.get().openKey().matches(event)) {
			onClose();
			return true;
		}
		int k = event.key();
		if (k == GLFW.GLFW_KEY_LEFT) {
			goBack();
			return true;
		}
		if (k == GLFW.GLFW_KEY_RIGHT) {
			goForward();
			return true;
		}
		if (productId != null && k >= GLFW.GLFW_KEY_1 && k <= GLFW.GLFW_KEY_5) {
			selectRange(TimeRange.values()[k - GLFW.GLFW_KEY_1]);
			return true;
		}
		if (productId != null && k == GLFW.GLFW_KEY_M) {
			BzConfig cfg = BzGraphClient.get().config();
			cfg.showMayors = !cfg.showMayors;
			cfg.save();
			rebuildWidgets(); // the plot height changes
			return true;
		}
		if (productId != null && k == GLFW.GLFW_KEY_B) {
			toggleBands();
			return true;
		}
		if (productId != null && k == GLFW.GLFW_KEY_P) {
			openPatchList(null);
			return true;
		}
		if (productId != null && k == GLFW.GLFW_KEY_R && handle != null && !handle.isBusy()) {
			BzGraphClient.get().repository().refresh(handle);
			for (CompareItem c : compare) if (c.handle != null && !c.handle.isBusy()) BzGraphClient.get().repository().refresh(c.handle);
			return true;
		}
		if (productId != null && k == GLFW.GLFW_KEY_C) {
			openCompareSearch();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (helpOpen) {
			if (event.button() == 0 && GraphUi.overHelpLink(event.x(), event.y())) {
				ConfirmLinkScreen.confirmLinkNow(this, BzGraphClient.COFL_URL);
			} else {
				helpOpen = false;
			}
			return true;
		}
		if (event.button() == GLFW.GLFW_MOUSE_BUTTON_4) {
			goBack();
			return true;
		}
		if (event.button() == 0 && overCredit(event.x(), event.y())) {
			ConfirmLinkScreen.confirmLinkNow(this, BzGraphClient.COFL_URL);
			return true;
		}
		if (event.button() == GLFW.GLFW_MOUSE_BUTTON_5) {
			goForward();
			return true;
		}
		if (event.button() == 0 && !compare.isEmpty() && event.y() >= py0 + HEADER_ROW - 1 && event.y() <= py0 + HEADER_ROW + 10) {
			for (LegendHit h : legendHits) {
				if (event.x() >= h.x0() && event.x() <= h.x1()) {
					if (h.item() == null) removeMain();
					else removeCompare(h.item());
					return true;
				}
			}
		}
		Cluster c = event.button() == 0 ? hoveredCluster(event.x(), event.y()) : null;
		if (c != null) {
			if (c.patches().size() == 1) {
				minecraft.gui.setScreen(new PatchDetailScreen(this, nav, c.patches().getFirst(), productId, historyPoints(), range));
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
		minecraft.gui.setScreen(new PatchListScreen(this, nav, preset, productId, historyPoints(), range));
	}

	/** Patch flag or line under the mouse (for clicks). */
	private Cluster hoveredCluster(double mx, double my) {
		Cluster c = hoveredFlag(mx, my);
		return c != null ? c : hoveredPatchLine(mx, my);
	}

	/** The small flag above the plot: hovering it shows the full patch tooltip. */
	private Cluster hoveredFlag(double mx, double my) {
		for (Cluster c : clusters) if (mx >= c.x() - 1 && mx <= c.x() + 5 && my >= gy0 - 10 && my <= gy0 + 1) return c;
		return null;
	}

	/** The patch line inside the plot: the price tooltip stays, with the patch title added. */
	private Cluster hoveredPatchLine(double mx, double my) {
		for (Cluster c : clusters) if (Math.abs(mx - c.x()) <= 1 && my >= gy0 && my <= gy1) return c;
		return null;
	}

	/** "Mayor: Cole (mining)" for the time under the crosshair, if known. */
	private void addMayorLine(List<Component> lines, long t) {
		MayorHistory.Term term = BzGraphClient.get().mayors().current().at(Instant.ofEpochMilli(t));
		if (term == null) return;
		lines.add(Component.literal("Mayor: ").withColor(COLOR_DIM).append(Component.literal(term.mayor()).withColor(mayorColor(term) | 0xFF404040))
				.append(Component.literal(term.category().isEmpty() ? "" : " (" + term.category() + ")").withColor(COLOR_DIM)));
	}

	/** One short line naming the patch(es) under the crosshair. */
	private void addPatchHint(List<Component> lines, Cluster c) {
		if (c == null) return;
		String title = c.patches().size() == 1 ? font.plainSubstrByWidth(c.patches().getFirst().title(), 200)
				: c.patches().size() + " patch notes";
		lines.add(Component.literal("Patch: " + title + " (click)").withColor(c.relevant() ? COLOR_PATCH : 0xFFBBBBBB));
	}


	/** Esc closes the whole BZgraph session, back to where it was opened from. */
	@Override
	public void onClose() {
		minecraft.gui.setScreen(nav.root());
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// ---------------------------------------------------------------- rendering

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		BzConfig cfg = BzGraphClient.get().config();
		GraphUi.panel(g, cfg, width, height, px0, py0, px1, py1);
		GraphUi.plotBackground(g, cfg, gx0, gy0, gx1, gy1);
		headerHits = new ArrayList<>();

		HistoryHandle h = handle;
		CachedHistory data = h == null ? null : h.data();
		if (fixedError == null) rebuildArrays(data, h == null ? null : h.live());
		drawHeader(g);
		drawFooter(g, data, mouseX, mouseY);

		if (fixedError != null) {
			drawCentered(g, fixedError, COLOR_ERROR);
			finishFrame(g, mouseX, mouseY, partialTick);
			return;
		}

		HistoryHandle.Status status = h == null ? HistoryHandle.Status.LOADING : h.status();
		if (times.length == 0) {
			int cx = (gx0 + gx1) / 2, cy = (gy0 + gy1) / 2;
			switch (status) {
				case LOADING, REFRESHING -> {
					drawSpinner(g, cx, cy - 6);
					g.centeredText(font, "Loading " + range.label() + " price history…", cx, cy + 12, COLOR_DIM);
				}
				case RATE_LIMITED -> {
					drawSpinner(g, cx, cy - 6);
					g.centeredText(font, h.message(), cx, cy + 12, COLOR_WARN);
				}
				case READY -> {
					g.centeredText(font, "No price data for this range", cx, cy - 8, COLOR_DIM);
					g.centeredText(font, "Try a longer range (1-5)", cx, cy + 4, GraphUi.COLOR_FAINT);
				}
				case NOT_FOUND -> {
					g.centeredText(font, "No price history for this item", cx, cy - 8, COLOR_DIM);
					g.centeredText(font, h.message(), cx, cy + 4, GraphUi.COLOR_FAINT);
				}
				default -> {
					g.centeredText(font, "Couldn't load prices right now", cx, cy - 8, COLOR_ERROR);
					g.centeredText(font, font.plainSubstrByWidth(h.message(), gx1 - gx0 - 20), cx, cy + 4, COLOR_DIM);
					g.centeredText(font, "Press R to try again", cx, cy + 16, GraphUi.COLOR_FAINT);
				}
			}
		} else {
			if (compare.isEmpty()) drawGraph(g, mouseX, mouseY);
			else drawCompareGraph(g, mouseX, mouseY);
			drawStatusBadge(g, h);
		}
		finishFrame(g, mouseX, mouseY, partialTick);
	}

	/** Widgets, the on/off boxes on top of them, header tooltips and the overview. */
	private void finishFrame(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		drawButtonStates(g);
		if (helpOpen) {
			GraphUi.drawHelp(g, font, (gx0 + gx1) / 2, (gy0 + gy1) / 2, false, COLOR_BUY, COLOR_SELL);
			return;
		}
		for (HeaderHit hit : headerHits) {
			if (mouseX >= hit.x0() && mouseX < hit.x1() && mouseY >= hit.y0() && mouseY < hit.y1()) {
				List<FormattedCharSequence> lines = new ArrayList<>();
				for (String s : hit.text().split("\n")) lines.addAll(font.split(Component.literal(s), 240));
				g.setTooltipForNextFrame(font, lines, mouseX, mouseY);
				break;
			}
		}
	}

	private void drawHeader(GuiGraphicsExtractor g) {
		String name = displayName != null && !displayName.isBlank() ? displayName : productId;
		String title = name == null ? "BZgraph" : name;
		int maxW = (modeButton != null && modeButton.visible ? modeButton.getX() : compareButton != null ? compareButton.getX() : px1) - 6 - titleX();
		String shown = font.plainSubstrByWidth(title, Math.max(20, maxW));
		g.text(font, shown, titleX(), py0 + PAD, COLOR_TEXT);
		if (productId != null) {
			// the internal ID is only for the curious: hover the title
			headerHits.add(new HeaderHit(titleX(), py0 + PAD - 1, titleX() + font.width(shown), py0 + PAD + 9,
					(shown.equals(title) ? "" : title + "\n") + "Item ID: " + productId));
		}
		if (times.length == 0 || !compare.isEmpty()) return;

		// big: current prices (+ their change over the range), medium: spread, small: range low/high
		int y = py0 + HEADER_ROW;
		int x = px0 + PAD;
		double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
		if (showBuy) x = drawPrice(g, "Buy", buys, COLOR_BUY, x, y, BUY_HELP) + 10;
		if (showSell) x = drawPrice(g, "Sell", sells, COLOR_SELL, x, y, SELL_HELP) + 10;
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
		double buy = lastValue(buys), sell = lastValue(sells);
		int ty = y + 3;
		if (!Double.isNaN(buy) && !Double.isNaN(sell) && sell > 0) {
			Component spread = Component.literal("Spread ").withColor(COLOR_DIM)
					.append(Component.literal(CoinFormat.compact(buy - sell)).withColor(0xFFDDDDDD))
					.append(Component.literal(String.format(Locale.ROOT, " · %.1f%%", (buy - sell) / sell * 100)).withColor(COLOR_DIM));
			if (x + font.width(spread) <= px1 - PAD) {
				g.text(font, spread, x, ty, COLOR_TEXT);
				headerHits.add(new HeaderHit(x, ty - 1, x + font.width(spread), ty + 9,
						"Spread: buy price minus sell price now (in % of the sell price).\nRoughly what flipping one item earns before tax."));
				x += font.width(spread) + 10;
			}
		}
		if (lo != Double.POSITIVE_INFINITY) {
			String range = this.range.label() + " low " + CoinFormat.compact(lo) + " · high " + CoinFormat.compact(hi);
			if (x + font.width(range) <= px1 - PAD) g.text(font, range, x, ty, GraphUi.COLOR_FAINT);
		}
	}

	/** "Buy 12.5M" in large text, then its change over the range. Returns the right edge. */
	private int drawPrice(GuiGraphicsExtractor g, String label, double[] vals, int color, int x, int y, String help) {
		double[] fl = firstLast(vals);
		if (Double.isNaN(fl[1])) return x;
		Component big = Component.literal(label + " ").withColor(color)
				.append(Component.literal(CoinFormat.compact(fl[1])).withColor(COLOR_TEXT));
		int w = GraphUi.scaledText(g, font, big, x, y, 1.5f);
		headerHits.add(new HeaderHit(x, y - 1, x + w, y + 13, help));
		x += w + 4;
		if (!Double.isNaN(fl[0]) && fl[0] != 0) {
			double pct = (fl[1] / fl[0] - 1) * 100;
			String s = String.format(Locale.ROOT, "%+.1f%%", pct);
			g.text(font, s, x, y + 3, GraphUi.changeColor(Math.round(pct * 10) / 10.0));
			headerHits.add(new HeaderHit(x, y + 2, x + font.width(s), y + 12, "Change over the " + range.label() + " range"));
			x += font.width(s);
		}
		return x;
	}

	private static double lastValue(double[] v) {
		for (int i = v.length - 1; i >= 0; i--) if (!Double.isNaN(v[i])) return v[i];
		return Double.NaN;
	}

	/** The title starts after the back/forward arrows. */
	private int titleX() {
		return px0 + PAD + 34;
	}

	/** "Updated 2 min ago" on the left, shortcuts on the right. Data sources are listed in the ? box. */
	private void drawFooter(GuiGraphicsExtractor g, CachedHistory data, int mx, int my) {
		// SkyCofl's terms ask for a visible credit on every graph: one short clickable word, the rest is in "?"
		boolean hover = overCredit(mx, my);
		g.text(font, Component.literal(CREDIT).withStyle(s -> s.withUnderlined(hover)), attrX, attrY, hover ? COLOR_TEXT : COLOR_LINK);
		String left = data == null || data.fetchedAt() == null ? "" : " · updated " + GraphUi.ago(data.fetchedAt(), Instant.now());
		g.text(font, left, attrX + font.width(CREDIT), attrY, GraphUi.COLOR_FAINT);
		int leftEnd = attrX + font.width(CREDIT) + font.width(left);
		String hint = productId == null ? "" : "1-5 range · C compare · P patches · M mayors · B band · ? help & sources";
		if (px1 - PAD - font.width(hint) <= leftEnd + 8) hint = productId == null ? "" : "? help & sources";
		int hx = px1 - PAD - font.width(hint);
		if (hx > leftEnd + 8) g.text(font, hint, hx, attrY, GraphUi.COLOR_FOOTER);
	}

	private static final String CREDIT = "SkyCofl";

	private boolean overCredit(double mx, double my) {
		return mx >= attrX && mx <= attrX + font.width(CREDIT) && my >= attrY - 1 && my <= attrY + font.lineHeight;
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
		int right = (modeButton != null && modeButton.visible ? modeButton.getX() : compareButton.getX()) - 6;
		g.text(font, msg, right - w, py0 + PAD - 2, color);
		if (h.isBusy()) drawSpinnerSmall(g, right - w - 8, py0 + PAD + 1);
	}

	private void drawSpinnerSmall(GuiGraphicsExtractor g, int cx, int cy) {
		int phase = (int) ((System.currentTimeMillis() / 120) % 4);
		int[][] pos = {{0, -3}, {3, 0}, {0, 3}, {-3, 0}};
		for (int i = 0; i < 4; i++) {
			int col = i == phase ? 0xFFFFFFFF : 0x60FFFFFF;
			g.fill(cx + pos[i][0] - 1, cy + pos[i][1] - 1, cx + pos[i][0] + 1, cy + pos[i][1] + 1, col);
		}
	}

	private void rebuildArrays(CachedHistory data, PricePoint live) {
		if (data == builtFrom && live == builtLive) return;
		builtFrom = data;
		builtLive = live;
		liveIndex = -1;
		if (data == null || data.points().isEmpty()) {
			times = new long[0];
			buys = sells = vols = new double[0];
			minBuys = maxBuys = minSells = maxSells = new double[0];
			return;
		}
		// equal buckets across the range, so the fine recent tail doesn't turn into a scribble
		List<PricePoint> pts = Resampler.resample(data.points());
		// The live snapshot becomes the last point when it's newer than the history (SkyCofl's history
		// trails live prices by ~10 minutes).
		boolean addLive = live != null && live.timestamp().isAfter(pts.getLast().timestamp());
		int n = pts.size() + (addLive ? 1 : 0);
		times = new long[n];
		buys = new double[n];
		sells = new double[n];
		vols = new double[n];
		minBuys = new double[n];
		maxBuys = new double[n];
		minSells = new double[n];
		maxSells = new double[n];
		for (int i = 0; i < n; i++) {
			PricePoint p = i < pts.size() ? pts.get(i) : live;
			times[i] = p.timestamp().toEpochMilli();
			buys[i] = p.buy();
			sells[i] = p.sell();
			vols[i] = p.volume();
			minBuys[i] = p.minBuy();
			maxBuys[i] = p.maxBuy();
			minSells[i] = p.minSell();
			maxSells[i] = p.maxSell();
		}
		if (addLive) liveIndex = n - 1;
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
			GraphUi.yLabel(g, font, CoinFormat.compact(t), gx0, gy1, y);
		}
		drawXAxis(g, tMin, tMax);

		int w = gx1 - gx0 + 1;
		Downsampler.Columns sellCols = Downsampler.columns(times, sells, tMin, tMax, w);
		Downsampler.Columns buyCols = Downsampler.columns(times, buys, tMin, tMax, w);
		// Bucket low-high bands first, so the lines and markers sit on top of them. With both lines shown,
		// hovering near one shows only its band (two overlapping bands blend into a muddy colour).
		if (BzGraphClient.get().config().showBands) {
			boolean inPlot = mx >= gx0 && mx <= gx1 && my >= gy0 && my <= gy1;
			int nearest = showBuy && showSell && inPlot ? nearestSeries(mx, my, buyCols, sellCols, lo, hi) : 0;
			int alpha = showBuy && showSell && nearest == 0 ? 0x1A : 0x2C;
			if (showSell && nearest >= 0) drawBand(g, minSells, maxSells, sellCols, sells, tMin, tMax, w, lo, hi, COLOR_SELL, alpha);
			if (showBuy && nearest <= 0) drawBand(g, minBuys, maxBuys, buyCols, buys, tMin, tMax, w, lo, hi, COLOR_BUY, alpha);
		}
		if (showSell) drawSeries(g, sellCols, lo, hi, COLOR_SELL);
		if (showBuy) drawSeries(g, buyCols, lo, hi, COLOR_BUY);

		drawLiveMarker(g, tMin, tMax, lo, hi);
		drawTradeMarkers(g, tMin, tMax, lo, hi);
		drawPatchMarkers(g, tMin, tMax);
		drawMayorBar(g, tMin, tMax);
		if (helpOpen) return;
		Cluster hc = hoveredFlag(mx, my);
		TradeCluster tc = hc == null ? hoveredTrade(mx, my) : null;
		MayorSegment ms = hc == null && tc == null ? hoveredMayor(mx, my) : null;
		if (hc != null) {
			drawPatchTooltip(g, hc, mx, my);
		} else if (tc != null) {
			drawTradeTooltip(g, tc, mx, my);
		} else if (ms != null) {
			drawMayorTooltip(g, ms, mx, my);
		} else if (mx >= gx0 && mx <= gx1 && my >= gy0 && my <= gy1) {
			drawCrosshair(g, mx, my, tMin, tMax, lo, hi, hoveredPatchLine(mx, my));
		}
	}

	private void drawXAxis(GuiGraphicsExtractor g, long tMin, long tMax) {
		GraphUi.xAxis(g, font, gx0, gy0, gx1, gy1, gy1 + 3 + (mayorBarY >= 0 ? MAYOR_BAR_H + 2 : 0), tMin, tMax);
	}

	/** -1 = the mouse is nearer the buy line, 1 = nearer the sell line (at the mouse's column). */
	private int nearestSeries(int mx, int my, Downsampler.Columns buy, Downsampler.Columns sell, double lo, double hi) {
		double b = columnValue(buy, mx - gx0, buys), s = columnValue(sell, mx - gx0, sells);
		if (Double.isNaN(b)) return 1;
		if (Double.isNaN(s)) return -1;
		return Math.abs(yFor(b, lo, hi) - my) <= Math.abs(yFor(s, lo, hi) - my) ? -1 : 1;
	}

	/** The line's value at a column: the column's own samples, or the sample in force there. */
	private double columnValue(Downsampler.Columns c, int col, double[] series) {
		if (col < 0 || col >= c.width()) return Double.NaN;
		if (c.count()[col] > 0) return c.last()[col];
		long t = times[0] + (long) ((double) col / Math.max(1, c.width() - 1) * (times[times.length - 1] - times[0]));
		int i = sampleAt(t);
		return i < 0 ? Double.NaN : series[i];
	}

	// ---------------------------------------------------------------- comparing items

	private String itemLabel() {
		return displayName != null && !displayName.isBlank() ? displayName : BzGraphClient.get().trades().displayName(productId);
	}

	private List<Line> compareLines(long tMin) {
		BzConfig.CompareMode mode = compareMode();
		boolean both = drawBuy() && drawSell();
		List<Line> out = new ArrayList<>();
		if (mode == BzConfig.CompareMode.RATIO) {
			CompareItem o = compare.getFirst();
			if (o.t.length == 0) return out;
			long gap = CompareMath.maxGap(o.t);
			String label = itemLabel() + " ÷ " + o.name;
			if (drawBuy()) out.add(new Line(label + " (buy)", COLOR_BUY, false, times, CompareMath.ratio(times, buys, o.t, o.buy, gap), null, CompareMath.maxGap(times)));
			if (drawSell()) out.add(new Line(label + " (sell)", both ? COLOR_SELL : COLOR_BUY, false, times, CompareMath.ratio(times, sells, o.t, o.sell, gap), null, CompareMath.maxGap(times)));
			return out;
		}
		addLines(out, mode, both, itemLabel(), COLOR_MAIN, times, buys, sells, tMin);
		for (CompareItem c : compare) if (c.t.length > 0) addLines(out, mode, both, c.name, c.color, c.t, c.buy, c.sell, tMin);
		return out;
	}

	private void addLines(List<Line> out, BzConfig.CompareMode mode, boolean both, String name, int color, long[] t, double[] b, double[] s, long tMin) {
		long gap = CompareMath.maxGap(t);
		boolean pct = mode == BzConfig.CompareMode.PERCENT;
		if (drawBuy()) out.add(new Line(both ? name + " (buy)" : name, color, false, t,
				pct ? CompareMath.percent(b, CompareMath.baseline(t, b, tMin)) : b, b, gap));
		if (drawSell()) out.add(new Line(both ? name + " (sell)" : name, color, both, t,
				pct ? CompareMath.percent(s, CompareMath.baseline(t, s, tMin)) : s, s, gap));
	}

	private static String formatY(double v, BzConfig.CompareMode mode, double step) {
		return switch (mode) {
			case PERCENT -> String.format(Locale.ROOT, step < 1 ? "%+.1f%%" : "%+.0f%%", v);
			case RATIO -> Math.abs(v) >= 1000 ? CoinFormat.compact(v) : String.format(Locale.ROOT, "%.3g", v);
			case PRICE -> CoinFormat.compact(v);
		};
	}

	private void drawCompareGraph(GuiGraphicsExtractor g, int mx, int my) {
		for (CompareItem c : compare) c.build();
		BzConfig.CompareMode mode = compareMode();
		// common window: from where every item has data to the newest point
		long tMin = times[0], tMax = times[times.length - 1];
		for (CompareItem c : compare) {
			if (c.t.length == 0) continue;
			tMin = Math.max(tMin, c.t[0]);
			tMax = Math.max(tMax, c.t[c.t.length - 1]);
		}
		if (tMin >= tMax) tMin = tMax - 1;
		List<Line> lines = compareLines(tMin);
		drawLegend(g, lines, mode, mx, my);

		boolean pct = mode == BzConfig.CompareMode.PERCENT;
		double lo = pct ? 0 : Double.POSITIVE_INFINITY;
		double hi = pct ? 0 : Double.NEGATIVE_INFINITY;
		boolean any = false;
		for (Line l : lines) {
			for (int i = 0; i < l.t().length; i++) {
				if (l.t()[i] < tMin || l.t()[i] > tMax || Double.isNaN(l.v()[i])) continue;
				lo = Math.min(lo, l.v()[i]);
				hi = Math.max(hi, l.v()[i]);
				any = true;
			}
		}
		if (!any) {
			boolean loading = false;
			for (CompareItem c : compare) loading |= c.t.length == 0 && c.handle != null && c.handle.isBusy();
			drawCentered(g, loading ? "Loading compared items…" : "These items have no prices in the same time span", COLOR_DIM);
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
		double step = ticks.length > 1 ? ticks[1] - ticks[0] : 1;
		for (double t : ticks) {
			int y = yFor(t, lo, hi);
			boolean zero = pct && Math.abs(t) < step / 2;
			g.horizontalLine(gx0, gx1, y, zero ? 0x70FFFFFF : COLOR_GRID);
			GraphUi.yLabel(g, font, formatY(t, mode, step), gx0, gy1, y);
		}
		drawXAxis(g, tMin, tMax);
		int w = gx1 - gx0 + 1;
		for (Line l : lines.reversed()) drawSeries(g, Downsampler.columns(l.t(), l.v(), tMin, tMax, w), lo, hi, l.drawColor());

		drawPatchMarkers(g, tMin, tMax);
		drawMayorBar(g, tMin, tMax);
		tradeClusters = List.of();
		if (helpOpen) return;
		Cluster hc = hoveredFlag(mx, my);
		MayorSegment ms = hc == null ? hoveredMayor(mx, my) : null;
		if (hc != null) {
			drawPatchTooltip(g, hc, mx, my);
		} else if (ms != null) {
			drawMayorTooltip(g, ms, mx, my);
		} else if (mx >= gx0 && mx <= gx1 && my >= gy0 && my <= gy1) {
			long t = tMin + Math.round((double) (mx - gx0) / (gx1 - gx0) * (tMax - tMin));
			g.verticalLine(mx, gy0, gy1, 0x90FFFFFF);
			List<Component> tip = new ArrayList<>();
			tip.add(Component.literal(FMT_TOOLTIP.format(Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()))).withColor(COLOR_TEXT));
			for (Line l : lines) {
				double v = CompareMath.valueAt(l.t(), l.v(), t, l.maxGap());
				if (Double.isNaN(v)) continue;
				GraphUi.dot(g, mx, yFor(v, lo, hi), l.color());
				String val = mode == BzConfig.CompareMode.PRICE ? CoinFormat.price(v) : formatY(v, mode, 0.1);
				net.minecraft.network.chat.MutableComponent line = Component.literal(l.faded() ? "∙ " : "■ ").withColor(l.color())
						.append(Component.literal(l.label() + ": ").withColor(COLOR_DIM))
						.append(Component.literal(val).withColor(COLOR_TEXT));
				if (pct && l.raw() != null) {
					line.append(Component.literal("  " + CoinFormat.compact(CompareMath.valueAt(l.t(), l.raw(), t, l.maxGap()))).withColor(COLOR_DIM));
				}
				tip.add(line);
			}
			addMayorLine(tip, t);
			addPatchHint(tip, hoveredPatchLine(mx, my));
			g.setComponentTooltipForNextFrame(font, tip, mx, my);
		}
	}

	/** What the lines show ("% change of the buy price"), then one entry per item with its change; × removes an item. */
	private void drawLegend(GuiGraphicsExtractor g, List<Line> lines, BzConfig.CompareMode mode, int mx, int my) {
		int y = py0 + HEADER_ROW;
		int x = px0 + PAD;
		boolean ratio = mode == BzConfig.CompareMode.RATIO;
		boolean both = drawBuy() && drawSell();
		String side = both ? "buy (solid) & sell (faded)" : drawBuy() ? "buy price" : "sell price";
		String what = switch (mode) {
			case PERCENT -> "% change of " + side + ":";
			case PRICE -> (both ? side : side.substring(0, 1).toUpperCase(Locale.ROOT) + side.substring(1)) + ":";
			case RATIO -> "Ratio of " + side + ":";
		};
		g.text(font, what, x, y, COLOR_DIM);
		headerHits.add(new HeaderHit(x, y - 1, x + font.width(what), y + 9, (drawBuy() ? BUY_HELP : SELL_HELP)
				+ "\nPick buy or sell with the Buy/Sell buttons; \"Both lines\" shows both."));
		x += font.width(what) + 6;
		List<LegendHit> hits = new ArrayList<>();
		if (ratio && !lines.isEmpty()) {
			double[] fl = firstLast(lines.getFirst().v());
			Component c = Component.literal(formatY(fl[1], mode, 1)).withColor(COLOR_TEXT).append(changeText(fl[0], fl[1]));
			g.text(font, c, x, y, COLOR_TEXT);
			x += font.width(c) + 10;
		}
		boolean buyBasis = drawBuy();
		int x0 = x;
		x = legendEntry(g, x, y, itemLabel(), COLOR_MAIN, ratio ? null : seriesChange(buyBasis ? buys : sells), null, mx, my);
		hits.add(new LegendHit(x0, x - 10, null));
		for (CompareItem c : compare) {
			x0 = x;
			String state = c.t.length > 0 ? null : c.handle == null || c.handle.isBusy() ? "loading…"
					: c.handle.status() == HistoryHandle.Status.READY ? "no data" : "can't load";
			x = legendEntry(g, x, y, c.name, c.color, ratio || c.t.length == 0 ? null : seriesChange(buyBasis ? c.buy : c.sell), state, mx, my);
			hits.add(new LegendHit(x0, x - 10, c));
		}
		legendHits = hits;
	}

	private int legendEntry(GuiGraphicsExtractor g, int x, int y, String name, int color, Component change, String state, int mx, int my) {
		boolean hover = mx >= x && my >= y - 1 && my <= y + 9;
		net.minecraft.network.chat.MutableComponent c = Component.literal("■ ").withColor(color)
				.append(Component.literal(font.plainSubstrByWidth(name, 100)).withColor(COLOR_TEXT));
		if (change != null) c.append(change);
		if (state != null) c.append(Component.literal(" (" + state + ")").withColor(GraphUi.COLOR_FAINT));
		int end = x + font.width(c) + font.width(" ✕");
		hover &= mx <= end;
		c.append(Component.literal(" ✕").withColor(hover ? 0xFFFF5555 : 0xFFAA6666));
		g.text(font, c, x, y, COLOR_TEXT);
		if (hover) headerHits.add(new HeaderHit(x, y - 1, end, y + 9, "Click to remove " + name + " from the graph"));
		return x + font.width(c) + 10;
	}

	private static double[] firstLast(double[] v) {
		double first = Double.NaN, last = Double.NaN;
		for (double d : v) {
			if (Double.isNaN(d)) continue;
			if (Double.isNaN(first)) first = d;
			last = d;
		}
		return new double[]{first, last};
	}

	private static Component seriesChange(double[] v) {
		double[] fl = firstLast(v);
		return changeText(fl[0], fl[1]);
	}

	private static Component changeText(double first, double last) {
		if (Double.isNaN(first) || Double.isNaN(last) || first == 0) return Component.empty();
		double pct = (last / first - 1) * 100;
		return Component.literal(String.format(Locale.ROOT, " %+.1f%%", pct)).withColor(pct >= 0 ? 0xFF55FF55 : 0xFFFF5555);
	}

	// ---------------------------------------------------------------- mayors

	/** Mayor colour by Hypixel's category key; unknown/special mayors get a stable colour from their name. */
	private static int mayorColor(MayorHistory.Term t) {
		return colorFor(t.category(), t.mayor());
	}

	private static int colorFor(String category, String name) {
		return switch (category == null ? "" : category) {
			case "mining" -> 0xFFC78B1E;
			case "farming" -> 0xFF3E9E3E;
			case "dungeons" -> 0xFFB03A3A;
			case "fishing" -> 0xFF2E8FA8;
			case "events" -> 0xFFC0509A;
			case "economist" -> 0xFFB8A23A;
			case "slayer" -> 0xFF6E4B9E;
			case "pets" -> 0xFF5E8A3A;
			case "skills" -> 0xFF4A5FC0;
			default -> 0xFF000000 | (Math.abs(String.valueOf(name).hashCode()) & 0x7F7F7F) | 0x303030;
		};
	}

	private int xForTime(long ms, long tMin, long tMax) {
		return gx0 + (int) Math.round((double) (ms - tMin) / (tMax - tMin) * (gx1 - gx0));
	}

	private void drawMayorBar(GuiGraphicsExtractor g, long tMin, long tMax) {
		mayorSegments = List.of();
		if (mayorBarY < 0) return;
		MayorHistory mayors = BzGraphClient.get().mayors().current();
		List<MayorSegment> segs = new ArrayList<>();
		int prevColor = 0;
		for (MayorHistory.Term t : mayors.between(Instant.ofEpochMilli(tMin), Instant.ofEpochMilli(tMax))) {
			int a = Math.max(gx0, xForTime(t.start().toEpochMilli(), tMin, tMax));
			int b = Math.min(gx1 + 1, xForTime(t.end().toEpochMilli(), tMin, tMax));
			if (b <= a) b = a + 1;
			int color = mayorColor(t);
			// two similar colours next to each other would read as one term: darken the second
			if (colorDistance(color, prevColor) < 120) color = shade(color, 0.6);
			prevColor = color;
			segs.add(new MayorSegment(a, b, t));
			g.fill(a, mayorBarY, b, mayorBarY + MAYOR_BAR_H, color);
			g.fill(a, mayorBarY, a + 1, mayorBarY + MAYOR_BAR_H, 0xC0000000); // term boundary
			String name = t.mayor();
			if (b - a > font.width(name) + 4) g.text(font, name, a + 2, mayorBarY + 1, 0xFFFFFFFF);
		}
		if (segs.isEmpty()) {
			String msg = mayors.terms().isEmpty() ? "mayor history loading…" : "no mayor data for this time";
			g.text(font, msg, gx0 + 2, mayorBarY + 1, 0xFF555555);
		}
		mayorSegments = segs;
	}

	private static int colorDistance(int a, int b) {
		return Math.abs((a >> 16 & 0xFF) - (b >> 16 & 0xFF)) + Math.abs((a >> 8 & 0xFF) - (b >> 8 & 0xFF)) + Math.abs((a & 0xFF) - (b & 0xFF));
	}

	private static int shade(int c, double f) {
		int r = (int) ((c >> 16 & 0xFF) * f), gr = (int) ((c >> 8 & 0xFF) * f), b = (int) ((c & 0xFF) * f);
		return 0xFF000000 | r << 16 | gr << 8 | b;
	}

	/** The term under the mouse; slivers too narrow to hit (or label) are picked from up to 3 pixels away. */
	private MayorSegment hoveredMayor(double mx, double my) {
		if (mayorBarY < 0 || my < mayorBarY || my >= mayorBarY + MAYOR_BAR_H) return null;
		// slivers first: they get a few extra pixels on each side so they can be hovered at all
		for (MayorSegment s : mayorSegments) {
			if (s.x1() - s.x0() < 6 && mx >= s.x0() - 2 && mx < s.x1() + 2) return s;
		}
		for (MayorSegment s : mayorSegments) if (mx >= s.x0() && mx < s.x1()) return s;
		return null;
	}

	private void drawMayorTooltip(GuiGraphicsExtractor g, MayorSegment s, int mx, int my) {
		g.outline(s.x0() - 1, mayorBarY - 1, s.x1() - s.x0() + 2, MAYOR_BAR_H + 2, 0xFFFFFFFF);
		MayorHistory.Term t = s.term();
		ZoneId zone = ZoneId.systemDefault();
		List<Component> lines = new ArrayList<>();
		lines.add(Component.literal("Mayor " + t.mayor()).withColor(mayorColor(t) | 0xFF404040)
				.append(Component.literal(t.category().isEmpty() ? "" : "  " + t.category()).withColor(COLOR_DIM)));
		lines.add(Component.literal(FMT_TRADE.format(t.start().atZone(zone)) + " – " + FMT_TRADE.format(t.end().atZone(zone))).withColor(COLOR_DIM));
		for (String perk : t.perks()) lines.add(Component.literal("• " + perk).withColor(COLOR_TEXT));
		if (t.minister() != null) {
			lines.add(Component.literal("Minister " + t.minister()).withColor(0xFFCCCCCC)
					.append(Component.literal(t.ministerPerk() == null ? "" : ": " + t.ministerPerk()).withColor(COLOR_TEXT)));
		}
		g.setComponentTooltipForNextFrame(font, lines, mx, my);
	}

	// ---------------------------------------------------------------- your trades

	private void drawTradeMarkers(GuiGraphicsExtractor g, long tMin, long tMax, double lo, double hi) {
		tradeClusters = List.of();
		if (!BzGraphClient.get().config().showTrades || productId == null) return;
		List<Trade> trades = BzGraphClient.get().trades().forProduct(productId);
		if (trades.isEmpty()) return;
		List<TradeCluster> out = new ArrayList<>();
		for (Trade.Side side : Trade.Side.values()) {
			List<Trade> cur = new ArrayList<>();
			int curCol = Integer.MIN_VALUE;
			for (Trade t : trades) {
				long ms = t.time().toEpochMilli();
				if (t.side() != side || ms < tMin || ms > tMax) continue;
				int x = gx0 + (int) Math.round((double) (ms - tMin) / (tMax - tMin) * (gx1 - gx0));
				int col = (x - gx0) / TRADE_CLUSTER_PX;
				if (!cur.isEmpty() && col != curCol) {
					out.add(tradeCluster(side, cur, tMin, tMax, lo, hi));
					cur = new ArrayList<>();
				}
				curCol = col;
				cur.add(t);
			}
			if (!cur.isEmpty()) out.add(tradeCluster(side, cur, tMin, tMax, lo, hi));
		}
		tradeClusters = out;
		for (TradeCluster c : out) {
			int size = c.trades().size() > 1 ? 4 : 3;
			GraphUi.tradeMarker(g, c.x(), c.y(), c.side() == Trade.Side.SELL, c.trades().size() > 1);
		}
	}

	/** Amount-weighted average price of the cluster, placed where that price is on the graph. */
	private TradeCluster tradeCluster(Trade.Side side, List<Trade> ts, long tMin, long tMax, double lo, double hi) {
		double coins = 0, items = 0;
		long msSum = 0;
		for (Trade t : ts) {
			msSum += t.time().toEpochMilli() / ts.size();
			if (t.hasPrice()) {
				coins += t.unitPrice() * t.amount();
				items += t.amount();
			}
		}
		long ms = msSum;
		int x = gx0 + (int) Math.round((double) (ms - tMin) / (tMax - tMin) * (gx1 - gx0));
		double price = items > 0 ? coins / items : priceOnGraphAt(ts.getFirst());
		int y = Double.isNaN(price) ? (gy0 + gy1) / 2 : yFor(price, lo, hi);
		return new TradeCluster(side, x, Math.max(gy0 + 4, Math.min(gy1 - 4, y)), List.copyOf(ts));
	}

	/**
	 * The graph line a trade corresponds to: instant sells and filled buy orders go against the highest buy
	 * order ("sell" line); filled sell offers and instant buys against the lowest sell offer ("buy" line).
	 */
	/**
	 * Shades each column between the lowest and highest price of the sample(s) it covers. Where a sample spans
	 * several columns (one point per day on long ranges), its range is held until the next sample starts, since
	 * it describes the whole bucket. The band doesn't stretch the y axis; extreme spikes are clipped.
	 */
	private void drawBand(GuiGraphicsExtractor g, double[] mins, double[] maxs, Downsampler.Columns line, double[] series,
						  long tMin, long tMax, int w, double lo, double hi, int color, int alpha) {
		Downsampler.Columns low = Downsampler.columns(times, mins, tMin, tMax, w);
		Downsampler.Columns high = Downsampler.columns(times, maxs, tMin, tMax, w);
		int fill = (alpha << 24) | (color & 0xFFFFFF);
		double[] a = new double[w], b = new double[w], reach = new double[w];
		for (int col = 0; col < w; col++) {
			if (low.count()[col] > 0 && high.count()[col] > 0) {
				a[col] = low.min()[col];
				b[col] = high.max()[col];
			} else {
				int i = sampleAt(tMin + (long) ((double) col / Math.max(1, w - 1) * (tMax - tMin)));
				a[col] = i < 0 ? Double.NaN : mins[i];
				b[col] = i < 0 ? Double.NaN : maxs[i];
			}
			// how far the band reaches beyond the line, for spotting outlier wicks
			double v = columnValue(line, col, series);
			reach[col] = Double.isNaN(v) || Double.isNaN(a[col]) || Double.isNaN(b[col]) ? Double.NaN : Math.max(v - a[col], b[col] - v);
		}
		// A few single trades at silly prices would stretch the band to the plot edge: cap each column's reach
		// at 1.5x what 90% of columns stay within.
		double cap = Resampler.percentile(reach, 0.9) * 1.5;
		for (int col = 0; col < w; col++) {
			if (Double.isNaN(a[col]) || Double.isNaN(b[col]) || b[col] < a[col]) continue;
			double v = columnValue(line, col, series);
			double bot = a[col], top = b[col];
			if (!Double.isNaN(v) && !Double.isNaN(cap) && cap > 0) {
				bot = Math.max(bot, v - cap);
				top = Math.min(top, v + cap);
			}
			int y0 = Math.max(gy0, yFor(top, lo, hi));
			int y1 = Math.min(gy1, yFor(bot, lo, hi));
			if (y0 > gy1 || y1 < gy0) continue;
			g.fill(gx0 + col, y0, gx0 + col + 1, Math.max(y0, y1) + 1, fill);
		}
	}

	/** Index of the sample whose bucket contains {@code t} (latest sample at or before t), or -1. */
	private int sampleAt(long t) {
		int i = Arrays.binarySearch(times, t);
		if (i < 0) i = -i - 2;
		return i;
	}

	/** "low – high" of the bucket a trade/crosshair falls in, for the given line; null if unknown. */
	private String rangeText(double[] mins, double[] maxs, int i) {
		if (i < 0 || i >= mins.length || Double.isNaN(mins[i]) || Double.isNaN(maxs[i])) return null;
		return CoinFormat.compact(mins[i]) + " – " + CoinFormat.compact(maxs[i]);
	}

	private double[] seriesFor(Trade t) {
		boolean sellLine = (t.side() == Trade.Side.SELL) == (t.kind() == Trade.Kind.INSTANT);
		return sellLine ? sells : buys;
	}

	private double priceOnGraphAt(Trade t) {
		if (times.length == 0) return Double.NaN;
		int idx = Arrays.binarySearch(times, t.time().toEpochMilli());
		if (idx < 0) idx = Math.min(times.length - 1, Math.max(0, -idx - 1));
		return seriesFor(t)[idx];
	}

	private TradeCluster hoveredTrade(double mx, double my) {
		TradeCluster best = null;
		double bestD = 5;
		for (TradeCluster c : tradeClusters) {
			double cy = c.y() + (c.side() == Trade.Side.SELL ? -2 : 2);
			double d = Math.hypot(mx - c.x(), my - cy);
			if (d < bestD) {
				bestD = d;
				best = c;
			}
		}
		return best;
	}

	private void drawTradeTooltip(GuiGraphicsExtractor g, TradeCluster c, int mx, int my) {
		List<Component> lines = new ArrayList<>();
		boolean sell = c.side() == Trade.Side.SELL;
		int color = GraphUi.COLOR_TRADE;
		long items = 0;
		double coins = 0, pricedItems = 0;
		for (Trade t : c.trades()) {
			items += t.amount();
			if (t.hasPrice()) {
				coins += t.unitPrice() * t.amount();
				pricedItems += t.amount();
			}
		}
		ZoneId zone = ZoneId.systemDefault();
		if (c.trades().size() == 1) {
			Trade t = c.trades().getFirst();
			String kind = switch (t.kind()) {
				case INSTANT -> sell ? "instant sell" : "instant buy";
				case OFFER -> sell ? "sell offer filled" : "buy order filled";
				case PARTIAL -> sell ? "partly filled offer (claimed)" : "partly filled order (claimed)";
			};
			lines.add(Component.literal((sell ? "\u25bc You sold " : "\u25b2 You bought ") + t.amount() + "\u00d7").withColor(color)
					.append(Component.literal("  " + kind).withColor(COLOR_DIM)));
			lines.add(t.hasPrice()
					? Component.literal("@ " + CoinFormat.price(t.unitPrice()) + " each").withColor(COLOR_TEXT)
					.append(Component.literal("  (" + CoinFormat.compact(t.unitPrice() * t.amount()) + " coins)").withColor(COLOR_DIM))
					: Component.literal("price unknown").withColor(COLOR_DIM));
			lines.add(Component.literal(FMT_TRADE.format(t.time().atZone(zone))).withColor(COLOR_DIM));
		} else {
			lines.add(Component.literal((sell ? "\u25bc " : "\u25b2 ") + c.trades().size() + (sell ? " sells" : " buys") + " · " + items + " items").withColor(color));
			if (pricedItems > 0) {
				lines.add(Component.literal("avg " + CoinFormat.price(coins / pricedItems) + " each").withColor(COLOR_TEXT)
						.append(Component.literal("  (" + CoinFormat.compact(coins) + " coins)").withColor(COLOR_DIM)));
			}
			lines.add(Component.literal(FMT_TRADE.format(c.trades().getFirst().time().atZone(zone)) + " – "
					+ FMT_TRADE.format(c.trades().getLast().time().atZone(zone))).withColor(COLOR_DIM));
		}
		// the market's low-high in the bucket where the (first) trade happened, on the matching line
		boolean sellLine = seriesFor(c.trades().getFirst()) == sells;
		String range = rangeText(sellLine ? minSells : minBuys, sellLine ? maxSells : maxBuys,
				sampleAt(c.trades().getFirst().time().toEpochMilli()));
		if (range != null) {
			lines.add(Component.literal("market then: " + range).withColor(COLOR_DIM)
					.append(Component.literal(sellLine ? "  (sell)" : "  (buy)").withColor(sellLine ? COLOR_SELL : COLOR_BUY)));
		}
		// compare with the current price on the matching line
		double[] series = seriesFor(c.trades().getFirst());
		double now = series.length == 0 ? Double.NaN : series[series.length - 1];
		if (pricedItems > 0 && !Double.isNaN(now)) {
			double pct = PriceImpact.percent(coins / pricedItems, now);
			lines.add(Component.literal("now " + CoinFormat.price(now)).withColor(COLOR_DIM)
					.append(Component.literal(String.format(Locale.ROOT, "  (%+.1f%%)", pct))
							.withColor(pct >= 0 ? 0xFF55FF55 : 0xFFFF5555)));
		}
		g.setComponentTooltipForNextFrame(font, lines, mx, my);
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

		for (Cluster c : out) GraphUi.patchMarker(g, c.x(), gy0, gy1, c.relevant(), c.patches().size() > 1);
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
			String note = p.noteFor(productId);
			if (note != null) {
				lines.add(Component.literal("For this item:").withColor(COLOR_PATCH).getVisualOrderText());
				List<FormattedCharSequence> nl = font.split(Component.literal(note).withColor(0xFFFFDDFF), wrap);
				lines.addAll(nl.subList(0, Math.min(5, nl.size())));
			}
			if (!p.summary().isEmpty()) {
				int max = note != null ? 2 : 3; // the general summary matters less once we have an item note
				List<FormattedCharSequence> sum = font.split(Component.literal(p.summary()).withColor(note != null ? 0xFF999999 : 0xFFDDDDDD), wrap);
				lines.addAll(sum.subList(0, Math.min(max, sum.size())));
				if (sum.size() > max) lines.add(Component.literal("...").withColor(COLOR_DIM).getVisualOrderText());
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

	/** Pulsing dot on the live price so it reads as "now", not just another sample. */
	private void drawLiveMarker(GuiGraphicsExtractor g, long tMin, long tMax, double lo, double hi) {
		if (liveIndex < 0) return;
		int x = gx0 + (int) Math.round((double) (times[liveIndex] - tMin) / (tMax - tMin) * (gx1 - gx0));
		int glow = (int) (60 + 60 * Math.sin(System.currentTimeMillis() / 250.0));
		if (showBuy && !Double.isNaN(buys[liveIndex])) {
			int y = yFor(buys[liveIndex], lo, hi);
			g.fill(x - 3, y - 3, x + 4, y + 4, (glow << 24) | (COLOR_BUY & 0xFFFFFF));
			GraphUi.dot(g, x, y, COLOR_BUY);
		}
		if (showSell && !Double.isNaN(sells[liveIndex])) {
			int y = yFor(sells[liveIndex], lo, hi);
			g.fill(x - 3, y - 3, x + 4, y + 4, (glow << 24) | (COLOR_SELL & 0xFFFFFF));
			GraphUi.dot(g, x, y, COLOR_SELL);
		}
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

	private void drawCrosshair(GuiGraphicsExtractor g, int mx, int my, long tMin, long tMax, double lo, double hi, Cluster patch) {
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
		if (showBuy && !Double.isNaN(buys[idx])) GraphUi.dot(g, px, yFor(buys[idx], lo, hi), COLOR_BUY);
		if (showSell && !Double.isNaN(sells[idx])) GraphUi.dot(g, px, yFor(sells[idx], lo, hi), COLOR_SELL);

		List<Component> lines = new ArrayList<>();
		String when = FMT_TOOLTIP.format(Instant.ofEpochMilli(times[idx]).atZone(ZoneId.systemDefault()));
		if (idx == liveIndex) {
			lines.add(Component.literal("Latest price  ").withColor(0xFF55FF55)
					.append(Component.literal(when).withColor(COLOR_DIM)));
		} else {
			lines.add(Component.literal(when).withColor(COLOR_TEXT));
		}
		String buyRange = rangeText(minBuys, maxBuys, idx);
		String sellRange = rangeText(minSells, maxSells, idx);
		lines.add(Component.literal("Buy:  ").withColor(COLOR_BUY).append(Component.literal(CoinFormat.price(buys[idx])).withColor(COLOR_TEXT))
				.append(Component.literal(buyRange == null ? "" : "  (" + buyRange + ")").withColor(COLOR_DIM)));
		lines.add(Component.literal("Sell: ").withColor(COLOR_SELL).append(Component.literal(CoinFormat.price(sells[idx])).withColor(COLOR_TEXT))
				.append(Component.literal(sellRange == null ? "" : "  (" + sellRange + ")").withColor(COLOR_DIM)));
		if (!Double.isNaN(buys[idx]) && !Double.isNaN(sells[idx]) && sells[idx] > 0) {
			double sp = buys[idx] - sells[idx];
			lines.add(Component.literal("Spread: ").withColor(COLOR_DIM).append(Component.literal(CoinFormat.compact(sp)).withColor(COLOR_TEXT))
					.append(Component.literal(String.format(Locale.ROOT, "  (%.1f%%)", sp / sells[idx] * 100)).withColor(COLOR_DIM)));
		}
		lines.add(Component.literal("Volume: ").withColor(COLOR_DIM).append(Component.literal(CoinFormat.compact(vols[idx])).withColor(COLOR_TEXT)));
		addMayorLine(lines, times[idx]);
		addPatchHint(lines, patch);
		g.setComponentTooltipForNextFrame(font, lines, mx, my);
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
