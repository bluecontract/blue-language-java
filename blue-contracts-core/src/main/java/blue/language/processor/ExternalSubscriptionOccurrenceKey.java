package blue.language.processor;

import blue.language.model.wire.JsonPointer;
import blue.language.processor.util.PointerUtils;

import java.util.Objects;

/**
 * Stable identity of one scope-local External Channel subscription occurrence.
 *
 * <p>The owning scope must be an absolute strict Runtime Pointer. It is
 * canonicalized at construction so hosts can safely use this value for
 * equality, maps, and exact ordered candidate lists.</p>
 */
public final class ExternalSubscriptionOccurrenceKey {

    private final String scopePath;
    private final String channelKey;

    private ExternalSubscriptionOccurrenceKey(
            String scopePath,
            String channelKey) {
        this.scopePath = PointerUtils.assertValidRuntimePointer(
                Objects.requireNonNull(scopePath, "scopePath"));
        this.channelKey = requireText(
                Objects.requireNonNull(channelKey, "channelKey"),
                "channelKey");
    }

    /**
     * Creates the exact identity of one External Channel occurrence.
     *
     * @param scopePath owning absolute strict Runtime Pointer
     * @param channelKey scope-local channel key
     * @return immutable normalized occurrence key
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalArgumentException if the scope is not a strict absolute
     *         Runtime Pointer or the channel key is empty
     */
    public static ExternalSubscriptionOccurrenceKey of(
            String scopePath,
            String channelKey) {
        return new ExternalSubscriptionOccurrenceKey(
                scopePath, channelKey);
    }

    /**
     * Returns the normalized owning scope.
     *
     * @return normalized absolute scope path
     */
    public String scopePath() {
        return scopePath;
    }

    /**
     * Returns the exact scope-local channel key.
     *
     * @return non-empty channel key
     */
    public String channelKey() {
        return channelKey;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ExternalSubscriptionOccurrenceKey)) {
            return false;
        }
        ExternalSubscriptionOccurrenceKey key =
                (ExternalSubscriptionOccurrenceKey) other;
        return scopePath.equals(key.scopePath)
                && channelKey.equals(key.channelKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scopePath, channelKey);
    }

    @Override
    public String toString() {
        return JsonPointer.ROOT.equals(scopePath)
                ? scopePath + channelKey
                : scopePath + "/" + channelKey;
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(
                    label + " must be non-empty");
        }
        return value;
    }
}
