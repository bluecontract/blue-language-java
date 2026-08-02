# Contracts registry correction: schema enum normalization

## Decision

The Blue Language 1.0 rule remains unchanged: `schema.enum` is a set of typed scalar identities. Authoring order is not semantic. During direct BlueId input construction, enum entries are typed, sorted by their RFC 8785 canonical typed-scalar identity bytes, and deduplicated.

The previous package-generation helper incorrectly preserved enum authoring order while calculating Contracts runtime registry BlueIds. The Java implementation correctly followed Language §9.8.1 and therefore rejected the supplied manifest. The implementation was right to stop.

This package corrects the generated artifacts rather than changing the Language algorithm.

## Corrected runtime identities

| Runtime entry | Previous incorrect BlueId | Correct BlueId |
|---|---|---|
| Document Update | `5qmRyRFrX38eVmgtRxUb79R27sG8VJRJcgsafyANxKgG` | `7HZ6UDNxDdGdvhowi92mwB4EAqKfeynpJEFUFVvjmTJ2` |
| Json Patch Entry | `6ibiR9xVJNErraawKrsDzrGS3H5HyUUNwdZbDTDbU2U6` | `5UihWoxkyiUbv9TZk7HcHsa82sz2R3ex1WifQQpKtHpP` |
| Scripted External Channel | `LYwiqvSHTUSVN15kKLxFhVLF2qLrzVu1grmYUjbqqgp` | `2hesjWGVbvcJSu6woCUTssU9S7A69ep93UzdgvwosDLt` |
| Contract Execution Result | `6i9NrtN7uqtSYx136MwLyZSiLjJ98aCUCJNHuQvZah6n` | `3aKiqpRW7E6kfk1LTrEijsQux49cx2T5xDX3faSzv3gv` |
| Scripted Handler | `DT9DtvU5MQbR1NWN46h6JzJFBwyhEWa4iQQHEw6S5QVZ` | `6rznQbYVahD1UVqdRXbPy7wF1NV5LYhDyzThEL1znaFw` |

The last two identities changed transitively because their canonical nodes reference corrected runtime types. No registry node prose or business semantics changed. `Process Embedded`, including `collectionPaths`, is unchanged.

## Corrected package identities

```text
Corrected ZIP:              ba7859cad8eb499fd394d236705d17c48eadb5304526e2ca27a563ee400c5251
Top-level release package:  sha256:0268c0adc8badf0d1ab5cdef4a323117b82253a3695f9125af750437a23014b6
Contracts runtime registry: sha256:46a7744c1cbfa4b00e1d8a99f6ca3f0089ef697de968fee08547894ab02b0ca1
Contracts fixture package:  sha256:16392301655431695df6a7cc142a7e388e426c382bf4e3c5f06ddfafb8efecdc
Contracts gas manifest:     sha256:88c7bbe77d531c9e973cae13002c3464a2c14568833adf5d804d13b7b3d26af5
```

The fixture scenarios and assertions are unchanged. Fixture files were rebound to the corrected conformance-only Scripted runtime BlueIds, exact provider-node identities were regenerated where their content changed, and file/package hashes were recalculated.

## Generator correction

The corrected package's `tools/fixture_blueid_v1.py` applies the same enum
normalization rule before direct registry hashing. That tool belongs to the
authoritative package, not this Java repository. The package validator and
repository tests include explicit regressions for the three enum-bearing
registry nodes and their two transitive dependents.
