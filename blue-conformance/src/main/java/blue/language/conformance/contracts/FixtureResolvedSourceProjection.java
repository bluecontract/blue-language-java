package blue.language.conformance.contracts;

import blue.language.identity.CanonicalTypeIdentityEvidence;
import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.model.Node;
import blue.language.model.Schema;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Projects resolver-completed fixture values back to exact Source form.
 *
 * <p>This is the conformance-side oracle for generated feeder evidence. It
 * consumes the authoritative resolver sidecar and never derives an identity
 * from a materialized type body.</p>
 */
final class FixtureResolvedSourceProjection {

    private FixtureResolvedSourceProjection() {
    }

    static Node project(
            Node value,
            CanonicalTypeIdentityLookup typeIdentities) {
        if (value == null) {
            throw new NullPointerException("resolvedNode");
        }
        if (typeIdentities == null) {
            throw new NullPointerException("typeIdentities");
        }
        Deque<Node> pending = new ArrayDeque<>();
        Set<Node> visited = Collections.newSetFromMap(
                new IdentityHashMap<Node, Boolean>());
        Set<Schema> visitedSchemas = Collections.newSetFromMap(
                new IdentityHashMap<Schema, Boolean>());
        pending.add(value);
        while (!pending.isEmpty()) {
            Node current = pending.removeLast();
            if (!visited.add(current)) {
                continue;
            }
            current.type(projectType(
                    current.getType(), typeIdentities));
            current.itemType(projectType(
                    current.getItemType(), typeIdentities));
            current.keyType(projectType(
                    current.getKeyType(), typeIdentities));
            current.valueType(projectType(
                    current.getValueType(), typeIdentities));
            add(pending, current.getBlue());
            add(pending, current.getContracts());
            if (current.getItems() != null) {
                for (Node item : current.getItems()) {
                    add(pending, item);
                }
            }
            if (current.getProperties() != null) {
                for (Node property : current.getProperties().values()) {
                    add(pending, property);
                }
            }
            Schema schema = current.getSchema();
            if (schema != null
                    && visitedSchemas.add(schema)
                    && !schema.isReferenceOnly()) {
                addSchemaValues(pending, schema);
            }
        }
        return value;
    }

    private static Node projectType(
            Node type,
            CanonicalTypeIdentityLookup typeIdentities) {
        if (type == null || type.isReferenceOnly()) {
            return type;
        }
        CanonicalTypeIdentityEvidence evidence = typeIdentities
                .findCanonicalTypeIdentityEvidence(type)
                .orElseThrow(() -> new IllegalStateException(
                        "Fixture effective type lacks resolver-issued "
                                + "identity evidence"));
        if (evidence.hasReferenceSource()) {
            return new Node().blueId(evidence.blueId());
        }
        Node authoredTypeSource = evidence.authoredTypeSource();
        if (authoredTypeSource == null) {
            throw new IllegalStateException(
                    "Fixture effective type lacks exact authored Source "
                            + "evidence");
        }
        return authoredTypeSource;
    }

    private static void addSchemaValues(
            Deque<Node> pending,
            Schema schema) {
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
            for (Node enumValue : schema.getEnum()) {
                add(pending, enumValue);
            }
        }
    }

    private static void add(
            Deque<Node> pending,
            Node node) {
        if (node != null) {
            pending.add(node);
        }
    }
}
