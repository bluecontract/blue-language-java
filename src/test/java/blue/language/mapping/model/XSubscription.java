package blue.language.mapping.model;

import blue.language.model.TypeBlueId;

@TypeBlueId("XSubscription-BlueId")
public class XSubscription extends X {
    private Integer subscriptionId;

    public Integer getSubscriptionId() {
        return subscriptionId;
    }

    public XSubscription subscriptionId(Integer subscriptionId) {
        this.subscriptionId = subscriptionId;
        return this;
    }
}