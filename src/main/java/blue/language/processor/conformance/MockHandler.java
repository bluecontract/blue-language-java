package blue.language.processor.conformance;

import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import blue.language.processor.model.HandlerContract;

@TypeBlueId({
        MockTypeBlueIds.MOCK_HANDLER,
        MockTypeBlueIds.LEGACY_MOCK_HANDLER
})
public final class MockHandler extends HandlerContract {

    private Long gasConsumed;
    private Node patches;
    private Node triggeredEvents;
    private String termination;
    private String terminationReason;
    private String failure;
    private Boolean emitInvalidEvent;
    private String addDocumentUpdateChannelAt;
    private String documentUpdatePath;

    public Long getGasConsumed() {
        return gasConsumed;
    }

    public void setGasConsumed(Long gasConsumed) {
        this.gasConsumed = gasConsumed;
    }

    public Node getPatches() {
        return patches;
    }

    public void setPatches(Node patches) {
        this.patches = patches;
    }

    public Node getTriggeredEvents() {
        return triggeredEvents;
    }

    public void setTriggeredEvents(Node triggeredEvents) {
        this.triggeredEvents = triggeredEvents;
    }

    public String getTermination() {
        return termination;
    }

    public void setTermination(String termination) {
        this.termination = termination;
    }

    public String getTerminationReason() {
        return terminationReason;
    }

    public void setTerminationReason(String terminationReason) {
        this.terminationReason = terminationReason;
    }

    public String getFailure() {
        return failure;
    }

    public void setFailure(String failure) {
        this.failure = failure;
    }

    public Boolean getEmitInvalidEvent() {
        return emitInvalidEvent;
    }

    public void setEmitInvalidEvent(Boolean emitInvalidEvent) {
        this.emitInvalidEvent = emitInvalidEvent;
    }

    public String getAddDocumentUpdateChannelAt() {
        return addDocumentUpdateChannelAt;
    }

    public void setAddDocumentUpdateChannelAt(String addDocumentUpdateChannelAt) {
        this.addDocumentUpdateChannelAt = addDocumentUpdateChannelAt;
    }

    public String getDocumentUpdatePath() {
        return documentUpdatePath;
    }

    public void setDocumentUpdatePath(String documentUpdatePath) {
        this.documentUpdatePath = documentUpdatePath;
    }
}
