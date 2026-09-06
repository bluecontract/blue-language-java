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
public final class DocumentUpdateOccurrence {

    private final String path;
    private final FrozenNode beforeFrozen;
    private final FrozenNode afterFrozen;
    private final JsonPatch.Op operation;
    private final String originScope;
    private final List<String> recipientChain;
    private final ContractBundle frozenRootDispatchBundle;
    private final FrozenNode retainedRootDispatchContracts;

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
                recipientChain,
                null);
    }

    DocumentUpdateOccurrence(
            String path,
            FrozenNode beforeFrozen,
            FrozenNode afterFrozen,
            JsonPatch.Op operation,
            String originScope,
            List<String> recipientChain) {
        this(path,
                beforeFrozen,
                afterFrozen,
                operation,
                originScope,
                recipientChain,
                null);
    }

    private DocumentUpdateOccurrence(
            String path,
            FrozenNode beforeFrozen,
            FrozenNode afterFrozen,
            JsonPatch.Op operation,
            String originScope,
            List<String> recipientChain,
            ContractBundle frozenRootDispatchBundle) {
        this(path, beforeFrozen, afterFrozen, operation, originScope, recipientChain, frozenRootDispatchBundle, null);
    }

    private DocumentUpdateOccurrence(String path, FrozenNode beforeFrozen, FrozenNode afterFrozen,
            JsonPatch.Op operation, String originScope, List<String> recipientChain,
            ContractBundle frozenRootDispatchBundle, FrozenNode retainedRootDispatchContracts) {
        this.path = Objects.requireNonNull(path, "path");
        this.beforeFrozen = beforeFrozen;
        this.operation = Objects.requireNonNull(operation, "operation");
        this.afterFrozen = operation == JsonPatch.Op.REMOVE
                ? null
                : afterFrozen;
        this.originScope = Objects.requireNonNull(
                originScope, "originScope");
        this.recipientChain = immutableRecipientChain(recipientChain);
        this.frozenRootDispatchBundle = frozenRootDispatchBundle;
        this.retainedRootDispatchContracts = retainedRootDispatchContracts;
    }

    /**
     * Returns the absolute changed document path.
     *
     * @return normalized absolute path
     */
    public String path() {
        return path;
    }

    /**
     * Materializes the exact value present before the change.
     *
     * @return detached value, or {@code null} when absent
     */
    public Node before() {
        if (beforeFrozen == null) {
            return null;
        }
        return beforeFrozen.toNode();
    }

    /**
     * Reports whether the before value was present.
     *
     * @return {@code true} when {@link #before()} returns a value
     */
    public boolean beforePresent() {
        return beforeFrozen != null;
    }

    /**
     * Materializes the exact value present after the change.
     *
     * @return detached value, or {@code null} when absent
     */
    public Node after() {
        if (afterFrozen == null) {
            return null;
        }
        return afterFrozen.toNode();
    }

    /**
     * Reports whether the after value is present.
     *
     * @return {@code true} when {@link #after()} returns a value
     */
    public boolean afterPresent() {
        return afterFrozen != null;
    }

    /**
     * Returns the semantic update operation.
     *
     * @return add, replace, or remove operation
     */
    public JsonPatch.Op op() {
        return operation;
    }

    /**
     * Returns the local scope that authored the patch.
     *
     * @return normalized origin scope
     */
    public String originScope() {
        return originScope;
    }

    /**
     * Returns the immutable receiving chain frozen for this occurrence.
     *
     * @return normalized recipient scopes in delivery order
     */
    public List<String> recipientChain() {
        return recipientChain;
    }

    DocumentUpdateOccurrence withFrozenRootDispatchBundle(
            ContractBundle bundle) {
        return new DocumentUpdateOccurrence(
                path,
                beforeFrozen,
                afterFrozen,
                operation,
                originScope,
                recipientChain,
                Objects.requireNonNull(bundle, "bundle"));
    }

    ContractBundle frozenRootDispatchBundle() {
        return frozenRootDispatchBundle;
    }

    /** Exact dispatch contracts retained for persistence; never serializes Java handler objects. */
    public Node exactRootDispatchContracts() {
        FrozenNode contracts = frozenRootDispatchContracts();
        return contracts == null ? null : contracts.toNode();
    }

    public FrozenNode frozenRootDispatchContracts() {
        if (retainedRootDispatchContracts != null) return retainedRootDispatchContracts;
        if (frozenRootDispatchBundle == null) return null;
        return blue.language.snapshot.FrozenNodeBuilder.fromResolvedParts(FrozenNode.fromResolvedNode(new Node()),
                java.util.Collections.<String, FrozenNode>emptyMap(), null, frozenRootDispatchBundle.contractNodes());
    }

    /** Restores a structurally verified retained occurrence; its enclosing source receipt is authoritative. */
    public static DocumentUpdateOccurrence fromRetainedEvidence(String path, Node before, Node after,
            JsonPatch.Op operation, String originScope, List<String> recipients, Node dispatchContracts) {
        return new DocumentUpdateOccurrence(path, freeze(before), freeze(after), operation, originScope, recipients,
                null, freeze(dispatchContracts));
    }

    /** Retains shared frozen evidence without materializing and re-freezing complete views. */
    public static DocumentUpdateOccurrence fromRetainedFrozenEvidence(String path, FrozenNode before, FrozenNode after,
            JsonPatch.Op operation, String originScope, List<String> recipientChain, FrozenNode dispatchContracts) {
        return new DocumentUpdateOccurrence(path, before, after, operation, originScope, recipientChain, null, dispatchContracts);
    }

    public FrozenNode frozenBefore() { return beforeFrozen; }
    public FrozenNode frozenAfter() { return afterFrozen; }

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
