# Thread Safety And Ownership

A processor built through the modern builder is an immutable generation. The
builder snapshots the runtime registry and configuration; later mutation of the
builder or source registry cannot change an already built processor.

The builder freezes these collaborator groups: verified node provider; runtime
registry generation; gas schedule and limit; delivery-plan derivation and
evidence verification; subscription-surface validation; snapshot store;
observer; and bounded cache policy. The runnable
[`CustomExternalChannelExample`](../../examples/src/main/java/blue/language/examples/CustomExternalChannelExample.java)
shows a complete immutable runtime generation.

Create a new processor generation to change any semantic collaborator. Do not
mutate a live generation or protect arbitrary reconfiguration with a global
read/write lock.

```mermaid
flowchart LR
    P["immutable processor generation"] --> S1["invocation session A"]
    P --> S2["invocation session B"]
    P --> S3["invocation session C"]
```

Every call creates its own `ProcessingSession`, gas meter, evidence view,
contract caches, event queue, lifecycle state, mutation transaction, and output
collector. Invocation-local objects are never reused across calls.

Shared collaborators must satisfy their declared contract:

- providers and snapshot stores return immutable or defensive exact values;
- registered contract processors are stateless or internally thread-safe;
- delivery/evidence/subscription functions are deterministic and do not consult
  mutable ambient state;
- observers may coordinate operational recording but cannot influence semantic
  decisions or gas;
- cache policy bounds processor-owned caches; cache hits cannot alter results.

Configuration produces a new immutable processor generation. Concurrency tests
process distinct inputs through one generation and compare results,
diagnostics, events, demands, and gas traces with serial execution.
