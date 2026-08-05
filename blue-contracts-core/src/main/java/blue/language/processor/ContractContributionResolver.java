package blue.language.processor;

import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.api.NodeProviderOutcome;
import blue.language.provider.NodeProvider;
import blue.language.provider.NodeProviderResult;
import blue.language.model.Node;
import blue.language.processor.util.PointerUtils;
import blue.language.snapshot.FrozenNode;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.identity.BlueIds;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reconstructs the actual ancestor-to-descendant Source contributions for one
 * effective contract without inventing an identity for the merged result.
 */
final class ContractContributionResolver {

    private final NodeProvider provider;
    private volatile GasSchedule gasSchedule;

    ContractContributionResolver(NodeProvider provider) {
        this(provider, GasSchedule.contracts10());
    }

    ContractContributionResolver(NodeProvider provider,
                                 GasSchedule gasSchedule) {
        this.provider = provider;
        this.gasSchedule = Objects.requireNonNull(gasSchedule, "gasSchedule");
    }

    void gasSchedule(GasSchedule gasSchedule) {
        this.gasSchedule = Objects.requireNonNull(gasSchedule, "gasSchedule");
    }

    /**
     * Materializes one exact contract contribution through the same verified
     * provider boundary used to reconstruct ordered Source identities.
     */
    FrozenNode materializeVerifiedReference(FrozenNode reference) {
        Objects.requireNonNull(reference, "reference");
        if (!reference.isReferenceOnly()) {
            return reference;
        }
        String blueId = reference.getReferenceBlueId();
        return FrozenNode.fromResolvedNode(
                materialize(reference.toNode(), blueId));
    }

    /**
     * Materializes provider-backed header values without opening any declared
     * executable-body field. Type references remain references and continue
     * through the ordinary type-contribution resolver.
     */
    FrozenNode materializeVerifiedHeader(
            FrozenNode contribution,
            Collection<String> executableBodyFields) {
        Objects.requireNonNull(contribution, "contribution");
        Set<String> deferred = executableBodyFields == null
                ? Collections.<String>emptySet()
                : new LinkedHashSet<>(executableBodyFields);
        Node exact = materializeHeaderNode(
                contribution.toNode(),
                deferred,
                new LinkedHashSet<String>());
        return FrozenNode.fromResolvedNode(exact);
    }

    List<String> resolve(Node selectedScope,
                         String contractKey,
                         boolean effectiveContractExists) {
        return resolve(
                selectedScope,
                null,
                contractKey,
                effectiveContractExists);
    }

    List<String> resolve(Node selectedScope,
                         FrozenNode effectiveScope,
                         String contractKey,
                         boolean effectiveContractExists) {
        return resolveBinding(
                selectedScope,
                effectiveScope,
                contractKey,
                effectiveContractExists,
                Collections.<String>emptyList())
                .sourceContributions();
    }

    BindingResolution resolveBinding(
            Node selectedScope,
            FrozenNode effectiveScope,
            String contractKey,
            boolean effectiveContractExists,
            Collection<String> executableBodyFields) {
        List<String> contributions = new ArrayList<>();
        Map<String, Node> exactExecutableBodies =
                new LinkedHashMap<>();
        Map<String, ExecutableBodySource> executableBodySources =
                new LinkedHashMap<>();
        Set<String> requestedExecutableBodies =
                executableBodyFields == null
                        ? Collections.<String>emptySet()
                        : new LinkedHashSet<>(
                                executableBodyFields);
        Set<String> activeTypes = new LinkedHashSet<>();
        Node selectedType =
                selectedScope != null ? selectedScope.getType() : null;
        if (selectedType == null && effectiveScope != null) {
            /*
             * A canonical fragment selected from a ResolvedSnapshot can omit
             * a type supplied contextually by its parent type. The completed
             * scope retains that verified type's requested BlueId. Recreate
             * only the pure reference and pass it through the ordinary
             * provider-verification path; never treat merged effective
             * contract content as Source-contribution evidence.
             */
            FrozenNode effectiveType = effectiveScope.getType();
            String inheritedTypeBlueId = effectiveType != null
                    ? effectiveType.getReferenceBlueId()
                    : null;
            if (inheritedTypeBlueId != null) {
                selectedType =
                        new Node().blueId(inheritedTypeBlueId);
            }
        }
        collectTypeContributions(
                selectedType,
                contractKey,
                contributions,
                requestedExecutableBodies,
                exactExecutableBodies,
                executableBodySources,
                activeTypes,
                0);
        Node contracts = selectedScope != null ? selectedScope.getContracts() : null;
        Node direct = null;
        if (contracts != null && contracts.getProperties() != null) {
            direct = contracts.getProperties().get(contractKey);
        }
        if (direct != null && contributesContent(direct)) {
            String contributionBlueId = exactIdentity(direct);
            contributions.add(contributionBlueId);
            overlayDeclaredExecutableBodies(
                    direct,
                    contributionBlueId,
                    requestedExecutableBodies,
                    exactExecutableBodies,
                    executableBodySources);
        }
        if (effectiveContractExists && contributions.isEmpty()) {
            throw new MustUnderstandFailureException(
                    "Cannot establish source contributions for effective contract '"
                            + contractKey + "'",
                    ProcessorErrorCategory.InvalidContractBinding);
        }
        return new BindingResolution(
                contributions,
                exactExecutableBodies,
                executableBodySources);
    }

