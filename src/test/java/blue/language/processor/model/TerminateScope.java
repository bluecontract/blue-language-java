package blue.language.processor.model;

import blue.language.model.TypeBlueId;
import blue.language.processor.model.HandlerContract;

@TypeBlueId("TerminateScope")
public class TerminateScope extends HandlerContract {

    private String mode;
    private String reason;
    private boolean emitAfter;
    private boolean patchAfter;

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public boolean isEmitAfter() {
        return emitAfter;
    }

    public void setEmitAfter(boolean emitAfter) {
        this.emitAfter = emitAfter;
    }

    public boolean isPatchAfter() {
        return patchAfter;
    }

    public void setPatchAfter(boolean patchAfter) {
        this.patchAfter = patchAfter;
    }
}
