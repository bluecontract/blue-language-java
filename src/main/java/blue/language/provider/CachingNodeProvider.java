package blue.language.provider;

import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.utils.NodeToMapListOrValue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static blue.language.utils.Properties.OBJECT_BLUE_ID;
import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;

/**
 * Size-bounded least-recently-used acceleration cache for provider outcomes.
 *
 * <p>Found values are retained through {@link NodeProviderResult}, which
 * defensively copies nodes on both insertion and access. A definitive miss may
 * be cached, but transient unavailability and invalid evidence are never
 * cached and therefore can never be rewritten as absence.</p>
 */
public final class CachingNodeProvider implements NodeProvider {

    private static final long OUTCOME_ENTRY_WEIGHT_BYTES = 32L;

    private final NodeProvider delegate;
    private final long maxSizeBytes;
    private final Object cacheLock = new Object();
    private final LinkedHashMap<String, CacheEntry> cache =
            new LinkedHashMap<String, CacheEntry>(16, 0.75f, true);
    private long currentSizeBytes;

    /**
     * Creates a cache with the requested approximate maximum retained size.
     *
     * @param delegate backing provider
     * @param maxSizeBytes non-negative approximate retained-size bound
     */
    public CachingNodeProvider(NodeProvider delegate, long maxSizeBytes) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        if (maxSizeBytes < 0L) {
            throw new IllegalArgumentException(
                    "maxSizeBytes must be non-negative");
        }
        this.maxSizeBytes = maxSizeBytes;
    }

    @Override
    public List<Node> fetchByBlueId(String blueId) {
        NodeProviderResult result = fetchResultByBlueId(blueId);
        return result.outcome() == NodeProviderOutcome.FOUND
                ? result.nodes()
                : null;
    }

    @Override
    public NodeProviderResult fetchResultByBlueId(String blueId) {
        Objects.requireNonNull(blueId, OBJECT_BLUE_ID);
        synchronized (cacheLock) {
            CacheEntry cached = cache.get(blueId);
            if (cached != null) {
                return cached.result;
            }
        }

        NodeProviderResult result = Objects.requireNonNull(
                delegate.fetchResultByBlueId(blueId),
                "delegate provider result");
        if (result.outcome() == NodeProviderOutcome.FOUND
                || result.outcome() == NodeProviderOutcome.NOT_FOUND) {
            cache(blueId, result);
        }
        return result;
    }

    private void cache(String blueId, NodeProviderResult result) {
        long weight = estimateWeight(result);
        if (weight > maxSizeBytes) {
            return;
        }
        synchronized (cacheLock) {
            CacheEntry replaced = cache.remove(blueId);
            if (replaced != null) {
                currentSizeBytes -= replaced.weightBytes;
            }
            while (currentSizeBytes + weight > maxSizeBytes
                    && !cache.isEmpty()) {
                Map.Entry<String, CacheEntry> oldest =
                        cache.entrySet().iterator().next();
                cache.remove(oldest.getKey());
                currentSizeBytes -= oldest.getValue().weightBytes;
            }
            cache.put(blueId, new CacheEntry(result, weight));
            currentSizeBytes += weight;
        }
    }

    private long estimateWeight(NodeProviderResult result) {
        long weight = OUTCOME_ENTRY_WEIGHT_BYTES;
        for (Node node : result.nodes()) {
            weight += YAML_MAPPER.writeValueAsString(
                    NodeToMapListOrValue.get(node)).length();
        }
        return weight;
    }

    /** Returns the current approximate retained size. */
    public long getCurrentSize() {
        synchronized (cacheLock) {
            return currentSizeBytes;
        }
    }

    /** Returns the current cache entry count. */
    public int getCacheSize() {
        synchronized (cacheLock) {
            return cache.size();
        }
    }

    /** One immutable cached conclusion and its precomputed retained weight. */
    private static final class CacheEntry {
        private final NodeProviderResult result;
        private final long weightBytes;

        private CacheEntry(NodeProviderResult result, long weightBytes) {
            this.result = result;
            this.weightBytes = weightBytes;
        }
    }
}