    private void collectTypeContributions(Node typeReference,
                                          String contractKey,
                                          List<String> result,
                                          Set<String> executableBodyFields,
                                          Map<String, Node> exactExecutableBodies,
                                          Map<String, ExecutableBodySource>
                                                  executableBodySources,
                                          Set<String> activeTypes,
                                          int depth) {
        if (typeReference == null) {
            return;
        }
        long maxTypeEdges = gasSchedule.portableLimit(GasScheduleConstants.PortableLimit.TYPE_CHAIN_EDGES);
        if (depth >= maxTypeEdges) {
            throw new PortableLimitExceededException(
                    ProcessorErrorCategory.DirectNodeLimitExceeded,
                    GasScheduleConstants.PortableLimit.TYPE_CHAIN_EDGES,
                    depth + 1L,
                    maxTypeEdges);
        }
        String typeBlueId = referenceIdentity(typeReference);
        Node typeNode = materialize(typeReference, typeBlueId);
        String cycleKey = typeBlueId != null
                ? typeBlueId
                : exactIdentity(typeNode);
        if (!activeTypes.add(cycleKey)) {
            throw new MustUnderstandFailureException(
                    "Cyclic type contribution while resolving contract '"
                            + contractKey + "'",
                    ProcessorErrorCategory.InvalidContractBinding);
        }
        collectTypeContributions(
                typeNode.getType(),
                contractKey,
                result,
                executableBodyFields,
                exactExecutableBodies,
                executableBodySources,
                activeTypes,
                depth + 1);
        Node contracts = typeNode.getContracts();
        Node contribution = null;
        if (contracts != null && contracts.getProperties() != null) {
            contribution = contracts.getProperties().get(contractKey);
        }
        if (contribution != null && contributesContent(contribution)) {
            String contributionBlueId =
                    exactIdentity(contribution);
            result.add(contributionBlueId);
            overlayDeclaredExecutableBodies(
                    contribution,
                    contributionBlueId,
                    executableBodyFields,
                    exactExecutableBodies,
                    executableBodySources);
        }
        activeTypes.remove(cycleKey);
    }

    private void overlayDeclaredExecutableBodies(
            Node contribution,
            String contributionBlueId,
            Set<String> executableBodyFields,
            Map<String, Node> exactExecutableBodies,
            Map<String, ExecutableBodySource>
                    executableBodySources) {
        if (contribution == null
                || executableBodyFields.isEmpty()) {
            return;
        }
        Node exactContribution =
                contribution.isReferenceOnly()
                        ? materialize(
                                contribution,
                                referenceIdentity(
                                        contribution))
                        : contribution;
        Map<String, Node> properties =
                exactContribution.getProperties();
        if (properties == null) {
            return;
        }
        for (String field : executableBodyFields) {
            if (!properties.containsKey(field)) {
                continue;
            }
            Node body = properties.get(field);
            exactExecutableBodies.put(
                    field,
                    body != null ? body.clone() : new Node());
            executableBodySources.put(
                    field,
                    new ExecutableBodySource(
                            contributionBlueId,
                            PointerUtils.toPointer(
                                    Collections.singletonList(
                                            field)),
                            body != null
                                    && body.isReferenceOnly()));
        }
    }

