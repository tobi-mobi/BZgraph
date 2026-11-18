package dev.bzgraph.core.purse;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the purse from SkyBlock's sidebar and the profile name from chat / the tab list. */
public final class PurseParser {
	private static final Pattern FORMATTING = Pattern.compile("§.");
	/** "Purse: 12,345,678 (+5)" or "Piggy: 1,234.5" (older name). */
	private static final Pattern PURSE = Pattern.compile("^(?:Purse|Piggy): ?([0-9][0-9,]*(?:\\.[0-9]+)?)");
	/** "You are playing on profile: Apple (Co-op)" (sent when you join SkyBlock or switch profiles). */
	private static final Pattern PROFILE_CHAT = Pattern.compile("^You are playing on profile: ([A-Za-z]+)");
	/** Tab list "Profile: Apple ♲" / "Profile: Apple (Co-op)". */
	private static final Pattern PROFILE_TAB = Pattern.compile("^Profile: ([A-Za-z]+)");

	private PurseParser() {
	}

	static String clean(String s) {
		// Hypixel pads sidebar lines with emoji/colour tricks; keep printable text only
		return FORMATTING.matcher(s).replaceAll("").replaceAll("[^\\x20-\\x7E]", "").strip();
	}

	/** The purse shown in the sidebar lines, or NaN if there's no purse line (not in SkyBlock, the Rift, ...). */
	public static double purse(List<String> sidebarLines) {
		for (String raw : sidebarLines) {
			Matcher m = PURSE.matcher(clean(raw));
			if (m.find()) {
				try {
					return Double.parseDouble(m.group(1).replace(",", ""));
				} catch (NumberFormatException ignored) {
					return Double.NaN;
				}
			}
		}
		return Double.NaN;
	}

	/** Profile name from a chat message, or null. */
	public static String profileFromChat(String message) {
		Matcher m = PROFILE_CHAT.matcher(clean(message));
		return m.find() ? normalize(m.group(1)) : null;
	}

	/** Profile name from tab-list entries, or null. */
	public static String profileFromTab(List<String> entries) {
		for (String e : entries) {
			Matcher m = PROFILE_TAB.matcher(clean(e));
			if (m.find()) return normalize(m.group(1));
		}
		return null;
	}

	private static String normalize(String p) {
		return p.isEmpty() ? null : Character.toUpperCase(p.charAt(0)) + p.substring(1).toLowerCase(Locale.ROOT);
	}
}
