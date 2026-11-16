package dev.bzgraph.core.trade;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * Reads bazaar trade messages out of Minecraft log files ({@code logs/2026-11-16-5.log.gz}, {@code latest.log}).
 * Lines look like {@code [19:25:21] [Render thread/INFO]: [CHAT] [Bazaar] Sold 3x ...}; the date comes from the
 * file name (or the file's modification date for latest.log) and the account from the "Setting user:" line.
 */
public final class LogImporter {
	public record LogEvent(String account, Instant time, TradeEvent event) {
	}

	/** One log file, in the order it should be replayed. */
	public record LogFile(Path path, LocalDate startDate, int index) {
		public String key() {
			return path.toAbsolutePath().normalize().toString();
		}
	}

	private static final Pattern DATED = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})-(\\d+)\\.log(\\.gz)?");
	private static final Pattern LINE = Pattern.compile("^\\[(\\d{2}):(\\d{2}):(\\d{2})] \\[[^]]*]: (.*)$");
	private static final String USER = "Setting user: ";
	private static final String CHAT = "[CHAT] ";

	private LogImporter() {
	}

	/** Log files in a folder, oldest first; latest.log (if wanted) last. */
	public static List<LogFile> listFiles(Path dir, boolean includeLatest) throws IOException {
		List<LogFile> out = new ArrayList<>();
		try (Stream<Path> s = Files.list(dir)) {
			for (Path p : (Iterable<Path>) s::iterator) {
				String name = p.getFileName().toString();
				Matcher m = DATED.matcher(name);
				if (m.matches()) {
					out.add(new LogFile(p, LocalDate.parse(m.group(1)), Integer.parseInt(m.group(2))));
				} else if (includeLatest && name.equals("latest.log")) {
					out.add(new LogFile(p, null, Integer.MAX_VALUE));
				}
			}
		}
		out.sort(Comparator.comparing((LogFile f) -> f.startDate() == null ? LocalDate.MAX : f.startDate())
				.thenComparingInt(LogFile::index));
		return out;
	}

	/** Reads one file; {@code fallbackAccount} is used until a "Setting user:" line is seen. */
	public static List<LogEvent> read(LogFile f, ZoneId zone, String fallbackAccount) throws IOException {
		List<String> lines = new ArrayList<>();
		try (InputStream raw = Files.newInputStream(f.path());
			 InputStream in = f.path().toString().endsWith(".gz") ? new GZIPInputStream(raw) : raw;
			 BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String l;
			while ((l = r.readLine()) != null) lines.add(l);
		}
		LocalDate start = f.startDate();
		if (start == null) {
			// latest.log: last-modified date, minus one day per midnight crossed inside the file
			LocalDate end = Instant.ofEpochMilli(Files.getLastModifiedTime(f.path()).toMillis()).atZone(zone).toLocalDate();
			start = end.minusDays(midnights(lines));
		}
		return parseLines(lines, start, zone, fallbackAccount);
	}

	static int midnights(List<String> lines) {
		int n = 0;
		int prev = -1;
		for (String l : lines) {
			Matcher m = LINE.matcher(l);
			if (!m.matches()) continue;
			int secs = secs(m);
			if (prev >= 0 && secs < prev - 3600) n++;
			prev = secs;
		}
		return n;
	}

	static List<LogEvent> parseLines(List<String> lines, LocalDate startDate, ZoneId zone, String fallbackAccount) {
		List<LogEvent> out = new ArrayList<>();
		LocalDate day = startDate;
		int prev = -1;
		String account = fallbackAccount;
		for (String l : lines) {
			Matcher m = LINE.matcher(l);
			if (!m.matches()) continue; // continuation lines of multi-line messages / stack traces
			int secs = secs(m);
			if (prev >= 0 && secs < prev - 3600) day = day.plusDays(1); // crossed midnight
			prev = secs;
			String msg = m.group(4);
			int u = msg.indexOf(USER);
			if (u >= 0) {
				account = msg.substring(u + USER.length()).strip();
				continue;
			}
			int c = msg.indexOf(CHAT);
			if (c < 0) continue;
			TradeEvent e = TradeParser.parse(msg.substring(c + CHAT.length()));
			if (e == null) continue;
			Instant t = day.atTime(LocalTime.ofSecondOfDay(secs)).atZone(zone).toInstant();
			out.add(new LogEvent(account, t, e));
		}
		return out;
	}

	private static int secs(Matcher m) {
		return Integer.parseInt(m.group(1)) * 3600 + Integer.parseInt(m.group(2)) * 60 + Integer.parseInt(m.group(3));
	}
}
