# Contracts fixture regeneration tools

This directory vendors the complete Python support closure from the canonical
Blue Contracts 1.0 release pipeline at release commit `5dc8096`, including the
JCS vectors and the upstream `JCS-LICENSE.txt` / `JCS-NOTICE.txt` attribution.
The preserved release entry point is
`release_regenerate_package.py`; the repository entry point is
`regenerate_package.py`.

Verify inline-type parity fixtures and their expected BlueIds with the
independent Python identity implementation (this does not invoke Java
canonicalization):

```bash
PYTHONDONTWRITEBYTECODE=1 python3 \
  blue-conformance/src/main/tools/verify_inline_type_fixture_oracles.py \
  --fixture-root \
  blue-conformance/src/main/resources/blue-language-1.0/fixtures
```

The repository entry point always requires an explicit resource-package root:

```bash
PYTHONDONTWRITEBYTECODE=1 python3 \
  blue-conformance/src/main/tools/regenerate_package.py \
  --package-root \
  blue-conformance/src/main/resources/blue-contracts-closure-1.0
```

With no output option, regeneration is a read-only byte-identity check.
`--stage-output DIR` writes only the candidate `conformance/contracts`
resource package. `--stage-release-output DIR` instead retains the complete
generated release shell, including its specifications, Language reference,
vendored tools, Java shape-smoke templates, top-level manifests, and the same
candidate Contracts subtree. The Java templates are retained under
`blue-conformance/src/main/templates/java-templates` and copied byte-for-byte;
full package validation compiles and runs them.
Both destinations must be new or empty and outside the repository/source
trees. Only `--write` can replace the checked-in resource package. The ordinary
`C-EVO-01..17` tranche is always rebuilt by the checked-in deterministic
generator before manifest rebinding. An optional `--fixture-source-root DIR`
requests the identity-free `FL-ADM` and `C-EVO-18..23` source pass; that mode
invokes the checked-in normative Java exporter and fails closed if the bridge
fails or returns anything other than the complete thirty-one-file inventory.
After the ordinary and optional full-lifecycle passes, every regeneration also
invokes the checked-in managed-transition receipt exporter against the
disposable candidate. It executes all eighty non-limit closure fixtures,
requires the exact ninety-eight-file inventory (including eighteen limit
microfixtures), and rebinds only the seventy-one complete-result receipt surfaces
and fifty-five committing companion identities. Receipt bodies, gas partitions, and
the 1.1 companion binding are never reconstructed in Python.

The Python pipeline uses Python 3, PyYAML, and (for full package validation)
`jsonschema`. It does not import or execute tools from an external checkout and
does not contain machine-specific runtime paths. Set
`PYTHONDONTWRITEBYTECODE=1` when invoking individual preserved support scripts;
the repository entry point and classifier also enforce it internally.

`implementation-baseline-paths.txt` is the authoritative, strictly sorted
inventory of every Java source under the fixed `src/main/java` roots of
`blue-language-model`, `blue-language-core`, `blue-language-mapping`,
`blue-language-ipfs`, `blue-contracts-core`, and `blue-language-java` (722
files in this release). `implementation_baseline.py` assigns the complete
six-module closure to the Contracts processor role and, conservatively, the
complete Language model-plus-core source closure to both cyclic finalization
and proof-verification roles. This binds transitive Language code without
claiming that source identity alone identifies compiler output or resources.

Check the inventory without modifying it:

```bash
PYTHONDONTWRITEBYTECODE=1 python3 \
  blue-conformance/src/main/tools/generate_implementation_baseline_inventory.py \
  --check
```

The repository and preserved release entry points independently scan all six
fixed roots and fail before generation on an unlisted addition, missing entry,
symlink, non-regular path, duplicate, unsorted path, or malformed path. Use the
same command with `--write` only when intentionally reviewing an inventory
transition. Source-archive filename and SHA-256 are distribution provenance
only: when supplied, they are recorded in the non-semantic
`validation-output.json` receipt and do not participate in release or package
identity. The receipt is excluded from the deterministic release ZIP.
Regeneration comparison ignores its source-archive filename and digest while
continuing to check every other receipt value.

Archive a retained complete release without making its temporary directory
name part of the ZIP bytes:

```bash
PYTHONDONTWRITEBYTECODE=1 python3 \
  blue-conformance/src/main/tools/regenerate_package.py \
  --package-root \
  blue-conformance/src/main/resources/blue-contracts-closure-1.0 \
  --stage-release-output /outside/the/repository/staged-release

PYTHONDONTWRITEBYTECODE=1 python3 \
  /outside/the/repository/staged-release/tools/build_release_archive.py \
  --root /outside/the/repository/staged-release \
  --output /path/to/blue-contracts-and-processor-specification-1.0-final.zip
```

An explicit `--root` must have the complete generated release layout and the
canonical package name. Archive output must be outside that release tree. The
archive uses the canonical package directory name, fixed entry metadata, and
excludes the local non-semantic `validation-output.json`, so equivalent staged
releases produce byte-identical ZIPs regardless of their staging-directory
names or source-archive provenance.

This repository entry point proves regeneration and exact candidate retention;
it does not independently issue the `PACKAGE_VALID` receipt because the two
validator-only `java-templates` trees are not repository inputs. A complete
upstream release that supplies those templates can run the vendored
`validate_package.py` before archiving; the checksum gate prevents any later
file change from being archived under that manifest.

`classify_fixture_identity_delta.py` compares a pre-rebind and post-generation
resource package, writes JSON and Markdown reports outside both packages, and
exits with status 2 under `--fail-on-unexpected` if an existing
`PROCESS_CLOSURE` fixture changes beyond the named identity surfaces.
