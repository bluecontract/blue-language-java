package blue.language.processor;

import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.identity.BlueIds;
import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.matching.FrozenTypeMatcher;
import blue.language.merge.TypeEvidenceResolution;
import blue.language.model.Node;
import blue.language.model.wire.BlueLanguageConstants;
import blue.language.model.wire.JsonPointer;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.snapshot.FrozenNode;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Resolves and validates values selected by one Process Embedded plan.
 *
 * <p>This helper owns the exact-content and canonical-type evidence boundary.
 * It deliberately does not enumerate collections, order routes, or charge gas;
 * those remain planner responsibilities.</p>
 */
final class EmbeddedScopeValueResolver {

    private static final FrozenNode DICTIONARY_TYPE_REFERENCE =
            FrozenNode.fromResolvedNode(new Node().blueId(
                    BlueLanguageConstants.DICTIONARY_TYPE_BLUE_ID));

    private final EmbeddedScopePlanner.ExactReferenceMaterializer
            referenceMaterializer;
    private final CanonicalTypeIdentityLookup canonicalTypeIdentities;
    private final FrozenTypeMatcher typeMatcher;

    EmbeddedScopeValueResolver(
            EmbeddedScopePlanner.ExactReferenceMaterializer
                    referenceMaterializer,
            Function<FrozenNode, TypeEvidenceResolution>
                    verifiedTypeMaterializer,
            CanonicalTypeIdentityLookup canonicalTypeIdentities) {
        this.referenceMaterializer = referenceMaterializer;
        this.canonicalTypeIdentities = Objects.requireNonNull(
                canonicalTypeIdentities, "canonicalTypeIdentities");
        this.typeMatcher = FrozenTypeMatcher.withVerifiedTypeEvidence(
                Objects.requireNonNull(
                        verifiedTypeMaterializer,
                        "verifiedTypeMaterializer"),
                canonicalTypeIdentities);
    }

    SelectedValue select(
            FrozenNode scope,
            String declaration,
            ProcessorErrorCategory invalidPathCategory,
            String scopePath,
            GasSchedule schedule) {
        FrozenNode current = scope;
        boolean presentByReference = current.getReferenceBlueId() != null;
        boolean scopeRoot = true;
        for (String segment : JsonPointer.split(declaration)) {
            if (!scopeRoot
                    && !isSemanticallyPresent(
                            current, presentByReference)) {
                return SelectedValue.absent();
            }
            current = materialize(current, scopePath, declaration);
            boolean traversable = scopeRoot
                    ? isAdmittedScopeRootObject(current)
                    : isTraversalObject(
                            current,
                            declaration,
                            scopePath,
                            schedule);
            if (!traversable) {
                throw invalid(
                        invalidPathCategory,
                        "Process Embedded declaration cannot traverse a non-object: "
                                + declaration,
                        scopePath);
            }
            current = current.property(segment);
            if (current == null) {
                return SelectedValue.absent();
            }
            presentByReference = current.getReferenceBlueId() != null;
            scopeRoot = false;
        }
        return new SelectedValue(current, presentByReference);
    }

    FrozenNode materialize(
            FrozenNode node,
            String scopePath,
            String logicalPath) {
        if (node == null || !node.isReferenceOnly()) {
            return node;
        }
        rejectCyclicMember(node, scopePath, logicalPath, null);
        String blueId = node.getReferenceBlueId();
        if (referenceMaterializer == null) {
            throw new ExecutionEvidenceUnavailableException(
                    "Verified exact content is required for embedded path "
                            + logicalPath,
                    Collections.singletonList(blueId));
        }
        FrozenNode materialized = referenceMaterializer.materialize(node);
        if (materialized == null || materialized.isReferenceOnly()) {
            throw new InvalidExecutionEvidenceException(
                    "Verified exact content was not found for embedded path "
                            + logicalPath,
                    ProcessorErrorCategory.InvalidProcessingDocument);
        }
        return materialized;
    }

    void rejectCyclicMember(
            FrozenNode node,
            String scopePath,
            String declaration,
            String memberKey) {
        if (node == null || !node.isReferenceOnly()) {
            return;
        }
        String blueId = node.getReferenceBlueId();
        if (!BlueIds.hasCyclicMemberSeparator(blueId)) {
            return;
        }
        try {
            BlueIds.requireBlueIdOrCyclicMember(
                    blueId, "embedded collection member");
        } catch (IllegalArgumentException invalidIdentity) {
            throw new InvalidExecutionEvidenceException(
                    "Invalid cyclic-member identity at embedded path "
                            + declaration,
                    ProcessorErrorCategory.InvalidProcessingDocument);
        }
        String suffix = memberKey != null
                ? "/" + PointerUtils.escapeSegment(memberKey)
                : "";
        throw invalid(
                ProcessorErrorCategory
                        .CyclicSetEmbeddedBoundaryUnsupported,
                "Process Embedded cannot cross cyclic-set member boundary: "
                        + declaration + suffix,
                scopePath);
    }

