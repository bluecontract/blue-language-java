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
import java.util.Objects;

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

        for (Map.Entry<String, FrozenNode> entry : contractsNode.getProperties().entrySet()) {
            String key = entry.getKey();
            String typeBlueId = typeBlueId(entry.getValue());
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
                if (!registry.lookupHandler(handler).isPresent()) {
                    throw new MustUnderstandFailureException(
                            "Unsupported contract type: " + typeBlueId);
                }
                if (handler.getChannelKey() == null || handler.getChannelKey().isEmpty()) {
                    throw new IllegalStateException("Handler " + key + " must declare channel");
                }
                builder.addHandler(key, handler);
            } else if (contract instanceof ProcessEmbedded) {
                builder.setEmbedded((ProcessEmbedded) contract);
            } else if (contract instanceof MarkerContract) {
                builder.addMarker(key, (MarkerContract) contract);
            }
        }

        return builder.build();
    }

    private String typeBlueId(FrozenNode node) {
        if (node == null || node.getType() == null) {
            return null;
        }
        FrozenNode type = node.getType();
        return type.getReferenceBlueId() != null ? type.getReferenceBlueId() : type.blueId();
    }
}
