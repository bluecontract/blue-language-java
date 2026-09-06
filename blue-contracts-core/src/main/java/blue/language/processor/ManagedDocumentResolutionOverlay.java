package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.util.PointerUtils;

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
                    Collections.<String, String>emptyMap());

    private final Map<String, Node> exactNodesByBlueId;
    private final Map<String, String> expectedManagedBlueIdsByPath;
    private final java.util.Set<String> admittedManagedBlueIds;

    /**
     * Creates an immutable invocation-local overlay.
     *
     * @param exactNodesByBlueId verified exact current content keyed by its
     *        admitted BlueId
     * @param expectedManagedBlueIdsByPath canonical absolute concrete paths
     *        selected by this Root's effective {@code Process Embedded}
     *        declaration, mapped to admitted exact target BlueIds
     */
    public ManagedDocumentResolutionOverlay(
            Map<String, Node> exactNodesByBlueId,
            Map<String, String> expectedManagedBlueIdsByPath) {
        this(exactNodesByBlueId, expectedManagedBlueIdsByPath, exactNodesByBlueId.keySet());
    }

    /** Complete managed identity inventory, with only resident bodies supplied above. */
    public ManagedDocumentResolutionOverlay(
            Map<String, Node> exactNodesByBlueId,
            Map<String, String> expectedManagedBlueIdsByPath,
            java.util.Collection<String> admittedManagedBlueIds) {
        this.admittedManagedBlueIds = Collections.unmodifiableSet(new java.util.LinkedHashSet<String>(
                Objects.requireNonNull(admittedManagedBlueIds, "admittedManagedBlueIds")));
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

    java.util.Set<String> admittedManagedBlueIds() { return admittedManagedBlueIds; }

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
}
