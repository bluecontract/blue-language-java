# Debugging And Diagnostics

Start with the closed result status. Only `success` commits. `no-match`,
`stale`, and `terminated` are expected terminal outcomes and have no diagnostic.
Failure statuses carry a stable `ProcessorErrorCategory` plus optional stable
details.

Call `processDocumentWithTrace(root,event)`, then inspect
`processResult().status()`, `processResult().totalGas()`, `trace().gas()`, and
`processResult().diagnostic()` in that order. These are API names, not a host
logging prescription; format output in host code.
Never branch on exception class names, localized messages, timings, cache
statistics, or stack traces.

## Triage order

1. Confirm the exact Root BlueId, event BlueId, runtime-registry identity, gas
   manifest, and evidence revision.
2. If the attempt needs resources, fulfill only the reported exact requests and
   retry the original inputs.
3. Compare status and diagnostic category/details.
4. Compare the named gas trace up to the first difference.
5. Compare logical provider demands, not backend call counts.
6. Re-run with inline and pure-reference representations to expose invalid
   evidence or ambient runtime dependencies.

`portable-limit-exceeded` identifies a fixed manifest boundary through
`limitName`, `observed`, and `limit`. Increasing gas is not a fix.
`subscription-surface-invalid` means the input or tentative Root cannot produce
a finite canonical feeder index; inspect `scopePath` and `contractKey` when
present.

Operational observations can be captured with `RecordingProcessingObserver`,
JFR, or a composite observer. Observers are deliberately outside gas and
semantic decisions. Throwing, blocking, or stateful observers should be treated
as host instrumentation defects, not Contracts behavior.

See [Processor results, diagnostics, and recovery](../processor-results-diagnostics-and-recovery.md)
for the full status matrix and stable detail vocabulary.
