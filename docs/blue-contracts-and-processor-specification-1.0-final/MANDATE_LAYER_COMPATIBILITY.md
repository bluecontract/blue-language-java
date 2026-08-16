# Compatibility with Mandate-Aware Coordination

This file is informative and is deliberately outside the Blue Contracts
specification.

Blue Language and Blue Contracts already provide the generic mechanisms needed
by a later Mandate-aware Coordination layer:

- a Mandate is an ordinary exact Blue document with contracts, initialization,
  lifecycle, Channels, workflows, events, checkpoints and history;
- the feeder may resolve any external authority evidence before constructing
  the frozen direct-delivery snapshot;
- the processor receives the original exact Timeline Entry/event and does not
  need a hidden authorization object;
- direct eligibility, exact historical Mandate-state resolution, authority
  holder/actor checks, target document, target Channel, target Operation and
  request constraints belong to the Coordination feeder/profile;
- when evidence is unavailable or invalid, the feeder withholds or defers the
  delivery before Contracts processing.

Therefore no Mandate type, `onBehalfOf` field, Mandate status, or Mandate
resolver belongs in Blue Language 1.0 or Blue Contracts and Processor
Specification 1.0. Adding Mandate-aware Coordination later does not require a
Language or Contracts semantic revision.
