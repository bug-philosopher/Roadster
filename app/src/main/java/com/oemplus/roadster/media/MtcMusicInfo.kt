package com.oemplus.roadster.media

internal object MtcMusicInfo {
    data class Info(val title: String? = null, val artist: String? = null, val album: String? = null) {
        val empty get() = title == null && artist == null && album == null
    }

    private const val LABELS = "title|name|song|track|artist|singer|author|album"
    private val labelled = Regex(
        "(?i)[\"']?\\b($LABELS)\\b[\"']?\\s*[=:]\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|([^,;|\\n\\r}]*))",
    )
    private val startsLabelled = Regex("(?i)^[{\\[\\s\"']*($LABELS)\\b[\"']?\\s*[=:]")
    private val numeric = Regex("[0-9:./ ]+")
    private val placeholders = setOf("unknown", "<unknown>", "unknow", "null", "n/a", "none", "未知", "-", "--")

    fun parse(raw: String?): Info {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return Info()

        if (startsLabelled.containsMatchIn(s)) {
            val found = HashMap<String, String?>()
            for (m in labelled.findAll(s)) {
                val slot = when (m.groupValues[1].lowercase()) {
                    "title", "name", "song", "track" -> "title"
                    "artist", "singer", "author" -> "artist"
                    else -> "album"
                }
                val value = (m.groups[2]?.value?.replace("\\\"", "\"") ?: m.groupValues[3])
                if (slot !in found) found[slot] = clean(value)
            }
            val info = Info(found["title"], found["artist"], found["album"])
            if (!info.empty) return info
        }

        val separators = listOf<(Char) -> Boolean>(
            { it < ' ' || it == '\u007F' || it == '\u00FF' || it == '\uFFFD' },
            { it == '|' },
            { it == ';' },
            { it == '^' },
        )
        for (isSeparator in separators) {
            val parts = split(s, isSeparator)
            if (parts.size >= 2) return fromFields(parts)
        }
        return Info(title = clean(s))
    }

    private fun fromFields(parts: List<String>): Info {
        val title = clean(parts[0])
        val rest = parts.drop(1).filterNot { it.isNotBlank() && numeric.matches(it.trim()) }
        return Info(title, clean(rest.getOrNull(0)), clean(rest.getOrNull(1)))
    }

    private fun split(s: String, isSeparator: (Char) -> Boolean): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (ch in s) {
            if (isSeparator(ch)) {
                out += cur.toString()
                cur.setLength(0)
            } else {
                cur.append(ch)
            }
        }
        out += cur.toString()
        return out
    }

    private fun clean(v: String?): String? {
        val t = v?.trim()?.trim('"', '\'')?.trim().orEmpty()
        return t.takeIf { it.isNotEmpty() && it.lowercase() !in placeholders }
    }
}
