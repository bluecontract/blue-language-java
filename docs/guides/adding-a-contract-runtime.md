# Adding A Contract Runtime

A runtime extension supplies deterministic behavior for one exact contract
type. It must not introduce ambient I/O, mutable global state, wall-clock input,
or application-specific behavior into the generic kernel.

## 1. Define the contract value

Use `ChannelContract` for an external source, `HandlerContract` for executable
behavior, or `MarkerContract` for a non-executable recognized marker.

```java
public final class SetValue extends HandlerContract {
    private String path;
    private Node value;

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public Node getValue() { return value; }
    public void setValue(Node value) { this.value = value; }
}
```

## 2. Implement the focused processor

```java
public final class SetValueProcessor implements HandlerProcessor<SetValue> {
    @Override
    public Class<SetValue> contractType() {
        return SetValue.class;
    }

    @Override
    public void execute(SetValue contract, ProcessorExecutionContext context) {
        context.applyPatch(JsonPatch.replace(
                context.resolvePointer(contract.getPath()),
                contract.getValue()));
    }
}
```

Read document state only through `ProcessorExecutionContext`. Return effects
through its patch, event, termination, and runtime-gas boundaries. A retained
context is invalid after execution closes.

## 3. Register exact type evidence

Calculate the canonical type BlueId with the Language API and register both the
BlueId and canonical type node. The registry snapshot bound into a modern
`DocumentProcessor` is immutable.

```java
ContractProcessorRegistry registry = new ContractProcessorRegistry();
registry.register(setValueBlueId, setValueTypeNode,
        new SetValueProcessor());

DocumentProcessor processor = DocumentProcessor.builder()
        .nodeProvider(provider)
        .runtimeRegistry(registry)
        .gasSchedule(GasSchedule.contracts10())
        .snapshotStore(snapshotManager)
        .deliveryPlanDeriver(planDeriver)
        .evidenceVerifier(evidenceVerifier)
        .subscriptionSurfaceValidator(surfaceValidator)
        .observer(NoOpProcessingObserver.INSTANCE)
        .build();
```

## 4. Test the deterministic boundary

Use given/when/then tests for:

- inline and pure-reference representations;
- accepted, rejected, stale, and missing-evidence paths;
- exact gas trace and gas-exhaustion retry;
- patch boundary and protected-state rejection;
- Root-only events and rollback;
- concurrent calls through one built processor.

Channel extensions also test subscription projection, checkpoint domain and
subject, same-scope dependency declarations, logical delivery grouping, and
source-versus-target authority. Executable bodies must remain cold until a
handler is selected.
