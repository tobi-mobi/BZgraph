package dev.bzgraph.core;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.LongSupplier;

/**
 * Single FIFO queue that runs tasks one at a time on a dedicated daemon thread,
 * starting each task at least {@code minSpacing} after the previous one started.
 * {@link #pauseFor(Duration)} additionally delays the next start (used for HTTP 429 Retry-After).
 */
public final class RequestQueue implements AutoCloseable {
	/** Abstraction over Thread.sleep so tests can use a fake clock. Units match the clock's ticks. */
	@FunctionalInterface
	public interface Sleeper {
		void sleep(long ticks) throws InterruptedException;
	}

	private record Job<T>(Callable<T> task, CompletableFuture<T> future) {
		void run() {
			if (future.isDone()) return; // cancelled while queued
			try {
				future.complete(task.call());
			} catch (Throwable t) {
				future.completeExceptionally(t);
			}
		}
	}

	/** All times below are in clock ticks (nanoseconds for the real clock, milliseconds in tests). */
	private final long minSpacing;
	private final long ticksPerMilli;
	private final LongSupplier clock;
	private final Sleeper sleeper;
	private final BlockingQueue<Job<?>> jobs = new LinkedBlockingQueue<>();
	private final Thread worker;
	private final Object lock = new Object();
	private long lastStart = Long.MIN_VALUE / 2;
	private long pausedUntil = Long.MIN_VALUE / 2;
	private volatile boolean closed;

	public RequestQueue(Duration minSpacing, String threadName) {
		// Nanosecond clock: comparing truncated milliseconds could start a request up to 1 ms early.
		this(minSpacing.toNanos(), 1_000_000L, System::nanoTime, ns -> Thread.sleep(Duration.ofNanos(ns)), threadName);
	}

	/** Millisecond-based constructor, used with a fake clock in tests. */
	public RequestQueue(long minSpacingMillis, LongSupplier clockMillis, Sleeper sleeper, String threadName) {
		this(minSpacingMillis, 1L, clockMillis, sleeper, threadName);
	}

	private RequestQueue(long minSpacing, long ticksPerMilli, LongSupplier clock, Sleeper sleeper, String threadName) {
		this.minSpacing = minSpacing;
		this.ticksPerMilli = ticksPerMilli;
		this.clock = clock;
		this.sleeper = sleeper;
		this.worker = new Thread(this::loop, threadName);
		this.worker.setDaemon(true);
		this.worker.start();
	}

	public <T> CompletableFuture<T> submit(Callable<T> task) {
		CompletableFuture<T> f = new CompletableFuture<>();
		if (closed) {
			f.completeExceptionally(new IllegalStateException("queue closed"));
			return f;
		}
		jobs.add(new Job<>(task, f));
		return f;
	}

	/** Delays the start of the next request until at least {@code d} from now. */
	public void pauseFor(Duration d) {
		synchronized (lock) {
			pausedUntil = Math.max(pausedUntil, clock.getAsLong() + d.toMillis() * ticksPerMilli);
		}
	}

	public int pending() {
		return jobs.size();
	}

	private void loop() {
		while (!closed) {
			Job<?> job;
			try {
				job = jobs.take();
			} catch (InterruptedException e) {
				if (closed) return;
				continue;
			}
			try {
				waitForSlot();
			} catch (InterruptedException e) {
				job.future().completeExceptionally(e);
				if (closed) return;
				continue;
			}
			job.run();
		}
	}

	private void waitForSlot() throws InterruptedException {
		while (true) {
			long now = clock.getAsLong();
			long earliest;
			synchronized (lock) {
				earliest = Math.max(lastStart + minSpacing, pausedUntil);
				if (now >= earliest) {
					lastStart = now;
					return;
				}
			}
			sleeper.sleep(earliest - now);
		}
	}

	@Override
	public void close() {
		closed = true;
		worker.interrupt();
		Job<?> j;
		while ((j = jobs.poll()) != null) {
			j.future().completeExceptionally(new IllegalStateException("queue closed"));
		}
	}
}
