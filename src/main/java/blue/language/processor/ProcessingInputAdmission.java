package blue.language.processor;

import blue.language.BlueLanguageErrorCategory;
import blue.language.BlueLanguageErrorClassifier;
import blue.language.model.Node;
import blue.language.processor.util.PointerUtils;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.BlueIds;
import blue.language.utils.JsonPointer;
import blue.language.utils.NodePathEditor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Invocation-local admission of exact pure-reference PROCESS inputs.
 *
 * <p>This helper opens only the exact references required to establish the
 * top-level semantic inputs and the ancestor closure of feeder-selected scope
 * paths. It never invokes ordinary snapshot resolution.</p>
 */
final class ProcessingInputAdmission {

    private final ProcessingSnapshotManager snapshotManager;

    ProcessingInputAdmission(ProcessingSnapshotManager snapshotManager) {
        this.snapshotManager = snapshotManager;
    }

    AdmittedNode materializeTopLevel(Node input, String label) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(label, "label");
        requireProcessableTopLevel(input, label);
        if (snapshotManager == null || !input.isReferenceOnly()) {
            return AdmittedNode.unchanged(input);
        }
        return AdmittedNode.materialized(
                exactContent(input, label));
    }

    void requireProcessableTopLevel(Node input, String label) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(label, "label");
        if (isFinalCyclicMemberReference(input)) {
            throw invalid(
                    label + " cannot be an independently processed "
                            + "cyclic-set member; process the owning ordinary "
                            + "Root or Event instead",
                    null);
        }
    }

    AdmittedNode materializeScopePaths(
            AdmittedNode admittedRoot,
            Collection<String> scopePaths) {
        Objects.requireNonNull(admittedRoot, "admittedRoot");
        if (snapshotManager == null
                || scopePaths == null
                || scopePaths.isEmpty()) {
            return admittedRoot;
        }

        List<String> orderedPaths = orderedScopePaths(scopePaths);
        Node working = admittedRoot.node();
        boolean copied = false;
        boolean materialized = admittedRoot.wasMaterialized();
        String expectedRootBlueId =
                BlueIdCalculator.calculateBlueId(working);

        for (String scopePath : orderedPaths) {
            List<String> segments = JsonPointer.split(scopePath);
            for (int depth = 0; depth <= segments.size(); depth++) {
                String prefix = JsonPointer.toPointer(
                        segments.subList(0, depth));
                Node selected = NodePathEditor.getOrNull(
                        working, prefix);
                if (selected == null) {
                    break;
                }
                if (!selected.isReferenceOnly()) {
                    continue;
                }
                if (isFinalCyclicMemberReference(selected)) {
                    throw invalid(
                            "Process Embedded traversal cannot cross opaque "
                                    + "cyclic-set member boundary at "
                                    + prefix,
                            null);
                }
                if (!copied) {
                    working = working.clone();
                    copied = true;
                    selected = NodePathEditor.getOrNull(
                            working, prefix);
                }
                Node exact = exactContent(
                        selected,
                        "Processing Root scope " + prefix);
                NodePathEditor.put(working, prefix, exact);
                materialized = true;
            }
        }

        if (!copied) {
            return admittedRoot;
        }
        requirePreservedIdentity(
                expectedRootBlueId,
                working,
                "Processing Root");
        return new AdmittedNode(working, materialized);
    }

    private boolean isFinalCyclicMemberReference(Node node) {
        if (node == null || !node.isReferenceOnly()) {
            return false;
        }
        String blueId = node.getBlueId();
        if (blueId == null || blueId.indexOf('#') < 0) {
            return false;
        }
        BlueIds.requireNoThisPlaceholderOutsideCyclicApi(
                blueId, "processing input");
        BlueIds.requireBlueIdOrCyclicMember(
                blueId, "processing input");
        return true;
    }

    ResolvedSnapshot deferredSnapshot(AdmittedNode admittedRoot) {
        Objects.requireNonNull(admittedRoot, "admittedRoot");
        if (!admittedRoot.wasMaterialized()) {
            throw new IllegalArgumentException(
                    "A deferred admission snapshot requires a materialized Root fragment");
        }
        Node root = admittedRoot.node();
        return ResolvedSnapshot.withDeferredResolution(
                FrozenNode.fromNode(root),
                FrozenNode.fromResolvedNode(root));
    }

    private Node exactContent(Node reference, String label) {
        String expectedBlueId = reference.getBlueId();
        FrozenNode materialized;
        try {
            materialized = snapshotManager
                    .materializeVerifiedExactReference(
                            FrozenNode.fromNode(reference));
        } catch (ExecutionEvidenceUnavailableException exception) {
            throw exception;
        } catch (InvalidExecutionEvidenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            if (isUnavailable(exception)) {
                throw unavailable(
                        label, expectedBlueId, exception);
            }
            throw invalid(
                    label + " provider evidence is invalid for "
                            + expectedBlueId,
                    exception);
        }
        if (materialized == null) {
            throw invalid(
                    label + " provider returned no content for "
                            + expectedBlueId,
                    null);
        }
        if (materialized.isReferenceOnly()) {
            throw invalid(
                    label + " provider retained a pure reference for "
                            + expectedBlueId,
                    null);
        }

        Node exact = materialized.toNode();
        requirePreservedIdentity(
                expectedBlueId, exact, label);
        return exact;
    }

    private void requirePreservedIdentity(
            String expectedBlueId,
            Node exact,
            String label) {
        final String actualBlueId;
        try {
            actualBlueId = BlueIdCalculator.calculateBlueId(exact);
        } catch (RuntimeException exception) {
            throw invalid(
                    label + " provider content is not exact canonical content for "
                            + expectedBlueId,
                    exception);
        }
        if (!Objects.equals(expectedBlueId, actualBlueId)) {
            throw invalid(
                    label + " provider content BlueId "
                            + actualBlueId
                            + " does not match requested BlueId "
                            + expectedBlueId,
                    null);
        }
    }

    private boolean isUnavailable(RuntimeException exception) {
        return BlueLanguageErrorClassifier.classify(exception)
                == BlueLanguageErrorCategory.ProviderUnavailable;
    }

    private ExecutionEvidenceUnavailableException unavailable(
            String label,
            String blueId,
            RuntimeException cause) {
        String message = label
                + " exact input is unavailable for "
                + blueId;
        if (cause != null
                && cause.getMessage() != null
                && !cause.getMessage().isEmpty()) {
            message += ": " + cause.getMessage();
        }
        return new ExecutionEvidenceUnavailableException(
                message,
                Collections.singleton(blueId));
    }

    private InvalidExecutionEvidenceException invalid(
            String message,
            RuntimeException cause) {
        String deterministic = cause != null
                && cause.getMessage() != null
                && !cause.getMessage().isEmpty()
                ? message + ": " + cause.getMessage()
                : message;
        return new InvalidExecutionEvidenceException(
                deterministic);
    }

    private List<String> orderedScopePaths(
            Collection<String> scopePaths) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String scopePath : scopePaths) {
            normalized.add(PointerUtils.normalizeScope(
                    Objects.requireNonNull(
                            scopePath, "scopePath")));
        }
        List<String> ordered = new ArrayList<>(normalized);
        ordered.sort(new Comparator<String>() {
            @Override
            public int compare(String left, String right) {
                int depth = Integer.compare(
                        JsonPointer.split(left).size(),
                        JsonPointer.split(right).size());
                return depth != 0
                        ? depth
                        : ExternalOrderKey.compareTextCodePoints(
                        left, right);
            }
        });
        return ordered;
    }

    static final class AdmittedNode {
        private final Node node;
        private final boolean materialized;

        private AdmittedNode(Node node, boolean materialized) {
            this.node = Objects.requireNonNull(node, "node");
            this.materialized = materialized;
        }

        static AdmittedNode unchanged(Node node) {
            return new AdmittedNode(node, false);
        }

        static AdmittedNode materialized(Node node) {
            return new AdmittedNode(node, true);
        }

        Node node() {
            return node;
        }

        boolean wasMaterialized() {
            return materialized;
        }
    }
}
