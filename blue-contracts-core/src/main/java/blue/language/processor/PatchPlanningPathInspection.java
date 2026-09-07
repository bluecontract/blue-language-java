package blue.language.processor;

import blue.language.model.wire.BlueLanguageConstants;
import blue.language.model.wire.JsonPointer;
import blue.language.model.wire.ParsedJsonPointer;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.ProcessorPointerConstants;
import blue.language.snapshot.FrozenNode;

import java.util.List;

/** Typed-ancestor queries used by patch conformance planning. */
final class PatchPlanningPathInspection {

    private PatchPlanningPathInspection() {
    }

    static boolean hasTypedNodeBetween(
            FrozenNode resolvedRoot,
            String originScope,
            String changedPath) {
        ImmutablePatchPlanner planner =
                ImmutablePatchPlanner.forFrozen(resolvedRoot);
        String normalizedOrigin = PointerUtils.normalizeScope(originScope);
        String current = PointerUtils.normalizePointer(changedPath);
        while (true) {
            if (hasTypeMetadata(planner.read(current))) {
                return true;
            }
            if (current.equals(normalizedOrigin)
                    || JsonPointer.ROOT.equals(current)) {
                return false;
            }
            current = parentPointer(current);
        }
    }

    /** Returns whether the patch target uses object-member upsert semantics. */
    static boolean targetsObjectMember(
            ImmutablePatchPlanner planner,
            ParsedJsonPointer path) {
        if (path.isRoot()) {
            return false;
        }
        FrozenNode parent = planner.read(path.parent());
        if (parent == null || !parent.hasItems()) {
            return parent != null;
        }
        String member = path.segments().get(
                path.segments().size() - 1);
        return BlueLanguageConstants.OBJECT_VALUE.equals(member)
                || ProcessorContractConstants.KEY_CONTRACTS.equals(member);
    }

    /** Returns whether any application patch changes the contracts subtree. */
    static boolean changesApplicationContracts(
            List<BatchPatchRecord> records) {
        for (BatchPatchRecord record : records) {
            if (record.processorManagedConformanceBypass()) {
                continue;
            }
            String contractsPath = ProcessorEngine.resolvePointer(
                    record.originScope(),
                    ProcessorPointerConstants.RELATIVE_CONTRACTS);
            String patchPath = PointerUtils.normalizePointer(record.path());
            if (PointerUtils.descendantOrEqual(patchPath, contractsPath)) {
                return true;
            }
            if (!PointerUtils.descendantOrEqual(contractsPath, patchPath)) {
                continue;
            }
            List<String> patchSegments = JsonPointer.split(patchPath);
            List<String> contractSegments = JsonPointer.split(contractsPath);
            String relativeContracts = JsonPointer.toPointer(
                    contractSegments.subList(
                            patchSegments.size(),
                            contractSegments.size()));
            FrozenNode before = record.beforeAtPatchTime();
            FrozenNode after = record.afterAtPatchTime();
            FrozenNode beforeContracts = before != null
                    ? before.at(relativeContracts)
                    : null;
            FrozenNode afterContracts = after != null
                    ? after.at(relativeContracts)
                    : null;
            if (!sameIdentity(beforeContracts, afterContracts)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasTypeMetadata(FrozenNode node) {
        return node != null
                && (node.getType() != null
                || node.getItemType() != null
                || node.getKeyType() != null
                || node.getValueType() != null);
    }

    private static boolean sameIdentity(FrozenNode left, FrozenNode right) {
        return left == null
                ? right == null
                : right != null && left.blueId().equals(right.blueId());
    }

    private static String parentPointer(String pointer) {
        List<String> segments = JsonPointer.split(pointer);
        return segments.isEmpty()
                ? JsonPointer.ROOT
                : JsonPointer.toPointer(
                        segments.subList(0, segments.size() - 1));
    }
}