    void requireOpaqueManagedTarget(
            FrozenNode target,
            String absolutePath,
            Map<String, String> expectedManagedBlueIdsByPath,
            String scopePath) {
        String expectedBlueId = expectedManagedBlueIdsByPath.get(
                absolutePath);
        if (expectedBlueId == null) {
            throw invalid(
                    ProcessorErrorCategory.SubscriptionSurfaceInvalid,
                    "Process Embedded selected a child without managed "
                            + "occurrence evidence: " + absolutePath,
                    scopePath);
        }
        if (target == null) {
            throw invalid(
                    ProcessorErrorCategory.InvalidProcessingDocument,
                    "Managed embedded occurrence is absent at "
                            + absolutePath,
                    scopePath);
        }
        if (!target.isReferenceOnly()
                && BlueIds.hasCyclicMemberSeparator(expectedBlueId)) {
            throw new InvalidExecutionEvidenceException(
                    "Materialized cyclic managed content requires complete "
                            + "owning cyclic-set proof at " + absolutePath,
                    ProcessorErrorCategory.InvalidProcessingDocument);
        }
        String actualBlueId = target.isReferenceOnly()
                ? target.getReferenceBlueId()
                : target.blueId();
        try {
            BlueIds.requireBlueIdOrCyclicMember(
                    actualBlueId,
                    "managed embedded occurrence");
        } catch (IllegalArgumentException invalidIdentity) {
            throw new InvalidExecutionEvidenceException(
                    "Invalid managed embedded identity at " + absolutePath,
                    ProcessorErrorCategory.InvalidProcessingDocument);
        }
        if (!expectedBlueId.equals(actualBlueId)) {
            throw new InvalidExecutionEvidenceException(
                    "Managed embedded reference disagrees with admitted "
                            + "occurrence evidence at " + absolutePath
                            + ": expected " + expectedBlueId
                            + " but found " + actualBlueId,
                    ProcessorErrorCategory.InvalidProcessingDocument);
        }
    }

    boolean isCollectionObject(
            FrozenNode node,
            String declaration,
            String scopePath,
            GasSchedule schedule) {
        if (node == null
                || node.getValue() != null
                || node.hasItems()
                || node.isReferenceOnly()
                || node.isPreviousOnly()) {
            return false;
        }
        if (!node.hasProperties()
                && node.getType() == null) {
            return false;
        }
        return hasObjectCompatibleDeclaredType(
                node, declaration, scopePath, schedule);
    }

    /**
     * Returns whether an already-admitted processing scope can be traversed
     * as an object container.
     *
     * <p>The processor admits the effective scope before invoking the planner.
     * Its application-specific nominal type therefore need not derive from the
     * Language Dictionary type. Descendants remain strictly checked so a
     * declaration cannot cross a list, reference shell, previous shell, or
     * scalar without a direct Contracts envelope.</p>
     */
    private boolean isAdmittedScopeRootObject(FrozenNode node) {
        if (node == null
                || node.hasItems()
                || node.isReferenceOnly()
                || node.isPreviousOnly()) {
            return false;
        }
        return node.getValue() == null || node.getContracts() != null;
    }

    /**
     * Verifies a selected value that will become an independently processed
     * embedded scope.
     *
     * <p>A non-empty resolved object envelope is positive value evidence even
     * when its application-specific type does not derive from Dictionary.
     * Exact empty values have no such structural proof, so their declared type
     * must still establish Dictionary compatibility.</p>
     */
    boolean isSelectedScopeRootObject(
            FrozenNode node,
            String declaration,
            String scopePath,
            GasSchedule schedule) {
        if (!isAdmittedScopeRootObject(node)) {
            return false;
        }
        if (node.getValue() != null || node.getContracts() != null) {
            return true;
        }
        Map<String, FrozenNode> properties = node.getProperties();
        if (properties != null && !properties.isEmpty()) {
            return true;
        }
        return hasObjectCompatibleDeclaredType(
                node, declaration, scopePath, schedule);
    }

    /**
     * A processing scope may carry a scalar payload when it also carries a
     * direct Contracts envelope. A bare scalar remains a non-object collection
     * member.
     */
    private boolean isTraversalObject(
            FrozenNode node,
            String declaration,
            String scopePath,
            GasSchedule schedule) {
        if (node == null
                || node.hasItems()
                || node.isReferenceOnly()
                || node.isPreviousOnly()) {
            return false;
        }
        if (node.getValue() != null) {
            return node.getContracts() != null;
        }
        if (!node.hasProperties()
                && node.getType() == null) {
            /* A Contracts-only admitted scope remains traversable. */
            return node.getContracts() != null;
        }
        return hasObjectCompatibleDeclaredType(
                node, declaration, scopePath, schedule);
    }

