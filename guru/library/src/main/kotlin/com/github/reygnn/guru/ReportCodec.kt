package com.github.reygnn.guru

/**
 * A report on disk: a "guru 1" line, then one "key=value" line per field, with backslash,
 * newline and CR escaped. Plain text so it needs no JSON parser on the JVM, and a file pulled
 * off the device with adb reads as it is. Unknown keys are skipped (a newer Guru may add some);
 * anything else off is null, never a half report.
 */
internal object ReportCodec {

    private const val MAGIC = "guru 1"

    /** A report id is also a file name part: no separators, nothing to escape. */
    val ID = Regex("[A-Za-z0-9-]{1,64}")

    fun encode(report: GuruReport): String = buildString {
        append(MAGIC).append('\n')
        put("id", report.id)
        put("kind", report.kind.name)
        put("crashedAt", report.crashedAt.toString())
        put("startedAt", report.startedAt.toString())
        put("thread", report.thread)
        put("package", report.env.packageName)
        put("versionName", report.env.versionName)
        put("versionCode", report.env.versionCode.toString())
        put("release", report.env.androidRelease)
        put("sdk", report.env.sdk.toString())
        put("brand", report.env.brand)
        put("model", report.env.model)
        put("device", report.env.device)
        put("trace", report.trace)
    }

    fun decode(text: String): GuruReport? {
        val lines = text.split('\n')
        if (lines.first() != MAGIC) return null
        val fields = lines.drop(1).filter { it.isNotEmpty() }.map(::field)
        return if (null in fields) null else build(fields.filterNotNull().toMap())
    }

    private fun field(line: String): Pair<String, String>? {
        val eq = line.indexOf('=')
        if (eq <= 0) return null
        return unescape(line.substring(eq + 1))?.let { line.substring(0, eq) to it }
    }

    // Missing key (NoSuchElementException), bad number or kind (IllegalArgumentException,
    // NumberFormatException among them): not a report.
    @Suppress("SwallowedException")
    private fun build(f: Map<String, String>): GuruReport? = try {
        val id = f.getValue("id")
        require(ID.matches(id))
        GuruReport(
            id = id,
            kind = GuruKind.valueOf(f.getValue("kind")),
            crashedAt = f.getValue("crashedAt").toLong(),
            startedAt = f.getValue("startedAt").toLong(),
            thread = f.getValue("thread"),
            trace = f.getValue("trace"),
            env = GuruEnv(
                packageName = f.getValue("package"),
                versionName = f.getValue("versionName"),
                versionCode = f.getValue("versionCode").toLong(),
                androidRelease = f.getValue("release"),
                sdk = f.getValue("sdk").toInt(),
                brand = f.getValue("brand"),
                model = f.getValue("model"),
                device = f.getValue("device"),
            ),
        )
    } catch (e: NoSuchElementException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun StringBuilder.put(key: String, value: String) {
        append(key).append('=').append(escape(value)).append('\n')
    }

    private fun escape(value: String): String = buildString(value.length) {
        for (c in value) {
            when (c) {
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(c)
            }
        }
    }

    // Null on an unknown escape or a backslash at the very end.
    private fun unescape(value: String): String? {
        val out = StringBuilder(value.length)
        var i = 0
        var valid = true
        while (valid && i < value.length) {
            val c = value[i++]
            val plain = if (c == '\\') value.getOrNull(i++)?.let(UNESCAPED::get) else c
            if (plain == null) valid = false else out.append(plain)
        }
        return if (valid) out.toString() else null
    }

    private val UNESCAPED = mapOf('\\' to '\\', 'n' to '\n', 'r' to '\r')
}
