package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.ChannelContract;
import blue.language.utils.BlueIdCalculator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Immutable, deterministic functions registered for one portable External
 * Channel runtime type.
 *
 * <p>The header functions build and validate the revision-complete
 * subscription index.  {@link #payload(ChannelContract, Node)} and
 * {@link #checkpointSubject(ChannelContract, Node, Node)} authoritatively
 * freeze the accepted delivery before initialization. Implementations must
 * depend only on the supplied effective contract snapshot and exact event.</p>
 */
public interface ExternalChannelSubscriptionFunctions<
        T extends ChannelContract> {

    /**
     * Returns the finite ordered subscription-key set for this occurrence.
     */
    List<String> channelKeys(T immutableContractSnapshot);

    /**
     * Returns the finite ordered key set carried by the exact event.
     *
     * <p>The default is the Contracts 1.0 core key vocabulary:
     * {@code subscriptionKeys: List<Text>} or singular
     * {@code subscriptionKey: Text}. A runtime type with another immutable
     * dispatch header must override this function.</p>
     */
    default List<String> eventKeys(Node exactEvent) {
        if (exactEvent == null || exactEvent.getProperties() == null) {
            return Collections.emptyList();
        }
        Node plural = exactEvent.getProperties().get(
                "subscriptionKeys");
        if (plural != null) {
            if (plural.getItems() == null) {
                throw new IllegalArgumentException(
                        "event subscriptionKeys must be a List of Text");
            }
            List<String> keys = new ArrayList<>();
            Set<String> unique = new LinkedHashSet<>();
            for (Node item : plural.getItems()) {
                Object value = item != null ? item.getValue() : null;
                if (!(value instanceof String)
                        || ((String) value).isEmpty()
                        || !unique.add((String) value)) {
                    throw new IllegalArgumentException(
                            "Event keys must be unique non-empty Text");
                }
                keys.add((String) value);
            }
            return keys;
        }
        Node singular = exactEvent.getProperties().get(
                "subscriptionKey");
        Object value = singular != null ? singular.getValue() : null;
        return value instanceof String && !((String) value).isEmpty()
                ? Collections.singletonList((String) value)
                : Collections.<String>emptyList();
    }

    /**
     * Exact immutable preselection. The default is the core finite-key
     * intersection proof.
     */
    default boolean preselects(
            T immutableContractSnapshot,
            Node exactEvent) {
        Set<String> eventKeys =
                new LinkedHashSet<>(eventKeys(exactEvent));
        for (String channelKey
                : channelKeys(immutableContractSnapshot)) {
            if (eventKeys.contains(channelKey)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Exact immutable acceptance. Runtime types with additional immutable
     * acceptance fields override this; the core form accepts every preselected
     * occurrence.
     */
    default boolean accepts(
            T immutableContractSnapshot,
            Node exactEvent) {
        return preselects(immutableContractSnapshot, exactEvent);
    }

    /**
     * Returns the exact channelized payload for an accepted occurrence.
     *
     * <p>The default preserves the exact input event. Runtime types that adapt
     * the payload must override this function; external delivery does not use
     * the legacy mutable {@link ChannelProcessor#evaluate} result.</p>
     */
    default Node payload(
            T immutableContractSnapshot,
            Node exactEvent) {
        if (exactEvent == null) {
            throw new IllegalArgumentException(
                    "External Channel payload requires an exact event");
        }
        return exactEvent.clone();
    }

    /**
     * Returns the exact checkpoint-subject node for an accepted occurrence.
     *
     * <p>The default is the Contracts 1.0 exact input-event identity retained
     * as a pure reference. A runtime type with another immutable subject or
     * newness policy must override this function.</p>
     */
    default Node checkpointSubject(
            T immutableContractSnapshot,
            Node exactEvent,
            Node exactPayload) {
        if (exactEvent == null) {
            throw new IllegalArgumentException(
                    "External Channel checkpoint subject requires an exact "
                            + "event");
        }
        return new Node().blueId(
                BlueIdCalculator.calculateBlueId(exactEvent));
    }

    /**
     * Returns the runtime-registered checkpoint-domain discriminator.  The
     * Contracts kernel combines it with the effective type and ordered Source
     * contribution identities to derive the exact checkpoint-domain BlueId.
     */
    String checkpointDomainDiscriminator(T immutableContractSnapshot);
}
