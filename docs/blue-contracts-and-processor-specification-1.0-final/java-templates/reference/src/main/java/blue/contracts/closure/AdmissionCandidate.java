package blue.contracts.closure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Closed admission-candidate union used only when admission must verify
 * potentially invalid evidence. The candidate identity is carried separately
 * by {@link AffectedClosureSnapshot} and is always nullable-but-present in the
 * serialized invocation shape.
 */
public final class AdmissionCandidate {
    public enum Kind {
        BAD_CYCLIC_PROOF,
        AMBIGUOUS_PRELIMINARY_MEMBERS,
        INVALID_OCCURRENCE_BINDING
    }

    private final Evidence evidence;

    public AdmissionCandidate(Evidence evidence) {
        this.evidence = Objects.requireNonNull(evidence, "evidence");
    }

    public Kind kind() { return evidence.candidateKind(); }
    public Evidence evidence() { return evidence; }

    /** Base class with a private constructor, making the evidence union closed. */
    public abstract static class Evidence {
        private Evidence() { }
        abstract Kind candidateKind();
    }

    public static final class BadCyclicProofEvidence extends Evidence {
        private final Object candidateCyclicProof;

        public BadCyclicProofEvidence(Object candidateCyclicProof) {
            this.candidateCyclicProof = Objects.requireNonNull(
                    candidateCyclicProof, "candidateCyclicProof");
        }

        @Override
        Kind candidateKind() { return Kind.BAD_CYCLIC_PROOF; }
        public Object candidateCyclicProof() { return candidateCyclicProof; }
    }

    public static final class AmbiguousPreliminaryMembersEvidence extends Evidence {
        private final List<CandidateCyclicMember> candidateCyclicMembers;

        public AmbiguousPreliminaryMembersEvidence(
                List<CandidateCyclicMember> candidateCyclicMembers) {
            ArrayList<CandidateCyclicMember> copy = new ArrayList<CandidateCyclicMember>(
                    Objects.requireNonNull(
                            candidateCyclicMembers, "candidateCyclicMembers"));
            requireNonEmpty(copy, "candidateCyclicMembers");
            for (int index = 1; index < copy.size(); index++) {
                if (copy.get(index - 1).documentId().compareTo(
                        copy.get(index).documentId()) >= 0) {
                    throw new IllegalArgumentException(
                            "candidateCyclicMembers not in canonical order");
                }
            }
            this.candidateCyclicMembers = Collections.unmodifiableList(copy);
        }

        @Override
        Kind candidateKind() { return Kind.AMBIGUOUS_PRELIMINARY_MEMBERS; }
        public List<CandidateCyclicMember> candidateCyclicMembers() {
            return candidateCyclicMembers;
        }
    }

    public static final class InvalidOccurrenceBindingEvidence extends Evidence {
        private final List<CandidateOccurrenceBinding> candidateOccurrenceBindings;

        public InvalidOccurrenceBindingEvidence(
                List<CandidateOccurrenceBinding> candidateOccurrenceBindings) {
            ArrayList<CandidateOccurrenceBinding> copy =
                    new ArrayList<CandidateOccurrenceBinding>(Objects.requireNonNull(
                            candidateOccurrenceBindings,
                            "candidateOccurrenceBindings"));
            requireNonEmpty(copy, "candidateOccurrenceBindings");
            for (int index = 1; index < copy.size(); index++) {
                CandidateOccurrenceBinding before = copy.get(index - 1);
                CandidateOccurrenceBinding after = copy.get(index);
                int occurrenceOrder = before.occurrenceIdentity().compareTo(
                        after.occurrenceIdentity());
                if (occurrenceOrder > 0
                        || (occurrenceOrder == 0
                        && before.bindingIdentity().compareTo(
                                after.bindingIdentity()) >= 0)) {
                    throw new IllegalArgumentException(
                            "candidateOccurrenceBindings not in canonical order");
                }
            }
            this.candidateOccurrenceBindings = Collections.unmodifiableList(copy);
        }

        @Override
        Kind candidateKind() { return Kind.INVALID_OCCURRENCE_BINDING; }
        public List<CandidateOccurrenceBinding> candidateOccurrenceBindings() {
            return candidateOccurrenceBindings;
        }
    }

    /** Strict nine-field occurrence row used only as admission evidence. */
    public static final class CandidateOccurrenceBinding {
        private final String occurrenceIdentity;
        private final String bindingIdentity;
        private final String bindingPolicyIdentity;
        private final DocumentId sourceDocumentId;
        private final String sourcePath;
        private final long activationGeneration;
        private final DocumentId targetDocumentId;
        private final String expectedTargetBlueId;
        private final boolean active;

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
            this.occurrenceIdentity = Objects.requireNonNull(
                    occurrenceIdentity, "occurrenceIdentity");
            this.bindingIdentity = Objects.requireNonNull(
                    bindingIdentity, "bindingIdentity");
            this.bindingPolicyIdentity = Objects.requireNonNull(
                    bindingPolicyIdentity, "bindingPolicyIdentity");
            this.sourceDocumentId = Objects.requireNonNull(
                    sourceDocumentId, "sourceDocumentId");
            this.sourcePath = Objects.requireNonNull(sourcePath, "sourcePath");
            this.activationGeneration = CanonicalOrders.requireSafeInteger(
                    activationGeneration, "activationGeneration");
            this.targetDocumentId = Objects.requireNonNull(
                    targetDocumentId, "targetDocumentId");
            this.expectedTargetBlueId = Objects.requireNonNull(
                    expectedTargetBlueId, "expectedTargetBlueId");
            this.active = active;
        }

        public String occurrenceIdentity() { return occurrenceIdentity; }
        public String bindingIdentity() { return bindingIdentity; }
        public String bindingPolicyIdentity() { return bindingPolicyIdentity; }
        public DocumentId sourceDocumentId() { return sourceDocumentId; }
        public String sourcePath() { return sourcePath; }
        public long activationGeneration() { return activationGeneration; }
        public DocumentId targetDocumentId() { return targetDocumentId; }
        public String expectedTargetBlueId() { return expectedTargetBlueId; }
        public boolean active() { return active; }
    }

    /** One exact preliminary member in canonical DocumentId order. */
    public static final class CandidateCyclicMember {
        private final DocumentId documentId;
        private final Object document;

        public CandidateCyclicMember(DocumentId documentId, Object document) {
            this.documentId = Objects.requireNonNull(documentId, "documentId");
            this.document = Objects.requireNonNull(document, "document");
        }

        public DocumentId documentId() { return documentId; }
        public Object document() { return document; }
    }

    private static void requireNonEmpty(List<?> values, String field) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException(field);
        }
    }
}
