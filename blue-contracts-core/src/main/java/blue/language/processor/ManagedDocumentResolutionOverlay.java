package blue.language.processor;

import blue.language.identity.BlueIds;
import blue.language.model.Node;
import blue.language.processor.util.PointerUtils;
import blue.language.provider.CyclicAwareNodeProvider;
import blue.language.provider.CyclicSetProof;
import blue.language.provider.CyclicSetProofResult;
import blue.language.provider.NodeProvider;
import blue.language.runtime.LanguageProcessing.ExactResolutionOverlay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Processor-private resolution evidence for one isolated managed-document
 * step.
 *
 * <p>The overlay contains only exact content reachable through the selected
 * document's forward references and the concrete {@code Process Embedded}
 * paths owned by that document. It deliberately contains no reverse
 * occurrence or containing-document identity.</p>
 */
public final class ManagedDocumentResolutionOverlay {

    private static final ManagedDocumentResolutionOverlay EMPTY =
            new ManagedDocumentResolutionOverlay(
                    Collections.<String, Node>emptyMap(),
                    Collections.<String, String>emptyMap(),
                    Collections.<String, CyclicSetProof>emptyMap());

    private final Map<String, Node> exactNodesByBlueId;
    private final Map<String, String> expectedManagedBlueIdsByPath;
    private final Map<String, CyclicSetProof> cyclicProofsByMasterBlueId;
    private final ExactResolutionOverlay exactResolutionOverlay;

    /**
     * Creates an immutable invocation-local overlay.
     *
     * @param exactNodesByBlueId verified exact current content keyed by its
     *        admitted BlueId
     * @param expectedManagedBlueIdsByPath canonical absolute concrete paths
     *        selected by this Root's effective {@code Process Embedded}
     *        declaration, mapped to admitted exact target BlueIds
     * @param cyclicProofsByMasterBlueId complete cyclic-set proofs keyed by
     *        master BlueId for every cyclic member in this overlay
     */
    public ManagedDocumentResolutionOverlay(
            Map<String, Node> exactNodesByBlueId,
            Map<String, String> expectedManagedBlueIdsByPath,
            Map<String, CyclicSetProof> cyclicProofsByMasterBlueId) {
        LinkedHashMap<String, Node> nodes =
                new LinkedHashMap<String, Node>();
        for (Map.Entry<String, Node> entry
                : Objects.requireNonNull(
                        exactNodesByBlueId,
                        "exactNodesByBlueId").entrySet()) {
            String blueId = Objects.requireNonNull(
                    entry.getKey(), "overlay BlueId");
            Node exact = Objects.requireNonNull(
                    entry.getValue(), "overlay exact node").clone();
            Node previous = nodes.put(blueId, exact);
            if (previous != null
                    && !blue.language.model.NodeWireForm.get(previous)
                            .equals(blue.language.model.NodeWireForm.get(exact))) {
                throw new IllegalArgumentException(
                        "One overlay BlueId identifies different exact nodes");
            }
        }
        this.exactNodesByBlueId = Collections.unmodifiableMap(nodes);

        LinkedHashMap<String, CyclicSetProof> proofs =
                new LinkedHashMap<String, CyclicSetProof>();
        for (Map.Entry<String, CyclicSetProof> entry
                : Objects.requireNonNull(
                        cyclicProofsByMasterBlueId,
                        "cyclicProofsByMasterBlueId").entrySet()) {
            String masterBlueId = BlueIds.requirePlainBlueId(
                    entry.getKey(), "overlay cyclic proof master BlueId");
            proofs.put(
                    masterBlueId,
                    copyProof(Objects.requireNonNull(
                            entry.getValue(), "overlay cyclic proof")));
        }
        for (String blueId : nodes.keySet()) {
            BlueIds.requireBlueIdOrCyclicMember(
                    blueId, "overlay exact-node BlueId");
            if (BlueIds.hasCyclicMemberSeparator(blueId)
                    && !proofs.containsKey(
                            BlueIds.cyclicSetMasterBlueId(blueId))) {
                throw new IllegalArgumentException(
                        "Cyclic overlay member requires its complete cyclic-set proof: "
                                + blueId);
            }
        }
        for (String masterBlueId : proofs.keySet()) {
            boolean used = false;
            for (String blueId : nodes.keySet()) {
                if (BlueIds.hasCyclicMemberSeparator(blueId)
                        && masterBlueId.equals(
                                BlueIds.cyclicSetMasterBlueId(blueId))) {
                    used = true;
                    break;
                }
            }
            if (!used) {
                throw new IllegalArgumentException(
                        "Cyclic overlay proof has no exact member content: "
                                + masterBlueId);
            }
        }
        this.cyclicProofsByMasterBlueId =
                Collections.unmodifiableMap(proofs);
        this.exactResolutionOverlay = ExactResolutionOverlay.from(
                new ExactNodeProvider(
                        this.exactNodesByBlueId,
                        this.cyclicProofsByMasterBlueId));

        LinkedHashMap<String, String> paths =
                new LinkedHashMap<String, String>();
        String previous = null;
        for (Map.Entry<String, String> entry : Objects.requireNonNull(
                expectedManagedBlueIdsByPath,
                "expectedManagedBlueIdsByPath").entrySet()) {
            String path = Objects.requireNonNull(
                    entry.getKey(), "opaque managed path");
            String normalized = PointerUtils.assertValidRuntimePointer(
                    path);
            if (!normalized.equals(path) || "/".equals(normalized)) {
                throw new IllegalArgumentException(
                        "Opaque managed paths must be normalized non-Root pointers");
            }
            if (previous != null
                    && ExternalOrderKey.compareTextCodePoints(
                            previous, normalized) >= 0) {
                throw new IllegalArgumentException(
                        "Opaque managed paths must be unique and canonical");
            }
            paths.put(normalized, Objects.requireNonNull(
                    entry.getValue(), "expected managed BlueId"));
            previous = normalized;
        }
        this.expectedManagedBlueIdsByPath =
                Collections.unmodifiableMap(paths);
    }

