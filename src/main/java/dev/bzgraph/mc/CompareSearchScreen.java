package dev.bzgraph.mc;

import dev.bzgraph.BzGraphClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/** Pick a bazaar item to add to a graph for comparison (search by name or product ID). */
public final class CompareSearchScreen extends Screen implements BzScreen {
	private static final int ROW_H = 12;
	private static final int MAX_ROWS = 200;

	private final Screen back;
	private final Screen root;
	private final Set<String> exclude;
	private final Consumer<String> onPick;
	private EditBox search;
	private List<String[]> rows = List.of(); // [id, name]
	private double scroll;
	private int listX0, listX1, listY0, listY1;

	public CompareSearchScreen(Screen back, Screen root, Set<String> exclude, Consumer<String> onPick) {
		super(Component.literal("Compare with..."));
		this.back = back;
		this.root = root;
		this.exclude = exclude;
		this.onPick = onPick;
	}

	@Override
	protected void init() {
		listX0 = Math.max(10, width / 2 - 160);
		listX1 = Math.min(width - 10, width / 2 + 160);
		String prev = search == null ? "" : search.getValue();
		search = addRenderableWidget(new EditBox(font, listX0, 26, listX1 - listX0, 20, Component.literal("Search")));
		search.setHint(Component.literal("Item name or ID, e.g. diamond"));
		search.setMaxLength(64);
		search.setValue(prev);
		search.setResponder(s -> rebuild());
		listY0 = 52;
		listY1 = height - 36;
		addRenderableWidget(Button.builder(Component.literal("Back"), b -> minecraft.gui.setScreen(back))
				.bounds(width / 2 - 75, height - 28, 150, 20).build());
		setInitialFocus(search);
		rebuild();
	}

	private void rebuild() {
		BzGraphClient c = BzGraphClient.get();
		c.ensureProducts();
		Set<String> products = c.products();
		String q = search == null ? "" : search.getValue().strip().toLowerCase(Locale.ROOT);
		String qId = q.toUpperCase(Locale.ROOT).replace(' ', '_');
		List<String[]> starts = new ArrayList<>(), contains = new ArrayList<>();
		if (products != null) {
			for (String id : products) {
				if (exclude.contains(id)) continue;
				String name = c.trades().displayName(id);
				String ln = name.toLowerCase(Locale.ROOT);
				if (q.isEmpty() || ln.startsWith(q) || id.startsWith(qId)) starts.add(new String[]{id, name});
				else if (ln.contains(q) || id.contains(qId)) contains.add(new String[]{id, name});
			}
		}
		starts.sort((a, b) -> a[1].compareToIgnoreCase(b[1]));
		contains.sort((a, b) -> a[1].compareToIgnoreCase(b[1]));
		starts.addAll(contains);
		rows = starts.size() > MAX_ROWS ? starts.subList(0, MAX_ROWS) : starts;
		scroll = 0;
	}

	@Override
	public void tick() {
		if (rows.isEmpty() && BzGraphClient.get().products() != null && search.getValue().isEmpty()) rebuild();
	}

	private void pick(String id) {
		onPick.accept(id);
		minecraft.gui.setScreen(back);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if ((event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) && !rows.isEmpty()) {
			pick(rows.getFirst()[0]);
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

	private int maxScroll() {
		return Math.max(0, rows.size() * ROW_H - (listY1 - listY0));
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		scroll = Math.max(0, Math.min(maxScroll(), scroll - sy * ROW_H * 3));
		return true;
	}

	private int rowAt(double mx, double my) {
		if (mx < listX0 || mx > listX1 || my < listY0 || my >= listY1) return -1;
		int i = (int) ((my - listY0 + scroll) / ROW_H);
		return i >= 0 && i < rows.size() ? i : -1;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) return true;
		int i = rowAt(event.x(), event.y());
		if (event.button() == 0 && i >= 0) {
			pick(rows.get(i)[0]);
			return true;
		}
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		g.centeredText(font, "Add an item to compare", width / 2, 10, 0xFFFFFFFF);
		g.fill(listX0 - 2, listY0 - 2, listX1 + 2, listY1 + 2, BzGraphClient.get().config().background(0x000000, 0.9));
		if (rows.isEmpty()) {
			String msg = BzGraphClient.get().products() == null ? "Loading bazaar items…" : "No matching bazaar item";
			g.centeredText(font, msg, width / 2, (listY0 + listY1) / 2, 0xFF888888);
		}
		int hovered = rowAt(mouseX, mouseY);
		g.enableScissor(listX0, listY0, listX1, listY1);
		for (int i = (int) (scroll / ROW_H); i < rows.size(); i++) {
			int y = listY0 + i * ROW_H - (int) scroll;
			if (y > listY1) break;
			if (i == hovered) g.fill(listX0, y, listX1, y + ROW_H, 0x30FFFFFF);
			String[] r = rows.get(i);
			g.text(font, font.plainSubstrByWidth(r[1], (listX1 - listX0) / 2), listX0 + 4, y + 2, i == 0 ? 0xFFFFFF77 : 0xFFFFFFFF);
			String id = font.plainSubstrByWidth(r[0], (listX1 - listX0) / 2 - 10);
			g.text(font, id, listX1 - 4 - font.width(id), y + 2, 0xFF777777);
		}
		g.disableScissor();
		super.extractRenderState(g, mouseX, mouseY, partialTick);
	}
}
