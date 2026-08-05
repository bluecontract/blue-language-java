package blue.language.examples;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.provider.NodeProvider;
import blue.language.runtime.BlueLanguage;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Expands verified provider content and collapses it back to one pure reference. */
public final class ExpandCollapseProviderExample {

    private static final String CONTENT_VALUE = "provider content";

    private ExpandCollapseProviderExample() {
    }

    /**
     * Runs exact graph operations against a defensive in-memory provider.
     *
     * @return the preserved identity and detached expanded and collapsed graphs
     */
    public static Result run() {
        // tag::verified-provider[]
        Node exactContent = new Node().value(CONTENT_VALUE);
        String exactBlueId =
                DirectBlueIdCalculator.calculateBlueId(exactContent);
        Map<String, Node> contentByBlueId = new LinkedHashMap<>();
        contentByBlueId.put(exactBlueId, exactContent.clone());
        Map<String, Node> providerState = Collections.unmodifiableMap(
                contentByBlueId);
        NodeProvider provider = requestedBlueId ->
                ExampleSupport.lookup(providerState, requestedBlueId);
        Node reference = ExampleSupport.reference(exactBlueId);

        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(provider)
                .build()) {
            Node expanded = language.graph().expand(reference);
            Node collapsed = language.graph().collapse(expanded);
            String expandedBlueId = language.identity()
                    .directBlueId(expanded);

            ExampleSupport.require(exactBlueId.equals(expandedBlueId),
                    "Expansion must preserve the referenced identity");
            ExampleSupport.require(exactBlueId.equals(collapsed.getBlueId()),
                    "Collapse must restore the same pure reference");
            ExampleSupport.require(CONTENT_VALUE.equals(
                            providerState.get(exactBlueId).getValue()),
                    "Graph operations must not mutate provider-owned content");
            ExampleSupport.require(reference.isReferenceOnly(),
                    "Expansion must not mutate the caller's reference");
            return new Result(exactBlueId, expanded, collapsed);
        }
        // end::verified-provider[]
    }

    /**
     * Runs from a shell and prints the preserved identity.
     *
     * @param args command-line arguments, which this example ignores
     */
    public static void main(String[] args) {
        System.out.println(run().getBlueId());
    }

    /** Immutable result from one expand/collapse round trip. */
    public static final class Result {
        private final String blueId;
        private final Node expanded;
        private final Node collapsed;

        private Result(String blueId, Node expanded, Node collapsed) {
            this.blueId = blueId;
            this.expanded = expanded.clone();
            this.collapsed = collapsed.clone();
        }

        /**
         * Returns the identity preserved by expansion and collapse.
         *
         * @return the referenced BlueId
         */
        public String getBlueId() {
            return blueId;
        }

        /**
         * Returns a detached copy of the expanded graph.
         *
         * @return a mutable copy of the expanded graph
         */
        public Node getExpanded() {
            return expanded.clone();
        }

        /**
         * Returns a detached copy of the collapsed reference.
         *
         * @return a mutable copy of the collapsed reference
         */
        public Node getCollapsed() {
            return collapsed.clone();
        }
    }
}
