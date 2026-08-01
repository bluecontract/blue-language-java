package blue.language.merge;

import blue.language.model.wire.BlueLanguageConstants;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import static blue.language.model.wire.BlueLanguageConstants.OBJECT_BLUE_ID;

/**
 * Tracks active type expansion by both BlueId and validation-path depth.
 *
 * <p>The depth-sensitive token rejects a hierarchy cycle at one logical path,
 * while the BlueId set identifies a recursive materialization boundary that
 * may safely retain a reference.</p>
 */
final class ActiveTypeStack {

    private final Set<Token> resolving = new HashSet<>();
    private final Set<String> materializingBlueIds = new HashSet<>();

    Token token(String blueId, int pathDepth) {
        return new Token(blueId, pathDepth);
    }

    boolean isResolving(Token token) {
        return resolving.contains(token);
    }

    boolean isMaterializing(String blueId) {
        return materializingBlueIds.contains(blueId);
    }

    void begin(Token token) {
        resolving.add(token);
        materializingBlueIds.add(token.blueId);
    }

    void finish(Token token) {
        resolving.remove(token);
        materializingBlueIds.remove(token.blueId);
    }

    /** One active type expansion at a deterministic validation-path depth. */
    static final class Token {
        private final String blueId;
        private final int pathDepth;

        private Token(String blueId, int pathDepth) {
            this.blueId = Objects.requireNonNull(blueId, OBJECT_BLUE_ID);
            this.pathDepth = pathDepth;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Token)) {
                return false;
            }
            Token token = (Token) other;
            return pathDepth == token.pathDepth
                    && blueId.equals(token.blueId);
        }

        @Override
        public int hashCode() {
            return 31 * blueId.hashCode() + pathDepth;
        }
    }
}
