package dev.bzgraph.mc;

import dev.bzgraph.BzGraphClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Optional Roughly Enough Items integration, done through reflection so REI is not a build or runtime
 * dependency. Finds the item under the mouse in any REI panel (item list, favorites, recipe screens).
 * If REI is missing or its API changes, this quietly does nothing.
 */
public final class ReiCompat {
	private static final boolean LOADED = FabricLoader.getInstance().isModLoaded("roughlyenoughitems");

	private static boolean initDone;
	private static boolean broken;
	private static Method registryInstance;
	private static Method focusedStack;
	private static Constructor<?> pointCtor;
	private static boolean pointTakesDoubles;
	private static Method entryValue;
	private static Method runtimeInstance;
	private static Method searchField;
	private static Method fieldFocused;

	private ReiCompat() {
	}

	public static boolean isLoaded() {
		return LOADED && !broken;
	}

	private static void init() throws ReflectiveOperationException {
		if (initDone) return;
		initDone = true;
		Class<?> registry = Class.forName("me.shedaniel.rei.api.client.registry.screen.ScreenRegistry");
		registryInstance = registry.getMethod("getInstance");
		for (Method m : registry.getMethods()) {
			if (m.getName().equals("getFocusedStack") && m.getParameterCount() == 2) focusedStack = m;
		}
		if (focusedStack == null) throw new NoSuchMethodException("ScreenRegistry.getFocusedStack(Screen, Point)");
		// Point lives in a library REI depends on; take its class from the method signature instead of naming it.
		Class<?> point = focusedStack.getParameterTypes()[1];
		try {
			pointCtor = point.getConstructor(int.class, int.class);
		} catch (NoSuchMethodException e) {
			pointCtor = point.getConstructor(double.class, double.class);
			pointTakesDoubles = true;
		}
		entryValue = Class.forName("me.shedaniel.rei.api.common.entry.EntryStack").getMethod("getValue");

		Class<?> runtime = Class.forName("me.shedaniel.rei.api.client.REIRuntime");
		runtimeInstance = runtime.getMethod("getInstance");
		searchField = runtime.getMethod("getSearchTextField");
		fieldFocused = Class.forName("me.shedaniel.rei.api.client.gui.widgets.TextField").getMethod("isFocused");
	}

	/** The item stack under the mouse in an REI panel on {@code screen}, or null. */
	public static ItemStack hoveredStack(Screen screen) {
		if (!isLoaded()) return null;
		try {
			init();
			Minecraft mc = Minecraft.getInstance();
			double x = mc.mouseHandler.getScaledXPos(mc.getWindow());
			double y = mc.mouseHandler.getScaledYPos(mc.getWindow());
			Object p = pointTakesDoubles ? pointCtor.newInstance(x, y) : pointCtor.newInstance((int) x, (int) y);
			Object entry = focusedStack.invoke(registryInstance.invoke(null), screen, p);
			if (entry == null) return null;
			return entryValue.invoke(entry) instanceof ItemStack s && !s.isEmpty() ? s : null;
		} catch (Throwable t) {
			disable(t);
			return null;
		}
	}

	/** True while REI's search box has keyboard focus (so the graph key should type a letter instead). */
	public static boolean searchFocused() {
		if (!isLoaded()) return false;
		try {
			init();
			Object field = searchField.invoke(runtimeInstance.invoke(null));
			return field != null && Boolean.TRUE.equals(fieldFocused.invoke(field));
		} catch (Throwable t) {
			disable(t);
			return false;
		}
	}

	private static void disable(Throwable t) {
		if (!broken) BzGraphClient.LOGGER.warn("REI integration disabled (API not as expected): {}", t.toString());
		broken = true;
	}
}
