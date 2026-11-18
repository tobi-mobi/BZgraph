package dev.bzgraph.mc;

import dev.bzgraph.BzGraphClient;
import dev.bzgraph.core.Favorites;
import dev.bzgraph.core.HistoryHandle;
import dev.bzgraph.core.TimeRange;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Your favourite items as mini charts, in categories you make yourself. Drag a chart to sort it or move it to
 * another category; right-click a chart or a category for more; click a category's name to fold it.
 */
public final class FavoritesScreen extends Screen implements BzScreen {
	private static final int PAD = 8;
	private static final int GAP = 6;
	private static final int COLS = 3;
	private static final int TILE_MIN_W = 130;
	private static final int HEADER_H = 26;
	private static final int SECTION_H = 14;
	private static final int EMPTY_H = 26;
	private static final int DRAG_START_PX = 4;
	private static final int MENU_ROW = 12;

	private final Screen parent;
	private final Map<String, HistoryHandle> handles = new HashMap<>();
	private TimeRange range;
	private int px0, py0, px1, py1, gridY0, gridY1;
	private double scroll, maxScroll;

	/** A category's header row and the area below it (drop target), as laid out in the last frame. */
	private record Section(String cat, int x0, int y0, int x1, int headerY1, int y1) {
	}

	private record Tile(String id, String cat, int index, int x0, int y0, int x1, int y1) {
	}

	private List<Section> sections = List.of();
	private List<Tile> tiles = List.of();

	// ---- dragging a chart
	private String pressId;
	private double pressX, pressY;
	private boolean dragging;
	private String dropCat;
	private int dropIndex;

	// ---- right-click menu
	/** A menu row: runs {@code action}, or opens {@code sub} beside it when hovered. */
	private record MenuItem(String label, int color, Runnable action, List<MenuItem> sub) {
		MenuItem(String label, int color, Runnable action) {
			this(label, color, action, null);
		}
	}

	private List<MenuItem> menu;
	private int menuX, menuY, menuW;
	/** Open submenu: index of its row in {@link #menu} (-1 = none), and where it's drawn. */
	private int subRow = -1;
	private int subX, subY, subW;

	// ---- category name box
	private EditBox nameBox;
	private String nameTitle;
	private Consumer<String> nameDone;
	private String nameError;

	/** @param parent screen to return to when closed (null = the game) */
	public FavoritesScreen(Screen parent) {
		super(Component.literal("BZgraph favourites"));
		this.parent = parent;
		TimeRange r = TimeRange.fromLabel(BzGraphClient.get().config().miniChartRange);
		this.range = r == null ? TimeRange.WEEK : r;
	}

	private static Favorites favs() {
		return BzGraphClient.get().config().favorites();
	}

	private static void save() {
		BzGraphClient.get().config().save();
	}

	@Override
	protected void init() {
		int pw = Math.min(width - 16, Math.max(470, Math.round(width * 0.85f)));
		int ph = Math.min(height - 16, Math.max(240, Math.round(height * 0.85f)));
		px0 = (width - pw) / 2;
		py0 = (height - ph) / 2;
		px1 = px0 + pw;
		py1 = py0 + ph;
		gridY0 = py0 + HEADER_H;
		gridY1 = py1 - 16;

		int tx = px1 - PAD;
		Button rangeBtn = addRenderableWidget(Button.builder(Component.literal("Range: " + range.label()), b -> cycleRange())
				.bounds(tx -= 60, py0 + 5, 60, 16).build());
		rangeBtn.setTooltip(Tooltip.create(Component.literal("Range of the mini charts (also the bazaar's mini chart)")));
		addRenderableWidget(Button.builder(Component.literal("Search items"), b -> minecraft.gui.setScreen(ItemSearchScreen.openGraph(this, false)))
				.bounds(tx -= 2 + 80, py0 + 5, 80, 16).build());
		Button add = addRenderableWidget(Button.builder(Component.literal("+ Category"), b -> askName("New category", null, n -> {}))
				.bounds(tx -= 2 + 70, py0 + 5, 70, 16).build());
		add.setTooltip(Tooltip.create(Component.literal("Make a category, then drag charts into it")));
		String last = BzGraphClient.get().lastItemName();
		if (last != null) {
			Button cont = addRenderableWidget(Button.builder(Component.literal("Last: " + font.plainSubstrByWidth(last, 70)), b -> {
				GraphScreen s = BzGraphClient.get().reopenLast(this);
				if (s != null) minecraft.gui.setScreen(s);
			}).bounds(tx -= 2 + 100, py0 + 5, 100, 16).build());
			cont.setTooltip(Tooltip.create(Component.literal("Reopen the graph you looked at last: " + last)));
		}
		if (nameBox != null) openNameBox(nameBox.getValue()); // keep an open name box across resizes
	}

