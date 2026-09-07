package blue.language.processor;

import blue.language.identity.CanonicalTypeIdentityEvidence;
import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.model.wire.BlueLanguageConstants;
import blue.language.model.wire.JsonPointer;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Projects resolver-completed type positions back to strict Source form. */
final class CanonicalEffectSourceProjection {

    private CanonicalEffectSourceProjection() {
    }

    /**
     * Projects an owned resolver-completed effect to strict Source. This
     * operation is intentionally unavailable to ordinary authored effects.
     */
    static Node projectResolvedOwned(
            Node value,
            CanonicalTypeIdentityLookup typeIdentities) {
        Node owned = Objects.requireNonNull(
                value, BlueLanguageConstants.OBJECT_VALUE);
        CanonicalTypeIdentityLookup identities = Objects.requireNonNull(
                typeIdentities, "typeIdentities");
        projectNode(owned, identities);
        return owned;
    }

    /** Projects a patch value with awareness of its target field role. */
    static Node projectResolvedPatchValue(
            Node value,
            String authoredPath,
            CanonicalTypeIdentityLookup typeIdentities) {
        Node owned = Objects.requireNonNull(
                value, BlueLanguageConstants.OBJECT_VALUE);
        CanonicalTypeIdentityLookup identities = Objects.requireNonNull(
                typeIdentities, "typeIdentities");
        if (targetsTypePosition(authoredPath)) {
            return projectType(owned, identities);
        }
        projectNode(owned, identities);
        return owned;
    }

    private static boolean targetsTypePosition(String authoredPath) {
        List<String> segments = JsonPointer.split(authoredPath);
        if (segments.isEmpty()) {
            return false;
        }
        String leaf = segments.get(segments.size() - 1);
        return BlueLanguageConstants.OBJECT_TYPE.equals(leaf)
                || BlueLanguageConstants.OBJECT_ITEM_TYPE.equals(leaf)
                || BlueLanguageConstants.OBJECT_KEY_TYPE.equals(leaf)
                || BlueLanguageConstants.OBJECT_VALUE_TYPE.equals(leaf);
    }

    private static void projectNode(
            Node node,
            CanonicalTypeIdentityLookup identities) {
        Deque<Node> pending = new ArrayDeque<>();
        Set<Node> visited = Collections.newSetFromMap(
                new IdentityHashMap<Node, Boolean>());
        Set<Schema> visitedSchemas = Collections.newSetFromMap(
                new IdentityHashMap<Schema, Boolean>());
        add(pending, node);
        while (!pending.isEmpty()) {
            Node current = pending.removeLast();
            if (!visited.add(current)) {
                continue;
            }
            current.type(projectType(current.getType(), identities));
            current.itemType(projectType(
                    current.getItemType(), identities));
            current.keyType(projectType(
                    current.getKeyType(), identities));
            current.valueType(projectType(
                    current.getValueType(), identities));
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
    }

    private static Node projectType(
            Node type,
            CanonicalTypeIdentityLookup identities) {
        if (type == null || type.isReferenceOnly()) {
            return type;
        }
        CanonicalTypeIdentityEvidence evidence = identities
                .findCanonicalTypeIdentityEvidence(type)
                .orElseThrow(() -> new IllegalStateException(
                        "Materialized resolved effect type lacks "
                                + "resolver-issued identity evidence"));
        return new Node().blueId(evidence.blueId());
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

    private static void add(Deque<Node> pending, Node node) {
        if (node != null) {
            pending.add(node);
        }
    }
}
