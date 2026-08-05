# One-Root Contracts processing

See [Contracts processing](../guides/contracts-processing.md) for the two-input
model, embedded scopes, internal drain, persistent changes, and Root-only
emissions.

`Process Embedded.paths` owns one exact child per pointer.
`Process Embedded.collectionPaths` owns every direct stable-key object member
below each declared collection pointer. Neither form creates another Root or
commit boundary. Collection selectors are not wildcards, do not select List
positions or `/contracts/...`, and do not import a parent Channel. See
[Embedded collection paths](../guides/embedded-collection-paths.md).
