package blue.language.snapshot;

/** Immutable canonical/resolved evidence pair retained under one BlueId. */
final class VerifiedReferenceEntry {
    final FrozenNode canonicalContent;
    final FrozenNode fullyResolvedContent;

    VerifiedReferenceEntry(
            FrozenNode canonicalContent,
            FrozenNode fullyResolvedContent) {
        if (canonicalContent == null) {
            throw new IllegalArgumentException(
                    "canonicalContent must not be null");
        }
        this.canonicalContent = canonicalContent;
        this.fullyResolvedContent = fullyResolvedContent;
    }
}
