package dev.bzgraph.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which part of the game a bazaar product belongs to (mining, farming, ...), so a mayor is only offered as a
 * cause for a price move when their perks plausibly touch that item. Built from Hypixel's public collections
 * list plus keyword rules for items that aren't collection items (gemstones, corpse keys, mutations, ...).
 */
public final class ItemAreas {
	public enum Area {MINING, FARMING, FORAGING, FISHING, COMBAT, DUNGEONS, SLAYER, MYTHOLOGICAL, EVENTS, RIFT}

	/** Keyword rules, checked against the product ID (first match per area wins; an item can have several areas). */
	private static final Map<Area, List<String>> KEYWORDS = Map.of(
			Area.MINING, List.of("_GEM", "GEMSTONE", "MITHRIL", "TITANIUM", "GLACITE", "UMBER", "TUNGSTEN", "HARD_STONE",
					"REFINED_", "GLOSSY", "SKELETON_KEY", "SUSPICIOUS_SCRAP", "CORLEONITE", "DIVAN", "SLUDGE", "FOSSIL",
					"WORM_MEMBRANE", "GOBLIN_EGG", "SORROW", "TREASURITE", "STARFALL", "OIL_BARREL", "PLASMA", "VOLTA",
					"ROBOTRON", "BIOFUEL", "SHATTERED_LOCKET", "FROZEN_SCUTE", "MINING", "PICKONIMBUS"),
			Area.FARMING, List.of("WHEAT", "SEEDS", "CARROT", "POTATO", "PUMPKIN", "MELON", "CACTUS", "SUGAR_CANE",
					"NETHER_STALK", "COCOA", "MUSHROOM", "COMPOST", "CROPIE", "SQUASH", "FERMENTO", "HELIANTHUS",
					"FLORAL", "ROYAL_JELLY", "DUNG", "HONEY_JAR", "PLANT_MATTER", "JELLY", "FINE_FLOUR", "BEADY_EYES",
					"CLIPPED_WINGS", "LOCUST", "WRIGGLING_LARVA", "MANTID", "SQUEAKY", "CHEESE_FUEL", "PESTERMINATOR",
					"GARDEN_CHIP", "SUNFLOWER", "MOONFLOWER", "WILD_ROSE", "ASHWREATH", "SNOOZLING", "TIMESTALK",
					"TURTLELLINI", "SHELLFRUIT", "GLASSCORN", "WITHERBLOOM", "LONELILY", "COALROOT", "CHOCOBERRY",
					"CREAMBLOOM", "DUSKBLOOM", "THORNSHADE", "CINDERSHADE", "CHLORONITE", "CHEESEBITE", "SOGGYBUD",
					"BLASTBERRY", "JELLYBEAN", "NOCTILUME", "FLESHTRAP", "THUNDERLING", "PLANTBOY", "STARTLEVINE",
					"PUFFERCLOUD", "ZOMBUD", "PHANTOMLEAF", "STOPLIGHT", "ALL_IN_ALOE", "GODSEED", "DEVOURER", "DUSTGRAIN",
					"SCOURROOT", "GLOOMGOURD", "SHADEVINE", "CHOCONUT", "VEILSHROOM", "CORNUCOPIA", "CARROT_ZEST",
					"DEEPFRIES", "FEASTFUNGUS", "AGGOURDIAN", "BOTROOT", "HAY_BALE"),
			Area.FORAGING, List.of("LOG", "_WOOD", "FIG", "MANGROVE", "HELIX", "VINESAP", "LUSHLILAC", "HONEYCOMB",
					"SEA_LUMIES", "FORESTS_FAVOR", "STRETCHING_STICKS", "MARSHROOM"),
			Area.FISHING, List.of("FISH", "LILY", "WATER_LILY", "PRISMARINE", "SPONGE", "SHARK", "MAGMAFISH",
					"BAIT", "LOTUS", "CHUM", "CLAY", "TROPHY", "LUCK_OF_THE_SEA", "FLEXBONE", "SEA_BRINE"),
			Area.DUNGEONS, List.of("ESSENCE_WITHER", "ESSENCE_UNDEAD", "ESSENCE_DRAGON", "ESSENCE_ICE", "ESSENCE_SPIDER",
					"ESSENCE_GOLD", "ESSENCE_DIAMOND", "DUNGEON", "BONZO", "SCARF", "LIVID", "NECRON", "GIANT_FRAGMENT",
					"KISMET", "WITHER_BLOOD", "MAXOR", "GOLDOR", "RECOMBOBULATOR"),
			Area.SLAYER, List.of("REVENANT", "TARANTULA", "WOLF_TOOTH", "GOLDEN_TOOTH", "NULL_", "VOODOO", "INFERNO",
					"MAGMA_CREAM", "BLAZE", "SPIDER", "ROTTEN_FLESH", "ENDER_PEARL", "SULPHUR", "FESTERING", "VAMPIR",
					"COVEN", "SUMMONING_EYE", "SOULFLOW", "DERELICT"),
			Area.MYTHOLOGICAL, List.of("GRIFFIN", "ANCIENT_CLAW", "DAEDALUS", "CHIMERA", "MINOS", "DWARF_TURTLE",
					"CROCHET_TIGER"),
			Area.EVENTS, List.of("CANDY", "ECTOPLASM", "PUMPKIN_GUTS", "SPOOKY", "GIFT", "JERRY", "WINTER",
					"NEW_YEAR", "CAKE", "CHOCOLATE", "RABBIT_FACTION", "CENTURY"),
			Area.RIFT, List.of("RIFT", "MOTES", "TIMITE", "HEMOVIBE", "BERBERIS", "CADUCOUS", "AGARICUS", "METAL_HEART")
	);

