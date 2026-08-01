package blue.language.processor;

import blue.language.utils.Properties;

import blue.language.api.LanguageRuntimeAccess;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.processor.util.NodeCanonicalizer;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.BlueIds;

import java.math.BigInteger;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Invocation-owned admission boundary for transient hosted-runtime output.
 *
 * <p>The boundary normalizes through the configured Blue Language runtime,
 * preserves the active verified-provider boundary for references, accounts
 * for semantic construction once, and returns an immutable exact handle.
 * Exact handles can subsequently be carried without reconstructing their
 * content.</p>
 */
public final class SemanticOutputBoundary {

    private final RuntimeWorkSession workSession;
    private final LanguageRuntimeAccess languageRuntime;
    private final ProcessingSnapshotManager snapshotManager;
    private final SemanticGasMeter semantic;
    private final AdmissionMemo admissionMemo;
    private final Map<String, ExactBlueValue> admittedByIdentity;
    private final Map<FrozenNode.ResolvedStructuralKey, ExactBlueValue>
            admittedByCanonicalStructure;

    SemanticOutputBoundary(RuntimeWorkSession workSession,
                           LanguageRuntimeAccess languageRuntime,
                           ProcessingSnapshotManager snapshotManager,
                           SemanticGasMeter semantic) {
        this(
                workSession,
                languageRuntime,
                snapshotManager,
                semantic,
                new AdmissionMemo());
    }

    SemanticOutputBoundary(RuntimeWorkSession workSession,
                           LanguageRuntimeAccess languageRuntime,
                           ProcessingSnapshotManager snapshotManager,
                           SemanticGasMeter semantic,
                           AdmissionMemo admissionMemo) {
        this.workSession =
                Objects.requireNonNull(workSession, "workSession");
        this.languageRuntime = Objects.requireNonNull(
                languageRuntime, Properties.OBJECT_BLUE);
        this.snapshotManager = snapshotManager;
        this.semantic = Objects.requireNonNull(semantic, "semantic");
        this.admissionMemo =
                Objects.requireNonNull(
                        admissionMemo, "admissionMemo");
        this.admittedByIdentity =
                admissionMemo.admittedByIdentity;
        this.admittedByCanonicalStructure =
                admissionMemo.admittedByCanonicalStructure;
    }

    /**
     * Normalizes, validates, meters, and admits a mutable runtime output.
     *
     * @param output mutable runtime-authored value
     * @return immutable exact value owned by this invocation
     * @throws GasLimitExceededException if semantic construction exceeds the
     *         remaining portable gas budget
     * @throws ProcessorFailureException if the value is not valid exact Blue
     *         content
     */
    public synchronized ExactBlueValue admit(Node output) {
        try {
            ensureOpen();
            Node supplied =
                    Objects.requireNonNull(output, "output");
            if (supplied.isReferenceOnly()) {
                return admitReference(
                        FrozenNode.fromResolvedNode(
                                supplied.clone()));
            }
            if (supplied.getBlueId() != null) {
                throw new ProcessorFailureException(
                        ProcessorErrorCategory.InvalidProcessingDocument,
                        "Hosted runtime output is not valid exact Blue content");
            }
            FrozenNode exactInput =
                    FrozenNode.fromResolvedNode(
                            supplied.clone());
            FrozenNode.ResolvedStructuralKey
                    suppliedStructuralKey =
                    exactInput.resolvedStructuralKey();
            ExactBlueValue carried =
                    admittedByCanonicalStructure.get(
                            suppliedStructuralKey);
            if (carried != null) {
                return carried;
            }

            /*
             * Provider lookup, transfer, and exact-evidence verification are
             * acquisition work, not portable semantic work. Canonicalization
             * is therefore allowed to establish all required evidence before
             * the first live semantic construction charge. Missing evidence
             * suspends with no semantic prefix; found evidence enters the
             * single live admission path below.
             */
            final FrozenNode normalized;
            try {
                normalized =
                        FrozenNode.fromResolvedNode(
                                languageRuntime.canonicalize(
                                        exactInput.toNode()));
            } catch (ExecutionEvidenceUnavailableException ex) {
                throw ex;
            } catch (RuntimeException invalid) {
                throw new ProcessorFailureException(
                        ProcessorErrorCategory.InvalidProcessingDocument,
                        "Hosted runtime output is not valid exact Blue content",
                        invalid);
            }
            ExactBlueValue admitted =
                    admitNormalized(
                            normalized, null, true);
            admittedByCanonicalStructure.put(
                    suppliedStructuralKey, admitted);
            return admitted;
        } catch (GasLimitExceededException exhaustion) {
            workSession.recordSemanticRejectedCharge(
                    exhaustion);
            throw exhaustion;
        }
    }

