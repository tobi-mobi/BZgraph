package dev.bzgraph.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FormatAndMappingTest {
	@Test
	void compactCoins() {
		assertEquals("0", CoinFormat.compact(0));
		assertEquals("3.45", CoinFormat.compact(3.449));
		assertEquals("950", CoinFormat.compact(950));
		assertEquals("1.2k", CoinFormat.compact(1234));
		assertEquals("12.3k", CoinFormat.compact(12_345));
		assertEquals("123k", CoinFormat.compact(123_456));
		assertEquals("1M", CoinFormat.compact(999_999));
		assertEquals("3.4M", CoinFormat.compact(3_400_000));
		assertEquals("1.5B", CoinFormat.compact(1_500_000_000));
		assertEquals("-1.2k", CoinFormat.compact(-1200));
		assertEquals("1,299.3", CoinFormat.full(1299.3));
	}

	@Test
	void enchantedBookMapsToEnchantmentProduct() {
		List<String> c = ItemIdMapper.candidates("ENCHANTED_BOOK", Map.of("ultimate_wise", 5), null);
		assertEquals("ENCHANTMENT_ULTIMATE_WISE_5", c.getFirst());
		assertEquals("ENCHANTMENT_ULTIMATE_WISE_5", ItemIdMapper.pick(c, Set.of("ENCHANTMENT_ULTIMATE_WISE_5")));
		// multi-enchant books are not a bazaar product
		assertNull(ItemIdMapper.pick(ItemIdMapper.candidates("ENCHANTED_BOOK", Map.of("a", 1, "b", 2), null), Set.of("ENCHANTMENT_A_1")));
	}

	@Test
	void plainIdsAndNameFallbacks() {
		Set<String> products = Set.of("ENCHANTED_DIAMOND", "ESSENCE_WITHER", "ENCHANTMENT_SHARPNESS_6");
		assertEquals("ENCHANTED_DIAMOND", ItemIdMapper.pick(ItemIdMapper.candidates("ENCHANTED_DIAMOND", null, null), products));
		assertEquals("ESSENCE_WITHER", ItemIdMapper.pick(ItemIdMapper.candidates("ESSENCE_WITHER", null, null), products));
		assertEquals("ENCHANTED_DIAMOND", ItemIdMapper.pick(ItemIdMapper.candidates(null, null, "§aEnchanted Diamond"), products));
		assertEquals("ESSENCE_WITHER", ItemIdMapper.pick(ItemIdMapper.candidates(null, null, "§dWither Essence"), products));
		assertEquals("ENCHANTMENT_SHARPNESS_6", ItemIdMapper.pick(ItemIdMapper.candidates(null, null, "Sharpness VI"), products));
		assertNull(ItemIdMapper.pick(ItemIdMapper.candidates("HYPERION", null, "Heroic Hyperion ✪✪✪✪✪"), products));
	}

	@Test
	void downsamplerBoundsColumns() {
		long[] t = new long[10_000];
		double[] v = new double[10_000];
		for (int i = 0; i < t.length; i++) {
			t[i] = i;
			v[i] = i == 5000 ? 1e9 : i;
		}
		Downsampler.Columns c = Downsampler.columns(t, v, 0, 9999, 100);
		assertEquals(100, c.width());
		int total = 0;
		double max = 0;
		for (int i = 0; i < 100; i++) {
			total += c.count()[i];
			if (c.count()[i] > 0) max = Math.max(max, c.max()[i]);
		}
		assertEquals(10_000, total);
		assertEquals(1e9, max, "spike preserved");
	}
}
