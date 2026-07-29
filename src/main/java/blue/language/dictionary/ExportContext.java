package blue.language.dictionary;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable receiver capabilities used during dictionary-aware export.
 *
 * <p>The map selects one supported dictionary package BlueId per dictionary
 * name. Unsupported external types are inlined by default.</p>
 */
public final class ExportContext {

    private final Map<String, String> dictionaries;
    private final boolean inlineUnsupportedTypes;

    private ExportContext(Builder builder) {
        this.dictionaries = Collections.unmodifiableMap(new LinkedHashMap<>(builder.dictionaries));
        this.inlineUnsupportedTypes = builder.inlineUnsupportedTypes;
    }

    /**
     * Creates a mutable builder with inline fallback enabled.
     *
     * @return new context builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a context with no requested dictionaries and inline fallback
     * enabled.
     *
     * @return empty immutable context
     */
    public static ExportContext empty() {
        return builder().build();
    }

    /**
     * Returns requested dictionary package identities by dictionary name.
     *
     * @return unmodifiable map owned by this context
     */
    public Map<String, String> dictionaries() {
        return dictionaries;
    }

    /**
     * Looks up the requested package identity for a dictionary.
     *
     * @param dictionaryName dictionary name; {@code null} produces an empty
     *                       result
     * @return requested dictionary package BlueId, or an empty optional
     */
    public Optional<String> dictionaryBlueId(String dictionaryName) {
        return Optional.ofNullable(dictionaries.get(dictionaryName));
    }

    /**
     * Tests whether known external types may be replaced by inline
     * definitions when no requested dictionary version can represent them.
     *
     * @return whether inline fallback is enabled
     */
    public boolean inlineUnsupportedTypes() {
        return inlineUnsupportedTypes;
    }

    /**
     * Mutable builder that validates names and dictionary identities.
     *
     * <p>Each built context takes a defensive snapshot, so subsequent builder
     * changes do not affect it.</p>
     */
    public static final class Builder {
        private final Map<String, String> dictionaries = new LinkedHashMap<>();
        private boolean inlineUnsupportedTypes = true;

        /** Creates a builder with inline fallback enabled. */
        public Builder() {
        }

        /**
         * Selects one package identity for a dictionary name, replacing any
         * previous selection with the same name.
         *
         * @param name nonblank dictionary name
         * @param dictionaryBlueId nonblank dictionary package BlueId
         * @return this builder
         * @throws IllegalArgumentException when either argument is null or
         *                                  blank
         */
        public Builder dictionary(String name, String dictionaryBlueId) {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("dictionary name must not be empty");
            }
            if (dictionaryBlueId == null || dictionaryBlueId.trim().isEmpty()) {
                throw new IllegalArgumentException("dictionaryBlueId must not be empty");
            }
            dictionaries.put(name, dictionaryBlueId);
            return this;
        }

        /**
         * Adds all selections in map iteration order.
         *
         * <p>A {@code null} map is a no-op. Valid entries processed before an
         * invalid entry remain in this builder.</p>
         *
         * @param dictionaries dictionary selections to copy, or {@code null}
         * @return this builder
         * @throws IllegalArgumentException when an entry has a null or blank
         *                                  name or package identity
         */
        public Builder dictionaries(Map<String, String> dictionaries) {
            if (dictionaries == null) {
                return this;
            }
            for (Map.Entry<String, String> entry : dictionaries.entrySet()) {
                dictionary(entry.getKey(), entry.getValue());
            }
            return this;
        }

        /**
         * Configures inline fallback for unsupported known types.
         *
         * @param inlineUnsupportedTypes whether inline fallback is enabled
         * @return this builder
         */
        public Builder inlineUnsupportedTypes(boolean inlineUnsupportedTypes) {
            this.inlineUnsupportedTypes = inlineUnsupportedTypes;
            return this;
        }

        /**
         * Creates an immutable snapshot of this builder.
         *
         * @return new export context
         */
        public ExportContext build() {
            return new ExportContext(this);
        }
    }
}