    /**
     * Admits an immutable authored value. A pure reference is opened only
     * through the invocation's verified snapshot manager.
     *
     * @param output immutable authored value or pure exact reference
     * @return immutable exact value owned by this invocation
     * @throws ExecutionEvidenceUnavailableException if an exact referenced
     *         value is not currently available
     * @throws GasLimitExceededException if semantic construction exceeds the
     *         remaining portable gas budget
     */
    public synchronized ExactBlueValue admit(FrozenNode output) {
        try {
            ensureOpen();
            FrozenNode exact =
                    Objects.requireNonNull(output, "output");
            if (exact.isReferenceOnly()) {
                return admitReference(exact);
            }
            return admit(exact.toNode());
        } catch (GasLimitExceededException exhaustion) {
            workSession.recordSemanticRejectedCharge(
                    exhaustion);
            throw exhaustion;
        }
    }

    /**
     * Carries a processor-issued exact value without recursively rebuilding or
     * charging its content.
     *
     * @param output processor-issued exact value
     * @return invocation-owned exact value, re-admitted when the handle came
     *         from another invocation
     * @throws GasLimitExceededException if cross-invocation re-admission
     *         exceeds the remaining portable gas budget
     */
    public synchronized ExactBlueValue admit(ExactBlueValue output) {
        ensureOpen();
        ExactBlueValue exact =
                Objects.requireNonNull(output, "output");
        if (!exact.belongsTo(admissionMemo)) {
            /*
             * Exact handles are invocation capabilities.  Re-admit a handle
             * crossing an invocation boundary so ordinary values pay this
             * invocation's semantic work and cyclic members require this
             * invocation's complete-set proof.
             */
            return admit(exact.frozenValue());
        }
        ExactBlueValue existing =
                admittedByIdentity.get(exact.blueId());
        if (existing != null) {
            return existing;
        }
        if (!exact.frozenValue().isReferenceOnly()) {
            FrozenNode.ResolvedStructuralKey structuralKey =
                    exact.frozenValue()
                            .resolvedStructuralKey();
            ExactBlueValue sameStructure =
                    admittedByCanonicalStructure.get(
                            structuralKey);
            if (sameStructure != null
                    && !sameStructure.blueId().equals(
                            exact.blueId())) {
                throw new InvalidExecutionEvidenceException(
                        "Processor-issued exact values disagree on BlueId");
            }
            admittedByCanonicalStructure.put(
                    structuralKey, exact);
        }
        admittedByIdentity.put(exact.blueId(), exact);
        return exact;
    }

