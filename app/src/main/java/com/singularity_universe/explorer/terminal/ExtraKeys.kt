package com.singularity_universe.explorer.terminal

import android.content.Context
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray

/**
 * A button in the terminal's extra keys. Saved by [id]:
 * a named key ("esc", "up", "f5"), a modifier ("ctrl", "alt"), a Ctrl shortcut ("^c"), or
 * text the user chose ("text:sudo ").
 */
sealed class ExtraKey(val id: String, val label: String) {
    /** A key with a key code: arrows, Esc, F1... (sticky Ctrl/Alt apply). */
    class Special(id: String, label: String, val keyCode: Int) : ExtraKey(id, label)

    /** Sticky Ctrl or Alt: applies to the next key typed. */
    class Modifier(id: String, label: String) : ExtraKey(id, label)

    /** Ctrl + a letter in one tap, e.g. ^C to stop a command. */
    class Control(id: String, val letter: Char) : ExtraKey(id, "^${letter.uppercaseChar()}")

    /** An empty slot, to leave gaps in the layout (e.g. three gaps then Del). Can appear many times. */
    data object Gap : ExtraKey("gap", "")

    /** Types [text] (one character or a whole word). */
    class Text(val text: String) : ExtraKey("text:$text", text.replace("\n", "↵").ifBlank { "␣" })

    companion object {
        private val specials = listOf(
            Special("esc", "Esc", KeyEvent.KEYCODE_ESCAPE),
            Special("tab", "Tab", KeyEvent.KEYCODE_TAB),
            Special("enter", "Enter", KeyEvent.KEYCODE_ENTER),
            Special("bksp", "⌫", KeyEvent.KEYCODE_DEL),
            Special("del", "Del", KeyEvent.KEYCODE_FORWARD_DEL),
            Special("ins", "Ins", KeyEvent.KEYCODE_INSERT),
            Special("left", "←", KeyEvent.KEYCODE_DPAD_LEFT),
            Special("up", "↑", KeyEvent.KEYCODE_DPAD_UP),
            Special("down", "↓", KeyEvent.KEYCODE_DPAD_DOWN),
            Special("right", "→", KeyEvent.KEYCODE_DPAD_RIGHT),
            Special("home", "Home", KeyEvent.KEYCODE_MOVE_HOME),
            Special("end", "End", KeyEvent.KEYCODE_MOVE_END),
            Special("pgup", "PgUp", KeyEvent.KEYCODE_PAGE_UP),
            Special("pgdn", "PgDn", KeyEvent.KEYCODE_PAGE_DOWN),
        ) + (1..12).map { Special("f$it", "F$it", KeyEvent.KEYCODE_F1 + it - 1) }

        private val modifiers = listOf(Modifier("ctrl", "Ctrl"), Modifier("alt", "Alt"))

        /** The key saved as [id], or null if it's unknown (e.g. from a newer version). */
        fun fromId(id: String): ExtraKey? = when {
            id == Gap.id -> Gap
            id.startsWith("text:") -> id.removePrefix("text:").takeIf { it.isNotEmpty() }?.let(::Text)
            id.length == 2 && id[0] == '^' && id[1].isLetter() -> Control(id, id[1].lowercaseChar())
            else -> specials.firstOrNull { it.id == id } ?: modifiers.firstOrNull { it.id == id }
        }

        private fun ids(vararg ids: String) = ids.mapNotNull(::fromId)

        /** What the settings offer, by group. */
        val catalog: List<Pair<String, List<ExtraKey>>> = listOf(
            "Modifiers" to modifiers,
            "Navigation" to ids("left", "up", "down", "right", "home", "end", "pgup", "pgdn"),
            "Editing" to ids("esc", "tab", "enter", "bksp", "del", "ins"),
            "Shortcuts" to "cvdzxorlaeuwk".map { Control("^$it", it) },
            "Characters" to "-/|~*&<>\\_=:;'\"`\$#@!?()[]{}^%.".map { Text(it.toString()) },
            "Function keys" to (1..12).mapNotNull { fromId("f$it") },
        )

        /** Laid out for a 3-column panel: arrows as a block, shortcuts last. */
        val defaults: List<String> = listOf(
            "esc", "ctrl", "alt",
            "tab", "up", "del",
            "left", "down", "right",
            "home", "end", "pgup",
            "text:~", "pgdn", "text:/",
            "text:-", "text:|", "text:*",
            "text:&", "text:>", "text:<",
            "^c", "^v", "^x",
        )
    }
}

