package blue.language.processor.conformance;

import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import blue.language.processor.model.ChannelContract;

@TypeBlueId(MockTypeBlueIds.MOCK_EXTERNAL_CHANNEL)
public final class MockExternalChannel extends ChannelContract {

    private String subscriptionKey;
    private String eventKey;
    private Boolean accept;
    private Node payload;
    private String checkpointDomain;

    public String getSubscriptionKey() {
        return subscriptionKey;
    }

    public void setSubscriptionKey(String subscriptionKey) {
        this.subscriptionKey = subscriptionKey;
    }

    public String getEventKey() {
        return eventKey;
    }

    public void setEventKey(String eventKey) {
        this.eventKey = eventKey;
    }

    public Boolean getAccept() {
        return accept;
    }

    public void setAccept(Boolean accept) {
        this.accept = accept;
    }

    public Node getPayload() {
        return payload;
    }

    public void setPayload(Node payload) {
        this.payload = payload;
    }

    public String getCheckpointDomain() {
        return checkpointDomain;
    }

    public void setCheckpointDomain(String checkpointDomain) {
        this.checkpointDomain = checkpointDomain;
    }
}
