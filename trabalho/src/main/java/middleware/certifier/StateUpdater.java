package middleware.certifier;

public interface StateUpdater<K, V> {
    void put(String tag, K key, V values);
}
