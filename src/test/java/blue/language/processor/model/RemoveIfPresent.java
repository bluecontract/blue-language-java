package blue.language.processor.model;

import blue.language.model.TypeBlueId;

@TypeBlueId("RemoveIfPresent")
public class RemoveIfPresent extends HandlerContract {

    private String propertyKey;

    public String getPropertyKey() {
        return propertyKey;
    }

    public RemoveIfPresent propertyKey(String propertyKey) {
        this.propertyKey = propertyKey;
        return this;
    }

    public void setPropertyKey(String propertyKey) {
        this.propertyKey = propertyKey;
    }
}
