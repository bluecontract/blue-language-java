package blue.language.processor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Run-local meter for effective contract recognition.
 *
 * <p>The bundle cache is deliberately outside this object. Every invocation
 * performs the same logical reads, while the exact effective-header charge is
 * deduplicated by {@code (scope, key, ordered contribution identities)}.</p>
 */
final class ContractRecognitionMeter {

    private final GasMeter gas;
    private final Set<HeaderIdentity> recognizedHeaders =
            new LinkedHashSet<>();
    private final List<PendingHeader> pendingHeaders =
            new ArrayList<>();
    private boolean canonicalClassificationBatch;

    ContractRecognitionMeter(GasMeter gas) {
        this.gas = Objects.requireNonNull(gas, "gas");
    }

    RuntimeWorkSession newRuntimeWorkSession() {
        return new RuntimeWorkSession(
                gas,
                RuntimeWorkSession.Mode.PROCESSING);
    }

    void recognizeHeader(String scopePath,
                         String contractKey,
                         List<String> orderedContributionBlueIds,
                         String reason) {
        HeaderIdentity identity = new HeaderIdentity(
                scopePath,
                contractKey,
                orderedContributionBlueIds);
        if (recognizedHeaders.contains(identity)) {
            return;
        }
        if (canonicalClassificationBatch) {
            for (PendingHeader pending : pendingHeaders) {
                if (pending.identity.equals(identity)) {
                    return;
                }
            }
            pendingHeaders.add(
                    new PendingHeader(identity, reason));
            return;
        }
        /*
         * Mutate the deduplication set only after the charge is admitted. A gas
         * failure therefore leaves the failed charge and its logical header
         * absent from the canonical prefix.
         */
        gas.chargeContractHeaderRecognized(
                scopePath,
                contractKey,
                reason);
        recognizedHeaders.add(identity);
    }

    void beginCanonicalClassificationBatch() {
        if (canonicalClassificationBatch) {
            throw new IllegalStateException(
                    "Contract-recognition batch is already active");
        }
        pendingHeaders.clear();
        canonicalClassificationBatch = true;
    }

    void flushCanonicalClassificationBatch() {
        if (!canonicalClassificationBatch) {
            throw new IllegalStateException(
                    "No contract-recognition batch is active");
        }
        if (pendingHeaders.size() == 1) {
            PendingHeader pending =
                    pendingHeaders.get(0);
            gas.chargeContractHeaderRecognized(
                    pending.identity.scopePath,
                    pending.identity.contractKey,
                    pending.reason);
        } else if (!pendingHeaders.isEmpty()) {
            gas.chargeContractHeadersRecognized(
                    pendingHeaders.size(),
                    "structural-and-channel-headers");
        }
        for (PendingHeader pending : pendingHeaders) {
            recognizedHeaders.add(
                    pending.identity);
        }
        pendingHeaders.clear();
        canonicalClassificationBatch = false;
    }

    void cancelCanonicalClassificationBatch() {
        pendingHeaders.clear();
        canonicalClassificationBatch = false;
    }

    private static final class HeaderIdentity {
        private final String scopePath;
        private final String contractKey;
        private final List<String> orderedContributionBlueIds;

        private HeaderIdentity(String scopePath,
                               String contractKey,
                               List<String> orderedContributionBlueIds) {
            this.scopePath = Objects.requireNonNull(
                    scopePath, "scopePath");
            this.contractKey = Objects.requireNonNull(
                    contractKey, "contractKey");
            this.orderedContributionBlueIds =
                    Collections.unmodifiableList(
                            new ArrayList<>(
                                    Objects.requireNonNull(
                                            orderedContributionBlueIds,
                                            "orderedContributionBlueIds")));
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof HeaderIdentity)) {
                return false;
            }
            HeaderIdentity identity = (HeaderIdentity) other;
            return scopePath.equals(identity.scopePath)
                    && contractKey.equals(identity.contractKey)
                    && orderedContributionBlueIds.equals(
                    identity.orderedContributionBlueIds);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    scopePath,
                    contractKey,
                    orderedContributionBlueIds);
        }
    }

    private static final class PendingHeader {
        private final HeaderIdentity identity;
        private final String reason;

        private PendingHeader(
                HeaderIdentity identity,
                String reason) {
            this.identity = Objects.requireNonNull(
                    identity, "identity");
            this.reason = reason;
        }
    }
}
