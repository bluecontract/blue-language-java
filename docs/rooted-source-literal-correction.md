# Source request field in the rooted literal plans

The original source declares `/contracts/setCounter/request/value` as an object
containing `type: Integer`. Blue reserves `value` for scalar value metadata, so
this declaration fails before admission: `IllegalArgumentException: Can't handle
node: {"type":"Integer"}`. The original handoff remains unchanged.

A real SDK probe against Coordination f2bfd151aaa9071f239d58aa1962e3143919713a
and Language6997bbc rejects the original bytes and admits the corrected source.
Evidence: MyOS campaign `evidence/rooted-adapter-compilation/literal-source-baseline.log`.
The artifact-bound adapter also rejects original RUN-001 at its first setup step
on application1088f02b3147; the raw failure and incomplete transcript are retained.
The maintained SDK/MyOS source fixtures already use the corrected spelling.

This correction changes the declared request field to `counterValue`, the BEX
binding to `event/message/request/counterValue`, and only the corresponding
literal `setCounter` requests. It preserves the Integer constraint, source
initial counter, tick behavior, expected counter5, source ordering, ownership,
gas tariff, history positions and all checker assertions. Production metadata
remains NOT_RUN; separate executed results establish each actual passing scope.

| File | Before SHA256 | After SHA256 |
| --- | --- | --- |
| examples/iteration2/source.yaml | 50a36a85f7a2cc009c5fede556716e54b4231c0df6297d1aa4fa694b9b51f8c0 | a86c519302d1800d569b21081f959d196d75eeab5926a9357c102ab399f70864 |
| plans/rcp-run-025.json (2 requests) | 44b1e7a0c6776b4b8a1e5ad6dce348475edbdf7627fe8b29fca354be241ad77f | 0b13930a44f960c047ab9fb97a9322953bf9bb923c051eabac8c223f0b4c6314 |
