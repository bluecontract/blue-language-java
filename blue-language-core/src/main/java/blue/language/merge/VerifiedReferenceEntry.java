package blue.language.merge;

import blue.language.snapshot.FrozenNode;

import java.util.Objects;

/** Immutable canonical/resolved evidence pair retained under one BlueId. */
final class VerifiedReferenceEntry {
    final FrozenNode canonicalContent;
    final FrozenNode fullyResolvedContent;
    final CanonicalTypeIdentityIndex.EvidenceSnapshot
            canonicalTypeIdentityEvidence;

    VerifiedReferenceEntry(
            FrozenNode canonicalContent,
            FrozenNode fullyResolvedContent) {
        this(canonicalContent,
                fullyResolvedContent,
                CanonicalTypeIdentityIndex.EvidenceSnapshot
                        .incompleteEmpty());
    }

    VerifiedReferenceEntry(
            FrozenNode canonicalContent,
            FrozenNode fullyResolvedContent,
            CanonicalTypeIdentityIndex.EvidenceSnapshot
                    canonicalTypeIdentityEvidence) {
        if (canonicalContent == null) {
            throw new IllegalArgumentException(
                    "canonicalContent must not be null");
        }
        this.canonicalContent = canonicalContent;
        this.fullyResolvedContent = fullyResolvedContent;
        this.canonicalTypeIdentityEvidence = Objects.requireNonNull(
                canonicalTypeIdentityEvidence,
                "canonicalTypeIdentityEvidence");
    }
}
