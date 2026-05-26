package blue.language.processor.model;

import blue.language.model.TypeBlueId;
import blue.language.processor.model.HandlerContract;

@TypeBlueId("GsQfKqSUXxx24JTvsHDaY5pJ2cE6vZnn7j1NQ5RFDCWv")
public class IncrementProperty extends HandlerContract {

    private String propertyKey;

    public String getPropertyKey() {
        return propertyKey;
    }

    public void setPropertyKey(String propertyKey) {
        this.propertyKey = propertyKey;
    }
}
