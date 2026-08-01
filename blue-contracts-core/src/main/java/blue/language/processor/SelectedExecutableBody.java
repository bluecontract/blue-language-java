package blue.language.processor;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.snapshot.FrozenNode;

import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Narrow invocation-owned exact view of one selected executable body.
 *
 * <p>Only reference edges actually present in the selected body (or in exact
 * content reached from such an edge) can be opened. Every demand stays on the
 * active verified processing provider and the capability expires with its
 * processor execution context.</p>
 */
public final class SelectedExecutableBody {

    private final String field;
    private final String bodyBlueId;
    private final FrozenNode body;
    private final Function<FrozenNode, FrozenNode> materializer;
    private final BooleanSupplier contextOpen;
    private final long referenceLimit;
    private final Set<String> allowedReferences =
            new LinkedHashSet<>();
    private final Map<String, FrozenNode> materialized =
            new LinkedHashMap<>();

    SelectedExecutableBody(
            String field,
            String bodyBlueId,
            FrozenNode body,
            Function<FrozenNode, FrozenNode> materializer,
            BooleanSupplier contextOpen,
            GasSchedule schedule) {
        this.field = requireText(field, "field");
        this.bodyBlueId =
                requireText(bodyBlueId, "bodyBlueId");
        this.body = Objects.requireNonNull(body, "body");
        this.materializer =
                Objects.requireNonNull(
                        materializer, "materializer");
        this.contextOpen =
                Objects.requireNonNull(
                        contextOpen, "contextOpen");
        this.referenceLimit =
                Objects.requireNonNull(schedule, "schedule")
                        .portableLimit(
                                GasScheduleConstants.PortableLimit.RUNTIME_CHILD_LEDGER_COUNTER_KINDS);
        collectReferences(
                body,
                allowedReferences,
                new IdentityHashMap<FrozenNode, Boolean>());
        enforceReferenceLimit();
    }

    /**
     * Returns the contract field from which this body was selected.
     *
     * @return executable-body field name
     */
    public String field() {
        return field;
    }

    /**
     * Returns the exact identity retained for the selected body.
     *
     * @return body BlueId
     */
    public String bodyBlueId() {
        return bodyBlueId;
    }

    /**
     * Returns the immutable selected body while the capability is live.
     *
     * @return exact frozen body
     * @throws IllegalStateException if the owning execution context is closed
     */
    public FrozenNode exactBody() {
        ensureOpen();
        return body;
    }

    /**
     * Returns references currently reachable through the selected body.
     *
     * @return immutable copy of the allowed exact BlueIds
     * @throws IllegalStateException if the owning execution context is closed
     */
    public Set<String> availableReferenceBlueIds() {
        ensureOpen();
        return Collections.unmodifiableSet(
                new LinkedHashSet<>(
                        allowedReferences));
    }

    /**
     * Opens one exact reference reachable through the selected body.
     *
     * @param blueId exact allowed reference identity
     * @return immutable materialized content
     * @throws IllegalArgumentException if the identity is not reachable
     * @throws IllegalStateException if the owning execution context is closed
     */
    public synchronized FrozenNode materializeExactReference(
            String blueId) {
        return materializeExactReference(
                FrozenNode.fromNode(
                        new Node().blueId(
                                requireText(
                                        blueId, BlueLanguageConstants.OBJECT_BLUE_ID))));
    }

    /**
     * Opens one pure reference reachable through the selected body.
     *
     * <p>References discovered in the opened content join this capability's
     * finite allowed set. Repeated demands reuse the immutable result.</p>
     *
     * @param reference pure exact reference to open
     * @return immutable materialized content
     * @throws IllegalArgumentException if {@code reference} is not pure or is
     *         outside the selected body's reachable surface
     * @throws IllegalStateException if the owning execution context is closed
     */
    public synchronized FrozenNode materializeExactReference(
            FrozenNode reference) {
        ensureOpen();
        FrozenNode exactReference =
                Objects.requireNonNull(
                        reference, "reference");
        if (!exactReference.isReferenceOnly()) {
            throw new IllegalArgumentException(
                    "Selected-body materialization requires a pure exact reference");
        }
        String blueId =
                exactReference.getReferenceBlueId();
        if (!allowedReferences.contains(blueId)) {
            throw new IllegalArgumentException(
                    "Reference is outside the selected executable body: "
                            + blueId);
        }
        FrozenNode cached =
                materialized.get(blueId);
        if (cached != null) {
            return cached;
        }
        if (materialized.size() + 1L
                > referenceLimit) {
            throw new PortableLimitExceededException(
                    ProcessorErrorCategory.RuntimeLedgerLimitExceeded,
                    GasScheduleConstants.PortableLimit.RUNTIME_CHILD_LEDGER_COUNTER_KINDS,
                    materialized.size() + 1L,
                    referenceLimit);
        }
        FrozenNode opened =
                Objects.requireNonNull(
                        materializer.apply(
                                exactReference),
                        "materializedReference");
        if (opened.isReferenceOnly()) {
            throw new InvalidExecutionEvidenceException(
                    "Selected executable body provider returned an unresolved reference for "
                            + blueId);
        }
        Set<String> expandedReferences =
                new LinkedHashSet<>(
                        allowedReferences);
        collectReferences(
                opened,
                expandedReferences,
                new IdentityHashMap<FrozenNode, Boolean>());
        enforceReferenceLimit(
                expandedReferences.size());
        materialized.put(blueId, opened);
        allowedReferences.clear();
        allowedReferences.addAll(
                expandedReferences);
        return opened;
    }

