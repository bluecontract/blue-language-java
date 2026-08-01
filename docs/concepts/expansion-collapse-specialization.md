# Expansion, collapse, and specialization

Expansion and collapse reveal or hide verified content of the same exact node.
Specialization creates a new authored node by assigning a type to a compatible
overlay.

```java
import blue.language.NodeProvider;
import blue.language.api.BlueLanguage;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;

import java.util.Collections;

import static blue.language.utils.Properties.TEXT_TYPE_BLUE_ID;

public final class GraphOperationsExample {
    public static void main(String[] args) {
        Node content = new Node().value("hello");
        String contentBlueId = new DirectBlueIdCalculator()
                .directBlueId(content);
        NodeProvider provider = requestedBlueId ->
                requestedBlueId.equals(contentBlueId)
                        ? Collections.singletonList(content)
                        : Collections.emptyList();

        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(provider)
                .build()) {
            Node expanded = language.graph()
                    .expand(new Node().blueId(contentBlueId));
            Node collapsed = language.graph().collapse(expanded);
            Node specialization = language.graph().specialize(
                    new Node().blueId(TEXT_TYPE_BLUE_ID),
                    new Node().value("hello"));

            if (!contentBlueId.equals(
                    language.identity().directBlueId(expanded))
                    || !contentBlueId.equals(collapsed.getBlueId())
                    || !TEXT_TYPE_BLUE_ID.equals(
                    specialization.getType().getBlueId())) {
                throw new AssertionError("Unexpected graph operation");
            }
        }
    }
}
```

Strict `expand` requires complete verified provider evidence. Use
`expandLimited` with `BlueOperationLimits` when the caller must retain
`ESTABLISHED`, `ABSENT`, `INCOMPLETE`, and `INVALID` as explicit outcomes.
Neither form mutates the input.

Expansion is not inheritance and does not create a new identity.
Specialization is not an alias for expansion and normally establishes a new
identity. The removed `extend` and `NodeExtender` compatibility names are not
part of the focused API.
