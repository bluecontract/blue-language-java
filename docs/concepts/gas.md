# Portable Gas

Portable gas is the deterministic semantic-work trace for one invocation. It
is separate from operational telemetry such as nanosecond timings, provider
calls, cache hits, bytes transferred, or thread scheduling.

```text
same exact Root + event + evidence + registry + gas manifest
    => same named charge trace and total
```

One `GasMeter` owns the ordered trace and live invocation limit.
`ProcessGasMeter` maps processor phases to named counters;
`SemanticGasMeter` charges representation-blind Language work; and
`RuntimeWorkSession` admits named child-runtime charges against the same parent
budget. A child ledger can merge once.

## Admission rules

- A charge is checked before its corresponding work.
- A rejected charge is absent from the trace.
- Gas exhaustion retains the exact admitted prefix.
- Carrying an already established exact value is cheap; inspecting or rebuilding
  it is charged.
- Text blocks, integer limbs, members, comparisons, validation, changed-spine
  identity work, patches, events, and lifecycle operations use named manifest
  counters.
- Provider acquisition, cache layout, serialized transport size, and wall-clock
  time never affect portable gas.

Portable limits are different: they bound one structural dimension such as a
direct container, pointer depth, event queue, or patch list. More gas cannot
repair `portable-limit-exceeded`. See
[Processor results, diagnostics, and recovery](../processor-results-diagnostics-and-recovery.md).
