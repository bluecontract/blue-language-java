package blue.lang.utils;

import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PropertyNameNormalizer {

    public static String normalize(String input) {
        // Normalize to remove accents and special characters
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFD);
        // Remove diacritics
        String noDiacritics = normalized.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        // Replace sequences of non-word characters and spaces with a single underscore
        String underscored = noDiacritics.replaceAll("[\\s\\W]+", "_");
        // Collapse multiple underscores into a single one and remove leading/trailing underscores
        String collapsedUnderscores = underscored.replaceAll("_+", "_").replaceAll("^_|_$", "");
        // Ensure the string does not start with a digit or is empty
        String prepended = collapsedUnderscores;
        if (!prepended.isEmpty() && Character.isDigit(prepended.charAt(0))) {
            prepended = "_" + prepended;
        }
        return toCamelCase(prepended);
    }

    private static String toCamelCase(String str) {
        // Avoid trimming leading underscore if it's there for a digit
        boolean startsWithUnderscore = str.startsWith("_");
        // Lowercase the first character if it's a letter and convert the rest using a regex
        if (!startsWithUnderscore) {
            str = Character.toLowerCase(str.charAt(0)) + str.substring(1);
        }
        Pattern p = Pattern.compile("_(.)");
        Matcher m = p.matcher(str);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, m.group(1).toUpperCase());
        }
        m.appendTail(sb);
        // Re-add leading underscore for variables that start with digits
        return startsWithUnderscore ? "_" + sb : sb.toString();
    }


    public static void main(String[] args) {
        String input = "1 a b c";
//        String input = "to \"♥\" rozwiązanie!!";
        System.out.println(normalize(input)); // Output: toRozwiazanie
    }
}