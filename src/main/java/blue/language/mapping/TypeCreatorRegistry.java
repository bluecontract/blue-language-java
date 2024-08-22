package blue.language.mapping;

import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TypeCreatorRegistry {
    private static final Map<Class<?>, TypeCreator<?>> creators = new HashMap<>();
    private static final Map<Class<?>, Class<?>> interfaceImplementations = new HashMap<>();

    static {
        registerDefaultCreators();
        registerDefaultInterfaceImplementations();
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

    public static <T> void register(Class<T> type, TypeCreator<T> creator) {
        creators.put(type, creator);
    }

    public static <T> void registerInterfaceImplementation(Class<T> interfaceType, Class<? extends T> implementationType) {
        interfaceImplementations.put(interfaceType, implementationType);
    }

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