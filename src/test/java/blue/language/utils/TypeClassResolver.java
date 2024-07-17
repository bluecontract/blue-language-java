package blue.language.utils;

import blue.language.Blue;
import blue.language.model.BlueId;
import blue.language.model.Node;
import org.reflections.Reflections;
import org.reflections.scanners.Scanners;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;

import java.util.*;
import java.util.stream.Collectors;

import static blue.language.utils.UncheckedObjectMapper.JSON_MAPPER;

public class TypeClassResolver {

    private final Blue blue;
    private final Map<String, Class<?>> blueIdMap = new HashMap<>();

    public TypeClassResolver(Blue blue, String... packagesToScan) {
        this.blue = blue;
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

    public Object toObject(Node node) {
        return toObjectRecursive(node);
    }

    private Object toObjectRecursive(Node node) {
        String blueId = getEffectiveBlueId(node);

        Class<?> clazz = blueIdMap.get(blueId);
        if (clazz != null) {
            Node resolved = blue.resolve(node);

            // Remove the type field
            resolved.type((Node) null);

            // Convert properties recursively
            if (resolved.getProperties() != null) {
                Map<String, Node> newProperties = new HashMap<>();
                for (Map.Entry<String, Node> entry : resolved.getProperties().entrySet()) {
                    Object processedValue = toObjectRecursive(entry.getValue());
                    if (processedValue instanceof Node) {
                        newProperties.put(entry.getKey(), (Node) processedValue);
                    } else {
                        // If it's not a Node, we create a new Node with the processed value
                        newProperties.put(entry.getKey(), new Node().value(processedValue));
                    }
                }
                resolved.properties(newProperties);
            }

            // Convert items recursively
            if (resolved.getItems() != null) {
                List<Node> newItems = resolved.getItems().stream()
                        .map(item -> {
                            Object processedItem = toObjectRecursive(item);
                            if (processedItem instanceof Node) {
                                return (Node) processedItem;
                            } else {
                                // If it's not a Node, we create a new Node with the processed value
                                return new Node().value(processedItem);
                            }
                        })
                        .collect(Collectors.toList());
                resolved.items(newItems);
            }

            return JSON_MAPPER.convertValue(NodeToObject.get(resolved, NodeToObject.Strategy.SIMPLE), clazz);
        }

        return NodeToObject.get(node, NodeToObject.Strategy.SIMPLE);
    }

    private String getEffectiveBlueId(Node node) {
        if (node.getType() != null && node.getType().getBlueId() != null) {
            return node.getType().getBlueId();
        } else if (node.getBlueId() != null) {
            return node.getBlueId();
        } else if (node.getType() != null) {
            return BlueIdCalculator.calculateBlueId(node.getType());
        }
        return null;
    }
}