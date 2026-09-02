package blue.language.mapping;

import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.model.wire.JsonPointer;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Public entry point for recursively materializing Blue nodes as Java object
 * graphs.
 */
public class NodeToObjectConverter {
    private final TypeClassResolver typeClassResolver;
    private final ObjectFactoryRegistry objectFactories;
    private final ConverterFactory converterFactory;

    /**
     * Creates a mapping facade.
     *
     * @param typeClassResolver resolver for Blue-declared Java types
     */
    public NodeToObjectConverter(TypeClassResolver typeClassResolver) {
        this(typeClassResolver, ObjectFactoryRegistry.defaults());
    }

    /**
     * Creates a mapping facade with an immutable object factory registry.
     *
     * @param typeClassResolver resolver for Blue-declared Java types
     * @param objectFactories immutable object factory registry
     */
    public NodeToObjectConverter(
            TypeClassResolver typeClassResolver,
            ObjectFactoryRegistry objectFactories) {
        this.typeClassResolver = Objects.requireNonNull(
                typeClassResolver,
                "typeClassResolver");
        this.objectFactories = Objects.requireNonNull(
                objectFactories,
                "objectFactories");
        this.converterFactory = new ConverterFactory(
                this.typeClassResolver,
                this.objectFactories);
    }

    /**
     * Converts while prioritizing the caller's target class over a resolved
     * Blue type mapping.
     *
     * @param node source Blue node
     * @param targetClass requested Java class
     * @param <T> requested Java value type
     * @return converted value
     */
    public <T> T convert(Node node, Class<T> targetClass) {
        return convertWithType(node, targetClass, true);
    }

    /**
     * Converts a resolved node using canonical type identities captured by
     * the authoritative resolver invocation.
     *
     * <p>Type evidence establishes Java class selection only. It cannot prove
     * the Content BlueId of a resolved value whose inherited fields have been
     * materialized. Use {@link #convert(ResolvedSnapshot, Class)} when the
     * target model contains {@code @BlueId} fields.</p>
     *
     * @param node source Blue node
     * @param targetClass requested Java class
     * @param typeIdentities resolver-issued canonical type identities
     * @param <T> requested Java value type
     * @return converted value
     * @throws NullPointerException if {@code node}, {@code targetClass}, or
     *         {@code typeIdentities} is null
     * @throws IllegalArgumentException if the node cannot be converted to the
     *         requested class
     * @throws IllegalStateException if required canonical type evidence is
     *         unavailable
     */
    public <T> T convert(
            Node node,
            Class<T> targetClass,
            CanonicalTypeIdentityLookup typeIdentities) {
        return convertWithType(
                node,
                targetClass,
                true,
                typeIdentities);
    }

    /**
     * Converts the root of an authoritative resolved snapshot.
     *
     * <p>Unlike a bare resolved node, a snapshot retains the exact Canonical
     * Identity Input. Fields annotated with {@code @BlueId} therefore receive
     * Source-derived Content BlueIds instead of identities calculated from
     * inherited or provider-materialized Resolved Form.</p>
     *
     * @param snapshot authoritative resolution snapshot
     * @param targetClass requested Java class
     * @param <T> requested Java value type
     * @return converted value
     * @throws NullPointerException if an argument is null
     * @throws IllegalStateException if an annotated field has no canonical
     *         identity lane
     */
    public <T> T convert(
            ResolvedSnapshot snapshot,
            Class<T> targetClass) {
        return convert(
                snapshot,
                JsonPointer.ROOT,
                targetClass);
    }

    /**
     * Converts one resolved snapshot subtree while retaining canonical path
     * provenance for recursively mapped {@code @BlueId} fields.
     *
     * @param snapshot authoritative resolution snapshot
     * @param pointer RFC 6901 path of the subtree; null selects the root
     * @param targetClass requested Java class
     * @param <T> requested Java value type
     * @return converted value, or null when the path is absent
     * @throws NullPointerException if {@code snapshot} or
     *         {@code targetClass} is null
     * @throws IllegalStateException if an annotated field has no canonical
     *         identity lane
     */
    public <T> T convert(
            ResolvedSnapshot snapshot,
            String pointer,
            Class<T> targetClass) {
        return convertWithType(
                snapshot,
                pointer,
                targetClass,
                true);
    }

    /**
     * Converts to an arbitrary reflective type.
     *
     * @param node source Blue node
     * @param targetType requested reflective Java type
     * @param prioritizeTargetType whether the requested type takes precedence
     *                             over resolved Blue metadata
     * @param <T> converted Java value type
     * @return converted value
     */
    @SuppressWarnings("unchecked")
    public <T> T convertWithType(Node node, Type targetType, boolean prioritizeTargetType) {
        Converter<?> converter = converterFactory.getConverter(
                node,
                targetType,
                prioritizeTargetType);
        return (T) converter.convert(node, targetType, prioritizeTargetType);
    }

