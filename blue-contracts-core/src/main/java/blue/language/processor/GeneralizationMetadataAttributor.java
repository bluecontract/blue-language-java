package blue.language.processor;

import blue.language.conformance.ConformancePlan;
import blue.language.model.Node;
import blue.language.model.wire.JsonPointer;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Attributes committed generated type metadata to its causal authored patch. */
final class GeneralizationMetadataAttributor {

    private GeneralizationMetadataAttributor() {
    }

    static List<BatchPatchResult.GeneralizationMetadataWrite> attribute(
            FrozenNode finalCanonical,
            FrozenNode finalResolved,
            List<String> changedPaths,
            List<BatchPatchRecord> records,
            Function<BatchPatchRecord, ConformancePlan> prefixPlanner) {
        Set<String> uniquePaths = new LinkedHashSet<>();
        for (String path : changedPaths != null
                ? changedPaths
                : Collections.<String>emptyList()) {
            if (GeneralizationMetadataPaths.isMetadataPath(path)) {
                uniquePaths.add(path);
            }
        }
        Map<String, FrozenNode> finalValues = new LinkedHashMap<>();
        for (String path : uniquePaths) {
            FrozenNode value = metadataValue(
                    finalCanonical, finalResolved, path);
            if (value != null) {
                finalValues.put(path, value);
            }
        }
        if (finalValues.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, Integer> requiringIndexes = new LinkedHashMap<>();
        /*
         * The final batch plan can omit a nested type write after an earlier
         * valid prefix has widened an ancestor. Recover that causal evidence
         * only when the prefix's generated identity is still the committed
         * final identity; a later overlapping repair therefore cannot leak a
         * transient generated write into the committed update sequence.
         */
        for (int recordIndex = 0; recordIndex < records.size(); recordIndex++) {
            BatchPatchRecord record = records.get(recordIndex);
            ConformancePlan prefixPlan;
            try {
                prefixPlan = prefixPlanner.apply(record);
            } catch (RuntimeException invalidPrefix) {
                /*
                 * Atomic batches may pass through an invalid intermediate
                 * document that a later authored patch repairs. Such a
                 * prefix cannot be the source of a committed generated write.
                 */
                continue;
            }
            FrozenNode prefixCanonical = prefixPlan.canonicalRoot() != null
                    ? prefixPlan.canonicalRoot()
                    : record.canonicalPlan().root();
            FrozenNode prefixResolved = prefixPlan.root();
            Set<String> prefixChangedPaths =
                    new LinkedHashSet<>(prefixPlan.changedPaths());
            for (String path : prefixChangedPaths) {
                if (!GeneralizationMetadataPaths.isMetadataPath(path)
                        || requiringIndexes.containsKey(path)) {
                    continue;
                }
                FrozenNode finalValue = finalValues.get(path);
                if (finalValue == null) {
                    finalValue = metadataValue(
                            finalCanonical, finalResolved, path);
                }
                if (finalValue == null) {
                    continue;
                }
                FrozenNode prefixValue = GeneralizationMetadataPaths.read(
                        prefixCanonical, path);
                if (prefixValue == null) {
                    prefixValue = GeneralizationMetadataPaths.read(
                            prefixResolved, path);
                }
                if (prefixValue != null
                        && sameIdentity(prefixValue, finalValue)) {
                    finalValues.put(path, finalValue);
                    requiringIndexes.put(path, recordIndex);
                }
            }
        }

        if (requiringIndexes.size() != finalValues.size()) {
            Set<String> missing = new LinkedHashSet<>(finalValues.keySet());
            missing.removeAll(requiringIndexes.keySet());
            throw new ProcessorFailureException(
                    ProcessorErrorCategory.TypeGeneralizationFailure,
                    "GeneralizationAttributionUnavailable: generated metadata "
                            + missing
                            + " cannot be tied to an authored patch");
        }

        List<BatchPatchResult.GeneralizationMetadataWrite> writes =
                new ArrayList<>(finalValues.size());
        List<String> orderedPaths = new ArrayList<>(finalValues.keySet());
        Collections.sort(
                orderedPaths,
                (left, right) -> Integer.compare(
                        JsonPointer.split(right).size(),
                        JsonPointer.split(left).size()));
        for (String path : orderedPaths) {
            writes.add(new BatchPatchResult.GeneralizationMetadataWrite(
                    path,
                    finalValues.get(path),
                    requiringIndexes.get(path)));
        }
        return writes;
    }

    private static FrozenNode metadataValue(
            FrozenNode canonicalRoot,
            FrozenNode resolvedRoot,
            String path) {
        FrozenNode value = GeneralizationMetadataPaths.read(
                canonicalRoot, path);
        if (value != null) {
            return value;
        }
        FrozenNode resolvedValue = GeneralizationMetadataPaths.read(
                resolvedRoot, path);
        if (resolvedValue == null) {
            return null;
        }
        String blueId = resolvedValue.getReferenceBlueId() != null
                ? resolvedValue.getReferenceBlueId()
                : resolvedValue.blueId();
        return FrozenNode.fromResolvedNode(new Node().blueId(blueId));
    }

    private static boolean sameIdentity(
            FrozenNode left,
            FrozenNode right) {
        return Objects.equals(
                left.getReferenceBlueId() != null
                        ? left.getReferenceBlueId()
                        : left.blueId(),
                right.getReferenceBlueId() != null
                        ? right.getReferenceBlueId()
                        : right.blueId());
    }
}
