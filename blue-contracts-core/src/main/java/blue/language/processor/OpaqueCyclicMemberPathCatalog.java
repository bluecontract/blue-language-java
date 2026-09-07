package blue.language.processor;

import blue.language.identity.BlueIds;
import blue.language.model.Node;
import blue.language.model.wire.JsonPointer;
import blue.language.processor.util.ProcessorContractConstants;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Finds ordinary cyclic-member occurrences while leaving structural type edges to resolution. */
final class OpaqueCyclicMemberPathCatalog {
    private OpaqueCyclicMemberPathCatalog() {}

    static Set<String> find(Node document) {
        Set<String> result = new LinkedHashSet<>();
        collectOpaqueCyclicMemberPaths(
                document,
                JsonPointer.ROOT,
                result,
                new IdentityHashMap<Node, Boolean>());
        return result;
    }

    private static void collectOpaqueCyclicMemberPaths(
            Node node,
            String path,
            Set<String> result,
            IdentityHashMap<Node, Boolean> visited) {
        if (node == null) {
            return;
        }
        if (node.isReferenceOnly()) {
            if (BlueIds.hasCyclicMemberSeparator(node.getBlueId())) {
                result.add(path);
            }
            return;
        }
        if (visited.put(node, Boolean.TRUE) != null) {
            return;
        }
        try {
            if (node.getItems() != null) {
                for (int index = 0; index < node.getItems().size(); index++) {
                    collectOpaqueCyclicMemberPaths(
                            node.getItems().get(index),
                            JsonPointer.append(path, String.valueOf(index)),
                            result,
                            visited);
                }
            }
            if (node.getProperties() != null) {
                for (Map.Entry<String, Node> entry
                        : node.getProperties().entrySet()) {
                    collectOpaqueCyclicMemberPaths(
                            entry.getValue(),
                            JsonPointer.append(path, entry.getKey()),
                            result,
                            visited);
                }
            }
            collectOpaqueCyclicMemberPaths(
                    node.getContracts(),
                    JsonPointer.append(
                            path, ProcessorContractConstants.KEY_CONTRACTS),
                    result,
                    visited);
        } finally {
            visited.remove(node);
        }
    }

}
