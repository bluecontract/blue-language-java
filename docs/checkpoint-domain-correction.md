# Checkpoint domain correction

Contracts §10.2 permits the exact checkpoint-domain object or a reference to
that object. `CheckpointEntry.domain` now retains `schema.required: true`
without the contradictory `Text` type restriction. Runtime-specific domain
validation remains the processor's responsibility.

`CheckpointEntry.domainBlueId()` uses the existing direct BlueId calculator
for either representation, as `subjectBlueId()` already does. A restored
inline domain therefore matches the delivery domain and the repeated event
is skipped. The direct BlueId algorithm is unchanged.

The definition correction changes `CheckpointEntry` from
`2uJq8ZJGyUpMiZckxopH2koa7ZFRavVacpu2eGdK2UwY` to
`55JVRmcrK9fcbGmpvYv6KLtoKendvDUBrYfv3nasuQne`, and its dependent
`ChannelEventCheckpoint` from `Ag2NpsQnNpn8nNRopURxcWVHRnYu5REDZeS8YJcfvQUS`
to `6H1dvQ7QnuNRhr9uRCewc7w7yn1UhZuHF2kZYhUKecQr`. Registries, fixture
inputs and expectations, cyclic oracles, Java bindings, and manifests are
regenerated against the unchanged Language implementation. Historical exact
identities remain bound to their original inputs; no aliases are introduced.

## Regression coverage

- `BlueRuntimeTypeRegistryTest` resolves inline and referenced domains and
  rejects an absent required domain.
- `CheckpointManagerTest` restores both persisted representations, recognizes
  replay, and permits a new subject or changed runtime domain.
- `ChannelRunnerTest` runs the handler: the first event increments the counter
  to one, replay leaves it at one, and a new event increments it to two.
  Before the accessor correction the inline case increments again on replay.

## Source canonicalization dependency

This is the prerequisite extracted from
[PR #36](https://github.com/bluecontract/blue-language-java/pull/36).
Contextual Source canonicalization and explicit checkpoint-entry type
serialization remain in the dependent PR: the latter must follow Language's
new rule retaining effective custom types in canonical children. This
prerequisite preserves the current Language canonical form.
