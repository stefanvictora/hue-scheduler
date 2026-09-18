# Preserve definition semantics in scene flags

Scene Schedules represent property-free State Definitions explicitly with `gap`, rather than rejecting their migration or interpreting the bridge's required light actions as scheduled values. A gap ignores stored actions throughout discovery, action changes, and membership updates; migration initializes those actions to off, while manual Scene Recall retains its normal meaning and may therefore turn lights off. The Hue app may require a saved scene to include an on light before allowing edits; changing these placeholder actions does not change the Schedule Gap.

Interpolation uses `i` for an explicit true value, `i:false` for an explicit false value, and the global default when neither is present. Migration preserves explicit false so `--interpolate-all` cannot silently change a migrated definition; the value-bearing option follows existing `tr:` and `tr-b:` syntax while retaining the compact `i` shorthand.
