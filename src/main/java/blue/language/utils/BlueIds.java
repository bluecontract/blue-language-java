package blue.language.utils;

import blue.language.model.TypeBlueId;

import java.util.Optional;
import java.util.regex.Pattern;

public class BlueIds {

    private static final Pattern PLAIN_BLUE_ID_PATTERN = Pattern.compile("^[1-9A-HJ-NP-Za-km-z]+$");
    private static final Pattern CYCLIC_MEMBER_PATTERN = Pattern.compile("^([1-9A-HJ-NP-Za-km-z]+)#(0|[1-9]\\d*)$");
    private static final Pattern THIS_MEMBER_PATTERN = Pattern.compile("^this#(0|[1-9]\\d*)$");
    private static final Pattern ZERO_PLACEHOLDER_PATTERN = Pattern.compile("^0{44}$");

    public static boolean isPotentialBlueId(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }

        try {
            requireBlueIdOrCyclicMember(value, "blueId");
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static String requirePlainBlueId(String value, String path) {
        if (value == null || value.isEmpty() || !PLAIN_BLUE_ID_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("Expected canonical Base58 SHA-256 BlueId at " + path + ".");
        }
        byte[] decoded;
        try {
            decoded = Base58.decode(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Expected canonical Base58 SHA-256 BlueId at " + path + ".", e);
        }
        if (decoded.length != 32 || !Base58.encode(decoded).equals(value)) {
            throw new IllegalArgumentException("Expected canonical Base58 SHA-256 BlueId at " + path + ".");
        }
        return value;
    }

    public static String requireBlueIdOrCyclicMember(String value, String path) {
        if (value == null) {
            throw new IllegalArgumentException("Expected BlueId at " + path + ".");
        }
        java.util.regex.Matcher cyclic = CYCLIC_MEMBER_PATTERN.matcher(value);
        if (cyclic.matches()) {
            requirePlainBlueId(cyclic.group(1), path);
            return value;
        }
        if (value.indexOf('#') >= 0) {
            throw new IllegalArgumentException("Invalid cyclic BlueId member syntax at " + path + ".");
        }
        return requirePlainBlueId(value, path);
    }

    public static String requireNoThisPlaceholderOutsideCyclicApi(String value, String path) {
        if (value != null && ("this".equals(value) || THIS_MEMBER_PATTERN.matcher(value).matches())) {
            throw new IllegalArgumentException("\"this\" BlueId placeholders are valid only inside cyclic BlueId calculation APIs. Path: " + path);
        }
        return value;
    }

    public static boolean isCyclicCalculationPlaceholder(String value) {
        return value != null && ("this".equals(value)
                || THIS_MEMBER_PATTERN.matcher(value).matches()
                || ZERO_PLACEHOLDER_PATTERN.matcher(value).matches());
    }

    public static Optional<String> getBlueId(Class<?> clazz) {
        return Optional.ofNullable(BlueIdResolver.resolveBlueId(clazz));
    }

}
