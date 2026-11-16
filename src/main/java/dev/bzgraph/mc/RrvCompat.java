package dev.bzgraph.mc;

import dev.bzgraph.BzGraphClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.Predicate;

/**
 * Optional Reliable Recipe Viewer (RRV) integration. RRV lets mods register a handler that runs when a
 * given key is pressed over one of its item slots (item list, bookmarks, craftables):
 * {@code OverlayView.registerGlobalOverlayKeybindSlotHandler(KeyMapping, OverlayKeybindSlotHandler)}.
 * We register the graph key through reflection and a dynamic proxy, so RRV is not a build or runtime
 * dependency; if it's missing or its API changes, nothing happens beyond one log line.
 */
public final class RrvCompat {
	private RrvCompat() {
	}

	/**
	 * @param onStack called with the hovered stack; returns true if it handled the key press
	 */
	public static void register(KeyMapping key, Predicate<ItemStack> onStack) {
		if (!FabricLoader.getInstance().isModLoaded("rrv")) return;
		try {
			Class<?> handlerType = Class.forName("cc.cassian.rrv.api.overlay.OverlayKeybindSlotHandler");
			Class<?> overlayView = Class.forName("cc.cassian.rrv.api.overlay.OverlayView");
			Method register = overlayView.getMethod("registerGlobalOverlayKeybindSlotHandler", KeyMapping.class, handlerType);
			Method getStack = Class.forName("cc.cassian.rrv.common.overlay.ItemSlot").getMethod("getStack");

			Object handler = Proxy.newProxyInstance(RrvCompat.class.getClassLoader(), new Class<?>[]{handlerType},
					(proxy, method, args) -> switch (method.getName()) {
						// boolean onKeybindPressedOnSlot(KeyEvent, ItemSlot, Identifier)
						case "onKeybindPressedOnSlot" -> {
							try {
								Object stack = args[1] == null ? null : getStack.invoke(args[1]);
								yield stack instanceof ItemStack s && !s.isEmpty() && onStack.test(s);
							} catch (Throwable t) {
								BzGraphClient.LOGGER.warn("RRV slot handler failed: {}", t.toString());
								yield false;
							}
						}
						case "toString" -> "BZgraph RRV graph-key handler";
						case "hashCode" -> System.identityHashCode(proxy);
						case "equals" -> proxy == args[0];
						default -> null;
					});
			register.invoke(null, key, handler);
			BzGraphClient.LOGGER.info("Registered graph key with Reliable Recipe Viewer");
		} catch (Throwable t) {
			BzGraphClient.LOGGER.warn("RRV integration disabled (API not as expected): {}", t.toString());
		}
	}
}
