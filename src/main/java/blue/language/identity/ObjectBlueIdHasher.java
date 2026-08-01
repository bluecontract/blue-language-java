package blue.language.identity;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;

import static blue.language.utils.Properties.OBJECT_BLUE_ID;
import static blue.language.utils.Properties.OBJECT_DESCRIPTION;
import static blue.language.utils.Properties.OBJECT_NAME;
import static blue.language.utils.Properties.OBJECT_VALUE;

/** Hashes one normalized Blue object from ordered field contributions. */
public final class ObjectBlueIdHasher {

    private final Function<Object, String> hashProvider;

    /**
     * Creates an object hasher.
     *
     * @param hashProvider canonical JSON hash function
     */
    public ObjectBlueIdHasher(Function<Object, String> hashProvider) {
        this.hashProvider = Objects.requireNonNull(hashProvider, "hashProvider");
    }

    /**
     * Hashes a normalized object. Pure references return their asserted BlueId.
     *
     * @param object normalized object input
     * @param childBlueId recursive child identity function
     * @return object BlueId
     */
    public String hash(
            Map<String, Object> object,
            Function<Object, String> childBlueId) {
        Objects.requireNonNull(object, "object");
        Objects.requireNonNull(childBlueId, "childBlueId");
        if (object.size() == 1 && object.containsKey(OBJECT_BLUE_ID)) {
            return (String) object.get(OBJECT_BLUE_ID);
        }

        Map<String, Object> hashes = new TreeMap<>(String::compareTo);
        for (Map.Entry<String, Object> entry : object.entrySet()) {
            String key = entry.getKey();
            if (isLiteralIdentityField(key)) {
                hashes.put(key, entry.getValue());
            } else {
                hashes.put(
                        key,
                        Collections.singletonMap(
                                OBJECT_BLUE_ID,
                                childBlueId.apply(entry.getValue())));
            }
        }
        return hashProvider.apply(hashes);
    }

    private boolean isLiteralIdentityField(String key) {
        return OBJECT_NAME.equals(key)
                || OBJECT_VALUE.equals(key)
                || OBJECT_DESCRIPTION.equals(key);
    }
}
