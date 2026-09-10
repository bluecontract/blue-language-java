# Rooted-profile semantic baseline

`api/semantic-baseline-rooted-1.0.json` is the explicit rooted-profile input to
`semanticBaselineVerify`. The historical `api/semantic-baseline-1.0.json` remains
byte-for-byte intact, including its original characterization source and artifact
identities. The capture task still targets that historical file and is not part
of producing the rooted variant.

The variant changes exactly six binding fields: the Contracts specification hash,
aggregate release identity, two copies of the Contracts release identity, and two
copies of its fixture-package identity. These bind the reviewed rooted
specification and regenerated release packages. All 71 exact gas-oracle records,
locality payloads and required tests, API evidence, minimum test count, and
historical provenance remain identical. Historical source and artifact fields
describe the original characterization, not a new measurement on the rooted build.

`RootedSemanticBaselineBindingTest` pins the historical file hash, checks the six
exact rooted identities, and compares the complete remainder with the original.
The existing semantic verifier independently checks the rooted variant against
the actual conformance, gas, locality, and API reports; its rules are unchanged.
Isolated verifier probes rejected wrong specification/package identities, changed
or missing gas evidence, changed locality evidence or required tests, wrong API
inventory, and invalid historical source provenance. Final release acceptance
still requires the complete maintained quality and RC gates on one frozen tuple.
