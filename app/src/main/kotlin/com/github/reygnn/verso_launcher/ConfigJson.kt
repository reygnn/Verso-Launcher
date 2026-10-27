package com.github.reygnn.verso_launcher

import org.json.JSONObject
import java.io.InputStream

/**
 * The JSON config of earlier versions (verso.json, their backups) -> [VersoConfig], pure: read,
 * never written any more. The parsers return null instead of throwing.
 */
internal object ConfigJson {

    /** Lenient (a missing section is empty): only for our own file — outside files use [parseForeign]. */
    // null is the answer: unparseable
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun parse(text: String): VersoConfig? = try {
        parseObject(JSONObject(text))
    } catch (e: Exception) {
        null
    } catch (e: StackOverflowError) {
        // Android's org.json has no depth limit: "[[[…" ~100 000 deep overflows the stack
        // (seen on a device), and an Error would pass the catch above.
        null
    }

    /**
     * For a file from outside the app: leniently, any JSON object ("{}", some package.json)
     * would become an empty config and wipe everything on adoption. So at least one known
     * section must be present with the right type (empty sections are fine).
     */
    // null is the answer: not a config
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun parseForeign(text: String): VersoConfig? = try {
        val j = JSONObject(text)
        val looksLikeConfig = j.optJSONArray("hidden") != null ||
            j.optJSONArray("favorites") != null ||
            j.optJSONObject("names") != null ||
            j.optJSONObject("tags") != null
        if (looksLikeConfig && withinLimits(j)) parseObject(j) else null
    } catch (e: Exception) {
        null
    } catch (e: StackOverflowError) {
        null
    }

    /** A real verso.json is a few KB; a file this big is not a config, and is not read into memory. */
    private const val MAX_FILE_BYTES = 1024 * 1024

    /** [input] as text, or null when it holds more than [maxBytes] (one byte past the cap is read to tell). */
    fun readCapped(input: InputStream, maxBytes: Int = MAX_FILE_BYTES): String? {
        val bytes = input.readNBytes(maxBytes + 1)
        return if (bytes.size > maxBytes) null else bytes.toString(Charsets.UTF_8)
    }

    const val ORDER_NAME = "name"
    const val ORDER_USAGE = "usage"

    const val MAX_ENTRIES = 5_000

    const val MAX_TAGS_PER_APP = 100

    /** A file beyond these limits is not a config: refused whole, not truncated. */
    private fun withinLimits(j: JSONObject): Boolean {
        val sizes = listOf(
            j.optJSONArray("hidden")?.length() ?: 0,
            j.optJSONArray("favorites")?.length() ?: 0,
            j.optJSONObject("names")?.length() ?: 0,
            j.optJSONObject("tags")?.length() ?: 0,
        )
        if (sizes.any { it > MAX_ENTRIES }) return false
        val tags = j.optJSONObject("tags") ?: return true
        for (k in tags.keys()) {
            if ((tags.optJSONArray(k)?.length() ?: 0) > MAX_TAGS_PER_APP) return false
        }
        return true
    }

    private fun parseObject(j: JSONObject): VersoConfig {
        val hidden = LinkedHashSet<String>()
        // A set while reading: a favorite listed twice is one favorite.
        val favorites = LinkedHashSet<String>()
        val names = LinkedHashMap<String, String>()
        val tags = LinkedHashMap<String, List<String>>()
        // opt() as? String, not getString(): Android's getString coerces 123 to "123".
        j.optJSONArray("hidden")?.let {
            for (i in 0 until it.length()) (it.opt(i) as? String)?.let { s -> hidden += s }
        }
        j.optJSONArray("favorites")?.let {
            for (i in 0 until it.length()) (it.opt(i) as? String)?.let { s -> favorites += s }
        }
        j.optJSONObject("names")?.let { o ->
            for (k in o.keys()) {
                val name = (o.opt(k) as? String)?.let(LauncherLogic::cleanName) ?: continue
                if (name.isNotEmpty()) names[k] = name
            }
        }
        j.optJSONObject("tags")?.let { o ->
            for (k in o.keys()) {
                val arr = o.optJSONArray(k) ?: continue
                val list = LinkedHashSet<String>()
                // Canonical for every loaded file, not only app-written ones; duplicates must
                // collapse, or toggleTag (removes the first only) could never un-tick the app.
                for (i in 0 until arr.length()) {
                    val raw = arr.opt(i) as? String ?: continue
                    val tag = LauncherLogic.canonicalTag(raw)
                    if (tag.isNotEmpty()) list += tag
                }
                if (list.isNotEmpty()) tags[k] = list.toList()
            }
        }
        // Absent (an older file) or unknown order: A to Z.
        return VersoConfig(hidden, favorites.toList(), names, tags, byUsage = j.opt("order") == ORDER_USAGE)
    }
}