    private Node materialize(Node reference, String blueId) {
        if (!reference.isReferenceOnly()) {
            return reference;
        }
        if (provider == null || blueId == null) {
            throw unavailable(blueId, null);
        }
        final NodeProviderResult providerResult;
        try {
            providerResult = provider.fetchResultByBlueId(blueId);
        } catch (ExecutionEvidenceUnavailableException exception) {
            throw exception;
        } catch (InvalidExecutionEvidenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            if (BlueLanguageErrorClassifier.classify(exception)
                    == BlueLanguageErrorCategory.ProviderUnavailable) {
                throw unavailable(blueId, exception.getMessage());
            }
            throw exception;
        }
        if (providerResult == null) {
            throw invalidEvidence(
                    blueId,
                    "Provider returned no typed result",
                    null);
        }
        if (providerResult.outcome() == NodeProviderOutcome.NOT_FOUND) {
            throw new MustUnderstandFailureException(
                    "Exact Source contribution was not found for " + blueId,
                    ProcessorErrorCategory.InvalidContractBinding);
        }
        if (providerResult.outcome() == NodeProviderOutcome.UNAVAILABLE) {
            throw unavailable(
                    blueId,
                    providerDiagnostic(providerResult));
        }
        if (providerResult.outcome()
                == NodeProviderOutcome.INVALID_EVIDENCE) {
            throw invalidEvidence(
                    blueId,
                    "Provider returned invalid exact Source contribution",
                    providerDiagnostic(providerResult));
        }
        List<Node> nodes = providerResult.nodes();
        if (nodes.size() != 1 || nodes.get(0) == null) {
            throw invalidEvidence(
                    blueId,
                    "Expected one verified Source contribution",
                    null);
        }
        Node node = nodes.get(0);
        Node canonicalContent = node.clone();
        if (canonicalContent.getBlueId() != null
                && !canonicalContent.isReferenceOnly()) {
            /*
             * Verified providers may retain the requested root identity as
             * materialization provenance. It is not content and must not be
             * fed back into strict BlueId input as a mixed reference.
             */
            canonicalContent.blueId(null);
        }
        if (BlueIds.hasCyclicMemberSeparator(blueId)) {
            /*
             * The processor's provider graph verifies MASTER#index through
             * the owning cyclic set. A member is not ordinary standalone
             * content and therefore must never be hashed independently.
             */
            return canonicalContent;
        }
        String calculated =
                DirectBlueIdCalculator.calculateBlueId(canonicalContent);
        if (!blueId.equals(calculated)) {
            throw invalidEvidence(
                    blueId,
                    "Source contribution BlueId mismatch",
                    null);
        }
        return canonicalContent;
    }

    private String providerDiagnostic(
            NodeProviderResult providerResult) {
        return providerResult.diagnostic().orElse(null);
    }

    private InvalidExecutionEvidenceException invalidEvidence(
            String blueId,
            String reason,
            String diagnostic) {
        String message = reason + " for "
                + (blueId != null ? blueId : "<unknown>");
        if (diagnostic != null && !diagnostic.isEmpty()) {
            message += ": " + diagnostic;
        }
        return new InvalidExecutionEvidenceException(
                message,
                ProcessorErrorCategory.InvalidContractBinding);
    }

    private Node materializeHeaderNode(
            Node authored,
            Set<String> deferredDirectFields,
            Set<String> activeReferences) {
        if (authored == null) {
            return null;
        }
        Node exact = authored;
        String activeBlueId = null;
        if (authored.isReferenceOnly()) {
            activeBlueId = referenceIdentity(authored);
            if (!activeReferences.add(activeBlueId)) {
                throw new MustUnderstandFailureException(
                        "Cyclic exact reference while materializing contract "
                                + "header " + activeBlueId,
                        ProcessorErrorCategory.InvalidContractBinding);
            }
            exact = materialize(authored, activeBlueId);
        }
        Node result = exact.clone();
        if (result.getContracts() != null) {
            result.contracts(materializeHeaderNode(
                    result.getContracts(),
                    Collections.<String>emptySet(),
                    activeReferences));
        }
        if (result.getProperties() != null) {
            for (Map.Entry<String, Node> entry
                    : result.getProperties().entrySet()) {
                if (!deferredDirectFields.contains(entry.getKey())) {
                    entry.setValue(materializeHeaderNode(
                            entry.getValue(),
                            Collections.<String>emptySet(),
                            activeReferences));
                }
            }
        }
        if (result.getItems() != null) {
            for (int index = 0; index < result.getItems().size(); index++) {
                result.getItems().set(
                        index,
                        materializeHeaderNode(
                                result.getItems().get(index),
                                Collections.<String>emptySet(),
                                activeReferences));
            }
        }
        if (activeBlueId != null) {
            activeReferences.remove(activeBlueId);
        }
        return result;
    }

