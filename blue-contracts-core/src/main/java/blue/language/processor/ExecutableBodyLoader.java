package blue.language.processor;

import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.mapping.NodeToObjectConverter;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.BlueId;
import blue.language.model.Node;
import blue.language.processor.model.Contract;
import blue.language.processor.model.HandlerContract;
import blue.language.snapshot.FrozenNode;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
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
            Function<FrozenNode, ResolvedSnapshot> materializer) {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(materializer, "materializer");
        FrozenNode frozen = binding.node();
        if (frozen == null) {
            return binding;
        }
        Node executable = frozen.toNode();
        List<CanonicalTypeIdentityLookup> identityEvidence =
                new ArrayList<>();
        Map<String, ResolvedSnapshot> materializedBodies =
                new LinkedHashMap<>();
        identityEvidence.add(binding.typeIdentities());
        for (String field : binding.executableBodyFields()) {
            materializeField(
                    executable,
                    frozen,
                    field,
                    materializer,
                    identityEvidence,
                    materializedBodies);
        }
        if (binding.executableBodyFields().isEmpty()) {
            return binding;
        }
        CanonicalTypeIdentityLookup recombinedTypeIdentities =
                CanonicalTypeIdentityEvidenceUnion
                        .establishForResolvedGraph(
                                executable,
                                identityEvidence);
        Node exactEventMatcher = binding.contract().getEvent();
        List<String> omittedFields = new ArrayList<>(
                materializedBodies.keySet());
        omittedFields.add(EVENT_MATCHER_FIELD);
        Contract converted = converter.convertWithType(
                headerNode(
                        executable,
                        omittedFields),
                Contract.class,
                false,
                recombinedTypeIdentities);
        if (!(converted instanceof HandlerContract)) {
            throw new MustUnderstandFailureException(
                    "Selected executable body no longer belongs to a Handler",
                    ProcessorErrorCategory.InvalidContractBinding);
        }
        HandlerContract handler = (HandlerContract) converted;
        restoreMaterializedBodies(handler, materializedBodies);
        restoreEventMatcher(handler, exactEventMatcher);
        handler.setKey(binding.key());
        handler.setTypeBlueId(binding.contract().getTypeBlueId());
        handler.setChannelKey(binding.contract().getChannelKey());
        return new ContractBundle.HandlerBinding(
                binding.key(),
                handler,
                FrozenNode.fromResolvedNode(executable),
                binding.executableBodyFields(),
                recombinedTypeIdentities);
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
            Function<FrozenNode, ResolvedSnapshot> materializer,
            List<CanonicalTypeIdentityLookup> identityEvidence,
            Map<String, ResolvedSnapshot> materializedBodies) {
        FrozenNode body = property(frozen, field);
        if (body == null || !body.isReferenceOnly()) {
            return;
        }
        ResolvedSnapshot materialized = Objects.requireNonNull(
                materializer.apply(body),
                "materializedExecutableBodySnapshot");
        executable.properties(
                field,
                materialized.frozenResolvedRoot().toNode());
        identityEvidence.add(materialized.canonicalTypeIdentities());
        materializedBodies.put(field, materialized);
    }

    private void restoreMaterializedBodies(
            HandlerContract handler,
            Map<String, ResolvedSnapshot> materializedBodies) {
        for (Map.Entry<String, ResolvedSnapshot> entry
                : materializedBodies.entrySet()) {
            Field field = findField(handler.getClass(), entry.getKey());
            if (field == null) {
                throw new IllegalStateException(
                        "No Handler field maps executable body property '"
                                + entry.getKey() + "'");
            }
            Object convertedBody = field.isAnnotationPresent(BlueId.class)
                    ? entry.getValue().blueId()
                    : converter.convertWithType(
                            entry.getValue(),
                            "/",
                            field.getGenericType(),
                            true);
            try {
                field.setAccessible(true);
                field.set(handler, convertedBody);
            } catch (RuntimeException | IllegalAccessException failure) {
                throw new IllegalStateException(
                        "Cannot restore materialized executable body property '"
                                + entry.getKey() + "' on "
                                + handler.getClass().getName(),
                        failure);
            }
        }
    }

    private Field findField(Class<?> type, String propertyName) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        || field.isSynthetic()) {
                    continue;
                }
                JsonProperty property = field.getAnnotation(
                        JsonProperty.class);
                String mappedName = property != null
                        && property.value() != null
                        && !property.value().isEmpty()
                        && !JsonProperty.USE_DEFAULT_NAME.equals(
                        property.value())
                        ? property.value()
                        : field.getName();
                if (propertyName.equals(mappedName)) {
                    return field;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private FrozenNode property(FrozenNode node, String key) {
        return node != null && node.getProperties() != null
                ? node.getProperties().get(key)
                : null;
    }
}
