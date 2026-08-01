package blue.language.mapping;

import blue.language.model.Node;
import blue.language.utils.TypeClassResolver;
import blue.language.utils.UncheckedObjectMapper;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static blue.language.utils.Properties.OBJECT_VALUE;

/**
 * Immutable, independently configured Java-object mapping facade.
 *
 * <p>A mapper snapshots both BlueId-to-class mappings and object factories at
 * build time. Built instances contain no mutable global registration state and
 * may therefore coexist safely with different registrations in one JVM.</p>
 *
 * <p>Mapping is a serialization boundary only. {@link #toNode(Object)} does
 * not preprocess, resolve, canonicalize, or otherwise interpret the produced
 * Blue node.</p>
 */
public final class BlueMapper {

    private final TypeClassResolver typeClassResolver;
    private final NodeToObjectConverter nodeToObjectConverter;

    private BlueMapper(
            TypeClassResolver typeClassResolver,
            ObjectFactoryRegistry objectFactories) {
        this.typeClassResolver = typeClassResolver;
        this.nodeToObjectConverter = new NodeToObjectConverter(
                typeClassResolver,
                objectFactories);
    }

    /**
     * Creates an independent mapper builder with standard object factories.
     *
     * @return new mutable builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Serializes one Java object to a fresh Blue node.
     *
     * @param value non-null Java object or Blue node
     * @return newly allocated node graph
     */
    public Node toNode(Object value) {
        Objects.requireNonNull(value, OBJECT_VALUE);
        if (value instanceof Node) {
            return ((Node) value).clone();
        }
        String json = UncheckedObjectMapper.JSON_MAPPER
                .writeValueAsString(value);
        return UncheckedObjectMapper.JSON_MAPPER.readValue(
                json,
                Node.class);
    }

    /**
     * Materializes one node as the requested Java class.
     *
     * @param node source node; it is not mutated
     * @param targetClass requested Java class
     * @param <T> requested Java value type
     * @return newly allocated mapped value
     */
    public <T> T fromNode(Node node, Class<T> targetClass) {
        return nodeToObjectConverter.convert(node, targetClass);
    }

    /**
     * Materializes one node as an arbitrary reflective Java type.
     *
     * @param node source node; it is not mutated
     * @param targetType requested reflective type
     * @param prioritizeTargetType whether the requested type takes precedence
     *                             over a mapped Blue type
     * @param <T> converted Java value type
     * @return newly allocated mapped value
     */
    public <T> T fromNode(
            Node node,
            Type targetType,
            boolean prioritizeTargetType) {
        return nodeToObjectConverter.convertWithType(
                node,
                targetType,
                prioritizeTargetType);
    }

    /**
     * Round-trips a Java object or maps a supplied node to another Java class.
     *
     * @param value source object or node
     * @param targetClass requested Java class
     * @param <T> requested Java value type
     * @return newly allocated mapped value
     */
    public <T> T convert(Object value, Class<T> targetClass) {
        Objects.requireNonNull(value, OBJECT_VALUE);
        Node node = value instanceof Node
                ? (Node) value
                : toNode(value);
        return fromNode(node, targetClass);
    }

    /**
     * Resolves the Java class registered for a node's effective type.
     *
     * @param node node whose mapped class is requested
     * @return mapped class, or empty when the type is unregistered
     */
    public Optional<Class<?>> mappedClass(Node node) {
        return node == null
                ? Optional.empty()
                : Optional.ofNullable(typeClassResolver.resolveClass(node));
    }

    /**
     * Resolves the Java class registered for an exact type BlueId.
     *
     * @param blueId exact type BlueId
     * @return mapped class, or empty when the BlueId is unregistered
     */
    public Optional<Class<?>> mappedClass(String blueId) {
        return blueId == null
                ? Optional.empty()
                : Optional.ofNullable(
                        typeClassResolver.resolveClass(blueId));
    }

    /** Mutable configuration scope for one immutable mapper. */
    public static final class Builder {
        private final Map<String, Class<?>> mappedClasses =
                new LinkedHashMap<>();
        private final ObjectFactoryRegistry.Builder objectFactories =
                ObjectFactoryRegistry.builder();

        private Builder() {
        }

        /**
         * Registers one exact Blue type identity to a Java class.
         *
         * @param blueId exact type BlueId
         * @param mappedClass Java class represented by the type
         * @return this builder
         */
        public Builder register(
                String blueId,
                Class<?> mappedClass) {
            if (blueId == null || blueId.isEmpty()) {
                throw new IllegalArgumentException(
                        "blueId must not be empty");
            }
            Objects.requireNonNull(mappedClass, "mappedClass");
            Class<?> existing = mappedClasses.get(blueId);
            if (existing != null && !existing.equals(mappedClass)) {
                throw new IllegalStateException(
                        "Duplicate BlueId mapping: " + blueId);
            }
            mappedClasses.put(blueId, mappedClass);
            return this;
        }

        /**
         * Registers every type identity declared by an annotated Java class.
         *
         * @param annotatedClass class carrying a Blue type annotation
         * @return this builder
         */
        public Builder register(Class<?> annotatedClass) {
            TypeClassResolver discovered = new TypeClassResolver()
                    .registerAnnotatedClass(annotatedClass);
            return registerMappings(discovered);
        }

        /**
         * Registers or replaces the object factory for one exact Java type.
         *
         * @param type exact requested Java type
         * @param creator factory returning a fresh assignable value
         * @param <T> requested Java value type
         * @return this builder
         */
        public <T> Builder register(
                Class<T> type,
                TypeCreator<? extends T> creator) {
            objectFactories.register(type, creator);
            return this;
        }

        /**
         * Registers a concrete implementation for an interface or base type.
         *
         * @param interfaceType requested interface or abstract base
         * @param implementationType assignable concrete implementation
         * @param <T> requested Java value type
         * @return this builder
         */
        public <T> Builder registerInterfaceImplementation(
                Class<T> interfaceType,
                Class<? extends T> implementationType) {
            objectFactories.registerInterfaceImplementation(
                    interfaceType,
                    implementationType);
            return this;
        }

        /**
         * Copies the resolver's current mappings into this builder.
         *
         * @param resolver existing resolver to snapshot now
         * @return this builder
         */
        public Builder registerMappings(TypeClassResolver resolver) {
            Objects.requireNonNull(resolver, "resolver");
            for (Map.Entry<String, Class<?>> entry
                    : resolver.getBlueIdMap().entrySet()) {
                register(entry.getKey(), entry.getValue());
            }
            return this;
        }

        /**
         * Discovers annotated classes in one package and copies their mappings.
         *
         * @param packageName package to scan
         * @return this builder
         */
        public Builder scanPackage(String packageName) {
            return registerMappings(
                    new TypeClassResolver(packageName));
        }

        /**
         * Freezes all current registrations into an independent mapper.
         *
         * @return immutable mapper snapshot
         */
        public BlueMapper build() {
            TypeClassResolver resolver = new TypeClassResolver();
            for (Map.Entry<String, Class<?>> entry
                    : mappedClasses.entrySet()) {
                resolver.register(entry.getKey(), entry.getValue());
            }
            return new BlueMapper(
                    resolver,
                    objectFactories.build());
        }
    }
}