    private ExactBlueValue admitReference(FrozenNode reference) {
        String requestedBlueId =
                reference.getReferenceBlueId();
        ExactBlueValue existing =
                admittedByIdentity.get(requestedBlueId);
        if (existing != null) {
            return existing;
        }
        if (snapshotManager == null) {
            throw new ExecutionEvidenceUnavailableException(
                    "Hosted runtime output reference requires the active "
                            + "verified processing provider",
                    java.util.Collections.singleton(requestedBlueId));
        }
        boolean cyclicMember =
                BlueIds.hasCyclicMemberSeparator(requestedBlueId);
        /*
         * Materialization is exact-evidence acquisition and intentionally
         * precedes portable semantic admission. An unavailable provider must
         * remain a zero-gas suspension even when no semantic gas remains.
         */
        final FrozenNode materialized =
                snapshotManager.materializeVerifiedExactReference(
                        reference);
        if (materialized == null) {
            throw new InvalidExecutionEvidenceException(
                    "No exact provider content for hosted runtime output "
                            + requestedBlueId);
        }
        if (materialized.isReferenceOnly()) {
            throw new InvalidExecutionEvidenceException(
                    "Provider returned a reference instead of exact content for "
                            + requestedBlueId);
        }
        if (cyclicMember) {
            /*
             * materializeVerifiedExactReference has required the complete
             * cyclic-set proof. Keep the admitted value as its opaque member
             * edge: a member has no standalone ordinary identity input and
             * must not be recursively hashed or reconstructed here.
             */
            ExactBlueValue admitted =
                    new ExactBlueValue(
                            reference,
                            requestedBlueId,
                            admissionMemo);
            admittedByIdentity.put(requestedBlueId, admitted);
            return admitted;
        }
        /*
         * Re-freeze in deferred-identity mode.  Provider verification has
         * established that this is exact canonical content; the boundary must
         * still append its construction charges before independently
         * establishing the value's ordinary identity.
         */
        Node exactMaterialized =
                materialized.toNode();
        return admitNormalized(
                FrozenNode.fromResolvedNode(
                        exactMaterialized),
                requestedBlueId,
                true);
    }

    private ExactBlueValue admitNormalized(FrozenNode exact,
                                           String expectedBlueId,
                                           boolean charge) {
        FrozenNode.ResolvedStructuralKey structuralKey =
                exact.resolvedStructuralKey();
        ExactBlueValue existing =
                admittedByCanonicalStructure.get(
                        structuralKey);
        if (existing != null && !charge) {
            if (expectedBlueId != null
                    && !expectedBlueId.equals(
                            existing.blueId())) {
                throw new InvalidExecutionEvidenceException(
                        "Hosted runtime output provider BlueId mismatch: expected "
                                + expectedBlueId
                                + " but calculated "
                                + existing.blueId());
            }
            return existing;
        }
        if (charge) {
            GasChargeContext context =
                    GasChargeContext.reason(
                            "hosted-runtime-output");
            chargeConstruction(
                    exact,
                    context,
                    new IdentityHashMap<
                            FrozenNode, Boolean>());
        }
        /*
         * fromResolvedNode deliberately deferred this calculation.  All
         * construction, list-fold, and node-identity charges above are now
         * present before the exact canonical identity work begins.
         */
        FrozenNode canonical =
                FrozenNode.fromNode(exact.toNode());
        String blueId = canonical.blueId();
        if (expectedBlueId != null
                && !expectedBlueId.equals(blueId)) {
            throw new InvalidExecutionEvidenceException(
                    "Hosted runtime output provider BlueId mismatch: expected "
                            + expectedBlueId
                            + " but calculated "
                            + blueId);
        }
        existing = admittedByIdentity.get(blueId);
        if (existing != null) {
            admittedByCanonicalStructure.put(
                    structuralKey, existing);
            return existing;
        }
        ExactBlueValue admitted =
                new ExactBlueValue(
                        canonical,
                        blueId,
                        admissionMemo);
        admittedByIdentity.put(blueId, admitted);
        admittedByCanonicalStructure.put(
                structuralKey, admitted);
        return admitted;
    }

