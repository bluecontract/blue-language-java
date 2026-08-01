package blue.language.processor;

import blue.language.api.BlueCachePolicy;
import blue.language.runtime.BlueLanguageRuntime;
import blue.language.runtime.LanguageRuntimeAccess;
import blue.language.provider.NodeProvider;
import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.api.NodeProviderOutcome;
import blue.language.provider.NodeProviderResult;
import blue.language.provider.SequentialNodeProvider;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;
import blue.language.utils.BlueIds;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Short-lived Language pipeline for a standalone {@link DocumentProcessor}.
 * External content is available only when it was supplied explicitly with the
 * corresponding contract registration. Built-in Language and Contracts types
 * remain available through the focused Language provider composition.
 */
final class RegisteredContractScopeIdentitySnapshotManager
        implements ProcessingSnapshotManager {

    private final BlueLanguageRuntime languageRuntime;

    RegisteredContractScopeIdentitySnapshotManager(
            ContractProcessorRegistry registry) {
        this(registry, null);
    }

    RegisteredContractScopeIdentitySnapshotManager(
            ContractProcessorRegistry registry,
            LanguageRuntimeAccess inheritedRuntime) {
        NodeProvider registeredTypes = blueId -> {
            Node canonicalTypeNode = registry.canonicalTypeNode(blueId);
            if (canonicalTypeNode != null) {
                return Collections.singletonList(canonicalTypeNode);
            }
            if (registry.processors().containsKey(blueId)) {
                throw new IllegalArgumentException(
                        "Missing provider content for registered contract BlueId " + blueId);
            }
            return null;
        };
        NodeProvider provider = inheritedRuntime == null
                ? registeredTypes
                : new SequentialNodeProvider(
                        registeredTypes,
                        inheritedRuntime.getNodeProvider());
        BlueCachePolicy cachePolicy = inheritedRuntime != null
                ? inheritedRuntime.cachePolicy()
                : BlueCachePolicy.boundedDefaults();
        Map<String, String> aliases = inheritedRuntime != null
                ? inheritedRuntime.preprocessingAliases()
                : Collections.emptyMap();
        this.languageRuntime = BlueLanguageRuntime.create(
                provider, cachePolicy, aliases);
    }

    @Override
    public ResolvedSnapshot fromDocument(Node document) {
        return languageRuntime.snapshots().resolve(document);
    }

    @Override
    public ResolvedSnapshot fromDocumentTransient(Node document) {
        return languageRuntime.snapshots().resolve(document);
    }

    @Override
    public ResolvedSnapshot fromDocumentPreservingPaths(
            Node document,
            Collection<String> preservedPaths) {
        return languageRuntime.snapshots().resolvePreservingPaths(
                document, preservedPaths);
    }

    @Override
    public ResolvedSnapshot fromDocumentTransientPreservingPaths(
            Node document,
            Collection<String> preservedPaths) {
        return languageRuntime.snapshots().resolvePreservingPaths(
                document, preservedPaths);
    }

    @Override
    public FrozenNode materializeVerifiedExactReference(
            FrozenNode reference) {
        if (reference == null) {
            throw new NullPointerException("reference");
        }
        if (!reference.isReferenceOnly()) {
            return reference;
        }
        String blueId = reference.getReferenceBlueId();
        NodeProviderResult result = languageRuntime
                .getNodeProvider()
                .fetchResultByBlueId(blueId);
        if (result.outcome() == NodeProviderOutcome.NOT_FOUND) {
            return null;
        }
        if (result.outcome() == NodeProviderOutcome.UNAVAILABLE) {
            throw new ExecutionEvidenceUnavailableException(
                    result.diagnostic().orElse(
                            "Exact provider content is unavailable for "
                                    + blueId),
                    Collections.singleton(blueId));
        }
        if (result.outcome()
                == NodeProviderOutcome.INVALID_EVIDENCE) {
            throw new InvalidExecutionEvidenceException(
                    result.diagnostic().orElse(
                            "Provider returned invalid exact evidence for "
                                    + blueId));
        }
        List<Node> nodes = result.nodes();
        Node canonical = nodes.size() == 1
                ? withoutRootIdentity(nodes.get(0))
                : new Node().items(withoutRootIdentity(nodes));
        FrozenNode exact = FrozenNode.fromNode(canonical);
        if (!BlueIds.hasCyclicMemberSeparator(blueId)
                && !blueId.equals(exact.blueId())) {
            throw new InvalidExecutionEvidenceException(
                    "Provider content BlueId mismatch for " + blueId);
        }
        return exact;
    }

    @Override
    public ResolvedSnapshot applyPatch(
            ResolvedSnapshot snapshot,
            JsonPatch patch) {
        return languageRuntime.patching().apply(snapshot, patch);
    }

    @Override
    public ResolvedSnapshot cacheSnapshot(ResolvedSnapshot snapshot) {
        return languageRuntime.snapshots().cache(snapshot);
    }

    @Override
    public void releaseTransientState() {
        languageRuntime.close();
    }

    private static Node withoutRootIdentity(Node node) {
        Node canonical = node.clone();
        if (canonical.getBlueId() != null
                && !canonical.isReferenceOnly()) {
            canonical.blueId(null);
        }
        return canonical;
    }

    private static List<Node> withoutRootIdentity(
            List<Node> nodes) {
        List<Node> canonical = new ArrayList<>(nodes.size());
        for (Node node : nodes) {
            canonical.add(withoutRootIdentity(node));
        }
        return canonical;
    }
}
