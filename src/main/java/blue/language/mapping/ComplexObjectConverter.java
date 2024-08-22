package blue.language.mapping;

import blue.language.model.BlueDescription;
import blue.language.model.BlueId;
import blue.language.model.BlueName;
import blue.language.model.Node;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.TypeClassResolver;

import java.lang.reflect.*;
import java.util.Map;

public class ComplexObjectConverter implements Converter<Object> {
    private final ConverterFactory converterFactory;
    private final TypeClassResolver typeClassResolver;

    public ComplexObjectConverter(ConverterFactory converterFactory, TypeClassResolver typeClassResolver) {
        this.converterFactory = converterFactory;
        this.typeClassResolver = typeClassResolver;
    }

    @Override
    public Object convert(Node node, Type targetType) {
        Class<?> resolvedClass = typeClassResolver.resolveClass(node);
        Class<?> classToInstantiate = resolvedClass != null ? resolvedClass : getRawType(targetType);

        if (classToInstantiate.isPrimitive() || ValueConverter.isSupportedType(classToInstantiate)) {
            return ValueConverter.convertValue(node, classToInstantiate);
        }

        try {
            Object instance = classToInstantiate.getDeclaredConstructor().newInstance();
            convertFields(node, classToInstantiate, instance);
            return instance;
        } catch (Exception e) {
            throw new RuntimeException("Error creating instance of " + classToInstantiate.getName(), e);
        }
    }

    private void convertFields(Node node, Class<?> clazz, Object instance) throws IllegalAccessException {
        if (clazz.getSuperclass() != null && clazz.getSuperclass() != Object.class) {
            convertFields(node, clazz.getSuperclass(), instance);
        }

        for (Field field : clazz.getDeclaredFields()) {
            field.setAccessible(true);
            String fieldName = field.getName();
            Object fieldValue = null;

            try {
                if (field.isAnnotationPresent(BlueId.class)) {
                    fieldValue = handleBlueIdAnnotation(node, fieldName);
                } else if (field.isAnnotationPresent(BlueName.class)) {
                    fieldValue = handleBlueNameAnnotation(node, field);
                } else if (field.isAnnotationPresent(BlueDescription.class)) {
                    fieldValue = handleBlueDescriptionAnnotation(node, field);
                } else {
                    Node fieldNode = node.getProperties().get(fieldName);
                    if (fieldNode != null) {
                        Type fieldType = field.getGenericType();
                        Class<?> resolvedFieldClass = typeClassResolver.resolveClass(fieldNode);

                        if (resolvedFieldClass != null && field.getType().isAssignableFrom(resolvedFieldClass)) {
                            Converter<?> fieldConverter = converterFactory.getConverter(fieldNode, resolvedFieldClass);
                            fieldValue = fieldConverter.convert(fieldNode, resolvedFieldClass);
                        } else if (Map.class.isAssignableFrom(field.getType())) {
                            fieldValue = converterFactory.convertMap(fieldNode, fieldType);
                        } else {
                            Converter<?> fieldConverter = converterFactory.getConverter(fieldNode, field.getType());
                            fieldValue = fieldConverter.convert(fieldNode, fieldType);
                        }
                    } else if ("name".equals(fieldName)) {
                        fieldValue = node.getName();
                    } else if ("description".equals(fieldName)) {
                        fieldValue = node.getDescription();
                    }
                }

                if (fieldValue != null) {
                    field.set(instance, fieldValue);
                }
            } catch (Exception e) {
                throw new RuntimeException("Error converting field: " + fieldName + " of type: " + field.getGenericType(), e);
            }
        }
    }

    private String handleBlueIdAnnotation(Node node, String fieldName) {
        Node targetNode = node.getProperties().get(fieldName);
        if (targetNode == null) {
            return null;
        }
        return BlueIdCalculator.calculateBlueId(targetNode);
    }

    private String handleBlueNameAnnotation(Node node, Field field) {
        BlueName annotation = field.getAnnotation(BlueName.class);
        String propertyName = annotation.value();
        Node targetNode = node.getProperties().get(propertyName);
        return targetNode != null ? targetNode.getName() : null;
    }

    private String handleBlueDescriptionAnnotation(Node node, Field field) {
        BlueDescription annotation = field.getAnnotation(BlueDescription.class);
        String propertyName = annotation.value();
        Node targetNode = node.getProperties().get(propertyName);
        return targetNode != null ? targetNode.getDescription() : null;
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
}