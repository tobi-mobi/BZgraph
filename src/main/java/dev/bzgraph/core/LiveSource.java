package dev.bzgraph.core;

import java.util.concurrent.CompletableFuture;

/** Something that can fetch the current (live) bazaar price of one product. */
@FunctionalInterface
public interface LiveSource {
	CompletableFuture<PricePoint> snapshot(String itemId);
}
