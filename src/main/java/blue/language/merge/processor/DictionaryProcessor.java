package blue.language.merge.processor;

import blue.language.*;
import blue.language.merge.MergingProcessor;
import blue.language.merge.NodeResolver;
import blue.language.model.Node;
import blue.language.utils.NodeToObject;
import blue.language.utils.Types;

import java.util.Map;

import static blue.language.utils.Types.isSubtype;

public class DictionaryProcessor implements MergingProcessor {

    @Override
    public void process(Node target, Node source, NodeProvider nodeProvider, NodeResolver nodeResolver) {
        if ((source.getKeyType() != null || source.getValueType() != null) && !Types.isDictionaryType(source.getType())) {
            throw new IllegalArgumentException("Source node with keyType or valueType must have a Dictionary type");
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
                        NodeToObject.get(sourceKeyType), NodeToObject.get(targetKeyType));
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
                        NodeToObject.get(sourceValueType), NodeToObject.get(targetValueType));
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
                Integer.parseInt(key);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Key '" + key + "' is not a valid Integer.");
            }
        } else if (Types.isNumberType(keyType, nodeProvider)) {
            try {
                Double.parseDouble(key);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Key '" + key + "' is not a valid Number.");
            }
        } else if (Types.isBooleanType(keyType, nodeProvider)) {
            if (!key.equalsIgnoreCase("true") && !key.equalsIgnoreCase("false")) {
                throw new IllegalArgumentException("Key '" + key + "' is not a valid Boolean.");
            }
        } else {
            throw new IllegalArgumentException("Unsupported key type: " + keyType.getName());
        }
    }

    private void validateValueType(Node value, Node valueType, NodeProvider nodeProvider) {
        if (value.getType() != null && !isSubtype(value.getType(), valueType, nodeProvider)) {
            String errorMessage = String.format("Value of type '%s' is not a subtype of the dictionary's value type '%s'.",
                    NodeToObject.get(value.getType()), NodeToObject.get(valueType));
            throw new IllegalArgumentException(errorMessage);
        }
    }
}