package blue.coordination.closure;

import blue.contracts.closure.ComponentKind;
import blue.contracts.closure.ClosureProcessResult;
import blue.contracts.closure.DocumentId;
import blue.contracts.closure.ManagedOccurrenceBinding;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Dependency-free smoke proof for required-closure selection and one policy. */
public final class ReferenceCoordinationIntegrationMain {
    public static void main(String[] args) {
        DocumentId a = new DocumentId("a");
        DocumentId b = new DocumentId("b");
        DocumentId outer = new DocumentId("outer");
        DocumentId unrelated = new DocumentId("unrelated");

        ComponentId cycle = new ComponentId("cycle");
        ComponentId outerComponent = new ComponentId("outer");
        ComponentId unrelatedComponent = new ComponentId("unrelated");
        Map<ComponentId, ProcessingComponent> components = new LinkedHashMap<ComponentId, ProcessingComponent>();
        components.put(cycle, new ProcessingComponent(cycle, 1, ComponentKind.CYCLIC, Arrays.asList(a, b), "M"));
        components.put(outerComponent, new ProcessingComponent(outerComponent, 1, ComponentKind.ACYCLIC, Collections.singletonList(outer), null));
        components.put(unrelatedComponent, new ProcessingComponent(unrelatedComponent, 1, ComponentKind.ACYCLIC, Collections.singletonList(unrelated), null));

        Map<DocumentId, ComponentId> byDocument = new LinkedHashMap<DocumentId, ComponentId>();
        byDocument.put(a, cycle);
        byDocument.put(b, cycle);
        byDocument.put(outer, outerComponent);
        byDocument.put(unrelated, unrelatedComponent);
        ComponentIndex index = new ComponentIndex(
                byDocument,
                components,
                Arrays.asList(cycle, outerComponent, unrelatedComponent),
                "index");
        boolean duplicateOrderRejected = false;
        try {
            new ComponentIndex(
                    byDocument,
                    components,
                    Arrays.asList(cycle, cycle, unrelatedComponent),
                    "invalid-index");
        } catch (IllegalArgumentException expected) {
            duplicateOrderRejected = true;
        }
        require(duplicateOrderRejected, "component order must be a permutation");

        Map<DocumentId, ManagedDocumentVertex> vertices = new LinkedHashMap<DocumentId, ManagedDocumentVertex>();
        for (DocumentId id : Arrays.asList(a, b, outer, unrelated)) {
            vertices.put(id, new ManagedDocumentVertex(id, 0, id + "-blue", new Object()));
        }
        List<ManagedOccurrenceBinding> occurrences = Arrays.asList(
                new ManagedOccurrenceBinding(
                        "a-b", "a-b-state", "policy", a, "/b", 1,
                        b, "b-blue", true, null),
                new ManagedOccurrenceBinding(
                        "b-a", "b-a-state", "policy", b, "/a", 1,
                        a, "a-blue", true, null),
                new ManagedOccurrenceBinding(
                        "outer-a", "outer-a-state", "policy", outer,
                        "/inner", 1, a, "a-blue", true, null),
                new ManagedOccurrenceBinding(
                        "prospective-unrelated-a", "prospective-unrelated-a-state",
                        "policy", unrelated, "/old", 1,
                        a, "a-blue", false, null));
        ManagedGraphSnapshot graph = new ManagedGraphSnapshot(1, vertices, occurrences, index, "graph");

        List<DocumentId> closure = new RequiredClosureResolver().resolve(
                graph, new LinkedHashSet<DocumentId>(Collections.singletonList(a)));
        require(closure.equals(Arrays.asList(a, b, outer)), "closure must exclude unrelated");

        ClosureProcessResult.Attempt suspended =
                new ClosureProcessResult.NeedsResources(
                        Collections.singletonList("required-blue-id"));
        require(suspended instanceof ClosureProcessResult.NeedsResources,
                "NeedsResources remains a separate attempt branch");

        new CoordinationExecutionPolicy(
                "release-default",
                100_000L,
                Collections.<DocumentId, Long>emptyMap(),
                "Contracts 1.0 release default");
        System.out.println("BLUE_COORDINATION_CLOSURE_REFERENCE_OK");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
