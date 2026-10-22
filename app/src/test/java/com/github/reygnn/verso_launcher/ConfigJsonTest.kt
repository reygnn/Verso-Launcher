package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain JVM: the REAL org.json is on the test classpath — android.jar's is a throwing stub. */
class ConfigJsonTest {

    private fun config(
        hidden: List<String> = emptyList(),
        favorites: List<String> = emptyList(),
        names: Map<String, String> = emptyMap(),
        tags: Map<String, List<String>> = emptyMap(),
    ) = VersoConfig(hidden.toSet(), favorites.toSet(), names, tags)

    @Test fun `round-trips a full config`() {
        val original = config(
            hidden = listOf("com.a/.A", "com.b/.B"),
            favorites = listOf("com.f2/.F", "com.f1/.F", "com.f3/.F"),
            names = mapOf("com.a/.A" to "Alpha", "com.b/.B" to "Beta"),
            tags = mapOf("com.a/.A" to listOf("work", "fun"), "com.b/.B" to listOf("games")),
        )
        val parsed = ConfigJson.parse(ConfigJson.serialize(original))!!
        assertEquals(original.hidden, parsed.hidden)
        assertEquals(original.favorites.toList(), parsed.favorites.toList())
        assertEquals(original.names, parsed.names)
        assertEquals(original.tags, parsed.tags)
    }

    @Test fun `the order round-trips, and an older or unknown one is A to Z`() {
        val byUsage = VersoConfig(byUsage = true)
        assertTrue(ConfigJson.parse(ConfigJson.serialize(byUsage))!!.byUsage)
        assertFalse(ConfigJson.parse(ConfigJson.serialize(VersoConfig()))!!.byUsage)
        assertFalse(ConfigJson.parse("""{"favorites":[]}""")!!.byUsage)
        assertFalse(ConfigJson.parse("""{"order":"USAGE"}""")!!.byUsage)
        assertFalse(ConfigJson.parse("""{"order":1}""")!!.byUsage)
    }

    @Test fun `an order alone does not make a foreign file a config`() {
        assertNull(ConfigJson.parseForeign("""{"order":"usage"}"""))
        assertTrue(ConfigJson.parseForeign("""{"favorites":[],"order":"usage"}""")!!.byUsage)
    }

    @Test fun `round-trips an empty config`() {
        val parsed = ConfigJson.parse(ConfigJson.serialize(VersoConfig()))!!
        assertTrue(parsed.hidden.isEmpty())
        assertTrue(parsed.favorites.isEmpty())
        assertTrue(parsed.names.isEmpty())
        assertTrue(parsed.tags.isEmpty())
    }

    @Test fun `round-trips tags, preserving per-key order`() {
        val original = config(tags = mapOf("com.a/.A" to listOf("z", "a", "m")))
        val parsed = ConfigJson.parse(ConfigJson.serialize(original))!!
        assertEquals(listOf("z", "a", "m"), parsed.tags["com.a/.A"])
    }

    @Test fun `an empty tag list is not materialized on parse`() {
        // serialize never writes [], but a hand-edited file might.
        val parsed = ConfigJson.parse("""{"tags":{"com.a/.A":[]}}""")!!
        assertTrue(parsed.tags.isEmpty())
    }

    @Test fun `round-trips tags with unicode and special characters`() {
        // Already lowercase: tags are stored ROOT-folded.
        val original = config(tags = mapOf("com.a/.A" to listOf("café", "c/c++", "a b")))
        val parsed = ConfigJson.parse(ConfigJson.serialize(original))!!
        assertEquals(original.tags, parsed.tags)
    }

    @Test fun `parse folds tag case so a hand-edited tag still matches the filter`() {
        val parsed = ConfigJson.parse("""{"tags":{"com.a/.A":["Work","GAMES"]}}""")!!
        assertEquals(listOf("work", "games"), parsed.tags["com.a/.A"])
    }

    @Test fun `preserves favorite order (the rank)`() {
        val original = config(favorites = listOf("z", "a", "m"))
        val parsed = ConfigJson.parse(ConfigJson.serialize(original))!!
        assertEquals(listOf("z", "a", "m"), parsed.favorites.toList())
    }

    @Test fun `malformed input returns null`() {
        assertNull(ConfigJson.parse("not json"))
        assertNull(ConfigJson.parse(""))
        assertNull(ConfigJson.parse("[1,2,3]"))
    }

