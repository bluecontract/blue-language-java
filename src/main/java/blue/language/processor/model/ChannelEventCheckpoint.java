package blue.language.processor.model;

import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import blue.language.processor.registry.RuntimeBlueIds;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@TypeBlueId(RuntimeBlueIds.CHANNEL_EVENT_CHECKPOINT)
public class ChannelEventCheckpoint extends MarkerContract {

    private Map<String, CheckpointEntry> entries = new LinkedHashMap<>();

    public Map<String, CheckpointEntry> getEntries() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    public ChannelEventCheckpoint entries(Map<String, CheckpointEntry> entries) {
        this.entries = new LinkedHashMap<>();
        if (entries != null) {
            this.entries.putAll(entries);
        }
        return this;
    }

    public CheckpointEntry entry(String rawChannelKey) {
        return entries.get(rawChannelKey);
    }

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

    public ChannelEventCheckpoint removeEntry(String rawChannelKey) {
        entries.remove(rawChannelKey);
        return this;
    }

}
