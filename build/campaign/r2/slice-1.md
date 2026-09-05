# Slice 1: exact schema arithmetic and private validation helpers

Base: cc5e06ef9dbe782bf4d667368db1a9954ecb86df (resolved uniquely).
Branch: codex/r2-types. This file is committed with its slice; `git log -- build/campaign/r2/slice-1.md` gives its exact commit.

Four independently specified public SDK counterexamples failed before the fix and pass afterwards: binary64 1e23 equality with Integer 99999999999999991611392; Double 2^53 versus Integer divisor 2^53+1; crossed inclusive/exclusive bounds; exact dyadic LCM of binary64 .1 and .3. The latter is 19471113219505603967277331424215 / 18014398509481984, represented by its Integer numerator over the supported value domains.

Schema propagation, validation and frozen matching now share exact arithmetic. Six accidental public utility entries are removed by package-private helpers in merge.processor; the three intended declaration API/SPI additions remain. Frozen schema predicates delegate to the existing validator, including required presence and invalid schemas.

Specification: §9.6 adds deterministic mixed/Double LCM normalization. Existing exact comparison and §9.9 contradiction requirements are enforced. Primary and mirror are byte identical. No binding or golden regeneration.

Validation commands:
- :blue-language-core:test --tests '*NumericConstraintExactnessTest' (03 reproduced all four failures; 04 passes).
- :test --tests 'blue.language.matching.*' --tests 'blue.language.merge.processor.Schema*Test' --tests 'blue.language.merge.processor.EnumConstraintMembershipTest' --tests 'blue.language.identity.SchemaEnumCanonicalizerTest' (05 passes).

blue-language-core/build/test-results/test: 4 tests, 0 failures/errors
build/test-results/test: 94 tests, 0 failures/errors

Integration: A2 must regenerate authoritative spec bindings after candidate review. Canonical effective mixed-Double multipleOf schemas can change; this repairs acceptance of values violating inherited constraints. This is a development slice, not release sealing. Wider regression and independent finite-oracle coverage continues in subsequent slices.
