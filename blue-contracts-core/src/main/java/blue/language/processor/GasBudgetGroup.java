package blue.language.processor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Live shared/local cap accounting; original meters retain their own traces and observers. */
final class GasBudgetGroup {
    private GasBudgetGroup parent = this;
    int members = 1;
    long admitted;
    long reserved;
    final Map<String, Long> admittedLocal = new LinkedHashMap<>();
    final Map<String, Long> reservedLocal = new LinkedHashMap<>();

    GasBudgetGroup root() {
        if (parent != this) {
            parent = parent.root();
        }
        return parent;
    }

    static GasMeter.MultiGroupJoinResult join(GasMeter initiating, Collection<GasMeter> others) {
        Objects.requireNonNull(others, "others");
        if (initiating.proofOnlyBudget()) {
            throw new IllegalStateException("Proof-only meters cannot join semantic gas groups");
        }
        long gasLimit = initiating.gasLimit();
        Map<String, Long> localGasLimits = initiating.localGasLimits();
        List<GasBudgetGroup> roots = new ArrayList<>();
        List<GasMeter.GroupContribution> contributions = new ArrayList<>();
        Set<GasBudgetGroup> seen = Collections.newSetFromMap(new IdentityHashMap<GasBudgetGroup, Boolean>());
        GasBudgetGroup own = initiating.budgetGroup().root();
        roots.add(own);
        seen.add(own);
        contributions.add(new GasMeter.GroupContribution(0, own));
        int input = 0;
        for (GasMeter other : others) {
            input++;
            Objects.requireNonNull(other, "group meter");
            if (other.proofOnlyBudget()) {
                throw new IllegalStateException("Proof-only meters cannot join semantic gas groups");
            }
            if (gasLimit != other.gasLimit()
                    || !initiating.schedule().packageIdentity().equals(other.schedule().packageIdentity())
                    || !localGasLimits.equals(other.localGasLimits())) {
                throw new IllegalArgumentException("Gas groups require the same exact execution policy");
            }
            GasBudgetGroup root = other.budgetGroup().root();
            if (seen.add(root)) {
                roots.add(root);
                contributions.add(new GasMeter.GroupContribution(input, root));
            }
        }
        if (roots.size() == 1) {
            return new GasMeter.MultiGroupJoinResult(GasMeter.GroupJoinResult.Status.ALREADY_JOINED,
                    contributions, gasLimit, null, 0L);
        }
        java.math.BigInteger proposed = java.math.BigInteger.ZERO;
        for (GasBudgetGroup root : roots) {
            proposed = proposed.add(java.math.BigInteger.valueOf(root.admitted + root.reserved));
        }
        if (proposed.compareTo(java.math.BigInteger.valueOf(gasLimit)) > 0) {
            return new GasMeter.MultiGroupJoinResult(GasMeter.GroupJoinResult.Status.SHARED_LIMIT_EXCEEDED,
                    contributions, gasLimit, null, 0L);
        }
        TreeMap<String, Long> proposedLocal = new TreeMap<>();
        for (GasBudgetGroup root : roots) {
            // Shared admission already bounds this sum, including every local reservation.
            mergeLocalGas(proposedLocal, root.admittedLocal);
            mergeLocalGas(proposedLocal, root.reservedLocal);
        }
        for (Map.Entry<String, Long> entry : proposedLocal.entrySet()) {
            long limit = localGasLimits.get(entry.getKey());
            if (entry.getValue() > limit) {
                return new GasMeter.MultiGroupJoinResult(GasMeter.GroupJoinResult.Status.LOCAL_LIMIT_EXCEEDED,
                        contributions, gasLimit, entry.getKey(), limit);
            }
        }
        GasBudgetGroup largest = own;
        for (GasBudgetGroup root : roots) {
            if (root.members > largest.members) largest = root;
        }
        for (GasBudgetGroup root : roots) {
            if (root == largest) continue;
            largest.admitted += root.admitted;
            largest.reserved += root.reserved;
            mergeLocalGas(largest.admittedLocal, root.admittedLocal);
            mergeLocalGas(largest.reservedLocal, root.reservedLocal);
            largest.members += root.members;
            root.parent = largest;
        }
        return new GasMeter.MultiGroupJoinResult(GasMeter.GroupJoinResult.Status.JOINED,
                contributions, gasLimit, null, 0L);
    }

