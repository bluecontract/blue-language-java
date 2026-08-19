package blue.language.processor.closure;

import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.model.wire.BlueLanguageConstants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Closed, untrusted evidence supplied only to closure admission.
 *
 * <p>Construction proves the released branch shape and portable scalar
 * constraints only.  In particular, a structurally valid candidate may still
 * contain a mismatched cyclic proof, ambiguous preliminary members, or an
 * occurrence row whose exact source route does not exist.  Those are semantic
 * verifier conclusions and are not constructor failures.</p>
 */
public abstract class AdmissionCandidate {

    /** Closed candidate-evidence discriminator. */
    public enum Kind {
        /** Candidate complete cyclic proof. */
        BAD_CYCLIC_PROOF,
        /** Complete preliminary members that may be ambiguous. */
        AMBIGUOUS_PRELIMINARY_MEMBERS,
        /** Strict candidate occurrence rows. */
        INVALID_OCCURRENCE_BINDING
    }

    private AdmissionCandidate() {
    }

    /**
     * Returns the closed branch discriminator.
     *
     * @return candidate kind
     */
    public abstract Kind kind();

    /**
     * Creates complete candidate cyclic-proof evidence.
     *
     * @param candidateCyclicProof exact untrusted proof record
     * @return typed candidate evidence
     */
    public static AdmissionCandidate badCyclicProof(
            CandidateCyclicProof candidateCyclicProof) {
        return new BadCyclicProof(candidateCyclicProof);
    }

    /**
     * Creates complete preliminary-member evidence.
     *
     * @param candidateCyclicMembers members in canonical DocumentId order
     * @return typed candidate evidence
     */
    public static AdmissionCandidate ambiguousPreliminaryMembers(
            List<CandidateCyclicMember> candidateCyclicMembers) {
        return new AmbiguousPreliminaryMembers(candidateCyclicMembers);
    }

    /**
     * Creates strict occurrence-binding verifier probes.
     *
     * @param candidateOccurrenceBindings rows in canonical identity order
     * @return typed candidate evidence
     */
    public static AdmissionCandidate invalidOccurrenceBinding(
            List<CandidateOccurrenceBinding> candidateOccurrenceBindings) {
        return new InvalidOccurrenceBinding(candidateOccurrenceBindings);
    }

    /** Complete proof record whose semantic claims are not yet trusted. */
    public static final class BadCyclicProof extends AdmissionCandidate {
        private final CandidateCyclicProof candidateCyclicProof;

        private BadCyclicProof(CandidateCyclicProof candidateCyclicProof) {
            this.candidateCyclicProof = Objects.requireNonNull(
                    candidateCyclicProof, "candidateCyclicProof");
        }

        /**
         * Returns the branch discriminator.
         *
         * @return {@link Kind#BAD_CYCLIC_PROOF}
         */
        @Override
        public Kind kind() {
            return Kind.BAD_CYCLIC_PROOF;
        }

        /**
         * Returns the immutable complete proof record.
         *
         * @return candidate proof
         */
        public CandidateCyclicProof candidateCyclicProof() {
            return candidateCyclicProof;
        }
    }

    /** Candidate members submitted to the unchanged preliminary calculation. */
    public static final class AmbiguousPreliminaryMembers
            extends AdmissionCandidate {
        private final List<CandidateCyclicMember> candidateCyclicMembers;

        private AmbiguousPreliminaryMembers(
                List<CandidateCyclicMember> candidateCyclicMembers) {
            ArrayList<CandidateCyclicMember> copy = copyNonNull(
                    candidateCyclicMembers, "candidateCyclicMembers");
            requireNonEmpty(copy, "candidateCyclicMembers");
            requireStrictMemberOrder(copy);
            this.candidateCyclicMembers = Collections.unmodifiableList(copy);
        }

        /**
         * Returns the branch discriminator.
         *
         * @return {@link Kind#AMBIGUOUS_PRELIMINARY_MEMBERS}
         */
        @Override
        public Kind kind() {
            return Kind.AMBIGUOUS_PRELIMINARY_MEMBERS;
        }

        /**
         * Returns the immutable canonical member sequence.
         *
         * @return candidate members
         */
        public List<CandidateCyclicMember> candidateCyclicMembers() {
            return candidateCyclicMembers;
        }
    }

