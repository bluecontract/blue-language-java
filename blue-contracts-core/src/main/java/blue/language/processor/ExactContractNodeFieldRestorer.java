package blue.language.processor;

import blue.language.model.Node;
import blue.language.model.Nodes;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Objects;

import static blue.language.model.wire.BlueLanguageConstants.OBJECT_CONTRACTS;

/**
 * Restores exact contract-header values whose Java representation is a Node.
 *
 * <p>The supplied header is opaque value data, never canonical-identity
 * evidence. Identity-bearing fields are populated by the snapshot-bound
 * mapper before this restorer runs. This class only preserves the contract
 * runtime's deliberate authored/collapsed representation for fields declared
 * as {@link Node}.</p>
 */
final class ExactContractNodeFieldRestorer {

    private ExactContractNodeFieldRestorer() {
    }

    static void restore(Object mappedContract, Node exactHeader) {
        Object target = Objects.requireNonNull(
                mappedContract,
                "mappedContract");
        Node header = Objects.requireNonNull(exactHeader, "exactHeader");
        Class<?> current = target.getClass();
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        || field.isSynthetic()
                        || !Node.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                restoreField(target, field, header);
            }
            current = current.getSuperclass();
        }
    }

    private static void restoreField(
            Object target,
            Field field,
            Node exactHeader) {
        String propertyName = propertyName(field);
        Node exactValue = OBJECT_CONTRACTS.equals(propertyName)
                ? exactHeader.getContracts()
                : exactHeader.getProperties() != null
                        ? exactHeader.getProperties().get(propertyName)
                        : null;
        Node detached = exactValue == null || Nodes.isEmptyNode(exactValue)
                ? null
                : exactValue.clone();
        try {
            field.setAccessible(true);
            field.set(target, detached);
        } catch (RuntimeException | IllegalAccessException failure) {
            throw new IllegalStateException(
                    "Cannot restore exact Node header field '"
                            + propertyName
                            + "' on "
                            + target.getClass().getName(),
                    failure);
        }
    }

    private static String propertyName(Field field) {
        JsonProperty property = field.getAnnotation(JsonProperty.class);
        if (property != null
                && property.value() != null
                && !property.value().isEmpty()
                && !JsonProperty.USE_DEFAULT_NAME.equals(property.value())) {
            return property.value();
        }
        return field.getName();
    }
}
