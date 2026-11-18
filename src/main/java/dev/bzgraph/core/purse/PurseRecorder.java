package dev.bzgraph.core.purse;

import java.time.Duration;
import java.time.Instant;

/**
 * Turns once-a-second sidebar readings into purse history. Every change of the purse counts (bazaar, auction
 * house, NPCs, mobs, bank...), but small changes (a few coins from mobs) are only written once a minute; a change
 * of at least {@link #BIG_ABS} coins or {@link #BIG_REL} of the purse is written immediately, so trades show up as
 * clean steps.
 * <p>
 * A reading must repeat once before it counts (the sidebar flickers while it's being rebuilt), and readings
 * are ignored for a few seconds after changing server/world.
 */
public final class PurseRecorder {
	static final Duration THROTTLE = Duration.ofSeconds(60);
	static final Duration SETTLE = Duration.ofSeconds(3);
	static final double BIG_ABS = 1_000;
	static final double BIG_REL = 0.001;

	private final PurseStore store;
	private double candidate = Double.NaN;
	private int seen;
	private Instant settleUntil = Instant.MIN;

	public PurseRecorder(PurseStore store) {
		this.store = store;
	}

	public PurseStore store() {
		return store;
	}

	/** Server/world change: the sidebar is about to be rebuilt. */
	public void onWorldChange(Instant now) {
		candidate = Double.NaN;
		seen = 0;
		settleUntil = now.plus(SETTLE);
	}

	/** A profile message (chat or tab list). */
	public void onProfile(String account, String profile) {
		if (profile == null) return;
		if (!profile.equals(store.lastProfile(account))) {
			candidate = Double.NaN;
			seen = 0;
		}
		store.setLastProfile(account, profile);
	}

	/**
	 * @param purse the sidebar's purse, or NaN when there is none
	 * @return true if a point was written
	 */
	public boolean onReading(String account, double purse, Instant now) {
		if (Double.isNaN(purse) || purse < 0 || now.isBefore(settleUntil)) {
			candidate = Double.NaN;
			seen = 0;
			return false;
		}
		if (purse != candidate) {
			candidate = purse;
			seen = 1;
			return false;
		}
		if (++seen < 2) return false;
		String profile = store.lastProfile(account);
		PurseStore.Point last = store.last(account, profile);
		if (last != null && Math.abs(last.coins() - purse) < 0.05) return false;
		boolean write = last == null
				|| Math.abs(purse - last.coins()) >= Math.max(BIG_ABS, Math.abs(last.coins()) * BIG_REL)
				|| now.toEpochMilli() - last.time() >= THROTTLE.toMillis();
		if (write) store.add(account, profile, now.toEpochMilli(), purse);
		return write;
	}
}
