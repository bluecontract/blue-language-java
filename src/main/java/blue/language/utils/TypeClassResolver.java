package blue.language.utils;

import blue.language.Blue;
import blue.language.model.BlueId;
import blue.language.model.Node;
import org.reflections.Reflections;
import org.reflections.scanners.Scanners;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class TypeClassResolver {

    private final Map<String, Class<?>> blueIdMap = new HashMap<>();

    public TypeClassResolver(String... packagesToScan) {
        for (String packageName : packagesToScan) {
            Reflections reflections = new Reflections(new ConfigurationBuilder()
                    .setUrls(ClasspathHelper.forPackage(packageName))
                    .setScanners(Scanners.TypesAnnotated, Scanners.SubTypes));

            Set<Class<?>> annotatedClasses = reflections.getTypesAnnotatedWith(BlueId.class);

            for (Class<?> clazz : annotatedClasses) {
                BlueId annotation = clazz.getAnnotation(BlueId.class);
                String[] blueIdValues = annotation.value();

                for (String blueIdValue : blueIdValues) {
                    if (blueIdMap.containsKey(blueIdValue)) {
                        throw new IllegalStateException("Duplicate BlueId value: " + blueIdValue);
                    }
                    blueIdMap.put(blueIdValue, clazz);
                }
            }
        }
    }

    public Class<?> resolveClass(Node node) {
        String blueId = getEffectiveBlueId(node);
        if (blueId == null) {
            return null;
        }

        Class<?> exactMatch = blueIdMap.get(blueId);
        if (exactMatch != null) {
            return exactMatch;
        }
        return null;
    }

    private String getEffectiveBlueId(Node node) {
        if (node.getType() != null && node.getType().getBlueId() != null) {
            return node.getType().getBlueId();
        } else if (node.getType() != null) {
            return BlueIdCalculator.calculateBlueId(node.getType());
        }
        return null;
    }

}