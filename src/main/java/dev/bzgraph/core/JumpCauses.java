package dev.bzgraph.core;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Possible explanations for a price jump. Only plausible ones are offered:
 * <ul>
 *   <li>patch notes from shortly before the jump that mention the item, plus big updates/release candidates
 *       (which shift where players spend their time); small bug-fix posts that don't mention it are left out;</li>
 *   <li>a new mayor or minister whose perks touch this kind of item (Cole for mining items, Finnegan for farming,
 *       ...), or market-wide mayors like Derpy. A mayor/minister who was already in office doesn't count.</li>
 * </ul>
 * These are timing matches, not proof.
 */
public final class JumpCauses {
	static final Duration PATCH_BEFORE = Duration.ofDays(5);
	static final Duration PATCH_AFTER = Duration.ofDays(1);
	static final Duration MAYOR_BEFORE = Duration.ofDays(3);
	static final Duration MAYOR_AFTER = Duration.ofDays(1);
	static final int MAX_PATCHES = 4;
	static final int MAX_UNTAGGED = 2;
	private static final Pattern MAJOR = Pattern.compile("\\b0\\.\\d+|release candidate|\\bupdate\\b", Pattern.CASE_INSENSITIVE);

	/**
	 * A term that started near the jump with something relevant changing.
	 *
	 * @param mayorRelevant    the new mayor's perks touch this item (and the mayor changed)
	 * @param ministerRelevant the new minister's perk touches this item (and the minister changed)
	 */
	public record MayorChange(MayorHistory.Term term, boolean mayorRelevant, boolean ministerRelevant) {
	}

	/**
	 * @param mayorAtTime the term in office at the jump, only if its mayor or minister is relevant to the item
	 */
	public record Explanation(List<PatchNote> patches, MayorChange change, MayorHistory.Term mayorAtTime) {
		public boolean isEmpty() {
			return patches.isEmpty() && change == null;
		}
	}

	private JumpCauses() {
	}

	static boolean isMajor(PatchNote p) {
		return MAJOR.matcher(p.title()).find();
	}

	public static Explanation explain(Instant jumpTime, String productId, Set<ItemAreas.Area> areas,
									  PatchIndex patches, MayorHistory mayors) {
		List<PatchNote> near = new ArrayList<>(patches.between(jumpTime.minus(PATCH_BEFORE), jumpTime.plus(PATCH_AFTER)));
		near.sort(Comparator.comparing((PatchNote p) -> !p.affects(productId))
				.thenComparingLong(p -> Math.abs(Duration.between(p.date(), jumpTime).toMillis())));
		List<PatchNote> picked = new ArrayList<>();
		int untagged = 0;
		for (PatchNote p : near) {
			if (picked.size() >= MAX_PATCHES) break;
			if (p.affects(productId)) {
				picked.add(p);
			} else if (isMajor(p) && untagged < MAX_UNTAGGED) {
				picked.add(p);
				untagged++;
			}
		}

		MayorChange change = null;
		for (MayorHistory.Term t : mayors.startedBetween(jumpTime.minus(MAYOR_BEFORE), jumpTime.plus(MAYOR_AFTER))) {
			MayorHistory.Term prev = mayors.at(t.start().minusMillis(1));
			boolean mayorChanged = prev == null || !Objects.equals(lower(prev.mayor()), lower(t.mayor()));
			boolean ministerChanged = t.minister() != null
					&& (prev == null || !Objects.equals(lower(prev.minister()), lower(t.minister())));
			boolean mayorRel = mayorChanged && ItemAreas.relevant(t.category(), areas);
			boolean ministerRel = ministerChanged && ItemAreas.relevant(t.ministerCategory(), areas);
			if (mayorRel || ministerRel) change = new MayorChange(t, mayorRel, ministerRel); // latest relevant wins
		}

		MayorHistory.Term at = mayors.at(jumpTime);
		if (at != null && !ItemAreas.relevant(at.category(), areas)
				&& (at.minister() == null || !ItemAreas.relevant(at.ministerCategory(), areas))) {
			at = null;
		}
		return new Explanation(List.copyOf(picked), change, at);
	}

	private static String lower(String s) {
		return s == null ? null : s.toLowerCase(Locale.ROOT);
	}
}