    @Test fun `missing keys yield empty sections, not null`() {
        val parsed = ConfigJson.parse("{}")!!
        assertTrue(parsed.hidden.isEmpty())
        assertTrue(parsed.favorites.isEmpty())
        assertTrue(parsed.names.isEmpty())
    }

    @Test fun `a partial file loads what it can`() {
        val parsed = ConfigJson.parse("""{"favorites":["x","y"]}""")!!
        assertEquals(listOf("x", "y"), parsed.favorites.toList())
        assertTrue(parsed.hidden.isEmpty())
        assertTrue(parsed.names.isEmpty())
    }

    @Test fun `non-string list elements and map values are dropped, not coerced`() {
        // Android's getString() would coerce 123 to "123" and throw on an object; opt() as? String drops both.
        val parsed = ConfigJson.parse(
            """{"hidden":[123,"com.a/A"],"favorites":[true,"com.f/F"],"names":{"com.b/B":456,"com.c/C":"Real"}}""",
        )!!
        assertEquals(listOf("com.a/A"), parsed.hidden.toList())
        assertEquals(listOf("com.f/F"), parsed.favorites.toList())
        assertEquals(mapOf("com.c/C" to "Real"), parsed.names)
    }

    @Test fun `round-trips names with quotes, backslashes and unicode`() {
        val tricky = mapOf(
            "com.a/.A" to "Wörk \"Gmail\"",
            "com.b/.B" to "back\\slash",
            "com.c/.C" to "絵文字 😀",
        )
        val parsed = ConfigJson.parse(ConfigJson.serialize(config(names = tricky)))!!
        assertEquals(tricky, parsed.names)
    }

    @Test fun `control characters in a stored name are dropped on load`() {
        val parsed = ConfigJson.parse(ConfigJson.serialize(config(names = mapOf("com.b/.B" to "tab\tand\nnewline"))))!!
        assertEquals("tabandnewline", parsed.names["com.b/.B"])
    }

    @Test fun `truncated JSON returns null - the torn-write recovery trigger`() {
        assertNull(ConfigJson.parse("{\"favorites\":[\"x\""))
        assertNull(ConfigJson.parse("{"))
        assertNull(ConfigJson.parse("   "))
    }

    @Test fun `duplicate favorites in the file are de-duplicated, first position wins`() {
        val parsed = ConfigJson.parse("""{"favorites":["x","y","x"]}""")!!
        assertEquals(listOf("x", "y"), parsed.favorites.toList())
    }
    // ---- hostile restore files -----------------------------------------------

    /** What parseForeign adopts must be what verso.json and every backup say once written as UTF-8. */
    @Test fun `a lone surrogate in a restored file does not survive into memory`() {
        val raw = """{"favorites":["a/b"],"names":{"a/b":"N\ud800"},"tags":{"a/b":["x\udc00"]}}"""
        val adopted = ConfigJson.parseForeign(raw)!!
        assertEquals("N", adopted.names["a/b"])
        assertEquals(listOf("x"), adopted.tags["a/b"])
        val onDisk = String(ConfigJson.serialize(adopted).toByteArray(Charsets.UTF_8), Charsets.UTF_8)
        assertEquals(adopted, ConfigJson.parse(onDisk))
    }

    @Test fun `a restored name loses its look-alike characters`() {
        val c = ConfigJson.parseForeign("""{"names":{"a/A":"Sig\u200Bnal\u202E"}}""")!!
        assertEquals("Signal", c.names["a/A"])
    }

    @Test fun `a restored name that is nothing but invisible characters is dropped`() {
        val c = ConfigJson.parseForeign("""{"names":{"a/A":"\u200B\u202E"}}""")!!
        assertTrue(c.names.isEmpty())
    }

    @Test fun `a file with more entries than any real config is refused whole`() {
        val many = (1..ConfigJson.MAX_ENTRIES + 1).joinToString(",") { "\"p/$it\"" }
        assertNull(ConfigJson.parseForeign("""{"hidden":[$many]}"""))
        val ok = (1..ConfigJson.MAX_ENTRIES).joinToString(",") { "\"p/$it\"" }
        assertEquals(ConfigJson.MAX_ENTRIES, ConfigJson.parseForeign("""{"hidden":[$ok]}""")!!.hidden.size)
    }

