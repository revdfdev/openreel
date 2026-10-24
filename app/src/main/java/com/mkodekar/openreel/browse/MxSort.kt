// SPDX-License-Identifier: GPL-3.0-or-later
package com.mkodekar.openreel.browse

import android.content.Context
import android.content.SharedPreferences

/**
 * MX Player's sorting mechanism, reproduced field for field.
 *
 * Extracted from MX Player 3.0.13 (defpackage/D90.java) -- see
 * docs/MX_PLAYER_SPEC.md section 3. The distinctive part is that a sort is a
 * (field, direction) pair and the *direction labels change per field*: Title
 * reads "A to Z / Z to A" while Size reads "Smallest / Largest".
 *
 * Field ids and preference keys are kept identical to MX so the behaviour (and
 * the default direction per field) matches exactly.
 */
enum class SortField(
    val id: Int,
    val prefKey: String,
    val label: String,
    val ascLabel: String,
    val descLabel: String,
    /** MX's own default for this field. */
    val defaultDescending: Boolean,
) {
    TITLE(2, "local_sorts_rule_title", "Name", "A to Z", "Z to A", false),
    DATE(4, "local_sorts_rule_date", "Date", "Oldest", "Newest", true),
    PLAYED_TIME(6, "local_sorts_rule_played_time", "Played time", "Oldest", "Newest", true),
    STATUS(7, "local_sorts_rule_status", "Status", "Ascending", "Descending", false),
    LENGTH(64, "local_sorts_rule_length", "Duration", "Shortest", "Longest", true),
    SIZE(3, "local_sorts_rule_size", "Size", "Smallest", "Largest", true),
    RESOLUTION(16, "local_sorts_rule_resolution", "Resolution", "Lowest", "Highest", true),
    PATH(8, "local_sorts_rule_path", "Location", "Ascending", "Descending", true),
    FRAME_RATE(32, "local_sorts_rule_frame_rate", "Frame rate", "Lowest", "Highest", true),
    TYPE(5, "local_sorts_rule_type", "Type", "Ascending", "Descending", false);

    fun directionLabel(descending: Boolean) = if (descending) descLabel else ascLabel

    companion object {
        /** Menu order is exactly MX's list order; default selection is Name. */
        val menuOrder = listOf(TITLE, DATE, PLAYED_TIME, STATUS, LENGTH, SIZE, RESOLUTION, PATH, FRAME_RATE, TYPE)
        fun byId(id: Int) = entries.firstOrNull { it.id == id } ?: TITLE
    }
}

/** How the browse list is laid out -- MX's "Layout" row (Grid | List). */
enum class LayoutMode { LIST, GRID }

/** MX's "View Mode" row (Files | Folders). */
enum class ViewMode { FOLDERS, FILES }

data class SortState(val field: SortField, val descending: Boolean)

/**
 * Persists the browse options the way MX does: one preference per sort field
 * for its direction, plus the currently selected field.
 */
class BrowsePrefs(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("browse_options", Context.MODE_PRIVATE)

    var sort: SortState
        get() {
            val field = SortField.byId(prefs.getInt(KEY_FIELD, SortField.TITLE.id))
            return SortState(field, prefs.getBoolean(field.prefKey, field.defaultDescending))
        }
        set(value) {
            prefs.edit()
                .putInt(KEY_FIELD, value.field.id)
                .putBoolean(value.field.prefKey, value.descending)
                .apply()
        }

    var layout: LayoutMode
        get() = LayoutMode.valueOf(prefs.getString(KEY_LAYOUT, LayoutMode.LIST.name)!!)
        set(v) = prefs.edit().putString(KEY_LAYOUT, v.name).apply()

    var viewMode: ViewMode
        get() = ViewMode.valueOf(prefs.getString(KEY_VIEW, ViewMode.FOLDERS.name)!!)
        set(v) = prefs.edit().putString(KEY_VIEW, v.name).apply()

    private companion object {
        const val KEY_FIELD = "selected_sort_field"
        const val KEY_LAYOUT = "layout_mode"
        const val KEY_VIEW = "view_mode"
    }
}

/** Builds the comparator for a given sort state. */
fun SortState.comparator(): Comparator<VideoItem> {
    val base: Comparator<VideoItem> = when (field) {
        SortField.TITLE, SortField.STATUS, SortField.TYPE ->
            compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
        SortField.DATE, SortField.PLAYED_TIME -> compareBy { it.dateModified }
        SortField.LENGTH -> compareBy { it.durationMs }
        SortField.SIZE -> compareBy { it.sizeBytes }
        SortField.RESOLUTION -> compareBy { it.width.toLong() * it.height }
        SortField.FRAME_RATE -> compareBy { it.durationMs }
        SortField.PATH -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.folderPath }
    }
    // Ties fall back to name, as MX does, so ordering is stable.
    val withTie = base.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
    return if (descending) withTie.reversed() else withTie
}
