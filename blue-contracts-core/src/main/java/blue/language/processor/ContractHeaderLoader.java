package blue.language.processor;

import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.mapping.NodeToObjectConverter;
import blue.language.model.Node;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.Contract;
import blue.language.processor.model.EmbeddedCollectionEventChannel;
import blue.language.processor.model.EmbeddedNodeChannel;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.MarkerContract;
import blue.language.processor.model.ProcessEmbedded;
import blue.language.processor.model.TriggeredEventChannel;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.snapshot.FrozenNode;
import blue.language.model.Nodes;
import blue.language.model.wire.BlueLanguageConstants;
import blue.language.mapping.TypeClassResolver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Recognizes effective contract headers and assembles a structural bundle.
 *
 * <p>The loader sees immutable effective headers and exact selected Source
 * contributions. Executable body fields are removed before conversion and
 * retained separately by {@link ExecutableBodyLoader}.</p>
 */
final class ContractHeaderLoader {

    private static final Set<String> INVALID_CONTRACT_KEYS = new LinkedHashSet<>();

    static {
        INVALID_CONTRACT_KEYS.add(BlueLanguageConstants.OBJECT_TYPE);
        INVALID_CONTRACT_KEYS.add(BlueLanguageConstants.OBJECT_VALUE);
        INVALID_CONTRACT_KEYS.add(BlueLanguageConstants.OBJECT_ITEMS);
        INVALID_CONTRACT_KEYS.add(BlueLanguageConstants.OBJECT_SCHEMA);
        INVALID_CONTRACT_KEYS.add(ProcessorContractConstants.KEY_CONTRACTS);
        INVALID_CONTRACT_KEYS.add(
                BlueLanguageConstants.LEGACY_OBJECT_PROPERTIES);
        INVALID_CONTRACT_KEYS.add(
                BlueLanguageConstants.LEGACY_OBJECT_CONSTRAINTS);
    }

    private final ContractProcessorRegistry registry;
    private final NodeToObjectConverter converter;
    private final TypeClassResolver typeResolver;
    private final EffectiveContractResolver effectiveContracts;
    private final ContractContributionCollector contributions;
    private final ExecutableBodyLoader executableBodies;
    private final ContractSnapshotFactory snapshots;
    private final ProcessingSnapshotManager snapshotManager;
    private final boolean canonicalContractOrder;
    private GasSchedule gasSchedule = GasSchedule.contracts10();

