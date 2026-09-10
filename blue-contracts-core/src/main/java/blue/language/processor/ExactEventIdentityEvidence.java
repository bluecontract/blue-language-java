package blue.language.processor;

import blue.language.api.NodeProviderOutcome;
import blue.language.identity.BlueIds;
import blue.language.model.Node;
import blue.language.provider.CyclicAwareNodeProvider;
import blue.language.provider.CyclicSetProof;
import blue.language.provider.CyclicSetProofResult;
import blue.language.provider.NodeProvider;
import blue.language.provider.NodeProviderResult;
import blue.language.provider.VerifyingNodeProvider;
import blue.language.snapshot.FrozenNode;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable proof that one exact event value has the accompanying BlueId.
 *
 * <p>The evidence is created only after one of three complete checks: a pure
 * reference names itself, ordinary Source is canonicalized by the selected
 * Language runtime, or a resolved cyclic member is verified with its complete
 * cyclic-set proof. Processor-internal semantic admissions may also mint this
 * capability without repeating work already proved by the same invocation.
 * Consequently, consumers must carry this value instead of detaching a
 * {@code Node}/{@code eventBlueId} pair and attempting a standalone hash.</p>
 */
public final class ExactEventIdentityEvidence {

    private final FrozenNode exactEvent;
    private final String eventBlueId;

    private ExactEventIdentityEvidence(
            FrozenNode exactEvent,
            String eventBlueId) {
        this.exactEvent = Objects.requireNonNull(exactEvent, "exactEvent");
        this.eventBlueId = BlueIds.requireBlueIdOrCyclicMember(
                Objects.requireNonNull(eventBlueId, "eventBlueId"),
                "eventBlueId");
    }

    /**
     * Independently verifies external or reconstructed event evidence.
     *
     * <p>A pure reference requires no materialized proof and must not carry
     * one. A resolved cyclic member requires a complete cyclic-set proof.
     * Every other value is treated as Source and receives the same
     * Source-derived canonical identity used by ordinary processing. The
     * runtime may be {@code null} only when that Source has no materialized
     * reserved type position.</p>
     *
     * @param runtimeAccess selected processor runtime, when Source
     *     canonicalization requires it
     * @param exactEvent exact reference, Source, or resolved cyclic member
     * @param eventBlueId asserted exact event identity
     * @param completeCyclicProof complete proof exactly for a resolved cyclic
     *     member
     * @return immutable verified event identity evidence
     */
    public static ExactEventIdentityEvidence verify(
            ProcessorRuntimeAccess runtimeAccess,
            Node exactEvent,
            String eventBlueId,
            CyclicSetProof completeCyclicProof) {
        Node event = Objects.requireNonNull(
                exactEvent, "exactEvent").clone();
        String claimed = BlueIds.requireBlueIdOrCyclicMember(
                Objects.requireNonNull(eventBlueId, "eventBlueId"),
                "eventBlueId");
        if (event.isReferenceOnly()) {
            if (completeCyclicProof != null) {
                throw new IllegalArgumentException(
                        "A pure event reference must not carry cyclic-set proof");
            }
            if (!claimed.equals(event.getBlueId())) {
                throw new IllegalArgumentException(
                        "eventBlueId does not match the exact event reference");
            }
            return new ExactEventIdentityEvidence(
                    FrozenNode.fromResolvedNode(event), claimed);
        }

        boolean cyclicMember = BlueIds.hasCyclicMemberSeparator(claimed);
        if (cyclicMember != (completeCyclicProof != null)) {
            throw new IllegalArgumentException(
                    "A resolved cyclic event requires exactly one complete "
                            + "cyclic-set proof");
        }
        if (cyclicMember) {
            verifyCyclicMember(
                    event, claimed, completeCyclicProof);
            return new ExactEventIdentityEvidence(
                    FrozenNode.fromResolvedNode(event), claimed);
        }

        // Ordinary exact input admission already has to establish this identity.
        // Retain that operation's strict immutable cursor rather than discarding
        // it and later handing a resolved-identity cursor to hosted runtimes.
        FrozenNode canonical = null;
        if (!CanonicalIdentityEvidence.requiresEffectiveTypeIdentity(event)) {
            try {
                canonical = FrozenNode.fromNode(event);
            } catch (IllegalArgumentException nonCanonicalSource) {
                // Preserve the existing Language-owned Source fallback below.
            }
        }
        if (canonical != null) {
            String calculated = canonical.blueId();
            if (!claimed.equals(calculated)) {
                throw new IllegalArgumentException(
                        "eventBlueId does not identify exact Source event: claimed="
                                + claimed + ", calculated=" + calculated);
            }
            return new ExactEventIdentityEvidence(canonical, claimed);
        }

        String calculated = CheckpointIdentityCalculator.identity(
                event,
                runtimeAccess != null
                        ? runtimeAccess.languageRuntime()
                        : null);
        if (!claimed.equals(calculated)) {
            throw new IllegalArgumentException(
                    "eventBlueId does not identify exact Source event: claimed="
                            + claimed + ", calculated=" + calculated);
        }
        return new ExactEventIdentityEvidence(
                FrozenNode.fromResolvedNode(event), claimed);
    }

