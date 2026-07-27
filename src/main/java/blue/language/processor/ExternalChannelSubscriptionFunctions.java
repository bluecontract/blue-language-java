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
 * depend only on the supplied effective contract snapshot, immutable
 * same-scope dependency context, and exact event. Dependencies used during
 * event evaluation must be covered by those declared while deriving the
 * immutable subscription header.</p>
 */
public interface ExternalChannelSubscriptionFunctions<
        T extends ChannelContract> {

    /**
     * Returns the finite ordered subscription-key set for this occurrence.
     */
    default List<String> channelKeys(
            T immutableContractSnapshot) {
        throw new UnsupportedOperationException(
                "External Channel runtime type must implement channelKeys");
    }

    /**
     * Context-aware subscription-key derivation.
     *
     * <p>Simple runtime types inherit the context-free implementation.
     * Composite runtime types use {@code context} to consult exact immutable
     * same-scope External Channel snapshots. Every consultation is captured as
     * a deterministic subscription dependency.</p>
     */
    default List<String> channelKeys(
            T immutableContractSnapshot,
            ExternalChannelFunctionContext context) {
        return channelKeys(immutableContractSnapshot);
    }

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
     * Context-aware event-key derivation. Event-only runtime types inherit the
     * context-free implementation.
     */
    default List<String> eventKeys(
            Node exactEvent,
            ExternalChannelFunctionContext context) {
        if (exactEvent == null
                || exactEvent.getProperties() == null) {
            return eventKeys(exactEvent);
        }
        Node projectedEvent = exactEvent;
        Node plural = exactEvent.getProperties().get(
                "subscriptionKeys");
        if (plural != null) {
            Node projectedPlural = plural;
            boolean changed = false;
            if (projectedPlural.isReferenceOnly()) {
                projectedPlural =
                        context.materializeExactReference(
                                projectedPlural);
                changed = true;
            }
            if (projectedPlural.getItems() != null) {
                List<Node> projectedItems =
                        new ArrayList<>(
                                projectedPlural
                                        .getItems().size());
                boolean changedItem = false;
                for (Node item
                        : projectedPlural.getItems()) {
                    Node projectedItem = item;
                    if (projectedItem != null
                            && projectedItem
                            .isReferenceOnly()) {
                        projectedItem =
                                context
                                        .materializeExactReference(
                                                projectedItem);
                        changedItem = true;
                    }
                    projectedItems.add(
                            projectedItem != null
                                    ? projectedItem.clone()
                                    : null);
                }
                if (changedItem) {
                    projectedPlural =
                            projectedPlural.clone()
                                    .items(projectedItems);
                    changed = true;
                }
            }
            if (changed) {
                projectedEvent = exactEvent.clone();
                projectedEvent.getProperties().put(
                        "subscriptionKeys",
                        projectedPlural.clone());
            }
            return eventKeys(projectedEvent);
        }
        Node singular = exactEvent.getProperties().get(
                "subscriptionKey");
        if (singular != null
                && singular.isReferenceOnly()) {
            projectedEvent = exactEvent.clone();
            projectedEvent.getProperties().put(
                    "subscriptionKey",
                    context.materializeExactReference(
                            singular));
        }
        return eventKeys(projectedEvent);
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
     * Context-aware exact immutable preselection.
     */
    default boolean preselects(
            T immutableContractSnapshot,
            Node exactEvent,
            ExternalChannelFunctionContext context) {
        Set<String> eventKeys =
                new LinkedHashSet<>(
                        eventKeys(exactEvent, context));
        for (String channelKey
                : channelKeys(
                immutableContractSnapshot, context)) {
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
     * Context-aware exact immutable acceptance.
     */
    default boolean accepts(
            T immutableContractSnapshot,
            Node exactEvent,
            ExternalChannelFunctionContext context) {
        return preselects(
                immutableContractSnapshot,
                exactEvent,
                context);
    }

    /**
     * Returns the exact channelized payload for an accepted occurrence.
     *
     * <p>The default preserves the exact input event. Runtime types that adapt
     * the payload must override this function; verified external delivery uses
     * this immutable function rather than the single-occurrence
     * {@link ChannelProcessor#evaluate} result.</p>
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
     * Context-aware channelized payload.
     */
    default Node payload(
            T immutableContractSnapshot,
            Node exactEvent,
            ExternalChannelFunctionContext context) {
        return payload(immutableContractSnapshot, exactEvent);
    }

    /**
     * Returns the same-scope Channel key used to discover handlers for this
     * accepted occurrence.
     *
     * <p>The accepting External Channel remains the source and checkpoint
     * owner. The returned Channel is only the logical handler target and is
     * never evaluated or checkpointed as another external occurrence. The
     * default preserves ordinary one-source/one-channel dispatch.</p>
     */
    default String handlerChannelKey(
            T immutableContractSnapshot,
            Node exactEvent,
            Node exactPayload,
            ExternalChannelFunctionContext context) {
        return context.channelKey();
    }

    /**
     * Returns the run-local logical-delivery key for this accepted occurrence.
     *
     * <p>Accepted-new occurrences in the same scope with the same logical key
     * are dispatched once when their exact payload identity and handler target
     * agree. Every participating source retains its own checkpoint. Defaulting
     * to the raw source key preserves independent delivery for existing
     * runtimes.</p>
     */
    default String logicalDeliveryKey(
            T immutableContractSnapshot,
            Node exactEvent,
            Node exactPayload,
            ExternalChannelFunctionContext context) {
        return context.channelKey();
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
     * Context-aware checkpoint subject.
     *
     * <p>A composite runtime can return a selected member evaluation's exact
     * subject unchanged. The subject may be an inline minimal ordering value;
     * it is not required to retain the complete event.</p>
     */
    default Node checkpointSubject(
            T immutableContractSnapshot,
            Node exactEvent,
            Node exactPayload,
            ExternalChannelFunctionContext context) {
        return checkpointSubject(
                immutableContractSnapshot,
                exactEvent,
                exactPayload);
    }

    /**
     * Returns the runtime-registered checkpoint-domain discriminator.  The
     * Contracts kernel combines it with the effective type and ordered Source
     * contribution identities to derive the exact checkpoint-domain BlueId.
     */
    default String checkpointDomainDiscriminator(
            T immutableContractSnapshot) {
        throw new UnsupportedOperationException(
                "External Channel runtime type must implement "
                        + "checkpointDomainDiscriminator");
    }

    /**
     * Context-aware checkpoint-domain discriminator. The generic kernel also
     * commits the exact ordered dependency identities captured by
     * {@code context} into the final domain BlueId.
     */
    default String checkpointDomainDiscriminator(
            T immutableContractSnapshot,
            ExternalChannelFunctionContext context) {
        return checkpointDomainDiscriminator(
                immutableContractSnapshot);
    }
}
