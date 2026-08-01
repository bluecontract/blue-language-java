# Direct versus Source Document BlueId

Blue has one BlueId algorithm and two input paths:

```text
exact BlueId input --------------------------> direct BlueId

Source -> preprocess -> complete resolve
       -> canonical identity input ----------> direct BlueId
```

This complete Java 8 program demonstrates that exact and authored forms reach
the same identifier for the same node:

```java
import blue.language.api.BlueLanguage;
import blue.language.codec.BlueFormat;
import blue.language.model.Node;

import static blue.language.utils.Properties.TEXT_TYPE_BLUE_ID;

public final class DirectVsSourceBlueIdExample {
    public static void main(String[] args) {
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            Node exact = language.codec().parseBlueIdInput(
                    "{\"type\":{\"blueId\":\"" + TEXT_TYPE_BLUE_ID
                            + "\"},\"value\":\"hello\"}",
                    BlueFormat.JSON);
            Node source = language.codec().parseSource(
                    "type: Text\nvalue: hello", BlueFormat.YAML);

            String direct = language.identity().directBlueId(exact);
            Node canonical = language.identity()
                    .canonicalIdentityInput(source);
            String fromSource = language.identity()
                    .sourceDocumentBlueId(source);

            if (!direct.equals(fromSource)
                    || !fromSource.equals(
                    language.identity().directBlueId(canonical))) {
                throw new AssertionError("Identity paths diverged");
            }
        }
    }
}
```

`directBlueId` is strict: it neither preprocesses nor resolves its argument and
rejects Source-only constructs. `canonicalIdentityInput` and
`sourceDocumentBlueId` require complete provider evidence for the Source graph
and fail closed when that evidence cannot be established. None of these
operations mutates the supplied `Node`.

Canonicalization is deterministic and produces exact direct input.
Minimization is deliberately absent from this pipeline: it produces an
author-facing Source overlay and can have more than one valid representation.
