package blue.language.utils;

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

}