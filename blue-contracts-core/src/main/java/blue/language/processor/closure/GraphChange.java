package blue.language.processor.closure;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** One closed ADD, REMOVE, or atomic REBIND graph receipt. */
public final class GraphChange {

    /** Closed graph-change discriminator. */
    public enum Kind {
        /** One active occurrence was added. */
        ADD,
        /** One active occurrence was removed. */
        REMOVE,
        /** An active occurrence was rebound within or across target lineages. */
        REBIND
    }

    private final long graphChangeOrdinal;
    private final Kind changeKind;
    private final DocumentId sourceDocumentId;
    private final String sourcePath;
    private final Side before;
    private final Side after;

    /**
     * Creates one closed graph-change receipt.
     *
     * @param graphChangeOrdinal contiguous canonical ordinal
     * @param changeKind closed change kind
     * @param sourceDocumentId containing managed document
     * @param sourcePath exact authored occurrence path
     * @param before complete before side, or {@code null}
     * @param after complete after side, or {@code null}
     */
    public GraphChange(
            long graphChangeOrdinal,
            Kind changeKind,
            DocumentId sourceDocumentId,
            String sourcePath,
            Side before,
            Side after) {
        this.graphChangeOrdinal = ClosureValueSupport.requireSafeInteger(
                graphChangeOrdinal, "graphChangeOrdinal");
        this.changeKind = Objects.requireNonNull(changeKind, "changeKind");
        this.sourceDocumentId = Objects.requireNonNull(
                sourceDocumentId, "sourceDocumentId");
        this.sourcePath = ClosureValueSupport.requireAbsolutePointer(
                sourcePath, "sourcePath");
        this.before = before;
        this.after = after;
        validateShape();
    }

    /**
     * Returns contiguous canonical graph-change ordinal.
     *
     * @return contiguous canonical graph-change ordinal
     */
    public long graphChangeOrdinal() {
        return graphChangeOrdinal;
    }

    /**
     * Returns closed graph-change kind.
     *
     * @return closed graph-change kind
     */
    public Kind changeKind() {
        return changeKind;
    }

    /**
     * Returns containing managed document.
     *
     * @return containing managed document
     */
    public DocumentId sourceDocumentId() {
        return sourceDocumentId;
    }

    /**
     * Returns normalized absolute authored path.
     *
     * @return normalized absolute authored path
     */
    public String sourcePath() {
        return sourcePath;
    }

    /**
     * Returns complete before side, or {@code null}.
     *
     * @return complete before side, or {@code null}
     */
    public Side before() {
        return before;
    }

    /**
     * Returns complete after side, or {@code null}.
     *
     * @return complete after side, or {@code null}
     */
    public Side after() {
        return after;
    }

    /**
     * Returns before activation generation, or {@code null}.
     *
     * @return before activation generation, or {@code null}
     */
    public Long beforeActivationGeneration() {
        return before == null ? null : Long.valueOf(before.activationGeneration());
    }

    /**
     * Returns before occurrence identity, or {@code null}.
     *
     * @return before occurrence identity, or {@code null}
     */
    public String beforeOccurrenceIdentity() {
        return before == null ? null : before.occurrenceIdentity();
    }

    /**
     * Returns before binding identity, or {@code null}.
     *
     * @return before binding identity, or {@code null}
     */
    public String beforeBindingIdentity() {
        return before == null ? null : before.bindingIdentity();
    }

    /**
     * Returns before target lineage, or {@code null}.
     *
     * @return before target lineage, or {@code null}
     */
    public DocumentId beforeTargetDocumentId() {
        return before == null ? null : before.targetDocumentId();
    }

    /**
     * Returns before target BlueId, or {@code null}.
     *
     * @return before target BlueId, or {@code null}
     */
    public String beforeTargetBlueId() {
        return before == null ? null : before.targetBlueId();
    }

    /**
     * Returns after activation generation, or {@code null}.
     *
     * @return after activation generation, or {@code null}
     */
    public Long afterActivationGeneration() {
        return after == null ? null : Long.valueOf(after.activationGeneration());
    }

    /**
     * Returns after occurrence identity, or {@code null}.
     *
     * @return after occurrence identity, or {@code null}
     */
    public String afterOccurrenceIdentity() {
        return after == null ? null : after.occurrenceIdentity();
    }

    /**
     * Returns after binding identity, or {@code null}.
     *
     * @return after binding identity, or {@code null}
     */
    public String afterBindingIdentity() {
        return after == null ? null : after.bindingIdentity();
    }

    /**
     * Returns after target lineage, or {@code null}.
     *
     * @return after target lineage, or {@code null}
     */
    public DocumentId afterTargetDocumentId() {
        return after == null ? null : after.targetDocumentId();
    }

    /**
     * Returns after target BlueId, or {@code null}.
     *
     * @return after target BlueId, or {@code null}
     */
    public String afterTargetBlueId() {
        return after == null ? null : after.targetBlueId();
    }

