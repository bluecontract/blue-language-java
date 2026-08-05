package blue.language.processor.model;

import blue.language.model.TypeBlueId;

@TypeBlueId(ProcessorTestTypeBlueIds.PROCESSING_FAILURE_MARKER)
public class ProcessingFailureMarker extends MarkerContract {

    private String code;
    private String reason;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
