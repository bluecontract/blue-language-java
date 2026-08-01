package blue.language.preprocess;

import blue.language.provider.NodeProvider;
import blue.language.model.Node;
import blue.language.provider.NodeProviderOutcome;
import blue.language.provider.NodeProviderResult;
import blue.language.provider.ProviderUnavailableException;
import blue.language.utils.BlueIds;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Resolves aliases and exact references used by preprocessing directives. */
public final class DirectiveResolver {

    private final NodeProvider verifiedProvider;
    private final Map<String, String> directiveAliases;

    /** Creates a resolver at an identity-verifying provider boundary. */
    public DirectiveResolver(
            NodeProvider verifiedProvider,
            Map<String, String> directiveAliases) {
        this.verifiedProvider = Objects.requireNonNull(
                verifiedProvider, "verifiedProvider");
        this.directiveAliases = exactMappings(
                directiveAliases, "directive alias");
    }

    /** Resolves an absent, inline, aliased, or pure-reference root directive. */
    ResolvedDirective resolveRootDirective(Node source) {
        List<String> dependencies = new ArrayList<>();
        Node directive = source.getBlue();
        String directiveBlueId = null;
        if (directive == null) {
            directive = new Node();
        } else if (directive.getValue() instanceof String) {
            String alias = (String) directive.getValue();
            directiveBlueId = directiveAliases.get(alias);
            if (directiveBlueId == null) {
                throw new IllegalArgumentException(
                        "Reserved \"blue\" directive alias is unbound: "
                                + alias);
            }
            addDependency(dependencies, directiveBlueId);
            directive = fetchExactNode(
                    directiveBlueId, "blue directive");
        } else if (directive.isReferenceOnly()) {
            directiveBlueId = BlueIds.requirePlainBlueId(
                    directive.getBlueId(), "blue.blueId");
            addDependency(dependencies, directiveBlueId);
            directive = fetchExactNode(
                    directiveBlueId, "blue directive");
        } else {
            directive = directive.clone();
        }
        return new ResolvedDirective(
                directiveBlueId, directive, dependencies);
    }

    /** Fetches exactly one verified node and strips its redundant self-key. */
    Node fetchExactNode(String blueId, String role) {
        NodeProviderResult result =
                verifiedProvider.fetchResultByBlueId(blueId);
        if (result.outcome() == NodeProviderOutcome.UNAVAILABLE) {
            throw new ProviderUnavailableException(
                    result.diagnostic().orElse(
                            "Provider unavailable for requested BlueId "
                                    + blueId));
        }
        if (result.outcome() == NodeProviderOutcome.INVALID_EVIDENCE) {
            throw new IllegalArgumentException(
                    result.diagnostic().orElse(
                            "Provider returned content that does not match requested BlueId "
                                    + blueId));
        }
        if (result.outcome() != NodeProviderOutcome.FOUND) {
            throw new IllegalArgumentException(
                    "Provider returned no content for requested BlueId "
                            + blueId + " (" + role + ").");
        }
        List<Node> nodes = result.nodes();
        if (nodes.size() != 1) {
            throw new IllegalArgumentException(
                    "Provider returned " + nodes.size()
                            + " nodes for requested BlueId " + blueId
                            + " (" + role + ").");
        }
        Node node = nodes.get(0).clone();
        if (blueId.equals(node.getBlueId())) {
            node.blueId(null);
        }
        PreprocessingLimits.requireGraphWithinBounds(node, role);
        return node;
    }

    /** Records one exact dependency while enforcing the portable bound. */
    void addDependency(List<String> dependencies, String blueId) {
        dependencies.add(blueId);
        PreprocessingLimits.requireReferencedResourceCount(
                new LinkedHashSet<>(dependencies).size());
    }

    /** Validates and freezes an alias-to-BlueId mapping. */
    static Map<String, String> exactMappings(
            Map<String, String> mappings, String role) {
        Map<String, String> result = new LinkedHashMap<>();
        if (mappings == null) {
            return Collections.unmodifiableMap(result);
        }
        for (Map.Entry<String, String> entry : mappings.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isEmpty()) {
                throw new IllegalArgumentException(
                        role + " name must not be empty.");
            }
            result.put(entry.getKey(), BlueIds.requirePlainBlueId(
                    entry.getValue(), role + "." + entry.getKey()));
        }
        return Collections.unmodifiableMap(result);
    }

    /** Immutable resolved directive and its exact provider dependencies. */
    static final class ResolvedDirective {
        private final String blueId;
        private final Node directive;
        private final List<String> dependencies;

        private ResolvedDirective(
                String blueId, Node directive, List<String> dependencies) {
            this.blueId = blueId;
            this.directive = directive;
            this.dependencies = dependencies;
        }

        String blueId() {
            return blueId;
        }

        Node directive() {
            return directive;
        }

        List<String> dependencies() {
            return dependencies;
        }
    }
}
