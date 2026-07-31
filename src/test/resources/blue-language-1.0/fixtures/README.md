# Blue Language 1.0 conformance fixtures

This directory is the machine-readable conformance package for Blue Language 1.0. It contains 153 exact behavior fixtures covering every prose vector, including BlueId, preprocessing, resolution, canonicalization, minimization, limited operations, providers, circular sets, registry identity, and documentation lint.

Read `HARNESS.md` before implementing a runner. Unknown operations or expected fields are errors and MUST NOT be skipped. The fixture package contains no gas model; Language operations define meaning and identity only.

The `preprocessing/` directory contains the normative Blue-directive fixtures and a closed conformance-only transformation registry. It verifies that directives may be inline or pure references, imports and transformations coexist, transformations execute exactly once in declared order before mandatory baseline normalization, and unsupported or unverified transforms fail closed.