    private boolean hasObjectCompatibleDeclaredType(
            FrozenNode node,
            String declaration,
            String scopePath,
            GasSchedule schedule) {
        FrozenNode declaredType = node.getType();
        if (declaredType == null) {
            return true;
        }
        requireInlineTypeIdentityEvidence(
                declaredType, declaration, scopePath);
        try {
            return typeMatcher.isSubtypeOrSame(
                    declaredType,
                    DICTIONARY_TYPE_REFERENCE,
                    schedule.portableLimit(
                            GasScheduleConstants.PortableLimit
                                    .TYPE_CHAIN_EDGES));
        } catch (ExecutionEvidenceUnavailableException
                | InvalidExecutionEvidenceException failure) {
            throw failure;
        } catch (IllegalArgumentException | IllegalStateException failure) {
            throw invalidTypeEvidence(
                    declaration, scopePath, failure);
        }
    }

    private void requireInlineTypeIdentityEvidence(
            FrozenNode declaredType,
            String declaration,
            String scopePath) {
        if (declaredType.isReferenceOnly()) {
            return;
        }
        final boolean covered;
        try {
            covered = canonicalTypeIdentities.findCanonicalTypeBlueId(
                    declaredType.toNode()).isPresent();
        } catch (IllegalArgumentException | IllegalStateException failure) {
            throw invalidTypeEvidence(
                    declaration, scopePath, failure);
        }
        if (!covered) {
            throw new ExecutionEvidenceUnavailableException(
                    "Resolver-issued canonical type identity evidence is "
                            + "required for embedded object "
                            + logicalPath(scopePath, declaration));
        }
    }

    private InvalidExecutionEvidenceException invalidTypeEvidence(
            String declaration,
            String scopePath,
            RuntimeException failure) {
        String detail = failure.getMessage();
        return new InvalidExecutionEvidenceException(
                "Invalid embedded object type evidence at "
                        + logicalPath(scopePath, declaration)
                        + (detail != null && !detail.isEmpty()
                                ? ": " + detail
                                : ""),
                ProcessorErrorCategory.InvalidProcessingDocument);
    }

    /** Applies Language semantic presence before interpreting node kind. */
    boolean isSemanticallyPresent(
            FrozenNode node,
            boolean presentByReference) {
        return node != null
                && (presentByReference
                        || node.getReferenceBlueId() != null
                        || node.getValue() != null
                        || node.hasItems()
                        || node.hasProperties()
                        || node.getContracts() != null);
    }

    static TypeEvidenceResolution unavailableTypeEvidence(
            FrozenNode reference) {
        Objects.requireNonNull(reference, "reference");
        String blueId = reference.getReferenceBlueId();
        throw new ExecutionEvidenceUnavailableException(
                "Verified exact type evidence is required for embedded "
                        + "object type " + blueId,
                Collections.singletonList(blueId));
    }

    static TypeEvidenceResolution materializeVerifiedTypeEvidence(
            ProcessingSnapshotManager snapshotManager,
            FrozenNode reference) {
        String blueId = Objects.requireNonNull(
                reference, "reference").getReferenceBlueId();
        try {
            TypeEvidenceResolution materialization = snapshotManager
                    .materializeVerifiedTypeReference(reference);
            if (materialization == null
                    || materialization.resolvedRoot().isReferenceOnly()) {
                throw new ExecutionEvidenceUnavailableException(
                        "Verified exact type evidence is unavailable for "
                                + blueId,
                        Collections.singletonList(blueId));
            }
            return materialization;
        } catch (ExecutionEvidenceUnavailableException
                | InvalidExecutionEvidenceException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            if (BlueLanguageErrorClassifier.classify(failure)
                    == BlueLanguageErrorCategory.ProviderUnavailable
                    || failure instanceof UnsupportedOperationException) {
                throw new ExecutionEvidenceUnavailableException(
                        "Verified exact type evidence is unavailable for "
                                + blueId,
                        Collections.singletonList(blueId));
            }
            throw failure;
        }
    }

    private String logicalPath(String scopePath, String declaration) {
        try {
            return PointerUtils.resolvePointer(scopePath, declaration);
        } catch (RuntimeException ignored) {
            return declaration;
        }
    }

    private SubscriptionSurfaceInvalidException invalid(
            ProcessorErrorCategory category,
            String message,
            String scopePath) {
        return new SubscriptionSurfaceInvalidException(
                message,
                scopePath,
                ProcessorContractConstants.KEY_EMBEDDED,
                category);
    }

    /** Selected content plus source-surface presence retained across opening. */
    static final class SelectedValue {
        private final FrozenNode node;
        private final boolean presentByReference;

        private SelectedValue(
                FrozenNode node,
                boolean presentByReference) {
            this.node = node;
            this.presentByReference = presentByReference;
        }

        static SelectedValue absent() {
            return new SelectedValue(null, false);
        }

        FrozenNode node() {
            return node;
        }

        boolean presentByReference() {
            return presentByReference;
        }
    }
}
