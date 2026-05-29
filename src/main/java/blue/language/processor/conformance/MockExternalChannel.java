package blue.language.processor.conformance;

import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import blue.language.processor.model.ChannelContract;

@TypeBlueId({
        MockTypeBlueIds.MOCK_EXTERNAL_CHANNEL,
        MockTypeBlueIds.LEGACY_MOCK_EXTERNAL_CHANNEL
})
public final class MockExternalChannel extends ChannelContract {

    private Boolean accept;
    private Node payload;

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
}
