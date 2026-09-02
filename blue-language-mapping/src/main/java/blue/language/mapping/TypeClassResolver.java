package blue.language.mapping;

import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import org.reflections.Reflections;
import org.reflections.scanners.Scanners;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;
import org.reflections.util.FilterBuilder;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Thread-safe registry from released type BlueIds to Java classes.
 *
 * <p>Explicit registration is the deterministic default. Optional package
 * scanning is an integration convenience: discovered classes are sorted by
 * binary name before registration, so a fixed classpath produces a fixed
 * registry. Duplicate BlueIds may be re-registered only for the same class.
 * The exposed map is a live, unmodifiable, synchronization-safe view.</p>
 */
public class TypeClassResolver {

    private final Map<String, Class<?>> blueIdMap = new LinkedHashMap<>();
    private final Map<String, Class<?>> blueIdView = Collections.unmodifiableMap(
            new AbstractMap<String, Class<?>>() {
                private final Set<Entry<String, Class<?>>> entries =
                        new AbstractSet<Entry<String, Class<?>>>() {
                            @Override
                            public Iterator<Entry<String, Class<?>>> iterator() {
                                synchronized (TypeClassResolver.this) {
                                    return Collections.unmodifiableMap(
                                            new LinkedHashMap<>(blueIdMap))
                                            .entrySet()
                                            .iterator();
                                }
                            }

                            @Override
                            public int size() {
                                synchronized (TypeClassResolver.this) {
                                    return blueIdMap.size();
                                }
                            }

                            @Override
                            public boolean contains(Object entry) {
                                synchronized (TypeClassResolver.this) {
                                    return blueIdMap.entrySet().contains(entry);
                                }
                            }
                        };

                @Override
                public Class<?> get(Object key) {
                    synchronized (TypeClassResolver.this) {
                        return blueIdMap.get(key);
                    }
                }

                @Override
                public boolean containsKey(Object key) {
                    synchronized (TypeClassResolver.this) {
                        return blueIdMap.containsKey(key);
                    }
                }

                @Override
                public int size() {
                    synchronized (TypeClassResolver.this) {
                        return blueIdMap.size();
                    }
                }

                @Override
                public Set<Entry<String, Class<?>>> entrySet() {
                    return entries;
                }
            });

    /** Creates an empty registry. */
    public TypeClassResolver() {
    }

    /**
     * Creates a registry and optionally scans the supplied packages in order.
     *
     * @param packagesToScan package names to scan
     */
    public TypeClassResolver(String... packagesToScan) {
        for (String packageName : packagesToScan) {
            scanPackage(packageName);
        }
    }

    /**
     * Discovers and registers every {@link TypeBlueId}-annotated class in a
     * package. Explicit {@link #register(String, Class)} calls avoid scanning
     * and are preferred by deterministic runtime assembly.
     *
     * @param packageName package to scan
     * @return this registry
     */
    public synchronized TypeClassResolver scanPackage(String packageName) {
        Reflections reflections = new Reflections(new ConfigurationBuilder()
                .setUrls(ClasspathHelper.forPackage(packageName))
                .filterInputsBy(new FilterBuilder().includePackage(packageName))
                .setScanners(Scanners.TypesAnnotated, Scanners.SubTypes));

        List<Class<?>> annotatedClasses = reflections
                .getTypesAnnotatedWith(TypeBlueId.class)
                .stream()
                .sorted((left, right) -> left.getName()
                        .compareTo(right.getName()))
                .collect(Collectors.toList());

        for (Class<?> clazz : annotatedClasses) {
            registerAnnotatedClass(clazz);
        }
        return this;
    }

