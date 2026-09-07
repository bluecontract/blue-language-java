package blue.language.processor;

import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.model.wire.JsonPointer;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.ProcessorPointerConstants;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Finds exact processor-state witnesses that must remain authored values. */
final class ProcessorStateReferencePathCatalog {

    private static final String PATCHES = "patches";
    private static final String PATCH_VALUE = "val";

    private ProcessorStateReferencePathCatalog() {
    }

    static Set<String> find(
            Node document,
            Iterable<String> openedScopePaths) {
        Set<String> result = new LinkedHashSet<String>();
        for (String scopePath
                : ExecutableBodyPathCatalog.openedScopes(openedScopePaths)) {
            Node scope = JsonPointer.ROOT.equals(scopePath)
                    ? document
                    : NodePathEditor.getOrNull(document, scopePath);
            Node contracts = scope != null ? scope.getContracts() : null;
            Node initialized = contracts != null
                    && contracts.getProperties() != null
                    ? contracts.getProperties().get(
                            ProcessorContractConstants.KEY_INITIALIZED)
                    : null;
            Node exactDocument = initialized != null
                    && initialized.getProperties() != null
                    ? initialized.getProperties().get(
                            ProcessorContractConstants.KEY_DOCUMENT)
                    : null;
            if (exactDocument != null) {
                result.add(JsonPointer.append(
                        ProcessorEngine.resolvePointer(
                                scopePath,
                                ProcessorPointerConstants
                                        .RELATIVE_INITIALIZED),
                        ProcessorContractConstants.KEY_DOCUMENT));
            }
        }
        return result;
    }

    /**
     * Finds exact initialization witnesses carried by authored Json Patch
     * values before those effects are applied to a processing document.
     *
     * <p>Only items of a normalized result's {@code patches} list participate.
     * A replacement value is not yet located at its target, so the ordinary
     * opened-scope catalog cannot see a processor-owned marker carried by the
     * value. The validated patch target and any whole-scope value supply the
     * missing role evidence without treating unrelated patch-shaped objects
     * or arbitrary properties named {@code initialized} as processor state.</p>
     */
    static Set<String> findInPatchEffects(Node source) {
        Set<String> result = new LinkedHashSet<String>();
        collectPatchCollections(
                source,
                JsonPointer.ROOT,
                result,
                Collections.newSetFromMap(
                        new IdentityHashMap<Node, Boolean>()));
        return result;
    }

    private static void collectPatchCollections(
            Node node,
            String path,
            Set<String> result,
            Set<Node> visited) {
        if (node == null || !visited.add(node)) {
            return;
        }
        try {
            if (node.isReferenceOnly()) {
                return;
            }
            if (node.getProperties() != null) {
                for (Map.Entry<String, Node> entry
                        : node.getProperties().entrySet()) {
                    String childPath = JsonPointer.append(
                            path, entry.getKey());
                    if (PATCHES.equals(entry.getKey())) {
                        collectPatchEntries(
                                entry.getValue(), childPath, result);
                    } else {
                        collectPatchCollections(
                                entry.getValue(),
                                childPath,
                                result,
                                visited);
                    }
                }
            }
            if (node.getItems() != null) {
                for (int index = 0; index < node.getItems().size(); index++) {
                    collectPatchCollections(
                            node.getItems().get(index),
                            JsonPointer.append(path, Integer.toString(index)),
                            result,
                            visited);
                }
            }
        } finally {
            visited.remove(node);
        }
    }

    private static void collectPatchEntries(
            Node patches,
            String patchesPath,
            Set<String> result) {
        if (patches == null
                || patches.isReferenceOnly()
                || patches.getItems() == null) {
            return;
        }
        for (int index = 0; index < patches.getItems().size(); index++) {
            addPatchEffectWitness(
                    patches.getItems().get(index),
                    JsonPointer.append(
                            patchesPath, Integer.toString(index)),
                    result);
        }
    }