	private final Map<String, Area> collectionItems;

	private ItemAreas(Map<String, Area> collectionItems) {
		this.collectionItems = collectionItems;
	}

	/** Keyword rules only (used until the collections list is loaded). */
	public static ItemAreas keywordsOnly() {
		return new ItemAreas(Map.of());
	}

	/** From Hypixel's {@code /v2/resources/skyblock/collections} response. */
	public static ItemAreas fromCollections(String json) {
		Map<String, Area> m = new HashMap<>();
		JsonObject root = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("collections");
		for (Map.Entry<String, JsonElement> cat : root.entrySet()) {
			Area area = switch (cat.getKey()) {
				case "FARMING" -> Area.FARMING;
				case "MINING" -> Area.MINING;
				case "COMBAT" -> Area.COMBAT;
				case "FORAGING" -> Area.FORAGING;
				case "FISHING" -> Area.FISHING;
				case "RIFT" -> Area.RIFT;
				default -> null;
			};
			if (area == null) continue;
			for (String id : cat.getValue().getAsJsonObject().getAsJsonObject("items").keySet()) m.put(id, area);
		}
		return new ItemAreas(m);
	}

	/** The areas a product belongs to (empty if unknown, e.g. most enchanted books). */
	public Set<Area> of(String productId) {
		EnumSet<Area> out = EnumSet.noneOf(Area.class);
		if (productId == null) return out;
		String id = productId.toUpperCase(Locale.ROOT);
		// collection items and their enchanted forms (ENCHANTED_DIAMOND, ENCHANTED_DIAMOND_BLOCK -> DIAMOND)
		Area direct = collectionItems.get(id);
		if (direct != null) out.add(direct);
		String base = id.replaceFirst("^ENCHANTED_", "").replaceFirst("_(BLOCK|ITEM|CUBE)$", "");
		for (Map.Entry<String, Area> e : collectionItems.entrySet()) {
			String c = e.getKey().replaceFirst("_(ITEM|ORE)$", "");
			if (c.length() >= 4 && !c.contains(":") && (base.equals(c) || base.startsWith(c + "_"))) out.add(e.getValue());
		}
		if (id.startsWith("ENCHANTMENT_")) return out; // book names would match too loosely
		for (Map.Entry<Area, List<String>> e : KEYWORDS.entrySet()) {
			for (String k : e.getValue()) {
				if (id.contains(k)) {
					out.add(e.getKey());
					break;
				}
			}
		}
		return out;
	}

	/**
	 * Areas a mayor/minister type affects. {@code null} = the whole market (Derpy, Jerry); empty = nothing
	 * item-specific (Diaz, Scorpius, Barry, unknown).
	 */
	public static Set<Area> affectedBy(String mayorType) {
		if (mayorType == null) return Set.of();
		return switch (mayorType) {
			case "mining" -> EnumSet.of(Area.MINING);
			case "farming" -> EnumSet.of(Area.FARMING);
			case "fishing" -> EnumSet.of(Area.FISHING);
			case "dungeons" -> EnumSet.of(Area.DUNGEONS);
			case "slayer" -> EnumSet.of(Area.SLAYER, Area.COMBAT);
			case "pets" -> EnumSet.of(Area.MYTHOLOGICAL);
			case "events" -> EnumSet.of(Area.EVENTS);
			// Aura's perks speed up minions: raw resources of every skill
			case "aura" -> EnumSet.of(Area.MINING, Area.FARMING, Area.FORAGING, Area.FISHING, Area.COMBAT);
			case "derp", "jerry" -> null;
			default -> Set.of();
		};
	}

	/** True if a mayor/minister of this type plausibly moves the price of an item in these areas. */
	public static boolean relevant(String mayorType, Set<Area> itemAreas) {
		Set<Area> affected = affectedBy(mayorType);
		if (affected == null) return true; // market-wide
		for (Area a : affected) if (itemAreas.contains(a)) return true;
		return false;
	}
}
