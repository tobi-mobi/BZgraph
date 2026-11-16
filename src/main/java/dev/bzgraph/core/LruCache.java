package dev.bzgraph.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** Small thread-safe LRU map. */
public final class LruCache<K, V> {
	private final LinkedHashMap<K, V> map;

	public LruCache(int capacity) {
		this.map = new LinkedHashMap<>(16, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
				return size() > capacity;
			}
		};
	}

	public synchronized V get(K key) {
		return map.get(key);
	}

	public synchronized void put(K key, V value) {
		map.put(key, value);
	}

	public synchronized int size() {
		return map.size();
	}

	public synchronized void clear() {
		map.clear();
	}
}
