package blue.language.provider;

/**
 * Marker for providers that resolve cyclic-set member BlueIds as part of their
 * own content-addressed ingestion model.
 */
public interface CyclicAwareNodeProvider {

    default boolean hasVerifiedContentForBlueId(String blueId) {
        return false;
    }
}
