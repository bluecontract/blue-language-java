# Language pipeline architecture

`BlueLanguage` is the immutable composition root for eight focused services:

```text
codec -> preprocessing -> graph/provider -> resolution -> snapshots
                                      \-> identity

matching and patching consume the same resolved/snapshot boundaries
```

```java
import blue.language.api.BlueCachePolicy;
import blue.language.codec.BlueFormat;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.provider.NodeProvider;
import blue.language.runtime.BlueLanguage;

import java.util.Collections;

public final class LanguagePipelineExample {
    public static void main(String[] args) {
        NodeProvider provider = blueId -> Collections.emptyList();
        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(provider)
                .cachePolicy(BlueCachePolicy.boundedDefaults())
                .build()) {
            Node source = language.codec().parseSource(
                    "type: Text\nvalue: hello", BlueFormat.YAML);
            ResolvedSnapshot snapshot = language.snapshots().resolve(source);
            String blueId = language.identity()
                    .sourceDocumentBlueId(source);

            if (!blueId.equals(snapshot.blueId())) {
                throw new AssertionError("Snapshot identity diverged");
            }
        }
    }
}
```

## Operation contracts

| Service | Completion and evidence | Returned value and mutation | Provider/cache behavior |
| --- | --- | --- | --- |
| `BlueCodec` | Syntax and direct-input validation only | New mutable `Node`; no semantic operation | No provider or cache |
| `BluePreprocessing` | Strict; referenced directives/imports/transforms require complete verified evidence | New mutable `Node`; input is unchanged | May demand the configured provider; no limited variant |
| `BlueGraph` | `expand`, `collapse`, and `specialize` are strict; `expandLimited` preserves exhaustive outcomes | New mutable `Node`; inputs are unchanged | Expansion and referenced specialization may demand the provider |
| `BlueResolution` | Strict methods require complete meaning; `resolveLimited` preserves exhaustive outcomes | New mutable `Node`; inputs are unchanged | May demand the provider; runtime memoization is semantic-neutral |
| `BlueIdentity` | Direct input is strict and local; Source identity/canonicalization require complete evidence | BlueId `String` or new canonical `Node`; input is unchanged | Source path may demand the provider; minimization is never used |
| `BlueSnapshots` | Resolve/load methods are strict | Immutable `ResolvedSnapshot`; mutable accessors return detached copies | Owns the bounded snapshot cache exposed by `cache`, `cached`, `clear`, and `stats` |
| `BlueMatching` | Strict overloads require their inputs; `matchesLimited` preserves exhaustive outcomes | `boolean` or `BlueOperationResult<Boolean>`; inputs are unchanged | Authored matching may resolve and demand the provider |
| `BluePatching` | Canonical patching is strict; snapshot patching re-establishes a complete snapshot | Immutable result/snapshot; inputs are unchanged | Snapshot application may resolve through the configured runtime |
| `LanguageProcessing` | Opens one-shot or transient processing scopes over exact Language snapshots | Scope-owned immutable snapshots and exact provider outcomes | Sequence/fork caches are run-scoped and never change semantic results |

The exhaustive limited-operation outcomes are `ESTABLISHED`, `ABSENT`,
`INCOMPLETE`, and `INVALID`. `INCOMPLETE` means that more evidence or budget is
needed; it never means absence. Strict convenience methods throw deterministic
exceptions instead of returning a partial value.

## Ownership and dependency direction

Configuration is copied and frozen by `build()`. A built runtime may be shared
when its borrowed `NodeProvider` is thread-safe; callers must not concurrently
mutate a supplied `Node`. Returned mutable nodes are caller-owned, while
`FrozenNode` and `ResolvedSnapshot` are immutable. Closing `BlueLanguage`
clears runtime-owned state and does not close the borrowed provider.

The enforced focused-core boundary prevents core packages from importing the
Contracts processor, conformance implementation, or aggregate façade.
Contracts depends on the public `LanguageProcessing` bridge; Language does not
depend on Contracts. The distribution aggregate composes both without moving
runtime-neutral Contracts behavior into the Language core.

The Language-owned `BluePatch` interface is the patch boundary implemented by
Contracts patch values.
