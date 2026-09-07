package blue.language.processor;

import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Immutable selected/effective scope pair and the resolver evidence that
 * produced its effective node.
 *
 * <p>A scope node must never escape its resolver invocation without this
 * lookup. In particular, a projected, deferred, or freshly materialized scope
 * cannot borrow the current document snapshot's lookup: structurally equal
 * completed types are validated by the lookup that actually resolved them.</p>
 */
final class ResolvedScopeView {

    private final FrozenNode selected;
    private final FrozenNode resolved;
    private final CanonicalTypeIdentityLookup canonicalTypeIdentities;

    ResolvedScopeView(
            FrozenNode selected,
            FrozenNode resolved,
            CanonicalTypeIdentityLookup canonicalTypeIdentities) {
        this.selected = selected;
        this.resolved = resolved;
        this.canonicalTypeIdentities = Objects.requireNonNull(
                canonicalTypeIdentities, "canonicalTypeIdentities");
    }

    FrozenNode selected() {
        return selected;
    }

    FrozenNode resolved() {
        return resolved;
    }

    CanonicalTypeIdentityLookup canonicalTypeIdentities() {
        return canonicalTypeIdentities;
    }

    ResolvedScopeView withSelected(FrozenNode replacement) {
        return new ResolvedScopeView(
                replacement, resolved, canonicalTypeIdentities);
    }

    /**
     * Rebinds a graph assembled from independently resolved fragments.
     * Coverage and conflicting identities are checked against the exact
     * resulting graph before the view is exposed.
     */
    ResolvedScopeView withRecombinedResolved(
            FrozenNode replacement,
            CanonicalTypeIdentityLookup... additionalEvidence) {
        FrozenNode checked = Objects.requireNonNull(
                replacement, "resolved");
        List<CanonicalTypeIdentityLookup> evidence = new ArrayList<>();
        evidence.add(canonicalTypeIdentities);
        if (additionalEvidence != null) {
            evidence.addAll(Arrays.asList(additionalEvidence));
        }
        CanonicalTypeIdentityLookup established =
                CanonicalTypeIdentityEvidenceUnion
                        .establishForResolvedGraph(
                                checked.toNode(), evidence);
        return new ResolvedScopeView(selected, checked, established);
    }
}