	private void cycleRange() {
		TimeRange[] all = TimeRange.values();
		range = all[(range.ordinal() + 1) % all.length];
		BzConfig cfg = BzGraphClient.get().config();
		cfg.miniChartRange = range.label();
		cfg.save();
		handles.clear();
		rebuildWidgets();
	}

	private void open(String id) {
		minecraft.gui.setScreen(new GraphScreen(this, id, BzGraphClient.get().trades().displayName(id)));
	}

	private HistoryHandle handle(String id) {
		return handles.computeIfAbsent(id, k -> BzGraphClient.get().repository().load(k, range));
	}

	// ---------------------------------------------------------------- category name box

	/** Asks for a category name: new ({@code rename} null) or renaming {@code rename}; then calls {@code done}. */
	private void askName(String title, String rename, Consumer<String> done) {
		nameTitle = title;
		nameError = null;
		nameDone = name -> {
			String n = rename == null ? favs().addCategory(name) : favs().renameCategory(rename, name) ? name.strip() : null;
			if (n == null) {
				nameError = "Empty, longer than " + Favorites.MAX_NAME + " letters, or already used";
				return;
			}
			save();
			closeNameBox();
			done.accept(n);
		};
		openNameBox(rename == null ? "" : rename);
	}

	private void openNameBox(String value) {
		if (nameBox != null) removeWidget(nameBox);
		// not added as a renderable: drawn last, above the dimmed backdrop
		nameBox = addWidget(new EditBox(font, width / 2 - 90, height / 2 - 4, 180, 18, Component.literal("Category name")));
		nameBox.setMaxLength(Favorites.MAX_NAME);
		nameBox.setValue(value);
		nameBox.setHint(Component.literal("Category name").withColor(0xFF808080));
		setFocused(nameBox);
	}

	private void closeNameBox() {
		if (nameBox != null) removeWidget(nameBox);
		nameBox = null;
		nameDone = null;
		setFocused(null);
	}

	private void drawNameBox(GuiGraphicsExtractor g, int mx, int my, float partialTick) {
		if (nameBox == null) return;
		int x0 = width / 2 - 100, y0 = height / 2 - 22, x1 = width / 2 + 100, y1 = height / 2 + 30;
		g.fill(0, 0, width, height, 0x60000000);
		g.fill(x0, y0, x1, y1, 0xF8101014);
		g.outline(x0, y0, x1 - x0, y1 - y0, GraphUi.COLOR_ACCENT);
		g.text(font, nameTitle, x0 + 8, y0 + 6, GraphUi.COLOR_ACCENT);
		String hint = nameError != null ? nameError : "Enter: save · Esc: cancel";
		g.text(font, font.plainSubstrByWidth(hint, x1 - x0 - 16), x0 + 8, y1 - 12, nameError != null ? 0xFFFF7777 : GraphUi.COLOR_FAINT);
		nameBox.extractRenderState(g, mx, my, partialTick);
	}

	// ---------------------------------------------------------------- menus

	private void openTileMenu(Tile t, double mx, double my) {
		List<MenuItem> items = new ArrayList<>();
		items.add(new MenuItem("Open graph", GraphUi.COLOR_TEXT, () -> open(t.id())));
		// every destination in one "Move to" submenu
		List<MenuItem> dest = new ArrayList<>();
		for (String c : favs().categories()) {
			if (!c.equals(t.cat())) dest.add(new MenuItem(c, 0xFFDDDDDD, () -> moveTo(t.id(), c)));
		}
		if (!t.cat().equals(Favorites.NONE)) dest.add(new MenuItem("Uncategorized", GraphUi.COLOR_DIM, () -> moveTo(t.id(), Favorites.NONE)));
		dest.add(new MenuItem("+ New category\u2026", GraphUi.COLOR_ACCENT, () -> askName("New category", null, c -> moveTo(t.id(), c))));
		items.add(new MenuItem("Move to", 0xFFDDDDDD, null, dest));
		items.add(new MenuItem("Remove from favourites", 0xFFFF7777, () -> {
			favs().remove(t.id());
			save();
		}));
		openMenu(items, mx, my);
	}

