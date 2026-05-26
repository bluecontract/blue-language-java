package blue.language.processor.model;

import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import blue.language.processor.registry.RuntimeBlueIds;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@TypeBlueId(RuntimeBlueIds.CHANNEL_EVENT_CHECKPOINT)
public class ChannelEventCheckpoint extends MarkerContract {

    private Map<String, Node> lastEvents = new LinkedHashMap<>();

    public Map<String, Node> getLastEvents() {
        return Collections.unmodifiableMap(lastEvents);
    }

    public ChannelEventCheckpoint lastEvents(Map<String, Node> lastEvents) {
        this.lastEvents = new LinkedHashMap<>();
        if (lastEvents != null) {
            for (Map.Entry<String, Node> entry : lastEvents.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    this.lastEvents.put(entry.getKey(), entry.getValue().clone());
                }
            }
        }
        return this;
    }

    public Node lastEvent(String channelKey) {
        Node node = lastEvents.get(channelKey);
        return node != null ? node.clone() : null;
    }

    public ChannelEventCheckpoint putEvent(String channelKey, Node event) {
        if (channelKey != null) {
            lastEvents.put(channelKey, event != null ? event.clone() : null);
        }
        return this;
    }

    public ChannelEventCheckpoint updateEvent(String channelKey, Node event) {
        return putEvent(channelKey, event);
    }
}