    /** Creates evidence from an invocation-admitted exact capability. */
    static ExactEventIdentityEvidence fromAdmitted(
            ExactBlueValue exactEvent) {
        ExactBlueValue admitted = Objects.requireNonNull(
                exactEvent, "exactEvent");
        return new ExactEventIdentityEvidence(
                admitted.frozenValue(), admitted.blueId());
    }

    /** Carries ordinary Source evidence just established by this processor. */
    static ExactEventIdentityEvidence fromVerifiedSource(
            Node exactEvent,
            String eventBlueId) {
        return new ExactEventIdentityEvidence(
                FrozenNode.fromResolvedNode(
                        Objects.requireNonNull(
                                exactEvent, "exactEvent").clone()),
                eventBlueId);
    }

    /**
     * Returns an immutable event cursor.
     *
     * @return exact frozen event value
     */
    public FrozenNode frozenEvent() {
        return exactEvent;
    }

    /**
     * Returns a detached event value.
     *
     * @return defensive mutable copy of the exact event
     */
    public Node event() {
        return exactEvent.toNode();
    }

    /**
     * Returns the identity proved for the exact event.
     *
     * @return ordinary or cyclic-member BlueId
     */
    public String eventBlueId() {
        return eventBlueId;
    }

    private static void verifyCyclicMember(
            Node exactEvent,
            String eventBlueId,
            CyclicSetProof completeCyclicProof) {
        CyclicSetProof proof = copyProof(Objects.requireNonNull(
                completeCyclicProof, "completeCyclicProof"));
        NodeProviderResult verified = new VerifyingNodeProvider(
                new SingleMemberEvidence(
                        eventBlueId, exactEvent, proof))
                .fetchResultByBlueId(eventBlueId);
        if (verified.outcome() != NodeProviderOutcome.FOUND) {
            throw new IllegalArgumentException(
                    verified.diagnostic().orElse(
                            "Resolved cyclic event evidence is invalid"));
        }
    }

    private static CyclicSetProof copyProof(CyclicSetProof proof) {
        return CyclicSetProof.fromDeclaredPlaceholderSet(
                proof.declaredPlaceholderSet());
    }

    /** Proof-bearing provider used only for independent value verification. */
    private static final class SingleMemberEvidence
            implements NodeProvider, CyclicAwareNodeProvider {
        private final String expectedBlueId;
        private final Node exactEvent;
        private final CyclicSetProof proof;

        private SingleMemberEvidence(
                String expectedBlueId,
                Node exactEvent,
                CyclicSetProof proof) {
            this.expectedBlueId = expectedBlueId;
            this.exactEvent = exactEvent.clone();
            this.proof = copyProof(proof);
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            return expectedBlueId.equals(blueId)
                    ? Collections.singletonList(exactEvent.clone())
                    : Collections.<Node>emptyList();
        }

        @Override
        public CyclicSetProofResult cyclicSetProofFor(String blueId) {
            return expectedBlueId.equals(blueId)
                    ? CyclicSetProofResult.found(copyProof(proof))
                    : CyclicSetProofResult.notFound();
        }
    }
}
