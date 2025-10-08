package blue.language.processor.model;

import blue.language.model.TypeBlueId;
import blue.language.processor.model.HandlerContract;

@TypeBlueId("IncrementProperty")
public class IncrementProperty extends HandlerContract {

    private String propertyKey;

    public String getPropertyKey() {
        return propertyKey;
    }

    public void setPropertyKey(String propertyKey) {
        this.propertyKey = propertyKey;
    }
}
