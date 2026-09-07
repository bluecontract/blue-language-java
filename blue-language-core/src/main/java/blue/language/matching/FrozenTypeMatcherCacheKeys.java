package blue.language.matching;

import blue.language.snapshot.FrozenNode;

import java.util.Objects;

/** Cache keys owned by {@link FrozenTypeMatcher}. */
final class FrozenTypeMatcherCacheKeys {

    private FrozenTypeMatcherCacheKeys() {
    }

    static final class TypeKey implements MatchingPlanCache.Weighted {
        private final String canonicalBlueId;
        private final FrozenNode.ResolvedStructuralKey structure;

        private TypeKey(
                String canonicalBlueId,
                FrozenNode.ResolvedStructuralKey structure) {
            this.canonicalBlueId = canonicalBlueId;
            this.structure = structure;
        }

        static TypeKey canonical(String blueId) {
            return new TypeKey(
                    Objects.requireNonNull(blueId, "canonicalBlueId"),
                    null);
        }

        static TypeKey structural(
                FrozenNode.ResolvedStructuralKey structure) {
            return new TypeKey(
                    null,
                    Objects.requireNonNull(structure, "structure"));
        }

        @Override
        public long retainedWeightBytes() {
            return canonicalBlueId != null
                    ? 48L + 2L * canonicalBlueId.length()
                    : 128L;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof TypeKey)) {
                return false;
            }
            TypeKey that = (TypeKey) other;
            return Objects.equals(canonicalBlueId, that.canonicalBlueId)
                    && Objects.equals(structure, that.structure);
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hashCode(canonicalBlueId)
                    + Objects.hashCode(structure);
        }
    }

    static final class TypePairKey implements MatchingPlanCache.Weighted {
        private final TypeKey candidate;
        private final TypeKey target;

        TypePairKey(TypeKey candidate, TypeKey target) {
            this.candidate = Objects.requireNonNull(candidate, "candidate");
            this.target = Objects.requireNonNull(target, "target");
        }

        @Override
        public long retainedWeightBytes() {
            return candidate.retainedWeightBytes()
                    + target.retainedWeightBytes();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof TypePairKey)) {
                return false;
            }
            TypePairKey that = (TypePairKey) other;
            return candidate.equals(that.candidate)
                    && target.equals(that.target);
        }

        @Override
        public int hashCode() {
            return 31 * candidate.hashCode() + target.hashCode();
        }
    }

    static final class MatchKey implements MatchingPlanCache.Weighted {
        private final FrozenNode.ResolvedStructuralKey candidate;
        private final FrozenNode.ResolvedStructuralKey target;
        private final long retainedWeightBytes;

        MatchKey(
                FrozenNode.ResolvedStructuralKey candidate,
                FrozenNode.ResolvedStructuralKey target,
                long retainedWeightBytes) {
            this.candidate = candidate;
            this.target = target;
            this.retainedWeightBytes = retainedWeightBytes;
        }

        MatchKey withRetainedWeightBytes(long retainedWeightBytes) {
            return new MatchKey(candidate, target, retainedWeightBytes);
        }

        @Override
        public long retainedWeightBytes() {
            return retainedWeightBytes;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof MatchKey)) {
                return false;
            }
            MatchKey that = (MatchKey) other;
            return candidate.equals(that.candidate)
                    && target.equals(that.target);
        }

        @Override
        public int hashCode() {
            return 31 * candidate.hashCode() + target.hashCode();
        }
    }
}
