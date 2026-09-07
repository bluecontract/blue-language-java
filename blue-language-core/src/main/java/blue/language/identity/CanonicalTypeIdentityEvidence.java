package blue.language.identity;

import blue.language.model.wire.BlueLanguageConstants;
import blue.language.model.Node;
import blue.language.model.Schema;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable proof of one completed effective type's canonical identity and
 * representation origin.
 *
 * <p>Inline evidence deliberately keeps two different representations. The
 * canonical identity input is the strict, directly hashable proof of the
 * claimed identity; every nested effective-type position in that graph is a
 * pure reference. The authored Source is the normalized, self-contained
 * declaration supplied by the author and may therefore retain nested inline
 * types and preprocessing list controls. Keeping those lanes separate avoids
 * presenting derived proof material as authored Source.</p>
 */
public final class CanonicalTypeIdentityEvidence {

    private final String blueId;
    private final Node canonicalTypeIdentityInput;
    private final Node authoredTypeSource;
    private final boolean referenceSourceAvailable;

    private CanonicalTypeIdentityEvidence(
            String blueId,
            Node canonicalTypeIdentityInput,
            Node authoredTypeSource,
            boolean referenceSourceAvailable) {
        this.blueId = Objects.requireNonNull(
                blueId, BlueLanguageConstants.OBJECT_BLUE_ID);
        BlueIdReferenceValidator.validate(
                new Node().blueId(blueId));
        this.referenceSourceAvailable = referenceSourceAvailable;
        if ((canonicalTypeIdentityInput == null)
                != (authoredTypeSource == null)) {
            throw new IllegalArgumentException(
                    "Canonical identity input and authored inline Source "
                            + "must be supplied together");
        }
        if (canonicalTypeIdentityInput != null) {
            Node identityInputCopy = canonicalTypeIdentityInput.clone();
            BlueIdReferenceValidator.validate(identityInputCopy);
            if (identityInputCopy.isReferenceOnly()) {
                throw new IllegalArgumentException(
                        "Canonical inline type identity input must not be a "
                                + "reference");
            }
            requireCanonicalTypePositions(identityInputCopy);
            String calculatedBlueId = DirectBlueIdCalculator.calculateBlueId(
                    identityInputCopy);
            if (!blueId.equals(calculatedBlueId)) {
                throw new IllegalArgumentException(
                        "Canonical inline type identity input does not match "
                                + "its "
                                + "canonical BlueId");
            }
            Node sourceCopy = authoredTypeSource.clone();
            BlueIdReferenceValidator.validate(sourceCopy);
            if (sourceCopy.isReferenceOnly()) {
                throw new IllegalArgumentException(
                        "Authored inline type Source must not be a pure "
                                + "reference");
            }
            this.canonicalTypeIdentityInput = identityInputCopy;
            this.authoredTypeSource = sourceCopy;
        } else {
            this.canonicalTypeIdentityInput = null;
            this.authoredTypeSource = null;
        }
    }

    /**
     * Creates identity-only evidence with no representation-origin claim.
     *
     * @param blueId canonical Content BlueId of the completed type
     * @return immutable identity-only evidence
     * @throws NullPointerException if {@code blueId} is null
     * @throws IllegalArgumentException if {@code blueId} is malformed
     */
    public static CanonicalTypeIdentityEvidence identityOnly(String blueId) {
        return new CanonicalTypeIdentityEvidence(
                blueId, null, null, false);
    }

    /**
     * Creates evidence independently derived from authored inline Source.
     *
     * @param blueId canonical Content BlueId of the completed type
     * @param canonicalTypeIdentityInput strict canonical identity input
     *         reconstructed for the completed type; all nested type positions
     *         must be pure references and its direct identity must be
     *         {@code blueId}; the graph is defensively copied
     * @param authoredTypeSource normalized self-contained authored inline
     *         Source; nested inline types and preprocessing controls are
     *         permitted and the graph is defensively copied
     * @return immutable inline-source evidence
     * @throws NullPointerException if an argument is null
     * @throws IllegalArgumentException if {@code blueId} is malformed or
     *         the proof is not valid strict canonical identity input, does
     *         not hash to {@code blueId}, or the Source is a pure reference
     */
    public static CanonicalTypeIdentityEvidence authoredInline(
            String blueId,
            Node canonicalTypeIdentityInput,
            Node authoredTypeSource) {
        return new CanonicalTypeIdentityEvidence(
                blueId,
                Objects.requireNonNull(
                        canonicalTypeIdentityInput,
                        "canonicalTypeIdentityInput"),
                Objects.requireNonNull(
                        authoredTypeSource,
                        "authoredTypeSource"),
                false);
    }

    /**
     * Creates evidence that a pure reference is a valid Source form.
     *
     * @param blueId canonical Content BlueId selected by the reference
     * @return immutable reference-source evidence
     * @throws NullPointerException if {@code blueId} is null
     * @throws IllegalArgumentException if {@code blueId} is malformed
     */
    public static CanonicalTypeIdentityEvidence referenceSource(
            String blueId) {
        return new CanonicalTypeIdentityEvidence(
                blueId, null, null, true);
    }

