package blue.language.provider;

import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.model.Node;

import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static blue.language.model.wire.BlueLanguageConstants.*;

/**
 * Helpers for canonical Blue type identity and subtype traversal.
 *
 * <p>Provider-backed traversal requires each non-core reference to resolve to
 * exactly one type definition. Expanded effective types require
 * resolver-issued canonical identity evidence; this class never manufactures
 * aliases by stripping labels or hashing a completed type body.</p>
 */
public class Types {

    private final Map<String, Node> types;

    /**
     * Indexes named type nodes by name.
     *
     * @param nodes type definitions to index; duplicate names are rejected
     */
    public Types(List<? extends Node> nodes) {
        types = nodes.stream()
                .collect(Collectors.toMap(Node::getName, node -> node));
    }

    /**
     * Tests whether one completed type is identical to or derives from
     * another using resolver-issued canonical identities.
     *
     * @param subtype candidate completed type
     * @param supertype required supertype
     * @param nodeProvider provider used to traverse pure type references
     * @param typeIdentities resolver-issued effective identities
     * @return {@code true} when the candidate is the same type or a subtype
     * @throws NullPointerException if {@code typeIdentities} is null, or if
     *         {@code nodeProvider} is null when provider traversal is required
     * @throws IllegalStateException if canonical evidence is missing or
     *         conflicting, or a reference resolves ambiguously
     */
    public static boolean isSubtype(
            Node subtype,
            Node supertype,
            NodeProvider nodeProvider,
            CanonicalTypeIdentityLookup typeIdentities) {
        Objects.requireNonNull(typeIdentities, "typeIdentities");
        if (subtype == null || supertype == null) {
            return false;
        }
        String supertypeBlueId = canonicalIdentity(
                supertype, typeIdentities);
        String subtypeBlueId = canonicalIdentity(
                subtype, typeIdentities);
        if (supertypeBlueId.equals(subtypeBlueId)) {
            return true;
        }

        return hasAncestorIdentity(
                subtype,
                supertypeBlueId,
                nodeProvider,
                typeIdentities);
    }

    private static boolean hasAncestorIdentity(
            Node candidate,
            String requiredBlueId,
            NodeProvider nodeProvider,
            CanonicalTypeIdentityLookup typeIdentities) {

        Set<String> visitedReferenceBlueIds = new HashSet<>();
        Set<Node> visitedInlineNodes = Collections.newSetFromMap(
                new IdentityHashMap<Node, Boolean>());
        Node current = candidate;
        if (current.isReferenceOnly()) {
            visitedReferenceBlueIds.add(current.getBlueId());
            current = fetchSingleDefinition(
                    current.getBlueId(), nodeProvider);
        }

        while (current != null && visitedInlineNodes.add(current)) {
            Node parent = current.getType();
            if (parent == null) {
                return false;
            }
            String parentBlueId = canonicalIdentity(
                    parent, typeIdentities);
            if (requiredBlueId.equals(parentBlueId)) {
                return true;
            }
            if (parent.isReferenceOnly()) {
                if (!visitedReferenceBlueIds.add(parentBlueId)) {
                    return false;
                }
                current = fetchSingleDefinition(
                        parentBlueId, nodeProvider);
            } else {
                current = parent;
            }
        }
        return false;
    }

    private static String canonicalIdentity(
            Node type,
            CanonicalTypeIdentityLookup typeIdentities) {
        if (type.isReferenceOnly()) {
            return type.getBlueId();
        }
        if (isBareCoreTypeName(type)) {
            return CORE_TYPE_NAME_TO_BLUE_ID_MAP.get(type.getName());
        }
        return typeIdentities.requireCanonicalTypeBlueId(type);
    }

    private static Node fetchSingleDefinition(
            String blueId,
            NodeProvider nodeProvider) {
        List<Node> referencedNodes = Objects.requireNonNull(
                nodeProvider, "nodeProvider")
                .fetchByBlueId(blueId);
        if (referencedNodes == null || referencedNodes.isEmpty()) {
            return null;
        }
        if (referencedNodes.size() > 1) {
            throw new IllegalStateException(String.format(
                    "Expected a single node for type with blueId '%s', "
                            + "but found multiple.",
                    blueId));
        }
        return referencedNodes.get(0);
    }

