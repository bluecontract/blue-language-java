package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.util.NodeCanonicalizer;
import blue.language.snapshot.FrozenNode;
import blue.language.model.wire.JsonPointer;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.Supplier;

/** Charges all semantic identity work before mutation planning performs it. */
final class MutationGasCharger {

    private static final String IDENTITY_REBUILD_REASON = "identity-rebuild";

    private final GasMeter meter;
    private final Supplier<FrozenNode> canonicalRoot;

    MutationGasCharger(
            GasMeter meter,
            Supplier<FrozenNode> canonicalRoot) {
        this.meter = meter;
        this.canonicalRoot = canonicalRoot;
    }

    void charge(List<PatchInput> patches) {
        for (PatchInput patch : patches) {
            if (patch == null) {
                continue;
            }
            charge(
                    patch.authoredPath(),
                    patch.op(),
                    patch.mutableValue(),
                    patch.frozenValue(),
                    patch.exactValue() != null);
        }
    }

    void charge(
            String path,
            JsonPatch.Op operation,
            Node mutableValue,
            FrozenNode frozenValue,
            boolean valueAlreadyAdmitted) {
        SemanticGasMeter semantic = meter.semantic();
        GasChargeContext context = GasChargeContext.of(
                null, null, path, IDENTITY_REBUILD_REASON);
        if (!valueAlreadyAdmitted && mutableValue != null) {
            chargeMutableIdentitySubtree(
                    mutableValue,
                    semantic,
                    context,
                    new IdentityHashMap<Node, Boolean>());
        } else if (!valueAlreadyAdmitted && frozenValue != null) {
            chargeFrozenIdentitySubtree(
                    frozenValue,
                    semantic,
                    context,
                    new IdentityHashMap<FrozenNode, Boolean>());
        }

        FrozenNode root = canonicalRoot.get();
        List<String> segments = JsonPointer.split(path);
        if (!segments.isEmpty()) {
            String parentPointer = JsonPointer.toPointer(
                    segments.subList(0, segments.size() - 1));
            chargeListPatchFold(
                    root.at(parentPointer),
                    segments.get(segments.size() - 1),
                    mutableValue != null || frozenValue != null,
                    context);
        }

        for (int count = Math.max(0, segments.size() - 1);
             count >= 0;
             count--) {
            String ancestorPath = JsonPointer.toPointer(
                    segments.subList(0, count));
            FrozenNode ancestor = root.at(ancestorPath);
            if (ancestor == null) {
                continue;
            }
            enforceRebuiltContainerLimit(
                    ancestor, ancestorPath, path, operation);
            semantic.nodeIdentitiesEstablished(1L, context);
            if (!ancestor.hasItems()) {
                semantic.objectMembersRebuilt(
                        directMemberCount(ancestor), context);
                semantic.directIdentityInput(
                        NodeCanonicalizer.directIdentityCanonicalSize(
                                ancestor.toNode()),
                        context);
            }
        }
    }

    void enforcePortableLimit(
            ProcessorErrorCategory category,
            String limitName,
            long observed) {
        long limit = meter.schedule().portableLimit(limitName);
        if (observed > limit) {
            throw new PortableLimitExceededException(
                    category, limitName, observed, limit);
        }
    }

    private void chargeListPatchFold(
            FrozenNode parent,
            String finalSegment,
            boolean resultContainsWrittenValue,
            GasChargeContext context) {
        if (parent == null || !parent.hasItems()) {
            return;
        }
        long beforeLength = parent.getItems().size();
        long index;
        if ("-".equals(finalSegment)) {
            index = beforeLength;
        } else {
            try {
                index = Long.parseLong(finalSegment);
            } catch (NumberFormatException ignored) {
                return;
            }
        }
        if (!resultContainsWrittenValue) {
            meter.semantic().listRemoveAt(
                    Math.max(0L, beforeLength - 1L),
                    index,
                    context);
        } else if (index >= beforeLength) {
            meter.semantic().verifiedListAppend(
                    beforeLength, 1L, context);
        } else {
            meter.semantic().listReplaceAt(
                    beforeLength, index, context);
        }
    }

    private void chargeMutableIdentitySubtree(
            Node node,
            SemanticGasMeter semantic,
            GasChargeContext context,
            IdentityHashMap<Node, Boolean> visited) {
        if (node == null
                || node.isReferenceOnly()
                || visited.put(node, Boolean.TRUE) != null) {
            return;
        }
        enforceMaterializedContainerLimit(node);
        semantic.nodeIdentitiesEstablished(1L, context);
        if (node.getItems() != null) {
            for (Node item : node.getItems()) {
                chargeMutableIdentitySubtree(
                        item, semantic, context, visited);
            }
            semantic.fullListIdentity(node.getItems().size(), context);
        } else {
            semantic.objectMembersRebuilt(
                    directMemberCount(node), context);
            semantic.directIdentityInput(
                    NodeCanonicalizer.directIdentityCanonicalSize(node),
                    context);
        }
        chargeMutableIdentitySubtree(node.getType(), semantic, context, visited);
        chargeMutableIdentitySubtree(node.getItemType(), semantic, context, visited);
        chargeMutableIdentitySubtree(node.getKeyType(), semantic, context, visited);
        chargeMutableIdentitySubtree(node.getValueType(), semantic, context, visited);
        chargeMutableIdentitySubtree(node.getContracts(), semantic, context, visited);
        chargeMutableIdentitySubtree(node.getBlue(), semantic, context, visited);
        if (node.getProperties() != null) {
            for (Node child : node.getProperties().values()) {
                chargeMutableIdentitySubtree(
                        child, semantic, context, visited);
            }
        }
    }

