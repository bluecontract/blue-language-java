package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.model.ChannelEventCheckpoint;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Invocation-local memo for event and stored-checkpoint identities.
 *
 * <p>Event entries use object identity because callers may hold distinct
 * authored representations with equal content. Stored entries additionally
 * bind to the exact checkpoint object and channel key; the cache is never
 * shared across processing invocations.</p>
 */
final class CheckpointIdentityCache {
    private final Blue blue;
    private final ProcessingMetricsSink metrics;
    private final IdentityHashMap<Node, String> eventIdentities = new IdentityHashMap<>();
    private final Map<StoredCheckpointKey, String> storedIdentities = new LinkedHashMap<>();

    CheckpointIdentityCache(Blue blue, ProcessingMetricsSink metrics) {
        this.blue = blue;
        this.metrics = metrics != null ? metrics : ProcessingMetricsSink.NOOP;
    }

    String identity(Node event) {
        if (event == null) {
            return null;
        }
        if (eventIdentities.containsKey(event)) {
            metrics.incrementCheckpointIdentityCacheHits();
            return eventIdentities.get(event);
        }
        metrics.incrementCheckpointIdentityCacheMisses();
        String identity = CheckpointIdentityCalculator.identity(event, blue, metrics);
        eventIdentities.put(event, identity);
        return identity;
    }

    String storedIdentity(ChannelEventCheckpoint checkpoint, String channelKey, Node event) {
        if (checkpoint == null || event == null) {
            return null;
        }
        StoredCheckpointKey key = new StoredCheckpointKey(checkpoint, channelKey);
        if (storedIdentities.containsKey(key)) {
            metrics.incrementCheckpointStoredIdentityCacheHits();
            return storedIdentities.get(key);
        }
        metrics.incrementCheckpointStoredIdentityCacheMisses();
        String identity = CheckpointIdentityCalculator.identity(event, blue, metrics);
        storedIdentities.put(key, identity);
        return identity;
    }

    void updateStoredIdentity(ChannelEventCheckpoint checkpoint, String channelKey, String identity) {
        if (checkpoint == null) {
            return;
        }
        storedIdentities.put(new StoredCheckpointKey(checkpoint, channelKey), identity);
    }

    private static final class StoredCheckpointKey {
        private final ChannelEventCheckpoint checkpoint;
        private final String channelKey;

        private StoredCheckpointKey(ChannelEventCheckpoint checkpoint, String channelKey) {
            this.checkpoint = checkpoint;
            this.channelKey = channelKey;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof StoredCheckpointKey)) {
                return false;
            }
            StoredCheckpointKey that = (StoredCheckpointKey) other;
            return checkpoint == that.checkpoint
                    && (channelKey != null ? channelKey.equals(that.channelKey) : that.channelKey == null);
        }

        @Override
        public int hashCode() {
            int result = System.identityHashCode(checkpoint);
            result = 31 * result + (channelKey != null ? channelKey.hashCode() : 0);
            return result;
        }
    }
}
