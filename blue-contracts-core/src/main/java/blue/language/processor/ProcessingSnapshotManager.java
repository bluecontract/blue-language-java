package blue.language.processor;

import blue.language.conformance.ConformanceEngine;
import blue.language.identity.CanonicalTypeIdentityEvidence;
import blue.language.merge.IncrementalValueResolutionRequest;
import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.merge.ResolvedSnapshot;
import blue.language.merge.TypeEvidenceResolution;
import blue.language.snapshot.FrozenNode;
import blue.language.runtime.LanguageProcessing.ExactResolutionOverlay;

import java.util.Collection;
import java.util.Objects;

/**
 * Bridges invocation mutation to canonical immutable snapshot publication.
 *
 * <p>Transient sequences and working documents are ownership scopes: callers
 * must close or release them, and implementations must not publish their
 * intermediate roots. Exact-reference materialization must preserve provider
 * verification and cyclic-member proof.</p>
 */
public interface ProcessingSnapshotManager {

    /**
     * Resolves and publishes an immutable snapshot for an authored document.
     *
     * @param document authored mutable document
     * @return immutable canonical and resolved snapshot
     */
    ResolvedSnapshot fromDocument(Node document);

    /**
     * Resolves a short-lived processing state without requiring it to be
     * published to shared snapshot caches. Implementations that do not have a
     * separate transient path retain their historical behavior by default.
     *
     * @param document authored mutable document
     * @return transient immutable snapshot
     */
    default ResolvedSnapshot fromDocumentTransient(Node document) {
        return fromDocument(document);
    }

    /**
     * Performs an unmasked transient resolution for an operation that must
     * establish the document's canonical identity and complete resolver-issued
     * effective-type identity evidence.
     *
     * <p>This operation is deliberately distinct from path-preserving
     * processing resolution. Implementations must fail closed rather than
     * return a target-limited snapshot or infer identities from its resolved
     * structure.</p>
     *
     * @param document authored document
     * @return transient snapshot with canonical identity and complete type
     *         identity evidence
     * @throws NullPointerException if {@code document} or the delegated
     *         snapshot result is null
     * @throws IllegalStateException if the delegated resolution does not
     *         establish complete type evidence and whole-document identity
     */
    default ResolvedSnapshot fromDocumentTransientForCanonicalIdentity(
            Node document) {
        ResolvedSnapshot snapshot = Objects.requireNonNull(
                fromDocumentTransient(
                        Objects.requireNonNull(document, "document")),
                "canonicalIdentitySnapshot");
        snapshot.canonicalTypeIdentities().requireCompleteCoverage();
        if (!snapshot.hasCanonicalIdentity()) {
            throw new IllegalStateException(
                    "Canonical identity resolution did not establish a "
                            + "whole-document identity");
        }
        return snapshot;
    }

