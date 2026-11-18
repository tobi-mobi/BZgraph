package dev.bzgraph.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Evens out the sample spacing of a history. SkyCofl returns coarse buckets (2 hours, a day) for older data and
 * a fine 3-minute tail for the last hours; drawn as-is, the fine tail becomes a noisy scribble at the right edge.
 * Merging samples into equal buckets gives the whole graph the same level of detail.
 */
public final class Resampler {
	/** Never split the visible span into more than this many buckets. */
	private static final int MIN_BUCKETS = 60;

	private Resampler() {
	}

	/**
	 * Bucket size for these samples: the spacing that covers most of the time span (a 12-hour 3-minute tail
	 * after 6 days of 2-hour buckets gives 2 hours, even though most samples are 3 minutes apart), but small
	 * enough that the span still has {@value MIN_BUCKETS} buckets. 0 = leave the data as it is.
	 */
	public static long bucketMs(List<PricePoint> pts) {
		int n = pts.size();
		if (n < 4) return 0;
		long[] gaps = new long[n - 1];
		for (int i = 1; i < n; i++) {
			gaps[i - 1] = pts.get(i).timestamp().toEpochMilli() - pts.get(i - 1).timestamp().toEpochMilli();
		}
		Arrays.sort(gaps);
		long span = pts.getLast().timestamp().toEpochMilli() - pts.getFirst().timestamp().toEpochMilli();
		long typical = gaps[gaps.length - 1], covered = 0;
		for (long gap : gaps) {
			covered += gap;
			if (covered * 2 >= span) {
				typical = gap;
				break;
			}
		}
		long bucket = Math.min(typical, span / MIN_BUCKETS);
		return bucket < 60_000L ? 0 : bucket;
	}

	public static List<PricePoint> resample(List<PricePoint> pts) {
		return resample(pts, bucketMs(pts));
	}

	/**
	 * One point per {@code bucketMs}-wide bucket: the average buy/sell of its samples, the lowest/highest price seen
	 * in any of them as the band, at the time of its newest sample (so the graph still ends at the newest data).
	 */
	public static List<PricePoint> resample(List<PricePoint> pts, long bucketMs) {
		if (bucketMs <= 0 || pts.size() < 2) return pts;
		List<PricePoint> out = new ArrayList<>();
		int i = 0;
		while (i < pts.size()) {
			long key = Math.floorDiv(pts.get(i).timestamp().toEpochMilli(), bucketMs);
			int j = i;
			while (j < pts.size() && Math.floorDiv(pts.get(j).timestamp().toEpochMilli(), bucketMs) == key) j++;
			out.add(j - i == 1 ? pts.get(i) : merge(pts.subList(i, j)));
			i = j;
		}
		return out;
	}

	private static PricePoint merge(List<PricePoint> ps) {
		Acc buy = new Acc(), sell = new Acc(), bVol = new Acc(), sVol = new Acc();
		double minB = Double.NaN, maxB = Double.NaN, minS = Double.NaN, maxS = Double.NaN;
		for (PricePoint p : ps) {
			buy.add(p.buy());
			sell.add(p.sell());
			bVol.add(p.buyVolume());
			sVol.add(p.sellVolume());
			minB = min(minB, Double.isNaN(p.minBuy()) ? p.buy() : p.minBuy());
			maxB = max(maxB, Double.isNaN(p.maxBuy()) ? p.buy() : p.maxBuy());
			minS = min(minS, Double.isNaN(p.minSell()) ? p.sell() : p.minSell());
			maxS = max(maxS, Double.isNaN(p.maxSell()) ? p.sell() : p.maxSell());
		}
		return new PricePoint(ps.getLast().timestamp(), buy.mean(), sell.mean(), bVol.mean(), sVol.mean(), minB, maxB, minS, maxS);
	}

	private static double min(double a, double b) {
		return Double.isNaN(a) ? b : Double.isNaN(b) ? a : Math.min(a, b);
	}

	private static double max(double a, double b) {
		return Double.isNaN(a) ? b : Double.isNaN(b) ? a : Math.max(a, b);
	}

	private static final class Acc {
		double sum;
		int n;

		void add(double v) {
			if (Double.isNaN(v)) return;
			sum += v;
			n++;
		}

		double mean() {
			return n == 0 ? Double.NaN : sum / n;
		}
	}

	/** The {@code p}-th percentile (0..1) of the non-NaN values, or NaN if there are none. */
	public static double percentile(double[] values, double p) {
		double[] v = Arrays.stream(values).filter(d -> !Double.isNaN(d)).sorted().toArray();
		if (v.length == 0) return Double.NaN;
		int idx = (int) Math.round(p * (v.length - 1));
		return v[Math.max(0, Math.min(v.length - 1, idx))];
	}
}
