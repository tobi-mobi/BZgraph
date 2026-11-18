package dev.bzgraph.core;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FavoritesTest {
	@Test
	void categoriesKeepTheirOrderAndItems() {
		Favorites f = new Favorites();
		f.add("A");
		f.add("B");
		f.add("C");
		assertEquals("Mining", f.addCategory("  Mining "));
		assertNull(f.addCategory("mining"), "names are unique, ignoring case");
		assertNull(f.addCategory("   "));
		f.addCategory("Farming");
		f.move("B", "Mining", 0);
		f.move("C", "Farming", 0);
		f.move("A", "Mining", 0);
		assertEquals(List.of("A", "B"), f.items("Mining"));
		assertEquals(List.of("Mining", "Farming"), f.categories());
		assertEquals(List.of("A", "B", "C"), f.all());

		f.moveCategory("Farming", -1);
		assertEquals(List.of("Farming", "Mining"), f.categories());
		assertTrue(f.renameCategory("Mining", "Dwarven"));
		assertEquals(List.of("Farming", "Dwarven"), f.categories());
		assertEquals("Dwarven", f.categoryOf("A"));

		f.deleteCategory("Dwarven");
		assertEquals(List.of("A", "B"), f.items(Favorites.NONE), "items fall back to Uncategorized");
		assertFalse(f.toggle("A"));
		assertFalse(f.contains("A"));
		assertTrue(f.toggle("A"));
		assertEquals(Favorites.NONE, f.categoryOf("A"));
	}

	@Test
	void moveWithinACategoryAndCollapse() {
		Favorites f = new Favorites();
		for (String id : List.of("A", "B", "C", "D")) f.add(id);
		f.move("D", Favorites.NONE, 1);
		assertEquals(List.of("A", "D", "B", "C"), f.items(Favorites.NONE));
		f.move("A", Favorites.NONE, 99);
		assertEquals(List.of("D", "B", "C", "A"), f.items(Favorites.NONE));
		f.addCategory("X");
		f.toggleCollapsed("X");
		assertTrue(f.isCollapsed("X"));
		f.renameCategory("X", "Y");
		assertTrue(f.isCollapsed("Y"));
	}

	@Test
	void survivesGsonRoundTrip() {
		Favorites f = new Favorites();
		f.add("A");
		f.addCategory("Keys");
		f.add("K");
		f.move("K", "Keys", 0);
		Gson gson = new Gson();
		Favorites back = gson.fromJson(gson.toJson(f), Favorites.class);
		assertEquals(List.of("Keys"), back.categories());
		assertEquals(List.of("K"), back.items("Keys"));
		assertEquals(List.of("A"), back.items(Favorites.NONE));
		assertEquals(0, gson.fromJson("{}", Favorites.class).size(), "missing fields are fine");
	}
}