    private ExecutionEvidenceUnavailableException unavailable(
            String blueId,
            String diagnostic) {
        String identity =
                blueId != null ? blueId : "<unknown>";
        String message =
                "Exact Source contribution is unavailable for "
                        + identity;
        if (diagnostic != null && !diagnostic.isEmpty()) {
            message += ": " + diagnostic;
        }
        return new ExecutionEvidenceUnavailableException(
                message,
                blueId != null
                        ? Collections.singletonList(
                                blueId)
                        : Collections.<String>emptyList());
    }

    private String referenceIdentity(Node node) {
        return node != null && node.getBlueId() != null
                ? node.getBlueId()
                : node != null ? DirectBlueIdCalculator.calculateBlueId(node) : null;
    }

    private String exactIdentity(Node node) {
        Objects.requireNonNull(node, "node");
        return node.getBlueId() != null
                ? node.getBlueId()
                : DirectBlueIdCalculator.calculateBlueId(node);
    }

    private boolean contributesContent(Node node) {
        if (node == null) {
            return false;
        }
        if (node.isReferenceOnly()) {
            return true;
        }
        return node.getType() != null
                || node.getValue() != null
                || node.getItems() != null
                || node.getContracts() != null
                || (node.getProperties() != null
                && !node.getProperties().isEmpty())
                || node.getName() != null
                || node.getDescription() != null;
    }

    /**
     * Immutable contribution-binding result for one effective contract.
     *
     * <p>It retains contribution identities in merge order together with
     * defensive copies of exact executable bodies and their authored source
     * provenance.</p>
     */
    static final class BindingResolution {
        private final List<String> sourceContributions;
        private final Map<String, Node> exactExecutableBodies;
        private final Map<String, ExecutableBodySource>
                executableBodySources;

        private BindingResolution(
                List<String> sourceContributions,
                Map<String, Node> exactExecutableBodies,
                Map<String, ExecutableBodySource>
                        executableBodySources) {
            this.sourceContributions =
                    Collections.unmodifiableList(
                            new ArrayList<>(
                                    sourceContributions));
            Map<String, Node> exactBodies =
                    new LinkedHashMap<>();
            for (Map.Entry<String, Node> entry
                    : exactExecutableBodies.entrySet()) {
                exactBodies.put(
                        entry.getKey(),
                        entry.getValue() != null
                                ? entry.getValue().clone()
                                : new Node());
            }
            this.exactExecutableBodies =
                    Collections.unmodifiableMap(
                            exactBodies);
            this.executableBodySources =
                    Collections.unmodifiableMap(
                            new LinkedHashMap<>(
                                    executableBodySources));
        }

        List<String> sourceContributions() {
            return sourceContributions;
        }

        Map<String, Node> exactExecutableBodies() {
            return exactExecutableBodies;
        }

        Map<String, ExecutableBodySource>
        executableBodySources() {
            return executableBodySources;
        }
    }

    /**
     * Provenance of one executable body selected from an owning
     * contribution.
     */
    static final class ExecutableBodySource {
        private final String owningContributionBlueId;
        private final String sourcePointer;
        private final boolean pureReference;

        private ExecutableBodySource(
                String owningContributionBlueId,
                String sourcePointer,
                boolean pureReference) {
            this.owningContributionBlueId =
                    Objects.requireNonNull(
                            owningContributionBlueId,
                            "owningContributionBlueId");
            this.sourcePointer =
                    Objects.requireNonNull(
                            sourcePointer,
                            "sourcePointer");
            this.pureReference = pureReference;
        }

        String owningContributionBlueId() {
            return owningContributionBlueId;
        }

        String sourcePointer() {
            return sourcePointer;
        }

        boolean pureReference() {
            return pureReference;
        }
    }
}
