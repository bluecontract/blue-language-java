package blue.language.processor.model;

import blue.language.model.TypeBlueId;
import blue.language.processor.model.HandlerContract;

@TypeBlueId("SetPropertyOnEvent")
public class SetPropertyOnEvent extends HandlerContract {

    private String expectedKind;
    private String propertyKey;
    private int propertyValue;

    public String getExpectedKind() {
        return expectedKind;
    }

    public SetPropertyOnEvent expectedKind(String expectedKind) {
        this.expectedKind = expectedKind;
        return this;
    }

    public String getPropertyKey() {
        return propertyKey;
    }

    public SetPropertyOnEvent propertyKey(String propertyKey) {
        this.propertyKey = propertyKey;
        return this;
    }

    public int getPropertyValue() {
        return propertyValue;
    }

    public SetPropertyOnEvent propertyValue(int propertyValue) {
        this.propertyValue = propertyValue;
        return this;
    }
}