    private void chargeConstruction(
            FrozenNode node,
            GasChargeContext context,
            IdentityHashMap<FrozenNode, Boolean> visited) {
        if (node == null
                || node.isReferenceOnly()
                || visited.put(node, Boolean.TRUE) != null) {
            return;
        }
        enforceContainerLimit(node);
        /*
         * Admit the identity-establishment charge before any helper below can
         * request a child identity or build the direct identity input.
         */
        semantic.nodeIdentitiesEstablished(1L, context);

        chargeText(node.getName(), context, false);
        chargeText(node.getDescription(), context, false);
        chargeText(node.getMergePolicy(), context, false);
        chargeText(node.getPreviousBlueId(), context, false);
        Object value = node.getValue();
        if (value instanceof String) {
            chargeText(
                    (String) value, context, false);
        } else if (value instanceof BigInteger) {
            semantic.integerConstructed(
                    (BigInteger) value, context);
        }

        chargeConstruction(node.getType(), context, visited);
        chargeConstruction(node.getItemType(), context, visited);
        chargeConstruction(node.getKeyType(), context, visited);
        chargeConstruction(node.getValueType(), context, visited);
        chargeConstruction(node.getContracts(), context, visited);
        chargeConstruction(node.getBlue(), context, visited);
        chargeSchemaConstruction(
                node.getSchema(),
                context,
                visited);

        List<FrozenNode> items = node.getItems();
        if (items != null) {
            for (FrozenNode item : items) {
                chargeConstruction(item, context, visited);
            }
            semantic.fullListIdentity(items.size(), context);
        }
        Map<String, FrozenNode> properties =
                node.getProperties();
        if (properties != null) {
            for (Map.Entry<String, FrozenNode> property :
                    properties.entrySet()) {
                chargeText(
                        property.getKey(),
                        context,
                        true);
                chargeConstruction(
                        property.getValue(),
                        context,
                        visited);
            }
        }

        if (items == null) {
            semantic.objectMembersRebuilt(
                    directMemberCount(node), context);
            semantic.directIdentityInput(
                    NodeCanonicalizer
                            .directIdentityCanonicalSize(
                                    node.toNode()),
                    context);
        }
    }

    private void chargeSchemaConstruction(
            Schema schema,
            GasChargeContext context,
            IdentityHashMap<FrozenNode, Boolean> visited) {
        if (schema == null || schema.isReferenceOnly()) {
            return;
        }
        chargeSchemaNode(schema.getRequired(), context, visited);
        chargeSchemaNode(schema.getMinLength(), context, visited);
        chargeSchemaNode(schema.getMaxLength(), context, visited);
        chargeSchemaNode(schema.getMinimum(), context, visited);
        chargeSchemaNode(schema.getMaximum(), context, visited);
        chargeSchemaNode(
                schema.getExclusiveMinimum(),
                context,
                visited);
        chargeSchemaNode(
                schema.getExclusiveMaximum(),
                context,
                visited);
        chargeSchemaNode(schema.getMultipleOf(), context, visited);
        chargeSchemaNode(schema.getMinItems(), context, visited);
        chargeSchemaNode(schema.getMaxItems(), context, visited);
        chargeSchemaNode(schema.getUniqueItems(), context, visited);
        chargeSchemaNode(schema.getMinFields(), context, visited);
        chargeSchemaNode(schema.getMaxFields(), context, visited);
        List<Node> enumValues = schema.getEnum();
        if (enumValues != null) {
            for (Node enumValue : enumValues) {
                chargeSchemaNode(
                        enumValue, context, visited);
            }
            semantic.fullListIdentity(
                    enumValues.size(), context);
        }
    }

    private void chargeSchemaNode(
            Node node,
            GasChargeContext context,
            IdentityHashMap<FrozenNode, Boolean> visited) {
        if (node != null) {
            chargeConstruction(
                    FrozenNode.fromResolvedNode(node),
                    context,
                    visited);
        }
    }

    private void enforceContainerLimit(FrozenNode node) {
        long observed;
        String limitName;
        if (node.getItems() != null) {
            observed = node.getItems().size();
            limitName =
                    GasScheduleConstants.PortableLimit.DIRECT_LIST_ITEMS;
        } else {
            observed = directMemberCount(node);
            limitName =
                    GasScheduleConstants.PortableLimit.DIRECT_OBJECT_ENTRIES;
        }
        long limit =
                semantic.schedule().portableLimit(limitName);
        if (observed > limit) {
            throw new PortableLimitExceededException(
                    ProcessorErrorCategory.RuntimeLedgerLimitExceeded,
                    limitName,
                    observed,
                    limit);
        }
    }

