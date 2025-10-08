package blue.language.processor.model;

import blue.language.model.Node;

/**
 * Base contract describing deterministic logic bound to a channel.
 */
public abstract class HandlerContract extends Contract {

    private String channel;
    private Node event;

    public String getChannelKey() {
        return channel;
    }

    public void setChannelKey(String channelKey) {
        this.channel = channelKey;
    }

    public HandlerContract channelKey(String channelKey) {
        this.channel = channelKey;
        return this;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public HandlerContract channel(String channel) {
        this.channel = channel;
        return this;
    }

    public Node getEvent() {
        return event;
    }

    public void setEvent(Node event) {
        this.event = event;
    }

    public HandlerContract event(Node event) {
        this.event = event;
        return this;
    }
}
