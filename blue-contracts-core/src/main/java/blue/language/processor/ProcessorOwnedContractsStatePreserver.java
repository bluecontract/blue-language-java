package blue.language.processor;

import blue.language.model.wire.JsonPointer;
import blue.language.model.wire.ParsedJsonPointer;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorPointerConstants;
import blue.language.snapshot.BluePatchOperation;
import blue.language.snapshot.CanonicalOverlayPatchEngine;
import blue.language.snapshot.FrozenNode;

import java.util.Map;

/** Preserves authoritative processor state across whole-contracts replacement. */
final class ProcessorOwnedContractsStatePreserver {

    private ProcessorOwnedContractsStatePreserver() {
    }

    static boolean requiresExactReplacement(
            String originScopePath,
            JsonPatch.Op op,
            String path) {
        if (op != JsonPatch.Op.ADD && op != JsonPatch.Op.REPLACE) {
            return false;
        }
        String contractsPath = ProcessorEngine.resolvePointer(
                originScopePath,
                ProcessorPointerConstants.RELATIVE_CONTRACTS);
        return contractsPath.equals(PointerUtils.normalizePointer(path));
    }

    static ImmutableJsonPatch preserve(
            String originScopePath,
            ImmutableJsonPatch patch,
            FrozenNode canonicalRoot,
            FrozenNode resolvedRoot) {
        if (patch.op() != JsonPatch.Op.ADD
                && patch.op() != JsonPatch.Op.REPLACE) {
            return patch;
        }
        String contractsPath = ProcessorEngine.resolvePointer(
                originScopePath,
                ProcessorPointerConstants.RELATIVE_CONTRACTS);
        if (!contractsPath.equals(patch.normalizedPath())) {
            return patch;
        }
        FrozenNode currentCanonicalContracts = canonicalRoot.at(contractsPath);
        if (currentCanonicalContracts == null
                || currentCanonicalContracts.getProperties() == null) {
            return patch;
        }
        FrozenNode currentResolvedContracts = resolvedRoot.at(contractsPath);
        FrozenNode canonicalReplacement = patch.canonicalValue();
        FrozenNode resolvedReplacement = patch.resolvedValue();
        for (Map.Entry<String, FrozenNode> entry
                : currentCanonicalContracts.getProperties().entrySet()) {
            String key = entry.getKey();
            if (!DirectProtectedStateMutationGuard
                    .isProcessorProtectedContractKey(key)) {
                continue;
            }
            FrozenNode proposed = canonicalReplacement.property(key);
            FrozenNode retainedCanonical = entry.getValue();
            if (proposed == null
                    || !retainedCanonical.blueId().equals(
                            proposed.blueId())) {
                continue;
            }
            FrozenNode retainedResolved = currentResolvedContracts != null
                    ? currentResolvedContracts.property(key)
                    : null;
            if (retainedResolved == null) {
                throw new IllegalStateException(
                        "Resolved processor state is missing at "
                                + JsonPointer.append(contractsPath, key));
            }
            ParsedJsonPointer markerPath = ParsedJsonPointer.parse(
                    JsonPointer.append(JsonPointer.ROOT, key));
            canonicalReplacement = replace(
                    canonicalReplacement,
                    markerPath,
                    retainedCanonical);
            resolvedReplacement = replace(
                    resolvedReplacement,
                    markerPath,
                    retainedResolved);
        }
        if (canonicalReplacement == patch.canonicalValue()
                && resolvedReplacement == patch.resolvedValue()) {
            return patch;
        }
        return patch.withCanonicalAndResolvedValues(
                canonicalReplacement, resolvedReplacement);
    }

    private static FrozenNode replace(
            FrozenNode root,
            ParsedJsonPointer path,
            FrozenNode replacement) {
        return new CanonicalOverlayPatchEngine(root)
                .apply(BluePatchOperation.REPLACE, path, replacement)
                .root();
    }
}
