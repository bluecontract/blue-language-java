package blue.language.matching;

import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.merge.processor.SchemaVerifier;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.snapshot.FrozenNode;

import java.util.Objects;

/** Evaluates schema predicates through the same validator as completed resolution. */
final class FrozenSchemaMatcher {
    private final CanonicalTypeIdentityLookup canonicalTypeIdentities;
    private final SchemaVerifier verifier = new SchemaVerifier();

    FrozenSchemaMatcher(CanonicalTypeIdentityLookup canonicalTypeIdentities) {
        this.canonicalTypeIdentities = Objects.requireNonNull(canonicalTypeIdentities, "canonicalTypeIdentities");
    }

    public boolean matches(FrozenNode node, Schema schema) {
        if (schema == null) {
            return true;
        }
        try {
            Node candidate = node.toNode().schema(schema.clone());
            boolean present = node.isReferenceOnly() || node.getValue() != null
                    || node.getItems() != null || node.getProperties() != null;
            verifier.validateCompleted(candidate, present, "", canonicalTypeIdentities);
            return true;
        } catch (RuntimeException invalidOrUnproven) {
            return false;
        }
    }
}