    private void chargeFrozenIdentitySubtree(
            FrozenNode node,
            SemanticGasMeter semantic,
            GasChargeContext context,
            IdentityHashMap<FrozenNode, Boolean> visited) {
        if (node == null
                || node.isReferenceOnly()
                || visited.put(node, Boolean.TRUE) != null) {
            return;
        }
        enforceMaterializedContainerLimit(node);
        semantic.nodeIdentitiesEstablished(1L, context);
        if (node.hasItems()) {
            for (FrozenNode item : node.getItems()) {
                chargeFrozenIdentitySubtree(
                        item, semantic, context, visited);
            }
            semantic.fullListIdentity(node.getItems().size(), context);
        } else {
            semantic.objectMembersRebuilt(
                    directMemberCount(node), context);
            semantic.directIdentityInput(
                    NodeCanonicalizer.directIdentityCanonicalSize(
                            node.toNode()),
                    context);
        }
        chargeFrozenIdentitySubtree(node.getType(), semantic, context, visited);
        chargeFrozenIdentitySubtree(node.getItemType(), semantic, context, visited);
        chargeFrozenIdentitySubtree(node.getKeyType(), semantic, context, visited);
        chargeFrozenIdentitySubtree(node.getValueType(), semantic, context, visited);
        chargeFrozenIdentitySubtree(node.getContracts(), semantic, context, visited);
        chargeFrozenIdentitySubtree(node.getBlue(), semantic, context, visited);
        if (node.getProperties() != null) {
            for (FrozenNode child : node.getProperties().values()) {
                chargeFrozenIdentitySubtree(
                        child, semantic, context, visited);
            }
        }
    }

    private void enforceRebuiltContainerLimit(
            FrozenNode container,
            String containerPath,
            String patchPath,
            JsonPatch.Op operation) {
        long observed;
        String limitName;
        if (container.hasItems()) {
            observed = container.getItems().size();
            limitName = GasScheduleConstants.PortableLimit.DIRECT_LIST_ITEMS;
        } else {
            observed = directMemberCount(container);
            limitName = GasScheduleConstants.PortableLimit.DIRECT_OBJECT_ENTRIES;
        }
        String parent = parentPointer(patchPath);
        if (containerPath.equals(parent)) {
            FrozenNode existing = container.at(
                    JsonPointer.ROOT
                            + JsonPointer.escape(lastSegment(patchPath)));
            if (operation == JsonPatch.Op.REMOVE && existing != null) {
                observed--;
            } else if ((operation == JsonPatch.Op.ADD
                    || operation == JsonPatch.Op.REPLACE)
                    && existing == null) {
                observed++;
            }
        }
        enforcePortableLimit(
                ProcessorErrorCategory.DirectNodeLimitExceeded,
                limitName,
                observed);
    }

    private void enforceMaterializedContainerLimit(Node node) {
        enforcePortableLimit(
                ProcessorErrorCategory.DirectNodeLimitExceeded,
                node.getItems() != null
                        ? GasScheduleConstants.PortableLimit.DIRECT_LIST_ITEMS
                        : GasScheduleConstants.PortableLimit.DIRECT_OBJECT_ENTRIES,
                node.getItems() != null
                        ? node.getItems().size()
                        : directMemberCount(node));
    }

    private void enforceMaterializedContainerLimit(FrozenNode node) {
        enforcePortableLimit(
                ProcessorErrorCategory.DirectNodeLimitExceeded,
                node.hasItems()
                        ? GasScheduleConstants.PortableLimit.DIRECT_LIST_ITEMS
                        : GasScheduleConstants.PortableLimit.DIRECT_OBJECT_ENTRIES,
                node.hasItems()
                        ? node.getItems().size()
                        : directMemberCount(node));
    }

    private static String parentPointer(String pointer) {
        List<String> segments = JsonPointer.split(pointer);
        return segments.isEmpty()
                ? JsonPointer.ROOT
                : JsonPointer.toPointer(
                        segments.subList(0, segments.size() - 1));
    }

    private static String lastSegment(String pointer) {
        List<String> segments = JsonPointer.split(pointer);
        return segments.isEmpty()
                ? ""
                : segments.get(segments.size() - 1);
    }

    private static long directMemberCount(Node node) {
        long members = node.getProperties() != null
                ? node.getProperties().size() : 0L;
        if (node.getName() != null) members++;
        if (node.getDescription() != null) members++;
        if (node.getType() != null) members++;
        if (node.getItemType() != null) members++;
        if (node.getKeyType() != null) members++;
        if (node.getValueType() != null) members++;
        if (node.getValue() != null) members++;
        if (node.getSchema() != null) members++;
        if (node.getContracts() != null) members++;
        if (node.getBlue() != null) members++;
        if (node.getMergePolicy() != null) members++;
        return members;
    }

    private static long directMemberCount(FrozenNode node) {
        long members = node.getProperties() != null
                ? node.getProperties().size() : 0L;
        if (node.getName() != null) members++;
        if (node.getDescription() != null) members++;
        if (node.getType() != null) members++;
        if (node.getItemType() != null) members++;
        if (node.getKeyType() != null) members++;
        if (node.getValueType() != null) members++;
        if (node.getValue() != null) members++;
        if (node.getSchema() != null) members++;
        if (node.getContracts() != null) members++;
        if (node.getBlue() != null) members++;
        if (node.getMergePolicy() != null) members++;
        return members;
    }
}