    /** Candidate rows selected for independent occurrence verification. */
    public static final class InvalidOccurrenceBinding
            extends AdmissionCandidate {
        private final List<CandidateOccurrenceBinding>
                candidateOccurrenceBindings;

        private InvalidOccurrenceBinding(
                List<CandidateOccurrenceBinding> candidateOccurrenceBindings) {
            ArrayList<CandidateOccurrenceBinding> copy = copyNonNull(
                    candidateOccurrenceBindings,
                    "candidateOccurrenceBindings");
            requireNonEmpty(copy, "candidateOccurrenceBindings");
            for (int index = 1; index < copy.size(); index++) {
                if (copy.get(index - 1).compareTo(copy.get(index)) >= 0) {
                    throw new IllegalArgumentException(
                            "candidateOccurrenceBindings are not canonical");
                }
            }
            this.candidateOccurrenceBindings =
                    Collections.unmodifiableList(copy);
        }

        /**
         * Returns the branch discriminator.
         *
         * @return {@link Kind#INVALID_OCCURRENCE_BINDING}
         */
        @Override
        public Kind kind() {
            return Kind.INVALID_OCCURRENCE_BINDING;
        }

        /**
         * Returns the immutable canonical candidate-row sequence.
         *
         * @return strict nine-field rows
         */
        public List<CandidateOccurrenceBinding>
                candidateOccurrenceBindings() {
            return candidateOccurrenceBindings;
        }
    }

    /**
     * Complete candidate proof record from the released closed union.
     *
     * <p>The record is only structurally admitted.  Its component, master,
     * member states, and placeholder set are deliberately allowed to disagree
     * so that admission can derive the semantic failure independently.</p>
     */
    public static final class CandidateCyclicProof {
        private final String componentIdentity;
        private final String masterBlueId;
        private final List<CandidateMemberState> memberStates;
        private final List<Node> declaredPlaceholderSet;

        /**
         * Creates one complete untrusted proof record.
         *
         * @param componentIdentity claimed component identity
         * @param masterBlueId claimed cyclic master BlueId
         * @param memberStates claimed states in canonical DocumentId order
         * @param declaredPlaceholderSet complete submitted placeholder set
         */
        public CandidateCyclicProof(
                String componentIdentity,
                String masterBlueId,
                List<CandidateMemberState> memberStates,
                List<Node> declaredPlaceholderSet) {
            this.componentIdentity = ClosureValueSupport.requireSha256Identity(
                    componentIdentity, "componentIdentity");
            this.masterBlueId = ClosureValueSupport.requireBlueId(
                    masterBlueId, "masterBlueId");
            ArrayList<CandidateMemberState> states = copyNonNull(
                    memberStates, "memberStates");
            requireNonEmpty(states, "memberStates");
            for (int index = 1; index < states.size(); index++) {
                if (states.get(index - 1).compareTo(states.get(index)) >= 0) {
                    throw new IllegalArgumentException(
                            "memberStates are not in canonical order");
                }
            }
            this.memberStates = Collections.unmodifiableList(states);
            this.declaredPlaceholderSet = immutableNodes(
                    declaredPlaceholderSet, "declaredPlaceholderSet");
        }

        /**
         * Returns the claimed component identity.
         *
         * @return claimed component identity
         */
        public String componentIdentity() {
            return componentIdentity;
        }

        /**
         * Returns the claimed cyclic master BlueId.
         *
         * @return claimed master BlueId
         */
        public String masterBlueId() {
            return masterBlueId;
        }

        /**
         * Returns the canonical claimed member-state sequence.
         *
         * @return immutable canonical claimed member states
         */
        public List<CandidateMemberState> memberStates() {
            return memberStates;
        }

        /**
         * Returns defensive copies of the exact submitted placeholder set.
         *
         * @return immutable copied placeholder members
         */
        public List<Node> declaredPlaceholderSet() {
            return defensiveNodes(declaredPlaceholderSet);
        }
    }