    private static boolean isBareCoreTypeName(Node node) {
        return node.getName() != null
                && CORE_TYPE_NAME_TO_BLUE_ID_MAP.containsKey(node.getName())
                && node.getDescription() == null
                && node.getType() == null
                && node.getItemType() == null
                && node.getKeyType() == null
                && node.getValueType() == null
                && node.getValue() == null
                && node.getItems() == null
                && node.getProperties() == null
                && node.getContracts() == null
                && node.getBlueId() == null
                && node.getSchema() == null
                && node.getMergePolicy() == null
                && node.getPreviousBlueId() == null
                && node.getPosition() == null
                && node.getBlue() == null;
    }

    /**
     * Tests a completed type against the released basic scalar types.
     *
     * @param type completed type to inspect
     * @param nodeProvider provider used to traverse its type chain
     * @param typeIdentities resolver-issued effective identities
     * @return {@code true} when the type derives from a basic scalar type
     * @throws NullPointerException if {@code typeIdentities} is null, or if
     *         {@code nodeProvider} is null when provider traversal is required
     * @throws IllegalStateException if canonical evidence is missing or
     *         conflicting, or a reference resolves ambiguously
     */
    public static boolean isSubtypeOfBasicType(
            Node type,
            NodeProvider nodeProvider,
            CanonicalTypeIdentityLookup typeIdentities) {
        return BASIC_TYPE_BLUE_IDS.stream()
                .map(blueId -> new Node().blueId(blueId))
                .anyMatch(basicTypeNode -> isSubtype(
                        type,
                        basicTypeNode,
                        nodeProvider,
                        typeIdentities));
    }

