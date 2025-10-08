package blue.language.processor.model;

import blue.language.model.Node;
import blue.language.model.TypeBlueId;

@TypeBlueId("ProcessingTerminatedMarker")
public class ProcessingTerminatedMarker extends MarkerContract {

    private String cause;
    private String reason;

    public String getCause() {
        return cause;
    }

    public void setCause(String cause) {
        this.cause = cause;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public ProcessingTerminatedMarker cause(String cause) {
        this.cause = cause;
        return this;
    }

    public ProcessingTerminatedMarker reason(String reason) {
        this.reason = reason;
        return this;
    }

    public Node toNode() {
        Node node = new Node()
                .type(new Node().blueId("ProcessingTerminatedMarker"))
                .properties("cause", new Node().value(cause));
        if (reason != null) {
            node.properties("reason", new Node().value(reason));
        }
        return node;
    }
}
