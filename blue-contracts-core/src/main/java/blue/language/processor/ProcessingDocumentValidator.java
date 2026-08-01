package blue.language.processor;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.model.Node;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.utils.UncheckedObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Production validation that must run before a Processing Document is converted
 * into the Node model when raw map keys still need to be inspected.
 */
public final class ProcessingDocumentValidator {

    private static final Set<String> INVALID_CONTRACT_KEYS = new LinkedHashSet<>(Arrays.asList(
            BlueLanguageConstants.OBJECT_TYPE,
            BlueLanguageConstants.OBJECT_VALUE,
            BlueLanguageConstants.OBJECT_ITEMS,
            BlueLanguageConstants.OBJECT_SCHEMA,
            ProcessorContractConstants.KEY_CONTRACTS,
            BlueLanguageConstants.LEGACY_OBJECT_PROPERTIES,
            BlueLanguageConstants.LEGACY_OBJECT_CONSTRAINTS));

    private ProcessingDocumentValidator() {
    }

    /**
     * Validates raw contract keys before model conversion loses key context.
     *
     * @param rawDocument raw JSON document
     * @param parsedDocument best-effort parsed document for failure output
     * @return deterministic rejection, or {@code null} when valid
     */
    public static DocumentProcessingResult validateRaw(JsonNode rawDocument, Node parsedDocument) {
        if (rawDocument == null || rawDocument.isNull()) {
            return DocumentProcessingResult.invalidProcessingDocument(
                    fallbackDocument(parsedDocument),
                    "Invalid Processing Document: root scope must be an object");
        }
        if (!rawDocument.isObject()) {
            return DocumentProcessingResult.invalidProcessingDocument(
                    fallbackDocument(parsedDocument),
                    "Invalid Processing Document: root scope must be an object");
        }
        JsonNode contracts = rawDocument.get(ProcessorContractConstants.KEY_CONTRACTS);
        if (contracts == null || !contracts.isObject()) {
            return null;
        }
        for (String key : iterable(contracts.fieldNames())) {
            if (key == null || key.isEmpty()) {
                return DocumentProcessingResult.runtimeFatal(
                        fallbackDocument(parsedDocument),
                        "Invalid contract key: key must be non-empty",
                        ProcessorErrorCategory.InvalidRuntimePointer);
            }
            if (INVALID_CONTRACT_KEYS.contains(key)) {
                return DocumentProcessingResult.runtimeFatal(
                        fallbackDocument(parsedDocument),
                        "Invalid contract key: reserved key '" + key + "'",
                        ProcessorErrorCategory.InvalidReservedRuntimeState);
            }
        }
        return null;
    }

    /**
     * Converts a raw processing document after normalizing object-valued wrappers.
     *
     * @param rawDocument raw JSON document
     * @return mutable parsed processing document
     * @throws IllegalArgumentException when conversion fails
     */
    public static Node readProcessingDocument(JsonNode rawDocument) {
        JsonNode normalizedRawDocument = normalizeObjectValuedValueWrappers(rawDocument);
        try {
            return UncheckedObjectMapper.JSON_MAPPER.convertValue(normalizedRawDocument, Node.class);
        } catch (IllegalArgumentException ex) {
            if (normalizedRawDocument == null || !normalizedRawDocument.isObject()) {
                throw ex;
            }
            JsonNode rawContracts = normalizedRawDocument.get(ProcessorContractConstants.KEY_CONTRACTS);
            if (rawContracts == null || rawContracts.isObject()) {
                throw ex;
            }
            ObjectNode copy = normalizedRawDocument.deepCopy();
            copy.remove(ProcessorContractConstants.KEY_CONTRACTS);
            Node document = UncheckedObjectMapper.JSON_MAPPER.convertValue(copy, Node.class);
            document.contracts(UncheckedObjectMapper.JSON_MAPPER.convertValue(rawContracts, Node.class));
            return document;
        }
    }

    private static JsonNode normalizeObjectValuedValueWrappers(JsonNode node) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isObject()) {
            JsonNode value = node.get(BlueLanguageConstants.OBJECT_VALUE);
            if (value != null && (value.isObject() || value.isArray()) && node.size() == 1) {
                return normalizeObjectValuedValueWrappers(value);
            }
            ObjectNode copy = ((ObjectNode) node).deepCopy();
            java.util.Iterator<String> names = copy.fieldNames();
            java.util.List<String> fields = new java.util.ArrayList<>();
            while (names.hasNext()) {
                fields.add(names.next());
            }
            for (String field : fields) {
                copy.set(field, normalizeObjectValuedValueWrappers(copy.get(field)));
            }
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = UncheckedObjectMapper.JSON_MAPPER.createArrayNode();
            for (JsonNode item : node) {
                copy.add(normalizeObjectValuedValueWrappers(item));
            }
            return copy;
        }
        return node;
    }

    private static Node fallbackDocument(Node parsedDocument) {
        return parsedDocument != null ? parsedDocument.clone() : new Node();
    }

    private static Iterable<String> iterable(java.util.Iterator<String> iterator) {
        return () -> iterator;
    }
}
