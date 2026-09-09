package blue.language.processor;

import blue.language.identity.BlueIds;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;

import java.util.Objects;

/**
 * Frozen Phase-B checkpoint eligibility owned by one raw Root source Channel.
 *
 * <p>The candidate is read-only. Passing it to settlement does not itself
 * prove that the corresponding logical delivery completed; the orchestrator
 * supplies only completed candidates at the post-quiescence barrier.</p>
 */
public final class ManagedCheckpointCandidate {

    private final ManagedRootChannelOccurrence channelOccurrence;
    private final ManagedCheckpointDomain domain;
    private final FrozenNode exactSubject;
    private final String subjectBlueId;
    private final ManagedCheckpointState beforeState;
    private final boolean eligibleNew;
    private final String handlerChannelKey;
    private final String logicalDeliveryKey;
    private final FrozenNode exactPayload;
    private final String payloadBlueId;

    ManagedCheckpointCandidate(
            ManagedRootChannelOccurrence channelOccurrence,
            ManagedCheckpointDomain domain,
            FrozenNode exactSubject,
            String subjectBlueId,
            ManagedCheckpointState beforeState,
            boolean eligibleNew,
            String handlerChannelKey,
            String logicalDeliveryKey,
            FrozenNode exactPayload,
            String payloadBlueId) {
        this.channelOccurrence = Objects.requireNonNull(
                channelOccurrence, "channelOccurrence");
        if (!channelOccurrence.externalSource()) {
            throw new IllegalArgumentException(
                    "Checkpoint candidate requires an External Channel");
        }
        this.domain = Objects.requireNonNull(domain, "domain");
        this.exactSubject = Objects.requireNonNull(
                exactSubject, "exactSubject");
        this.subjectBlueId = BlueIds.requireBlueIdOrCyclicMember(
                subjectBlueId,
                "subjectBlueId");
        this.beforeState = Objects.requireNonNull(
                beforeState, "beforeState");
        this.eligibleNew = eligibleNew;
        this.handlerChannelKey = requireText(
                handlerChannelKey, "handlerChannelKey");
        this.logicalDeliveryKey = requireText(
                logicalDeliveryKey, "logicalDeliveryKey");
        this.exactPayload = Objects.requireNonNull(
                exactPayload, "exactPayload");
        this.payloadBlueId = BlueIds.requireBlueIdOrCyclicMember(
                payloadBlueId,
                "payloadBlueId");
    }

    /**
     * Returns the exact raw source key.
     *
     * @return non-empty Root Channel key
     */
    public String rawChannelKey() {
        return channelOccurrence.rawChannelKey();
    }

    /**
     * Returns immutable authoritative Channel-occurrence facts.
     *
     * @return processor-derived Root Channel projection
     */
    public ManagedRootChannelOccurrence channelOccurrence() {
        return channelOccurrence;
    }

    /**
     * Returns the complete exact frozen checkpoint domain.
     *
     * @return exact domain DTO
     */
    public ManagedCheckpointDomain domain() { return domain; }

    /**
     * Returns the exact checkpoint subject defensively.
     *
     * @return detached exact subject node
     */
    public Node exactSubject() { return exactSubject.toNode(); }

    /**
     * Returns the exact subject identity frozen during Phase B.
     *
     * @return exact subject BlueId
     */
    public String subjectBlueId() { return subjectBlueId; }

    /**
     * Returns the stable checkpoint representation to publish.
     *
     * <p>A resolved cursor for a cyclic member cannot be serialized as if it
     * were standalone authored content. Its canonical persistent form is the
     * already-proved member reference. Ordinary subjects retain their exact
     * inline representation so custom ordering functions can read them later
     * without an unrelated provider lookup.</p>
     *
     * @return detached exact persistent subject representation
     */
    Node settlementSubject() {
        return BlueIds.hasCyclicMemberSeparator(subjectBlueId)
                && !exactSubject.isReferenceOnly()
                ? new Node().blueId(subjectBlueId)
                : exactSubject.toNode();
    }

    /**
     * Returns the exact raw-key state observed before comparison.
     *
     * @return immutable complete before side
     */
    public ManagedCheckpointState beforeState() { return beforeState; }

    /**
     * Reports whether newness and duplicate checks accepted this subject.
     *
     * @return whether successful delivery may settle this candidate
     */
    public boolean eligibleNew() { return eligibleNew; }

    /**
     * Returns the same-Root Channel key used for Handler lookup.
     *
     * @return exact Handler-target Channel key
     */
    public String handlerChannelKey() { return handlerChannelKey; }

    /**
     * Returns the runtime-defined logical delivery grouping key.
     *
     * @return immutable logical-delivery key
     */
    public String logicalDeliveryKey() { return logicalDeliveryKey; }

    /**
     * Returns the exact channelized payload defensively.
     *
     * @return detached exact payload
     */
    public Node exactPayload() { return exactPayload.toNode(); }

    /** Carries the already admitted immutable payload within the processor. */
    FrozenNode frozenPayload() { return exactPayload; }

    /**
     * Returns the identity proved when the external payload was admitted.
     *
     * <p>This value is carried separately because a cyclic member has no
     * standalone ordinary identity input and therefore must not be rehashed
     * from its frozen representation.</p>
     *
     * @return exact payload BlueId, including a cyclic-member identity
     */
    public String payloadBlueId() { return payloadBlueId; }

    private static String requireText(String value, String label) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(label + " must be non-empty");
        }
        return value;
    }
}
