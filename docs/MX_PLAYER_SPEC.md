# MX Player 3.0.13 — extracted spec (for the 1:1 clone)

Source: `D:\Soft\MX Player v3.0.13 (Premium).apk`, decompiled with jadx 1.5.6
and `aapt2 dump resources`. This file exists so we never have to re-decompile.

## 1. Engine (from `lib/arm64-v8a/`)

| Native lib | Role |
|---|---|
| `libffmpeg.mx.so` | software decode (FFmpeg) |
| `libmxmcdec.21.so` | hardware decode (MediaCodec wrapper) |
| `libmxass.so` + `libft2.mx.so` + `libfribidi.so` | styled .ass/.ssa subtitles |
| `libsmb2.so` | SMB network shares |
| `libmxvp.so` | player core |

=> MX = FFmpeg + MediaCodec + libass. Our FOSS equivalent is **libmpv**, which
bundles exactly FFmpeg + libass, available prebuilt as `dev.jdtech.mpv:libmpv`.

## 2. Theme — dark navy ("Aurora Dark", `mxskin__*__dark`)

| Role | Hex |
|---|---|
| activity background | `#16202b` |
| card / list item / divider | `#26374c` |
| disabled item bg | `#2d3d52` |
| primary text | `#dadde4` |
| secondary text | `#85929c` |
| accent blue | `#3c8cf0` |
| brand primary | `#1467cd` |
| colorAccent (legacy) | `#ff4081` |

Themes are `AuroraLight` / `AuroraThemeDark` / `AuroraThemeBlack`; the player
uses `ActionBarTheme.Playback.DarkNavy`. Typeface: **Muli** (`R.font.font_muli`).

## 3. Sorting mechanism (from `defpackage/D90.java`)

Sort = **field + direction**, and the direction *labels change per field*.
Menu order is exactly the order below. Default = Title, ascending.

| ID | Field | Default dir | Pref key | Asc label | Desc label |
|----|-------|-------------|----------|-----------|------------|
| 2  | Title       | asc  | `local_sorts_rule_title`       | A to Z    | Z to A     |
| 4  | Date        | desc | `local_sorts_rule_date`        | Oldest    | Newest     |
| 6  | Played time | desc | `local_sorts_rule_played_time` | Oldest    | Newest     |
| 7  | Status      | asc  | `local_sorts_rule_status`      | Ascending | Descending |
| 64 | Length      | desc | `local_sorts_rule_length`      | Shortest  | Longest    |
| 3  | Size        | desc | `local_sorts_rule_size`        | Smallest  | Largest    |
| 16 | Resolution  | desc | `local_sorts_rule_resolution`  | Lowest    | Highest    |
| 8  | Path        | desc | `local_sorts_rule_path`        | Ascending | Descending |
| 32 | Frame rate  | desc | `local_sorts_rule_frame_rate`  | Lowest    | Highest    |
| 5  | Type        | asc  | `local_sorts_rule_type`        | Ascending | Descending |

Sort row widget (`OptionsMenuSelectSortView`): label + up/down arrow; when
selected the pill background is tinted with the accent colour and the arrow
tint matches; unselected uses `mxskin__options_menu_sort_layout_unselected`.

## 4. View Options menu (strings `view_options_*`)

- **View Mode** — Files | Folders
- **Layout** — Grid | List
- **Sort** — the field+direction table above

## 5. Typography (styles.xml, NewText.*)

MX's UI typeface is **Muli** (`@font/font_muli`, `@font/font_muli_semibold`).
We bundle muli_regular/semibold/bold -- SIL OFL, see docs/FONT_LICENSE.md.

| Style | Size | Weight | Used for |
|-------|------|--------|----------|
| `NewText.Primary` | 15sp | Muli regular | folder title |
| `NewText.Medium.Primary` | 14sp | Muli SemiBold | file / video title |
| `NewText.Secondary` | 12sp | Muli regular | counts, paths, info lines |
| `NewText.Medium.Tag` | 10sp | Muli SemiBold, +0.05 tracking, white | duration / NEW badges |

## 6. Row + cell geometry (exact, from the layout XML)

### list_row_media.xml -- video row (List layout)
- root `minHeight` 54sp; content padding left 14dp, top/bottom 8dp
- thumbnail **96x56dp** in a CardView, `cardCornerRadius` 4dp, elevation 2dp,
  `scaleType=centerCrop`; centred placeholder glyph 32dp
- duration badge bottom-right, 4dp margin, `round_box_small`, tint `tag_dark`
- "NEW" badge top-left, 4dp margin, tint `tag_red`
- text block starts 8dp after the card; title 14sp max **3** lines,
  `lineSpacingExtra` 4dp, then 2dp gap, then the secondary info line
- overflow button 40x38dp, padding top/bottom 10dp, right 22dp

### list_row_listable.xml -- folder row (Folders view)
- folder icon `@dimen/directory_width` = **62dp**, 16dp left margin
- text block inset 16dp both sides; title 15sp max 2 lines, 2dp bottom margin
- count line below; optional rounded tag 4dp to its right
- path line below that, 2dp top margin, single line

### list_grid_media.xml -- grid cell (Grid layout)
- root padding top/bottom 4dp
- card `layout_constraintDimensionRatio` **1:0.564** (16:9), 4dp corners,
  2dp elevation, `cardUseCompatPadding`
- centred placeholder glyph **40dp** (larger than the list's 32dp)
- title 4dp below the card, right inset 18dp, `maxLines=2` **and
  `minLines=2`** so every cell is the same height, `lineSpacingExtra` 4dp
- overflow 38x28dp pinned top-right of the title

Badges use `round_box_small` = 2dp corner radius.

## 7. Key layouts to mirror

`activity_folder`, `list_grid_media`, `list_grid_listable`,
`layout_options_menu_view_list_item`, `layout_options_menu_fields_list_item`,
`tuner_screen_new` (player settings), `subtitle_panel`, `player_right_menu_layout`.
