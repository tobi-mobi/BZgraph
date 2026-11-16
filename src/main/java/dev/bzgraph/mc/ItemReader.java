package dev.bzgraph.mc;

import dev.bzgraph.core.ItemIdMapper;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads SkyBlock item data from an ItemStack and maps it to a bazaar product ID. */
public final class ItemReader {
	/** Outcome of resolving a stack; {@code productId} is null when it isn't a bazaar item. */
	public record Result(String productId, String displayName, String skyblockId) {
	}

	private ItemReader() {
	}

	/**
	 * @param products known bazaar product IDs, or null if the list isn't loaded yet (then the
	 *                 first candidate is used optimistically and the API decides)
	 */
	public static Result resolve(ItemStack stack, Set<String> products) {
		String name = stack.getHoverName().getString();
		CompoundTag tag = customData(stack);
		String id = null;
		Map<String, Integer> enchants = null;
		if (tag != null) {
			// Modern location is the root of minecraft:custom_data; older dumps nested it in ExtraAttributes.
			CompoundTag attrs = tag.contains("id") ? tag : tag.getCompoundOrEmpty("ExtraAttributes");
			id = attrs.getString("id").orElse(null);
			CompoundTag ench = attrs.getCompoundOrEmpty("enchantments");
			if (!ench.isEmpty()) {
				enchants = new LinkedHashMap<>();
				for (String k : ench.keySet()) enchants.put(k, ench.getIntOr(k, 0));
			}
		}
		List<String> candidates = ItemIdMapper.candidates(id, enchants, name);
		String product;
		if (products != null) {
			product = ItemIdMapper.pick(candidates, products);
		} else {
			// Without the product list we only trust the real item id, not name guesses.
			product = id == null ? null : candidates.getFirst();
		}
		return new Result(product, name, id);
	}

	private static CompoundTag customData(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data == null || data.isEmpty() ? null : data.copyTag();
	}
}
