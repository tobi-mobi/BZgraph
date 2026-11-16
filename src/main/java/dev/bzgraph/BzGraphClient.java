package dev.bzgraph;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.bzgraph.core.ApiClient;
import dev.bzgraph.core.DiskCache;
import dev.bzgraph.core.HistoryRepository;
import dev.bzgraph.core.PatchIndexRepository;
import dev.bzgraph.core.RequestQueue;
import dev.bzgraph.mc.BzConfig;
import dev.bzgraph.mc.GraphScreen;
import dev.bzgraph.mc.ItemReader;
import dev.bzgraph.mc.BzScreen;
import dev.bzgraph.mc.GraphNav;
import dev.bzgraph.mc.PatchListScreen;
import dev.bzgraph.mc.ReiCompat;
import dev.bzgraph.mc.RrvCompat;
import dev.bzgraph.mc.TradeTracker;
import dev.bzgraph.mixin.AbstractContainerScreenAccessor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BzGraphClient implements ClientModInitializer {
	public static final String MOD_ID = "bzgraph";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	public static final String COFL_URL = "https://sky.coflnet.com";

	private static BzGraphClient instance;

	private BzConfig config;
	private ApiClient api;
	private HistoryRepository repository;
	private TradeTracker trades;
	private PatchIndexRepository patches;
	private String defaultPatchIndexUrl = "";
	private final AtomicBoolean patchesStarted = new AtomicBoolean();
	private KeyMapping openKey;
	private volatile Set<String> products;
	private final AtomicBoolean productsLoading = new AtomicBoolean();
	private volatile long productsRetryAt;
	/** Screen to open on the next tick (commands run while the chat screen is still closing). */
	private Screen pendingScreen;

	public static BzGraphClient get() {
		return instance;
	}

	@Override
	public void onInitializeClient() {
		instance = this;
		Path dir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
		config = BzConfig.load(dir.resolve("config.json"));

		String version = FabricLoader.getInstance().getModContainer(MOD_ID)
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");
		RequestQueue queue = new RequestQueue(Duration.ofMillis(1100), MOD_ID + "-http");
		api = new ApiClient(queue, MOD_ID + "/" + version);
		ExecutorService io = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, MOD_ID + "-io");
			t.setDaemon(true);
			return t;
		});
		repository = new HistoryRepository(api, api, new DiskCache(dir.resolve("cache")), 64, io, Instant::now,
				t -> LOGGER.warn("BZgraph background error", t));

		// Your own bazaar trades: from chat (live) and from old log files (once per file).
		trades = new TradeTracker(dir, api, io);
		trades.start(config.importLogsOnStartup);
		// GAME_CANCELED too: other mods (e.g. chat filters) may hide bazaar messages, but they still happened.
		ClientReceiveMessageEvents.GAME.register((msg, overlay) -> onGameMessage(msg, overlay));
		ClientReceiveMessageEvents.GAME_CANCELED.register((msg, overlay) -> onGameMessage(msg, overlay));
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> trades.stop());

		defaultPatchIndexUrl = FabricLoader.getInstance().getModContainer(MOD_ID)
				.map(c -> c.getMetadata().getCustomValue("bzgraph:patch_index_url"))
				.map(v -> v.getAsString().trim()).orElse("");
		patches = new PatchIndexRepository(api::getText, dir.resolve("cache").resolve("patch_index.json"),
				BzGraphClient::readBundledPatchIndex, io, Instant::now, msg -> LOGGER.warn(msg));

		openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.bzgraph.open_graph",
				InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G,
				KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"))));

		// Reliable Recipe Viewer: graph key over an item in its item list / bookmarks / craftables.
		RrvCompat.register(openKey, stack -> {
			if (!isActive()) return false;
			openForStack(stack, Minecraft.getInstance().gui.screen());
			return true;
		});

		// Keybind inside container screens (inventory, chests, bazaar): game keybinds aren't ticked while a
		// screen is open, so intercept the key press on the screen itself.
		// Also on recipe-viewer screens (RRV's recipe view, REI's screens), where items can be hovered.
		ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
			boolean container = screen instanceof AbstractContainerScreen<?>;
			if (container || RrvCompat.isRecipeScreen(screen) || (ReiCompat.isLoaded() && !(screen instanceof BzScreen))) {
				ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> {
					if (!openKey.matches(event)) return true;
					return !tryOpenHovered(s);
				});
			}
		});

		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> dispatcher.register(
				ClientCommands.literal("bzgraph")
						.then(ClientCommands.literal("patches").executes(c -> {
							if (!isActive()) {
								c.getSource().sendError(Component.literal("BZgraph is only active on Hypixel."));
								return 0;
							}
							ensurePatches();
							pendingScreen = new PatchListScreen(null, new GraphNav(null), null, null, null, null);
							return 1;
						}))
						.then(ClientCommands.literal("trades")
									.executes(c -> {
										int n = trades.service().store().count(TradeTracker.account());
										c.getSource().sendFeedback(Component.literal("BZgraph: " + n + " recorded bazaar trades for "
												+ TradeTracker.account() + ". Import older ones with /bzgraph trades import <logs folder>"));
										return 1;
									})
									.then(ClientCommands.literal("import")
											.then(ClientCommands.argument("folder", StringArgumentType.greedyString())
													.executes(c -> importTrades(c.getSource(), StringArgumentType.getString(c, "folder"))))))
							.then(ClientCommands.argument("item", StringArgumentType.greedyString())
								.suggests((c, b) -> {
									ensureProducts();
									Set<String> p = products;
									if (p == null) return b.buildFuture();
									String rem = b.getRemaining().toUpperCase(Locale.ROOT);
									for (String id : p) if (id.startsWith(rem)) b.suggest(id);
									return b.buildFuture();
								})
								.executes(c -> runCommand(c.getSource(), StringArgumentType.getString(c, "item"))))
						.executes(c -> {
							c.getSource().sendFeedback(Component.literal("Usage: /bzgraph <ITEM_ID>  (or hover an item and press "
									+ openKey.getTranslatedKeyMessage().getString() + ")"));
							return 1;
						})));
	}

	public BzConfig config() {
		return config;
	}

	/** Bazaar product IDs, or null until loaded. */
	public Set<String> products() {
		return products;
	}

	public TradeTracker trades() {
		return trades;
	}

	private void onGameMessage(Component msg, boolean overlay) {
		if (!overlay && isActive()) trades.onMessage(msg);
	}

	private int importTrades(FabricClientCommandSource src, String folder) {
		Path dir;
		try {
			dir = Path.of(folder.strip().replaceAll("^\"|\"$", ""));
		} catch (RuntimeException e) {
			src.sendError(Component.literal("Not a valid folder path: " + folder));
			return 0;
		}
		if (!java.nio.file.Files.isDirectory(dir)) {
			src.sendError(Component.literal("Folder not found: " + dir));
			return 0;
		}
		src.sendFeedback(Component.literal("BZgraph: importing trades from " + dir + " ..."));
		trades.importFolder(dir).whenComplete((r, err) -> Minecraft.getInstance().execute(() -> {
			if (err != null) {
				src.sendError(Component.literal("Import failed: " + err.getMessage()));
			} else {
				src.sendFeedback(Component.literal("BZgraph: imported " + r.trades() + " trades from " + r.files()
						+ " log files (" + r.skippedAsLive() + " already recorded live)."));
			}
		}));
		return 1;
	}

	public HistoryRepository repository() {
		return repository;
	}

	public PatchIndexRepository patches() {
		return patches;
	}

	/** Configured override, else the URL baked in at build time (gradle.properties patch_index_url). */
	public String patchIndexUrl() {
		String u = config.patchIndexUrl == null ? "" : config.patchIndexUrl.trim();
		return u.isEmpty() ? defaultPatchIndexUrl : u;
	}

	/** Loads the cached/bundled index and downloads a new one if stale; once per session. */
	public void ensurePatches() {
		if (patchesStarted.compareAndSet(false, true)) patches.init(patchIndexUrl());
	}

	public void refreshPatches(boolean force) {
		if (!patchesStarted.get()) {
			ensurePatches();
			return;
		}
		if (force) patches.refresh(patchIndexUrl());
	}

	private static String readBundledPatchIndex() {
		try (InputStream in = BzGraphClient.class.getResourceAsStream("/assets/bzgraph/patch_index.json")) {
			return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			return null;
		}
	}

	public KeyMapping openKey() {
		return openKey;
	}

	public boolean isActive() {
		if (!config.onlyOnHypixel) return true;
		ServerData server = Minecraft.getInstance().getCurrentServer();
		return server != null && server.ip != null && server.ip.toLowerCase(Locale.ROOT).contains("hypixel.net");
	}

	private void onTick(Minecraft mc) {
		trades.tick();
		if (pendingScreen != null) {
			Screen s = pendingScreen;
			pendingScreen = null;
			mc.gui.setScreen(s);
		}
		if (mc.player == null) return;
		if (isActive()) {
			ensureProducts();
			ensurePatches();
		}
		// Keybind with no screen open: graph the held item.
		while (openKey.consumeClick()) {
			if (mc.gui.screen() == null && isActive()) {
				ItemStack held = mc.player.getMainHandItem();
				if (!held.isEmpty()) openForStack(held, null);
			}
		}
	}

	/** @return true if the key press was handled (and should be consumed). */
	private boolean tryOpenHovered(Screen screen) {
		if (!isActive()) return false;
		// Typing in a text box (anvil, creative search, REI search) must still produce the letter.
		if (screen.getFocused() instanceof EditBox box && box.canConsumeInput()) return false;
		if (ReiCompat.searchFocused()) return false;
		ItemStack stack = ReiCompat.hoveredStack(screen); // REI item list / favorites / recipe screens
		if (stack == null) stack = RrvCompat.hoveredRecipeStack(screen); // RRV recipe screen
		if (stack == null && screen instanceof AbstractContainerScreen<?>) {
			Slot slot = ((AbstractContainerScreenAccessor) screen).bzgraph$getHoveredSlot();
			if (slot != null && slot.hasItem()) stack = slot.getItem();
		}
		if (stack == null) return false;
		openForStack(stack, screen);
		return true;
	}

	private void openForStack(ItemStack stack, Screen parent) {
		ensureProducts();
		ItemReader.Result r = ItemReader.resolve(stack, products);
		Minecraft mc = Minecraft.getInstance();
		if (r.productId() == null) {
			mc.gui.setScreen(GraphScreen.notOnBazaar(parent, r.displayName()));
		} else {
			mc.gui.setScreen(new GraphScreen(parent, r.productId(), r.displayName()));
		}
	}

	private int runCommand(FabricClientCommandSource src, String raw) {
		if (!isActive()) {
			src.sendError(Component.literal("BZgraph is only active on Hypixel (set \"onlyOnHypixel\": false in config/bzgraph/config.json to change)."));
			return 0;
		}
		String id = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
		ensureProducts();
		Set<String> p = products;
		if (p != null && !p.contains(id)) {
			src.sendError(Component.literal(id + " is not a bazaar product.").withStyle(ChatFormatting.RED));
			return 0;
		}
		pendingScreen = new GraphScreen(null, id, null);
		return 1;
	}

	/** Fetches the bazaar product list once per session (retrying after failures, at most once a minute). */
	public void ensureProducts() {
		if (products != null || System.currentTimeMillis() < productsRetryAt) return;
		if (!productsLoading.compareAndSet(false, true)) return;
		api.bazaarProductIds().whenComplete((set, err) -> {
			if (err != null) {
				LOGGER.warn("Could not load bazaar product list: {}", err.toString());
				productsRetryAt = System.currentTimeMillis() + 60_000;
			} else {
				products = Set.copyOf(set);
				trades.ensureNames(products);
				LOGGER.info("Loaded {} bazaar products", set.size());
			}
			productsLoading.set(false);
		});
	}
}
