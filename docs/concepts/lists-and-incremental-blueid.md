# Lists and incremental BlueId calculation

List identity is a recursive prefix fold:

```text
L0 = id([])
Ln = FOLD_LIST_ID(Ln-1, id(elementN))
id([a1, ..., an]) = Ln
```

There is no second incremental identity algorithm. Appending is exactly one
normative fold step using the established prefix BlueId and the new element
BlueId; it does not require the content of earlier elements.

```java
import blue.language.identity.CanonicalJsonHasher;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.identity.ListBlueIdFold;
import blue.language.model.Node;

public final class IncrementalListBlueIdExample {
    public static void main(String[] args) {
        ListBlueIdFold fold = new ListBlueIdFold(
                new CanonicalJsonHasher());
        DirectBlueIdCalculator direct = new DirectBlueIdCalculator();

        String prefix = fold.seedBlueId();
        String first = direct.directBlueId(new Node().value("a"));
        String second = direct.directBlueId(new Node().value("b"));

        prefix = fold.appendBlueId(prefix, first);
        String incremental = fold.appendBlueId(prefix, second);
        String complete = direct.directBlueId(
                java.util.Arrays.asList(
                        new Node().value("a"),
                        new Node().value("b")));

        if (!complete.equals(incremental)) {
            throw new AssertionError("List fold diverged");
        }
    }
}
```

Replacing element `i` keeps the established accumulator immediately before
`i`, then recomputes the changed element and every following suffix step.
Appending `k` elements therefore performs exactly `k` fold steps.

Inline elements and pure references both contribute their exact element
BlueId. List metadata belongs to the enclosing node identity and is rebuilt
after the final payload fold. A digest never implies storage location,
fragment availability, or provider metadata.