    private void enforceReferenceLimit() {
        enforceReferenceLimit(
                allowedReferences.size());
    }

    private void enforceReferenceLimit(long observed) {
        if (observed > referenceLimit) {
            throw new PortableLimitExceededException(
                    ProcessorErrorCategory.RuntimeLedgerLimitExceeded,
                    GasScheduleConstants.PortableLimit.RUNTIME_CHILD_LEDGER_COUNTER_KINDS,
                    observed,
                    referenceLimit);
        }
    }

    private void ensureOpen() {
        if (!contextOpen.getAsBoolean()) {
            throw new IllegalStateException(
                    "Selected executable body capability is closed");
        }
    }

    private static void collectReferences(
            FrozenNode node,
            Set<String> references,
            IdentityHashMap<FrozenNode, Boolean> visited) {
        if (node == null
                || visited.put(node, Boolean.TRUE) != null) {
            return;
        }
        if (node.isReferenceOnly()) {
            references.add(
                    node.getReferenceBlueId());
            return;
        }
        collectReferences(node.getType(), references, visited);
        collectReferences(node.getItemType(), references, visited);
        collectReferences(node.getKeyType(), references, visited);
        collectReferences(node.getValueType(), references, visited);
        collectReferences(node.getContracts(), references, visited);
        collectReferences(node.getBlue(), references, visited);
        collectSchemaReferences(
                node.getSchema(),
                references,
                new IdentityHashMap<Node, Boolean>());
        if (node.getItems() != null) {
            for (FrozenNode item : node.getItems()) {
                collectReferences(
                        item, references, visited);
            }
        }
        if (node.getProperties() != null) {
            for (FrozenNode child :
                    node.getProperties().values()) {
                collectReferences(
                        child, references, visited);
            }
        }
    }

    private static void collectSchemaReferences(
            Schema schema,
            Set<String> references,
            IdentityHashMap<Node, Boolean> visited) {
        if (schema == null) {
            return;
        }
        if (schema.getBlueId() != null) {
            references.add(schema.getBlueId());
        }
        for (Node nested : Arrays.asList(
                schema.getRequired(),
                schema.getMinLength(),
                schema.getMaxLength(),
                schema.getMinimum(),
                schema.getMaximum(),
                schema.getExclusiveMinimum(),
                schema.getExclusiveMaximum(),
                schema.getMultipleOf(),
                schema.getMinItems(),
                schema.getMaxItems(),
                schema.getUniqueItems(),
                schema.getMinFields(),
                schema.getMaxFields())) {
            collectNodeReferences(
                    nested, references, visited);
        }
        if (schema.getEnum() != null) {
            for (Node enumValue : schema.getEnum()) {
                collectNodeReferences(
                        enumValue, references, visited);
            }
        }
    }

    private static void collectNodeReferences(
            Node node,
            Set<String> references,
            IdentityHashMap<Node, Boolean> visited) {
        if (node == null
                || visited.put(node, Boolean.TRUE) != null) {
            return;
        }
        if (node.isReferenceOnly()) {
            references.add(node.getBlueId());
            return;
        }
        collectNodeReferences(node.getType(), references, visited);
        collectNodeReferences(node.getItemType(), references, visited);
        collectNodeReferences(node.getKeyType(), references, visited);
        collectNodeReferences(node.getValueType(), references, visited);
        collectNodeReferences(node.getContracts(), references, visited);
        collectNodeReferences(node.getBlue(), references, visited);
        collectSchemaReferences(
                node.getSchema(), references, visited);
        if (node.getItems() != null) {
            for (Node item : node.getItems()) {
                collectNodeReferences(
                        item, references, visited);
            }
        }
        if (node.getProperties() != null) {
            for (Node child :
                    node.getProperties().values()) {
                collectNodeReferences(
                        child, references, visited);
            }
        }
    }

    private static String requireText(
            String value, String label) {
        String exact =
                Objects.requireNonNull(value, label);
        if (exact.isEmpty()) {
            throw new IllegalArgumentException(
                    label + " must be non-empty");
        }
        return exact;
    }
}
