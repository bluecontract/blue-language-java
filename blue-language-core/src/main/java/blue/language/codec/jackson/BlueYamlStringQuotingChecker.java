package blue.language.codec.jackson;

import com.fasterxml.jackson.dataformat.yaml.util.StringQuotingChecker;
import java.util.regex.Pattern;

/** Preserves numeric-looking Text and keys that would otherwise become YAML merge syntax. */
final class BlueYamlStringQuotingChecker extends StringQuotingChecker.Default {
    private static final Pattern NUMBER = Pattern.compile(
            "-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");

    @Override
    public boolean needToQuoteName(String name) {
        return "<<".equals(name) || super.needToQuoteName(name) || NUMBER.matcher(name).matches();
    }

    @Override
    public boolean needToQuoteValue(String value) {
        return super.needToQuoteValue(value) || NUMBER.matcher(value).matches();
    }
}