/** Where the extra keys go: Auto puts them in a side panel on tablets and under the terminal on phones. */
enum class KeysPosition(val label: String) { Auto("Auto"), Bottom("Bottom"), Side("Side panel") }

/** The user's extra keys and their placement, saved across sessions. Observable by Compose. */
class ExtraKeysSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("explorer", Context.MODE_PRIVATE)

    var keys by mutableStateOf(load())
        private set

    /** Keys per row in the side panel. */
    var columns by mutableStateOf(prefs.getInt(KEY_COLUMNS, DEFAULT_COLUMNS).coerceIn(2, 5))
        private set

    var position by mutableStateOf(
        runCatching { KeysPosition.valueOf(prefs.getString(KEY_POSITION, null)!!) }.getOrDefault(KeysPosition.Auto)
    )
        private set

    /** Keys on the left: the side panel on the left, and the row starting from the left edge. */
    var leftHanded by mutableStateOf(prefs.getBoolean(KEY_LEFT_HANDED, false))
        private set

    fun updateKeys(ids: List<String>) {
        // Each key once; empty slots as often as wanted.
        val seen = HashSet<String>()
        val cleaned = ids.filter { it == ExtraKey.Gap.id || seen.add(it) }
        keys = cleaned.mapNotNull(ExtraKey::fromId)
        prefs.edit().putString(KEY_KEYS, JSONArray(cleaned).toString()).apply()
    }

    fun updateColumns(value: Int) {
        columns = value.coerceIn(2, 5)
        prefs.edit().putInt(KEY_COLUMNS, columns).apply()
    }

    fun updatePosition(value: KeysPosition) {
        position = value
        prefs.edit().putString(KEY_POSITION, value.name).apply()
    }

    fun updateLeftHanded(value: Boolean) {
        leftHanded = value
        prefs.edit().putBoolean(KEY_LEFT_HANDED, value).apply()
    }

    fun reset() {
        updateKeys(ExtraKey.defaults)
        updatePosition(KeysPosition.Auto)
        updateLeftHanded(false)
        updateColumns(DEFAULT_COLUMNS)
    }

    private fun load(): List<ExtraKey> {
        val saved = prefs.getString(KEY_KEYS, null)
        var ids = saved?.let { runCatching { JSONArray(it).let { a -> List(a.length()) { i -> a.getString(i) } } }.getOrNull() }
            ?: ExtraKey.defaults
        // ^V (paste in many programs) arrived later: once, put it right after ^C in saved layouts.
        if (saved != null && !prefs.getBoolean(KEY_ADDED_CTRL_V, false)) {
            val afterCtrlC = ids.indexOf("^c")
            if (afterCtrlC >= 0 && "^v" !in ids) {
                ids = ids.toMutableList().apply { add(afterCtrlC + 1, "^v") }
                prefs.edit().putString(KEY_KEYS, JSONArray(ids).toString()).apply()
            }
            prefs.edit().putBoolean(KEY_ADDED_CTRL_V, true).apply()
        }
        return ids.mapNotNull(ExtraKey::fromId)
    }

    private companion object {
        const val KEY_KEYS = "terminal_extra_keys"
        const val KEY_POSITION = "terminal_extra_keys_position"
        const val KEY_LEFT_HANDED = "terminal_extra_keys_left_handed"
        const val KEY_COLUMNS = "terminal_extra_keys_columns"
        const val DEFAULT_COLUMNS = 3
        const val KEY_ADDED_CTRL_V = "terminal_extra_keys_added_ctrl_v"
    }
}
