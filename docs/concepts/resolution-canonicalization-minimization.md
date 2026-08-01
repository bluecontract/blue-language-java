# Resolution, canonicalization, and minimization

These operations answer different questions:

- resolution establishes complete type-derived meaning;
- canonicalization produces the unique exact identity input;
- minimization produces a smaller ordinary Source overlay with the same
  complete meaning.

```java
import blue.language.api.BlueLanguage;
import blue.language.codec.BlueFormat;
import blue.language.model.Node;

public final class ResolutionAndIdentityExample {
    public static void main(String[] args) {
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            Node source = language.codec().parseSource(
                    "type: Text\nvalue: hello", BlueFormat.YAML);

            Node resolved = language.resolution().resolve(source);
            Node canonical = language.identity()
                    .canonicalIdentityInput(source);
            Node minimized = language.resolution().minimize(source);
            String originalId = language.identity()
                    .sourceDocumentBlueId(source);
            String minimizedId = language.identity()
                    .sourceDocumentBlueId(minimized);

            if (resolved == null
                    || !originalId.equals(minimizedId)
                    || !originalId.equals(
                    language.identity().directBlueId(canonical))) {
                throw new AssertionError("Semantic forms diverged");
            }
        }
    }
}
```

Canonicalization consumes list controls and applies the specification's exact
omission tie-breakers. Its result is valid direct BlueId input. Minimization may
emit list controls and must be passed through preprocessing and complete
resolution again; it is never called by Source Document identity.

The strict `resolve` convenience method requires complete evidence and throws
when completion is impossible or the input is invalid. `resolveLimited`
returns `BlueOperationResult<Node>` with `ESTABLISHED`, `ABSENT`, `INCOMPLETE`,
or `INVALID`. Missing provider evidence and exhausted limits are
`INCOMPLETE`; they never establish semantic absence. Both forms leave their
input untouched and return caller-owned mutable nodes.
