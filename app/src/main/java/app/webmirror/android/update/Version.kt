package app.webmirror.android.update

/** Semantic-version comparison (SemVer 2.0 precedence; build metadata ignored; leading "v" ignored). */
object Version {
    /** Strips whitespace and a leading v/V. */
    fun normalize(tag: String): String = tag.trim().removePrefix("v").removePrefix("V")

    /** Negative if [a] < [b], 0 if equal, positive if [a] > [b]. */
    fun compare(a: String, b: String): Int {
        val x = parse(a)
        val y = parse(b)
        for (i in 0 until maxOf(x.core.size, y.core.size)) {
            val c = (x.core.getOrElse(i) { 0L }).compareTo(y.core.getOrElse(i) { 0L })
            if (c != 0) return c
        }
        // A version without a pre-release is greater than one with.
        if (x.pre.isEmpty() && y.pre.isEmpty()) return 0
        if (x.pre.isEmpty()) return 1
        if (y.pre.isEmpty()) return -1
        for (i in 0 until minOf(x.pre.size, y.pre.size)) {
            val p = x.pre[i]
            val q = y.pre[i]
            val pn = p.toLongOrNull()
            val qn = q.toLongOrNull()
            val c = when {
                pn != null && qn != null -> pn.compareTo(qn)
                pn != null -> -1
                qn != null -> 1
                else -> p.compareTo(q)
            }
            if (c != 0) return c
        }
        return x.pre.size.compareTo(y.pre.size)
    }

    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0

    private class Parsed(val core: List<Long>, val pre: List<String>)

    private fun parse(v: String): Parsed {
        val s = normalize(v).substringBefore('+')
        val core = s.substringBefore('-').split('.').map { part -> part.takeWhile { it.isDigit() }.toLongOrNull() ?: 0L }
        val pre = if (s.contains('-')) s.substringAfter('-').split('.').filter { it.isNotEmpty() } else emptyList()
        return Parsed(core, pre)
    }
}
