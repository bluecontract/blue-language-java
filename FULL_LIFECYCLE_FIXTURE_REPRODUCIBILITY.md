# Full-lifecycle fixture reproducibility

## Final package boundary

This report records the final independent regeneration proof for the
full-lifecycle admission checkpoint. The checked-in package used as the
comparison target has fixture package identity
`sha256:3bb21b5df6eb87b578e9647f11d094aff2cf45c56b3b7050f8d854147bdb3e3d`
and Contracts release identity
`sha256:5917b16adfde2ed6bb21bac74c40a1b44526d7c9ddb3faaaf5fbe8a13aae3b1c`.

The final proof included the identity-free source pass. Omitting
`--fixture-source-root` would only revalidate the already generated FL-ADM
files and therefore would not satisfy this checkpoint.

## Independent generations

Both commands were run from the repository root with the system Python 3
runtime. `PYTHONDONTWRITEBYTECODE=1` prevents interpreter cache files from
becoming incidental evidence.

```bash
PYTHONDONTWRITEBYTECODE=1 /usr/local/bin/python3 \
  blue-conformance/src/main/tools/regenerate_package.py \
  --package-root blue-conformance/src/main/resources/blue-contracts-closure-1.0 \
  --fixture-source-root blue-conformance/src/main/fixture-sources/full-lifecycle \
  --stage-output /private/tmp/fladm-generation-final-a.nyTTWK
```

```bash
PYTHONDONTWRITEBYTECODE=1 /usr/local/bin/python3 \
  blue-conformance/src/main/tools/regenerate_package.py \
  --package-root blue-conformance/src/main/resources/blue-contracts-closure-1.0 \
  --fixture-source-root blue-conformance/src/main/fixture-sources/full-lifecycle \
  --stage-output /private/tmp/fladm-generation-final-b.EnT9GC
```

Each generation executed the normative Java exporter, required the complete
thirteen-file FL-ADM inventory, rebuilt the full resource package in fresh
temporary storage, and completed successfully.

## Byte comparisons

The following comparisons completed with exit code 0 and no differences:

```bash
diff -qr \
  /private/tmp/fladm-generation-final-a.nyTTWK \
  /private/tmp/fladm-generation-final-b.EnT9GC
```

```bash
diff -qr \
  /private/tmp/fladm-generation-final-a.nyTTWK \
  blue-conformance/src/main/resources/blue-contracts-closure-1.0
```

Therefore generation A equals generation B byte-for-byte, and both equal the
canonical checked-in package byte-for-byte.

## Final read-only check

The repository entry point was then run without an output mode:

```bash
PYTHONDONTWRITEBYTECODE=1 /usr/local/bin/python3 \
  blue-conformance/src/main/tools/regenerate_package.py \
  --package-root blue-conformance/src/main/resources/blue-contracts-closure-1.0 \
  --fixture-source-root blue-conformance/src/main/fixture-sources/full-lifecycle \
  --check
```

It returned `PACKAGE_REGENERATION_CHECK_OK`. No external package checkout,
Maven Local publication, deployed artifact, or network-published artifact was
used as release evidence.
