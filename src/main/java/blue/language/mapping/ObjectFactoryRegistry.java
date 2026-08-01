package blue.language.mapping;

import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import static blue.language.utils.Properties.OBJECT_TYPE;

/**
 * Immutable per-mapper registry of Java object factories and interface
 * implementations.
 *
 * <p>A registry is assembled by a {@link Builder}, defensively copied at
 * {@link Builder#build()}, and then safe to share between mapping calls. It
 * contains no process-wide mutable registration state.</p>
 */
public final class ObjectFactoryRegistry {

    private final Map<Class<?>, TypeCreator<?>> creators;
    private final Map<Class<?>, Class<?>> interfaceImplementations;

    private ObjectFactoryRegistry(
            Map<Class<?>, TypeCreator<?>> creators,
            Map<Class<?>, Class<?>> interfaceImplementations) {
        this.creators = Collections.unmodifiableMap(
                new LinkedHashMap<>(creators));
        this.interfaceImplementations = Collections.unmodifiableMap(
                new LinkedHashMap<>(interfaceImplementations));
    }

    /**
     * Creates a builder initialized with the standard collection factories.
     *
     * @return mutable builder whose output is independent of other builders
     */
    public static Builder builder() {
        return new Builder(true);
    }

    /**
     * Returns the immutable default registry.
     *
     * @return shared immutable default registry
     */
    public static ObjectFactoryRegistry defaults() {
        return DefaultsHolder.DEFAULTS;
    }

    /**
     * Creates a fresh instance for the requested Java type.
     *
     * @param type requested exact type or registered interface
     * @param <T> requested Java value type
     * @return fresh assignable instance
     * @throws IllegalArgumentException when no safe construction path exists
     */
    public <T> T create(Class<T> type) {
        Objects.requireNonNull(type, OBJECT_TYPE);
        return create(type, new HashSet<Class<?>>());
    }

    @SuppressWarnings("unchecked")
    private <T> T create(Class<T> type, Set<Class<?>> activeTypes) {
        if (!activeTypes.add(type)) {
            throw new IllegalArgumentException(
                    "Cyclic interface implementation mapping for type: "
                            + type.getName());
        }
        try {
            TypeCreator<?> creator = creators.get(type);
            if (creator != null) {
                Object value = creator.create();
                if (value == null || !type.isInstance(value)) {
                    throw new IllegalArgumentException(
                            "Factory returned a non-assignable value for type: "
                                    + type.getName());
                }
                return (T) value;
            }

            Class<?> implementation = interfaceImplementations.get(type);
            if (implementation != null) {
                return (T) create(implementation, activeTypes);
            }
            if (type.isInterface()
                    || Modifier.isAbstract(type.getModifiers())) {
                throw new IllegalArgumentException(
                        "Cannot create interface or abstract type: "
                                + type.getName());
            }
            try {
                return type.getDeclaredConstructor().newInstance();
            } catch (Exception failure) {
                throw new IllegalArgumentException(
                        "No object factory registered for type: "
                                + type.getName(),
                        failure);
            }
        } finally {
            activeTypes.remove(type);
        }
    }

    /** Mutable construction scope for one immutable registry. */
    public static final class Builder {
        private final Map<Class<?>, TypeCreator<?>> creators =
                new LinkedHashMap<>();
        private final Map<Class<?>, Class<?>> interfaceImplementations =
                new LinkedHashMap<>();

        private Builder(boolean includeDefaults) {
            if (includeDefaults) {
                registerDefaults();
            }
        }

        /**
         * Registers or replaces the factory for an exact type.
         *
         * @param type exact requested type
         * @param creator factory returning a fresh assignable instance
         * @param <T> requested Java value type
         * @return this builder
         */
        public <T> Builder register(
                Class<T> type,
                TypeCreator<? extends T> creator) {
            creators.put(
                    Objects.requireNonNull(type, OBJECT_TYPE),
                    Objects.requireNonNull(creator, "creator"));
            return this;
        }

        /**
         * Registers or replaces the concrete type used for an interface.
         *
         * @param interfaceType requested interface or abstract base
         * @param implementationType assignable concrete implementation
         * @param <T> requested Java value type
         * @return this builder
         */
        public <T> Builder registerInterfaceImplementation(
                Class<T> interfaceType,
                Class<? extends T> implementationType) {
            Objects.requireNonNull(interfaceType, "interfaceType");
            Objects.requireNonNull(
                    implementationType,
                    "implementationType");
            if (!interfaceType.isAssignableFrom(implementationType)) {
                throw new IllegalArgumentException(
                        implementationType.getName()
                                + " is not assignable to "
                                + interfaceType.getName());
            }
            interfaceImplementations.put(
                    interfaceType,
                    implementationType);
            return this;
        }

        /**
         * Freezes this builder's current registrations.
         *
         * @return independent immutable registry snapshot
         */
        public ObjectFactoryRegistry build() {
            return new ObjectFactoryRegistry(
                    creators,
                    interfaceImplementations);
        }

        private void registerDefaults() {
            register(ArrayList.class, ArrayList::new);
            register(LinkedList.class, LinkedList::new);
            register(HashSet.class, HashSet::new);
            register(TreeSet.class, TreeSet::new);
            register(HashMap.class, HashMap::new);
            register(TreeMap.class, TreeMap::new);
            register(LinkedHashMap.class, LinkedHashMap::new);
            register(ConcurrentHashMap.class, ConcurrentHashMap::new);
            register(ArrayDeque.class, ArrayDeque::new);
            registerInterfaceImplementation(List.class, ArrayList.class);
            registerInterfaceImplementation(Set.class, HashSet.class);
            registerInterfaceImplementation(Map.class, HashMap.class);
            registerInterfaceImplementation(Queue.class, LinkedList.class);
            registerInterfaceImplementation(Deque.class, ArrayDeque.class);
        }
    }

    private static final class DefaultsHolder {
        private static final ObjectFactoryRegistry DEFAULTS =
                ObjectFactoryRegistry.builder().build();

        private DefaultsHolder() {
        }
    }
}
