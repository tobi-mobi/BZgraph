package dev.bzgraph.core;

import dev.bzgraph.mc.GraphNav;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GraphNavTest {
	@Test
	void backForwardAndBranching() {
		GraphNav nav = new GraphNav(null);
		assertNull(nav.current());
		assertFalse(nav.canBack());

		nav.push("A", null);
		nav.push("B", null);
		nav.push("C", null);
		assertEquals("C", nav.current().productId());
		assertTrue(nav.canBack());
		assertFalse(nav.canForward());

		assertEquals("B", nav.back().productId());
		assertEquals("A", nav.back().productId());
		assertEquals("A", nav.back().productId(), "stays at the start");
		assertEquals("B", nav.forward().productId());

		nav.push("D", null); // visiting a new item from B drops C
		assertFalse(nav.canForward());
		assertEquals(3, nav.size());
		assertEquals("B", nav.back().productId());

		nav.forward();
		nav.push("D", null); // re-visiting the current item is a no-op
		assertEquals(3, nav.size());
	}

	@Test
	void historyIsCapped() {
		GraphNav nav = new GraphNav(null);
		for (int i = 0; i < 80; i++) nav.push("I" + i, null);
		assertEquals(50, nav.size());
		assertEquals("I79", nav.current().productId());
	}
}
