package blue.language.processor.closure;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;

import java.util.Objects;
import java.util.Optional;

/**
 * Exact managed-occurrence lineage evidence required before a noncommitting
 * closure attempt can be retried.
 */
public final class ManagedOccurrenceEvidenceDemand
        extends ClosureResourceDemand {

    private static final ClosureIdentityService IDENTITIES =
            ClosureIdentityService.INSTANCE;

    private final String logicalCauseIdentity;
    private final String inputClosureIdentity;
    private final long inputGraphGeneration;
    private final String processEmbeddedDeclarationIdentity;
    private final long demandOrdinal;
    private final Node suppliedExactValue;

    /**
     * Creates and verifies one managed-occurrence evidence demand.
     *
     * @param demandIdentity asserted canonical demand identity
     * @param logicalCauseIdentity exact logical processing-cause identity
     * @param inputClosureIdentity exact unchanged input-closure identity
     * @param inputGraphGeneration unchanged input graph generation
     * @param sourceDocumentId containing managed-document lineage
     * @param sourcePath absolute effective occurrence path
     * @param processEmbeddedDeclarationIdentity exact declaration contribution
     * @param suppliedValueBlueId exact supplied embedded value BlueId
     * @param demandOrdinal deterministic same-boundary ordinal
     */
    public ManagedOccurrenceEvidenceDemand(
            String demandIdentity,
            String logicalCauseIdentity,
            String inputClosureIdentity,
            long inputGraphGeneration,
            DocumentId sourceDocumentId,
            String sourcePath,
            String processEmbeddedDeclarationIdentity,
            String suppliedValueBlueId,
            long demandOrdinal) {
        this(
                demandIdentity,
                logicalCauseIdentity,
                inputClosureIdentity,
                inputGraphGeneration,
                sourceDocumentId,
                sourcePath,
                processEmbeddedDeclarationIdentity,
                suppliedValueBlueId,
                demandOrdinal,
                null,
                false);
    }

    /**
     * Creates and verifies one managed-occurrence evidence demand carrying
     * the exact inline value observed by the noncommitting attempt.
     *
     * <p>The value must be a complete simple-wire value whose direct BlueId
     * equals {@code suppliedValueBlueId}. It is cloned on input and output.
     * The retained value is defensive resource evidence only and does not
     * contribute to demand identity or ordering, which remain bound to its
     * verified exact BlueId.</p>
     *
     * @param demandIdentity asserted canonical demand identity
     * @param logicalCauseIdentity exact logical processing-cause identity
     * @param inputClosureIdentity exact unchanged input-closure identity
     * @param inputGraphGeneration unchanged input graph generation
     * @param sourceDocumentId containing managed-document lineage
     * @param sourcePath absolute effective occurrence path
     * @param processEmbeddedDeclarationIdentity exact declaration contribution
     * @param suppliedValueBlueId exact supplied embedded value BlueId
     * @param demandOrdinal deterministic same-boundary ordinal
     * @param suppliedExactValue complete exact inline supplied value
     */
    public ManagedOccurrenceEvidenceDemand(
            String demandIdentity,
            String logicalCauseIdentity,
            String inputClosureIdentity,
            long inputGraphGeneration,
            DocumentId sourceDocumentId,
            String sourcePath,
            String processEmbeddedDeclarationIdentity,
            String suppliedValueBlueId,
            long demandOrdinal,
            Node suppliedExactValue) {
        this(
                demandIdentity,
                logicalCauseIdentity,
                inputClosureIdentity,
                inputGraphGeneration,
                sourceDocumentId,
                sourcePath,
                processEmbeddedDeclarationIdentity,
                suppliedValueBlueId,
                demandOrdinal,
                Objects.requireNonNull(
                        suppliedExactValue, "suppliedExactValue"),
                true);
    }

    private ManagedOccurrenceEvidenceDemand(
            String demandIdentity,
            String logicalCauseIdentity,
            String inputClosureIdentity,
            long inputGraphGeneration,
            DocumentId sourceDocumentId,
            String sourcePath,
            String processEmbeddedDeclarationIdentity,
            String suppliedValueBlueId,
            long demandOrdinal,
            Node suppliedExactValue,
            boolean exactValuePresent) {
        super(Kind.MANAGED_OCCURRENCE_EVIDENCE,
                demandIdentity,
                sourceDocumentId,
                sourcePath,
                suppliedValueBlueId,
                demandOrdinal);
        this.logicalCauseIdentity =
                ClosureValueSupport.requireSha256Identity(
                        logicalCauseIdentity, "logicalCauseIdentity");
        this.inputClosureIdentity =
                ClosureValueSupport.requireSha256Identity(
                        inputClosureIdentity, "inputClosureIdentity");
        this.inputGraphGeneration = ClosureValueSupport.requireSafeInteger(
                inputGraphGeneration, "inputGraphGeneration");
        this.processEmbeddedDeclarationIdentity =
                ClosureValueSupport.requireBlueId(
                        processEmbeddedDeclarationIdentity,
                        "processEmbeddedDeclarationIdentity");
        this.demandOrdinal = ClosureValueSupport.requireSafeInteger(
                demandOrdinal, "demandOrdinal");
        this.suppliedExactValue = exactValuePresent
                ? verifiedExactInlineValue(
                        suppliedExactValue, suppliedValueBlueId())
                : null;
        String computed = IDENTITIES.closureResourceDemandIdentity(
                Kind.MANAGED_OCCURRENCE_EVIDENCE,
                this.logicalCauseIdentity,
                this.inputClosureIdentity,
                Long.valueOf(this.inputGraphGeneration),
                Objects.requireNonNull(sourceDocumentId,
                        "sourceDocumentId"),
                sourcePath(),
                this.processEmbeddedDeclarationIdentity,
                suppliedValueBlueId(),
                Long.valueOf(this.demandOrdinal));
        if (!demandIdentity().equals(computed)) {
            throw new IllegalArgumentException(
                    "demandIdentity does not identify this occurrence demand");
        }
    }

    /**
     * Derives the canonical identity and creates one occurrence demand.
     *
     * @param logicalCauseIdentity exact logical processing-cause identity
     * @param inputClosureIdentity exact unchanged input-closure identity
     * @param inputGraphGeneration unchanged input graph generation
     * @param sourceDocumentId containing managed-document lineage
     * @param sourcePath absolute effective occurrence path
     * @param processEmbeddedDeclarationIdentity exact declaration contribution
     * @param suppliedValueBlueId exact supplied embedded value BlueId
     * @param demandOrdinal deterministic same-boundary ordinal
     * @return verified immutable demand
     */
    public static ManagedOccurrenceEvidenceDemand derived(
            String logicalCauseIdentity,
            String inputClosureIdentity,
            long inputGraphGeneration,
            DocumentId sourceDocumentId,
            String sourcePath,
            String processEmbeddedDeclarationIdentity,
            String suppliedValueBlueId,
            long demandOrdinal) {
        String cause = ClosureValueSupport.requireSha256Identity(
                logicalCauseIdentity, "logicalCauseIdentity");
        String closure = ClosureValueSupport.requireSha256Identity(
                inputClosureIdentity, "inputClosureIdentity");
        long generation = ClosureValueSupport.requireSafeInteger(
                inputGraphGeneration, "inputGraphGeneration");
        DocumentId source = Objects.requireNonNull(
                sourceDocumentId, "sourceDocumentId");
        String path = ClosureValueSupport.requireAbsolutePointer(
                sourcePath, "sourcePath");
        String declaration = ClosureValueSupport.requireBlueId(
                processEmbeddedDeclarationIdentity,
                "processEmbeddedDeclarationIdentity");
        String supplied = ClosureValueSupport.requireBlueId(
                suppliedValueBlueId, "suppliedValueBlueId");
        long ordinal = ClosureValueSupport.requireSafeInteger(
                demandOrdinal, "demandOrdinal");
        String identity = IDENTITIES.closureResourceDemandIdentity(
                Kind.MANAGED_OCCURRENCE_EVIDENCE,
                cause,
                closure,
                Long.valueOf(generation),
                source,
                path,
                declaration,
                supplied,
                Long.valueOf(ordinal));
        return new ManagedOccurrenceEvidenceDemand(
                identity,
                cause,
                closure,
                generation,
                source,
                path,
                declaration,
                supplied,
                ordinal);
    }

    /**
     * Derives one canonical occurrence demand and retains its verified exact
     * inline supplied value.
     *
     * <p>The exact value is cloned and validated as a complete simple wire
     * value. Its direct BlueId must equal {@code suppliedValueBlueId}. The
     * value does not alter canonical demand identity or ordering.</p>
     *
     * @param logicalCauseIdentity exact logical processing-cause identity
     * @param inputClosureIdentity exact unchanged input-closure identity
     * @param inputGraphGeneration unchanged input graph generation
     * @param sourceDocumentId containing managed-document lineage
     * @param sourcePath absolute effective occurrence path
     * @param processEmbeddedDeclarationIdentity exact declaration contribution
     * @param suppliedValueBlueId exact supplied embedded value BlueId
     * @param demandOrdinal deterministic same-boundary ordinal
     * @param suppliedExactValue complete exact inline supplied value
     * @return verified immutable demand with exact inline evidence
     */
    public static ManagedOccurrenceEvidenceDemand derived(
            String logicalCauseIdentity,
            String inputClosureIdentity,
            long inputGraphGeneration,
            DocumentId sourceDocumentId,
            String sourcePath,
            String processEmbeddedDeclarationIdentity,
            String suppliedValueBlueId,
            long demandOrdinal,
            Node suppliedExactValue) {
        ManagedOccurrenceEvidenceDemand withoutValue = derived(
                logicalCauseIdentity,
                inputClosureIdentity,
                inputGraphGeneration,
                sourceDocumentId,
                sourcePath,
                processEmbeddedDeclarationIdentity,
                suppliedValueBlueId,
                demandOrdinal);
        return new ManagedOccurrenceEvidenceDemand(
                withoutValue.demandIdentity(),
                withoutValue.logicalCauseIdentity(),
                withoutValue.inputClosureIdentity(),
                withoutValue.inputGraphGeneration(),
                withoutValue.sourceDocumentId(),
                withoutValue.sourcePath(),
                withoutValue.processEmbeddedDeclarationIdentity(),
                withoutValue.suppliedValueBlueId(),
                withoutValue.demandOrdinal(),
                suppliedExactValue);
    }

    /**
     * Returns the exact logical processing-cause identity.
     *
     * @return exact logical processing-cause identity
     */
    public String logicalCauseIdentity() {
        return logicalCauseIdentity;
    }

    /**
     * Returns the exact unchanged input-closure identity.
     *
     * @return exact unchanged input-closure identity
     */
    public String inputClosureIdentity() {
        return inputClosureIdentity;
    }

    /**
     * Returns the unchanged input graph generation.
     *
     * @return unchanged input graph generation
     */
    public long inputGraphGeneration() {
        return inputGraphGeneration;
    }

    /**
     * Returns the exact Process Embedded declaration contribution BlueId.
     *
     * @return exact Process Embedded declaration contribution BlueId
     */
    public String processEmbeddedDeclarationIdentity() {
        return processEmbeddedDeclarationIdentity;
    }

    /**
     * Returns the deterministic same-boundary demand ordinal.
     *
     * @return deterministic same-boundary demand ordinal
     */
    public long demandOrdinal() {
        return demandOrdinal;
    }

    /**
     * Returns a defensive clone of the exact inline supplied value, when the
     * noncommitting attempt observed one directly.
     *
     * <p>Pure references whose content is unavailable carry no inline value.
     * The supplied value BlueId remains available independently through
     * {@link #suppliedValueBlueId()}.</p>
     *
     * @return optional defensive exact inline value
     */
    public Optional<Node> suppliedExactValue() {
        return suppliedExactValue == null
                ? Optional.<Node>empty()
                : Optional.of(suppliedExactValue.clone());
    }

    private static Node verifiedExactInlineValue(
            Node suppliedExactValue,
            String suppliedValueBlueId) {
        Node copy = Objects.requireNonNull(
                suppliedExactValue, "suppliedExactValue").clone();
        if (copy.isReferenceOnly()) {
            throw new IllegalArgumentException(
                    "suppliedExactValue must be a complete inline value");
        }
        NodeWireForm.get(copy, NodeWireForm.Strategy.SIMPLE);
        String actualBlueId = DirectBlueIdCalculator.calculateBlueId(copy);
        if (!suppliedValueBlueId.equals(actualBlueId)) {
            throw new IllegalArgumentException(
                    "suppliedExactValue does not match suppliedValueBlueId");
        }
        return copy;
    }
}