    /**
     * Returns an empty overlay for ordinary compatibility callers.
     *
     * @return shared immutable empty overlay
     */
    public static ManagedDocumentResolutionOverlay empty() {
        return EMPTY;
    }

    /**
     * Returns defensive exact provider content in insertion order.
     *
     * @return immutable map with defensive node values
     */
    public Map<String, Node> exactNodesByBlueId() {
        LinkedHashMap<String, Node> result =
                new LinkedHashMap<String, Node>();
        for (Map.Entry<String, Node> entry : exactNodesByBlueId.entrySet()) {
            result.put(entry.getKey(), entry.getValue().clone());
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Returns the selected Root's declaration-covered managed paths.
     *
     * @return immutable canonical absolute paths
     */
    public List<String> opaqueManagedPaths() {
        return Collections.unmodifiableList(new ArrayList<String>(
                expectedManagedBlueIdsByPath.keySet()));
    }

    /**
     * Returns exact expected target BlueIds in canonical path order.
     *
     * @return immutable path-to-BlueId map
     */
    public Map<String, String> expectedManagedBlueIdsByPath() {
        return expectedManagedBlueIdsByPath;
    }

    /** Returns the proof-preserving opaque Language overlay. */
    ExactResolutionOverlay exactResolutionOverlay() {
        return exactResolutionOverlay;
    }

    private static CyclicSetProof copyProof(CyclicSetProof proof) {
        return CyclicSetProof.fromDeclaredPlaceholderSet(
                proof.declaredPlaceholderSet());
    }

    /** Immutable proof-bearing leaf; Language still verifies all evidence. */
    private static final class ExactNodeProvider
            implements NodeProvider, CyclicAwareNodeProvider {
        private final Map<String, Node> exactNodesByBlueId;
        private final Map<String, CyclicSetProof> proofsByMasterBlueId;

        private ExactNodeProvider(
                Map<String, Node> exactNodesByBlueId,
                Map<String, CyclicSetProof> proofsByMasterBlueId) {
            this.exactNodesByBlueId = exactNodesByBlueId;
            this.proofsByMasterBlueId = proofsByMasterBlueId;
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            Node exact = exactNodesByBlueId.get(blueId);
            return exact == null
                    ? Collections.<Node>emptyList()
                    : Collections.singletonList(exact.clone());
        }

        @Override
        public CyclicSetProofResult cyclicSetProofFor(String blueId) {
            CyclicSetProof proof = proofsByMasterBlueId.get(
                    BlueIds.cyclicSetMasterBlueId(blueId));
            return proof == null
                    ? CyclicSetProofResult.notFound()
                    : CyclicSetProofResult.found(copyProof(proof));
        }
    }
}