    private void chargeText(
            String value,
            GasChargeContext context,
            boolean objectKey) {
        if (value == null) {
            return;
        }
        long inlineLimit =
                semantic.schedule()
                        .portableLimit(
                                GasScheduleConstants.PortableLimit.DIRECT_INLINE_IDENTITY_TEXT_CODE_POINTS);
        long keyLimit =
                objectKey
                        ? semantic.schedule()
                        .portableLimit(
                                GasScheduleConstants.PortableLimit.DIRECT_OBJECT_KEY_CODE_POINTS)
                        : Long.MAX_VALUE;
        /*
         * Code-point count is charge metadata. Contracts §13.3 requires the
         * aggregate §13.8 Text formula to remain one trace entry; after that
         * exact aggregate charge is admitted, identity construction may use
         * the Text.
         */
        long observed =
                value.codePointCount(
                        0, value.length());
        if (observed > inlineLimit) {
            throw new PortableLimitExceededException(
                    ProcessorErrorCategory
                            .RuntimeLedgerLimitExceeded,
                    GasScheduleConstants.PortableLimit.DIRECT_INLINE_IDENTITY_TEXT_CODE_POINTS,
                    observed,
                    inlineLimit);
        }
        if (observed > keyLimit) {
            throw new PortableLimitExceededException(
                    ProcessorErrorCategory
                            .RuntimeLedgerLimitExceeded,
                    GasScheduleConstants.PortableLimit.DIRECT_OBJECT_KEY_CODE_POINTS,
                    observed,
                    keyLimit);
        }
        semantic.textCodePointsConstructed(
                observed, context);
    }

    private static long directMemberCount(
            FrozenNode node) {
        long count =
                node.getProperties() != null
                        ? node.getProperties().size()
                        : 0L;
        if (node.getName() != null) count++;
        if (node.getDescription() != null) count++;
        if (node.getType() != null) count++;
        if (node.getItemType() != null) count++;
        if (node.getKeyType() != null) count++;
        if (node.getValueType() != null) count++;
        if (node.getValue() != null) count++;
        if (node.getSchema() != null) count++;
        if (node.getContracts() != null) count++;
        if (node.getBlue() != null) count++;
        if (node.getMergePolicy() != null) count++;
        if (node.getPreviousBlueId() != null) count++;
        if (node.getPosition() != null) count++;
        return count;
    }

    private void ensureOpen() {
        if (!workSession.acceptsWork()) {
            throw new IllegalStateException(
                    "Semantic output boundary is closed");
        }
    }

    synchronized SemanticOutputBoundary forkFor(
            RuntimeWorkSession session) {
        return new SemanticOutputBoundary(
                session,
                languageRuntime,
                snapshotManager,
                sessionSemanticMeter(session),
                new AdmissionMemo(admissionMemo));
    }

    synchronized void carryExactInput(
            Node input,
            String blueId) {
        admit(new ExactBlueValue(
                FrozenNode.fromResolvedNode(
                        Objects.requireNonNull(
                                input, "input")
                                .clone()),
                Objects.requireNonNull(
                        blueId, Properties.OBJECT_BLUE_ID),
                admissionMemo));
    }

    private static SemanticGasMeter sessionSemanticMeter(
            RuntimeWorkSession session) {
        return session.semanticMeter();
    }

    static final class AdmissionMemo {
        private final Map<String, ExactBlueValue>
                admittedByIdentity =
                new LinkedHashMap<>();
        private final Map<
                FrozenNode.ResolvedStructuralKey,
                ExactBlueValue>
                admittedByCanonicalStructure =
                new LinkedHashMap<>();

        AdmissionMemo() {
        }

        private AdmissionMemo(
                AdmissionMemo source) {
            admittedByIdentity.putAll(
                    source.admittedByIdentity);
            admittedByCanonicalStructure.putAll(
                    source.admittedByCanonicalStructure);
        }
    }
}
