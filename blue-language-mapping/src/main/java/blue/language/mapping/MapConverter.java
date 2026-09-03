package blue.language.mapping;

import blue.language.model.Node;
import blue.language.model.wire.BlueLanguageConstants;

import java.lang.reflect.*;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts Blue object properties to a Java map using the map's generic key
 * and value types.
 *
 * <p>Node name and description metadata are exposed as map entries when
 * present. Implementations that cannot be instantiated fall back to a
 * {@link HashMap}.</p>
 */
public class MapConverter implements Converter<Map<?, ?>> {
    private final ConverterFactory converterFactory;
    private final TypeClassResolver typeClassResolver;
    private final ObjectFactoryRegistry objectFactories;

    /**
     * Creates a recursive map converter.
     *
     * @param converterFactory factory for nested value converters
     * @param typeClassResolver resolver for Blue-declared Java types
     */
    public MapConverter(
            ConverterFactory converterFactory,
            TypeClassResolver typeClassResolver) {
        this(
                converterFactory,
                typeClassResolver,
                ObjectFactoryRegistry.defaults());
    }

    /**
     * Creates a recursive map converter with explicit factories.
     *
     * @param converterFactory factory for nested value converters
     * @param typeClassResolver resolver for Blue-declared Java types
     * @param objectFactories immutable object factory registry
     */
    public MapConverter(
            ConverterFactory converterFactory,
            TypeClassResolver typeClassResolver,
            ObjectFactoryRegistry objectFactories) {
        this.converterFactory = converterFactory;
        this.typeClassResolver = typeClassResolver;
        this.objectFactories = objectFactories;
    }

    @Override
    public Map<?, ?> convert(Node node, Type targetType) {
        if (node == null) {
            return null;
        }
        MappingPayload.requireCompatible(node, targetType, "map mapping");

        Class<?> rawType = getRawType(targetType);
        Map<Object, Object> result;
        try {
            result = (Map<Object, Object>) objectFactories.create(rawType);
        } catch (IllegalArgumentException e) {
            result = new HashMap<>();
        }

        Type[] typeArguments = getTypeArguments(targetType);
        Type keyType = typeArguments[0];
        Type valueType = typeArguments[1];

        if (node.getName() != null) {
            result.put(BlueLanguageConstants.OBJECT_NAME, node.getName());
        }
        if (node.getDescription() != null) {
            result.put(BlueLanguageConstants.OBJECT_DESCRIPTION, node.getDescription());
        }

        for (Map.Entry<String, Node> entry : node.getProperties().entrySet()) {
            try {
                Object key = convertKey(entry.getKey(), keyType);
                Object value = convertValue(entry.getValue(), valueType);
                result.put(key, value);
            } catch (RuntimeException e) {
                throw MappingPayload.nestedFailure(
                        "map value for key '" + entry.getKey() + "'",
                        e);
            }
        }

        return result;
    }

    private Object convertKey(String key, Type keyType) {
        Class<?> keyClass = getRawType(keyType);
        Node keyNode = new Node().value(key);
        keyNode.type(new Node().blueId(BlueLanguageConstants.TEXT_TYPE_BLUE_ID));
        return ValueConverter.convertValue(keyNode, keyClass);
    }

    private Object convertValue(Node valueNode, Type valueType) {
        if (valueNode == null) {
            return null;
        }

        Class<?> resolvedClass = converterFactory.resolveClass(
                valueNode, typeClassResolver);
        if (resolvedClass != null && isAssignableToValueType(resolvedClass, valueType)) {
            Converter<?> converter = converterFactory.getConverter(valueNode, resolvedClass);
            return converter.convert(valueNode, resolvedClass);
        } else {
            Converter<?> converter = converterFactory.getConverter(
                    valueNode,
                    getRawType(valueType));
            return converter.convert(valueNode, valueType);
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
