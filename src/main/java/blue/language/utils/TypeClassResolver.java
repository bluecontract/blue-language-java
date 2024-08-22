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

    public TypeClassResolver(String... packagesToScan) {
        for (String packageName : packagesToScan) {
            Reflections reflections = new Reflections(new ConfigurationBuilder()
                    .setUrls(ClasspathHelper.forPackage(packageName))
                    .filterInputsBy(new FilterBuilder().includePackage(packageName))
                    .setScanners(Scanners.TypesAnnotated, Scanners.SubTypes));

            Set<Class<?>> annotatedClasses = reflections.getTypesAnnotatedWith(TypeBlueId.class);

            for (Class<?> clazz : annotatedClasses) {
                TypeBlueId annotation = clazz.getAnnotation(TypeBlueId.class);
                registerClass(clazz, annotation);
            }
        }
    }

    private void registerClass(Class<?> clazz, TypeBlueId annotation) {
        String blueId = BlueIdResolver.resolveBlueId(clazz);
        if (blueId != null) {
            if (blueIdMap.containsKey(blueId)) {
                throw new IllegalStateException("Duplicate BlueId value: " + blueId);
            }
            blueIdMap.put(blueId, clazz);
        }
    }

    public Class<?> resolveClass(Node node) {
        String blueId = getEffectiveBlueId(node);
        if (blueId == null) {
            return null;
        }

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