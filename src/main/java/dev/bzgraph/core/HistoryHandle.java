package dev.bzgraph.core;

/**
 * Live view of a history load, safe to poll from the render thread every frame.
 * Data may be present (cached) while a background refresh is still running.
 */
public final class HistoryHandle {
	public enum Status {
		/** Nothing to show yet, request in flight. */
		LOADING,
		/** Showing cached data, background refresh in flight. */
		REFRESHING,
		READY,
		/** Got HTTP 429, waiting for Retry-After before retrying. */
		RATE_LIMITED,
		OFFLINE,
		NOT_FOUND,
		ERROR
	}

	private final String itemId;
	private final TimeRange range;
	private volatile CachedHistory data;
	private volatile Status status = Status.LOADING;
	private volatile String message = "";

	public HistoryHandle(String itemId, TimeRange range) {
		this.itemId = itemId;
		this.range = range;
	}

	public String itemId() {
		return itemId;
	}

	public TimeRange range() {
		return range;
	}

	public CachedHistory data() {
		return data;
	}

	public Status status() {
		return status;
	}

	public String message() {
		return message;
	}

	public boolean isBusy() {
		Status s = status;
		return s == Status.LOADING || s == Status.REFRESHING || s == Status.RATE_LIMITED;
	}

	void setData(CachedHistory data) {
		this.data = data;
	}

	void set(Status status, String message) {
		this.message = message == null ? "" : message;
		this.status = status;
	}

	void startRequest() {
		set(data == null ? Status.LOADING : Status.REFRESHING, "");
	}

	void rateLimited(long seconds) {
		set(Status.RATE_LIMITED, "Rate limited, retrying in " + seconds + "s");
	}
}
