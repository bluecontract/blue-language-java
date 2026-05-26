# Blue Contracts 1.0 Fixture Package

This directory is the Blue Contracts and Processor 1.0 conformance fixture
package referenced by the runtime registry manifest.

## Fixture Package Identity

`fixturePackageIdentity` is a SHA-256 digest over the fixture manifest and every
listed fixture file.

Current identity:

```text
sha256:2f197ca3bbdc41b75e772777cc48e51019754347e1bee26b5f3209b71d9bd9ca
```

Digest calculation:

1. Normalize all line endings to LF.
2. Start the digest with the UTF-8 bytes of `manifest.yaml\n`.
3. Read `manifest.yaml`, replace the line beginning `fixturePackageIdentity:`
   with `fixturePackageIdentity: ""`, normalize line endings, and append those
   bytes.
4. Iterate manifest `fixtures` in manifest order. Do not sort paths separately.
5. For each fixture, append the UTF-8 bytes of `\n--- <path>\n`, then append the
   fixture file bytes after LF line-ending normalization.
6. Encode the digest as lowercase hexadecimal prefixed by `sha256:`.

The release manifest uses `requiredFixtureSet: exact`. Release tooling should
verify that every manifest entry exists, every fixture ID is unique, and no
unlisted fixture YAML files are present.
