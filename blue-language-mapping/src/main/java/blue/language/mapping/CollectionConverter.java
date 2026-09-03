package blue.language.mapping;

import blue.language.model.Node;
import blue.language.model.Nodes;

import java.lang.reflect.*;
import java.util.*;

/**
 * Converts Blue list nodes to Java arrays and collection types, recursively
 * selecting converters for their declared generic item type.
 *
 * <p>When an interface or abstract collection cannot be instantiated, the
 * converter falls back to an {@link ArrayList}. Null elements become Java null
 * values or primitive defaults for primitive arrays.</p>
 */
public class CollectionConverter implements Converter<Object> {
    private final ConverterFactory converterFactory;
    private final TypeClassResolver typeClassResolver;
    private final ObjectFactoryRegistry objectFactories;

    /**
     * Creates a recursive collection converter.
     *
     * @param converterFactory factory for nested item converters
     * @param typeClassResolver resolver for Blue-declared Java types
     */
    public CollectionConverter(
            ConverterFactory converterFactory,
            TypeClassResolver typeClassResolver) {
        this(
                converterFactory,
                typeClassResolver,
                ObjectFactoryRegistry.defaults());
    }

    /**
     * Creates a recursive collection converter with explicit factories.
     *
     * @param converterFactory factory for nested item converters
     * @param typeClassResolver resolver for Blue-declared Java types
     * @param objectFactories immutable object factory registry
     */
    public CollectionConverter(
            ConverterFactory converterFactory,
            TypeClassResolver typeClassResolver,
            ObjectFactoryRegistry objectFactories) {
        this.converterFactory = converterFactory;
        this.typeClassResolver = typeClassResolver;
        this.objectFactories = objectFactories;
    }

    @Override
    public Object convert(Node node, Type targetType) {
        if (node == null) {
            return null;
        }

        MappingPayload.requireCompatible(
                node,
                targetType,
                "collection mapping");

        Class<?> rawType = getRawType(targetType);
        if (rawType.isArray()) {
            return convertToArray(node, getComponentType(targetType));
        } else if (Collection.class.isAssignableFrom(rawType)) {
            return convertToCollection(node, targetType, rawType);
        }

        throw new IllegalArgumentException("Unsupported collection type: " + targetType);
    }

    private Object convertToCollection(Node node, Type targetType, Class<?> rawType) {
        if (rawType.isArray()) {
            return convertToArray(node, getComponentType(targetType));
        }

        Collection<Object> result;
        try {
            result = (Collection<Object>) objectFactories.create(rawType);
        } catch (IllegalArgumentException e) {
            result = new ArrayList<>();
        }

        if (node.getItems() == null) {
            return result;
        }

        Type itemType = getItemType(targetType);

        for (int index = 0; index < node.getItems().size(); index++) {
            Node item = node.getItems().get(index);
            try {
                if (item == null || Nodes.isEmptyPlaceholder(item)) {
                    result.add(null);
                } else {
                    Class<?> resolvedClass = converterFactory.resolveClass(
                            item, typeClassResolver);
                    Object convertedItem;
                    if (resolvedClass != null && isAssignableToItemType(resolvedClass, itemType)) {
                        Converter<?> itemConverter = converterFactory.getConverter(item, resolvedClass);
                        convertedItem = itemConverter.convert(item, resolvedClass);
                    } else {
                        Converter<?> itemConverter = converterFactory.getConverter(item, getRawType(itemType));
                        convertedItem = itemConverter.convert(item, itemType);
                    }
                    result.add(convertedItem);
                }
            } catch (RuntimeException e) {
                throw MappingPayload.nestedFailure(
                        "collection item [" + index + "]",
                        e);
            }
        }

        return result;
    }

    private Object convertToArray(Node node, Type componentType) {
        if (node == null || node.getItems() == null) {
            return null;
        }
        List<Object> list = convertToList(node, componentType);
        Class<?> componentClass = getRawType(componentType);
        Object array = Array.newInstance(componentClass, list.size());
        for (int i = 0; i < list.size(); i++) {
            Object value = list.get(i);
            if (value == null && componentClass.isPrimitive()) {
                // Set default value for primitive types
                if (componentClass == int.class) {
                    Array.setInt(array, i, 0);
                } else if (componentClass == long.class) {
                    Array.setLong(array, i, 0L);
                } else if (componentClass == double.class) {
                    Array.setDouble(array, i, 0.0);
                } else if (componentClass == float.class) {
                    Array.setFloat(array, i, 0.0f);
                } else if (componentClass == boolean.class) {
                    Array.setBoolean(array, i, false);
                } else if (componentClass == byte.class) {
                    Array.setByte(array, i, (byte) 0);
                } else if (componentClass == short.class) {
                    Array.setShort(array, i, (short) 0);
                } else if (componentClass == char.class) {
                    Array.setChar(array, i, '\u0000');
                }
            } else {
                Array.set(array, i, value);
            }
        }
        return array;
    }

    private List<Object> convertToList(Node node, Type itemType) {
        List<Object> result = new ArrayList<>();
        if (node.getItems() == null) {
            return result;
        }
        for (int index = 0; index < node.getItems().size(); index++) {
            Node item = node.getItems().get(index);
            try {
                if (item == null || Nodes.isEmptyPlaceholder(item)) {
                    result.add(null);
                } else {
                    Class<?> resolvedClass = converterFactory.resolveClass(
                            item, typeClassResolver);
                    if (resolvedClass != null && isAssignableToItemType(resolvedClass, itemType)) {
                        Converter<?> itemConverter = converterFactory.getConverter(item, resolvedClass);
                        result.add(itemConverter.convert(item, resolvedClass));
                    } else {
                        Converter<?> itemConverter = converterFactory.getConverter(item, getRawType(itemType));
                        result.add(itemConverter.convert(item, itemType));
                    }
                }
            } catch (RuntimeException e) {
                throw MappingPayload.nestedFailure(
                        "array item [" + index + "]",
                        e);
            }
        }
        return result;
    }

    private boolean isAssignableToItemType(Class<?> resolvedClass, Type itemType) {
        if (itemType instanceof Class<?>) {
            return ((Class<?>) itemType).isAssignableFrom(resolvedClass);
        } else if (itemType instanceof WildcardType) {
            Type[] upperBounds = ((WildcardType) itemType).getUpperBounds();
            if (upperBounds.length > 0 && upperBounds[0] instanceof Class<?>) {
                return ((Class<?>) upperBounds[0]).isAssignableFrom(resolvedClass);
            }
        } else if (itemType instanceof ParameterizedType) {
            return isAssignableToItemType(resolvedClass, ((ParameterizedType) itemType).getRawType());
        }
        return false;
    }

    private Type getItemType(Type type) {
        if (type instanceof ParameterizedType) {
            Type[] typeArguments = ((ParameterizedType) type).getActualTypeArguments();
            if (typeArguments.length > 0) {
                return typeArguments[0];
            }
        } else if (type instanceof GenericArrayType) {
            return ((GenericArrayType) type).getGenericComponentType();
        } else if (type instanceof Class<?> && ((Class<?>) type).isArray()) {
            return ((Class<?>) type).getComponentType();
        }
        return Object.class;
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

    private Type getComponentType(Type type) {
        if (type instanceof Class<?> && ((Class<?>) type).isArray()) {
            return ((Class<?>) type).getComponentType();
        } else if (type instanceof GenericArrayType) {
            return ((GenericArrayType) type).getGenericComponentType();
        }
        return Object.class;
    }
}
