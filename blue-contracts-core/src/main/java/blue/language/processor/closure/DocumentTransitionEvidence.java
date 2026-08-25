package blue.language.processor.closure;

import blue.language.identity.BlueIds;
import blue.language.model.Node;
import blue.language.processor.DocumentUpdateOccurrence;
import blue.language.processor.FrozenJsonPatch;
import blue.language.processor.ManagedGeneralizationWrite;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable presentation evidence for one processor-owned managed-document
 * step inside a closure attempt.
 *
 * <p>The evidence is deliberately not an input to invocation, document,
 * component, closure, companion, or result identity. Every field reports a
 * fact already produced by the authoritative processor path. Consumers must
 * never use this value to authorize or reconstruct processing.</p>
 */
public final class DocumentTransitionEvidence {

    private final DocumentId documentId;
    private final String workOccurrenceIdentity;
    private final String beforeDocumentBlueId;
    private final String afterDocumentBlueId;
    private final String beforeEffectiveTypeBlueId;
    private final String afterEffectiveTypeBlueId;
    private final List<AuthoredContractPatch> authoredContractPatches;
    private final List<GeneratedGeneralizationWrite>
            generatedGeneralizationWrites;

    static DocumentTransitionEvidence fromManagedStep(
            DocumentId documentId,
            String workOccurrenceIdentity,
            String beforeDocumentBlueId,
            String beforeEffectiveTypeBlueId,
            String afterEffectiveTypeBlueId,
            List<FrozenJsonPatch> orderedPatches,
            List<DocumentUpdateOccurrence> orderedPatchUpdates,
            List<ManagedGeneralizationWrite> generatedWrites) {
        if (orderedPatches.size() != orderedPatchUpdates.size()) {
            throw new IllegalArgumentException(
                    "Authored patches and transitions must have equal size");
        }
        ArrayList<AuthoredContractPatch> contractPatches =
                new ArrayList<AuthoredContractPatch>();
        for (int index = 0; index < orderedPatches.size(); index++) {
            FrozenJsonPatch patch = orderedPatches.get(index);
            FrozenJsonPatch exact = Objects.requireNonNull(
                    patch, "ordered patch");
            List<String> segments = exact.parsedPath().segments();
            if (!segments.isEmpty()
                    && ProcessorContractConstants.KEY_CONTRACTS.equals(
                            segments.get(0))) {
                contractPatches.add(AuthoredContractPatch.from(
                        exact,
                        Objects.requireNonNull(
                                orderedPatchUpdates.get(index),
                                "ordered patch update")));
            }
        }
        ArrayList<GeneratedGeneralizationWrite> generalizationWrites =
                new ArrayList<GeneratedGeneralizationWrite>();
        for (ManagedGeneralizationWrite write : Objects.requireNonNull(
                generatedWrites, "generatedWrites")) {
            generalizationWrites.add(
                    GeneratedGeneralizationWrite.from(write));
        }
        return new DocumentTransitionEvidence(
                documentId,
                workOccurrenceIdentity,
                beforeDocumentBlueId,
                null,
                beforeEffectiveTypeBlueId,
                afterEffectiveTypeBlueId,
                contractPatches,
                generalizationWrites);
    }

    private DocumentTransitionEvidence(
            DocumentId documentId,
            String workOccurrenceIdentity,
            String beforeDocumentBlueId,
            String afterDocumentBlueId,
            String beforeEffectiveTypeBlueId,
            String afterEffectiveTypeBlueId,
            List<AuthoredContractPatch> authoredContractPatches,
            List<GeneratedGeneralizationWrite>
                    generatedGeneralizationWrites) {
        this.documentId = Objects.requireNonNull(documentId, "documentId");
        this.workOccurrenceIdentity = ClosureValueSupport
                .requireSha256Identity(
                        workOccurrenceIdentity,
                        "workOccurrenceIdentity");
        this.beforeDocumentBlueId = ClosureValueSupport.requireBlueId(
                beforeDocumentBlueId, "beforeDocumentBlueId");
        this.afterDocumentBlueId = afterDocumentBlueId == null
                ? null
                : ClosureValueSupport.requireBlueId(
                        afterDocumentBlueId, "afterDocumentBlueId");
        this.beforeEffectiveTypeBlueId = optionalBlueId(
                beforeEffectiveTypeBlueId, "beforeEffectiveTypeBlueId");
        this.afterEffectiveTypeBlueId = optionalBlueId(
                afterEffectiveTypeBlueId, "afterEffectiveTypeBlueId");
        this.authoredContractPatches = immutable(
                authoredContractPatches, "authoredContractPatch");
        this.generatedGeneralizationWrites = immutable(
                generatedGeneralizationWrites,
                "generatedGeneralizationWrite");
    }

