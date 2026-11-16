package dev.bzgraph.core;

/** Classified failure from the remote APIs. */
public final class ApiException extends RuntimeException {
	public enum Kind {
		OFFLINE, RATE_LIMITED, NOT_FOUND, HTTP_ERROR, BAD_RESPONSE
	}

	private final Kind kind;

	public ApiException(Kind kind, String message, Throwable cause) {
		super(message, cause);
		this.kind = kind;
	}

	public ApiException(Kind kind, String message) {
		this(kind, message, null);
	}

	public Kind kind() {
		return kind;
	}

	/** Unwraps CompletionException chains to find an ApiException, or null. */
	public static ApiException find(Throwable t) {
		for (Throwable c = t; c != null; c = c.getCause()) {
			if (c instanceof ApiException a) return a;
			if (c.getCause() == c) break;
		}
		return null;
	}
}
