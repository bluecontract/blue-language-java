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

    ContractRecognitionMeter(GasMeter gas) {
        this.gas = Objects.requireNonNull(gas, "gas");
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

    void embeddedPathEntryRead(String scopePath,
                               String contractKey,
                               int index) {
        gas.chargeEmbeddedPathEntryRead(
                scopePath,
                embeddedPath(scopePath, contractKey, index));
    }

    void embeddedPathSegmentsValidated(String scopePath,
                                       String contractKey,
                                       int index,
                                       long quantity) {
        gas.chargeEmbeddedPathSegmentsValidated(
                scopePath,
                embeddedPath(scopePath, contractKey, index),
                quantity);
    }

    private String embeddedPath(String scopePath,
                                String contractKey,
                                int index) {
        String normalizedScope = ProcessorEngine.normalizeScope(scopePath);
        String prefix = "/".equals(normalizedScope)
                ? ""
                : normalizedScope;
        return prefix + "/contracts/"
                + blue.language.utils.JsonPointer.escape(contractKey)
                + "/paths/" + index;
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
}
