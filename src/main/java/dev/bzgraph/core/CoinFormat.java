package dev.bzgraph.core;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

public final class CoinFormat {
	private static final String[] SUFFIX = {"", "k", "M", "B", "T"};
	private static final ThreadLocal<DecimalFormat> FULL = ThreadLocal.withInitial(
			() -> new DecimalFormat("#,##0.0", DecimalFormatSymbols.getInstance(Locale.ROOT)));
	private static final ThreadLocal<DecimalFormat> ONE = ThreadLocal.withInitial(
			() -> new DecimalFormat("0.#", DecimalFormatSymbols.getInstance(Locale.ROOT)));
	private static final ThreadLocal<DecimalFormat> TWO = ThreadLocal.withInitial(
			() -> new DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.ROOT)));

	private CoinFormat() {
	}

	/** Compact form: 950, 1.2k, 3.4M, 120M, 1.5B. Small values keep up to 2 decimals. */
	public static String compact(double v) {
		if (Double.isNaN(v)) return "-";
		if (Double.isInfinite(v)) return v > 0 ? "∞" : "-∞";
		String sign = v < 0 ? "-" : "";
		double a = Math.abs(v);
		if (a < 1000) return sign + (a < 10 ? TWO.get() : ONE.get()).format(a);
		int idx = 0;
		while (a >= 999.95 && idx < SUFFIX.length - 1) {
			a /= 1000;
			idx++;
		}
		String num = a >= 100 ? String.valueOf(Math.round(a)) : ONE.get().format(a);
		return sign + num + SUFFIX[idx];
	}

	/** Full form with grouping and one decimal: 1,299.3 */
	public static String full(double v) {
		if (Double.isNaN(v)) return "-";
		return FULL.get().format(v);
	}
}
