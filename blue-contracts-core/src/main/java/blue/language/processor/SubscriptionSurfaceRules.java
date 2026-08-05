package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.registry.RuntimeTypeKey;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.ProcessorPointerConstants;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.wire.JsonPointer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared deterministic shape, pointer, and portable-limit rules used while
 * projecting subscription surfaces.
 *
 * <p>This class deliberately contains no traversal state. A projection owns
 * traversal and ancestry; these helpers only validate one value or compare
 * one dependency with the normalized change set.</p>
 */
final class SubscriptionSurfaceRules {

    /** Normalizes and validates the changed pointers in insertion order. */
    Set<String> normalizeChanges(Set<String> changes) {
        Set<String> result = new LinkedHashSet<>();
        for (String path : changes) {
            try {
                result.add(PointerUtils.assertValidRuntimePointer(path));
            } catch (RuntimeException exception) {
                throw invalid(
                        "Invalid changed path: " + path,
                        JsonPointer.ROOT,
                        null);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    /** Reports whether a scope or contract dependency overlaps a change. */
    boolean dependencyAffected(String scopePath,
                               String dependencyPath,
                               Set<String> changes) {
        String typePath = PointerUtils.resolvePointer(
                scopePath,
                ProcessorPointerConstants.RELATIVE_TYPE);
        String terminationPath = PointerUtils.resolvePointer(
                scopePath,
                ProcessorPointerConstants.RELATIVE_TERMINATED);
        for (String changed : changes) {
            if (overlaps(changed, dependencyPath)
                    || overlaps(changed, typePath)
                    || overlaps(changed, terminationPath)
                    || JsonPointer.ROOT.equals(changed)) {
                return true;
            }
        }
        return false;
    }

    /** Reports whether the direct contracts map of a scope changed. */
    boolean sameScopeContractsAffected(String scopePath,
                                       Set<String> changes) {
        String contractsPath = PointerUtils.resolvePointer(
                scopePath,
                ProcessorPointerConstants.RELATIVE_CONTRACTS);
        for (String changed : changes) {
            if (PointerUtils.descendantOrEqual(changed, contractsPath)
                    || overlaps(changed, contractsPath)
                    && changed.equals(scopePath)) {
                return true;
            }
        }
        return false;
    }

    /** Reports whether any change overlaps the supplied branch. */
    boolean branchAffected(String branch, Set<String> changes) {
        for (String changed : changes) {
            if (overlaps(changed, branch)) {
                return true;
            }
        }
        return false;
    }

    /** Reports whether either pointer contains the other. */
    boolean overlaps(String left, String right) {
        return PointerUtils.descendantOrEqual(left, right)
                || PointerUtils.descendantOrEqual(right, left);
    }

    /** Reads and validates direct subscription keys from a channel. */
    List<String> subscriptionKeys(Node channel,
                                  String scopePath,
                                  String key) {
        Node plural = property(
                channel,
                ProcessorContractConstants.KEY_SUBSCRIPTION_KEYS);
        List<String> result = new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();
        if (plural != null) {
            if (plural.getItems() == null) {
                throw invalid(
                        "subscriptionKeys must be a List",
                        scopePath,
                        key);
            }
            for (Node item : plural.getItems()) {
                Object value = item != null ? item.getValue() : null;
                if (!(value instanceof String)
                        || ((String) value).isEmpty()
                        || !unique.add((String) value)) {
                    throw invalid(
                            "Subscription keys must be unique non-empty Text",
                            scopePath,
                            key);
                }
                result.add((String) value);
            }
            return result;
        }
        String singular = textField(
                channel,
                ProcessorContractConstants.KEY_SUBSCRIPTION_KEY);
        if (singular != null && !singular.isEmpty()) {
            result.add(singular);
        }
        return result;
    }

    /** Resolves the first recognized runtime type in a contract type chain. */
    String recognizedType(Node contract) {
        Node type = contract != null ? contract.getType() : null;
        Set<String> visited = new LinkedHashSet<>();
        while (type != null) {
            String blueId = type.getBlueId() != null
                    ? type.getBlueId()
                    : DirectBlueIdCalculator.calculateBlueId(type);
            if (!visited.add(blueId)) {
                throw new IllegalArgumentException(
                        "Cyclic effective contract type");
            }
            if (isKnownExternalType(blueId)
                    || RuntimeBlueIds.PROCESS_EMBEDDED.equals(blueId)) {
                return blueId;
            }
            if (type.isReferenceOnly()) {
                return blueId;
            }
            type = type.getType();
        }
        return null;
    }

    /** Reports whether the BlueId identifies a registered External Channel. */
    boolean isKnownExternalType(String blueId) {
        return BlueRuntimeTypeRegistry.getDefault()
                .isRegisteredSubtype(
                        blueId,
                        RuntimeTypeKey.EXTERNAL_CHANNEL);
    }

    /** Reports whether a scope carries the direct termination marker. */
    boolean directTerminated(Node scope) {
        Node contracts = scope != null ? scope.getContracts() : null;
        Node marker = contracts != null && contracts.getProperties() != null
                ? contracts.getProperties().get(
                        ProcessorContractConstants.KEY_TERMINATED)
                : null;
        return marker != null
                && RuntimeBlueIds.PROCESSING_TERMINATED_MARKER.equals(
                        recognizedType(marker));
    }

    /** Validates the portable text limits for one contract key. */
    void validateContractKey(String key,
                             GasSchedule schedule,
                             String scopePath) {
        if (key == null || key.isEmpty()) {
            throw invalid(
                    "Contract key must be non-empty", scopePath, key);
        }
        requireLimit(
                GasScheduleConstants.PortableLimit.CONTRACT_KEY_CODE_POINTS,
                key.codePointCount(0, key.length()),
                schedule.portableLimit(
                        GasScheduleConstants.PortableLimit
                                .CONTRACT_KEY_CODE_POINTS),
                scopePath,
                key);
        requireLimit(
                GasScheduleConstants.PortableLimit.CONTRACT_KEY_UTF8_BYTES,
                key.getBytes(StandardCharsets.UTF_8).length,
                schedule.portableLimit(
                        GasScheduleConstants.PortableLimit
                                .CONTRACT_KEY_UTF8_BYTES),
                scopePath,
                key);
    }

    /** Validates direct object/list cardinality limits for one node. */
    void requireObjectLimits(Node node,
                             GasSchedule schedule,
                             String scopePath,
                             String key) {
        if (node == null) {
            return;
        }
        int entries = node.getProperties() != null
                ? node.getProperties().size() : 0;
        requireLimit(
                GasScheduleConstants.PortableLimit.DIRECT_OBJECT_ENTRIES,
                entries,
                schedule.portableLimit(
                        GasScheduleConstants.PortableLimit
                                .DIRECT_OBJECT_ENTRIES),
                scopePath,
                key);
        int items = node.getItems() != null
                ? node.getItems().size() : 0;
        requireLimit(
                GasScheduleConstants.PortableLimit.DIRECT_LIST_ITEMS,
                items,
                schedule.portableLimit(
                        GasScheduleConstants.PortableLimit.DIRECT_LIST_ITEMS),
                scopePath,
                key);
    }

    /** Enforces a named portable limit with stable diagnostics. */
    void requireLimit(String name,
                      long actual,
                      long limit,
                      String scopePath,
                      String key) {
        if (actual > limit) {
            throw invalid(
                    name + " exceeds portable limit "
                            + limit + ": " + actual,
                    scopePath,
                    key);
        }
    }

    /** Reads a bounded integer field or returns its deterministic default. */
    int integerField(Node node,
                     String key,
                     int defaultValue,
                     String scopePath,
                     String contractKey) {
        Node field = property(node, key);
        Object value = field != null ? field.getValue() : null;
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Number)) {
            throw invalid(
                    key + " must be an Integer", scopePath, contractKey);
        }
        long result = ((Number) value).longValue();
        if (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE) {
            throw invalid(
                    key + " is outside Integer range",
                    scopePath,
                    contractKey);
        }
        return (int) result;
    }

    /** Resolves a descendant relative to the supplied materialized scope. */
    Node nodeAt(Node currentScope,
                String currentScopePath,
                String target) {
        String relative = PointerUtils.relativizePointer(
                currentScopePath, target);
        Node current = currentScope;
        for (String segment : JsonPointer.split(relative)) {
            if (current == null || current.getProperties() == null) {
                return null;
            }
            current = current.getProperties().get(segment);
        }
        return current;
    }

    /** Resolves an absolute pointer against an exact selected Root. */
    Node nodeAtRoot(Node root, String pointer) {
        if (JsonPointer.ROOT.equals(pointer)) {
            return root;
        }
        Node current = root;
        for (String segment : JsonPointer.split(pointer)) {
            if (current == null || current.getProperties() == null) {
                return null;
            }
            current = current.getProperties().get(segment);
        }
        return current;
    }

    /** Returns the exact retained or calculated identity of one node. */
    String exactIdentity(Node node) {
        return node.getBlueId() != null
                ? node.getBlueId()
                : DirectBlueIdCalculator.calculateBlueId(node);
    }

    /**
     * Uses an already retained identity for ancestry checks without hashing an
     * unrelated subtree.
     */
    String declaredExactIdentity(Node node) {
        return node != null ? node.getBlueId() : null;
    }

    /** Reads a scalar text property, returning {@code null} otherwise. */
    String textField(Node node, String key) {
        Node field = property(node, key);
        Object value = field != null ? field.getValue() : null;
        return value instanceof String ? (String) value : null;
    }

    /** Reads a direct property without resolving references. */
    Node property(Node node, String key) {
        return node != null && node.getProperties() != null
                ? node.getProperties().get(key)
                : null;
    }

    /** Reports whether a node is a materialized direct object. */
    boolean isObject(Node node) {
        return node != null
                && node.getItems() == null
                && !node.isReferenceOnly()
                && (node.getValue() == null
                        || node.getContracts() != null);
    }

    /** Reports whether a node is materialized rather than reference-only. */
    boolean isConcrete(Node node) {
        return node != null && !node.isReferenceOnly();
    }

    /** Creates the stable fail-closed exception for an invalid surface. */
    SubscriptionSurfaceInvalidException invalid(
            String message,
            String scopePath,
            String key) {
        return new SubscriptionSurfaceInvalidException(
                message, scopePath, key);
    }
}
