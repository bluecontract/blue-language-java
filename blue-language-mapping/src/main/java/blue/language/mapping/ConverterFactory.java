package blue.language.mapping;

import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.model.Node;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

/**
 * Chooses recursive Node-to-Java converters from reflective target types and
 * resolved Blue type metadata.
 */
public class ConverterFactory {
    private final TypeClassResolver typeClassResolver;
    private final ObjectFactoryRegistry objectFactories;
    private final CanonicalTypeIdentityLookup canonicalTypeIdentities;
    private final CanonicalContentIdentityLookup canonicalContentIdentities;
    private final Map<Class<?>, Converter<?>> converters = new HashMap<>();

    /**
     * Creates a converter catalog backed by a Blue type resolver.
     *
     * @param typeClassResolver resolver for Blue-declared Java types
     */
    public ConverterFactory(TypeClassResolver typeClassResolver) {
        this(typeClassResolver, ObjectFactoryRegistry.defaults());
    }

    /**
     * Creates a converter catalog with mapper-owned object factories.
     *
     * @param typeClassResolver resolver for Blue-declared Java types
     * @param objectFactories immutable object factory registry
     */
    public ConverterFactory(
            TypeClassResolver typeClassResolver,
            ObjectFactoryRegistry objectFactories) {
        this(typeClassResolver, objectFactories, null);
    }

    ConverterFactory(
            TypeClassResolver typeClassResolver,
            ObjectFactoryRegistry objectFactories,
            CanonicalTypeIdentityLookup canonicalTypeIdentities) {
        this(
                typeClassResolver,
                objectFactories,
                canonicalTypeIdentities,
                CanonicalContentIdentityLookup.directOnly());
    }

    ConverterFactory(
            TypeClassResolver typeClassResolver,
            ObjectFactoryRegistry objectFactories,
            CanonicalTypeIdentityLookup canonicalTypeIdentities,
            CanonicalContentIdentityLookup canonicalContentIdentities) {
        this.typeClassResolver = typeClassResolver != null
                ? typeClassResolver
                : new TypeClassResolver();
        this.objectFactories = Objects.requireNonNull(
                objectFactories,
                "objectFactories");
        this.canonicalTypeIdentities = canonicalTypeIdentities;
        this.canonicalContentIdentities = Objects.requireNonNull(
                canonicalContentIdentities,
                "canonicalContentIdentities");
        registerConverters();
    }

    private void registerConverters() {
        PrimitiveConverter primitiveConverter = new PrimitiveConverter();
        converters.put(
                Object.class,
                new ComplexObjectConverter(
                        this,
                        this.typeClassResolver,
                        objectFactories));
        converters.put(String.class, primitiveConverter);
        converters.put(Boolean.class, primitiveConverter);
        converters.put(Byte.class, primitiveConverter);
        converters.put(Short.class, primitiveConverter);
        converters.put(Integer.class, primitiveConverter);
        converters.put(Long.class, primitiveConverter);
        converters.put(Float.class, primitiveConverter);
        converters.put(Double.class, primitiveConverter);
        converters.put(BigInteger.class, primitiveConverter);
        converters.put(BigDecimal.class, primitiveConverter);
        CollectionConverter collectionConverter = new CollectionConverter(
                this,
                this.typeClassResolver,
                objectFactories);
        converters.put(Collection.class, collectionConverter);
        converters.put(List.class, collectionConverter);
        converters.put(Set.class, collectionConverter);
        converters.put(Queue.class, collectionConverter);
        converters.put(Deque.class, collectionConverter);
        converters.put(Enum.class, new EnumConverter());
        converters.put(
                Map.class,
                new MapConverter(
                        this,
                        this.typeClassResolver,
                        objectFactories));
        converters.put(Node.class, new NodeConverter());
//        converters.put(AnnotatedField.class, new AnnotatedFieldConverter(this));

    }

    /**
     * Selects a converter using normal Blue-type precedence.
     *
     * @param node source node, possibly {@code null}
     * @param targetType requested Java type
     * @return converter appropriate for the source and target
     */
    public Converter<?> getConverter(Node node, Type targetType) {
        return getConverter(node, targetType, false);
    }

    /**
     * Selects a converter with explicit target-type precedence.
     *
     * @param node source node, possibly {@code null}
     * @param targetType requested Java type
     * @param prioritizeTargetType whether the target type takes precedence
     *                             over resolved Blue metadata
     * @return converter appropriate for the source and target
     */
    @SuppressWarnings("unchecked")
    public Converter<?> getConverter(Node node, Type targetType, boolean prioritizeTargetType) {

        if (node == null) {
            return new NullConverter();
        }

        Class<?> rawType = MappingPayload.rawType(targetType);

        if (rawType.isEnum()) {
            return converters.get(Enum.class);
        }
        if (rawType.isArray() || Collection.class.isAssignableFrom(rawType)) {
            return converters.get(Collection.class);
        }
        if (Map.class.isAssignableFrom(rawType)) {
            return converters.get(Map.class);
        }
        if (rawType.isPrimitive() || ValueConverter.isSupportedType(rawType)) {
            return converters.get(Object.class);
        }
        Converter<?> converter = converters.get(rawType);
        if (converter == null) {
            return new ComplexObjectConverter(
                    this,
                    this.typeClassResolver,
                    objectFactories);
        }
        return converter;
    }

    /**
     * Converts an object node using generic map key/value rules.
     *
     * @param node source object node
     * @param mapType requested map type, including generic arguments
     * @return converted map, or {@code null} for absent properties
     */
    public Map<?, ?> convertMap(Node node, Type mapType) {
        MapConverter mapConverter = new MapConverter(
                this,
                this.typeClassResolver,
                objectFactories);
        return mapConverter.convert(node, mapType);
    }

    Class<?> resolveClass(
            Node node,
            TypeClassResolver resolver) {
        return canonicalTypeIdentities == null
                ? resolver.resolveClass(node)
                : resolver.resolveClass(node, canonicalTypeIdentities);
    }

    CanonicalTypeIdentityLookup canonicalTypeIdentities() {
        return canonicalTypeIdentities != null
                ? canonicalTypeIdentities
                : CanonicalTypeIdentityLookup.incomplete();
    }

    String requireCanonicalContentBlueId(Node node) {
        return canonicalContentIdentities.requireBlueId(node);
    }
}
