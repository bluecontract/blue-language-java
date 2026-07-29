package blue.language.mapping;

import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide registry of factories and default concrete implementations used
 * by Java object mapping.
 *
 * <p>Registrations affect subsequent conversions globally. Callers should
 * register custom mappings during application setup.</p>
 */
public class TypeCreatorRegistry {
    private static final Map<Class<?>, TypeCreator<?>> creators = new HashMap<>();
    private static final Map<Class<?>, Class<?>> interfaceImplementations = new HashMap<>();

    static {
        registerDefaultCreators();
        registerDefaultInterfaceImplementations();
    }

    /**
     * Creates a compatibility facade over the process-wide static registry.
     */
    public TypeCreatorRegistry() {
    }

    private static void registerDefaultCreators() {
        register(ArrayList.class, ArrayList::new);
        register(LinkedList.class, LinkedList::new);
        register(HashSet.class, HashSet::new);
        register(TreeSet.class, TreeSet::new);
        register(HashMap.class, HashMap::new);
        register(TreeMap.class, TreeMap::new);
        register(LinkedHashMap.class, LinkedHashMap::new);
        register(ConcurrentHashMap.class, ConcurrentHashMap::new);
        register(ArrayDeque.class, ArrayDeque::new);
    }

    private static void registerDefaultInterfaceImplementations() {
        registerInterfaceImplementation(List.class, ArrayList.class);
        registerInterfaceImplementation(Set.class, HashSet.class);
        registerInterfaceImplementation(Map.class, HashMap.class);
        registerInterfaceImplementation(Queue.class, LinkedList.class);
        registerInterfaceImplementation(Deque.class, ArrayDeque.class);
    }

    /**
     * Registers or replaces the factory for an exact concrete type.
     *
     * @param type exact type to construct
     * @param creator factory for fresh instances
     * @param <T> registered Java type
     */
    public static <T> void register(Class<T> type, TypeCreator<T> creator) {
        creators.put(type, creator);
    }

    /**
     * Registers the default concrete implementation for an interface.
     *
     * @param interfaceType interface requested by callers
     * @param implementationType concrete assignable implementation
     * @param <T> interface value type
     */
    public static <T> void registerInterfaceImplementation(Class<T> interfaceType, Class<? extends T> implementationType) {
        interfaceImplementations.put(interfaceType, implementationType);
    }

    /**
     * Creates an instance through a registered creator, interface mapping, or
     * no-argument constructor.
     *
     * @param type requested Java type
     * @param <T> requested Java value type
     * @return fresh instance
     * @throws IllegalArgumentException when the type cannot be instantiated
     */
    @SuppressWarnings("unchecked")
    public static <T> T createInstance(Class<T> type) {
        TypeCreator<T> creator = (TypeCreator<T>) creators.get(type);
        if (creator != null) {
            return creator.create();
        }

        Class<?> implementationType = interfaceImplementations.get(type);
        if (implementationType != null) {
            return (T) createInstance(implementationType);
        }

        if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
            throw new IllegalArgumentException("Cannot create instance of interface or abstract class: " + type);
        }

        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new IllegalArgumentException("No creator registered for type: " + type, e);
        }
    }
}