    /**
     * Returns the managed lineage whose isolated step produced this evidence.
     *
     * @return managed document lineage
     */
    public DocumentId documentId() {
        return documentId;
    }

    /**
     * Returns the exact closure work occurrence that owns this evidence.
     *
     * @return SHA-256 work occurrence identity
     */
    public String workOccurrenceIdentity() {
        return workOccurrenceIdentity;
    }

    /**
     * Returns the exact document identity at step admission.
     *
     * @return plain BlueId before the step
     */
    public String beforeDocumentBlueId() {
        return beforeDocumentBlueId;
    }

    /**
     * Returns the exact finalized document identity after this step.
     *
     * @return plain BlueId after the step
     */
    public String afterDocumentBlueId() {
        if (afterDocumentBlueId == null) {
            throw new IllegalStateException(
                    "Transition evidence has not crossed finalization");
        }
        return afterDocumentBlueId;
    }

    /**
     * Returns the admitted effective Root type, when one was present.
     *
     * @return optional admitted effective type BlueId
     */
    public Optional<String> beforeEffectiveTypeBlueId() {
        return Optional.ofNullable(beforeEffectiveTypeBlueId);
    }

    /**
     * Returns the resulting effective Root type, when one was present.
     *
     * @return optional resulting effective type BlueId
     */
    public Optional<String> afterEffectiveTypeBlueId() {
        return Optional.ofNullable(afterEffectiveTypeBlueId);
    }

    /**
     * Returns exact authored patches at {@code /contracts} or below it.
     *
     * @return immutable contract-surface patches in authored order
     */
    public List<AuthoredContractPatch> authoredContractPatches() {
        return authoredContractPatches;
    }

    /**
     * Returns processor-generated type metadata writes in commit order.
     *
     * @return immutable generated write evidence
     */
    public List<GeneratedGeneralizationWrite>
            generatedGeneralizationWrites() {
        return generatedGeneralizationWrites;
    }

    DocumentTransitionEvidence finalizedWith(String exactAfterBlueId) {
        if (afterDocumentBlueId != null) {
            throw new IllegalStateException(
                    "Transition evidence was already finalized");
        }
        return new DocumentTransitionEvidence(
                documentId,
                workOccurrenceIdentity,
                beforeDocumentBlueId,
                exactAfterBlueId,
                beforeEffectiveTypeBlueId,
                afterEffectiveTypeBlueId,
                authoredContractPatches,
                generatedGeneralizationWrites);
    }

    /** One exact authored contract-path patch applied by the managed step. */
    public static final class AuthoredContractPatch {
        private final Operation operation;
        private final String path;
        private final String authoredValueBlueId;
        private final String beforeValueBlueId;
        private final String afterValueBlueId;

        private AuthoredContractPatch(
                Operation operation,
                String path,
                String authoredValueBlueId,
                String beforeValueBlueId,
                String afterValueBlueId) {
            this.operation = Objects.requireNonNull(operation, "operation");
            this.path = PointerUtils.assertValidRuntimePointer(path);
            this.authoredValueBlueId = optionalBlueId(
                    authoredValueBlueId, "authoredValueBlueId");
            this.beforeValueBlueId = optionalBlueId(
                    beforeValueBlueId, "beforeValueBlueId");
            this.afterValueBlueId = optionalBlueId(
                    afterValueBlueId, "afterValueBlueId");
            if ((operation == Operation.REMOVE)
                    != (authoredValueBlueId == null)) {
                throw new IllegalArgumentException(
                        "Only REMOVE contract patches omit authoredValueBlueId");
            }
            if (operation == Operation.ADD && afterValueBlueId == null) {
                throw new IllegalArgumentException(
                        "ADD contract patches require afterValueBlueId");
            }
            if (operation == Operation.REPLACE
                    && (beforeValueBlueId == null
                            || afterValueBlueId == null)) {
                throw new IllegalArgumentException(
                        "REPLACE contract patches require before and after values");
            }
            if (operation == Operation.REMOVE
                    && (beforeValueBlueId == null
                            || afterValueBlueId != null)) {
                throw new IllegalArgumentException(
                        "REMOVE contract patches require only a before value");
            }
        }

