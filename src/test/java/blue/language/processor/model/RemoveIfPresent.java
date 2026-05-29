package blue.language.processor.model;

import blue.language.model.TypeBlueId;

@TypeBlueId("72r7LSWk5VP9Wh1e5KJX2x8Mrr7Yk8d8Zey9QTbDaHBe")
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
