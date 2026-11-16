package dev.bzgraph.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Set;
import java.util.TreeSet;

/** Parses the product ID list from Hypixel's public {@code /v2/skyblock/bazaar} endpoint. */
public final class BazaarProducts {
	private BazaarProducts() {
	}

	public static Set<String> parseProductIds(String json) {
		JsonElement root = JsonParser.parseString(json);
		if (!root.isJsonObject()) throw new ApiException(ApiException.Kind.BAD_RESPONSE, "Bazaar response is not an object");
		JsonObject o = root.getAsJsonObject();
		JsonElement products = o.get("products");
		if (products == null || !products.isJsonObject()) {
			throw new ApiException(ApiException.Kind.BAD_RESPONSE, "Bazaar response has no products");
		}
		return new TreeSet<>(products.getAsJsonObject().keySet());
	}
}
