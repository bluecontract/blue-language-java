package blue.language.conformance.contracts;

import blue.language.processor.model.Contract;

/**
 * Fixture-only recognized contract role used to prove typed same-scope
 * Channel lookup without granting Channel or executable capabilities.
 */
final class FixtureNonChannelContract {

    private FixtureNonChannelContract() {
    }

    /** Public reflection carrier hidden behind this package-private holder. */
    public static final class Value extends Contract {

    private String subscriptionKey;
    private String id;

    /** Creates an empty fixture contract for mapper population. */
    public Value() {
    }

    /**
     * Returns the fixture subscription key.
     *
     * @return configured subscription key, or {@code null}
     */
    public String getSubscriptionKey() {
        return subscriptionKey;
    }

    /**
     * Sets the fixture subscription key.
     *
     * @param subscriptionKey subscription key, or {@code null}
     */
    public void setSubscriptionKey(String subscriptionKey) {
        this.subscriptionKey = subscriptionKey;
    }

    /**
     * Returns the fixture identifier.
     *
     * @return configured identifier, or {@code null}
     */
    public String getId() {
        return id;
    }

    /**
     * Sets the fixture identifier.
     *
     * @param id identifier, or {@code null}
     */
    public void setId(String id) {
        this.id = id;
    }
    }
}
