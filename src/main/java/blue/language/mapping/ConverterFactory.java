package blue.language.mapping;

import blue.language.model.Node;
import blue.language.utils.TypeClassResolver;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

public class ConverterFactory {
    private final TypeClassResolver typeClassResolver;
    private final Map<Class<?>, Converter<?>> converters = new HashMap<>();

    public ConverterFactory(TypeClassResolver typeClassResolver) {
        this.typeClassResolver = typeClassResolver;
        registerConverters();
    }

    private void registerConverters() {
        PrimitiveConverter primitiveConverter = new PrimitiveConverter();
        converters.put(Object.class, new ComplexObjectConverter(this, typeClassResolver));
        converters.put(String.class, primitiveConverter);
        converters.put(Boolean.class, primitiveConverter);
        converters.put(Byte.class, primitiveConverter);
        converters.put(Short.class, primitiveConverter);
        converters.put(Integer.class, primitiveConverter);
        converters.put(Long.class, primitiveConverter);
        converters.put(Float.class, primitiveConverter);
        converters.put(Double.class, primitiveConverter);
        converters.put(BigInteger.class, primitiveConverter);
        converters.put(BigDecimal.class, primitiveConverter);
        CollectionConverter collectionConverter = new CollectionConverter(this, typeClassResolver);
        converters.put(Collection.class, collectionConverter);
        converters.put(List.class, collectionConverter);
        converters.put(Set.class, collectionConverter);
        converters.put(Queue.class, collectionConverter);
        converters.put(Deque.class, collectionConverter);
        converters.put(Enum.class, new EnumConverter());
        converters.put(Map.class, new MapConverter(this, typeClassResolver));
        converters.put(Node.class, new NodeConverter());
//        converters.put(AnnotatedField.class, new AnnotatedFieldConverter(this));

    }

    public Converter<?> getConverter(Node node, Type targetType) {
        return getConverter(node, targetType, false);
    }

    @SuppressWarnings("unchecked")
    public Converter<?> getConverter(Node node, Type targetType, boolean prioritizeTargetType) {

        if (node == null) {
            return new NullConverter();
        }

        Class<?> rawType = getRawType(targetType);

        if (rawType.isEnum()) {
            return converters.get(Enum.class);
        }
        if (rawType.isArray() || Collection.class.isAssignableFrom(rawType)) {
            return converters.get(Collection.class);
        }
        if (Map.class.isAssignableFrom(rawType)) {
            return converters.get(Map.class);
        }
        if (rawType.isPrimitive() || ValueConverter.isSupportedType(rawType)) {
            return converters.get(Object.class);
        }
        Converter<?> converter = converters.get(rawType);
        if (converter == null) {
            return new ComplexObjectConverter(this, typeClassResolver);
        }
        return converter;
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

    public Map<?, ?> convertMap(Node node, Type mapType) {
        MapConverter mapConverter = new MapConverter(this, typeClassResolver);
        return mapConverter.convert(node, mapType);
    }
}
