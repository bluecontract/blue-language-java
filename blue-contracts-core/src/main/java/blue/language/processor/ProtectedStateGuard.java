package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.snapshot.FrozenNode;
import blue.language.model.wire.JsonPointer;
import blue.language.identity.NodeToBlueIdInput;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Compares the processor-owned state whose effective meaning may not be
 * changed by an application patch.
 */
final class ProtectedStateGuard {

    private static final String[] HISTORY_KEYS = {
            ProcessorContractConstants.KEY_INITIALIZED,
            ProcessorContractConstants.KEY_TERMINATED,
            ProcessorContractConstants.KEY_CHECKPOINT
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
        verifyUnchanged(
                beforeCanonical,
                beforeResolved,
                afterCanonical,
                afterResolved,
                wholeEmbeddedChildPatches,
                null,
                false,
                null);
    }

    static void verifyUnchanged(FrozenNode beforeCanonical,
                                FrozenNode beforeResolved,
                                FrozenNode afterCanonical,
                                FrozenNode afterResolved,
                                Set<String> wholeEmbeddedChildPatches,
                                ProcessingSnapshotManager evidenceManager) {
        verifyUnchanged(
                beforeCanonical,
                beforeResolved,
                afterCanonical,
                afterResolved,
                wholeEmbeddedChildPatches,
                evidenceManager,
                true,
                null);
    }

    /**
     * Verifies the processor-owned state of the revision-bound participating
     * closure. The caller supplies the scopes already opened by this
     * invocation so an application patch cannot turn protected-state checking
     * into a complete scan of unrelated embedded branches.
     */
    static void verifyUnchanged(FrozenNode beforeCanonical,
                                FrozenNode beforeResolved,
                                FrozenNode afterCanonical,
                                FrozenNode afterResolved,
                                Set<String> wholeEmbeddedChildPatches,
                                ProcessingSnapshotManager evidenceManager,
                                Set<String> participatingScopePaths) {
        verifyUnchanged(
                beforeCanonical,
                beforeResolved,
                afterCanonical,
                afterResolved,
                wholeEmbeddedChildPatches,
                evidenceManager,
                true,
                participatingScopePaths);
    }

    private static void verifyUnchanged(FrozenNode beforeCanonical,
                                        FrozenNode beforeResolved,
                                        FrozenNode afterCanonical,
                                        FrozenNode afterResolved,
                                        Set<String> wholeEmbeddedChildPatches,
                                        ProcessingSnapshotManager evidenceManager,
                                        boolean requireExactEvidence,
                                        Set<String> fixedParticipatingScopes) {
        Set<String> participatingScopes = fixedParticipatingScopes != null
                ? normalizedScopes(fixedParticipatingScopes)
                : participatingScopes(
                        beforeResolved,
                        evidenceManager,
                        requireExactEvidence);
        if (fixedParticipatingScopes == null) {
            participatingScopes.addAll(participatingScopes(
                    afterResolved,
                    evidenceManager,
                    requireExactEvidence));
        }
        Map<String, String> before = snapshot(
                beforeCanonical,
                beforeResolved,
                participatingScopes,
                evidenceManager,
                requireExactEvidence);
        Map<String, String> after = snapshot(
                afterCanonical,
                afterResolved,
                participatingScopes,
                evidenceManager,
                requireExactEvidence);
        verifyEqual(before, after, wholeEmbeddedChildPatches);
    }

    private static Set<String> normalizedScopes(Set<String> scopePaths) {
        Set<String> result = new LinkedHashSet<>();
        result.add(JsonPointer.ROOT);
        if (scopePaths != null) {
            for (String scopePath : scopePaths) {
                if (scopePath != null) {
                    result.add(PointerUtils.normalizeScope(scopePath));
                }
            }
        }
        return result;
    }

