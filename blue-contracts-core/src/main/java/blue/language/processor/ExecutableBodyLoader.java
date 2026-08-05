package blue.language.processor;

import blue.language.mapping.NodeToObjectConverter;
import blue.language.model.Node;
import blue.language.processor.model.Contract;
import blue.language.processor.model.HandlerContract;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Keeps executable contract fields cold until their handler is selected.
 *
 * <p>Header conversion operates on a body-free clone. Exact authored bodies
 * are retained as frozen nodes and a pure reference is materialized only at
 * the explicit selection boundary.</p>
 */
final class ExecutableBodyLoader {

    private static final String EVENT_MATCHER_FIELD =
            EffectiveContractSnapshotConstants.DispatchField.EVENT;

    private final NodeToObjectConverter converter;

    ExecutableBodyLoader(NodeToObjectConverter converter) {
        this.converter = Objects.requireNonNull(converter, "converter");
    }

    ContractBundle.HandlerBinding materializeSelected(
            ContractBundle.HandlerBinding binding,
            Function<FrozenNode, FrozenNode> materializer) {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(materializer, "materializer");
        FrozenNode frozen = binding.node();
        if (frozen == null) {
            return binding;
        }
        Node executable = frozen.toNode();
        for (String field : binding.executableBodyFields()) {
            materializeField(executable, frozen, field, materializer);
        }
        if (binding.executableBodyFields().isEmpty()) {
            return binding;
        }
        Node exactEventMatcher = binding.contract().getEvent();
        Contract converted = converter.convertWithType(
                headerNode(
                        executable,
                        Collections.singletonList(EVENT_MATCHER_FIELD)),
                Contract.class,
                false);
        if (!(converted instanceof HandlerContract)) {
            throw new MustUnderstandFailureException(
                    "Selected executable body no longer belongs to a Handler",
                    ProcessorErrorCategory.InvalidContractBinding);
        }
        HandlerContract handler = (HandlerContract) converted;
        restoreEventMatcher(handler, exactEventMatcher);
        handler.setKey(binding.key());
        handler.setTypeBlueId(binding.contract().getTypeBlueId());
        handler.setChannelKey(binding.contract().getChannelKey());
        return new ContractBundle.HandlerBinding(
                binding.key(),
                handler,
                FrozenNode.fromResolvedNode(executable),
                binding.executableBodyFields());
    }

    List<String> deferredHandlerFields(List<String> executableBodyFields) {
        List<String> fields = new ArrayList<>(
                executableBodyFields != null
                        ? executableBodyFields
                        : Collections.<String>emptyList());
        if (!fields.contains(EVENT_MATCHER_FIELD)) {
            fields.add(EVENT_MATCHER_FIELD);
        }
        return fields;
    }

    Node exactExecutableContract(
            FrozenNode effectiveContract,
            List<String> executableBodyFields,
            Map<String, Node> exactExecutableBodies) {
        Node executable = effectiveContract.toNode();
        if (executableBodyFields.isEmpty()) {
            return executable;
        }
        Map<String, Node> properties =
                executable.getProperties() != null
                        ? new LinkedHashMap<>(executable.getProperties())
                        : new LinkedHashMap<String, Node>();
        for (String field : executableBodyFields) {
            Node exactBody = exactExecutableBodies.get(field);
            if (exactBody != null) {
                properties.put(field, exactBody.clone());
            } else {
                properties.remove(field);
            }
        }
        return executable.properties(properties);
    }

    Node headerNode(
            Node executableContract,
            List<String> executableBodyFields) {
        Node header = executableContract.clone();
        if (header.getProperties() == null) {
            return header;
        }
        Map<String, Node> fields = new LinkedHashMap<>(header.getProperties());
        for (String field : executableBodyFields) {
            fields.remove(field);
        }
        return header.properties(fields);
    }

    void restoreEventMatcher(HandlerContract handler, Node exactEventMatcher) {
        handler.setEvent(exactEventMatcher != null ? exactEventMatcher.clone() : null);
    }

    private void materializeField(
            Node executable,
            FrozenNode frozen,
            String field,
            Function<FrozenNode, FrozenNode> materializer) {
        FrozenNode body = property(frozen, field);
        if (body == null || !body.isReferenceOnly()) {
            return;
        }
        FrozenNode materialized = materializer.apply(body);
        executable.properties(field, materialized.toNode());
    }

    private FrozenNode property(FrozenNode node, String key) {
        return node != null && node.getProperties() != null
                ? node.getProperties().get(key)
                : null;
    }
}
