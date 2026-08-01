package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One immutable semantic document-update occurrence.
 *
 * <p>Absolute paths and exact before/after values belong to the occurrence;
 * scope-relative rendering is performed later for each frozen recipient.
 * Mutable {@link Node} views are materialized on demand as detached values and
 * are never retained by the occurrence.</p>
 */
final class DocumentUpdateOccurrence {

    private final String path;
    private final FrozenNode beforeFrozen;
    private final FrozenNode afterFrozen;
    private final JsonPatch.Op operation;
    private final String originScope;
    private final List<String> recipientChain;

    DocumentUpdateOccurrence(
            String path,
            Node before,
            Node after,
            JsonPatch.Op operation,
            String originScope,
            List<String> recipientChain) {
        this(path,
                freeze(before),
                operation == JsonPatch.Op.REMOVE
                        ? null
                        : freeze(after),
                operation,
                originScope,
                recipientChain);
    }

    DocumentUpdateOccurrence(
            String path,
            FrozenNode beforeFrozen,
            FrozenNode afterFrozen,
            JsonPatch.Op operation,
            String originScope,
            List<String> recipientChain) {
        this.path = Objects.requireNonNull(path, "path");
        this.beforeFrozen = beforeFrozen;
        this.operation = Objects.requireNonNull(operation, "operation");
        this.afterFrozen = operation == JsonPatch.Op.REMOVE
                ? null
                : afterFrozen;
        this.originScope = Objects.requireNonNull(
                originScope, "originScope");
        this.recipientChain = immutableRecipientChain(recipientChain);
    }

    String path() {
        return path;
    }

    Node before() {
        if (beforeFrozen == null) {
            return null;
        }
        return beforeFrozen.toNode();
    }

    boolean beforePresent() {
        return beforeFrozen != null;
    }

    Node after() {
        if (afterFrozen == null) {
            return null;
        }
        return afterFrozen.toNode();
    }

    boolean afterPresent() {
        return afterFrozen != null;
    }

    JsonPatch.Op op() {
        return operation;
    }

    String originScope() {
        return originScope;
    }

    List<String> recipientChain() {
        return recipientChain;
    }

    private static FrozenNode freeze(Node value) {
        return value == null
                ? null
                : FrozenNode.fromResolvedNode(value);
    }

    private static List<String> immutableRecipientChain(
            List<String> recipientChain) {
        Objects.requireNonNull(recipientChain, "recipientChain");
        List<String> owned = new ArrayList<>(recipientChain.size());
        for (String scope : recipientChain) {
            owned.add(Objects.requireNonNull(
                    scope, "recipientChain element"));
        }
        return Collections.unmodifiableList(owned);
    }
}
