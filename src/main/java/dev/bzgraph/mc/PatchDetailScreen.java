package dev.bzgraph.mc;

import dev.bzgraph.core.CoinFormat;
import dev.bzgraph.core.PatchNote;
import dev.bzgraph.core.PriceImpact;
import dev.bzgraph.core.PricePoint;
import dev.bzgraph.core.TimeRange;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;

import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Title, date, own-words summary, mentioned items and a link to the full forum post. */
public final class PatchDetailScreen extends Screen {
	static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ROOT);
	private static final int MAX_ITEMS_SHOWN = 24;

	private final Screen parent;
	private final PatchNote patch;
	/** Item whose graph we came from (for the before/after numbers), or null. */
	private final String productId;
	private final List<PricePoint> history;
	private final TimeRange range;

	private final List<ItemLink> itemLinks = new ArrayList<>();

	private record ItemLink(String id, int x, int y, int w) {
	}

	public PatchDetailScreen(Screen parent, PatchNote patch, String productId, List<PricePoint> history, TimeRange range) {
		super(Component.literal(patch.title()));
		this.parent = parent;
		this.patch = patch;
		this.productId = productId;
		this.history = history;
		this.range = range;
	}

	@Override
	protected void init() {
		int y = height - 28;
		Button open = addRenderableWidget(Button.builder(Component.literal("Open full post"),
				b -> ConfirmLinkScreen.confirmLinkNow(this, patch.url())).bounds(width / 2 - 154, y, 150, 20).build());
		open.active = patch.url() != null;
		addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose()).bounds(width / 2 + 4, y, 150, 20).build());
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == 0) {
			for (ItemLink l : itemLinks) {
				if (event.x() >= l.x && event.x() <= l.x + l.w && event.y() >= l.y - 1 && event.y() <= l.y + font.lineHeight) {
					minecraft.gui.setScreen(new GraphScreen(this, l.id, null));
					return true;
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		int left = Math.max(10, width / 2 - 200);
		int textW = Math.min(400, width - 20);
		int y = 12;
		g.fill(left - 6, 6, left + textW + 6, height - 34, 0xA0000000);

		for (FormattedCharSequence line : font.split(Component.literal(patch.title()), textW)) {
			g.text(font, line, left, y, 0xFFFFFFFF);
			y += font.lineHeight + 1;
		}
		String meta = DATE.format(patch.date().atZone(ZoneId.systemDefault()))
				+ (patch.version() != null ? "   v" + patch.version() : "");
		g.text(font, meta, left, y + 1, 0xFFAAAAAA);
		y += font.lineHeight + 8;

		String note = patch.noteFor(productId);
		if (note != null) {
			g.text(font, "What changed for " + productId + ":", left, y, 0xFFFF55FF);
			y += font.lineHeight + 2;
			for (FormattedCharSequence line : font.split(Component.literal(note), textW)) {
				g.text(font, line, left, y, 0xFFFFDDFF);
				y += font.lineHeight + 1;
			}
			y += 6;
		}

		String summary = patch.summary().isEmpty() ? "No summary yet - open the full post for details." : patch.summary();
		if (note != null) {
			g.text(font, "Overall:", left, y, 0xFFAAAAAA);
			y += font.lineHeight + 2;
		}
		for (FormattedCharSequence line : font.split(Component.literal(summary), textW)) {
			g.text(font, line, left, y, patch.summary().isEmpty() ? 0xFF888888 : 0xFFDDDDDD);
			y += font.lineHeight + 1;
		}
		y += 8;

		if (productId != null && history != null && !history.isEmpty() && range != null) {
			Duration window = PriceImpact.windowFor(range);
			PriceImpact pi = PriceImpact.of(history, patch.date(), window);
			g.text(font, Component.literal(productId + " around this patch (" + windowLabel(window) + " before vs. after):"), left, y, 0xFFFFFFFF);
			y += font.lineHeight + 2;
			g.text(font, impactLine("Buy ", 0xFFFFAA00, pi.buyBefore(), pi.buyAfter()), left + 8, y, 0xFFFFFFFF);
			y += font.lineHeight + 1;
			g.text(font, impactLine("Sell", 0xFF55FFFF, pi.sellBefore(), pi.sellAfter()), left + 8, y, 0xFFFFFFFF);
			y += font.lineHeight + 1;
			g.text(font, "Timing only - prices also move with events, mayors and the market.", left + 8, y, 0xFF777777);
			y += font.lineHeight + 8;
		}

		itemLinks.clear();
		int bottom = height - 48;
		if (!patch.itemNotes().isEmpty()) {
			// Curated: one line per affected item with what changed for it.
			g.text(font, "Affected bazaar items (click to graph):", left, y, 0xFFFFFFFF);
			y += font.lineHeight + 3;
			List<Map.Entry<String, String>> notes = patch.sortedNotes();
			int i = 0;
			for (; i < notes.size() && y + font.lineHeight <= bottom; i++) {
				String id = notes.get(i).getKey();
				boolean link = !id.endsWith("*");
				int w = font.width(id);
				boolean hover = link && mouseX >= left && mouseX <= left + w && mouseY >= y - 1 && mouseY <= y + font.lineHeight;
				g.text(font, Component.literal(id).withStyle(st -> st.withUnderlined(hover)), left, y,
						!link ? 0xFFAAAAAA : hover ? 0xFFFFFFFF : 0xFF55AAFF);
				if (link) itemLinks.add(new ItemLink(id, left, y, w));
				int nx = left + w + 6;
				String text = notes.get(i).getValue();
				String cut = font.plainSubstrByWidth(text, left + textW - nx);
				g.text(font, cut.length() < text.length() ? font.plainSubstrByWidth(text, left + textW - nx - 8) + "..." : text,
						nx, y, 0xFFBBBBBB);
				y += font.lineHeight + 2;
			}
			if (i < notes.size()) g.text(font, "+" + (notes.size() - i) + " more", left, y, 0xFF888888);
		} else {
			List<String> items = patch.items().stream().sorted().toList();
			if (!items.isEmpty()) {
				g.text(font, "Mentioned bazaar items (auto-detected, click to graph):", left, y, 0xFFFFFFFF);
				y += font.lineHeight + 3;
				int colW = Math.max(120, textW / 3);
				int cols = Math.max(1, textW / colW);
				int shown = Math.min(items.size(), MAX_ITEMS_SHOWN);
				int drawn = 0;
				for (int i = 0; i < shown; i++) {
					int x = left + (i % cols) * colW;
					int iy = y + (i / cols) * (font.lineHeight + 2);
					if (iy + font.lineHeight > bottom) break;
					String id = font.plainSubstrByWidth(items.get(i), colW - 6);
					int w = font.width(id);
					boolean hover = mouseX >= x && mouseX <= x + w && mouseY >= iy - 1 && mouseY <= iy + font.lineHeight;
					g.text(font, Component.literal(id).withStyle(st -> st.withUnderlined(hover)), x, iy, hover ? 0xFFFFFFFF : 0xFF55AAFF);
					itemLinks.add(new ItemLink(items.get(i), x, iy, w));
					drawn++;
				}
				int rows = (drawn + cols - 1) / cols;
				y += rows * (font.lineHeight + 2);
				if (items.size() > drawn) g.text(font, "+" + (items.size() - drawn) + " more", left, y + 2, 0xFF888888);
			}
		}

		g.text(font, "Source: hypixel.net forums", left, height - 46, 0xFF666666);
		super.extractRenderState(g, mouseX, mouseY, partialTick);
	}

	static Component impactLine(String label, int color, double before, double after) {
		MutableComponent c = Component.literal(label + "  ").withColor(color)
				.append(Component.literal(CoinFormat.compact(before) + " -> " + CoinFormat.compact(after)).withColor(0xFFFFFFFF));
		double pct = PriceImpact.percent(before, after);
		if (!Double.isNaN(pct)) {
			c.append(Component.literal(String.format(Locale.ROOT, "  (%+.1f%%)", pct)).withColor(pct >= 0 ? 0xFF55FF55 : 0xFFFF5555));
		}
		return c;
	}

	static String windowLabel(Duration d) {
		return d.toDays() >= 1 ? "±" + d.toDays() + "d" : "±" + d.toHours() + "h";
	}
}
