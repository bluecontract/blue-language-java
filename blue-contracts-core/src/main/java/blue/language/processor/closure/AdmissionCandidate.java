package blue.language.processor.closure;

import blue.language.model.Node;
import blue.language.provider.CyclicSetProof;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Typed potentially-invalid evidence supplied only to admission. */
public abstract class AdmissionCandidate {

    /** Closed candidate-evidence discriminator. */
    public enum Kind {
        /** Candidate cyclic proof expected to fail verification. */
        BAD_CYCLIC_PROOF,
        /** Preliminary members with an ambiguous stable mapping. */
        AMBIGUOUS_PRELIMINARY_MEMBERS,
        /** Structurally closed rows expected to fail semantic binding checks. */
        INVALID_OCCURRENCE_BINDINGS
    }

    private AdmissionCandidate() {
    }

    /**
     * Returns the documented value.
     *
     * @return closed candidate-evidence discriminator
     */
    public abstract Kind kind();

    /**
     * Creates a candidate proof for independent rejection.
     *
     * @param proof candidate cyclic proof
     * @return typed candidate evidence
     */
    public static AdmissionCandidate badCyclicProof(CyclicSetProof proof) {
        return new BadCyclicProof(proof);
    }

    /**
     * Creates ambiguous preliminary-member evidence.
     *
     * @param members canonical candidate members
     * @return typed candidate evidence
     */
    public static AdmissionCandidate ambiguousPreliminaryMembers(
            List<PreliminaryMember> members) {
        return new AmbiguousPreliminaryMembers(members);
    }

    /**
     * Creates occurrence rows requiring semantic rejection.
     *
     * @param bindings canonical candidate rows
     * @return typed candidate evidence
     */
    public static AdmissionCandidate invalidOccurrenceBindings(
            List<ManagedOccurrenceBinding> bindings) {
        return new InvalidOccurrenceBindings(bindings);
    }

    /** Candidate complete proof that admission is expected to reject. */
    public static final class BadCyclicProof extends AdmissionCandidate {
        private final CyclicSetProof proof;

        private BadCyclicProof(CyclicSetProof proof) {
            this.proof = copyProof(proof);
        }

        /**
         * Returns the documented value.
         *
         * @return {@link Kind#BAD_CYCLIC_PROOF}
         */
        @Override
        public Kind kind() {
            return Kind.BAD_CYCLIC_PROOF;
        }

        /**
         * Returns the documented value.
         *
         * @return independent copy of the candidate proof
         */
        public CyclicSetProof proof() {
            return copyProof(proof);
        }
    }

    /** Candidate members whose preliminary mapping is ambiguous. */
    public static final class AmbiguousPreliminaryMembers
            extends AdmissionCandidate {
        private final List<PreliminaryMember> members;

        private AmbiguousPreliminaryMembers(List<PreliminaryMember> members) {
            ArrayList<PreliminaryMember> copy = copyNonNull(
                    members, "preliminaryMembers");
            requireNonEmpty(copy, "preliminaryMembers");
            for (int index = 1; index < copy.size(); index++) {
                if (copy.get(index - 1).documentId().compareTo(
                        copy.get(index).documentId()) >= 0) {
                    throw new IllegalArgumentException(
                            "Preliminary members are not in canonical order");
                }
            }
            this.members = Collections.unmodifiableList(copy);
        }

        /**
         * Returns the documented value.
         *
         * @return {@link Kind#AMBIGUOUS_PRELIMINARY_MEMBERS}
         */
        @Override
        public Kind kind() {
            return Kind.AMBIGUOUS_PRELIMINARY_MEMBERS;
        }

        /**
         * Returns the documented value.
         *
         * @return immutable canonical preliminary members
         */
        public List<PreliminaryMember> members() {
            return members;
        }
    }

    /** Candidate rows that fail semantic occurrence-binding verification. */
    public static final class InvalidOccurrenceBindings
            extends AdmissionCandidate {
        private final List<ManagedOccurrenceBinding> bindings;

        private InvalidOccurrenceBindings(
                List<ManagedOccurrenceBinding> bindings) {
            ArrayList<ManagedOccurrenceBinding> copy = copyNonNull(
                    bindings, "occurrenceBindings");
            requireNonEmpty(copy, "occurrenceBindings");
            for (int index = 1; index < copy.size(); index++) {
                if (copy.get(index - 1).compareTo(copy.get(index)) >= 0) {
                    throw new IllegalArgumentException(
                            "Candidate occurrence bindings are not canonical");
                }
            }
            this.bindings = Collections.unmodifiableList(copy);
        }

        /**
         * Returns the documented value.
         *
         * @return {@link Kind#INVALID_OCCURRENCE_BINDINGS}
         */
        @Override
        public Kind kind() {
            return Kind.INVALID_OCCURRENCE_BINDINGS;
        }

        /**
         * Returns the documented value.
         *
         * @return immutable canonical candidate occurrence rows
         */
        public List<ManagedOccurrenceBinding> bindings() {
            return bindings;
        }
    }

    /** Exact preliminary document selected by stable lineage identity. */
    public static final class PreliminaryMember
            implements Comparable<PreliminaryMember> {
        private final DocumentId documentId;
        private final Node document;

        /**
         * Creates one exact preliminary member.
         *
         * @param documentId stable candidate lineage
         * @param document exact candidate node
         */
        public PreliminaryMember(DocumentId documentId, Node document) {
            this.documentId = Objects.requireNonNull(documentId, "documentId");
            this.document = Objects.requireNonNull(document, "document").clone();
        }

        /**
         * Returns the documented value.
         *
         * @return stable candidate lineage
         */
        public DocumentId documentId() {
            return documentId;
        }

        /**
         * Returns the documented value.
         *
         * @return defensive copy of the exact candidate node
         */
        public Node document() {
            return document.clone();
        }

        /**
         * Compares preliminary members by stable lineage.
         *
         * @param other member to compare
         * @return canonical member order
         */
        @Override
        public int compareTo(PreliminaryMember other) {
            return documentId.compareTo(other.documentId);
        }
    }

    private static CyclicSetProof copyProof(CyclicSetProof proof) {
        return CyclicSetProof.fromDeclaredPlaceholderSet(
                Objects.requireNonNull(proof, "proof")
                        .declaredPlaceholderSet());
    }

    private static <T> ArrayList<T> copyNonNull(
            List<T> values,
            String field) {
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
