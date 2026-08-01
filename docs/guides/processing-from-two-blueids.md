# Processing From Two BlueIds

When a host already has the exact Root and event identities, pass pure Blue
references. The configured snapshot/provider boundary retrieves and verifies
their content; the semantic API still has exactly two inputs.

```java
Node rootReference = new Node().blueId(rootBlueId);
Node eventReference = new Node().blueId(eventBlueId);

ProcessAttemptResult attempt =
        processor.processAttempt(rootReference, eventReference);
```

Handle the attempt before using a completed result:

```java
if (!attempt.isComplete()) {
    acquireExactResources(attempt.requiredExactBlueIds()); // host policy
    attempt = processor.processAttempt(rootReference, eventReference);
}

DocumentProcessingResult result = attempt.processResult();
if (result.commits()) {
    persist(result.document(), result.events()); // host transaction
}
```

The helper calls represent host code. Resource acquisition is deliberately
outside semantic execution. Retry with the original exact Root and event after
the reported resources become available.

The snapshot manager verifies that fetched content has the requested BlueId.
A definitive miss, temporary unavailability, and identity-invalid evidence are
different outcomes. Cache warmth, provider batching, and whether either input
was initially inline cannot alter the result or portable gas trace.

For a host that also persists delivery progress and the external subscription
index, use `processDocumentForPlatformCommit(...)` and commit its companion in
the same host transaction as the successful Root.