    @Test fun `a file with too many tags on one app is refused whole`() {
        val tags = (1..ConfigJson.MAX_TAGS_PER_APP + 1).joinToString(",") { "\"t$it\"" }
        assertNull(ConfigJson.parseForeign("""{"tags":{"a/A":[$tags]}}"""))
    }

    // ---- hostile nesting ----------------------------------------------------

    /** Android's org.json overflows the stack here (an Error); this JVM one throws. Either way: null. */
    @Test fun `an absurdly nested document is refused, not thrown`() {
        val deep = "{\"favorites\":" + "[".repeat(200_000) + "]".repeat(200_000) + "}"
        assertNull(ConfigJson.parse(deep))
        assertNull(ConfigJson.parseForeign(deep))
    }

    // ---- parseForeign -------------------------------------------------------

    /** Adopting it would replace the whole config with an empty one. */
    @Test
    fun `foreign JSON without a single known section is rejected`() {
        assertNull(ConfigJson.parseForeign("{}"))
        assertNull(ConfigJson.parseForeign("""{"name":"thing","version":"1.0.0"}"""))
        assertNull(ConfigJson.parseForeign("""{"favourites":["a/b"]}"""))  // British spelling
        assertNull(ConfigJson.parseForeign("[]"))
        assertNull(ConfigJson.parseForeign("not json at all"))
        assertNull(ConfigJson.parseForeign(""))
    }

    @Test
    fun `known keys of the wrong type do not qualify`() {
        assertNull(ConfigJson.parseForeign("""{"hidden":true,"names":"nope"}"""))
    }

    @Test
    fun `known keys of the wrong container shape do not qualify`() {
        assertNull(ConfigJson.parseForeign("""{"favorites":{},"hidden":{},"names":[],"tags":[]}"""))
    }

    @Test
    fun `a single known section is enough to accept`() {
        val onlyFavorites = ConfigJson.parseForeign("""{"favorites":["pkg/Act"]}""")
        assertNotNull(onlyFavorites)
        assertEquals(listOf("pkg/Act"), onlyFavorites!!.favorites.toList())
    }

    /** Clearing everything and backing that up must still restore. */
    @Test
    fun `a well-formed but empty config is accepted`() {
        val empty = ConfigJson.parseForeign("""{"hidden":[],"favorites":[],"names":{},"tags":{}}""")
        assertNotNull(empty)
        assertTrue(empty!!.favorites.isEmpty())
    }

    @Test
    fun `a real serialized config round-trips through parseForeign`() {
        val cfg = VersoConfig(
            hidden = linkedSetOf("h/1"),
            favorites = linkedSetOf("f/1", "f/2"),
            names = linkedMapOf("f/1" to "Custom"),
            tags = linkedMapOf("f/2" to mutableListOf("work")),
        )
        val back = ConfigJson.parseForeign(ConfigJson.serialize(cfg))
        assertNotNull(back)
        assertEquals(listOf("f/1", "f/2"), back!!.favorites.toList())
        assertEquals(listOf("h/1"), back.hidden.toList())
        assertEquals("Custom", back.names["f/1"])
        assertEquals(listOf("work"), back.tags["f/2"])
    }

    // ---- tag canonicalisation on load ---------------------------------------

    @Test
    fun `loading canonicalises tags out of a foreign or older file`() {
        val cfg = ConfigJson.parse("""{"tags":{"pkg/A":["#work","Chat","foo,bar","  spaced  "]}}""")

        assertEquals(listOf("work", "chat", "foobar", "spaced"), cfg!!.tags["pkg/A"])
    }

    /** toggleTag removes only the first occurrence, so a doubled tag could never be un-ticked. */
    @Test
    fun `loading collapses tags that canonicalise to the same thing`() {
        val cfg = ConfigJson.parse("""{"tags":{"pkg/A":["Work","work","#work"]}}""")

        assertEquals(listOf("work"), cfg!!.tags["pkg/A"])
    }

    /** An empty list would disagree with what serialize writes next. */
    @Test
    fun `an app whose tags all vanish loses its key`() {
        val cfg = ConfigJson.parse("""{"tags":{"pkg/A":["###",",",""],"pkg/B":["work"]}}""")

        assertTrue("pkg/A" !in cfg!!.tags)
        assertEquals(listOf("work"), cfg.tags["pkg/B"])
    }

}
