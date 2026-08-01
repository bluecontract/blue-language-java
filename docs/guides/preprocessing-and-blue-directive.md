# Preprocessing and the `blue` directive

Preprocessing converts human-authored Source into the portable preprocessed
document consumed by resolution. It is deterministic and input-preserving.

```yaml
blue:
  imports:
    Message:
      blueId: 8msg...textType
  transformations:
    - type:
        blueId: 2first...transform
    - type:
        blueId: 3second...transform
type: Message
value: hello
```

## Exact order

1. Resolve and validate the root directive.
2. Resolve and freeze imports and transformation entries.
3. Preflight every transformation before running any transformation.
4. Clone Source and remove `blue`.
5. Execute each frozen transformation once in declaration order.
6. Run baseline wrapper normalization, alias substitution, primitive
   inference, and final validation.

The mandatory baseline is an algorithm stage. It is not an implicit directive
and cannot reorder custom transformations.

## Imports

An import maps an authored name to an exact Blue reference. The mapping is
frozen during preflight, so transformations cannot change the meaning of a
later alias. Referenced directive/import evidence is verified in the configured
Source environment.

## Transformations

A transformation is selected by exact type identity and runs through an
explicit registry. It must be deterministic, must not mutate the caller's
Source, and must not consult time, locale, random state, classpath scan order,
or ambient I/O. If any entry is unavailable or invalid, no transformation
runs.

`PreprocessingDirectiveExample` in `:examples` proves import substitution,
ordered execution, directive removal, and unchanged input. See the
[Language pipeline](../architecture/language-pipeline.md).
