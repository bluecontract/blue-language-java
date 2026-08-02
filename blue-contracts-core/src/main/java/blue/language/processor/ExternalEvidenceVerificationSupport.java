package blue.language.processor;

import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.ProcessorPointerConstants;
import blue.language.processor.util.PointerUtils;
import blue.language.model.wire.JsonPointer;

import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Set;

/** Shared deterministic primitives for external-delivery evidence checks. */
final class ExternalEvidenceVerificationSupport {

    private ExternalEvidenceVerificationSupport() {
    }

    static InvalidExecutionEvidenceException invalid(String message) {
        return new InvalidExecutionEvidenceException(message);
    }

    static ExecutionEvidenceUnavailableException unavailable(
            String message,
            Set<String> requiredExactBlueIds) {
        return new ExecutionEvidenceUnavailableException(
                message, requiredExactBlueIds);
    }

    static String occurrenceKey(
            String scopePath, String channelKey) {
        return PointerUtils.normalizeScope(scopePath)
                + ProcessorIdentityConstants.SELECTOR_COMPONENT_DELIMITER
                + channelKey;
    }

    static boolean hasDirectTerminatedMarker(Node scope) {
        Node contracts = scope != null ? scope.getContracts() : null;
        Node marker = contracts != null
                && contracts.getProperties() != null
                ? contracts.getProperties().get(
                ProcessorContractConstants.KEY_TERMINATED)
                : null;
        if (marker == null) {
            return false;
        }
        try {
            ProcessorEngine.validateTerminationMarker(
                    marker,
                    PointerUtils.resolvePointer(
                            JsonPointer.ROOT,
                            ProcessorPointerConstants
                                    .RELATIVE_TERMINATED));
            return true;
        } catch (RuntimeException exception) {
            throw invalid("Invalid direct terminated marker");
        }
    }

    static Node nodeAt(Node root, String pointer) {
        if (JsonPointer.ROOT.equals(pointer)) {
            return root;
        }
        Node current = root;
        for (String segment : JsonPointer.split(pointer)) {
            if (current == null
                    || current.getProperties() == null) {
                return null;
            }
            current = current.getProperties().get(segment);
        }
        return current;
    }

    static boolean isValidScope(String scopePath, Node node) {
        if (node == null || node.isReferenceOnly()) {
            return false;
        }
        if (JsonPointer.ROOT.equals(PointerUtils.normalizeScope(
                scopePath))) {
            return true;
        }
        return node.getItems() == null
                && (node.getValue() == null
                        || node.getContracts() != null);
    }

    static int depth(String scopePath) {
        return JsonPointer.split(scopePath).size();
    }

    static boolean requiresEmbeddedRouting(
            String path,
            Set<String> requestedScopes) {
        String normalized = PointerUtils.normalizeScope(path);
        for (String requestedScope : requestedScopes) {
            String requested =
                    PointerUtils.normalizeScope(requestedScope);
            if (!requested.equals(normalized)
                    && PointerUtils.descendantOrEqual(
                    requested, normalized)) {
                return true;
            }
        }
        return false;
    }

    static boolean requestedBranch(
            String candidate,
            Set<String> requestedScopes) {
        String normalized = PointerUtils.normalizeScope(candidate);
        for (String scope : requestedScopes) {
            if (PointerUtils.descendantOrEqual(
                    scope, normalized)) {
                return true;
            }
        }
        return false;
    }

    static Set<String> referencedBlueIds(Node... roots) {
        Set<String> result = new LinkedHashSet<>();
        IdentityHashMap<Node, Boolean> visited =
                new IdentityHashMap<>();
        if (roots != null) {
            for (Node root : roots) {
                collectReferencedBlueIds(
                        root, result, visited);
            }
        }
        return result;
    }

    private static void collectReferencedBlueIds(
            Node node,
            Set<String> result,
            IdentityHashMap<Node, Boolean> visited) {
        if (node == null
                || visited.put(node, Boolean.TRUE) != null) {
            return;
        }
        if (node.isReferenceOnly()) {
            if (node.getBlueId() != null
                    && !node.getBlueId().isEmpty()) {
                result.add(node.getBlueId());
            }
            return;
        }
        collectReferencedBlueIds(node.getType(), result, visited);
        collectReferencedBlueIds(node.getSchema(), result, visited);
        collectReferencedBlueIds(node.getContracts(), result, visited);
        if (node.getProperties() != null) {
            for (Node child : node.getProperties().values()) {
                collectReferencedBlueIds(child, result, visited);
            }
        }
        if (node.getItems() != null) {
            for (Node child : node.getItems()) {
                collectReferencedBlueIds(child, result, visited);
            }
        }
    }

    private static void collectReferencedBlueIds(
            Schema schema,
            Set<String> result,
            IdentityHashMap<Node, Boolean> visited) {
        if (schema == null) {
            return;
        }
        if (schema.isReferenceOnly()) {
            if (schema.getBlueId() != null
                    && !schema.getBlueId().isEmpty()) {
                result.add(schema.getBlueId());
            }
            return;
        }
        collectReferencedBlueIds(schema.getRequired(), result, visited);
        collectReferencedBlueIds(schema.getMinLength(), result, visited);
        collectReferencedBlueIds(schema.getMaxLength(), result, visited);
        collectReferencedBlueIds(schema.getMinimum(), result, visited);
        collectReferencedBlueIds(schema.getMaximum(), result, visited);
        collectReferencedBlueIds(
                schema.getExclusiveMinimum(), result, visited);
        collectReferencedBlueIds(
                schema.getExclusiveMaximum(), result, visited);
        collectReferencedBlueIds(schema.getMultipleOf(), result, visited);
        collectReferencedBlueIds(schema.getMinItems(), result, visited);
        collectReferencedBlueIds(schema.getMaxItems(), result, visited);
        collectReferencedBlueIds(schema.getUniqueItems(), result, visited);
        collectReferencedBlueIds(schema.getMinFields(), result, visited);
        collectReferencedBlueIds(schema.getMaxFields(), result, visited);
        if (schema.getEnum() != null) {
            for (Node value : schema.getEnum()) {
                collectReferencedBlueIds(value, result, visited);
            }
        }
    }
}
