/**
 * Focused immutable canonical patching service for Language callers.
 *
 * <p><strong>Contents.</strong> The service boundary that applies one
 * Language-owned patch to canonical input or a resolved snapshot belongs here.
 * Mutable JSON editing utilities and Contracts transaction policy do not.</p>
 *
 * <p><strong>Entry points.</strong>
 * {@link blue.language.patching.BluePatching} accepts
 * {@link blue.language.snapshot.BluePatch} values and returns immutable patch
 * results or snapshots.</p>
 *
 * <p><strong>Lifecycle.</strong> Patch values and results are defensive and
 * reusable. A service instance follows its owning Language runtime and is no
 * longer usable after that runtime closes.</p>
 *
 * <p><strong>Extension.</strong> New operation kinds require a Language change;
 * callers compose existing operations rather than extending the closed
 * semantics. Immutable node mechanics live in {@code blue.language.snapshot},
 * and snapshot pairs live in {@code blue.language.merge}.</p>
 */
package blue.language.patching;
