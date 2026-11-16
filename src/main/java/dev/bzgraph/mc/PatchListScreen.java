package dev.bzgraph.mc;

import dev.bzgraph.BzGraphClient;
import dev.bzgraph.core.PatchIndexRepository;
import dev.bzgraph.core.PatchNote;
import dev.bzgraph.core.PricePoint;
import dev.bzgraph.core.TimeRange;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.time.ZoneId;
import java.util.List;

/** Searchable list of patch notes, optionally filtered to one bazaar item or a preset selection. */
public final class PatchListScreen extends Screen {
	private static final int ROW_H = 24;

	private final Screen parent;
	/** Fixed selection (e.g. a cluster of graph markers); null = whole index. */
	private final List<PatchNote> preset;
	private final String productId;
	private final List<PricePoint> history;
	private final TimeRange range;

	private EditBox search;
	private Button itemFilter;
	private boolean onlyThisItem;
	private List<PatchNote> rows = List.of();
	private double scroll;
	private int listX0, listX1, listY0, listY1;
	private Object shownIndex;

	public PatchListScreen(Screen parent, List<PatchNote> preset, String productId, List<PricePoint> history, TimeRange range) {
		super(Component.literal("Patch notes"));
		this.parent = parent;
		this.preset = preset;
		this.productId = productId;
		this.history = history;
		this.range = range;
		this.onlyThisItem = productId != null && preset == null;
	}

	@Override
	protected void init() {
		listX0 = Math.max(10, width / 2 - 220);
		listX1 = Math.min(width - 10, width / 2 + 220);
		int filterW = productId != null && preset == null ? 110 : 0;
		String prev = search == null ? "" : search.getValue();
		search = addRenderableWidget(new EditBox(font, listX0, 26, listX1 - listX0 - (filterW > 0 ? filterW + 4 : 0), 20,
				Component.literal("Search")));
		search.setHint(Component.literal("Search title, summary or item ID…"));
		search.setMaxLength(64);
		search.setValue(prev);
		search.setResponder(s -> rebuild());
		if (filterW > 0) {
			itemFilter = addRenderableWidget(Button.builder(Component.empty(), b -> {
				onlyThisItem = !onlyThisItem;
				updateFilterLabel();
				rebuild();
			}).bounds(listX1 - filterW, 26, filterW, 20).build());
			updateFilterLabel();
		}
		listY0 = 52;
		listY1 = height - 48;

		int by = height - 28;
		addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> BzGraphClient.get().refreshPatches(true))
				.bounds(width / 2 - 154, by, 150, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
				.bounds(width / 2 + 4, by, 150, 20).build());
		setInitialFocus(search);
		rebuild();
	}

	private void updateFilterLabel() {
		itemFilter.setMessage(Component.literal(onlyThisItem ? "This item only" : "All patches"));
	}

	private void rebuild() {
		var idx = BzGraphClient.get().patches().current();
		shownIndex = idx;
		String q = search == null ? "" : search.getValue();
		if (preset != null) {
			rows = preset.stream().filter(p -> q.isBlank() || idx.search(q, null).contains(p)).toList().reversed();
		} else {
			rows = idx.search(q, onlyThisItem ? productId : null);
		}
		scroll = 0;
	}

	@Override
	public void tick() {
		// index may finish downloading while the screen is open
		if (preset == null && shownIndex != BzGraphClient.get().patches().current()) rebuild();
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
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
		scroll = Math.max(0, Math.min(maxScroll(), scroll - sy * ROW_H));
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
			minecraft.gui.setScreen(new PatchDetailScreen(this, rows.get(i), productId, history, range));
			return true;
		}
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		String heading = preset != null ? "Patch notes at this point (" + rows.size() + ")"
				: "SkyBlock patch notes (" + rows.size() + ")";
		g.centeredText(font, heading, width / 2, 10, 0xFFFFFFFF);
		g.fill(listX0 - 2, listY0 - 2, listX1 + 2, listY1 + 2, 0xA0000000);

		if (rows.isEmpty()) {
			String msg = onlyThisItem ? "No patch notes mention " + productId : "No patch notes found";
			g.centeredText(font, msg, (listX0 + listX1) / 2, (listY0 + listY1) / 2, 0xFF888888);
		}

		int hovered = rowAt(mouseX, mouseY);
		g.enableScissor(listX0, listY0, listX1, listY1);
		int first = (int) (scroll / ROW_H);
		for (int i = first; i < rows.size(); i++) {
			int y = listY0 + i * ROW_H - (int) scroll;
			if (y > listY1) break;
			PatchNote p = rows.get(i);
			if (i == hovered) g.fill(listX0, y, listX1, y + ROW_H - 1, 0x30FFFFFF);
			boolean relevant = productId != null && p.affects(productId);
			String date = PatchDetailScreen.DATE.format(p.date().atZone(ZoneId.systemDefault()));
			int dateW = font.width(date);
			g.text(font, date, listX0 + 4, y + 3, 0xFFAAAAAA);
			int tx = listX0 + 4 + Math.max(dateW, 72) + 6;
			String title = font.plainSubstrByWidth(p.title(), listX1 - tx - 6);
			g.text(font, title, tx, y + 3, relevant ? 0xFFFF77FF : 0xFFFFFFFF);
			// With an item in context, prefer what changed for that item over the general summary.
			String note = p.noteFor(productId);
			String sub = note != null ? note : p.summary().isEmpty() ? "(no summary yet)" : p.summary();
			String items = note != null || p.items().isEmpty() ? "" : "  · " + p.items().size() + " items";
			g.text(font, font.plainSubstrByWidth(sub, listX1 - tx - 6 - font.width(items)) + items, tx, y + 13,
					note != null ? 0xFFFFBBFF : p.summary().isEmpty() ? 0xFF666666 : 0xFF999999);
		}
		g.disableScissor();

		if (maxScroll() > 0) {
			int trackH = listY1 - listY0;
			int barH = Math.max(12, trackH * trackH / (rows.size() * ROW_H));
			int barY = listY0 + (int) ((trackH - barH) * (scroll / maxScroll()));
			g.fill(listX1 - 3, barY, listX1, barY + barH, 0x80FFFFFF);
		}

		PatchIndexRepository repo = BzGraphClient.get().patches();
		String foot = "Index: " + repo.source() + (repo.problem() != null ? " - " + repo.problem() : "");
		g.text(font, font.plainSubstrByWidth(foot, width - 20), 10, height - 42, repo.problem() != null ? 0xFFFFAA55 : 0xFF666666);
		super.extractRenderState(g, mouseX, mouseY, partialTick);
	}
}