    /** One claimed member state in a candidate cyclic proof. */
    public static final class CandidateMemberState
            implements Comparable<CandidateMemberState> {
        private final DocumentId documentId;
        private final String blueId;

        /**
         * Creates one claimed proof member state.
         *
         * @param documentId stable managed lineage
         * @param blueId claimed exact state BlueId
         */
        public CandidateMemberState(DocumentId documentId, String blueId) {
            this.documentId = Objects.requireNonNull(
                    documentId, "documentId");
            this.blueId = ClosureValueSupport.requireBlueId(
                    blueId, BlueLanguageConstants.OBJECT_BLUE_ID);
        }

        /**
         * Returns the stable managed lineage.
         *
         * @return stable managed lineage
         */
        public DocumentId documentId() {
            return documentId;
        }

        /**
         * Returns the claimed exact member-state BlueId.
         *
         * @return claimed exact state BlueId
         */
        public String blueId() {
            return blueId;
        }

        /** {@inheritDoc} */
        @Override
        public int compareTo(CandidateMemberState other) {
            return documentId.compareTo(other.documentId);
        }
    }

    /** One exact preliminary member in canonical DocumentId order. */
    public static final class CandidateCyclicMember
            implements Comparable<CandidateCyclicMember> {
        private final DocumentId documentId;
        private final Node document;

        /**
         * Creates one submitted preliminary member.
         *
         * @param documentId stable candidate lineage
         * @param document complete placeholder-form document
         */
        public CandidateCyclicMember(DocumentId documentId, Node document) {
            this.documentId = Objects.requireNonNull(documentId, "documentId");
            this.document = admittedNode(document, "document");
        }

        /**
         * Returns the stable candidate lineage.
         *
         * @return stable candidate lineage
         */
        public DocumentId documentId() {
            return documentId;
        }

        /**
         * Returns a defensive copy of the submitted document.
         *
         * @return defensive copy of the complete candidate document
         */
        public Node document() {
            return document.clone();
        }

        /** {@inheritDoc} */
        @Override
        public int compareTo(CandidateCyclicMember other) {
            return documentId.compareTo(other.documentId);
        }
    }

