package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.util.PointerUtils;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.JsonPointer;
import blue.language.utils.NodeToBlueIdInput;
import blue.language.utils.Nodes;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compares the processor-owned state whose effective meaning may not be
 * changed by an application patch.
 */
final class ProtectedStateGuard {

    private static final String[] HISTORY_KEYS = {
            "initialized", "terminated", "checkpoint"
    };

    private ProtectedStateGuard() {
    }

    static void verifyUnchanged(FrozenNode beforeCanonical,
                                FrozenNode beforeResolved,
                                FrozenNode afterCanonical,
                                FrozenNode afterResolved) {
        verifyUnchanged(
                beforeCanonical,
                beforeResolved,
                afterCanonical,
                afterResolved,
                Collections.<String>emptySet());
    }

    static void verifyUnchanged(FrozenNode beforeCanonical,
                                FrozenNode beforeResolved,
                                FrozenNode afterCanonical,
                                FrozenNode afterResolved,
                                Set<String> wholeEmbeddedChildPatches) {
        Set<String> participatingScopes = participatingScopes(
                beforeResolved);
        participatingScopes.addAll(participatingScopes(afterResolved));
        Map<String, String> before = snapshot(
                beforeCanonical, beforeResolved, participatingScopes);
        Map<String, String> after = snapshot(
                afterCanonical,
                afterResolved,
                participatingScopes);
        verifyEqual(before, after, wholeEmbeddedChildPatches);
    }

    static void verifyEffectiveUnchanged(FrozenNode beforeResolved,
                                         FrozenNode afterResolved) {
        Set<String> participatingScopes = participatingScopes(
                beforeResolved);
        participatingScopes.addAll(participatingScopes(afterResolved));
        Map<String, String> before = effectiveSnapshot(
                beforeResolved, participatingScopes);
        Map<String, String> after = effectiveSnapshot(
                afterResolved, participatingScopes);
        verifyEqual(before, after);
    }

    private static void verifyEqual(Map<String, String> before,
                                    Map<String, String> after) {
        verifyEqual(before, after, Collections.<String>emptySet());
    }

