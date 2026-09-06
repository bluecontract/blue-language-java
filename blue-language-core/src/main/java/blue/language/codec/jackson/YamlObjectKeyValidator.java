package blue.language.codec.jackson;

import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.events.CollectionEndEvent;
import org.yaml.snakeyaml.events.CollectionStartEvent;
import org.yaml.snakeyaml.events.Event;
import org.yaml.snakeyaml.events.MappingStartEvent;
import org.yaml.snakeyaml.events.ScalarEvent;

/** Checks key tokens before the JSON-facing YAML parser coerces them to strings. */
final class YamlObjectKeyValidator {
    private static final Pattern JSON_SCALAR = Pattern.compile(
            "(?:null|true|false|-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)");

    private YamlObjectKeyValidator() { }

    static void validate(String source) {
        Deque<Container> containers = new ArrayDeque<>();
        // Parse events only: no YAML object construction, implicit Java types,
        // alias expansion, or provider reads occur at this syntax boundary.
        for (Event event : new Yaml().parse(new StringReader(source))) {
            if (event instanceof CollectionEndEvent) {
                containers.pop();
                continue;
            }
            if (!(event instanceof ScalarEvent) && !(event instanceof CollectionStartEvent)) {
                continue;
            }
            Container parent = containers.peek();
            if (parent != null && parent.mapping) {
                if (parent.key && !stringKey(event)) {
                    throw new UncheckedObjectMapper.JsonException(new IllegalArgumentException(
                            "Portable Blue YAML requires string object keys."));
                }
                parent.key = !parent.key;
            }
            if (event instanceof CollectionStartEvent) {
                containers.push(new Container(event instanceof MappingStartEvent));
            }
        }
    }

    private static boolean stringKey(Event event) {
        if (!(event instanceof ScalarEvent)) return false;
        ScalarEvent scalar = (ScalarEvent) event;
        if (scalar.getScalarStyle() != DumperOptions.ScalarStyle.PLAIN) return true;
        String value = scalar.getValue();
        // YAML 1.2 JSON-schema strings such as yes/no remain string keys.
        return !value.isEmpty() && !JSON_SCALAR.matcher(value).matches();
    }

    private static final class Container {
        private final boolean mapping;
        private boolean key = true;
        private Container(boolean mapping) { this.mapping = mapping; }
    }
}
