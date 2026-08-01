package blue.language.processor.model;

import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.ProcessorContractConstants;

/**
 * Processor-owned marker recording the stable cause and optional reason for
 * document termination.
 */
@TypeBlueId(RuntimeBlueIds.PROCESSING_TERMINATED_MARKER)
public class ProcessingTerminatedMarker extends MarkerContract {

    private String cause;
    private String reason;

    /** Creates an empty termination marker. */
    public ProcessingTerminatedMarker() {
    }

    /**
     * Returns the stable machine-readable termination cause.
     *
     * @return termination cause, or {@code null} before it is assigned
     */
    public String getCause() {
        return cause;
    }

    /**
     * Sets the stable machine-readable termination cause.
     *
     * @param cause stable cause, or {@code null} to clear it
     */
    public void setCause(String cause) {
        this.cause = cause;
    }

    /**
     * Returns optional human-readable termination detail.
     *
     * @return termination reason, or {@code null} when absent
     */
    public String getReason() {
        return reason;
    }

    /**
     * Sets optional human-readable termination detail.
     *
     * @param reason human-readable detail, or {@code null} to clear it
     */
    public void setReason(String reason) {
        this.reason = reason;
    }

    /**
     * Sets the stable cause for fluent construction.
     *
     * @param cause stable cause, or {@code null} to clear it
     * @return this marker
     */
    public ProcessingTerminatedMarker cause(String cause) {
        this.cause = cause;
        return this;
    }

    /**
     * Sets the optional reason for fluent construction.
     *
     * @param reason human-readable detail, or {@code null} to clear it
     * @return this marker
     */
    public ProcessingTerminatedMarker reason(String reason) {
        this.reason = reason;
        return this;
    }

    /**
     * Materializes the current marker state as a Blue node.
     *
     * @return newly allocated node using the registered termination-marker
     *         type
     */
    public Node toNode() {
        Node node = new Node()
                .type(new Node().blueId(RuntimeBlueIds.PROCESSING_TERMINATED_MARKER))
                .properties(
                        ProcessorContractConstants.KEY_CAUSE,
                        new Node().value(cause));
        if (reason != null) {
            node.properties(
                    ProcessorContractConstants.KEY_REASON,
                    new Node().value(reason));
        }
        return node;
    }
}
