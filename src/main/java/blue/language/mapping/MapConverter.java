package blue.language.mapping;

import blue.language.model.Node;
import blue.language.utils.TypeClassResolver;

import java.lang.reflect.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MapConverter implements Converter<Map<?, ?>> {
    private final ConverterFactory converterFactory;
    private final TypeClassResolver typeClassResolver;

    public MapConverter(ConverterFactory converterFactory, TypeClassResolver typeClassResolver) {
        this.converterFactory = converterFactory;
        this.typeClassResolver = typeClassResolver;
    }

    @Override
    public Map<?, ?> convert(Node node, Type targetType) {
        if (node.getProperties() == null) {
            return null;
        }

        Class<?> rawType = getRawType(targetType);
        Map<Object, Object> result;
        try {
            result = (Map<Object, Object>) TypeCreatorRegistry.createInstance(rawType);
        } catch (IllegalArgumentException e) {
            result = new HashMap<>();
        }

        Type[] typeArguments = getTypeArguments(targetType);
        Type keyType = typeArguments[0];
        Type valueType = typeArguments[1];

        if (node.getName() != null) {
            result.put("name", node.getName());
        }
        if (node.getDescription() != null) {
            result.put("description", node.getDescription());
        }

        for (Map.Entry<String, Node> entry : node.getProperties().entrySet()) {
            Object key = convertKey(entry.getKey(), keyType);
            Object value = convertValue(entry.getValue(), valueType);
            if (value != null) {
                result.put(key, value);
            }
        }

        return result;
    }

    private Object convertKey(String key, Type keyType) {
        Class<?> keyClass = getRawType(keyType);
        Node keyNode = new Node().value(key);
        keyNode.type(new Node().blueId(blue.language.utils.Properties.TEXT_TYPE_BLUE_ID));
        return ValueConverter.convertValue(keyNode, keyClass);
    }

    private Object convertValue(Node valueNode, Type valueType) {
        Class<?> resolvedClass = typeClassResolver.resolveClass(valueNode);
        if (resolvedClass != null && isAssignableToValueType(resolvedClass, valueType)) {
            Converter<?> converter = converterFactory.getConverter(valueNode, resolvedClass);
            return converter.convert(valueNode, resolvedClass);
        } else {
            if (valueType == Object.class) {
                return convertToAppropriateType(valueNode);
            } else {
                Converter<?> converter = converterFactory.getConverter(valueNode, getRawType(valueType));
                return converter.convert(valueNode, valueType);
            }
        }
    }

    private Object convertToAppropriateType(Node valueNode) {
        if (valueNode.getValue() != null) {
            return valueNode.getValue();
        } else if (valueNode.getProperties() != null) {
            return convert(valueNode, Map.class);
        } else if (valueNode.getItems() != null) {
            return converterFactory.getConverter(valueNode, List.class).convert(valueNode, List.class);
        } else {
            return null;
        }
    }

    private boolean isAssignableToValueType(Class<?> resolvedClass, Type valueType) {
        if (valueType instanceof Class<?>) {
            return ((Class<?>) valueType).isAssignableFrom(resolvedClass);
        } else if (valueType instanceof WildcardType) {
            Type[] upperBounds = ((WildcardType) valueType).getUpperBounds();
            if (upperBounds.length > 0 && upperBounds[0] instanceof Class<?>) {
                return ((Class<?>) upperBounds[0]).isAssignableFrom(resolvedClass);
            }
        } else if (valueType instanceof ParameterizedType) {
            return isAssignableToValueType(resolvedClass, ((ParameterizedType) valueType).getRawType());
        }
        return false;
    }

    private Class<?> getRawType(Type type) {
        if (type instanceof Class<?>) {
            return (Class<?>) type;
        } else if (type instanceof ParameterizedType) {
            return getRawType(((ParameterizedType) type).getRawType());
        } else if (type instanceof GenericArrayType) {
            Type componentType = ((GenericArrayType) type).getGenericComponentType();
            return Array.newInstance(getRawType(componentType), 0).getClass();
        } else if (type instanceof TypeVariable) {
            return Object.class;
        } else if (type instanceof WildcardType) {
            return getRawType(((WildcardType) type).getUpperBounds()[0]);
        }
        throw new IllegalArgumentException("Unsupported type: " + type);
    }

    private Type[] getTypeArguments(Type type) {
        if (type instanceof ParameterizedType) {
            return ((ParameterizedType) type).getActualTypeArguments();
        }
        return new Type[]{Object.class, Object.class};
    }
}