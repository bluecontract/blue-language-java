package blue.language.utils;

import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import org.reflections.Reflections;
import org.reflections.scanners.Scanners;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;
import org.reflections.util.FilterBuilder;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class TypeClassResolver {

    private final Map<String, Class<?>> blueIdMap = new HashMap<>();

    public TypeClassResolver() {
    }

    public TypeClassResolver(String... packagesToScan) {
        for (String packageName : packagesToScan) {
            scanPackage(packageName);
        }
    }

    public TypeClassResolver scanPackage(String packageName) {
        Reflections reflections = new Reflections(new ConfigurationBuilder()
                .setUrls(ClasspathHelper.forPackage(packageName))
                .filterInputsBy(new FilterBuilder().includePackage(packageName))
                .setScanners(Scanners.TypesAnnotated, Scanners.SubTypes));

        Set<Class<?>> annotatedClasses = reflections.getTypesAnnotatedWith(TypeBlueId.class);

        for (Class<?> clazz : annotatedClasses) {
            registerAnnotatedClass(clazz);
        }
        return this;
    }

    public TypeClassResolver registerAnnotatedClass(Class<?> clazz) {
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

    public TypeClassResolver register(String blueId, Class<?> clazz) {
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

    public Class<?> resolveClass(Node node) {
        String blueId = getEffectiveBlueId(node);
        if (blueId == null) {
            return null;
        }

        return resolveClass(blueId);
    }

    public Class<?> resolveClass(String blueId) {
        return blueIdMap.get(blueId);
    }

    private String getEffectiveBlueId(Node node) {
        if (node.getType() != null && node.getType().getBlueId() != null) {
            return node.getType().getBlueId();
        } else if (node.getType() != null) {
            return BlueIdCalculator.calculateBlueId(node.getType());
        }
        return null;
    }

    public Map<String, Class<?>> getBlueIdMap() {
        return Collections.unmodifiableMap(blueIdMap);
    }
}
