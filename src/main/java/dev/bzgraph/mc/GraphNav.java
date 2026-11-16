package dev.bzgraph.mc;

import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Browser-style history of the items graphed in one BZgraph session, plus the screen to return to when
 * the session is closed (the inventory/chest it was opened from, or null for none). Esc on any BZgraph
 * screen returns straight to {@link #root()}; the arrows move through the item history.
 */
public final class GraphNav {
	public record Entry(String productId, String displayName) {
	}

	private static final int MAX = 50;

	private final Screen root;
	private final List<Entry> entries = new ArrayList<>();
	private int pos = -1;

	public GraphNav(Screen root) {
		this.root = root;
	}

	public Screen root() {
		return root;
	}

	public Entry current() {
		return pos < 0 ? null : entries.get(pos);
	}

	public boolean canBack() {
		return pos > 0;
	}

	public boolean canForward() {
		return pos >= 0 && pos < entries.size() - 1;
	}

	public int size() {
		return entries.size();
	}

	/** Visits an item: drops any "forward" history, ignores re-visiting the current item. */
	public void push(String productId, String displayName) {
		Entry cur = current();
		if (cur != null && Objects.equals(cur.productId(), productId)) return;
		while (entries.size() > pos + 1) entries.removeLast();
		entries.add(new Entry(productId, displayName));
		if (entries.size() > MAX) entries.removeFirst();
		pos = entries.size() - 1;
	}

	public Entry back() {
		if (canBack()) pos--;
		return current();
	}

	public Entry forward() {
		if (canForward()) pos++;
		return current();
	}
}