    void rejectIfCapExceeded(long gasLimit, Map<String, Long> localGasLimits,
                             String namespace, String counter, long quantity, long weight,
                             long subtotal, GasChargeContext context) {
        GasBudgetGroup group = root();
        long sharedRemaining = gasLimit - group.admitted - group.reserved;
        LocalAllowance local = localAllowance(localGasLimits, context);
        long localRemaining = local != null ? local.remaining : Long.MAX_VALUE;
        if (subtotal <= sharedRemaining && subtotal <= localRemaining) return;
        if (local != null && localRemaining < sharedRemaining) {
            throw new GasLimitExceededException(namespace, counter, quantity, weight,
                    local.admitted, local.limit, GasLimitExceededException.ApplicableCapKind.LOCAL,
                    local.documentId, localRemaining, context);
        }
        long sharedAdmitted = group.admitted + group.reserved;
        throw new GasLimitExceededException(namespace, counter, quantity, weight,
                sharedAdmitted, gasLimit, GasLimitExceededException.ApplicableCapKind.SHARED,
                null, sharedRemaining, context);
    }

    private LocalAllowance localAllowance(Map<String, Long> localGasLimits, GasChargeContext context) {
        String documentId = context != null ? context.documentId() : null;
        if (documentId == null) return null;
        Long limit = localGasLimits.get(documentId);
        if (limit == null) return null;
        GasBudgetGroup group = root();
        long localAdmitted = localGas(group.admittedLocal, documentId) + localGas(group.reservedLocal, documentId);
        return new LocalAllowance(documentId, limit.longValue(), localAdmitted, limit.longValue() - localAdmitted);
    }

    static void addLocalGas(Map<String, Long> localGasLimits, Map<String, Long> gasByDocument,
                            GasChargeContext context, long subtotal) {
        LocalAllowance local = localAllowanceWithoutReservations(localGasLimits, context, gasByDocument);
        if (local == null) return;
        gasByDocument.put(local.documentId, Long.valueOf(local.admitted + subtotal));
    }

    static void removeLocalGas(Map<String, Long> localGasLimits, Map<String, Long> gasByDocument,
                               GasChargeContext context, long subtotal) {
        String documentId = context != null ? context.documentId() : null;
        if (documentId == null || !localGasLimits.containsKey(documentId)) return;
        long current = localGas(gasByDocument, documentId);
        if (subtotal < 0L || subtotal > current) {
            throw new IllegalStateException("Document-local runtime gas reservation mismatch");
        }
        long updated = current - subtotal;
        if (updated == 0L) gasByDocument.remove(documentId);
        else gasByDocument.put(documentId, Long.valueOf(updated));
    }

    private static LocalAllowance localAllowanceWithoutReservations(Map<String, Long> localGasLimits,
            GasChargeContext context, Map<String, Long> gasByDocument) {
        String documentId = context != null ? context.documentId() : null;
        if (documentId == null) return null;
        Long limit = localGasLimits.get(documentId);
        if (limit == null) return null;
        long localAdmitted = localGas(gasByDocument, documentId);
        return new LocalAllowance(documentId, limit.longValue(), localAdmitted, limit.longValue() - localAdmitted);
    }

    private static void mergeLocalGas(Map<String, Long> target, Map<String, Long> source) {
        for (Map.Entry<String, Long> entry : source.entrySet()) {
            target.put(entry.getKey(), localGas(target, entry.getKey()) + entry.getValue());
        }
    }

    static long localGas(Map<String, Long> gasByDocument, String documentId) {
        Long value = gasByDocument.get(documentId);
        return value != null ? value.longValue() : 0L;
    }

    private static final class LocalAllowance {
        private final String documentId;
        private final long limit;
        private final long admitted;
        private final long remaining;

        private LocalAllowance(String documentId, long limit, long admitted, long remaining) {
            this.documentId = documentId;
            this.limit = limit;
            this.admitted = admitted;
            this.remaining = remaining;
        }
    }
}
