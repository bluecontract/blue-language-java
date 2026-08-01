package blue.language.merge.processor;

import blue.language.merge.MergingProcessor;
import blue.language.merge.NodeResolver;
import blue.language.model.Node;
import blue.language.provider.NodeProvider;
import blue.language.model.NodeWireForm;
import blue.language.model.wire.BlueLanguageConstants;
import blue.language.provider.Types;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;

import static blue.language.provider.Types.isSubtype;

/**
 * Propagates Dictionary key/value type metadata and validates every contributed
 * property against the resulting constraints.
 */
public class DictionaryProcessor implements MergingProcessor {

    /** Creates a stateless Dictionary merge processor. */
    public DictionaryProcessor() {
    }

    @Override
    public void process(Node target, Node source, NodeProvider nodeProvider, NodeResolver nodeResolver) {
        Node effectiveCollectionType =
                source.getType() != null
                        ? source.getType()
                        : target.getType();
        if (Types.isDictionaryType(effectiveCollectionType, nodeProvider)
                && (source.getValue() != null
                || source.getItems() != null)) {
            throw new IllegalArgumentException(
                    "Dictionary-compatible values must use object encoding");
        }
        if (source.getKeyType() != null
                || source.getValueType() != null) {
            /*
             * TypeAssigner runs before this processor, so target carries the
             * effective inherited collection type. An explicit source type
             * still wins for validation and cannot borrow Dictionary
             * compatibility from the target.
             */
            if (!Types.isDictionaryType(
                    effectiveCollectionType,
                    nodeProvider)) {
                throw new IllegalArgumentException(
                        "Source node with keyType or valueType must have a Dictionary type");
            }
        }

        processKeyType(target, source, nodeProvider);
        processValueType(target, source, nodeProvider);

        if ((target.getKeyType() != null || target.getValueType() != null) && source.getProperties() != null) {
            for (Map.Entry<String, Node> entry : source.getProperties().entrySet()) {
                if (target.getKeyType() != null) {
                    validateKeyType(entry.getKey(), target.getKeyType(), nodeProvider);
                }
                if (target.getValueType() != null) {
                    validateValueType(entry.getValue(), target.getValueType(), nodeProvider);
                }
            }
        }
    }

    private void processKeyType(Node target, Node source, NodeProvider nodeProvider) {
        Node targetKeyType = target.getKeyType();
        Node sourceKeyType = source.getKeyType();

        if (targetKeyType == null) {
            if (sourceKeyType != null) {
                validateBasicKeyType(sourceKeyType, nodeProvider);
                target.keyType(sourceKeyType);
            }
        } else if (sourceKeyType != null) {
            validateBasicKeyType(sourceKeyType, nodeProvider);
            boolean isSubtype = isSubtype(sourceKeyType, targetKeyType, nodeProvider);
            if (!isSubtype) {
                String errorMessage = String.format("The source key type '%s' is not a subtype of the target key type '%s'.",
                        NodeWireForm.get(sourceKeyType), NodeWireForm.get(targetKeyType));
                throw new IllegalArgumentException(errorMessage);
            }
            target.keyType(sourceKeyType);
        }
    }

    private void processValueType(Node target, Node source, NodeProvider nodeProvider) {
        Node targetValueType = target.getValueType();
        Node sourceValueType = source.getValueType();

        if (targetValueType == null) {
            if (sourceValueType != null) {
                target.valueType(sourceValueType);
            }
        } else if (sourceValueType != null) {
            boolean isSubtype = isSubtype(sourceValueType, targetValueType, nodeProvider);
            if (!isSubtype) {
                String errorMessage = String.format("The source value type '%s' is not a subtype of the target value type '%s'.",
                        NodeWireForm.get(sourceValueType), NodeWireForm.get(targetValueType));
                throw new IllegalArgumentException(errorMessage);
            }
            target.valueType(sourceValueType);
        }
    }

    private void validateBasicKeyType(Node keyType, NodeProvider nodeProvider) {
        if (!Types.isBasicType(keyType, nodeProvider)) {
            throw new IllegalArgumentException("Dictionary key type must be a basic type");
        }
    }

    private void validateKeyType(String key, Node keyType, NodeProvider nodeProvider) {
        if (Types.isTextType(keyType, nodeProvider)) {
            return;
        }

        if (Types.isIntegerType(keyType, nodeProvider)) {
            try {
                BigInteger value = new BigInteger(key);
                if (!value.toString().equals(key)) {
                    throw new NumberFormatException("non-canonical Integer key");
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Dictionary key '" + key
                        + "' is not a canonical Integer textual form.");
            }
        } else if (Types.isNumberType(keyType, nodeProvider)) {
            try {
                double value = Double.parseDouble(key);
                if (!Double.isFinite(value)
                        || !BigDecimal.valueOf(value).toString().equals(key)) {
                    throw new NumberFormatException("non-canonical Double key");
                }
            } catch (NumberFormatException invalidDouble) {
                throw new IllegalArgumentException("Dictionary key '" + key
                        + "' is not a canonical Double textual form.");
            }
        } else if (Types.isBooleanType(keyType, nodeProvider)) {
            if (!BlueLanguageConstants.BOOLEAN_TEXT_TRUE.equals(key)
                    && !BlueLanguageConstants.BOOLEAN_TEXT_FALSE.equals(key)) {
                throw new IllegalArgumentException("Dictionary key '" + key
                        + "' is not a canonical Boolean textual form.");
            }
        } else {
            throw new IllegalArgumentException("Unsupported key type: " + keyType.getName());
        }
    }

    private void validateValueType(Node value, Node valueType, NodeProvider nodeProvider) {
        if (value.getType() != null && !isSubtype(value.getType(), valueType, nodeProvider)) {
            String errorMessage = String.format("Value of type '%s' is not a subtype of the dictionary's value type '%s'.",
                    NodeWireForm.get(value.getType()), NodeWireForm.get(valueType));
            throw new IllegalArgumentException(errorMessage);
        }
    }
}
