# Literal empty-collection acceptance

RCP-RUN-011 now contains 19 concrete SDK steps. The original supplied recipe is
retained byte-for-byte with its SHA-256 under provenance/recipe-inputs. The source
YAML files are unchanged. The Orders document starts with orders: {} and both
listeners registered. Payment and both Orders start independently; Order A uses
the captured initialized payment reference. The parent adds the two captured
member references afterward.

Two member operations must produce direct counts 1 then 2 and descendant counts
1 then 2. A payment operation must leave the direct count at 2 and advance only
the descendant count to 3. Each phase has an exact ordered event inventory.
An unrelated source on a separate timeline is then processed; the parent must
have no eligible input and retain exactly the same state and history. A final
retained-store reconstruction compares complete records. Fresh-JVM coverage is
provided separately by the unchanged packaged collection acceptance tests.

The independent checker rejects missing, extra, reordered and wrong-origin phase
events. Six synthetic negative/positive checks supplement the existing hardened
suite; they provide no runtime conformance credit. Actual execution status is
stored separately from fixture metadata, which remains NOT_RUN; separate results bind each evaluation to exact
application bytes and the independent dependency/source lock.

The first execution stopped before admission because the runner supplied only
examples/iteration2 YAML. The input loader now reads the exact declared example
paths, rejecting missing files, absolute paths, traversal and non-YAML inputs.
No source document or runtime behavior changed to repair that harness issue.

The complete literal passed on application SHA-256
3b9422207ecb6008c3c3c6e33cb12574bd41ff6b457d3a3ebe52a9aa7ec85b44.
Evidence: rooted-production-empty-collection-declared-sources.json. The new
loader and phase inventory checks pass all 54 hardened checker tests; the 64
Iteration 2 and 18 harness tests remain required as part of final verification.