    ContractHeaderLoader(
            ContractProcessorRegistry registry,
            NodeToObjectConverter converter,
            TypeClassResolver typeResolver,
            EffectiveContractResolver effectiveContracts,
            ContractContributionCollector contributions,
            ExecutableBodyLoader executableBodies,
            ContractSnapshotFactory snapshots,
            ProcessingSnapshotManager snapshotManager,
            boolean canonicalContractOrder) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.converter = Objects.requireNonNull(converter, "converter");
        this.typeResolver = Objects.requireNonNull(typeResolver, "typeResolver");
        this.effectiveContracts =
                Objects.requireNonNull(effectiveContracts, "effectiveContracts");
        this.contributions = Objects.requireNonNull(contributions, "contributions");
        this.executableBodies = Objects.requireNonNull(executableBodies, "executableBodies");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.snapshotManager = snapshotManager;
        this.canonicalContractOrder = canonicalContractOrder;
    }

    void gasSchedule(GasSchedule gasSchedule) {
        this.gasSchedule = Objects.requireNonNull(gasSchedule, "gasSchedule");
    }

    void preflightSelectedContractHeaders(FrozenNode selectedScopeNode) {
        FrozenNode contracts = effectiveContracts.property(
                selectedScopeNode, ProcessorContractConstants.KEY_CONTRACTS);
        if (contracts == null) {
            return;
        }
        if (contracts.isReferenceOnly()) {
            contracts = contributions.materializeVerifiedReference(contracts);
        }
        if (contracts.getProperties() == null) {
            if (contracts.isEmptyNode()) {
                return;
            }
            throw new MustUnderstandFailureException(
                    "Contracts must be an object map",
                    ProcessorErrorCategory.InvalidProcessingDocument);
        }
        for (Map.Entry<String, FrozenNode> entry : contracts.getProperties().entrySet()) {
            if (!EffectiveContractResolver.isDirectProcessorStateKey(entry.getKey())) {
                preflightDirectContractHeader(
                        entry.getKey(), entry.getValue(), null);
            }
        }
    }

    void preflightDirectContractHeader(
            String key,
            FrozenNode contractNode,
            CanonicalTypeIdentityLookup canonicalTypeIdentities) {
        validateContractKey(key);
        if (contractNode == null || contractNode.isReferenceOnly()) {
            return;
        }
        String typeBlueId = exactSourceTypeBlueId(
                contractNode,
                "Direct contract header '" + key + "'",
                canonicalTypeIdentities);
        if (typeBlueId == null) {
            return;
        }
        Class<?> contractClass = typeResolver.resolveClass(typeBlueId);
        if (contractClass == null || !Contract.class.isAssignableFrom(contractClass)) {
            throw new MustUnderstandFailureException(
                    "Unsupported contract type: " + typeBlueId,
                    ProcessorErrorCategory.UnsupportedRuntimeType);
        }
        validateReservedContractRole(key, contractClass);
    }

    private String exactSourceTypeBlueId(
            FrozenNode contractNode,
            String purpose,
            CanonicalTypeIdentityLookup canonicalTypeIdentities) {
        FrozenNode type = contractNode != null
                ? contractNode.getType()
                : null;
        if (type == null) {
            return null;
        }
        if (type.isReferenceOnly()) {
            return type.getReferenceBlueId();
        }
        if (canonicalTypeIdentities != null) {
            Optional<String> established = canonicalTypeIdentities
                    .findCanonicalTypeBlueId(type.toNode());
            if (established.isPresent()) {
                return established.get();
            }
        }
        return CanonicalIdentityEvidence.sourceTypeBlueId(
                type.toNode(),
                snapshotManager,
                purpose + " type identity");
    }

    ContractBundle load(
            Node selectedScopeNode,
            FrozenNode effectiveScopeNode,
            String scopePath,
            ContractRecognitionMeter recognitionMeter,
            String recognitionReason,
            CanonicalTypeIdentityLookup typeIdentities,
            ContractHeaderMappingEvidence mappingEvidence) {
        ContractBundle.Builder bundle = ContractBundle.builder(
                Objects.requireNonNull(typeIdentities, "typeIdentities"));
        Node exactSelectedScope =
                effectiveContracts.materializeSelectedContractsMap(selectedScopeNode);
        Node selectedContractMap =
                exactSelectedScope != null ? exactSelectedScope.getContracts() : null;
        if (selectedContractMap != null
                && selectedContractMap.getProperties() == null) {
            if (Nodes.isEmptyNode(selectedContractMap)) {
                selectedContractMap = null;
            } else {
                throw new MustUnderstandFailureException(
                        "Contracts must be an object map",
                        ProcessorErrorCategory.InvalidProcessingDocument);
            }
        }

        FrozenNode effectiveContractMap = effectiveContracts.property(
                effectiveScopeNode, ProcessorContractConstants.KEY_CONTRACTS);
        if (effectiveContractMap != null && effectiveContractMap.getProperties() != null) {
            for (String key : effectiveContractMap.getProperties().keySet()) {
                validateContractKey(key);
            }
        }
        Map<String, FrozenNode> contractNodes =
                effectiveContracts.effectiveApplicationContracts(
                        effectiveScopeNode,
                        typeIdentities);
        Map<String, String> typeBlueIds = new LinkedHashMap<>();
        for (Map.Entry<String, FrozenNode> entry : contractNodes.entrySet()) {
            String typeBlueId = effectiveContracts.typeBlueId(
                    entry.getValue(), typeIdentities);
            if (typeBlueId != null) {
                typeBlueIds.put(entry.getKey(), typeBlueId);
            }
        }

        List<String> recognitionKeys = new ArrayList<>(
                contractNodes.keySet());
        if (canonicalContractOrder) {
            recognitionKeys.sort(
                    ExternalOrderKey::compareTextCodePoints);
        }
        for (String key : recognitionKeys) {
            recognize(
                    bundle,
                    exactSelectedScope,
                    effectiveScopeNode,
                    scopePath,
                    key,
                    contractNodes.get(key),
                    typeBlueIds,
                    contractNodes,
                    recognitionMeter,
                    recognitionReason,
                    typeIdentities,
                    mappingEvidence);
        }
        ContractBundle result = bundle.build();
        validateEmbeddedCollectionChannels(
                result, scopePath, recognitionMeter);
        return result;
    }

    private void recognize(
            ContractBundle.Builder bundle,
            Node exactSelectedScope,
            FrozenNode effectiveScopeNode,
            String scopePath,
            String key,
            FrozenNode effectiveContract,
            Map<String, String> typeBlueIds,
            Map<String, FrozenNode> contractNodes,
            ContractRecognitionMeter recognitionMeter,
            String recognitionReason,
            CanonicalTypeIdentityLookup typeIdentities,
            ContractHeaderMappingEvidence mappingEvidence) {
        String typeBlueId = typeBlueIds.get(key);
        if (typeBlueId == null) {
            throw new MustUnderstandFailureException(
                    "Contract '" + key + "' must declare a type",
                    ProcessorErrorCategory.UnsupportedRuntimeType);
        }
        Class<?> contractClass = typeResolver.resolveClass(typeBlueId);
        if (contractClass == null || !Contract.class.isAssignableFrom(contractClass)) {
            throw new MustUnderstandFailureException(
                    "Unsupported contract type: " + typeBlueId,
                    ProcessorErrorCategory.UnsupportedRuntimeType);
        }
        validateReservedContractRole(key, contractClass);
        boolean handlerContract = HandlerContract.class.isAssignableFrom(contractClass);
        List<String> executableBodyFields = handlerContract
                ? registry.executableBodyFields(typeBlueId)
                : Collections.<String>emptyList();
        List<String> deferredFields = handlerContract
                ? executableBodies.deferredHandlerFields(executableBodyFields)
                : executableBodyFields;
        List<String> exactSourceFields = new ArrayList<>(deferredFields);
        for (String nodeField : registry.nodeValuedHeaderFields(typeBlueId)) {
            if (!exactSourceFields.contains(nodeField)) {
                exactSourceFields.add(nodeField);
            }
        }
        String eventField = EffectiveContractSnapshotConstants
                .DispatchField.EVENT;
        boolean managedEventChannel = TriggeredEventChannel.class
                .isAssignableFrom(contractClass)
                || EmbeddedNodeChannel.class.isAssignableFrom(contractClass)
                || EmbeddedCollectionEventChannel.class
                .isAssignableFrom(contractClass);
        if (managedEventChannel && !exactSourceFields.contains(eventField)) {
            exactSourceFields.add(eventField);
        }
        ContractContributionResolver.BindingResolution binding =
                contributions.collect(
                        exactSelectedScope,
                        effectiveScopeNode,
                        key,
                        true,
                        exactSourceFields);
        List<String> sourceContributions = binding.sourceContributions();
        if (recognitionMeter != null) {
            recognitionMeter.recognizeHeader(
                    scopePath,
                    key,
                    sourceContributions,
                    recognitionReason != null
                            ? recognitionReason
                            : "effective-contract-header");
        }
        Node executableContract = executableBodies.exactExecutableContract(
                effectiveContract,
                exactSourceFields,
                binding.exactExecutableBodies());
        FrozenNode exactExecutable = FrozenNode.fromResolvedNode(executableContract);
        Node conversionNode = deferredFields.isEmpty()
                ? executableContract
                : executableBodies.headerNode(executableContract, deferredFields);
        Contract contract = mappingEvidence.convertContract(
                key,
                conversionNode,
                deferredFields,
                Contract.class,
                false,
                converter,
                typeIdentities);
        if (contract == null) {
            return;
        }
        if (contract instanceof HandlerContract) {
            executableBodies.restoreEventMatcher(
                    (HandlerContract) contract,
                    binding.exactExecutableBodies().get(
                            EffectiveContractSnapshotConstants.DispatchField.EVENT));
        }
        contract.setKey(key);
        contract.setTypeBlueId(typeBlueId);

        FrozenNode normalizedExecutable = contract instanceof ChannelContract
                ? NormalizedRuntimeContribution.channel(
                        exactExecutable,
                        typeBlueId,
                        exactSourceFields,
                        typeIdentities,
                        snapshotManager)
                : exactExecutable;

        EffectiveContractSnapshot.Builder snapshot = snapshots.begin(
                scopePath,
                key,
                typeBlueId,
                contractOrder(contract),
                sourceContributions);
        snapshots.addHeaderFields(
                snapshot, normalizedExecutable, executableBodyFields);
        classify(
                bundle,
                snapshot,
                contract,
                effectiveContract,
                normalizedExecutable,
                executableBodyFields,
                binding,
                scopePath,
                key,
                typeBlueId,
                contractNodes,
                typeBlueIds,
                recognitionMeter,
                typeIdentities,
                mappingEvidence);
        bundle.addEffectiveContractSnapshot(snapshot.build());
    }

    private void classify(
            ContractBundle.Builder bundle,
            EffectiveContractSnapshot.Builder snapshot,
            Contract contract,
            FrozenNode effectiveContract,
            FrozenNode exactExecutable,
            List<String> executableBodyFields,
            ContractContributionResolver.BindingResolution binding,
            String scopePath,
            String key,
            String typeBlueId,
            Map<String, FrozenNode> contractNodes,
            Map<String, String> typeBlueIds,
            ContractRecognitionMeter recognitionMeter,
            CanonicalTypeIdentityLookup typeIdentities,
            ContractHeaderMappingEvidence mappingEvidence) {
        if (contract instanceof ChannelContract) {
            addChannel(
                    bundle,
                    snapshot,
                    key,
                    (ChannelContract) contract,
                    effectiveContract,
                    exactExecutable,
                    typeBlueId,
                    binding);
        } else if (contract instanceof HandlerContract) {
            addHandler(
                    bundle,
                    snapshot,
                    key,
                    (HandlerContract) contract,
                    exactExecutable,
                    executableBodyFields,
                    binding,
                    scopePath,
                    typeBlueId,
                    contractNodes,
                    typeBlueIds,
                    recognitionMeter,
                    typeIdentities,
                    mappingEvidence);
        } else if (contract instanceof ProcessEmbedded) {
            ProcessEmbedded embedded = (ProcessEmbedded) contract;
            bundle.setEmbedded(embedded, effectiveContract);
            snapshot.role(EffectiveContractSnapshotConstants.Role.PROCESS_EMBEDDED);
            addEmbeddedDeclarationDependency(
                    snapshot,
                    effectiveContract,
                    ProcessorContractConstants.KEY_PATHS);
            addEmbeddedDeclarationDependency(
                    snapshot,
                    effectiveContract,
                    ProcessorContractConstants.KEY_COLLECTION_PATHS);
        } else if (contract instanceof MarkerContract) {
            bundle.addMarker(key, (MarkerContract) contract, effectiveContract);
            snapshot.role(EffectiveContractSnapshotConstants.Role.MARKER);
        } else {
            snapshot.role(EffectiveContractSnapshotConstants.Role.EXECUTABLE_EXTENSION);
        }
    }

    private void addChannel(
            ContractBundle.Builder bundle,
            EffectiveContractSnapshot.Builder snapshot,
            String key,
            ChannelContract channel,
            FrozenNode effectiveContract,
            FrozenNode exactContract,
            String typeBlueId,
            ContractContributionResolver.BindingResolution binding) {
        if (!ProcessorManagedChannelTypes.contains(channel)
                && !registry.lookupChannel(channel).isPresent()) {
            throw new MustUnderstandFailureException(
                    "Unsupported contract type: " + typeBlueId,
                    ProcessorErrorCategory.UnsupportedRuntimeType);
        }
        bundle.addChannel(key, channel, exactContract);
        snapshot.role(
                        ProcessorManagedChannelTypes.contains(channel)
                                ? EffectiveContractSnapshotConstants.Role.PROCESSOR_CHANNEL
                                : EffectiveContractSnapshotConstants.Role.EXTERNAL_CHANNEL)
                .dispatchField(
                        EffectiveContractSnapshotConstants.DispatchField.ORDER,
                        channel.getOrder());
        if (channel instanceof EmbeddedNodeChannel) {
            EmbeddedNodeChannel embedded = (EmbeddedNodeChannel) channel;
            Node exactEvent = exactManagedEvent(
                    key, effectiveContract, binding);
            embedded.setEvent(exactEvent != null ? exactEvent.clone() : null);
            snapshot.dispatchField(
                    EffectiveContractSnapshotConstants.DispatchField.SOURCE_PATH,
                    embedded.getSourcePath());
            snapshots.addEventDispatch(snapshot, exactEvent);
        } else if (channel instanceof EmbeddedCollectionEventChannel) {
            EmbeddedCollectionEventChannel embedded =
                    (EmbeddedCollectionEventChannel) channel;
            Node exactEvent = exactManagedEvent(
                    key, effectiveContract, binding);
            embedded.setEvent(exactEvent != null ? exactEvent.clone() : null);
            snapshot.dispatchField(
                    EffectiveContractSnapshotConstants.DispatchField
                            .COLLECTION_PATH,
                    embedded.getCollectionPath());
            snapshot.dispatchField(
                    EffectiveContractSnapshotConstants.DispatchField
                            .INCLUDE_DESCENDANTS,
                    embedded.includesDescendants());
            snapshots.addEventDispatch(snapshot, exactEvent);
        } else if (channel instanceof TriggeredEventChannel) {
            TriggeredEventChannel triggered = (TriggeredEventChannel) channel;
            Node exactEvent = exactManagedEvent(
                    key, effectiveContract, binding);
            triggered.setEvent(exactEvent != null ? exactEvent.clone() : null);
            snapshots.addEventDispatch(snapshot, exactEvent);
        }
    }

    private void validateEmbeddedCollectionChannels(
            ContractBundle bundle,
            String scopePath,
            ContractRecognitionMeter recognitionMeter) {
        for (ContractBundle.ChannelBinding binding
                : bundle.channelsOfType(
                        EmbeddedCollectionEventChannel.class)) {
            EmbeddedCollectionEventChannelSupport.validateHeader(
                    (EmbeddedCollectionEventChannel) binding.contract(),
                    bundle.embeddedScopeDeclaration(),
                    scopePath,
                    gasSchedule,
                    recognitionMeter);
        }
    }

    private Node exactManagedEvent(
            String key,
            FrozenNode effectiveContract,
            ContractContributionResolver.BindingResolution binding) {
        String eventField = EffectiveContractSnapshotConstants
                .DispatchField.EVENT;
        Node exactEvent = binding.exactExecutableBodies().get(eventField);
        FrozenNode effectiveEvent = effectiveContract != null
                && effectiveContract.getProperties() != null
                ? effectiveContract.getProperties().get(eventField)
                : null;
        if (effectiveEvent != null
                && exactEvent == null
                && !hasOnlyEventDocumentation(effectiveEvent)) {
            throw new MustUnderstandFailureException(
                    "Cannot establish exact event Source for managed channel '"
                            + key + "'",
                    ProcessorErrorCategory.InvalidContractBinding);
        }
        return exactEvent;
    }

    /**
     * Distinguishes the inherited optional-field descriptor on the built-in
     * managed channel types from an effective event matcher.
     *
     * <p>Name and description are declaration documentation and are ignored
     * by matching. Any other modeled field is matcher content and therefore
     * still requires an exact Source contribution.</p>
     */
    private boolean hasOnlyEventDocumentation(FrozenNode event) {
        return event.getType() == null
                && event.getItemType() == null
                && event.getKeyType() == null
                && event.getValueType() == null
                && event.getValue() == null
                && !event.hasItems()
                && !event.hasProperties()
                && event.getContracts() == null
                && event.getReferenceBlueId() == null
                && event.getSchema() == null
                && event.getMergePolicy() == null
                && event.getPreviousBlueId() == null
                && event.getPosition() == null
                && event.getBlue() == null;
    }

    private void addHandler(
            ContractBundle.Builder bundle,
            EffectiveContractSnapshot.Builder snapshot,
            String key,
            HandlerContract handler,
            FrozenNode exactExecutable,
            List<String> executableBodyFields,
            ContractContributionResolver.BindingResolution binding,
            String scopePath,
            String typeBlueId,
            Map<String, FrozenNode> contractNodes,
            Map<String, String> typeBlueIds,
            ContractRecognitionMeter recognitionMeter,
            CanonicalTypeIdentityLookup typeIdentities,
            ContractHeaderMappingEvidence mappingEvidence) {
        Optional<HandlerProcessor<? extends HandlerContract>> processor =
                registry.lookupHandler(handler);
        if (!processor.isPresent()) {
            throw new MustUnderstandFailureException(
                    "Unsupported contract type: " + typeBlueId,
                    ProcessorErrorCategory.UnsupportedRuntimeType);
        }
        String channelKey = resolveHandlerChannel(
                scopePath,
                key,
                handler,
                processor.get(),
                contractNodes,
                typeBlueIds,
                recognitionMeter,
                typeIdentities,
                mappingEvidence);
        handler.setChannelKey(channelKey);
        if (hasRegisteredSameScopeChannel(
                channelKey,
                contractNodes,
                typeBlueIds,
                typeIdentities,
                mappingEvidence)) {
            bundle.addHandler(
                    key,
                    handler,
                    exactExecutable,
                    executableBodyFields,
                    typeIdentities);
        }
        snapshot.role(EffectiveContractSnapshotConstants.Role.HANDLER)
                .dispatchField(
                        EffectiveContractSnapshotConstants.DispatchField.ORDER,
                        handler.getOrder())
                .dispatchField(
                        EffectiveContractSnapshotConstants.DispatchField.CHANNEL,
                        channelKey);
        for (String field : executableBodyFields) {
            snapshot.executableBodyField(field);
            snapshots.addExecutableBody(
                    snapshot,
                    field,
                    scopePath,
                    key,
                    typeBlueId,
                    binding);
        }
    }

    private int contractOrder(Contract contract) {
        if (contract instanceof ChannelContract) {
            Integer order = ((ChannelContract) contract).getOrder();
            return order != null ? order : 0;
        }
        if (contract instanceof HandlerContract) {
            Integer order = ((HandlerContract) contract).getOrder();
            return order != null ? order : 0;
        }
        return 0;
    }

    private void validateContractKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new MustUnderstandFailureException(
                    "Invalid contract key: key must be non-empty",
                    ProcessorErrorCategory.InvalidRuntimePointer);
        }
        if (INVALID_CONTRACT_KEYS.contains(key)) {
            throw new MustUnderstandFailureException(
                    "Invalid contract key: reserved key '" + key + "'",
                    ProcessorErrorCategory.InvalidReservedRuntimeState);
        }
    }

    /** Enforces the Contracts 1.0 reserved location for Process Embedded. */
    private void validateReservedContractRole(
            String key,
            Class<?> contractClass) {
        boolean embeddedKey = ProcessorContractConstants.KEY_EMBEDDED
                .equals(key);
        boolean processEmbedded = ProcessEmbedded.class
                .isAssignableFrom(contractClass);
        if (embeddedKey == processEmbedded) {
            return;
        }
        throw new MustUnderstandFailureException(
                processEmbedded
                        ? "Process Embedded must use reserved contract key '"
                                + ProcessorContractConstants.KEY_EMBEDDED + "'"
                        : "Reserved contract key '"
                                + ProcessorContractConstants.KEY_EMBEDDED
                                + "' must contain Process Embedded",
                ProcessorErrorCategory.InvalidContractKey);
    }

    private void addEmbeddedDeclarationDependency(
            EffectiveContractSnapshot.Builder snapshot,
            FrozenNode effectiveContract,
            String field) {
        FrozenNode declaration = effectiveContracts.property(
                effectiveContract, field);
        if (declaration != null) {
            snapshot.deterministicDependency(declaration.blueId());
        }
    }

    @SuppressWarnings("unchecked")
    private String resolveHandlerChannel(
            String scopePath,
            String handlerKey,
            HandlerContract handler,
            HandlerProcessor<? extends HandlerContract> processor,
            Map<String, FrozenNode> contractNodes,
            Map<String, String> typeBlueIds,
            ContractRecognitionMeter recognitionMeter,
            CanonicalTypeIdentityLookup typeIdentities,
            ContractHeaderMappingEvidence mappingEvidence) {
        String channelKey = trimToNull(handler.getChannelKey());
        if (channelKey == null) {
            RuntimeWorkSession work = recognitionMeter != null
                    ? recognitionMeter.newRuntimeWorkSession()
                    : new RuntimeWorkSession(
                            new GasMeter(gasSchedule),
                            RuntimeWorkSession.Mode.ADMISSION);
            HandlerRegistrationContext context = new HandlerRegistrationContext(
                    scopePath,
                    handlerKey,
                    contractNodes,
                    typeBlueIds,
                    converter,
                    work,
                    typeIdentities,
                    mappingEvidence);
            HandlerProcessor<HandlerContract> typed =
                    (HandlerProcessor<HandlerContract>) processor;
            try {
                channelKey = trimToNull(typed.deriveChannel(handler, context));
                work.complete();
            } catch (ExecutionEvidenceUnavailableException unavailable) {
                work.suspend();
                throw unavailable;
            } catch (RuntimeException | Error failure) {
                work.failDeterministically();
                throw failure;
            } finally {
                work.close();
            }
        }
        if (channelKey == null) {
            throw new IllegalStateException(
                    "Handler "
                            + handlerKey
                            + " must declare channel or derive one from its processor");
        }
        return channelKey;
    }

    private boolean hasRegisteredSameScopeChannel(
            String channelKey,
            Map<String, FrozenNode> contractNodes,
            Map<String, String> typeBlueIds,
            CanonicalTypeIdentityLookup typeIdentities,
            ContractHeaderMappingEvidence mappingEvidence) {
        FrozenNode channelNode = contractNodes.get(channelKey);
        if (channelNode == null) {
            return false;
        }
        String channelTypeBlueId = typeBlueIds.get(channelKey);
        if (channelTypeBlueId == null) {
            return false;
        }
        Class<?> channelClass = typeResolver.resolveClass(channelTypeBlueId);
        if (channelClass == null
                || !ChannelContract.class.isAssignableFrom(channelClass)) {
            return false;
        }
        Contract converted = mappingEvidence.convertContract(
                channelKey,
                channelNode.toNode(),
                Collections.<String>emptyList(),
                Contract.class,
                false,
                converter,
                typeIdentities);
        if (!(converted instanceof ChannelContract)) {
            return false;
        }
        ChannelContract channel = (ChannelContract) converted;
        channel.setKey(channelKey);
        channel.setTypeBlueId(channelTypeBlueId);
        return ProcessorManagedChannelTypes.contains(channel)
                || registry.lookupChannel(channel).isPresent();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