    private static void addPatchEffectWitness(
            Node patch,
            String patchPath,
            Set<String> result) {
        if (patch == null) {
            return;
        }
        Map<String, Node> fields = patch.getProperties();
        if (fields == null) {
            return;
        }
        String operation = scalarText(fields.get(
                ProcessorContractConstants.KEY_OPERATION));
        if (!"add".equals(operation) && !"replace".equals(operation)) {
            return;
        }
        String target = scalarText(fields.get(
                ProcessorContractConstants.KEY_PATH));
        Node value = fields.get(PATCH_VALUE);
        if (target == null || value == null) {
            return;
        }
        List<String> targetSegments;
        try {
            targetSegments = JsonPointer.split(
                    PointerUtils.assertValidRuntimePointer(target));
        } catch (IllegalArgumentException invalidPointer) {
            return;
        }
        int size = targetSegments.size();
        if (size >= 1
                && ProcessorContractConstants.KEY_CONTRACTS.equals(
                        targetSegments.get(size - 1))) {
            Node initialized = property(
                    value, ProcessorContractConstants.KEY_INITIALIZED);
            addDocumentPath(
                    initialized,
                    JsonPointer.append(
                            JsonPointer.append(
                                    patchPath, PATCH_VALUE),
                            ProcessorContractConstants.KEY_INITIALIZED),
                    result);
            return;
        }
        if (size >= 2
                && ProcessorContractConstants.KEY_CONTRACTS.equals(
                        targetSegments.get(size - 2))
                && ProcessorContractConstants.KEY_INITIALIZED.equals(
                        targetSegments.get(size - 1))) {
            addDocumentPath(
                    value,
                    JsonPointer.append(patchPath, PATCH_VALUE),
                    result);
            return;
        }
        if (size >= 3
                && ProcessorContractConstants.KEY_CONTRACTS.equals(
                        targetSegments.get(size - 3))
                && ProcessorContractConstants.KEY_INITIALIZED.equals(
                        targetSegments.get(size - 2))
                && ProcessorContractConstants.KEY_DOCUMENT.equals(
                        targetSegments.get(size - 1))) {
            result.add(JsonPointer.append(patchPath, PATCH_VALUE));
            return;
        }
        collectWholeScopeWitnesses(
                value,
                JsonPointer.append(patchPath, PATCH_VALUE),
                result,
                Collections.newSetFromMap(
                        new IdentityHashMap<Node, Boolean>()));
    }

    private static void collectWholeScopeWitnesses(
            Node node,
            String path,
            Set<String> result,
            Set<Node> visited) {
        if (node == null || !visited.add(node)) {
            return;
        }
        try {
            Node contracts = node.getContracts();
            Node initialized = contracts != null
                    ? property(
                            contracts,
                            ProcessorContractConstants.KEY_INITIALIZED)
                    : null;
            addDocumentPath(
                    initialized,
                    JsonPointer.append(
                            JsonPointer.append(
                                    path,
                                    ProcessorContractConstants.KEY_CONTRACTS),
                            ProcessorContractConstants.KEY_INITIALIZED),
                    result);
            if (node.isReferenceOnly()) {
                return;
            }
            if (node.getProperties() != null) {
                for (Map.Entry<String, Node> entry
                        : node.getProperties().entrySet()) {
                    collectWholeScopeWitnesses(
                            entry.getValue(),
                            JsonPointer.append(path, entry.getKey()),
                            result,
                            visited);
                }
            }
            if (node.getItems() != null) {
                for (int index = 0; index < node.getItems().size(); index++) {
                    collectWholeScopeWitnesses(
                            node.getItems().get(index),
                            JsonPointer.append(
                                    path, Integer.toString(index)),
                            result,
                            visited);
                }
            }
        } finally {
            visited.remove(node);
        }
    }

    private static void addDocumentPath(
            Node initialized,
            String initializedPath,
            Set<String> result) {
        if (property(
                initialized,
                ProcessorContractConstants.KEY_DOCUMENT) != null) {
            result.add(JsonPointer.append(
                    initializedPath,
                    ProcessorContractConstants.KEY_DOCUMENT));
        }
    }

    private static Node property(Node node, String key) {
        return node != null && node.getProperties() != null
                ? node.getProperties().get(key)
                : null;
    }

    private static String scalarText(Node node) {
        return node != null && node.getValue() instanceof String
                ? (String) node.getValue()
                : null;
    }
}
