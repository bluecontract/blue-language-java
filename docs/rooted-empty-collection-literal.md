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
stored separately from fixture metadata, which remains NOT_RUN until evaluated
against exact application bytes and the independent dependency/source lock.
