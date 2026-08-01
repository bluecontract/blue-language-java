# Preprocessing

Preprocessing converts authored Source into the portable Preprocessed Document
consumed by resolution. The stage order is fixed:

1. resolve and validate the root `blue` directive;
2. resolve and freeze imports, then preflight every transformation;
3. clone the Source and remove `blue`;
4. execute every frozen transformation exactly once in declaration order;
5. run mandatory wrapper normalization, alias substitution, primitive
   inference, and final validation.

All preflight work completes before the first transformation runs. The
mandatory baseline always runs and is not a hidden Default Blue directive.

This complete Java 8 program uses an inline directive to define an import:

```java
import blue.language.api.BlueLanguage;
import blue.language.codec.BlueFormat;
import blue.language.model.Node;

import static blue.language.utils.Properties.TEXT_TYPE_BLUE_ID;

public final class PreprocessingExample {
    public static void main(String[] args) {
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            Node source = language.codec().parseSource(
                    "blue:\n"
                            + "  imports:\n"
                            + "    Message:\n"
                            + "      blueId: " + TEXT_TYPE_BLUE_ID + "\n"
                            + "type: Message\n"
                            + "value: hello",
                    BlueFormat.YAML);

            Node preprocessed = language.preprocessing().preprocess(source);

            if (preprocessed.getBlue() != null
                    || !TEXT_TYPE_BLUE_ID.equals(
                    preprocessed.getType().getBlueId())
                    || source.getBlue() == null) {
                throw new AssertionError("Unexpected preprocessing result");
            }
        }
    }
}
```

The root directive may be inline, a string alias configured on the
`BlueLanguage` builder, or a pure reference to one exact directive. Referenced
directives, import maps, transformation lists, and transformation nodes must be
available and identity-verified during preflight. The strict `preprocess`
method fails closed for `NOT_FOUND`, `UNAVAILABLE`, or invalid evidence; it
does not reinterpret unavailable evidence as absence.

`environmentIdentity()` identifies the frozen preprocessing configuration.
The baseline-only runtime has one stable identity; builder-configured directive
aliases contribute deterministically to the configured runtime identity. A
host that constructs `StandardBluePreprocessing` with additional behavior must
supply an explicit stable environment identity and retain it with Source-derived
results.
