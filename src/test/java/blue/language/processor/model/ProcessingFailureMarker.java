package blue.language.processor.model;

import blue.language.model.TypeBlueId;

@TypeBlueId("33kfH8pfk7F1P5zMsuK1Jm3GcSdmTXoFHKjP16DesEco")
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