	private void openSectionMenu(Section s, double mx, double my) {
		if (s.cat().equals(Favorites.NONE)) return;
		List<String> cats = favs().categories();
		int i = cats.indexOf(s.cat());
		List<MenuItem> items = new ArrayList<>();
		items.add(new MenuItem("Rename…", GraphUi.COLOR_TEXT, () -> askName("Rename \"" + s.cat() + "\"", s.cat(), n -> {})));
		if (i > 0) items.add(new MenuItem("Move up", 0xFFDDDDDD, () -> {
			favs().moveCategory(s.cat(), -1);
			save();
		}));
		if (i >= 0 && i < cats.size() - 1) items.add(new MenuItem("Move down", 0xFFDDDDDD, () -> {
			favs().moveCategory(s.cat(), 1);
			save();
		}));
		items.add(new MenuItem("Delete category (charts stay)", 0xFFFF7777, () -> {
			favs().deleteCategory(s.cat());
			save();
		}));
		openMenu(items, mx, my);
	}

	private void moveTo(String id, String cat) {
		favs().move(id, cat, Integer.MAX_VALUE);
		save();
	}

	private void openMenu(List<MenuItem> items, double mx, double my) {
		menu = items;
		subRow = -1;
		menuW = 0;
		for (MenuItem m : items) menuW = Math.max(menuW, font.width(m.label()) + (m.sub() != null ? 22 : 12));
		menuX = (int) Math.min(mx, width - menuW - 2);
		menuY = (int) Math.min(my, height - items.size() * MENU_ROW - 6);
	}

	private static int rowAt(List<MenuItem> items, int x, int y, int w, double mx, double my) {
		if (items == null || mx < x || mx >= x + w || my < y + 2) return -1;
		int i = (int) ((my - y - 2) / MENU_ROW);
		return i < items.size() ? i : -1;
	}

	private int menuRowAt(double mx, double my) {
		return rowAt(menu, menuX, menuY, menuW, mx, my);
	}

	private List<MenuItem> subItems() {
		return menu == null || subRow < 0 ? null : menu.get(subRow).sub();
	}

	private int subRowAt(double mx, double my) {
		return rowAt(subItems(), subX, subY, subW, mx, my);
	}

	/** Hovering a row with a submenu opens it beside the menu; it stays open while the mouse is in it. */
	private void updateSubmenu(double mx, double my) {
		int row = menuRowAt(mx, my);
		if (row >= 0 && row != subRow) {
			subRow = menu.get(row).sub() != null ? row : -1;
			List<MenuItem> sub = subItems();
			if (sub != null) {
				subW = 0;
				for (MenuItem m : sub) subW = Math.max(subW, font.width(m.label()) + 12);
				subX = menuX + menuW - 1;
				if (subX + subW > width - 2) subX = menuX - subW + 1; // no room on the right: open to the left
				subY = Math.min(menuY + row * MENU_ROW, height - sub.size() * MENU_ROW - 6);
			}
		}
	}

	private void drawMenu(GuiGraphicsExtractor g, int mx, int my) {
		if (menu == null) return;
		updateSubmenu(mx, my);
		drawMenuBox(g, menu, menuX, menuY, menuW, menuRowAt(mx, my), subRow);
		List<MenuItem> sub = subItems();
		if (sub != null) drawMenuBox(g, sub, subX, subY, subW, subRowAt(mx, my), -1);
	}

	private void drawMenuBox(GuiGraphicsExtractor g, List<MenuItem> items, int x, int y0, int w, int hover, int open) {
		int h = items.size() * MENU_ROW + 4;
		g.fill(x, y0, x + w, y0 + h, 0xF8101014);
		g.outline(x, y0, w, h, 0xFF6A6A78);
		for (int i = 0; i < items.size(); i++) {
			int y = y0 + 2 + i * MENU_ROW;
			if (i == hover || i == open) g.fill(x + 1, y, x + w - 1, y + MENU_ROW, 0x40FFFFFF);
			MenuItem m = items.get(i);
			g.text(font, m.label(), x + 6, y + 2, m.color());
			if (m.sub() != null) g.text(font, "\u25b8", x + w - 10, y + 2, GraphUi.COLOR_DIM);
		}
	}

	// ---------------------------------------------------------------- input

