package blue.language.processor.model;

import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import blue.language.processor.registry.RuntimeBlueIds;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Processor-owned checkpoint marker keyed by raw external-channel key.
 *
 * <p>The map structure is copied on input and output. Entry values are mutable
 * {@link CheckpointEntry} instances and are shared by those shallow copies.</p>
 */
@TypeBlueId(RuntimeBlueIds.CHANNEL_EVENT_CHECKPOINT)
public class ChannelEventCheckpoint extends MarkerContract {

    private Map<String, CheckpointEntry> entries = new LinkedHashMap<>();

    /** Creates an empty checkpoint marker. */
    public ChannelEventCheckpoint() {
    }

    /**
     * Returns an immutable snapshot of the current checkpoint entries.
     *
     * <p>The returned map cannot be structurally modified, but its entry
     * values are the mutable values retained by this marker.</p>
     *
     * @return immutable shallow copy in deterministic insertion order
     */
    public Map<String, CheckpointEntry> getEntries() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    /**
     * Replaces all checkpoint entries with a defensive copy.
     *
     * <p>The map structure is copied; entry values are retained by reference.</p>
     *
     * @param entries replacement entries, or {@code null} to clear the marker
     * @return this marker
     */
    public ChannelEventCheckpoint entries(Map<String, CheckpointEntry> entries) {
        this.entries = new LinkedHashMap<>();
        if (entries != null) {
            this.entries.putAll(entries);
        }
        return this;
    }

    /**
     * Returns the entry for {@code rawChannelKey}, or {@code null}.
     *
     * @param rawChannelKey exact external-channel key
     * @return retained mutable entry, or {@code null} when no entry exists
     */
    public CheckpointEntry entry(String rawChannelKey) {
        return entries.get(rawChannelKey);
    }

    /**
     * Stores a validated domain/subject pair for one raw channel key.
     *
     * @param rawChannelKey non-empty external-channel key
     * @param domainBlueId non-empty exact checkpoint-domain BlueId
     * @param subjectBlueId non-empty exact checkpoint-subject BlueId
     * @return this marker
     * @throws IllegalArgumentException if any supplied key or BlueId is
     *         {@code null} or empty
     */
    public ChannelEventCheckpoint putEntry(String rawChannelKey,
                                           String domainBlueId,
                                           String subjectBlueId) {
        if (rawChannelKey == null || rawChannelKey.isEmpty()) {
            throw new IllegalArgumentException("Raw channel key must not be empty");
        }
        if (domainBlueId == null || domainBlueId.isEmpty()
                || subjectBlueId == null || subjectBlueId.isEmpty()) {
            throw new IllegalArgumentException(
                    "Checkpoint domain and subject BlueIds must not be empty");
        }
        entries.put(rawChannelKey, new CheckpointEntry()
                .domain(new Node().blueId(domainBlueId))
                .subject(new Node().blueId(subjectBlueId)));
        return this;
    }

    /**
     * Removes the checkpoint for {@code rawChannelKey}.
     *
     * @param rawChannelKey external-channel key to remove; a missing key is a
     *        no-op
     * @return this marker
     */
    public ChannelEventCheckpoint removeEntry(String rawChannelKey) {
        entries.remove(rawChannelKey);
        return this;
    }

}
