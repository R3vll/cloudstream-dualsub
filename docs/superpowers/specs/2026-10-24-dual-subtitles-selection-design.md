# Dual Subtitle Selection & Clean Track UI Design

## Summary
Improve the Cloudstream player subtitle picker dialog so users can select dual subtitles (Primary and Secondary) directly with checkmarks in the Subtitles language list. Remove `[Sub1]` and `[Sub2]` square-bracket labels from track names, and eliminate the redundant "Set as Primary" and "Set as Secondary" buttons for a clean, direct-tap experience.

## Requirements
1. **Subtitles Language List (Middle Column)**:
   - Support multiple active checkmarks (using `CHOICE_MODE_MULTIPLE` or programmatic item-check state).
   - Display checkmarks for both Primary and Secondary selected languages (or "No Subtitles" if none).
   - Tap behavior (FIFO Replacement):
     - Tap "No Subtitles": Clears both subtitles; sets "No Subtitles" checked.
     - Tap unselected language:
       - If no Primary is set -> sets as Primary (1st checkmark).
       - If Primary is set but no Secondary -> sets as Secondary (2nd checkmark).
       - If both are set -> replaces Secondary with the new language.
     - Tap selected language:
       - Deselects that language. If Primary was deselected and Secondary exists, Secondary is promoted to Primary. If no subtitles remain, reverts to "No Subtitles".
2. **Subtitle Options List (Right Column)**:
   - Delete `[Sub1]` and `[Sub2]` square-bracket tags from subtitle track text.
   - Delete the duplicate string checkmark prefix (`✓ `).
   - Cleanly show track base name (e.g., `1`, `2`, `3`).
   - Selecting a track updates the specific track for whichever role (Primary or Secondary) belongs to the currently inspected language.
3. **Buttons & Layout**:
   - Remove `btn_set_primary_sub` and `btn_set_secondary_sub` buttons from `player_select_source_and_subs.xml`.
   - Remove associated button listeners from `GeneratorPlayer.kt`.
   - Keep only "Apply" and "Cancel" buttons.
4. **Applying Changes**:
   - On "Apply", apply both `primarySub` and `secondarySub` via `setDualPreferredSubtitles(...)`.