    /**
     * Strict nine-field candidate occurrence row.
     *
     * <p>This is intentionally not {@link ManagedOccurrenceBinding}: candidate
     * rows cannot carry authoritative {@code pendingHistoricalEpoch} state.</p>
     */
    public static final class CandidateOccurrenceBinding
            implements Comparable<CandidateOccurrenceBinding> {
        private final String occurrenceIdentity;
        private final String bindingIdentity;
        private final String bindingPolicyIdentity;
        private final DocumentId sourceDocumentId;
        private final String sourcePath;
        private final long activationGeneration;
        private final DocumentId targetDocumentId;
        private final String expectedTargetBlueId;
        private final boolean active;

        /**
         * Creates one strict candidate row.
         *
         * @param occurrenceIdentity claimed stable occurrence identity
         * @param bindingIdentity claimed state-specific binding identity
         * @param bindingPolicyIdentity claimed selected binding policy
         * @param sourceDocumentId containing document lineage
         * @param sourcePath absolute non-Root occurrence path
         * @param activationGeneration positive occurrence generation
         * @param targetDocumentId selected target lineage
         * @param expectedTargetBlueId claimed exact target state
         * @param active whether the candidate claims an active edge
         */
        public CandidateOccurrenceBinding(
                String occurrenceIdentity,
                String bindingIdentity,
                String bindingPolicyIdentity,
                DocumentId sourceDocumentId,
                String sourcePath,
                long activationGeneration,
                DocumentId targetDocumentId,
                String expectedTargetBlueId,
                boolean active) {
            this.occurrenceIdentity =
                    ClosureValueSupport.requireSha256Identity(
                            occurrenceIdentity, "occurrenceIdentity");
            this.bindingIdentity = ClosureValueSupport.requireSha256Identity(
                    bindingIdentity, "bindingIdentity");
            this.bindingPolicyIdentity =
                    ClosureValueSupport.requireSha256Identity(
                            bindingPolicyIdentity, "bindingPolicyIdentity");
            this.sourceDocumentId = Objects.requireNonNull(
                    sourceDocumentId, "sourceDocumentId");
            ScopeAddress address = ScopeAddress.embedded(
                    sourcePath, activationGeneration);
            this.sourcePath = address.path();
            this.activationGeneration = address.activationGeneration();
            this.targetDocumentId = Objects.requireNonNull(
                    targetDocumentId, "targetDocumentId");
            this.expectedTargetBlueId = ClosureValueSupport.requireBlueId(
                    expectedTargetBlueId, "expectedTargetBlueId");
            this.active = active;
        }

        /**
         * Returns the claimed stable occurrence identity.
         *
         * @return claimed stable occurrence identity
         */
        public String occurrenceIdentity() {
            return occurrenceIdentity;
        }

        /**
         * Returns the claimed state-specific binding identity.
         *
         * @return claimed state-specific binding identity
         */
        public String bindingIdentity() {
            return bindingIdentity;
        }

        /**
         * Returns the claimed selected binding-policy identity.
         *
         * @return claimed selected binding policy
         */
        public String bindingPolicyIdentity() {
            return bindingPolicyIdentity;
        }

        /**
         * Returns the containing document lineage.
         *
         * @return containing document lineage
         */
        public DocumentId sourceDocumentId() {
            return sourceDocumentId;
        }

        /**
         * Returns the absolute non-Root occurrence path.
         *
         * @return absolute non-Root occurrence path
         */
        public String sourcePath() {
            return sourcePath;
        }

        /**
         * Returns the positive occurrence generation.
         *
         * @return positive occurrence generation
         */
        public long activationGeneration() {
            return activationGeneration;
        }

        /**
         * Returns the selected target lineage.
         *
         * @return selected target lineage
         */
        public DocumentId targetDocumentId() {
            return targetDocumentId;
        }

        /**
         * Returns the claimed exact target-state BlueId.
         *
         * @return claimed exact target state
         */
        public String expectedTargetBlueId() {
            return expectedTargetBlueId;
        }

        /**
         * Reports whether the candidate claims an active edge.
         *
         * @return whether the candidate claims an active edge
         */
        public boolean active() {
            return active;
        }

        /** {@inheritDoc} */
        @Override
        public int compareTo(CandidateOccurrenceBinding other) {
            int order = ClosureValueSupport.comparePortableText(
                    occurrenceIdentity, other.occurrenceIdentity);
            return order != 0
                    ? order
                    : ClosureValueSupport.comparePortableText(
                            bindingIdentity, other.bindingIdentity);
        }
    }

    private static void requireStrictMemberOrder(
            List<CandidateCyclicMember> members) {
        for (int index = 1; index < members.size(); index++) {
            if (members.get(index - 1).compareTo(members.get(index)) >= 0) {
                throw new IllegalArgumentException(
                        "candidateCyclicMembers are not in canonical order");
            }
        }
    }

    private static Node admittedNode(Node node, String field) {
        Node copy = Objects.requireNonNull(node, field).clone();
        NodeWireForm.get(copy, NodeWireForm.Strategy.SIMPLE);
        return copy;
    }

    private static List<Node> immutableNodes(
            List<Node> values, String field) {
        ArrayList<Node> copy = new ArrayList<Node>();
        for (Node value : Objects.requireNonNull(values, field)) {
            copy.add(admittedNode(value, field + " item"));
        }
        requireNonEmpty(copy, field);
        return Collections.unmodifiableList(copy);
    }

    private static List<Node> defensiveNodes(List<Node> values) {
        ArrayList<Node> copy = new ArrayList<Node>(values.size());
        for (Node value : values) {
            copy.add(value.clone());
        }
        return Collections.unmodifiableList(copy);
    }

    private static <T> ArrayList<T> copyNonNull(
            List<T> values, String field) {
        ArrayList<T> copy = new ArrayList<T>(
                Objects.requireNonNull(values, field));
        for (T value : copy) {
            Objects.requireNonNull(value, field + " item");
        }
        return copy;
    }

    private static void requireNonEmpty(List<?> values, String field) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
    }
}
