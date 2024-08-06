package blue.language.utils;

import blue.language.model.BlueId;

import java.util.Optional;
import java.util.regex.Pattern;

public class BlueIds {

    private static final int MIN_BLUE_ID_LENGTH = 41;
    private static final int MAX_BLUE_ID_LENGTH = 45;
    private static final Pattern BLUE_ID_PATTERN = Pattern.compile("^[1-9A-HJ-NP-Za-km-z]{41,45}(?:#\\d+)?$");

    public static boolean isPotentialBlueId(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }

        if (!BLUE_ID_PATTERN.matcher(value).matches()) {
            return false;
        }

        String[] parts = value.split("#");
        String blueIdPart = parts[0];

        int blueIdLength = blueIdPart.length();
        if (blueIdLength < MIN_BLUE_ID_LENGTH || blueIdLength > MAX_BLUE_ID_LENGTH) {
            return false;
        }

        try {
            byte[] decoded = Base58.decode(blueIdPart);
            if (decoded.length != 32) {
                return false;
            }
        } catch (IllegalArgumentException e) {
            return false;
        }

        if (parts.length > 1) {
            try {
                int index = Integer.parseInt(parts[1]);
                if (index < 0) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
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