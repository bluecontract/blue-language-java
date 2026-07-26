package blue.language.provider;

import blue.language.BlueOperationResult;
import blue.language.BlueViewPath;
import blue.language.model.Node;
import blue.language.utils.BlueIdCalculator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Direct, non-transitive evidence for one node.
 *
 * <p>A complete manifest contains every direct object field or every ordered
 * list-element identity. A prefix/partial manifest is useful for transport
 * optimization but cannot prove an omitted field or final list length.</p>
 */
public final class DirectNodeManifest {

    private final Node directNode;
    private final boolean complete;

    private DirectNodeManifest(Node directNode, boolean complete) {
        this.directNode = Objects.requireNonNull(directNode, "directNode").clone();
        this.complete = complete;
    }

    public static DirectNodeManifest complete(Node directNode) {
        return new DirectNodeManifest(directNode, true);
    }

    public static DirectNodeManifest partial(Node knownDirectContent) {
        return new DirectNodeManifest(knownDirectContent, false);
    }

    public Node directNode() {
        return directNode.clone();
    }

    public boolean isComplete() {
        return complete;
    }

    public BlueOperationResult<Node> verify(String requestedBlueId) {
        if (!complete) {
            return BlueOperationResult.incomplete(directNode(), Collections.<String>emptySet(),
                    null, "A partial direct manifest cannot verify a complete node.");
        }
        String calculated;
        try {
            calculated = BlueIdCalculator.calculateBlueId(directNode);
        } catch (RuntimeException invalid) {
            return BlueOperationResult.invalid(invalid.getMessage(),
                    NodeProviderOutcome.INVALID_EVIDENCE);
        }
        if (!calculated.equals(requestedBlueId)) {
            return BlueOperationResult.invalid(
                    "Direct manifest calculated BlueId " + calculated
                            + " instead of requested BlueId " + requestedBlueId + ".",
                    NodeProviderOutcome.INVALID_EVIDENCE);
        }
        return BlueOperationResult.established(directNode());
    }

    public BlueOperationResult<Node> semanticSelect(String path) {
        List<String> segments;
        try {
            segments = BlueViewPath.split(path);
        } catch (IllegalArgumentException invalidPath) {
            return BlueOperationResult.invalid(
                    invalidPath.getMessage(), NodeProviderOutcome.INVALID_EVIDENCE);
        }
        try {
            Node selected = directNode;
            StringBuilder prefix = new StringBuilder();
            for (String segment : segments) {
                if (selected != null && selected.isReferenceOnly()) {
                    if ("blueId".equals(segment)) {
                        return BlueOperationResult.absent(
                                "pure reference wrapper is not a semantic "
                                        + "child of the referenced node");
                    }
                    return BlueOperationResult.incomplete(
                            selected.clone(),
                            Collections.singleton(selected.getBlueId()),
                            null,
                            "Semantic selection requires materializing "
                                    + "reference " + selected.getBlueId()
                                    + " before traversing " + path + ".");
                }
                prefix.append('/').append(
                        escapePointerSegment(segment));
                selected = BlueViewPath.select(
                        directNode, prefix.toString());
                if (selected == null) {
                    break;
                }
            }
            if (selected != null) {
                return BlueOperationResult.established(selected.clone());
            }
        } catch (IllegalArgumentException invalidTraversal) {
            return BlueOperationResult.invalid(
                    invalidTraversal.getMessage(), NodeProviderOutcome.INVALID_EVIDENCE);
        }
        if (!complete) {
            return BlueOperationResult.incomplete(
                    directNode(), Collections.<String>emptySet(), null,
                    "A partial direct manifest cannot establish absence at " + path + ".");
        }
        String reason = targetsReferenceWrapperBlueId(segments)
                ? "pure reference wrapper is not a semantic child of the referenced node"
                : "The complete direct manifest establishes semantic absence at " + path + ".";
        return BlueOperationResult.absent(reason);
    }

    private boolean targetsReferenceWrapperBlueId(List<String> segments) {
        if (segments.isEmpty()
                || !"blueId".equals(segments.get(segments.size() - 1))) {
            return false;
        }
        Node parent = directNode;
        if (segments.size() > 1) {
            StringBuilder pointer = new StringBuilder();
            for (int index = 0; index < segments.size() - 1; index++) {
                pointer.append('/').append(escapePointerSegment(segments.get(index)));
            }
            parent = BlueViewPath.select(directNode, pointer.toString());
        }
        return parent != null && parent.isReferenceOnly();
    }

    private static String escapePointerSegment(String segment) {
        return segment.replace("~", "~0").replace("/", "~1");
    }

    public BlueOperationResult<List<String>> orderedListElementIdentities() {
        if (!complete) {
            return BlueOperationResult.incomplete(null, Collections.<String>emptySet(),
                    null, "A list prefix cannot establish the complete ordered element manifest.");
        }
        if (directNode.getItems() == null) {
            return BlueOperationResult.invalid(
                    "Direct node is not a list.", NodeProviderOutcome.INVALID_EVIDENCE);
        }
        List<String> identities = new ArrayList<>(directNode.getItems().size());
        for (Node item : directNode.getItems()) {
            identities.add(BlueIdCalculator.calculateBlueId(item));
        }
        return BlueOperationResult.established(Collections.unmodifiableList(identities));
    }
}