    /**
     * Returns the canonical Content BlueId of the completed type.
     *
     * @return canonical Content BlueId
     */
    public String blueId() {
        return blueId;
    }

    /**
     * Returns the strict canonical input that directly proves
     * {@link #blueId()}, or {@code null} when no inline proof was retained.
     *
     * <p>The returned graph is a fresh defensive copy. Every reserved
     * effective-type position in it is a nonempty pure reference.</p>
     *
     * @return canonical identity-input copy, or {@code null}
     */
    public Node canonicalTypeIdentityInput() {
        return canonicalTypeIdentityInput != null
                ? canonicalTypeIdentityInput.clone()
                : null;
    }

    /**
     * Returns the normalized self-contained Source reconstructed from an
     * authored inline declaration, or {@code null} when no inline
     * representation was established. Unlike the proof lane, this graph may
     * contain nested inline types and preprocessing list controls.
     *
     * <p>The returned graph is a fresh defensive copy. Mutating it cannot
     * change this evidence object.</p>
     *
     * @return authored Source copy, or {@code null}
     */
    public Node authoredTypeSource() {
        return authoredTypeSource != null
                ? authoredTypeSource.clone()
                : null;
    }

    /**
     * Reports whether a pure reference to {@link #blueId()} is an available
     * Source representation.
     *
     * @return {@code true} when reference-source provenance was established
     */
    public boolean hasReferenceSource() {
        return referenceSourceAvailable;
    }

    /**
     * Combines independent evidence for one structural completed type.
     * Reference-Source availability is monotonic; equivalent inline inputs
     * are retained for consumers that prefer a self-contained Source form.
     *
     * @param other independent evidence for the same completed type
     * @return combined immutable evidence
     * @throws NullPointerException if {@code other} is null
     * @throws IllegalStateException if the evidence names different
     *         canonical type identities
     */
    public CanonicalTypeIdentityEvidence combine(
            CanonicalTypeIdentityEvidence other) {
        CanonicalTypeIdentityEvidence checked = Objects.requireNonNull(
                other, "other");
        if (!blueId.equals(checked.blueId)) {
            throw new IllegalStateException(
                    "Conflicting canonical type identities: " + blueId
                            + " and " + checked.blueId);
        }
        CanonicalTypeIdentityEvidence inline = selectRetainedInlineEvidence(
                this, checked);
        boolean referenceSource = referenceSourceAvailable
                || checked.referenceSourceAvailable;
        if (inline == this
                && referenceSource == referenceSourceAvailable) {
            return this;
        }
        if (inline == checked
                && referenceSource
                == checked.referenceSourceAvailable) {
            return checked;
        }
        return new CanonicalTypeIdentityEvidence(
                blueId,
                inline != null
                        ? inline.canonicalTypeIdentityInput
                        : null,
                inline != null ? inline.authoredTypeSource : null,
                referenceSource);
    }

    private static CanonicalTypeIdentityEvidence
    selectRetainedInlineEvidence(
            CanonicalTypeIdentityEvidence left,
            CanonicalTypeIdentityEvidence right) {
        if (left.authoredTypeSource == null) {
            return right.authoredTypeSource != null ? right : null;
        }
        /*
         * Both witnesses have already established the same canonical type
         * BlueId. Either exact authored representation is therefore a valid
         * Source projection. Retaining the established witness avoids a
         * second, recursively calculated direct identity that would make
         * evidence combination depend on graph depth rather than proof.
         */
        return left;
    }

    private static void requireCanonicalTypePositions(Node root) {
        Deque<Node> pending = new ArrayDeque<>();
        Set<Node> visited = Collections.newSetFromMap(
                new IdentityHashMap<Node, Boolean>());
        pending.add(root);
        while (!pending.isEmpty()) {
            Node node = pending.removeLast();
            if (!visited.add(node)) {
                continue;
            }
            requireCanonicalTypeReference(
                    node.getType(), BlueLanguageConstants.OBJECT_TYPE);
            requireCanonicalTypeReference(
                    node.getItemType(),
                    BlueLanguageConstants.OBJECT_ITEM_TYPE);
            requireCanonicalTypeReference(
                    node.getKeyType(),
                    BlueLanguageConstants.OBJECT_KEY_TYPE);
            requireCanonicalTypeReference(
                    node.getValueType(),
                    BlueLanguageConstants.OBJECT_VALUE_TYPE);
            add(pending, node.getContracts());
            add(pending, node.getBlue());
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

    private static void requireCanonicalTypeReference(
            Node type,
            String fieldName) {
        if (type == null) {
            return;
        }
        if (!type.isReferenceOnly() || type.getBlueId() == null) {
            throw new IllegalArgumentException(
                    "Canonical inline type identity input requires a "
                            + "nonempty pure "
                            + fieldName + " reference");
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
