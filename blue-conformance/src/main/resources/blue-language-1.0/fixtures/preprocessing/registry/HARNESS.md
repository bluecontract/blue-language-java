# Conformance-only preprocessing transformation registry

The Language fixture harness registers the exact transformation type BlueIds in
`manifest.yaml` only while executing this fixture package.

## Rename Root Field Transformation

Configuration:

```yaml
type:
  blueId: 7kEewGH6vogsgUXw3Gdyi73rtb5oQK1L8LWtHbYcG7pB
from: <Text>
to: <Text>
```

The transformation requires an object Source root, an existing direct field
named by `from`, and no direct field named by `to`. It moves the exact Source
child from `from` to `to` and otherwise preserves the root. Missing source,
existing destination, non-Text configuration, or non-object root fails.

## Set Root Field Transformation

Configuration:

```yaml
type:
  blueId: DS4rHtvxTg1S3tn6e9ciuMNDkeEQtk4VTdiw8KUCfMAu
field: <Text>
value: <any Source node>
```

The transformation writes a defensive copy of `value` to the direct root field
named by `field`, replacing any previous value. A non-object root or non-Text
`field` fails.

## Append Root Text Transformation

Configuration:

```yaml
type:
  blueId: D2DxVxaddbJT5k2YYj3sG35cSdotw1Mz9GpxrdP43nzN
field: <Text>
suffix: <Text>
```

The transformation requires a direct root field whose Source scalar value is
Text. It appends `suffix` exactly once. Missing field, non-Text source value,
non-Text configuration, or non-object root fails.

All three transformations are pure. They operate after the root `blue` field is
removed and before mandatory baseline preprocessing. Their invocation order is
the declared `transformations` list order.
