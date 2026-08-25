package blue.language.processor.closure;

import blue.language.model.wire.BlueLanguageConstants;

import java.util.Objects;

/** Exact Blue-node resource required before a closure attempt can continue. */
public final class ExactNodeDemand extends ClosureResourceDemand {

    private static final ClosureIdentityService IDENTITIES =
            ClosureIdentityService.INSTANCE;

    static final DocumentId PROVIDER_SOURCE_DOCUMENT_ID =
            new DocumentId("blue-contracts/exact-node-provider");
    static final String PROVIDER_LOGICAL_PATH = "/";

    private final String blueId;
    private final String logicalPath;

    /**
     * Creates and verifies one exact-node demand.
     *
     * @param demandIdentity asserted canonical demand identity
     * @param blueId exact unavailable BlueId
     * @param sourceDocumentId source managed-document lineage
     * @param logicalPath absolute logical source path
     */
    public ExactNodeDemand(
            String demandIdentity,
            String blueId,
            DocumentId sourceDocumentId,
            String logicalPath) {
        super(Kind.EXACT_NODE,
                demandIdentity,
                sourceDocumentId,
                logicalPath,
                blueId,
                0L);
        this.blueId = ClosureValueSupport.requireBlueId(
                blueId, BlueLanguageConstants.OBJECT_BLUE_ID);
        this.logicalPath = ClosureValueSupport.requireAbsolutePointer(
                logicalPath, "logicalPath");
        String computed = IDENTITIES.closureResourceDemandIdentity(
                Kind.EXACT_NODE,
                null,
                null,
                null,
                Objects.requireNonNull(sourceDocumentId,
                        "sourceDocumentId"),
                this.logicalPath,
                null,
                this.blueId,
                null);
        if (!demandIdentity().equals(computed)) {
            throw new IllegalArgumentException(
                    "demandIdentity does not identify this exact-node demand");
        }
    }

    /**
     * Derives the canonical identity and creates one exact-node demand.
     *
     * @param blueId exact unavailable BlueId
     * @param sourceDocumentId source managed-document lineage
     * @param logicalPath absolute logical source path
     * @return verified immutable demand
     */
    public static ExactNodeDemand derived(
            String blueId,
            DocumentId sourceDocumentId,
            String logicalPath) {
        String selectedBlueId = ClosureValueSupport.requireBlueId(
                blueId, BlueLanguageConstants.OBJECT_BLUE_ID);
        DocumentId source = Objects.requireNonNull(
                sourceDocumentId, "sourceDocumentId");
        String path = ClosureValueSupport.requireAbsolutePointer(
                logicalPath, "logicalPath");
        String identity = IDENTITIES.closureResourceDemandIdentity(
                Kind.EXACT_NODE,
                null,
                null,
                null,
                source,
                path,
                null,
                selectedBlueId,
                null);
        return new ExactNodeDemand(
                identity, selectedBlueId, source, path);
    }

    static ExactNodeDemand providerResource(String blueId) {
        return derived(blueId,
                PROVIDER_SOURCE_DOCUMENT_ID,
                PROVIDER_LOGICAL_PATH);
    }

    /**
     * Returns the exact unavailable BlueId.
     *
     * @return exact unavailable BlueId
     */
    public String blueId() {
        return blueId;
    }

    /**
     * Returns the absolute logical source path.
     *
     * @return absolute logical source path
     */
    public String logicalPath() {
        return logicalPath;
    }
}
