# Preserve empty schema in frozen exact identities

The optimized frozen digest omitted a present `schema: {}` while the generic
direct digest and the serialized exact value retained it. For
`type: Integer; value: 3; schema: {}`, the ordinary exact identity is
`4SrR9s5T8u24vn5vLB5nPh3eSUtmeGR9uD2uDxLyvpzt`. The defective frozen/snapshot
path returned `DRVVrXdZfVEbBQ94B1ExfokDDV6pKhbva5YhVQgNvs4H`, the identity
of the value with schema absent.

`FrozenCanonicalDigester.calculateSchemaBlueId` now hashes the empty schema
map instead of returning null. An absent schema still contributes no field.
This restores the existing Language exact-object formula; it changes no
validation, publication, epoch, or tariff rule.

The new `FrozenCanonicalDigesterTest` case compares the independent ordinary
digest, generic frozen projection, optimized digest and cached frozen identity
for a root, an ordinary child and a list item. It verifies that schema absence
remains distinct and that the direct path does not silently fall back.
`SourceSchemaNullNormalizationTest` checks actual source preprocessing,
snapshot bytes and identity, minimization, and canonical exact graph
expansion/collapse. Both regressions failed before the correction.

RCP-RUN-031 supplies the public SDK companion. A definition is prepared with
`providerContentYaml` and supplied through `ExactNodeProvider`; completed
instances use `values().yaml`. The graph API receives canonical exact content.
Neither boundary invents a sample definition value or treats unprocessed
authoring aliases as canonical exact input. The conformance runner also binds
the exact source-file inventory and hashes.

Historical artifacts retain their original bytes. Corrected identities require
new immutable builds; no content is replaced under an old identity. Focused
checks alone do not establish the final release or product acceptance result.
