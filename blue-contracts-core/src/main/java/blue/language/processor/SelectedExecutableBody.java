package blue.language.processor;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.FrozenNode;

import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
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
    private final boolean materializedFromReference;
    private final Function<FrozenNode, FrozenNode> materializer;
    private final BiFunction<SelectedExecutableBody, List<JsonPatch>,
            List<JsonPatch>> resolvedPatchAdmission;
    private final BiFunction<SelectedExecutableBody, Node, Node>
            resolvedEventAdmission;
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
            boolean materializedFromReference,
            Function<FrozenNode, FrozenNode> materializer,
            BiFunction<SelectedExecutableBody, List<JsonPatch>,
                    List<JsonPatch>> resolvedPatchAdmission,
            BiFunction<SelectedExecutableBody, Node, Node>
                    resolvedEventAdmission,
            BooleanSupplier contextOpen,
            GasSchedule schedule) {
        this.field = requireText(field, "field");
        this.bodyBlueId =
                requireText(bodyBlueId, "bodyBlueId");
        this.body = Objects.requireNonNull(body, "body");
        this.materializedFromReference = materializedFromReference;
        this.materializer =
                Objects.requireNonNull(
                        materializer, "materializer");
        this.resolvedPatchAdmission = Objects.requireNonNull(
                resolvedPatchAdmission, "resolvedPatchAdmission");
        this.resolvedEventAdmission = Objects.requireNonNull(
                resolvedEventAdmission, "resolvedEventAdmission");
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
     * Returns whether the selected body was opened from an exact pure
     * reference and is therefore resolver-completed rather than authored
     * inline Source.
     *
     * @return {@code true} only for a materialized referenced body
     * @throws IllegalStateException if the owning execution context is closed
     */
    public boolean wasMaterializedFromReference() {
        ensureOpen();
        return materializedFromReference;
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

    /**
     * Admits one patch produced from this resolver-completed selected body.
     *
     * <p>Resolver-expanded type positions are projected back to their exact
     * Source representation before buffering. This operation is available
     * only when the selected body itself was opened from a pure reference.</p>
     *
     * @param patch resolver-completed patch, or {@code null}
     * @return admitted Source patch, or {@code null} when no effect is admitted
     * @throws IllegalArgumentException if this body was authored inline
     * @throws IllegalStateException if the owning context is closed or exact
     *         type evidence is unavailable
     */
    public JsonPatch applyResolvedPatch(JsonPatch patch) {
        ensureOpen();
        if (patch == null) {
            return null;
        }
        List<JsonPatch> accepted = applyResolvedPatches(
                Collections.singletonList(patch));
        return accepted.isEmpty() ? null : accepted.get(0);
    }

    /**
     * Admits an ordered patch batch produced from this resolver-completed
     * selected body.
     *
     * @param patches resolver-completed ordered patches
     * @return immutable Source patches accepted by the owning invocation
     * @throws IllegalArgumentException if this body was authored inline
     * @throws IllegalStateException if the owning context is closed or exact
     *         type evidence is unavailable
     */
    public List<JsonPatch> applyResolvedPatches(List<JsonPatch> patches) {
        ensureOpen();
        return resolvedPatchAdmission.apply(this, patches);
    }

    /**
     * Admits an event produced from this resolver-completed selected body.
     *
     * @param event resolver-completed application event
     * @return admitted Source event, or {@code null} when scope work stopped
     * @throws IllegalArgumentException if this body was authored inline
     * @throws IllegalStateException if the owning context is closed or exact
     *         type evidence is unavailable
     */
    public Node emitResolvedEvent(Node event) {
        ensureOpen();
        return resolvedEventAdmission.apply(this, event);
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
        if (schema.isReferenceOnly()) {
            references.add(schema.getBlueId());
            return;
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
