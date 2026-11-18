package dev.bzgraph.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Favourite bazaar items, sorted by the player into their own categories (in their own order). Items without a
 * category live in {@link #NONE}. Stored as JSON in the config (Gson), so it keeps plain fields.
 */
public final class Favorites {
	/** The "Uncategorized" group. */
	public static final String NONE = "";
	public static final int MAX_NAME = 24;

	/** Category -> item IDs, both in display order. */
	private LinkedHashMap<String, List<String>> groups = new LinkedHashMap<>();
	private List<String> collapsed = new ArrayList<>();

	private LinkedHashMap<String, List<String>> groups() {
		if (groups == null) groups = new LinkedHashMap<>();
		if (collapsed == null) collapsed = new ArrayList<>();
		groups.computeIfAbsent(NONE, k -> new ArrayList<>());
		for (Map.Entry<String, List<String>> e : groups.entrySet()) if (e.getValue() == null) e.setValue(new ArrayList<>());
		return groups;
	}

	public int size() {
		int n = 0;
		for (List<String> l : groups().values()) n += l.size();
		return n;
	}

	public boolean contains(String id) {
		return categoryOf(id) != null;
	}

	/** The item's category ({@link #NONE} if uncategorized), or null if it's not a favourite. */
	public String categoryOf(String id) {
		for (Map.Entry<String, List<String>> e : groups().entrySet()) if (e.getValue().contains(id)) return e.getKey();
		return null;
	}

	/** The player's categories in order (without {@link #NONE}). */
	public List<String> categories() {
		List<String> out = new ArrayList<>(groups().keySet());
		out.remove(NONE);
		return out;
	}

	public List<String> items(String category) {
		List<String> l = groups().get(category);
		return l == null ? List.of() : List.copyOf(l);
	}

	/** Every favourite, category by category (uncategorized last). */
	public List<String> all() {
		List<String> out = new ArrayList<>();
		for (String c : categories()) out.addAll(items(c));
		out.addAll(items(NONE));
		return out;
	}

	/** Adds to the end of "Uncategorized" (no-op if it's already a favourite). */
	public void add(String id) {
		if (id == null || contains(id)) return;
		groups().get(NONE).add(id);
	}

	public void remove(String id) {
		for (List<String> l : groups().values()) l.remove(id);
	}

	/** Adds or removes; returns whether it is a favourite now. */
	public boolean toggle(String id) {
		if (contains(id)) {
			remove(id);
			return false;
		}
		add(id);
		return true;
	}

	/** Trimmed name, or null if it's empty / too long / taken. */
	public String validName(String name, String except) {
		if (name == null) return null;
		String n = name.strip();
		if (n.isEmpty() || n.length() > MAX_NAME) return null;
		for (String c : categories()) if (c.equalsIgnoreCase(n) && !c.equals(except)) return null;
		return n;
	}

	/** Creates an empty category at the end; returns its name, or null if the name isn't usable. */
	public String addCategory(String name) {
		String n = validName(name, null);
		if (n == null) return null;
		reorder(categoriesWith(n, categories().size()), Map.of(n, new ArrayList<>()));
		return n;
	}

	public boolean renameCategory(String from, String to) {
		String n = validName(to, from);
		if (n == null || from.equals(NONE) || !groups().containsKey(from)) return false;
		if (n.equals(from)) return true;
		List<String> order = categories();
		order.set(order.indexOf(from), n);
		List<String> items = groups().remove(from);
		boolean wasCollapsed = collapsed.remove(from);
		reorder(order, Map.of(n, items));
		if (wasCollapsed) collapsed.add(n);
		return true;
	}

	/** Deletes a category; its items move to the end of "Uncategorized". */
	public void deleteCategory(String name) {
		if (name.equals(NONE)) return;
		List<String> items = groups().remove(name);
		collapsed.remove(name);
		if (items != null) groups().get(NONE).addAll(items);
	}

	/** Moves a category up (-1) or down (+1) in the list. */
	public void moveCategory(String name, int delta) {
		List<String> order = categories();
		int i = order.indexOf(name);
		int j = i + delta;
		if (i < 0 || j < 0 || j >= order.size()) return;
		order.remove(i);
		order.add(j, name);
		reorder(order, Map.of());
	}

	/** Moves an item into {@code category} at {@code index} (clamped; counted without the item itself). */
	public void move(String id, String category, int index) {
		if (!contains(id) || !groups().containsKey(category)) return;
		remove(id);
		List<String> l = groups().get(category);
		l.add(Math.max(0, Math.min(index, l.size())), id);
	}

	public boolean isCollapsed(String category) {
		groups();
		return collapsed.contains(category);
	}

	public void toggleCollapsed(String category) {
		groups();
		if (!collapsed.remove(category)) collapsed.add(category);
	}

	private List<String> categoriesWith(String name, int at) {
		List<String> order = categories();
		order.add(at, name);
		return order;
	}

	/** Rebuilds the map in {@code order} (then NONE), taking new lists from {@code extra}. */
	private void reorder(List<String> order, Map<String, List<String>> extra) {
		LinkedHashMap<String, List<String>> old = groups();
		LinkedHashMap<String, List<String>> next = new LinkedHashMap<>();
		for (String c : order) next.put(c, extra.containsKey(c) ? extra.get(c) : old.get(c));
		next.put(NONE, old.get(NONE));
		groups = next;
	}
}