    /**
     * Registers all usable BlueIds declared by one annotated class.
     *
     * @param clazz annotated class to register
     * @return this registry
     */
    public synchronized TypeClassResolver registerAnnotatedClass(Class<?> clazz) {
        TypeBlueId annotation = clazz.getAnnotation(TypeBlueId.class);
        if (annotation == null) {
            throw new IllegalArgumentException("Class lacks @TypeBlueId: " + clazz.getName());
        }
        boolean registered = false;
        if (!annotation.defaultValue().isEmpty()) {
            register(annotation.defaultValue(), clazz);
            registered = true;
        }
        for (String blueId : annotation.value()) {
            if (blueId != null && !blueId.isEmpty()) {
                register(blueId, clazz);
                registered = true;
            }
        }
        if (!registered) {
            String blueId = BlueIdResolver.resolveBlueId(clazz);
            if (blueId != null) {
                register(blueId, clazz);
            }
        }
        return this;
    }

    /**
     * Registers one exact mapping.
     *
     * @param blueId exact type BlueId
     * @param clazz Java class represented by the BlueId
     * @return this registry
     * @throws IllegalStateException if the BlueId already maps to another class
     */
    public synchronized TypeClassResolver register(String blueId, Class<?> clazz) {
        if (blueId == null || blueId.isEmpty()) {
            throw new IllegalArgumentException("blueId must not be empty");
        }
        if (clazz == null) {
            throw new IllegalArgumentException("clazz must not be null");
        }
        Class<?> existing = blueIdMap.get(blueId);
        if (existing != null && !existing.equals(clazz)) {
            throw new IllegalStateException("Duplicate BlueId value: " + blueId);
        }
        blueIdMap.put(blueId, clazz);
        return this;
    }

    /**
     * Resolves the exact referenced type of a node.
     *
     * <p>This evidence-free entry point intentionally accepts only a pure
     * canonical type reference. A materialized or inline type body is not
     * itself canonical identity input and therefore cannot be hashed here.</p>
     *
     * @param node node whose effective type should be resolved
     * @return registered Java class, or {@code null} if unregistered
     * @throws NullPointerException if {@code node} is null
     * @throws IllegalStateException if the effective type is materialized or
     *         inline and no resolver evidence was supplied
     */
    public synchronized Class<?> resolveClass(Node node) {
        Objects.requireNonNull(node, "node");
        Node type = node.getType();
        if (type == null) {
            return null;
        }
        if (!type.isReferenceOnly()) {
            throw new IllegalStateException(
                    "Resolving a materialized or inline type requires "
                            + "resolver-issued canonical identity evidence");
        }
        String blueId = type.getBlueId();
        if (blueId == null) {
            return null;
        }

        return resolveClass(blueId);
    }

    /**
     * Resolves the effective type of a node using exact resolver evidence.
     *
     * <p>Pure references are already canonical identity input. Expanded and
     * inline type bodies must be covered by the supplied lookup; incomplete
     * evidence fails closed.</p>
     *
     * @param node node whose effective type should be resolved
     * @param typeIdentities resolver-issued canonical type identities
     * @return registered Java class, or {@code null} if unregistered
     * @throws NullPointerException if {@code node} is null, or if a
     *         materialized type is encountered and {@code typeIdentities} is
     *         null
     * @throws IllegalStateException if required canonical type evidence is
     *         unavailable or conflicting
     */
    public synchronized Class<?> resolveClass(
            Node node,
            CanonicalTypeIdentityLookup typeIdentities) {
        Objects.requireNonNull(node, "node");
        Node type = node.getType();
        if (type == null) {
            return null;
        }
        String blueId = type.isReferenceOnly()
                ? type.getBlueId()
                : Objects.requireNonNull(typeIdentities, "typeIdentities")
                .requireCanonicalTypeBlueId(type);
        return resolveClass(blueId);
    }

    /**
     * Resolves an exact BlueId.
     *
     * @param blueId exact type BlueId
     * @return registered Java class, or {@code null} if unregistered
     */
    public synchronized Class<?> resolveClass(String blueId) {
        return blueIdMap.get(blueId);
    }

    /**
     * Returns a live unmodifiable view of registered mappings.
     *
     * @return synchronization-safe BlueId-to-class view
     */
    public synchronized Map<String, Class<?>> getBlueIdMap() {
        return blueIdView;
    }

}
