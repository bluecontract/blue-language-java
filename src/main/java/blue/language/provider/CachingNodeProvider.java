package blue.language.provider;

import blue.language.model.Node;
import blue.language.NodeProvider;
import blue.language.utils.NodeToMapListOrValue;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;

/**
 * Size-bounded compatibility cache in front of another {@link NodeProvider}.
 *
 * <p>The byte bound is an approximate serialized-character count. Cached lists
 * are returned directly, so this class is an acceleration adapter rather than
 * an immutable evidence store; verification must occur at the consuming
 * boundary.</p>
 */
public class CachingNodeProvider implements NodeProvider {
    private final NodeProvider delegate;
    private final Map<String, List<Node>> cache;
    private final Queue<String> accessOrder;
    private final AtomicLong currentSize;
    private final long maxSizeBytes;

    /**
     * Creates a cache with the requested approximate maximum retained size.
     *
     * @param delegate backing provider
     * @param maxSizeBytes approximate maximum serialized retained size
     */
    public CachingNodeProvider(NodeProvider delegate, long maxSizeBytes) {
        this.delegate = delegate;
        this.cache = new ConcurrentHashMap<>();
        this.accessOrder = new LinkedList<>();
        this.currentSize = new AtomicLong(0);
        this.maxSizeBytes = maxSizeBytes;
    }

    @Override
    public List<Node> fetchByBlueId(String blueId) {
        List<Node> cachedNodes = cache.get(blueId);
        if (cachedNodes != null) {
            updateAccessOrder(blueId);
            return cachedNodes;
        }

        List<Node> nodes = delegate.fetchByBlueId(blueId);
        if (nodes != null) {
            cacheNodes(blueId, nodes);
        }
        return nodes;
    }

    private void updateAccessOrder(String blueId) {
        synchronized (accessOrder) {
            accessOrder.remove(blueId);
            accessOrder.offer(blueId);
        }
    }

    private void cacheNodes(String blueId, List<Node> nodes) {
        long nodeSize = estimateSize(nodes);
        while (currentSize.get() + nodeSize > maxSizeBytes && !accessOrder.isEmpty()) {
            removeOldestEntry();
        }

        if (currentSize.get() + nodeSize <= maxSizeBytes) {
            cache.put(blueId, nodes);
            currentSize.addAndGet(nodeSize);
            synchronized (accessOrder) {
                accessOrder.offer(blueId);
            }
        }
    }

    private void removeOldestEntry() {
        String oldestBlueId;
        synchronized (accessOrder) {
            oldestBlueId = accessOrder.poll();
        }
        if (oldestBlueId != null) {
            List<Node> removedNodes = cache.remove(oldestBlueId);
            if (removedNodes != null) {
                currentSize.addAndGet(-estimateSize(removedNodes));
            }
        }
    }

    private long estimateSize(List<Node> nodes) {
        return nodes.stream().mapToLong(node -> YAML_MAPPER.writeValueAsString(NodeToMapListOrValue.get(node)).length()).sum();
    }

    /**
     * Returns the current approximate retained size.
     *
     * @return approximate serialized size in bytes
     */
    public long getCurrentSize() {
        return currentSize.get();
    }

    /**
     * Returns the current cache entry count.
     *
     * @return number of cached identities
     */
    public int getCacheSize() {
        return cache.size();
    }

}
