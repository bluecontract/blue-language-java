package blue.language.dictionary;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class ExportContext {

    private final Map<String, String> dictionaries;
    private final boolean inlineUnsupportedTypes;

    private ExportContext(Builder builder) {
        this.dictionaries = Collections.unmodifiableMap(new LinkedHashMap<>(builder.dictionaries));
        this.inlineUnsupportedTypes = builder.inlineUnsupportedTypes;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static ExportContext empty() {
        return builder().build();
    }

    public Map<String, String> dictionaries() {
        return dictionaries;
    }

    public Optional<String> dictionaryBlueId(String dictionaryName) {
        return Optional.ofNullable(dictionaries.get(dictionaryName));
    }

    public boolean inlineUnsupportedTypes() {
        return inlineUnsupportedTypes;
    }

    public static final class Builder {
        private final Map<String, String> dictionaries = new LinkedHashMap<>();
        private boolean inlineUnsupportedTypes = true;

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

        public Builder dictionaries(Map<String, String> dictionaries) {
            if (dictionaries == null) {
                return this;
            }
            for (Map.Entry<String, String> entry : dictionaries.entrySet()) {
                dictionary(entry.getKey(), entry.getValue());
            }
            return this;
        }

        public Builder inlineUnsupportedTypes(boolean inlineUnsupportedTypes) {
            this.inlineUnsupportedTypes = inlineUnsupportedTypes;
            return this;
        }

        public ExportContext build() {
            return new ExportContext(this);
        }
    }
}
