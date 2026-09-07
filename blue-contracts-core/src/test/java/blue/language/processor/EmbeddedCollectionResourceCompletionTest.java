package blue.language.processor;

import blue.language.api.NodeProviderOutcome;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.provider.NodeProvider;
import blue.language.provider.NodeProviderResult;
import blue.language.runtime.BlueLanguage;
import blue.language.runtime.LanguageProcessing;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class EmbeddedCollectionResourceCompletionTest {

    private static final String COLLECTION_PATH = "/orders";
    private static final String SCOPE_PATH = "/root";

    @Test
    void unavailableCollapsedCollectionReplaysAfterResourceCompletion() {
        // given
        Node collection = new Node().properties(
                "order-b", new Node().properties(
                        "name", new Node().value("B")),
                "order-a", new Node().properties(
                        "name", new Node().value("A")));
        String collectionBlueId = DirectBlueIdCalculator.calculateBlueId(
                collection);
        AtomicBoolean available = new AtomicBoolean(false);
        List<String> requests = new ArrayList<String>();
        NodeProvider provider = completingProvider(
                collectionBlueId, collection, available, requests);
        Node expandedScope = new Node().properties(
                "orders", collection.clone());
        Node collapsedScope = new Node().properties(
                "orders", new Node().blueId(collectionBlueId));
        GasMeter expandedGas = new GasMeter(GasSchedule.contracts10());
        GasMeter completedGas = new GasMeter(GasSchedule.contracts10());

        // when
        EmbeddedScopePlan expanded = new EmbeddedScopePlanner().plan(
                FrozenNode.fromResolvedNode(expandedScope),
                SCOPE_PATH,
                Collections.<String>emptyList(),
                Collections.singletonList(COLLECTION_PATH),
                expandedGas);
        ExecutionEvidenceUnavailableException unavailable =
                captureUnavailable(collapsedScope, provider);
        available.set(true);
        EmbeddedScopePlan completed = planWithProvider(
                collapsedScope, provider, completedGas);

        // then
        assertEquals(Collections.singletonList(collectionBlueId),
                unavailable.requiredExactBlueIds());
        assertEquals("collection resource unavailable",
                unavailable.getMessage());
        assertEquals(Arrays.asList(collectionBlueId, collectionBlueId),
                requests);
        assertEquals(expanded.concreteChildPaths(),
                completed.concreteChildPaths());
        assertEquals(expanded.collectionMemberKeysByDeclaration(),
                completed.collectionMemberKeysByDeclaration());
        assertEquals(expanded.collectionStatesByDeclaration(),
                completed.collectionStatesByDeclaration());
        assertEquals(
                EmbeddedCollectionState.PRESENT_COLLECTION,
                completed.collectionStatesByDeclaration().get(
                        COLLECTION_PATH));
        assertEquals(expanded.concretePathOrigins(),
                completed.concretePathOrigins());
        assertEquals(expandedGas.totalGas(), completedGas.totalGas());
        assertEquals(traceSignatures(expandedGas),
                traceSignatures(completedGas));
    }

    private static ExecutionEvidenceUnavailableException captureUnavailable(
            Node scope,
            NodeProvider provider) {
        try {
            planWithProvider(
                    scope,
                    provider,
                    new GasMeter(GasSchedule.contracts10()));
        } catch (ExecutionEvidenceUnavailableException failure) {
            return failure;
        }
        throw new AssertionError(
                "Unavailable collection resource was treated as absence");
    }

    private static EmbeddedScopePlan planWithProvider(
            Node scope,
            NodeProvider provider,
            GasMeter meter) {
        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(provider)
                .build();
             LanguageProcessing.Scope processingScope =
                     language.processing().openScope()) {
            LanguageProcessingSnapshotManager manager =
                    new LanguageProcessingSnapshotManager(processingScope);
            return new EmbeddedScopePlanner(
                    manager::materializeVerifiedExactReference).plan(
                            FrozenNode.fromResolvedNode(scope),
                            SCOPE_PATH,
                            Collections.<String>emptyList(),
                            Collections.singletonList(COLLECTION_PATH),
                            meter);
        }
    }

    private static NodeProvider completingProvider(
            String collectionBlueId,
            Node collection,
            AtomicBoolean available,
            List<String> requests) {
        return new NodeProvider() {
            @Override
            public List<Node> fetchByBlueId(String blueId) {
                NodeProviderResult result = fetchResultByBlueId(blueId);
                return result.outcome() == NodeProviderOutcome.FOUND
                        ? result.nodes() : null;
            }

            @Override
            public NodeProviderResult fetchResultByBlueId(String blueId) {
                requests.add(blueId);
                if (!collectionBlueId.equals(blueId)) {
                    return NodeProviderResult.notFound();
                }
                return available.get()
                        ? NodeProviderResult.found(
                        Collections.singletonList(collection))
                        : NodeProviderResult.unavailable(
                        "collection resource unavailable");
            }
        };
    }

    private static List<String> traceSignatures(GasMeter meter) {
        List<String> result = new ArrayList<String>();
        for (GasTraceEntry entry : meter.trace()) {
            result.add(entry.sequence()
                    + "|" + entry.namespace()
                    + "|" + entry.counter()
                    + "|" + entry.quantity()
                    + "|" + entry.weight()
                    + "|" + entry.subtotal()
                    + "|" + entry.scopePath()
                    + "|" + entry.contractKey()
                    + "|" + entry.logicalPath()
                    + "|" + entry.reason());
        }
        return result;
    }
}
