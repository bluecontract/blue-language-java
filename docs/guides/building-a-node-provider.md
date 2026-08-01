# Building a NodeProvider

A provider retrieves candidate content; the Language verification boundary
decides whether that content proves the requested BlueId. Preserve all four
transport outcomes:

- `FOUND`: candidate content is available;
- `NOT_FOUND`: the provider definitively has no content;
- `UNAVAILABLE`: the answer cannot currently be established;
- `INVALID_EVIDENCE`: returned content or proof failed verification.

`NodeProvider` keeps its legacy list method as the single abstract method, so a
lambda remains valid. Override `fetchResultByBlueId` when the implementation
can distinguish a definitive miss from temporary unavailability:

```java
import blue.language.NodeProvider;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.provider.NodeProviderOutcome;
import blue.language.provider.NodeProviderResult;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TypedNodeProviderExample {
    public static void main(String[] args) {
        Node stored = new Node().value("hello");
        String storedBlueId = new DirectBlueIdCalculator()
                .directBlueId(stored);
        Map<String, List<Node>> storage = new HashMap<>();
        storage.put(storedBlueId, Collections.singletonList(stored));
        AtomicBoolean available = new AtomicBoolean(true);

        NodeProvider provider = new NodeProvider() {
            @Override
            public List<Node> fetchByBlueId(String blueId) {
                NodeProviderResult result = fetchResultByBlueId(blueId);
                return result.outcome() == NodeProviderOutcome.FOUND
                        ? result.nodes()
                        : null;
            }

            @Override
            public NodeProviderResult fetchResultByBlueId(String blueId) {
                if (!available.get()) {
                    return NodeProviderResult.unavailable(
                            "storage offline");
                }
                List<Node> candidates = storage.get(blueId);
                return candidates == null || candidates.isEmpty()
                        ? NodeProviderResult.notFound()
                        : NodeProviderResult.found(candidates);
            }
        };

        NodeProviderResult found = provider.fetchResultByBlueId(storedBlueId);
        stored.value("caller mutation");
        if (found.outcome() != NodeProviderOutcome.FOUND
                || !"hello".equals(found.nodes().get(0).getValue())) {
            throw new AssertionError("Provider value was not defensive");
        }
    }
}
```

`NodeProviderResult` copies nodes on construction and access. Custom providers
should likewise avoid sharing mutable storage objects. A
`CachingNodeProvider` may retain `FOUND` and definitive `NOT_FOUND` results;
it retries `UNAVAILABLE` and invalid evidence rather than rewriting a temporary
failure as semantic absence.

Plain content is verified directly against the requested BlueId. Source-content
provider mode must be explicitly bound to a preprocessing environment. Cyclic
members are verified through their set proof and must not be independently
hashed. Exact fragments remain ordinary Blue nodes and are assembled before
identity verification. Keep transport, verification, cyclic proof, fragment
assembly, and caching as separate responsibilities.