    /**
     * Returns the basic type name reached by a completed type chain.
     *
     * @param type completed type to inspect
     * @param nodeProvider provider used to traverse its type chain
     * @param typeIdentities resolver-issued effective identities
     * @return released basic type name
     * @throws NullPointerException if {@code type} or
     *         {@code typeIdentities} is null, or if {@code nodeProvider} is
     *         null when provider traversal is required
     * @throws IllegalArgumentException if the type does not derive from a
     *         released basic scalar type
     * @throws IllegalStateException if canonical evidence is missing or
     *         conflicting, or a reference resolves ambiguously
     */
    public static String findBasicTypeName(
            Node type,
            NodeProvider nodeProvider,
            CanonicalTypeIdentityLookup typeIdentities) {
        return BASIC_TYPE_BLUE_IDS.stream()
                .filter(blueId -> Types.isSubtype(
                        type,
                        new Node().blueId(blueId),
                        nodeProvider,
                        typeIdentities))
                .findFirst()
                .map(CORE_TYPE_BLUE_ID_TO_NAME_MAP::get)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Cannot determine the basic type for node of type \""
                                + type.getName() + "\"."));
    }

    /**
     * Tests whether a string is a released basic scalar type name.
     *
     * @param type candidate type name
     * @return {@code true} when the name identifies a basic scalar type
     */
    public static boolean isBasicTypeName(String type) {
        return BASIC_TYPES.contains(type);
    }

    /**
     * Tests whether a completed node is a released basic scalar type.
     *
     * @param typeNode completed type to inspect
     * @param nodeProvider provider used to traverse its type chain
     * @param typeIdentities resolver-issued effective identities
     * @return {@code true} when the type is a basic scalar type
     * @throws NullPointerException if {@code typeIdentities} is null, or if
     *         {@code nodeProvider} is null when provider traversal is required
     * @throws IllegalStateException if canonical evidence is missing or
     *         conflicting, or a reference resolves ambiguously
     */
    public static boolean isBasicType(
            Node typeNode,
            NodeProvider nodeProvider,
            CanonicalTypeIdentityLookup typeIdentities) {
        return isSubtypeOfBasicType(
                typeNode, nodeProvider, typeIdentities);
    }

    /**
     * Tests a completed type against Text using resolver-issued identity.
     *
     * @param typeNode completed type to inspect
     * @param nodeProvider provider used to traverse its type chain
     * @param typeIdentities resolver-issued effective identities
     * @return {@code true} when the type derives from Text
     * @throws NullPointerException if {@code typeIdentities} is null, or if
     *         {@code nodeProvider} is null when provider traversal is required
     * @throws IllegalStateException if required evidence is unavailable or a
     *         provider reference is ambiguous
     */
    public static boolean isTextType(
            Node typeNode,
            NodeProvider nodeProvider,
            CanonicalTypeIdentityLookup typeIdentities) {
        return isSubtype(
                typeNode,
                new Node().blueId(TEXT_TYPE_BLUE_ID),
                nodeProvider,
                typeIdentities);
    }

    /**
     * Tests a completed type against Number using resolver-issued identity.
     *
     * @param typeNode completed type to inspect
     * @param nodeProvider provider used to traverse its type chain
     * @param typeIdentities resolver-issued effective identities
     * @return {@code true} when the type derives from Number
     * @throws NullPointerException if {@code typeIdentities} is null, or if
     *         {@code nodeProvider} is null when provider traversal is required
     * @throws IllegalStateException if required evidence is unavailable or a
     *         provider reference is ambiguous
     */
    public static boolean isNumberType(
            Node typeNode,
            NodeProvider nodeProvider,
            CanonicalTypeIdentityLookup typeIdentities) {
        return isSubtype(
                typeNode,
                new Node().blueId(DOUBLE_TYPE_BLUE_ID),
                nodeProvider,
                typeIdentities);
    }

    /**
     * Tests a completed type against Integer using resolver-issued identity.
     *
     * @param typeNode completed type to inspect
     * @param nodeProvider provider used to traverse its type chain
     * @param typeIdentities resolver-issued effective identities
     * @return {@code true} when the type derives from Integer
     * @throws NullPointerException if {@code typeIdentities} is null, or if
     *         {@code nodeProvider} is null when provider traversal is required
     * @throws IllegalStateException if required evidence is unavailable or a
     *         provider reference is ambiguous
     */
    public static boolean isIntegerType(
            Node typeNode,
            NodeProvider nodeProvider,
            CanonicalTypeIdentityLookup typeIdentities) {
        return isSubtype(
                typeNode,
                new Node().blueId(INTEGER_TYPE_BLUE_ID),
                nodeProvider,
                typeIdentities);
    }

    /**
     * Tests a completed type against Boolean using resolver-issued identity.
     *
     * @param typeNode completed type to inspect
     * @param nodeProvider provider used to traverse its type chain
     * @param typeIdentities resolver-issued effective identities
     * @return {@code true} when the type derives from Boolean
     * @throws NullPointerException if {@code typeIdentities} is null, or if
     *         {@code nodeProvider} is null when provider traversal is required
     * @throws IllegalStateException if required evidence is unavailable or a
     *         provider reference is ambiguous
     */
    public static boolean isBooleanType(
            Node typeNode,
            NodeProvider nodeProvider,
            CanonicalTypeIdentityLookup typeIdentities) {
        return isSubtype(
                typeNode,
                new Node().blueId(BOOLEAN_TYPE_BLUE_ID),
                nodeProvider,
                typeIdentities);
    }

    /**
     * Tests a completed type against List using resolver-issued identity.
     *
     * @param typeNode completed type to inspect
     * @param nodeProvider provider used to traverse its type chain
     * @param typeIdentities resolver-issued effective identities
     * @return {@code true} when the type derives from List
     * @throws NullPointerException if {@code typeIdentities} is null, or if
     *         {@code nodeProvider} is null when provider traversal is required
     * @throws IllegalStateException if required evidence is unavailable or a
     *         provider reference is ambiguous
     */
    public static boolean isListType(
            Node typeNode,
            NodeProvider nodeProvider,
            CanonicalTypeIdentityLookup typeIdentities) {
        return isSubtype(
                typeNode,
                new Node().blueId(LIST_TYPE_BLUE_ID),
                nodeProvider,
                typeIdentities);
    }

    /**
     * Tests a completed type against Dictionary using resolver-issued
     * identity.
     *
     * @param typeNode completed type to inspect
     * @param nodeProvider provider used to traverse its type chain
     * @param typeIdentities resolver-issued effective identities
     * @return {@code true} when the type derives from Dictionary
     * @throws NullPointerException if {@code typeIdentities} is null, or if
     *         {@code nodeProvider} is null when provider traversal is required
     * @throws IllegalStateException if required evidence is unavailable or a
     *         provider reference is ambiguous
     */
    public static boolean isDictionaryType(
            Node typeNode,
            NodeProvider nodeProvider,
            CanonicalTypeIdentityLookup typeIdentities) {
        return isSubtype(
                typeNode,
                new Node().blueId(DICTIONARY_TYPE_BLUE_ID),
                nodeProvider,
                typeIdentities);
    }

}