    private static void verifyEqual(Map<String, String> before,
                                    Map<String, String> after,
                                    Set<String> wholeEmbeddedChildPatches) {
        if (before.equals(after)) {
            return;
        }
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());
        for (String key : keys) {
            String left = before.get(key);
            String right = after.get(key);
            if (left == null ? right != null : !left.equals(right)) {
                if (permittedWholeChildStateRemoval(
                        key,
                        left,
                        right,
                        wholeEmbeddedChildPatches)) {
                    continue;
                }
                throw new ProcessorFailureException(
                        ProcessorErrorCategory.ProtectedProcessorStateMutation,
                        "Application patch changed protected processor state at "
                                + key
                                + " (before=" + left
                                + ", after=" + right + ")");
            }
        }
        return;
    }

    private static boolean permittedWholeChildStateRemoval(
            String key,
            String before,
            String after,
            Set<String> wholeEmbeddedChildPatches) {
        /*
         * Contracts 1.0 §5.8 permits an ancestor to remove or replace an
         * immediate embedded child root. Losing the old occurrence also loses
         * its direct processor state. This exception is deliberately
         * one-way: a replacement still cannot introduce or alter protected
         * state.
         */
        if (before == null
                || after != null
                || wholeEmbeddedChildPatches == null
                || wholeEmbeddedChildPatches.isEmpty()) {
            return false;
        }
        int separator = key.indexOf(':');
        if (separator < 0 || separator + 1 >= key.length()) {
            return false;
        }
        String protectedPath = key.substring(separator + 1);
        for (String childPath : wholeEmbeddedChildPatches) {
            if (childPath != null
                    && PointerUtils.descendantOrEqual(
                    protectedPath,
                    childPath)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> effectiveSnapshot(
            FrozenNode resolved,
            Set<String> scopes) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String scope : scopes) {
            collectEffective(
                    resolved != null ? resolved.at(scope) : null,
                    scope,
                    result);
        }
        return result;
    }

    private static Map<String, String> snapshot(FrozenNode canonical,
                                                FrozenNode resolved,
                                                Set<String> scopes) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String scope : scopes) {
            collectDirect(canonical != null
                            ? canonical.at(scope)
                            : null,
                    scope,
                    result);
            collectEffective(resolved != null ? resolved.at(scope) : null,
                    scope,
                    result);
        }
        return result;
    }

    /**
     * Discovers only Root and object scopes selected transitively by an
     * effective Process Embedded declaration. Ordinary nested objects and list
     * entries are application data, even when they happen to contain a field
     * named {@code contracts}.
     */
    private static Set<String> participatingScopes(FrozenNode resolvedRoot) {
        Set<String> result = new LinkedHashSet<>();
        result.add("/");
        if (resolvedRoot == null) {
            return result;
        }
        Deque<String> pending = new ArrayDeque<>();
        pending.add("/");
        while (!pending.isEmpty()) {
            String scope = pending.removeFirst();
            FrozenNode scopeNode = resolvedRoot.at(scope);
            FrozenNode embedded = contract(scopeNode, "embedded");
            FrozenNode paths = embedded != null
                    ? embedded.property("paths")
                    : null;
            List<FrozenNode> items = paths != null
                    ? paths.getItems()
                    : null;
            if (items == null) {
                continue;
            }
            for (FrozenNode item : items) {
                Object value = item != null ? item.getValue() : null;
                if (!(value instanceof String)) {
                    continue;
                }
                String child;
                String relative;
                try {
                    relative = PointerUtils
                            .assertValidRuntimePointer((String) value);
                    child = PointerUtils.resolvePointer(scope, relative);
                } catch (IllegalArgumentException invalidPath) {
                    /*
                     * Shape and boundary validation own malformed declarations.
                     * Protected-state comparison must not reclassify them.
                     */
                    continue;
                }
                FrozenNode childNode = objectMemberAt(
                        scopeNode, relative);
                if (!isObjectScope(childNode) || !result.add(child)) {
                    continue;
                }
                pending.addLast(child);
            }
        }
        return result;
    }

    private static void collectDirect(FrozenNode node,
                                      String path,
                                      Map<String, String> result) {
        if (node == null) {
            return;
        }
        FrozenNode contracts = node.getContracts();
        if (contracts != null) {
            for (String key : HISTORY_KEYS) {
                putIdentity(result,
                        "direct:" + contractPath(path, key),
                        contracts.property(key));
            }
        }
    }

    private static void collectEffective(FrozenNode node,
                                         String path,
                                         Map<String, String> result) {
        if (node == null) {
            return;
        }
        FrozenNode contracts = node.getContracts();
        if (contracts != null) {
            putEffectiveIdentity(result,
                    "effective:" + contractPath(path, "embedded"),
                    withoutEmbeddedPaths(contracts.property("embedded")));
            putEffectiveIdentity(result,
                    "effective:" + contractPath(path, "generalization"),
                    contracts.property("generalization"));
        }
    }

    private static FrozenNode contract(FrozenNode scope, String key) {
        FrozenNode contracts = scope != null ? scope.getContracts() : null;
        return contracts != null ? contracts.property(key) : null;
    }

    private static FrozenNode objectMemberAt(FrozenNode scope,
                                             String relativePath) {
        FrozenNode current = scope;
        for (String segment : JsonPointer.split(relativePath)) {
            if (!isObjectScope(current)) {
                return null;
            }
            current = current.property(segment);
        }
        return current;
    }

    private static boolean isObjectScope(FrozenNode node) {
        return node != null
                && node.getValue() == null
                && !node.hasItems()
                && !node.isReferenceOnly();
    }

    private static FrozenNode withoutEmbeddedPaths(FrozenNode embedded) {
        if (embedded == null) {
            return null;
        }
        Node stripped = embedded.toNode();
        if (stripped.getProperties() != null) {
            stripped.getProperties().remove("paths");
        }
        NodeToBlueIdInput.stripResolvedBlueIdMetadata(stripped);
        return Nodes.isEmptyNode(stripped)
                ? null
                : FrozenNode.fromResolvedNode(stripped);
    }

    private static void putIdentity(Map<String, String> result,
                                    String path,
                                    FrozenNode node) {
        if (node != null) {
            result.put(path, node.blueId());
        }
    }

    private static void putEffectiveIdentity(Map<String, String> result,
                                             String path,
                                             FrozenNode node) {
        if (node != null) {
            Node normalized = node.toNode();
            NodeToBlueIdInput.stripResolvedBlueIdMetadata(normalized);
            result.put(path,
                    FrozenNode.fromResolvedNode(normalized).blueId());
        }
    }

    private static String contractPath(String scopePath, String key) {
        String contracts = childPath(scopePath, "contracts");
        return childPath(contracts, key);
    }

    private static String childPath(String parent, String segment) {
        String escaped = JsonPointer.escape(segment);
        return "/".equals(parent)
                ? "/" + escaped
                : parent + "/" + escaped;
    }
}
