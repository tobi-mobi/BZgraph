package dev.bzgraph.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Time-axis ticks at round, evenly spaced moments (every 3 hours, every 4 days, every month...) instead of
 * evenly spaced pixels, so labels never jump by an odd amount.
 */
public final class TimeTicks {
	public record Tick(long time, String label) {
	}

	private enum Unit { MINUTE, HOUR, DAY, MONTH, YEAR }

	private record Step(Unit unit, int n, long approxMs) {
	}

	private static final long MIN = 60_000L, HOUR = 60 * MIN, DAY = 24 * HOUR;
	private static final List<Step> STEPS = List.of(
			new Step(Unit.MINUTE, 1, MIN), new Step(Unit.MINUTE, 2, 2 * MIN), new Step(Unit.MINUTE, 5, 5 * MIN),
			new Step(Unit.MINUTE, 10, 10 * MIN), new Step(Unit.MINUTE, 15, 15 * MIN), new Step(Unit.MINUTE, 30, 30 * MIN),
			new Step(Unit.HOUR, 1, HOUR), new Step(Unit.HOUR, 2, 2 * HOUR), new Step(Unit.HOUR, 3, 3 * HOUR),
			new Step(Unit.HOUR, 4, 4 * HOUR), new Step(Unit.HOUR, 6, 6 * HOUR), new Step(Unit.HOUR, 12, 12 * HOUR),
			new Step(Unit.DAY, 1, DAY), new Step(Unit.DAY, 2, 2 * DAY), new Step(Unit.DAY, 4, 4 * DAY),
			new Step(Unit.DAY, 7, 7 * DAY), new Step(Unit.DAY, 14, 14 * DAY),
			new Step(Unit.MONTH, 1, 30 * DAY), new Step(Unit.MONTH, 2, 61 * DAY), new Step(Unit.MONTH, 3, 91 * DAY),
			new Step(Unit.MONTH, 6, 182 * DAY),
			new Step(Unit.YEAR, 1, 365 * DAY), new Step(Unit.YEAR, 2, 730 * DAY), new Step(Unit.YEAR, 5, 1826 * DAY),
			new Step(Unit.YEAR, 10, 3652 * DAY));

	private static final DateTimeFormatter FMT_TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
	private static final DateTimeFormatter FMT_DAY = DateTimeFormatter.ofPattern("MMM d", Locale.ROOT);
	private static final DateTimeFormatter FMT_MONTH = DateTimeFormatter.ofPattern("MMM", Locale.ROOT);
	private static final DateTimeFormatter FMT_YEAR = DateTimeFormatter.ofPattern("yyyy", Locale.ROOT);
	/** 1970-01-05 was a Monday: day steps count from there, so 7-day ticks land on Mondays. */
	private static final long MONDAY = 4;

	private TimeTicks() {
	}

	/** At most {@code maxTicks} ticks within [tMin, tMax], using the smallest round step that fits. */
	public static List<Tick> ticks(long tMin, long tMax, int maxTicks, ZoneId zone) {
		long span = Math.max(1, tMax - tMin);
		Step step = STEPS.getLast();
		for (Step s : STEPS) {
			if (span / s.approxMs() <= Math.max(1, maxTicks)) {
				step = s;
				break;
			}
		}
		List<Tick> out = new ArrayList<>();
		ZonedDateTime t = first(Instant.ofEpochMilli(tMin).atZone(zone), step);
		for (int guard = 0; guard < 500; guard++) {
			long ms = t.toInstant().toEpochMilli();
			if (ms > tMax) break;
			if (ms >= tMin) out.add(new Tick(ms, label(t, step)));
			t = next(t, step);
		}
		return out;
	}

	private static ZonedDateTime first(ZonedDateTime z, Step s) {
		ZoneId zone = z.getZone();
		return switch (s.unit()) {
			case MINUTE -> {
				ZonedDateTime m = z.truncatedTo(ChronoUnit.MINUTES);
				yield m.withMinute(m.getMinute() / s.n() * s.n());
			}
			case HOUR -> {
				ZonedDateTime h = z.truncatedTo(ChronoUnit.HOURS);
				yield h.withHour(h.getHour() / s.n() * s.n());
			}
			case DAY -> {
				long d = z.toLocalDate().toEpochDay();
				long aligned = d - Math.floorMod(d - MONDAY, s.n());
				yield LocalDate.ofEpochDay(aligned).atStartOfDay(zone);
			}
			case MONTH -> {
				LocalDate d = z.toLocalDate().withDayOfMonth(1);
				yield d.withMonth((d.getMonthValue() - 1) / s.n() * s.n() + 1).atStartOfDay(zone);
			}
			case YEAR -> {
				int y = Math.floorDiv(z.getYear(), s.n()) * s.n();
				yield LocalDateTime.of(LocalDate.of(y, 1, 1), LocalTime.MIDNIGHT).atZone(zone);
			}
		};
	}

	private static ZonedDateTime next(ZonedDateTime z, Step s) {
		return switch (s.unit()) {
			case MINUTE -> z.plusMinutes(s.n());
			case HOUR -> z.plusHours(s.n());
			case DAY -> z.toLocalDate().plusDays(s.n()).atStartOfDay(z.getZone());
			case MONTH -> z.toLocalDate().plusMonths(s.n()).atStartOfDay(z.getZone());
			case YEAR -> z.toLocalDate().plusYears(s.n()).atStartOfDay(z.getZone());
		};
	}

	private static String label(ZonedDateTime z, Step s) {
		boolean midnight = z.getHour() == 0 && z.getMinute() == 0;
		return switch (s.unit()) {
			case MINUTE, HOUR -> midnight ? FMT_DAY.format(z) : FMT_TIME.format(z);
			case DAY -> FMT_DAY.format(z);
			case MONTH -> z.getMonthValue() == 1 ? FMT_YEAR.format(z) : FMT_MONTH.format(z);
			case YEAR -> FMT_YEAR.format(z);
		};
	}
}
