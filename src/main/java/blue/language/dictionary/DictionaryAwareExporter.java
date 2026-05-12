package blue.language.dictionary;

import blue.language.model.Node;
import blue.language.model.Schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static blue.language.utils.Properties.CORE_TYPE_BLUE_IDS;

public final class DictionaryAwareExporter {

    private final DictionaryRegistry registry;
    private final ExportContext context;

    public DictionaryAwareExporter(DictionaryRegistry registry, ExportContext context) {
        this.registry = registry != null ? registry : new DictionaryRegistry();
        this.context = context != null ? context : ExportContext.empty();
    }

    public Node export(Node node) {
        validateContext();
        if (node == null) {
            return null;
        }
        if (registry.isEmpty()) {
            return node.clone();
        }
        return transformNode(node, new LinkedHashSet<String>());
    }

    private void validateContext() {
        for (Map.Entry<String, String> entry : context.dictionaries().entrySet()) {
            Optional<TypeDictionary> dictionary = registry.dictionary(entry.getKey());
            if (dictionary.isPresent() && !dictionary.get().supportsDictionaryBlueId(entry.getValue())) {
                throw new IllegalArgumentException("Unknown dictionary BlueId \"" + entry.getValue()
                        + "\" for dictionary \"" + entry.getKey() + "\".");
            }
        }
    }

    private Node transformNode(Node node, Set<String> inliningStack) {
        if (node == null) {
            return null;
        }

        Node result = node.clone();
        result.type(transformTypeReference(result.getType(), inliningStack));
        result.itemType(transformTypeReference(result.getItemType(), inliningStack));
        result.keyType(transformTypeReference(result.getKeyType(), inliningStack));
        result.valueType(transformTypeReference(result.getValueType(), inliningStack));

        if (result.getItems() != null) {
            List<Node> transformedItems = new ArrayList<>(result.getItems().size());
            for (Node item : result.getItems()) {
                transformedItems.add(transformNode(item, inliningStack));
            }
            result.items(transformedItems);
        }

        if (result.getProperties() != null) {
            Map<String, Node> transformedProperties = new LinkedHashMap<>();
            for (Map.Entry<String, Node> entry : result.getProperties().entrySet()) {
                transformedProperties.put(entry.getKey(), transformNode(entry.getValue(), inliningStack));
            }
            result.properties(transformedProperties);
        }

        if (result.getSchema() != null) {
            result.schema(transformSchema(result.getSchema(), inliningStack));
        }

        return result;
    }

    private Node transformTypeReference(Node typeNode, Set<String> inliningStack) {
        if (typeNode == null) {
            return null;
        }

        String blueId = typeNode.getBlueId();
        if (blueId == null) {
            return transformNode(typeNode, inliningStack);
        }

        if (CORE_TYPE_BLUE_IDS.contains(blueId)) {
            return typeNode.clone();
        }

        Optional<DictionaryRegistry.OwnedType> owned = registry.typeOwner(blueId);
        if (!owned.isPresent()) {
            return transformNode(typeNode, inliningStack);
        }

        TypeDictionary dictionary = owned.get().dictionary();
        String currentBlueId = owned.get().currentBlueId();
        Optional<String> targetDictionaryBlueId = context.dictionaryBlueId(dictionary.name());

        if (targetDictionaryBlueId.isPresent()) {
            Optional<String> targetTypeBlueId = dictionary.typeBlueIdFor(currentBlueId, targetDictionaryBlueId.get());
            if (targetTypeBlueId.isPresent()) {
                return new Node().blueId(targetTypeBlueId.get());
            }
        }

        if (!context.inlineUnsupportedTypes()) {
            throw new IllegalArgumentException("Type \"" + currentBlueId + "\" from dictionary \""
                    + dictionary.name() + "\" cannot be represented by the requested export context.");
        }

        return inlineDefinition(dictionary, currentBlueId, inliningStack);
    }

    private Node inlineDefinition(TypeDictionary dictionary, String currentBlueId, Set<String> inliningStack) {
        if (inliningStack.contains(currentBlueId)) {
            throw new IllegalArgumentException("Cycle detected while inlining dictionary type: "
                    + inliningStack + " -> " + currentBlueId);
        }

        Node definition = dictionary.definition(currentBlueId)
                .orElseThrow(() -> new IllegalArgumentException("Missing definition for dictionary type: " + currentBlueId));

        inliningStack.add(currentBlueId);
        try {
            Node inline = definition.clone().blueId(null);
            return transformNode(inline, inliningStack);
        } finally {
            inliningStack.remove(currentBlueId);
        }
    }

    private Schema transformSchema(Schema schema, Set<String> inliningStack) {
        Schema result = schema.clone();
        result.required(transformNode(result.getRequired(), inliningStack));
        result.allowMultiple(transformNode(result.getAllowMultiple(), inliningStack));
        result.minLength(transformNode(result.getMinLength(), inliningStack));
        result.maxLength(transformNode(result.getMaxLength(), inliningStack));
        result.minimum(transformNode(result.getMinimum(), inliningStack));
        result.maximum(transformNode(result.getMaximum(), inliningStack));
        result.exclusiveMinimum(transformNode(result.getExclusiveMinimum(), inliningStack));
        result.exclusiveMaximum(transformNode(result.getExclusiveMaximum(), inliningStack));
        result.multipleOf(transformNode(result.getMultipleOf(), inliningStack));
        result.minItems(transformNode(result.getMinItems(), inliningStack));
        result.maxItems(transformNode(result.getMaxItems(), inliningStack));
        result.uniqueItems(transformNode(result.getUniqueItems(), inliningStack));
        result.minFields(transformNode(result.getMinFields(), inliningStack));
        result.maxFields(transformNode(result.getMaxFields(), inliningStack));

        if (result.getEnum() != null) {
            List<Node> transformedEnum = new ArrayList<>(result.getEnum().size());
            for (Node enumValue : result.getEnum()) {
                transformedEnum.add(transformNode(enumValue, inliningStack));
            }
            result.enumValues(transformedEnum);
        }

        return result;
    }
}