	@Override
	public boolean keyPressed(KeyEvent event) {
		int k = event.key();
		if (nameBox != null) {
			if (k == GLFW.GLFW_KEY_ESCAPE) {
				closeNameBox();
				return true;
			}
			if (k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER) {
				nameDone.accept(nameBox.getValue());
				return true;
			}
			return super.keyPressed(event);
		}
		if (menu != null) { // any key closes the menu
			menu = null;
			return true;
		}
		if (BzGraphClient.get().openKey().matches(event)) {
			onClose();
			return true;
		}
		if (k == GLFW.GLFW_KEY_F) {
			minecraft.gui.setScreen(ItemSearchScreen.openGraph(this, true)); // true: the "f" of this press isn't typed
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x(), my = event.y();
		if (nameBox != null) {
			if (nameBox.isMouseOver(mx, my)) return super.mouseClicked(event, doubleClick);
			closeNameBox(); // click outside cancels
			return true;
		}
		if (menu != null) {
			int si = subRowAt(mx, my);
			if (si >= 0) {
				Runnable action = subItems().get(si).action();
				menu = null;
				action.run();
				return true;
			}
			int i = menuRowAt(mx, my);
			if (i >= 0 && menu.get(i).sub() != null) { // clicking "Move to" just opens its submenu
				updateSubmenu(mx, my);
				return true;
			}
			Runnable action = i >= 0 ? menu.get(i).action() : null;
			menu = null;
			if (action != null) action.run();
			return true;
		}
		if (super.mouseClicked(event, doubleClick)) return true;
		if (my < gridY0 || my > gridY1) return false;
		Tile t = tileAt(mx, my);
		if (t != null) {
			if (event.button() == 1) {
				openTileMenu(t, mx, my);
			} else if (event.button() == 0) {
				pressId = t.id();
				pressX = mx;
				pressY = my;
				dragging = false;
			}
			return true;
		}
		Section s = sectionAt(mx, my);
		if (s != null && my < s.headerY1()) {
			if (event.button() == 1) {
				openSectionMenu(s, mx, my);
			} else if (event.button() == 0) {
				favs().toggleCollapsed(s.cat());
				save();
			}
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (pressId != null && !dragging && Math.hypot(event.x() - pressX, event.y() - pressY) > DRAG_START_PX) dragging = true;
		if (dragging) return true;
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (pressId != null && event.button() == 0) {
			String id = pressId;
			pressId = null;
			if (dragging) {
				dragging = false;
				if (dropCat != null) {
					favs().move(id, dropCat, dropIndex);
					save();
				}
			} else {
				Tile t = tileAt(event.x(), event.y());
				if (t != null && t.id().equals(id)) {
					if (overRemove(t, event.x(), event.y())) {
						favs().remove(id);
						save();
					} else {
						open(id);
					}
				}
			}
			return true;
		}
		return super.mouseReleased(event);
	}

	private Tile tileAt(double mx, double my) {
		if (my < gridY0 || my > gridY1) return null;
		for (Tile t : tiles) if (mx >= t.x0() && mx <= t.x1() && my >= t.y0() && my <= t.y1()) return t;
		return null;
	}

	private Section sectionAt(double mx, double my) {
		if (my < gridY0 || my > gridY1) return null;
		for (Section s : sections) if (mx >= s.x0() && mx <= s.x1() && my >= s.y0() && my <= s.y1()) return s;
		return null;
	}

	private static boolean overRemove(Tile t, double mx, double my) {
		return mx >= t.x1() - 12 && mx <= t.x1() - 2 && my >= t.y0() + 2 && my <= t.y0() + 12;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		if (menu != null || nameBox != null) return true;
		scroll = Math.max(0, Math.min(maxScroll, scroll - sy * 30));
		return true;
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
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partialTick) {
		BzConfig cfg = BzGraphClient.get().config();
		GraphUi.panel(g, cfg, width, height, px0, py0, px1, py1);
		g.text(font, "★ Favourites", px0 + PAD, py0 + 9, GraphUi.COLOR_ACCENT);
		Favorites f = favs();

		if (dragging) { // scroll while dragging near the top or bottom edge
			if (my < gridY0 + 16) scroll = Math.max(0, scroll - 6);
			else if (my > gridY1 - 16) scroll = Math.min(maxScroll, scroll + 6);
		}

		if (f.size() == 0 && f.categories().isEmpty()) {
			int cx = (px0 + px1) / 2, cy = (gridY0 + gridY1) / 2;
			g.centeredText(font, "No favourites yet", cx, cy - 14, GraphUi.COLOR_DIM);
			g.centeredText(font, "Open an item's graph and click ☆ next to its name (or press S).", cx, cy - 2, GraphUi.COLOR_FAINT);
			g.centeredText(font, "Search items (F) to find one.", cx, cy + 10, GraphUi.COLOR_FAINT);
			sections = List.of();
			tiles = List.of();
		} else {
			drawGrid(g, f, mx, my);
		}
		String hint = "Drag charts to sort them · right-click a chart or category for options · click a category to fold it";
		g.text(font, font.plainSubstrByWidth(hint, px1 - px0 - 2 * PAD), px0 + PAD, py1 - 12, GraphUi.COLOR_FOOTER);
		super.extractRenderState(g, mx, my, partialTick);
		drawMenu(g, mx, my);
		drawNameBox(g, mx, my, partialTick);
	}

	private void drawGrid(GuiGraphicsExtractor g, Favorites f, int mx, int my) {
		int innerW = px1 - px0 - 2 * PAD;
		int cols = Math.max(1, Math.min(COLS, (innerW + GAP) / (TILE_MIN_W + GAP)));
		int tw = (innerW - GAP * (cols - 1)) / cols;
		int th = Math.max(90, Math.min(170, tw * 11 / 20));
		boolean interactive = menu == null && nameBox == null;

		List<String> cats = new ArrayList<>(f.categories());
		// "Uncategorized" only matters once you have categories (or while dragging, as a place to drop)
		if (cats.isEmpty() || !f.items(Favorites.NONE).isEmpty() || dragging) cats.add(Favorites.NONE);
		boolean showHeaders = !f.categories().isEmpty();

		List<Section> outS = new ArrayList<>();
		List<Tile> outT = new ArrayList<>();
		int y = gridY0 - (int) scroll;
		int x0 = px0 + PAD, x1 = px1 - PAD;
		for (String cat : cats) {
			int sy0 = y;
			List<String> items = f.items(cat);
			boolean collapsed = showHeaders && f.isCollapsed(cat);
			if (showHeaders) y += SECTION_H + 2;
			int headerY1 = y;
			if (!collapsed) {
				if (items.isEmpty()) {
					y += EMPTY_H;
				} else {
					for (int i = 0; i < items.size(); i++) {
						int tx0 = x0 + (i % cols) * (tw + GAP);
						int ty0 = y + (i / cols) * (th + GAP);
						outT.add(new Tile(items.get(i), cat, i, tx0, ty0, tx0 + tw, ty0 + th));
					}
					y += ((items.size() + cols - 1) / cols) * (th + GAP) - GAP;
				}
			}
			outS.add(new Section(cat, x0, sy0, x1, headerY1, y));
			y += GAP * 2;
		}
		maxScroll = Math.max(0, y + (int) scroll - gridY0 - (gridY1 - gridY0) - GAP * 2);
		scroll = Math.min(scroll, maxScroll);
		sections = outS;
		tiles = outT;

		computeDrop(mx, my);
		g.enableScissor(px0 + 1, gridY0, px1 - 1, gridY1);
		for (Section s : outS) {
			if (s.y1() < gridY0 - 20 || s.y0() > gridY1) continue;
			boolean dropHere = dragging && s.cat().equals(dropCat);
			if (showHeaders) drawSectionHeader(g, f, s, mx, my, interactive, dropHere);
			boolean open = !showHeaders || !f.isCollapsed(s.cat());
			if (open && f.items(s.cat()).isEmpty()) {
				int ey = s.headerY1();
				g.outline(s.x0(), ey, s.x1() - s.x0(), EMPTY_H - 2, dropHere ? GraphUi.COLOR_ACCENT : 0xFF3A3A44);
				g.centeredText(font, dragging ? "Drop here" : "Empty · drag charts here", (s.x0() + s.x1()) / 2, ey + 8, GraphUi.COLOR_FAINT);
			}
		}
		for (Tile t : outT) {
			if (t.y1() < gridY0 || t.y0() > gridY1) continue;
			boolean hover = interactive && !dragging && my >= gridY0 && my <= gridY1
					&& mx >= t.x0() && mx <= t.x1() && my >= t.y0() && my <= t.y1();
			BazaarMiniChart.drawCard(g, font, t.x0(), t.y0(), t.x1(), t.y1(), BzGraphClient.get().trades().displayName(t.id()),
					handle(t.id()), range, null, hover, 12, hover ? mx : -1, hover ? my : -1); // hover: price at the mouse
			if (dragging && t.id().equals(pressId)) g.fill(t.x0(), t.y0(), t.x1(), t.y1(), 0xB0101014);
			if (hover) {
				boolean rm = overRemove(t, mx, my);
				g.fill(t.x1() - 12, t.y0() + 2, t.x1() - 2, t.y0() + 12, rm ? 0xFFAA3333 : 0xC0101014);
				g.text(font, "✕", t.x1() - 10, t.y0() + 3, rm ? 0xFFFFFFFF : 0xFFAA6666);
			}
		}
		drawDropMarker(g);
		g.disableScissor();

		if (dragging && pressId != null) { // the dragged chart's name follows the mouse
			String name = BzGraphClient.get().trades().displayName(pressId);
			int w = font.width(name) + 10;
			g.fill(mx + 6, my + 6, mx + 6 + w, my + 20, 0xE0202028);
			g.outline(mx + 6, my + 6, w, 14, GraphUi.COLOR_ACCENT);
			g.text(font, name, mx + 11, my + 9, GraphUi.COLOR_TEXT);
		}
	}

	private void drawSectionHeader(GuiGraphicsExtractor g, Favorites f, Section s, int mx, int my, boolean interactive, boolean dropHere) {
		boolean collapsed = f.isCollapsed(s.cat());
		int n = f.items(s.cat()).size();
		boolean none = s.cat().equals(Favorites.NONE);
		String name = none ? "Uncategorized" : s.cat();
		boolean hover = interactive && mx >= s.x0() && mx <= s.x1() && my >= s.y0() && my < s.headerY1();
		if (hover || dropHere) g.fill(s.x0(), s.y0(), s.x1(), s.headerY1() - 2, dropHere ? 0x30FFD25A : 0x18FFFFFF);
		g.text(font, Component.literal((collapsed ? "▸ " : "▾ ") + name).withColor(none ? GraphUi.COLOR_DIM : GraphUi.COLOR_ACCENT)
				.append(Component.literal("  " + n).withColor(GraphUi.COLOR_FAINT)), s.x0() + 2, s.y0() + 3, GraphUi.COLOR_TEXT);
		g.horizontalLine(s.x0(), s.x1() - 1, s.headerY1() - 2, 0x40FFFFFF);
	}

	/** Where the dragged chart would land: before/after the chart under the mouse, or at the end of a category. */
	private void computeDrop(int mx, int my) {
		dropCat = null;
		if (!dragging || pressId == null) return;
		Favorites f = favs();
		String from = f.categoryOf(pressId);
		int fromIndex = from == null ? -1 : f.items(from).indexOf(pressId);
		for (Tile t : tiles) {
			if (mx < t.x0() - GAP || mx > t.x1() + GAP || my < t.y0() || my > t.y1()) continue;
			int idx = t.index() + (mx > (t.x0() + t.x1()) / 2 ? 1 : 0);
			if (t.cat().equals(from) && fromIndex >= 0 && fromIndex < idx) idx--; // counted without the dragged chart
			dropCat = t.cat();
			dropIndex = idx;
			return;
		}
		Section s = sectionAt(mx, my);
		if (s == null) { // just below a section counts as its end
			for (Section sec : sections) if (mx >= sec.x0() && mx <= sec.x1() && my > sec.y1() && my <= sec.y1() + GAP * 2) s = sec;
		}
		if (s != null) {
			dropCat = s.cat();
			dropIndex = Integer.MAX_VALUE;
		}
	}

	/** An accent bar in the gap where the dragged chart will go. */
	private void drawDropMarker(GuiGraphicsExtractor g) {
		if (!dragging || dropCat == null || dropIndex == Integer.MAX_VALUE) return;
		Favorites f = favs();
		String from = f.categoryOf(pressId);
		int fromIndex = from == null ? -1 : f.items(from).indexOf(pressId);
		// dropIndex is counted without the dragged chart; map it back to the charts as drawn
		int visual = dropCat.equals(from) && fromIndex >= 0 && fromIndex <= dropIndex ? dropIndex + 1 : dropIndex;
		Tile before = null, after = null;
		for (Tile t : tiles) {
			if (!t.cat().equals(dropCat)) continue;
			if (t.index() == visual) before = t;
			if (t.index() == visual - 1) after = t;
		}
		int x, y0, y1;
		if (before != null && (after == null || after.y0() != before.y0())) {
			x = before.x0() - GAP / 2 - 1;
			y0 = before.y0();
			y1 = before.y1();
		} else if (after != null) {
			x = after.x1() + GAP / 2 - 1;
			y0 = after.y0();
			y1 = after.y1();
		} else {
			return;
		}
		g.fill(x, y0, x + 2, y1, GraphUi.COLOR_ACCENT);
	}
}
