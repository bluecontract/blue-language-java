package blue.language.codec.jackson;

import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.events.CollectionEndEvent;
import org.yaml.snakeyaml.events.CollectionStartEvent;
import org.yaml.snakeyaml.events.Event;
import org.yaml.snakeyaml.events.MappingStartEvent;
import org.yaml.snakeyaml.events.NodeEvent;
import org.yaml.snakeyaml.events.ScalarEvent;

/** Checks portable syntax before the JSON-facing parser discards YAML token metadata. */
final class YamlObjectKeyValidator {
    private static final Pattern JSON_SCALAR = Pattern.compile(
            "(?:null|true|false|-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)");

    private YamlObjectKeyValidator() { }

    static void validate(String source) {
        try {
            validateEvents(source);
        } catch (YAMLException invalidSyntax) {
            throw new UncheckedObjectMapper.JsonException(invalidSyntax);
        }
    }

    private static void validateEvents(String source) {
        Deque<Container> containers = new ArrayDeque<>();
        // Parse events only: no YAML object construction, implicit Java types,
        // alias expansion, or provider reads occur at this syntax boundary.
        for (Event event : new Yaml().parse(new StringReader(source))) {
            rejectYamlOnlySyntax(event);
            if (event instanceof CollectionEndEvent) {
                containers.pop();
                continue;
            }
            if (!(event instanceof ScalarEvent) && !(event instanceof CollectionStartEvent)) {
                continue;
            }
            Container parent = containers.peek();
            if (parent != null && parent.mapping) {
                if (parent.key) {
                    if (!stringKey(event)) {
                        reject("Portable Blue YAML requires string object keys.");
                    }
                    ScalarEvent key = (ScalarEvent) event;
                    if (key.isPlain() && "<<".equals(key.getValue())) {
                        reject("Portable Blue YAML does not support merge keys.");
                    }
                }
                parent.key = !parent.key;
            }
            if (event instanceof CollectionStartEvent) {
                containers.push(new Container(event instanceof MappingStartEvent));
            }
        }
    }

    private static void rejectYamlOnlySyntax(Event event) {
        // AliasEvent is also a NodeEvent: its anchor names the referenced node.
        if (event instanceof NodeEvent && ((NodeEvent) event).getAnchor() != null) {
            reject("YAML anchors and aliases are not part of the Blue JSON data model.");
        }
        String tag = null;
        if (event instanceof ScalarEvent) {
            tag = ((ScalarEvent) event).getTag();
        } else if (event instanceof CollectionStartEvent) {
            tag = ((CollectionStartEvent) event).getTag();
        }
        if (tag != null) {
            reject("YAML tags are not part of the Blue JSON data model.");
        }
    }

    private static void reject(String message) {
        throw new UncheckedObjectMapper.JsonException(new IllegalArgumentException(message));
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