    /**
     * Canonical-identity counterpart that admits one operation-local exact
     * provider ahead of the manager's ordinary provider graph.
     *
     * <p>The default rejects the capability. A decorator holding exact
     * invocation evidence must not silently discard it and retry against an
     * ambient provider.</p>
     *
     * @param document authored document
     * @param exactResolutionOverlay opaque operation-local exact evidence
     * @return transient snapshot with canonical identity and complete type
     *         identity evidence
     * @throws NullPointerException if an argument is null
     * @throws UnsupportedOperationException when overlay-aware authoritative
     *         resolution is unavailable
     */
    default ResolvedSnapshot fromDocumentTransientForCanonicalIdentity(
            Node document,
            ExactResolutionOverlay exactResolutionOverlay) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(
                exactResolutionOverlay, "exactResolutionOverlay");
        throw new UnsupportedOperationException(
                "This ProcessingSnapshotManager cannot establish canonical "
                        + "identity through operation-local exact evidence");
    }

    /**
     * Resolves one authored type declaration as metadata and returns exact
     * resolver-issued identity evidence for that representation.
     *
     * @param declaration authored inline declaration or pure reference
     * @return exact canonical identity and representation evidence
     * @throws NullPointerException if {@code declaration} is null
     * @throws UnsupportedOperationException when the manager cannot expose
     *         verified declaration-resolution evidence
     */
    default CanonicalTypeIdentityEvidence resolveTypeDeclarationIdentity(
            Node declaration) {
        Objects.requireNonNull(declaration, "declaration");
        throw new UnsupportedOperationException(
                "This ProcessingSnapshotManager cannot resolve authored type "
                        + "declaration identity");
    }

    /**
     * Overlay-aware declaration identity resolution.
     *
     * @param declaration authored inline declaration or pure reference
     * @param exactResolutionOverlay operation-local exact evidence
     * @return exact canonical identity and representation evidence
     * @throws NullPointerException if an argument is null
     * @throws UnsupportedOperationException when the manager cannot preserve
     *         the overlay-aware declaration boundary
     */
    default CanonicalTypeIdentityEvidence resolveTypeDeclarationIdentity(
            Node declaration,
            ExactResolutionOverlay exactResolutionOverlay) {
        Objects.requireNonNull(declaration, "declaration");
        Objects.requireNonNull(exactResolutionOverlay, "exactResolutionOverlay");
        throw new UnsupportedOperationException(
                "This ProcessingSnapshotManager cannot resolve authored type "
                        + "declaration identity through operation-local exact "
                        + "evidence");
    }

    /**
     * Resolves a Processing Document while retaining the exact authored
     * subtrees at the supplied paths. Contracts uses this boundary for
     * executable bodies: preflight may resolve their surrounding headers, but
     * the body itself is not a semantic demand until its Handler matches.
     *
     * <p>The default fails closed for a nonempty preservation request.
     * Silently falling back to ordinary eager resolution would turn a deferred
     * executable body into a semantic provider demand. Managers backed by a
     * selective Language resolver must override this method.</p>
     *
     * @param document authored processing document
     * @param preservedPaths absolute paths whose authored form must remain exact
     * @return target-limited snapshot retaining the requested exact Source;
     *         whole-document identity is absent when type evidence is incomplete
     * @throws UnsupportedOperationException when preservation is unsupported
     */
    default ResolvedSnapshot fromDocumentPreservingPaths(
            Node document,
            Collection<String> preservedPaths) {
        if (preservedPaths == null || preservedPaths.isEmpty()) {
            return fromDocument(document);
        }
        throw new UnsupportedOperationException(
                "This ProcessingSnapshotManager does not support deferred path resolution");
    }

    /**
     * Transient counterpart to
     * {@link #fromDocumentPreservingPaths(Node, Collection)}.
     *
     * @param document authored processing document
     * @param preservedPaths absolute paths whose authored form must remain exact
     * @return transient resolved snapshot retaining the requested subtrees
     */
    default ResolvedSnapshot fromDocumentTransientPreservingPaths(
            Node document,
            Collection<String> preservedPaths) {
        if (preservedPaths == null || preservedPaths.isEmpty()) {
            return fromDocumentTransient(document);
        }
        return fromDocumentPreservingPaths(document, preservedPaths);
    }

    /**
     * Calculates the Content BlueId of one selected processing scope as a
     * standalone Blue Language document.
     *
     * <p>The supplied snapshot and selected subtree are an immutable capture of
     * one processing state. Implementations must use the same preprocessing,
     * provider-verification, resolution, and cache-generation context that owns
     * this manager. A canonical fragment of the containing document is not, in
     * general, a standalone scope identity input.</p>
     *
     * <p>The captured selected contribution and completed resolved scope are
     * projected to a standalone Source-equivalent document. The projection is
     * accepted only when resolving it through this manager's full transient
     * Language pipeline reproduces the exact captured resolved scope. The
     * resolved view is never hashed directly and unchecked BlueId calculation
     * is never used.</p>
     *
     * @param scopePath absolute selected scope path
     * @param selectedScope exact selected Source contribution
     * @param capturedDocumentSnapshot immutable containing document snapshot
     * @return strict standalone scope Content BlueId
     * @throws IllegalArgumentException when projection cannot be reproduced
     */
    default String calculateScopeContentBlueId(String scopePath,
                                               FrozenNode selectedScope,
                                               ResolvedSnapshot capturedDocumentSnapshot) {
        return ScopeSourceProjection.project(
                scopePath, selectedScope, capturedDocumentSnapshot, this)
                .contentBlueId();
    }

    /**
     * Materializes one pure reference through this manager's verified provider
     * and cache-generation context for a runtime view that requires its
     * content, such as Contract Recognition Resolution.
     *
     * <p>The returned node is resolved content, not a selected-document
     * mutation. The reference is placed in a type position solely to require
     * the normal Language resolver to fetch and verify its target. This keeps
     * custom managers conservative while avoiding an unchecked provider side
     * channel.</p>
     *
     * @param reference pure exact reference or already materialized node
     * @return immutable verified resolved content
     * @throws IllegalArgumentException when verified content is unavailable
     */
    default FrozenNode materializeVerifiedReference(FrozenNode reference) {
        FrozenNode checked = Objects.requireNonNull(reference, "reference");
        if (!checked.isReferenceOnly()) {
            return checked;
        }
        String blueId = checked.getReferenceBlueId();
        ResolvedSnapshot probe = Objects.requireNonNull(
                fromDocumentTransient(new Node().type(new Node().blueId(blueId))),
                "materializedReferenceSnapshot");
        FrozenNode materialized = probe.frozenResolvedRoot().getType();
        if (materialized == null || materialized.isReferenceOnly()) {
            throw new IllegalArgumentException(
                    "Unable to materialize required reference for blueId: " + blueId);
        }
        Node content = materialized.toNode();
        // Resolved views may retain the source reference BlueId as provenance.
        // It must not become a mixed-reference shape when consumed as content.
        content.blueId(null);
        return FrozenNode.fromResolvedNode(content);
    }

    /**
     * Returns exact canonical provider content for one demanded pure
     * reference, including top-level PROCESS inputs, event fragments,
     * checkpoint subjects, and selected executable bodies.
     *
     * <p>Managers with direct verified-provider access should override; the
     * runtime independently revalidates the returned direct BlueId and fails
     * closed if a recursively resolved representation was substituted.</p>
     *
     * @param reference pure exact reference or already exact content
     * @return immutable exact canonical provider content
     */
    default FrozenNode materializeVerifiedExactReference(
            FrozenNode reference) {
        return materializeVerifiedReference(reference);
    }

    /**
     * Materializes one pure exact type reference together with canonical
     * identity evidence issued by the same verified resolver invocation.
     *
     * <p>This is a first-class snapshot-manager operation because transparent
     * manager decorators and transient forks must preserve every semantic
     * capability of the manager they wrap. It must not be implemented by
     * hashing a resolved type body. Managers without access to verified type
     * metadata fail closed by default.</p>
     *
     * @param reference pure exact type reference
     * @return resolved type declaration and same-invocation identity evidence,
     *         or {@code null} when verified content is absent
     * @throws NullPointerException if {@code reference} is null
     * @throws IllegalArgumentException if {@code reference} is not a pure
     *         exact reference
     * @throws UnsupportedOperationException when verified type evidence is
     *         unavailable through this manager
     */
    default TypeEvidenceResolution materializeVerifiedTypeReference(
            FrozenNode reference) {
        FrozenNode checked = Objects.requireNonNull(reference, "reference");
        if (!checked.isReferenceOnly()) {
            throw new IllegalArgumentException(
                    "Verified type materialization requires a pure exact "
                            + "reference");
        }
        throw new UnsupportedOperationException(
                "This ProcessingSnapshotManager cannot materialize verified "
                        + "type-reference evidence");
    }

    /**
     * Opens a short-lived manager for one observable patch sequence. The
     * default preserves historical manager behavior; cache-aware managers can
     * retain intermediate resolution data locally until final publication.
     * Decorators around a cache-aware manager must override and delegate this
     * method if they need to preserve that manager's optimized cache scope.
     *
     * @return invocation-owned transient manager
     */
    default ProcessingSnapshotManager transientSequence() {
        return this;
    }

    /**
     * Returns an independent hand-off scope containing current transient evidence.
     *
     * @return independently owned transient manager
     */
    default ProcessingSnapshotManager forkTransientSequence() {
        return transientSequence();
    }

    /**
     * Prunes a reusable transient scope to entries reachable from current state.
     *
     * @param canonicalRoot current canonical root
     * @param resolvedRoot current resolved root
     */
    default void retainTransientState(FrozenNode canonicalRoot, FrozenNode resolvedRoot) {
        // Historical managers have no explicit transient cache to prune.
    }

    /** Releases a transient manager after its preview/sequence ownership ends. */
    default void releaseTransientState() {
        // Historical managers have no explicitly owned transient state.
    }

    /**
     * Reports whether this scope belongs to the current cache generation.
     *
     * @return {@code true} when transient evidence may still be reused
     */
    default boolean isTransientStateCurrent() {
        return true;
    }

    /**
     * Whether this manager accepts dependency-proven value-only snapshot
     * updates without invoking {@link #fromDocumentTransient(Node)}.
     *
     * <p>The default is deliberately conservative for custom managers.</p>
     *
     * @return whether generic incremental value resolution is supported
     */
    default boolean supportsIncrementalValueResolution() {
        return false;
    }

    /**
     * Tests incremental support for a dependency-proven request.
     *
     * @param request immutable incremental-resolution request
     * @return whether the manager can safely apply that request
     */
    default boolean supportsIncrementalValueResolution(
            IncrementalValueResolutionRequest request) {
        return supportsIncrementalValueResolution();
    }

    /**
     * Returns the conformance view that shares this sequence's transient
     * resolution scope. Cache-aware decorators should delegate this method
     * together with {@link #transientSequence()}.
     *
     * @param conformanceEngine base conformance engine, or {@code null}
     * @return transient conformance view, or {@code null}
     */
    default ConformanceEngine transientConformanceEngine(ConformanceEngine conformanceEngine) {
        return conformanceEngine != null ? conformanceEngine.transientView() : null;
    }

    /**
     * Applies one patch and resolves the resulting immutable snapshot.
     * Implementations must retain or re-establish the source provenance and
     * canonical type-identity evidence required by the resulting graph; an
     * evidence-less structural snapshot is not an equivalent result.
     *
     * @param snapshot immutable base snapshot
     * @param patch patch to apply
     * @return resulting immutable evidence-bearing snapshot
     */
    ResolvedSnapshot applyPatch(ResolvedSnapshot snapshot, JsonPatch patch);

    /**
     * Publishes or retains a completed snapshot in shared cache state.
     *
     * @param snapshot completed immutable snapshot
     * @return published snapshot
     */
    default ResolvedSnapshot cacheSnapshot(ResolvedSnapshot snapshot) {
        return snapshot;
    }
}