    Map<String, Object> identityValue() {
        LinkedHashMap<String, Object> value =
                new LinkedHashMap<String, Object>();
        value.put("graphChangeOrdinal", Long.valueOf(graphChangeOrdinal));
        value.put("changeKind", changeKind.name());
        value.put("sourceDocumentId", sourceDocumentId.value());
        value.put("sourcePath", sourcePath);
        value.put("beforeActivationGeneration", beforeActivationGeneration());
        value.put("beforeOccurrenceIdentity", beforeOccurrenceIdentity());
        value.put("beforeBindingIdentity", beforeBindingIdentity());
        value.put("beforeTargetDocumentId", before == null
                ? null : before.targetDocumentId().value());
        value.put("beforeTargetBlueId", beforeTargetBlueId());
        value.put("afterActivationGeneration", afterActivationGeneration());
        value.put("afterOccurrenceIdentity", afterOccurrenceIdentity());
        value.put("afterBindingIdentity", afterBindingIdentity());
        value.put("afterTargetDocumentId", after == null
                ? null : after.targetDocumentId().value());
        value.put("afterTargetBlueId", afterTargetBlueId());
        return value;
    }

    private void validateShape() {
        if ((changeKind == Kind.ADD && (before != null || after == null))
                || (changeKind == Kind.REMOVE
                && (before == null || after != null))
                || (changeKind == Kind.REBIND
                && (before == null || after == null))) {
            throw new IllegalArgumentException(
                    "Graph-change kind disagrees with its closed sides");
        }
        if (changeKind != Kind.REBIND) {
            return;
        }
        boolean sameOccurrence = before.occurrenceIdentity().equals(
                after.occurrenceIdentity());
        boolean sameTarget = before.targetDocumentId().equals(
                after.targetDocumentId());
        if (sameOccurrence) {
            if (before.activationGeneration()
                    != after.activationGeneration() || !sameTarget) {
                throw new IllegalArgumentException(
                        "Same-lineage REBIND must preserve occurrence generation and target lineage");
            }
            if (before.bindingIdentity().equals(after.bindingIdentity())
                    && before.targetBlueId().equals(after.targetBlueId())) {
                throw new IllegalArgumentException("REBIND cannot be a no-op");
            }
            return;
        }
        if (sameTarget
                || before.bindingIdentity().equals(after.bindingIdentity())
                || before.activationGeneration()
                == ClosureValueSupport.MAX_SAFE_INTEGER
                || after.activationGeneration()
                != before.activationGeneration() + 1L) {
            throw new IllegalArgumentException(
                    "Different-lineage REBIND must retarget with fresh occurrence and binding identities at the next activation generation");
        }
    }

    /** Complete non-null side of a graph change. */
    public static final class Side {
        private final long activationGeneration;
        private final String occurrenceIdentity;
        private final String bindingIdentity;
        private final DocumentId targetDocumentId;
        private final String targetBlueId;

        /**
         * Creates one complete graph-change side.
         *
         * @param activationGeneration positive occurrence generation
         * @param occurrenceIdentity stable occurrence identity
         * @param bindingIdentity exact state-specific binding identity
         * @param targetDocumentId stable target lineage
         * @param targetBlueId exact target state
         */
        public Side(
                long activationGeneration,
                String occurrenceIdentity,
                String bindingIdentity,
                DocumentId targetDocumentId,
                String targetBlueId) {
            this.activationGeneration =
                    ClosureValueSupport.requirePositiveSafeInteger(
                            activationGeneration, "activationGeneration");
            this.occurrenceIdentity =
                    ClosureValueSupport.requireSha256Identity(
                            occurrenceIdentity, "occurrenceIdentity");
            this.bindingIdentity = ClosureValueSupport.requireSha256Identity(
                    bindingIdentity, "bindingIdentity");
            this.targetDocumentId = Objects.requireNonNull(
                    targetDocumentId, "targetDocumentId");
            this.targetBlueId = ClosureValueSupport.requireBlueId(
                    targetBlueId, "targetBlueId");
        }

        /**
         * Returns positive activation generation.
         *
         * @return positive activation generation
         */
        public long activationGeneration() {
            return activationGeneration;
        }

        /**
         * Returns stable occurrence identity.
         *
         * @return stable occurrence identity
         */
        public String occurrenceIdentity() {
            return occurrenceIdentity;
        }

        /**
         * Returns exact binding identity.
         *
         * @return exact binding identity
         */
        public String bindingIdentity() {
            return bindingIdentity;
        }

        /**
         * Returns stable target lineage.
         *
         * @return stable target lineage
         */
        public DocumentId targetDocumentId() {
            return targetDocumentId;
        }

        /**
         * Returns exact target BlueId.
         *
         * @return exact target BlueId
         */
        public String targetBlueId() {
            return targetBlueId;
        }
    }
}
