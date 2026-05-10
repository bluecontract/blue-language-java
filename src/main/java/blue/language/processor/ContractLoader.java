package blue.language.processor;

import blue.language.mapping.NodeToObjectConverter;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.Contract;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.MarkerContract;
import blue.language.processor.model.ProcessEmbedded;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.TypeClassResolver;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;

/**
 * Parses contracts under a scope and produces a {@link ContractBundle}.
 */
final class ContractLoader {

    private final ContractProcessorRegistry registry;
    private final NodeToObjectConverter converter;
    private final TypeClassResolver typeResolver;

    ContractLoader(ContractProcessorRegistry registry,
                   NodeToObjectConverter converter,
                   TypeClassResolver typeResolver) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.converter = Objects.requireNonNull(converter, "converter");
        this.typeResolver = Objects.requireNonNull(typeResolver, "typeResolver");
    }

    ContractBundle load(ResolvedSnapshot snapshot, String scopePath) {
        Objects.requireNonNull(snapshot, "snapshot");
        return load(snapshot.resolvedAt(scopePath), scopePath);
    }

    ContractBundle load(FrozenNode scopeNode, String scopePath) {
        ContractBundle.Builder builder = ContractBundle.builder();
        if (scopeNode == null) {
            return builder.build();
        }
        Map<String, FrozenNode> properties = scopeNode.getProperties();
        if (properties == null) {
            return builder.build();
        }
        FrozenNode contractsNode = properties.get("contracts");
        if (contractsNode == null || contractsNode.getProperties() == null) {
            return builder.build();
        }

        Map<String, FrozenNode> contractNodes = new LinkedHashMap<>(contractsNode.getProperties());
        Map<String, String> contractTypeBlueIds = new LinkedHashMap<>();
        for (Map.Entry<String, FrozenNode> entry : contractNodes.entrySet()) {
            String typeBlueId = typeBlueId(entry.getValue());
            if (typeBlueId != null) {
                contractTypeBlueIds.put(entry.getKey(), typeBlueId);
            }
        }

        for (Map.Entry<String, FrozenNode> entry : contractNodes.entrySet()) {
            String key = entry.getKey();
            String typeBlueId = contractTypeBlueIds.get(key);
            if (typeBlueId == null) {
                continue;
            }
            Class<?> contractClass = typeResolver.resolveClass(typeBlueId);
            if (contractClass == null || !Contract.class.isAssignableFrom(contractClass)) {
                throw new MustUnderstandFailureException("Unsupported contract type: " + typeBlueId);
            }
            Contract contract = converter.convertWithType(entry.getValue().toNode(), Contract.class, false);
            if (contract == null) {
                continue;
            }
            contract.setKey(key);
            contract.setTypeBlueId(typeBlueId);
            if (contract instanceof ChannelContract) {
                ChannelContract channel = (ChannelContract) contract;
                if (!ProcessorContractConstants.isProcessorManagedChannel(channel)
                        && !registry.lookupChannel(channel).isPresent()) {
                    throw new MustUnderstandFailureException(
                            "Unsupported contract type: " + typeBlueId);
                }
                builder.addChannel(key, channel);
            } else if (contract instanceof HandlerContract) {
                HandlerContract handler = (HandlerContract) contract;
                Optional<HandlerProcessor<? extends HandlerContract>> processor = registry.lookupHandler(handler);
                if (!processor.isPresent()) {
                    throw new MustUnderstandFailureException(
                            "Unsupported contract type: " + typeBlueId);
                }
                String channelKey = resolveHandlerChannel(scopePath,
                        key,
                        handler,
                        processor.get(),
                        contractNodes,
                        contractTypeBlueIds);
                handler.setChannelKey(channelKey);
                requireRegisteredChannel(key, channelKey, contractNodes, contractTypeBlueIds);
                builder.addHandler(key, handler);
            } else if (contract instanceof ProcessEmbedded) {
                builder.setEmbedded((ProcessEmbedded) contract);
            } else if (contract instanceof MarkerContract) {
                builder.addMarker(key, (MarkerContract) contract);
            }
        }

        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private String resolveHandlerChannel(String scopePath,
                                         String handlerKey,
                                         HandlerContract handler,
                                         HandlerProcessor<? extends HandlerContract> processor,
                                         Map<String, FrozenNode> contractNodes,
                                         Map<String, String> contractTypeBlueIds) {
        String channelKey = trimToNull(handler.getChannelKey());
        if (channelKey == null) {
            HandlerRegistrationContext context = new HandlerRegistrationContext(scopePath,
                    handlerKey,
                    contractNodes,
                    contractTypeBlueIds,
                    converter);
            HandlerProcessor<HandlerContract> typed = (HandlerProcessor<HandlerContract>) processor;
            channelKey = trimToNull(typed.deriveChannel(handler, context));
        }
        if (channelKey == null) {
            throw new IllegalStateException(
                    "Handler " + handlerKey + " must declare channel or derive one from its processor");
        }
        return channelKey;
    }

    private void requireRegisteredChannel(String handlerKey,
                                          String channelKey,
                                          Map<String, FrozenNode> contractNodes,
                                          Map<String, String> contractTypeBlueIds) {
        FrozenNode channelNode = contractNodes.get(channelKey);
        if (channelNode == null) {
            throw new IllegalStateException(
                    "Handler " + handlerKey + " references unknown channel '" + channelKey + "'");
        }
        String channelTypeBlueId = contractTypeBlueIds.get(channelKey);
        if (channelTypeBlueId == null) {
            throw new IllegalStateException(
                    "Handler " + handlerKey + " references contract '" + channelKey + "' without a type");
        }
        Class<?> channelClass = typeResolver.resolveClass(channelTypeBlueId);
        if (channelClass == null || !ChannelContract.class.isAssignableFrom(channelClass)) {
            throw new IllegalStateException(
                    "Handler " + handlerKey + " references non-channel contract '" + channelKey + "'");
        }
        Contract channelContract = converter.convertWithType(channelNode.toNode(), Contract.class, false);
        if (!(channelContract instanceof ChannelContract)) {
            throw new IllegalStateException(
                    "Handler " + handlerKey + " references non-channel contract '" + channelKey + "'");
        }
        ChannelContract channel = (ChannelContract) channelContract;
        channel.setKey(channelKey);
        channel.setTypeBlueId(channelTypeBlueId);
        if (!ProcessorContractConstants.isProcessorManagedChannel(channel)
                && !registry.lookupChannel(channel).isPresent()) {
            throw new IllegalStateException(
                    "Handler " + handlerKey + " references unsupported channel '" + channelKey + "'");
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String typeBlueId(FrozenNode node) {
        if (node == null || node.getType() == null) {
            return null;
        }
        FrozenNode type = node.getType();
        return type.getReferenceBlueId() != null ? type.getReferenceBlueId() : type.blueId();
    }
}
