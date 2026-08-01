package blue.language.processor;

import blue.language.LanguageRuntimeAccess;
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
    private final LanguageRuntimeAccess languageRuntime;
    private final ProcessingObserver metrics;
    private final IdentityHashMap<Node, String> eventIdentities = new IdentityHashMap<>();
    private final Map<StoredCheckpointKey, String> storedIdentities = new LinkedHashMap<>();

    CheckpointIdentityCache(
            LanguageRuntimeAccess languageRuntime,
            ProcessingObserver metrics) {
        this.languageRuntime = languageRuntime;
        this.metrics = metrics != null ? metrics : NoOpProcessingObserver.INSTANCE;
    }

    String identity(Node event) {
        if (event == null) {
            return null;
        }
        if (eventIdentities.containsKey(event)) {
            ProcessingObservations.record(metrics,
                    ProcessingMetricId.CHECKPOINT_IDENTITY_CACHE_HITS, 1L);
            return eventIdentities.get(event);
        }
        ProcessingObservations.record(metrics,
                ProcessingMetricId.CHECKPOINT_IDENTITY_CACHE_MISSES, 1L);
        String identity = CheckpointIdentityCalculator.identity(
                event, languageRuntime, metrics);
        eventIdentities.put(event, identity);
        return identity;
    }

    String storedIdentity(ChannelEventCheckpoint checkpoint, String channelKey, Node event) {
        if (checkpoint == null || event == null) {
            return null;
        }
        StoredCheckpointKey key = new StoredCheckpointKey(checkpoint, channelKey);
        if (storedIdentities.containsKey(key)) {
            ProcessingObservations.record(metrics,
                    ProcessingMetricId.CHECKPOINT_STORED_IDENTITY_CACHE_HITS, 1L);
            return storedIdentities.get(key);
        }
        ProcessingObservations.record(metrics,
                ProcessingMetricId.CHECKPOINT_STORED_IDENTITY_CACHE_MISSES, 1L);
        String identity = CheckpointIdentityCalculator.identity(
                event, languageRuntime, metrics);
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
