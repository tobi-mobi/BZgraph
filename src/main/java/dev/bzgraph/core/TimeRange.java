package dev.bzgraph.core;

import java.time.Duration;
import java.time.Instant;

/** Selectable graph time ranges with their disk-cache TTLs. */
public enum TimeRange {
	DAY("1D", Duration.ofDays(1), Duration.ofMinutes(2)),
	WEEK("1W", Duration.ofDays(7), Duration.ofMinutes(10)),
	MONTH("1M", Duration.ofDays(30), Duration.ofHours(1)),
	YEAR("1Y", Duration.ofDays(365), Duration.ofHours(6)),
	ALL("ALL", null, Duration.ofHours(6));

	/** Earliest instant used for the ALL range. */
	public static final Instant EPOCH_START = Instant.parse("1970-01-01T00:00:00Z");

	private final String label;
	private final Duration span;
	private final Duration ttl;

	TimeRange(String label, Duration span, Duration ttl) {
		this.label = label;
		this.span = span;
		this.ttl = ttl;
	}

	public String label() {
		return label;
	}

	public Duration ttl() {
		return ttl;
	}

	/** Start of the query window for a request made at {@code now}. */
	public Instant start(Instant now) {
		return span == null ? EPOCH_START : now.minus(span);
	}

	public static TimeRange fromLabel(String label) {
		for (TimeRange r : values()) {
			if (r.label.equalsIgnoreCase(label)) return r;
		}
		return null;
	}
}