    /**
     * Converts a resolved node to a reflective Java type using exact resolver
     * evidence for every materialized effective type encountered recursively.
     * Type evidence does not substitute for the canonical content lane needed
     * by {@code @BlueId}; use the snapshot overload for such models.
     *
     * @param node source Blue node
     * @param targetType requested reflective Java type
     * @param prioritizeTargetType whether the requested type takes precedence
     *                             over resolved Blue metadata
     * @param typeIdentities resolver-issued canonical type identities
     * @param <T> converted Java value type
     * @return converted value
     * @throws NullPointerException if {@code node}, {@code targetType}, or
     *         {@code typeIdentities} is null
     * @throws IllegalArgumentException if the node cannot be converted to the
     *         requested type
     * @throws IllegalStateException if required canonical type evidence is
     *         unavailable
     */
    @SuppressWarnings("unchecked")
    public <T> T convertWithType(
            Node node,
            Type targetType,
            boolean prioritizeTargetType,
            CanonicalTypeIdentityLookup typeIdentities) {
        ConverterFactory evidenceBoundFactory = new ConverterFactory(
                typeClassResolver,
                objectFactories,
                Objects.requireNonNull(typeIdentities, "typeIdentities"),
                CanonicalContentIdentityLookup.resolvedOnly(node));
        Converter<?> converter = evidenceBoundFactory.getConverter(
                node,
                targetType,
                prioritizeTargetType);
        return (T) converter.convert(
                node,
                targetType,
                prioritizeTargetType);
    }

    /**
     * Converts one resolved snapshot subtree to an arbitrary reflective type
     * with exact canonical-content and effective-type evidence.
     *
     * @param snapshot authoritative resolution snapshot
     * @param pointer RFC 6901 path of the subtree; null selects the root
     * @param targetType requested reflective Java type
     * @param prioritizeTargetType whether the requested type takes precedence
     *                             over resolved Blue metadata
     * @param <T> converted Java value type
     * @return converted value, or null when the path is absent
     * @throws NullPointerException if {@code snapshot} or
     *         {@code targetType} is null
     * @throws IllegalStateException if an annotated field has no canonical
     *         identity lane
     */
    @SuppressWarnings("unchecked")
    public <T> T convertWithType(
            ResolvedSnapshot snapshot,
            String pointer,
            Type targetType,
            boolean prioritizeTargetType) {
        return convertWithTypeOmittingProperties(
                snapshot,
                pointer,
                Collections.<String>emptyList(),
                targetType,
                prioritizeTargetType);
    }

    /**
     * Converts an omit-only projection of one resolved snapshot subtree.
     *
     * <p>The projection is derived inside the mapper from the authoritative
     * snapshot. Callers can omit direct object properties, but cannot supply
     * replacement resolved content, assert a path for a detached node, or
     * substitute unrelated type-identity evidence. Retained subtrees therefore
     * keep their snapshot-issued canonical-content provenance.</p>
     *
     * @param snapshot authoritative resolution snapshot
     * @param pointer RFC 6901 path occupied by the unprojected subtree
     * @param omittedProperties direct object-property names to remove
     * @param targetType requested reflective Java type
     * @param prioritizeTargetType whether the requested type takes precedence
     * @param <T> converted Java value type
     * @return converted value, or null when the snapshot path is absent
     * @throws NullPointerException if a required argument or omitted property
     *         name is null
     * @throws IllegalStateException if an annotated field has no canonical
     *         identity lane
     */
    public <T> T convertWithTypeOmittingProperties(
            ResolvedSnapshot snapshot,
            String pointer,
            Collection<String> omittedProperties,
            Type targetType,
            boolean prioritizeTargetType) {
        ResolvedSnapshot checkedSnapshot = Objects.requireNonNull(
                snapshot,
                "snapshot");
        Collection<String> requestedOmissions = Objects.requireNonNull(
                omittedProperties,
                "omittedProperties");
        Collection<String> checkedOmissions = new ArrayList<>(
                requestedOmissions.size());
        for (String property : requestedOmissions) {
            checkedOmissions.add(Objects.requireNonNull(
                    property,
                    "omittedProperty"));
        }
        Objects.requireNonNull(targetType, "targetType");
        Node resolvedNode = checkedSnapshot.resolvedNodeAt(pointer);
        if (resolvedNode == null) {
            return null;
        }
        if (!checkedOmissions.isEmpty()
                && resolvedNode.getProperties() != null) {
            Map<String, Node> retained = new LinkedHashMap<>(
                    resolvedNode.getProperties());
            for (String property : checkedOmissions) {
                retained.remove(property);
            }
            resolvedNode.properties(retained);
        }
        return convertSnapshotProjection(
                checkedSnapshot,
                pointer,
                resolvedNode,
                targetType,
                prioritizeTargetType);
    }

    /**
     * Maps a projection already derived by this converter. This helper remains
     * private so no caller can pair arbitrary content with a snapshot path.
     */
    @SuppressWarnings("unchecked")
    private <T> T convertSnapshotProjection(
            ResolvedSnapshot snapshot,
            String pointer,
            Node resolvedProjection,
            Type targetType,
            boolean prioritizeTargetType) {
        ResolvedSnapshot checkedSnapshot = Objects.requireNonNull(
                snapshot,
                "snapshot");
        Objects.requireNonNull(targetType, "targetType");
        if (resolvedProjection == null) {
            return null;
        }
        ConverterFactory snapshotBoundFactory = new ConverterFactory(
                typeClassResolver,
                objectFactories,
                checkedSnapshot.canonicalTypeIdentities(),
                CanonicalContentIdentityLookup.fromSnapshot(
                        checkedSnapshot,
                        resolvedProjection,
                        pointer));
        Converter<?> converter = snapshotBoundFactory.getConverter(
                resolvedProjection,
                targetType,
                prioritizeTargetType);
        return (T) converter.convert(
                resolvedProjection,
                targetType,
                prioritizeTargetType);
    }
}
