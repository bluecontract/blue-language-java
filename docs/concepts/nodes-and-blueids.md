# Nodes and BlueIds

A `Node` is the mutable Java representation used for parsing, authoring, and
serialization. A BlueId is the one content identifier defined by Blue Language
1.0: a Base58-encoded SHA-256 result calculated from the normative identity
projection. Java represents every BlueId as `String`; there is no separate
semantic or meaning identifier type.

The following complete Java 8 program creates exact direct input, calculates
its BlueId, and creates a pure reference to the same content:

```java
import blue.language.api.BlueLanguage;
import blue.language.model.Node;

import static blue.language.utils.Properties.TEXT_TYPE_BLUE_ID;

public final class NodesAndBlueIdsExample {
    public static void main(String[] args) {
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            Node exact = new Node()
                    .type(new Node().blueId(TEXT_TYPE_BLUE_ID))
                    .value("hello");

            String blueId = language.identity().directBlueId(exact);
            Node reference = new Node().blueId(blueId);

            if (!blueId.equals(reference.getBlueId())) {
                throw new AssertionError("Reference identity changed");
            }
        }
    }
}
```

Direct identity requires exact BlueId input. Human-friendly Source constructs
such as `type: Text`, a root `blue` directive, `$pos`, or `$replace` must
instead use the Source Document identity path. Exact list identity input may
start with the specification-defined `$previous` prefix accumulator and may
contain the exact `$empty` marker; those are direct list controls, not authored
positional overlays.

`Node` remains mutable by design. Public semantic operations do not mutate
their input and return caller-owned values. Runtime snapshots retain
`FrozenNode` graphs, which are immutable and safe to share; methods that expose
a mutable `Node` materialize a detached copy.

See [direct versus Source identity](direct-vs-source-blueid.md) for the two
preparation paths and their shared final calculation.
