package blue.language.processor.model;

import blue.language.processor.model.HandlerContract;
import blue.language.model.TypeBlueId;

@TypeBlueId("AssertDocumentUpdate")
public class AssertDocumentUpdate extends HandlerContract {

    private String expectedPath;
    private String expectedOp;
    private Integer expectedBeforeValue;
    private boolean expectBeforeNull;
    private Integer expectedAfterValue;
    private boolean expectAfterNull;

    public String getExpectedPath() {
        return expectedPath;
    }

    public void setExpectedPath(String expectedPath) {
        this.expectedPath = expectedPath;
    }

    public String getExpectedOp() {
        return expectedOp;
    }

    public void setExpectedOp(String expectedOp) {
        this.expectedOp = expectedOp;
    }

    public Integer getExpectedBeforeValue() {
        return expectedBeforeValue;
    }

    public void setExpectedBeforeValue(Integer expectedBeforeValue) {
        this.expectedBeforeValue = expectedBeforeValue;
    }

    public boolean isExpectBeforeNull() {
        return expectBeforeNull;
    }

    public void setExpectBeforeNull(boolean expectBeforeNull) {
        this.expectBeforeNull = expectBeforeNull;
    }

    public Integer getExpectedAfterValue() {
        return expectedAfterValue;
    }

    public void setExpectedAfterValue(Integer expectedAfterValue) {
        this.expectedAfterValue = expectedAfterValue;
    }

    public boolean isExpectAfterNull() {
        return expectAfterNull;
    }

    public void setExpectAfterNull(boolean expectAfterNull) {
        this.expectAfterNull = expectAfterNull;
    }
}
