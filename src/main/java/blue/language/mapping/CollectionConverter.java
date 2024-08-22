package blue.language.mapping;

import blue.language.model.Node;
import blue.language.utils.TypeClassResolver;

import java.lang.reflect.*;
import java.util.*;

public class CollectionConverter implements Converter<Object> {
    private final ConverterFactory converterFactory;
    private final TypeClassResolver typeClassResolver;

    public CollectionConverter(ConverterFactory converterFactory, TypeClassResolver typeClassResolver) {
        this.converterFactory = converterFactory;
        this.typeClassResolver = typeClassResolver;
    }

    @Override
    public Object convert(Node node, Type targetType) {
        if (node.getItems() == null) {
            return null;
        }

        Class<?> rawType = getRawType(targetType);
        if (rawType.isArray()) {
            return convertToArray(node, getComponentType(targetType));
        } else if (Collection.class.isAssignableFrom(rawType)) {
            return convertToCollection(node, targetType, rawType);
        }

        throw new IllegalArgumentException("Unsupported collection type: " + targetType);
    }

    private Object convertToCollection(Node node, Type targetType, Class<?> rawType) {
        Collection<Object> result;
        try {
            result = (Collection<Object>) TypeCreatorRegistry.createInstance(rawType);
        } catch (IllegalArgumentException e) {
            result = new ArrayList<>();
        }

        Type itemType = getItemType(targetType);

        for (Node item : node.getItems()) {
            Class<?> resolvedClass = typeClassResolver.resolveClass(item);
            if (resolvedClass != null && isAssignableToItemType(resolvedClass, itemType)) {
                Converter<?> itemConverter = converterFactory.getConverter(item, resolvedClass);
                Object convertedItem = itemConverter.convert(item, resolvedClass);
                result.add(convertedItem);
            } else {
                Converter<?> itemConverter = converterFactory.getConverter(item, getRawType(itemType));
                Object convertedItem = itemConverter.convert(item, itemType);
                result.add(convertedItem);
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

    private Object convertToArray(Node node, Type componentType) {
        List<Object> list = convertToList(node, componentType);
        Class<?> componentClass = getRawType(componentType);
        Object array = Array.newInstance(componentClass, list.size());
        for (int i = 0; i < list.size(); i++) {
            Array.set(array, i, list.get(i));
        }
        return array;
    }

    private List<Object> convertToList(Node node, Type itemType) {
        List<Object> result = new ArrayList<>();
        for (Node item : node.getItems()) {
            Class<?> resolvedClass = typeClassResolver.resolveClass(item);
            if (resolvedClass != null && isAssignableToItemType(resolvedClass, itemType)) {
                Converter<?> itemConverter = converterFactory.getConverter(item, resolvedClass);
                result.add(itemConverter.convert(item, resolvedClass));
            } else {
                Converter<?> itemConverter = converterFactory.getConverter(item, getRawType(itemType));
                result.add(itemConverter.convert(item, itemType));
            }
        }
        return result;
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