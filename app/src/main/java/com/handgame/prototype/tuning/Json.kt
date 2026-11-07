package com.handgame.prototype.tuning

/**
 * Tiny JSON reader/writer for tuning recordings. org.json isn't usable in JVM unit tests (the
 * Android stubs throw), and recordings must be readable both on the device and in tests.
 */
object Json {
    fun parse(text: String): Any? = Parser(text).run { val v = value(); skipWs(); v }

    fun write(v: Any?): String = StringBuilder().also { write(it, v) }.toString()

    fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> { sb.append('"'); escape(sb, v); sb.append('"') }
            is Boolean -> sb.append(v)
            is Float -> num(sb, v.toDouble())
            is Double -> num(sb, v)
            is Number -> sb.append(v.toLong())
            is FloatArray -> { sb.append('['); v.forEachIndexed { i, f -> if (i > 0) sb.append(','); num(sb, f.toDouble()) }; sb.append(']') }
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(','); first = false
                    sb.append('"'); escape(sb, k.toString()); sb.append("\":"); write(sb, value)
                }
                sb.append('}')
            }
            is Iterable<*> -> { sb.append('['); v.forEachIndexed { i, x -> if (i > 0) sb.append(','); write(sb, x) }; sb.append(']') }
            else -> { sb.append('"'); escape(sb, v.toString()); sb.append('"') }
        }
    }

    /** Numbers rounded to 4 decimals (plenty for landmarks; keeps files small). */
    private fun num(sb: StringBuilder, d: Double) {
        if (!d.isFinite()) { sb.append("null"); return }
        val r = Math.round(d * 10000.0)
        if (r % 10000 == 0L) { sb.append(r / 10000); return }
        val neg = r < 0
        val a = kotlin.math.abs(r)
        if (neg) sb.append('-')
        sb.append(a / 10000).append('.')
        var frac = (a % 10000).toString().padStart(4, '0')
        frac = frac.trimEnd('0')
        sb.append(frac)
    }

    private fun escape(sb: StringBuilder, s: String) {
        for (c in s) when (c) {
            '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\"); '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r"); '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
        }
    }

    private class Parser(val s: String) {
        var i = 0
        fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            skipWs()
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> if (c == '-' || c.isDigit()) number() else error("Unexpected '$c' at $i")
            }
        }
        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++; skipWs()
            if (s[i] == '}') { i++; return m }
            while (true) {
                skipWs(); val k = str(); skipWs(); check(s[i] == ':'); i++
                m[k] = value(); skipWs()
                if (s[i] == ',') { i++; continue }
                check(s[i] == '}'); i++; return m
            }
        }
        fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++; skipWs()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l += value(); skipWs()
                if (s[i] == ',') { i++; continue }
                check(s[i] == ']'); i++; return l
            }
        }
        fun str(): String {
            check(s[i] == '"'); i++
            val sb = StringBuilder()
            while (s[i] != '"') {
                val c = s[i++]
                if (c == '\\') {
                    when (val e = s[i++]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                        'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> sb.append(e)
                    }
                } else sb.append(c)
            }
            i++
            return sb.toString()
        }
        fun number(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDouble()
        }
    }
}

// Small helpers for reading parsed JSON.
@Suppress("UNCHECKED_CAST")
fun Any?.obj(): Map<String, Any?> = this as Map<String, Any?>
fun Map<String, Any?>.num(key: String, default: Double = 0.0) = (this[key] as? Double) ?: default
fun Map<String, Any?>.str(key: String) = this[key] as? String
fun Map<String, Any?>.floats(key: String): FloatArray? =
    (this[key] as? List<*>)?.let { l -> FloatArray(l.size) { (l[it] as Double).toFloat() } }
