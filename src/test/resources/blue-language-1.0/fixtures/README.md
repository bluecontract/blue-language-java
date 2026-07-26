# Blue Language 1.0 conformance fixtures

This directory is the machine-readable conformance package for Blue Language 1.0. It contains 125 exact behavior fixtures covering every prose vector, including BlueId, preprocessing, resolution, canonicalization, minimization, limited operations, providers, circular sets, registry identity, and documentation lint.

Read `HARNESS.md` before implementing a runner. Unknown operations or expected fields are errors and MUST NOT be skipped. The fixture package contains no gas model; Language operations define meaning and identity only.
