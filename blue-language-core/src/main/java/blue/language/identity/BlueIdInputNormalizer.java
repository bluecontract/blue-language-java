package blue.language.identity;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.model.Node;
import blue.language.identity.NodeToBlueIdInput;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static blue.language.model.wire.BlueLanguageConstants.LIST_CONTROL_EMPTY;
import static blue.language.model.wire.BlueLanguageConstants.LIST_CONTROL_POS;
import static blue.language.model.wire.BlueLanguageConstants.LIST_CONTROL_PREVIOUS;
import static blue.language.model.wire.BlueLanguageConstants.LIST_CONTROL_REPLACE;
import static blue.language.model.wire.BlueLanguageConstants.OBJECT_BLUE_ID;

/**
 * Projects nodes and sanitizes map/list/scalar inputs before direct identity
 * hashing.
 *
 * <p>Strict node validation remains centralized in the Language node
 * projection. This class owns the representation-independent normalization
 * that removes null object fields and validates list control placement.</p>
 */
public final class BlueIdInputNormalizer {

    /** Creates the stateless input normalizer. */
    public BlueIdInputNormalizer() {
    }

    /**
     * Projects an exact Blue node to normalized direct identity input.
     *
     * @param node strict BlueId input
     * @return normalized map, list, or scalar input
     */
    public Object normalize(Node node) {
        return normalizeCanonicalInput(NodeToBlueIdInput.get(node));
    }

    /**
     * Projects an ordered list of exact elements to normalized list input.
     *
     * @param nodes ordered exact elements
     * @return normalized list input
     */
    public List<Object> normalizeElements(List<Node> nodes) {
        return normalizeElements(nodes, false);
    }

    /**
     * Normalizes an already projected map/list/scalar identity value.
     *
     * @param input projected identity value
     * @return defensive normalized representation
     */
    public Object normalizeCanonicalInput(Object input) {
        if (input == null) {
            throw new IllegalArgumentException(
                    "Root null is not valid BlueId input.");
        }
        if (input instanceof Map) {
            return cleanMap(castMap(input), true);
        }
        if (input instanceof List) {
            return cleanList(castList(input));
        }
        return input;
    }

    Object normalizeAllowingCyclicPlaceholders(Node node) {
        return normalizeCanonicalInput(
                NodeToBlueIdInput.getAllowingCyclicPlaceholders(node));
    }

    List<Object> normalizeElementsAllowingCyclicPlaceholders(
            List<Node> nodes) {
        return normalizeElements(nodes, true);
    }

    private List<Object> normalizeElements(
            List<Node> nodes,
            boolean allowCyclicPlaceholders) {
        if (nodes == null) {
            throw new IllegalArgumentException(
                    "BlueId input list must not be null.");
        }
        List<Object> elements = new ArrayList<>(nodes.size());
        for (int index = 0; index < nodes.size(); index++) {
            elements.add(allowCyclicPlaceholders
                    ? NodeToBlueIdInput
                    .getListElementAllowingCyclicPlaceholders(
                            nodes.get(index),
                            index)
                    : NodeToBlueIdInput.getListElement(
                            nodes.get(index),
                            index));
        }
        return castList(normalizeCanonicalInput(elements));
    }

    private Object cleanObjectField(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map) {
            Map<String, Object> cleaned = cleanMap(castMap(value), false);
            return cleaned.isEmpty() ? null : cleaned;
        }
        if (value instanceof List) {
            return cleanList(castList(value));
        }
        return value;
    }

    private Object cleanListElement(Object value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "Direct BlueId input must use { \"$empty\": true } for null list placeholders.");
        }
        if (value instanceof Map) {
            Map<String, Object> map = castMap(value);
            if (map.containsKey(LIST_CONTROL_EMPTY)) {
                validateEmptyPlaceholder(map);
            }
            if (map.isEmpty()) {
                throw new IllegalArgumentException(
                        "Direct BlueId input must use { \"$empty\": true } for empty object list placeholders.");
            }
            Map<String, Object> cleaned = cleanMap(map, false);
            if (cleaned.isEmpty()) {
                throw new IllegalArgumentException(
                        "Direct BlueId input must use { \"$empty\": true } for empty object list placeholders.");
            }
            return cleaned;
        }
        if (value instanceof List) {
            return cleanList(castList(value));
        }
        return value;
    }

    private Map<String, Object> cleanMap(
            Map<String, Object> map,
            boolean root) {
        if (map.containsKey(LIST_CONTROL_POS)) {
            throw new IllegalArgumentException(
                    "\"$pos\" overlays are not valid direct BlueId input.");
        }
        if (map.containsKey(LIST_CONTROL_REPLACE)) {
            throw new IllegalArgumentException(
                    "\"$replace\" overlays are not valid direct BlueId input.");
        }
        if (map.containsKey(LIST_CONTROL_PREVIOUS)
                && !isPreviousControl(map)) {
            throw new IllegalArgumentException(
                    "\"$previous\" must have shape { blueId: <PrevListBlueId> } and appear only as the first list item.");
        }
        Map<String, Object> cleaned = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Object cleanedValue = cleanObjectField(entry.getValue());
            if (cleanedValue != null) {
                cleaned.put(entry.getKey(), cleanedValue);
            }
        }
        if (root || !cleaned.isEmpty()) {
            return cleaned;
        }
        return cleaned;
    }

    private List<Object> cleanList(List<Object> list) {
        List<Object> cleaned = new ArrayList<>();
        for (int index = 0; index < list.size(); index++) {
            Object item = list.get(index);
            if (index == 0 && isPreviousControl(item)) {
                cleaned.add(item);
                continue;
            }
            if (hasInvalidPreviousControl(item) || isPreviousControl(item)) {
                throw new IllegalArgumentException(
                        "\"$previous\" must appear only as the first list item.");
            }
            cleaned.add(cleanListElement(item));
        }
        return cleaned;
    }

    private void validateEmptyPlaceholder(Map<String, Object> map) {
        if (map.size() == 1
                && Boolean.TRUE.equals(map.get(LIST_CONTROL_EMPTY))) {
            return;
        }
        throw new IllegalArgumentException(
                "\"$empty\" list placeholder must have exact shape { \"$empty\": true }.");
    }

    private boolean isPreviousControl(Object item) {
        if (!(item instanceof Map)) {
            return false;
        }
        Map<?, ?> map = (Map<?, ?>) item;
        return map.size() == 1
                && map.containsKey(LIST_CONTROL_PREVIOUS)
                && map.get(LIST_CONTROL_PREVIOUS) instanceof Map
                && ((Map<?, ?>) map.get(LIST_CONTROL_PREVIOUS)).size() == 1
                && ((Map<?, ?>) map.get(LIST_CONTROL_PREVIOUS))
                .containsKey(OBJECT_BLUE_ID)
                && ((Map<?, ?>) map.get(LIST_CONTROL_PREVIOUS))
                .get(OBJECT_BLUE_ID) instanceof String;
    }

    private boolean hasInvalidPreviousControl(Object item) {
        return item instanceof Map
                && ((Map<?, ?>) item).containsKey(LIST_CONTROL_PREVIOUS)
                && !isPreviousControl(item);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Object> castList(Object value) {
        return (List<Object>) value;
    }
}
