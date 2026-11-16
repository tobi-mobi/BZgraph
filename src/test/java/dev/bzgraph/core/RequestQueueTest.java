package dev.bzgraph.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class RequestQueueTest {
	/** Fake clock: sleeping advances time instantly, so spacing is checked deterministically. */
	private static final class FakeClock {
		final AtomicLong now = new AtomicLong(1_000_000);

		long get() {
			return now.get();
		}

		void sleep(long ms) {
			now.addAndGet(ms);
		}
	}

	@Test
	void enforcesOneSecondSpacingWithFakeClock() throws Exception {
		FakeClock clock = new FakeClock();
		try (RequestQueue q = new RequestQueue(1000, clock::get, clock::sleep, "test-queue")) {
			List<Long> starts = Collections.synchronizedList(new ArrayList<>());
			List<CompletableFuture<Integer>> fs = new ArrayList<>();
			for (int i = 0; i < 5; i++) {
				int n = i;
				fs.add(q.submit(() -> {
					starts.add(clock.get());
					return n;
				}));
			}
			CompletableFuture.allOf(fs.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
			assertEquals(5, starts.size());
			for (int i = 1; i < starts.size(); i++) {
				assertTrue(starts.get(i) - starts.get(i - 1) >= 1000, "gap " + i + " was " + (starts.get(i) - starts.get(i - 1)));
			}
			// results keep FIFO order
			for (int i = 0; i < 5; i++) assertEquals(i, fs.get(i).get());
		}
	}

	@Test
	void enforcesSpacingInRealTime() throws Exception {
		try (RequestQueue q = new RequestQueue(Duration.ofMillis(150), "test-queue-rt")) {
			List<Long> starts = Collections.synchronizedList(new ArrayList<>());
			List<CompletableFuture<Void>> fs = new ArrayList<>();
			for (int i = 0; i < 4; i++) {
				fs.add(q.submit(() -> {
					starts.add(System.nanoTime());
					return null;
				}));
			}
			CompletableFuture.allOf(fs.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
			// The queue guarantees spacing between task starts; the timestamps here are taken a few µs
			// into each task, so allow 1 ms of scheduling jitter (the fake-clock test checks it exactly).
			long minGap = TimeUnit.MILLISECONDS.toNanos(150) - TimeUnit.MILLISECONDS.toNanos(1);
			for (int i = 1; i < starts.size(); i++) {
				long gap = starts.get(i) - starts.get(i - 1);
				assertTrue(gap >= minGap, "gap was " + gap / 1e6 + " ms");
			}
		}
	}

	@Test
	void pauseForDelaysNextRequest() throws Exception {
		FakeClock clock = new FakeClock();
		try (RequestQueue q = new RequestQueue(1000, clock::get, clock::sleep, "test-queue-pause")) {
			long first = q.submit(clock::get).get(5, TimeUnit.SECONDS);
			q.pauseFor(Duration.ofSeconds(30)); // e.g. Retry-After: 30
			long second = q.submit(clock::get).get(5, TimeUnit.SECONDS);
			assertTrue(second - first >= 30_000, "second request started after " + (second - first) + "ms");
		}
	}

	@Test
	void failingTaskDoesNotStopQueue() throws Exception {
		FakeClock clock = new FakeClock();
		try (RequestQueue q = new RequestQueue(1000, clock::get, clock::sleep, "test-queue-fail")) {
			CompletableFuture<Object> bad = q.submit(() -> {
				throw new IllegalStateException("boom");
			});
			CompletableFuture<String> good = q.submit(() -> "ok");
			assertEquals("ok", good.get(5, TimeUnit.SECONDS));
			assertTrue(bad.isCompletedExceptionally());
		}
	}

	@Test
	void runsTasksOffCallerThread() throws Exception {
		try (RequestQueue q = new RequestQueue(Duration.ofMillis(1), "bzgraph-test-worker")) {
			String name = q.submit(() -> Thread.currentThread().getName()).get(5, TimeUnit.SECONDS);
			assertEquals("bzgraph-test-worker", name);
		}
	}
}
