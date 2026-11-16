package dev.bzgraph.mc;

import dev.bzgraph.BzGraphClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Field;
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
	private static final boolean LOADED = FabricLoader.getInstance().isModLoaded("rrv");
	private static final String RECIPE_SCREEN = "cc.cassian.rrv.common.recipe.inventory.RecipeViewScreen";

	// Recipe screen access (resolved lazily; RRV's recipe screen doesn't use the overlay key handlers).
	private static boolean screenInitDone;
	private static boolean screenBroken;
	private static Class<?> extendedScreen;
	private static Method hoveredStackMethod;
	private static Field workstationField;
	private static Method itemSlotHovered;
	private static Method itemSlotStack;

	private RrvCompat() {
	}

	/** True for RRV's recipe view screen (opened by clicking an item in RRV). */
	public static boolean isRecipeScreen(Screen screen) {
		return LOADED && screen != null && screen.getClass().getName().equals(RECIPE_SCREEN);
	}

	private static void initScreenAccess() throws ReflectiveOperationException {
		if (screenInitDone) return;
		screenInitDone = true;
		// RRVExtendedContainerScreen#rrv$hoveredStack(): the item in the hovered recipe slot
		extendedScreen = Class.forName("cc.cassian.rrv.client.util.RRVExtendedContainerScreen");
		hoveredStackMethod = extendedScreen.getMethod("rrv$hoveredStack");
		// The workstation icon (crafting table, furnace, ...) is a separate ItemSlot not covered above.
		workstationField = Class.forName(RECIPE_SCREEN).getDeclaredField("workstationSlot");
		workstationField.setAccessible(true);
		Class<?> itemSlot = Class.forName("cc.cassian.rrv.common.overlay.ItemSlot");
		itemSlotHovered = itemSlot.getMethod("isHovered");
		itemSlotStack = itemSlot.getMethod("getStack");
	}

	/** The item under the mouse on RRV's recipe screen, or null. */
	public static ItemStack hoveredRecipeStack(Screen screen) {
		if (!isRecipeScreen(screen) || screenBroken) return null;
		try {
			initScreenAccess();
			if (extendedScreen.isInstance(screen)
					&& hoveredStackMethod.invoke(screen) instanceof ItemStack s && !s.isEmpty()) {
				return s;
			}
			Object ws = workstationField.get(screen);
			if (ws != null && Boolean.TRUE.equals(itemSlotHovered.invoke(ws))
					&& itemSlotStack.invoke(ws) instanceof ItemStack s && !s.isEmpty()) {
				return s;
			}
			return null;
		} catch (Throwable t) {
			screenBroken = true;
			BzGraphClient.LOGGER.warn("RRV recipe-screen integration disabled (API not as expected): {}", t.toString());
			return null;
		}
	}

	/**
	 * @param onStack called with the hovered stack; returns true if it handled the key press
	 */
	public static void register(KeyMapping key, Predicate<ItemStack> onStack) {
		if (!LOADED) return;
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
