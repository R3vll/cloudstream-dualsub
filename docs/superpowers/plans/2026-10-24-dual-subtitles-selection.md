# Dual Subtitle Selection & Clean Track UI Implementation Plan

> **Goal:** Enable dual checkmarks for Primary & Secondary subtitle languages in Cloudstream, remove `[Sub1]` and `[Sub2]` brackets, remove bottom "Set as..." buttons, and provide direct tap selection.

## Proposed Changes

### 1. Layout Updates
#### `app/src/main/res/layout/player_select_source_and_subs.xml`
- Remove the `btn_set_primary_sub` and `btn_set_secondary_sub` MaterialButtons.
- Clean up any unused space in `apply_btt_holder`.

### 2. Player Subtitle Selection Logic
#### `app/src/main/java/com/lagradost\cloudstream3\ui\player\GeneratorPlayer.kt`
- Initialize local state:
  - `var primarySub: SubtitleData? = currentSelectedSubtitles`
  - `var secondarySub: SubtitleData? = secondarySubtitleData`
- Configure `subtitleList`:
  - Change `choiceMode` to `AbsListView.CHOICE_MODE_MULTIPLE`.
  - Add `updateSubtitleLanguageCheckmarks()` helper to set checked items according to `primarySub` and `secondarySub`.
  - In `setOnItemClickListener`:
    - Handle item 0 ("No Subtitles"): Clears both `primarySub` and `secondarySub`.
    - Handle language items (> 0):
      - If tapped language is already `primarySub`: deselect it; if `secondarySub` exists, promote it to `primarySub` and set `secondarySub = null`.
      - If tapped language is already `secondarySub`: deselect it; set `secondarySub = null`.
      - If neither: if `primarySub == null`, set as `primarySub`. Else if `secondarySub == null`, set as `secondarySub`. Else (both set), replace `secondarySub` with this language.
      - Refresh checkmarks and option list.
- In `updateSubtitleOptionList()`:
  - Remove `"✓ "` string prepending.
  - Remove `" [Sub1]"` and `" [Sub2]"` string appending.
  - Show clean base name for each track option.
  - Set checked state for the track matching `primarySub` or `secondarySub`.
- In `subtitleOptionList.setOnItemClickListener`:
  - Assign chosen track to `primarySub` if the viewed language is `primarySub`, or `secondarySub` if viewed language is `secondarySub`.
- In `binding.applyBtt.setOnClickListener`:
  - Set `currentSelectedSubtitles = primarySub`
  - Set `secondarySubtitleData = secondarySub`
  - Call `player.setDualPreferredSubtitles(primarySub, secondarySub)`
  - Handle audio/sub loading and dismiss dialog.
- Remove listener bindings for removed buttons (`btnSetPrimarySub`, `btnSetSecondarySub`).

## Verification Plan
1. Compile check with `./gradlew compileDebugKotlin` to ensure syntax, binding references, and null-safety pass cleanly.
2. Code review of checkmark rendering and state transitions against the spec.

