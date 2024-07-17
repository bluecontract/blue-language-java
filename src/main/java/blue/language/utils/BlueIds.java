package blue.language.utils;

import blue.language.model.BlueId;

import java.util.Optional;

public class BlueIds {

    public static final int BLUE_ID_LENGTH = 44;

    public static boolean isPotentialBlueId(String value) {
        if (value.length() != BLUE_ID_LENGTH)
            return false;

        try {
            Base58.decode(value);
        } catch (IllegalArgumentException e) {
            return false;
        }

        return true;
    }

    public static Optional<String> getBlueId(Class<?> clazz) {
        BlueId blueIdAnnotation = clazz.getAnnotation(BlueId.class);
        if (blueIdAnnotation != null) {
            String defaultValue = blueIdAnnotation.defaultValue();
            if (!defaultValue.isEmpty()) {
                return Optional.of(defaultValue);
            }
            String[] values = blueIdAnnotation.value();
            if (values.length > 0) {
                return Optional.of(values[0]);
            }
        }
        return Optional.empty();
    }

}