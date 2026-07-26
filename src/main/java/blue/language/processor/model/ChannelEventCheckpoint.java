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

    /*
     * Read compatibility for the preview's lastEvents shape. New writes always
     * use domain-bound entries.
     */
    @Deprecated
    public Map<String, Node> getLastEvents() {
        Map<String, Node> result = new LinkedHashMap<>();
        for (Map.Entry<String, CheckpointEntry> entry : entries.entrySet()) {
            Node subject = entry.getValue() != null ? entry.getValue().getSubject() : null;
            if (subject != null) {
                result.put(entry.getKey(), subject);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    @Deprecated
    public ChannelEventCheckpoint lastEvents(Map<String, Node> lastEvents) {
        entries.clear();
        if (lastEvents != null) {
            for (Map.Entry<String, Node> entry : lastEvents.entrySet()) {
                Node subject = entry.getValue();
                if (entry.getKey() != null && subject != null) {
                    String subjectBlueId = subject.getBlueId();
                    if (subjectBlueId != null) {
                        putEntry(entry.getKey(), subjectBlueId, subjectBlueId);
                    }
                }
            }
        }
        return this;
    }

    @Deprecated
    public Node lastEvent(String channelKey) {
        CheckpointEntry entry = entries.get(channelKey);
        return entry != null ? entry.getSubject() : null;
    }

    @Deprecated
    public ChannelEventCheckpoint putEvent(String channelKey, Node event) {
        if (event == null || event.getBlueId() == null) {
            throw new IllegalArgumentException(
                    "Legacy checkpoint events must be exact references");
        }
        return putEntry(channelKey, event.getBlueId(), event.getBlueId());
    }

    @Deprecated
    public ChannelEventCheckpoint updateEvent(String channelKey, Node event) {
        return putEvent(channelKey, event);
    }
}
