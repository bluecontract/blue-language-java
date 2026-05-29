package blue.language.processor.model;

import blue.language.model.TypeBlueId;
import blue.language.processor.model.HandlerContract;

@TypeBlueId("2REa15BDY5EWq4tJsbUaBwhhTG2xSdk2ZyFL1aCpqTVF")
public class RemoveProperty extends HandlerContract {

    private String propertyKey;

    public String getPropertyKey() {
        return propertyKey;
    }

    public void setPropertyKey(String propertyKey) {
        this.propertyKey = propertyKey;
    }
}
