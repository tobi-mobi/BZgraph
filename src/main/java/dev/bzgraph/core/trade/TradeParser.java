package dev.bzgraph.core.trade;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Hypixel's bazaar chat messages, e.g.
 * <pre>
 * [Bazaar] Sold 32x  Fine Citrine Gemstone for 842,982 coins!
 * [Bazaar] Your Sell Offer for 76x Refined Mineral was filled! [Go To Orders]
 * [Bazaar] Claimed 4,017,686 coins from selling 1x  Flawless Jasper Gemstone at 4,063,399 each!
 * </pre>
 * Only messages that START with "[Bazaar]" are accepted, so other players can't fake trades by typing the
 * same text in chat (their messages start with their rank/name).
 */
public final class TradeParser {
	private static final String N = "([\\d,]+(?:\\.\\d+)?)";
	private static final String AMT = "([\\d,]+)x";
	private static final Pattern FORMATTING = Pattern.compile("§.");
	/** Repeat counters added by chat-stacking mods: " (2)", " (×3)". */
	private static final Pattern REPEAT_SUFFIX = Pattern.compile("\\s*\\((?:×|x)?\\d+\\)\\s*$");

	private record Rule(TradeEvent.Type type, Pattern pattern) {
	}

	private static final Rule[] RULES = {
			rule(TradeEvent.Type.INSTANT_SELL, "Sold " + AMT + " (.+?) for " + N + " coins!"),
			rule(TradeEvent.Type.INSTANT_BUY, "Bought " + AMT + " (.+?) for " + N + " coins!"),
			rule(TradeEvent.Type.SELL_SETUP, "Sell Offer Setup! " + AMT + " (.+?) for " + N + " coins\\."),
			rule(TradeEvent.Type.BUY_SETUP, "Buy Order Setup! " + AMT + " (.+?) for " + N + " coins\\."),
			rule(TradeEvent.Type.SELL_FILLED, "Your Sell Offer for " + AMT + " (.+?) was filled!.*"),
			rule(TradeEvent.Type.BUY_FILLED, "Your Buy Order for " + AMT + " (.+?) was filled!.*"),
			rule(TradeEvent.Type.SELL_CLAIMED, "Claimed " + N + " coins from selling " + AMT + " (.+?) at " + N + " each!"),
			rule(TradeEvent.Type.BUY_CLAIMED, "Claimed " + AMT + " (.+?) worth " + N + " coins bought for " + N + " each!"),
			rule(TradeEvent.Type.SELL_CANCELLED, "Cancelled! Refunded " + AMT + " (.+?) from cancelling Sell Offer!"),
			rule(TradeEvent.Type.BUY_CANCELLED, "Cancelled! Refunded " + N + " coins from cancelling Buy Order!"),
	};

	private TradeParser() {
	}

	private static Rule rule(TradeEvent.Type type, String regex) {
		return new Rule(type, Pattern.compile(regex));
	}

	/** Returns the parsed event, or null if this isn't a bazaar trade message. */
	public static TradeEvent parse(String message) {
		if (message == null) return null;
		String s = FORMATTING.matcher(message).replaceAll("").strip();
		if (!s.startsWith("[Bazaar] ")) return null;
		s = REPEAT_SUFFIX.matcher(s.substring("[Bazaar] ".length())).replaceFirst("").strip();
		for (Rule r : RULES) {
			Matcher m = r.pattern.matcher(s);
			if (!m.matches()) continue;
			return switch (r.type) {
				case INSTANT_SELL, INSTANT_BUY, SELL_SETUP, BUY_SETUP ->
						new TradeEvent(r.type, amount(m.group(1)), cleanName(m.group(2)), num(m.group(3)), Double.NaN);
				case SELL_FILLED, BUY_FILLED, SELL_CANCELLED ->
						new TradeEvent(r.type, amount(m.group(1)), cleanName(m.group(2)), Double.NaN, Double.NaN);
				case SELL_CLAIMED ->
						new TradeEvent(r.type, amount(m.group(2)), cleanName(m.group(3)), num(m.group(1)), num(m.group(4)));
				case BUY_CLAIMED ->
						new TradeEvent(r.type, amount(m.group(1)), cleanName(m.group(2)), num(m.group(3)), num(m.group(4)));
				case BUY_CANCELLED -> new TradeEvent(r.type, 0, null, num(m.group(1)), Double.NaN);
			};
		}
		return null;
	}

	/**
	 * Strips leading icon glyphs (gemstone symbols like ❈/❤, or private-use font characters) and extra
	 * spaces: " Fine Citrine Gemstone" -> "Fine Citrine Gemstone".
	 */
	public static String cleanName(String raw) {
		int i = 0;
		while (i < raw.length() && !Character.isLetterOrDigit(raw.codePointAt(i))) i += Character.charCount(raw.codePointAt(i));
		return raw.substring(i).strip().replaceAll("\\s+", " ");
	}

	/** Case-, spacing- and hyphen-insensitive key for matching item names ("Turbo-Carrot I" = "Turbo Carrot I"). */
	public static String nameKey(String name) {
		return cleanName(name.replace('-', ' ')).toLowerCase(Locale.ROOT);
	}

	private static long amount(String s) {
		return Long.parseLong(s.replace(",", ""));
	}

	private static double num(String s) {
		return Double.parseDouble(s.replace(",", ""));
	}
}
