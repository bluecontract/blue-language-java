package blue.buildlogic.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable, path-ordered identity of a set of source inputs. */
public final class SourceSnapshot {

    private final String identity;
    private final List<Entry> entries;

    public SourceSnapshot(String identity, List<Entry> entries) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
    }

    public String getIdentity() {
        return identity;
    }

    public List<Entry> getEntries() {
        return entries;
    }

    /** One regular-file input and its content identity. */
    public static final class Entry {

        private final String path;
        private final String identity;

        public Entry(String path, String identity) {
            this.path = Objects.requireNonNull(path, "path");
            this.identity = Objects.requireNonNull(identity, "identity");
        }

        public String getPath() {
            return path;
        }

        public String getIdentity() {
            return identity;
        }
    }
}
