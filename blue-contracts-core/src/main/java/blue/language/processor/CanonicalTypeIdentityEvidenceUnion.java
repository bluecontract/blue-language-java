package blue.language.processor;

import blue.language.identity.BlueIdReferenceValidator;
import blue.language.identity.CanonicalTypeIdentityEvidence;
import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.model.Node;
import blue.language.model.Schema;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Graph-scoped union of resolver-issued type-identity lookups.
 *
 * <p>Each constituent lookup proves only the structures reached by its own
 * producing resolution. Constituents may therefore be target-limited. A
 * union becomes complete only after every reserved type position reachable
 * from the exact recombined graph has been found in at least one constituent.
 * When several resolvers cover the same completed structure, their identities
 * must agree. This is not a blanket claim that any constituent covers content
 * produced by another operation.</p>
 */
final class CanonicalTypeIdentityEvidenceUnion
        implements CanonicalTypeIdentityLookup {

    private final List<CanonicalTypeIdentityLookup> constituents;

    private CanonicalTypeIdentityEvidenceUnion(
            Node resolvedGraph,
            List<CanonicalTypeIdentityLookup> constituents) {
        List<CanonicalTypeIdentityLookup> checked = new ArrayList<>();
        Set<CanonicalTypeIdentityLookup> seen = Collections.newSetFromMap(
                new IdentityHashMap<CanonicalTypeIdentityLookup, Boolean>());
        boolean sawIncomplete = false;
        for (CanonicalTypeIdentityLookup constituent : constituents) {
            CanonicalTypeIdentityLookup exact = Objects.requireNonNull(
                    constituent, "canonicalTypeIdentityLookup");
            if (exact == CanonicalTypeIdentityLookup.incomplete()) {
                sawIncomplete = true;
                continue;
            }
            if (seen.add(exact)) {
                checked.add(exact);
            }
        }
        if (checked.isEmpty() && sawIncomplete) {
            checked.add(CanonicalTypeIdentityLookup.incomplete());
        }
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one canonical type identity lookup is required");
        }
        this.constituents = Collections.unmodifiableList(checked);
        requireCoverage(Objects.requireNonNull(
                resolvedGraph, "resolvedGraph"));
    }

    static CanonicalTypeIdentityLookup establishForResolvedGraph(
            Node resolvedGraph,
            List<CanonicalTypeIdentityLookup> constituents) {
        return new CanonicalTypeIdentityEvidenceUnion(
                resolvedGraph,
                Objects.requireNonNull(constituents, "constituents"));
    }

    @Override
    public boolean hasCompleteCoverage() {
        return true;
    }

    @Override
    public Optional<String> findCanonicalTypeBlueId(Node completedType) {
        return findCanonicalTypeIdentityEvidence(completedType)
                .map(CanonicalTypeIdentityEvidence::blueId);
    }

    @Override
    public Optional<CanonicalTypeIdentityEvidence>
    findCanonicalTypeIdentityEvidence(Node completedType) {
        Objects.requireNonNull(completedType, "completedType");
        if (completedType.isReferenceOnly()) {
            BlueIdReferenceValidator.validate(completedType);
            return Optional.of(
                    CanonicalTypeIdentityEvidence.referenceSource(
                            completedType.getBlueId()));
        }
        CanonicalTypeIdentityEvidence established = null;
        for (CanonicalTypeIdentityLookup constituent : constituents) {
            Optional<CanonicalTypeIdentityEvidence> candidate = constituent
                    .findCanonicalTypeIdentityEvidence(completedType);
            if (!candidate.isPresent()) {
                continue;
            }
            established = established == null
                    ? candidate.get()
                    : established.combine(candidate.get());
        }
        return Optional.ofNullable(established);
    }

    @Override
    public Optional<CanonicalTypeIdentityEvidence>
    findCanonicalTypeIdentityEvidence(
            Node completedType,
            Node authoredTypeSource) {
        Objects.requireNonNull(completedType, "completedType");
        CanonicalTypeIdentityEvidence established = null;
        for (CanonicalTypeIdentityLookup constituent : constituents) {
            Optional<CanonicalTypeIdentityEvidence> candidate = constituent
                    .findCanonicalTypeIdentityEvidence(
                            completedType, authoredTypeSource);
            if (!candidate.isPresent()) {
                continue;
            }
            established = established == null
                    ? candidate.get()
                    : established.combine(candidate.get());
        }
        return Optional.ofNullable(established);
    }

    @Override
    public long approximateRetainedWeightBytes() {
        long weight = 0L;
        for (CanonicalTypeIdentityLookup constituent : constituents) {
            weight = saturatedAdd(
                    weight,
                    constituent.approximateRetainedWeightBytes());
        }
        return weight;
    }

    @Override
    public String requireCanonicalTypeBlueId(Node completedType) {
        return findCanonicalTypeBlueId(completedType)
                .orElseThrow(() -> new MissingEvidenceException(
                        "No resolver-issued canonical type identity evidence "
                                + "covers the recombined executable handler"));
    }

    /** Missing coverage is recoverable only by obtaining authoritative evidence. */
    static final class MissingEvidenceException
            extends IllegalStateException {

        private MissingEvidenceException(String message) {
            super(message);
        }
    }

    private void requireCoverage(Node root) {
        Deque<Node> pending = new ArrayDeque<>();
        Set<Node> visited = Collections.newSetFromMap(
                new IdentityHashMap<Node, Boolean>());
        add(pending, root);
        while (!pending.isEmpty()) {
            Node node = pending.removeLast();
            if (!visited.add(node)) {
                continue;
            }
            requireTypePosition(node.getType(), pending);
            requireTypePosition(node.getItemType(), pending);
            requireTypePosition(node.getKeyType(), pending);
            requireTypePosition(node.getValueType(), pending);
            add(pending, node.getBlue());
            add(pending, node.getContracts());
            if (node.getItems() != null) {
                for (Node item : node.getItems()) {
                    add(pending, item);
                }
            }
            if (node.getProperties() != null) {
                for (Node property : node.getProperties().values()) {
                    add(pending, property);
                }
            }
            addSchemaValues(pending, node.getSchema());
        }
    }

    private void requireTypePosition(Node type, Deque<Node> pending) {
        if (type != null) {
            requireCanonicalTypeBlueId(type);
            if (!type.isReferenceOnly()) {
                add(pending, type);
            }
        }
    }

    private static void addSchemaValues(
            Deque<Node> pending,
            Schema schema) {
        if (schema == null || schema.isReferenceOnly()) {
            return;
        }
        add(pending, schema.getRequired());
        add(pending, schema.getMinLength());
        add(pending, schema.getMaxLength());
        add(pending, schema.getMinimum());
        add(pending, schema.getMaximum());
        add(pending, schema.getExclusiveMinimum());
        add(pending, schema.getExclusiveMaximum());
        add(pending, schema.getMultipleOf());
        add(pending, schema.getMinItems());
        add(pending, schema.getMaxItems());
        add(pending, schema.getUniqueItems());
        add(pending, schema.getMinFields());
        add(pending, schema.getMaxFields());
        if (schema.getEnum() != null) {
            for (Node value : schema.getEnum()) {
                add(pending, value);
            }
        }
    }

    private static void add(Deque<Node> pending, Node node) {
        if (node != null) {
            pending.add(node);
        }
    }

    private static long saturatedAdd(long left, long right) {
        if (left < 0L
                || right < 0L
                || left == Long.MAX_VALUE
                || right == Long.MAX_VALUE
                || left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}
