package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.FrozenNode;

import java.util.List;
import java.util.Objects;

/**
 * Package-local compatibility view over an immutable document-update
 * occurrence.
 *
 * <p>The adapter owns only final references. It records operational
 * materialization telemetry when a detached mutable view is requested; the
 * exact occurrence remains immutable and telemetry cannot affect semantics.</p>
 */
class DocumentUpdateDataAdapter {

    private final DocumentUpdateOccurrence occurrence;
    private final UpdateMaterializationMetrics
            materializationMetrics;

    DocumentUpdateDataAdapter(
            String path,
            Node before,
            Node after,
            JsonPatch.Op operation,
            String originScope,
            List<String> recipientChain) {
        this(new DocumentUpdateOccurrence(
                        path,
                        before,
                        after,
                        operation,
                        originScope,
                        recipientChain),
                null);
    }

    DocumentUpdateDataAdapter(
            String path,
            FrozenNode before,
            FrozenNode after,
            JsonPatch.Op operation,
            String originScope,
            List<String> recipientChain,
            UpdateMaterializationMetrics metrics) {
        this(new DocumentUpdateOccurrence(
                        path,
                        before,
                        after,
                        operation,
                        originScope,
                        recipientChain),
                metrics);
    }

    DocumentUpdateDataAdapter(
            DocumentUpdateOccurrence occurrence,
            UpdateMaterializationMetrics metrics) {
        this.occurrence = Objects.requireNonNull(occurrence, "occurrence");
        this.materializationMetrics = metrics;
    }

    final String path() {
        return occurrence.path();
    }

    final Node before() {
        Node materialized = occurrence.before();
        if (materialized != null && materializationMetrics != null) {
            materializationMetrics.recordBeforeNodeMaterialization();
        }
        return materialized;
    }

    final boolean beforePresent() {
        return occurrence.beforePresent();
    }

    final Node after() {
        Node materialized = occurrence.after();
        if (materialized != null && materializationMetrics != null) {
            materializationMetrics.recordAfterNodeMaterialization();
        }
        return materialized;
    }

    final boolean afterPresent() {
        return occurrence.afterPresent();
    }

    final JsonPatch.Op op() {
        return occurrence.op();
    }

    final String originScope() {
        return occurrence.originScope();
    }

    final List<String> recipientChain() {
        return occurrence.recipientChain();
    }

    final List<String> cascadeScopes() {
        return occurrence.recipientChain();
    }

    final DocumentUpdateOccurrence occurrence() {
        return occurrence;
    }
}
