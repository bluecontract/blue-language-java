package blue.language.processor.conformance;

import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import blue.language.processor.model.ChannelContract;

/**
 * Closed fixture-only external channel used by the Contracts conformance
 * harness.
 *
 * <p>Its fields describe deterministic lookup, acceptance, payload,
 * checkpoint, and logical-delivery behavior. It is registered only in the
 * fixed conformance environment and is not a host extension point.</p>
 */
@TypeBlueId(MockTypeBlueIds.MOCK_EXTERNAL_CHANNEL)
public final class MockExternalChannel extends ChannelContract {

    private String subscriptionKey;
    private String eventKey;
    private Boolean accept;
    private Node payload;
    private String checkpointDomain;
    private String dependencyMode;
    private String dependentChannelKey;
    private String handlerChannelKey;
    private String logicalDeliveryKey;
    private Boolean fallbackToSourceOnAbsentOrNonChannel;

    /** Creates an empty fixture channel for mapper population. */
    public MockExternalChannel() {
    }

    /**
     * Returns the fixture subscription key.
     *
     * @return configured key, or {@code null}
     */
    public String getSubscriptionKey() {
        return subscriptionKey;
    }

    /**
     * Sets the fixture subscription key.
     *
     * @param subscriptionKey subscription key, or {@code null}
     */
    public void setSubscriptionKey(String subscriptionKey) {
        this.subscriptionKey = subscriptionKey;
    }

    /**
     * Returns the event-derived key expected by this fixture.
     *
     * @return configured event key, or {@code null}
     */
    public String getEventKey() {
        return eventKey;
    }

    /**
     * Sets the event-derived key expected by this fixture.
     *
     * @param eventKey event key, or {@code null}
     */
    public void setEventKey(String eventKey) {
        this.eventKey = eventKey;
    }

    /**
     * Returns the explicit acceptance control.
     *
     * @return acceptance control, or {@code null} for default behavior
     */
    public Boolean getAccept() {
        return accept;
    }

    /**
     * Sets the explicit acceptance control.
     *
     * @param accept acceptance control, or {@code null}
     */
    public void setAccept(Boolean accept) {
        this.accept = accept;
    }

    /**
     * Returns the fixed fixture payload.
     *
     * @return retained mutable payload, or {@code null}
     */
    public Node getPayload() {
        return payload;
    }

    /**
     * Sets the fixed fixture payload.
     *
     * @param payload payload retained by reference, or {@code null}
     */
    public void setPayload(Node payload) {
        this.payload = payload;
    }

    /**
     * Returns the fixture checkpoint-domain control.
     *
     * @return checkpoint domain, or {@code null}
     */
    public String getCheckpointDomain() {
        return checkpointDomain;
    }

    /**
     * Sets the fixture checkpoint-domain control.
     *
     * @param checkpointDomain checkpoint domain, or {@code null}
     */
    public void setCheckpointDomain(String checkpointDomain) {
        this.checkpointDomain = checkpointDomain;
    }

    /**
     * Returns the same-scope dependency lookup mode.
     *
     * @return dependency mode, or {@code null}
     */
    public String getDependencyMode() {
        return dependencyMode;
    }

    /**
     * Sets the same-scope dependency lookup mode.
     *
     * @param dependencyMode dependency mode, or {@code null}
     */
    public void setDependencyMode(String dependencyMode) {
        this.dependencyMode = dependencyMode;
    }

    /**
     * Returns the exact dependent channel key.
     *
     * @return dependent key, or {@code null}
     */
    public String getDependentChannelKey() {
        return dependentChannelKey;
    }

    /**
     * Sets the exact dependent channel key.
     *
     * @param dependentChannelKey dependent key, or {@code null}
     */
    public void setDependentChannelKey(String dependentChannelKey) {
        this.dependentChannelKey = dependentChannelKey;
    }

    /**
     * Returns the same-scope handler channel target.
     *
     * @return handler channel key, or {@code null}
     */
    public String getHandlerChannelKey() {
        return handlerChannelKey;
    }

    /**
     * Sets the same-scope handler channel target.
     *
     * @param handlerChannelKey handler channel key, or {@code null}
     */
    public void setHandlerChannelKey(String handlerChannelKey) {
        this.handlerChannelKey = handlerChannelKey;
    }

    /**
     * Returns the run-local logical delivery identity.
     *
     * @return logical delivery key, or {@code null}
     */
    public String getLogicalDeliveryKey() {
        return logicalDeliveryKey;
    }

    /**
     * Sets the run-local logical delivery identity.
     *
     * @param logicalDeliveryKey logical delivery key, or {@code null}
     */
    public void setLogicalDeliveryKey(String logicalDeliveryKey) {
        this.logicalDeliveryKey = logicalDeliveryKey;
    }

    /**
     * Returns whether absent/non-channel dependency lookup falls back to the
     * source member.
     *
     * @return fallback control, or {@code null} for default behavior
     */
    public Boolean getFallbackToSourceOnAbsentOrNonChannel() {
        return fallbackToSourceOnAbsentOrNonChannel;
    }

    /**
     * Sets absent/non-channel source fallback behavior.
     *
     * @param fallbackToSourceOnAbsentOrNonChannel fallback control, or
     *        {@code null}
     */
    public void setFallbackToSourceOnAbsentOrNonChannel(
            Boolean fallbackToSourceOnAbsentOrNonChannel) {
        this.fallbackToSourceOnAbsentOrNonChannel =
                fallbackToSourceOnAbsentOrNonChannel;
    }
}