    static void verifyEffectiveUnchanged(FrozenNode beforeResolved,
                                         FrozenNode afterResolved) {
        Set<String> participatingScopes = participatingScopes(
                beforeResolved, null, false);
        participatingScopes.addAll(participatingScopes(
                afterResolved, null, false));
        Map<String, String> before = effectiveSnapshot(
                beforeResolved, participatingScopes, null, false);
        Map<String, String> after = effectiveSnapshot(
                afterResolved, participatingScopes, null, false);
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
            Set<String> scopes,
            ProcessingSnapshotManager evidenceManager,
            boolean requireExactEvidence) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String scope : scopes) {
            FrozenNode resolvedScope = resolved != null
                    ? resolved.at(scope)
                    : null;
            collectEffective(
                    resolvedContent(
                            resolvedScope,
                            scope,
                            evidenceManager,
                            requireExactEvidence),
                    scope,
                    result);
        }
        return result;
    }

    private static Map<String, String> snapshot(FrozenNode canonical,
                                                FrozenNode resolved,
                                                Set<String> scopes,
                                                ProcessingSnapshotManager evidenceManager,
                                                boolean requireExactEvidence) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String scope : scopes) {
            FrozenNode canonicalScope = canonical != null
                    ? canonical.at(scope)
                    : null;
            FrozenNode resolvedScope = resolved != null
                    ? resolved.at(scope)
                    : null;
            collectDirect(exactContent(
                            canonicalScope,
                            scope,
                            evidenceManager,
                            requireExactEvidence),
                    scope,
                    result);
            collectEffective(resolvedContent(
                            resolvedScope,
                            scope,
                            evidenceManager,
                            requireExactEvidence),
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
    private static Set<String> participatingScopes(
            FrozenNode resolvedRoot,
            ProcessingSnapshotManager evidenceManager,
            boolean requireExactEvidence) {
        Set<String> result = new LinkedHashSet<>();
        result.add("/");
        if (resolvedRoot == null) {
            return result;
        }
        Deque<String> pending = new ArrayDeque<>();
        pending.add("/");
        while (!pending.isEmpty()) {
            String scope = pending.removeFirst();
            FrozenNode scopeNode = resolvedContent(
                    resolvedRoot.at(scope),
                    scope,
                    evidenceManager,
                    requireExactEvidence);
            EmbeddedScopePlan plan = requireExactEvidence
                    ? ProcessingSnapshotBootstrap.embeddedScopePlan(
                            scopeNode, scope, evidenceManager)
                    : ProcessingSnapshotBootstrap
                            .embeddedScopePlanIfAvailable(
                                    scopeNode, scope);
            if (plan == null) {
                continue;
            }
            for (String child : plan.concreteChildPaths()) {
                if (result.add(child)) {
                    pending.addLast(child);
                }
            }
        }
        return result;
    }

    private static FrozenNode exactContent(
            FrozenNode node,
            String scopePath,
            ProcessingSnapshotManager evidenceManager,
            boolean requireExactEvidence) {
        if (node == null || !node.isReferenceOnly()) {
            return node;
        }
        if (!requireExactEvidence) {
            return node;
        }
        if (evidenceManager == null) {
            throw missingEvidence(node, scopePath);
        }
        FrozenNode exact = evidenceManager
                .materializeVerifiedExactReference(node);
        if (exact == null || exact.isReferenceOnly()) {
            throw new InvalidExecutionEvidenceException(
                    "Verified exact content was not found for protected "
                            + "scope " + scopePath,
                    ProcessorErrorCategory.InvalidProcessingDocument);
        }
        return exact;
    }

    private static FrozenNode resolvedContent(
            FrozenNode node,
            String scopePath,
            ProcessingSnapshotManager evidenceManager,
            boolean requireExactEvidence) {
        FrozenNode exact = exactContent(
                node, scopePath, evidenceManager, requireExactEvidence);
        if (exact == null
                || !requireExactEvidence
                || node == null
                || !node.isReferenceOnly()) {
            return exact;
        }
        return evidenceManager.fromDocumentTransient(exact.toNode())
                .frozenResolvedRoot();
    }

    private static ExecutionEvidenceUnavailableException missingEvidence(
            FrozenNode reference,
            String scopePath) {
        return new ExecutionEvidenceUnavailableException(
                "Verified exact content is required for protected scope "
                        + scopePath,
                Collections.singletonList(
                        reference.getReferenceBlueId()));
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
                    "effective:" + contractPath(
                            path,
                            ProcessorContractConstants.KEY_GENERALIZATION),
                    contracts.property(
                            ProcessorContractConstants.KEY_GENERALIZATION));
        }
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
        String contracts = childPath(scopePath, ProcessorContractConstants.KEY_CONTRACTS);
        return childPath(contracts, key);
    }

    private static String childPath(String parent, String segment) {
        String escaped = JsonPointer.escape(segment);
        return "/".equals(parent)
                ? "/" + escaped
                : parent + "/" + escaped;
    }
}