        private static AuthoredContractPatch from(
                FrozenJsonPatch patch,
                DocumentUpdateOccurrence update) {
            JsonPatch.Op op = patch.getOp();
            return new AuthoredContractPatch(
                    Operation.valueOf(op.name()),
                    patch.parsedPath().pointer(),
                    patch.getValue() == null
                            ? null : patch.getValue().blueId(),
                    valueBlueId(update.before()),
                    valueBlueId(update.after()));
        }

        /**
         * Returns the exact authored JSON Patch operation.
         *
         * @return authored operation
         */
        public Operation operation() {
            return operation;
        }

        /**
         * Returns the canonical absolute patch path.
         *
         * @return canonical absolute JSON Pointer
         */
        public String path() {
            return path;
        }

        /**
         * Returns the exact authored patch-value BlueId, absent for remove.
         *
         * @return optional authored value identity
         */
        public Optional<String> authoredValueBlueId() {
            return Optional.ofNullable(authoredValueBlueId);
        }

        /**
         * Returns the exact pre-patch value identity, when the path existed.
         *
         * @return optional exact before-value identity
         */
        public Optional<String> beforeValueBlueId() {
            return Optional.ofNullable(beforeValueBlueId);
        }

        /**
         * Returns the exact post-patch value identity, when the path remains.
         *
         * @return optional exact after-value identity
         */
        public Optional<String> afterValueBlueId() {
            return Optional.ofNullable(afterValueBlueId);
        }
    }

    /** Stable authored JSON Patch operations. */
    public enum Operation {
        /** Adds a previously absent path value. */
        ADD,
        /** Replaces a previously present path value. */
        REPLACE,
        /** Removes a previously present path value. */
        REMOVE
    }

    /** One exact processor-generated type-metadata write. */
    public static final class GeneratedGeneralizationWrite {
        private final String path;
        private final String valueBlueId;
        private final int requiringPatchIndex;

        private GeneratedGeneralizationWrite(
                String path,
                String valueBlueId,
                int requiringPatchIndex) {
            this.path = PointerUtils.assertValidRuntimePointer(path);
            this.valueBlueId = optionalBlueId(
                    Objects.requireNonNull(valueBlueId, "valueBlueId"),
                    "valueBlueId");
            if (requiringPatchIndex < 0) {
                throw new IllegalArgumentException(
                        "requiringPatchIndex must be non-negative");
            }
            this.requiringPatchIndex = requiringPatchIndex;
        }

        private static GeneratedGeneralizationWrite from(
                ManagedGeneralizationWrite write) {
            ManagedGeneralizationWrite exact = Objects.requireNonNull(
                    write, "generatedGeneralizationWrite");
            return new GeneratedGeneralizationWrite(
                    exact.path(),
                    exact.valueBlueId(),
                    exact.requiringPatchIndex());
        }

        /**
         * Returns the exact generated metadata path.
         *
         * @return canonical absolute JSON Pointer
         */
        public String path() {
            return path;
        }

        /**
         * Returns the exact generated metadata value BlueId.
         *
         * @return plain BlueId of the committed value
         */
        public String valueBlueId() {
            return valueBlueId;
        }

        /**
         * Returns the authored patch index that required this write.
         *
         * @return zero-based authored patch index
         */
        public int requiringPatchIndex() {
            return requiringPatchIndex;
        }
    }

    private static String optionalBlueId(String value, String label) {
        return value == null ? null : BlueIds.requirePlainBlueId(
                value, "/" + label);
    }

    private static String valueBlueId(Node value) {
        return value == null
                ? null
                : FrozenNode.fromResolvedNode(value).blueId();
    }

    private static <T> List<T> immutable(List<T> values, String label) {
        ArrayList<T> copy = new ArrayList<T>();
        for (T value : Objects.requireNonNull(values, label + "s")) {
            copy.add(Objects.requireNonNull(value, label));
        }
        return Collections.unmodifiableList(copy);
    }
}
