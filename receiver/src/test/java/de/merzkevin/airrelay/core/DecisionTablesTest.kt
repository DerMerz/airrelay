package de.merzkevin.airrelay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * Full cross products of the UI decisions against hand-written tables in
 * src/test/resources. The tables are typed out row by row, not derived from the code.
 * A missing table file or a missing row is a failure, never a skip.
 */
class DecisionTablesTest {

    private fun table(name: String): List<List<String>> {
        val stream = javaClass.getResourceAsStream(name)
        assertNotNull("decision table $name is missing", stream)
        return stream!!.bufferedReader().readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.split(Regex("\\s+")) }
    }

    private fun bit(s: String) = when (s) {
        "0" -> false
        "1" -> true
        else -> error("not a bit: $s")
    }

    @Test
    fun screenForMatchesTheTableForEveryCombination() {
        val rows = table("screen-table.txt")
        val expected = rows.associate { r ->
            listOf(r[0], r[1], r[2], r[3], r[4]) to Screen.valueOf(r[5])
        }
        assertEquals("duplicate rows in screen-table.txt", rows.size, expected.size)

        var checked = 0
        for (e in Entitlement.entries) for (s in ServerStatus.entries)
            for (m in listOf(false, true)) for (f in listOf(false, true)) for (a in listOf(false, true)) {
                val key = listOf(e.name, s.name, if (m) "1" else "0", if (f) "1" else "0", if (a) "1" else "0")
                val want = expected[key] ?: run { fail("no row for $key"); return }
                assertEquals("row $key", want, screenFor(s, m, f, a, e))
                checked++
            }
        // 2 entitlements x 5 server states x 2 x 2 x 2; a new enum value must extend the table
        assertEquals(80, checked)
        assertEquals(80, rows.size)
    }

    @Test
    fun backActionMatchesTheTableForEveryCombination() {
        val rows = table("back-table.txt")
        val expected = rows.associate { r -> Triple(r[0], bit(r[1]), bit(r[2])) to BackAction.valueOf(r[3]) }
        assertEquals("duplicate rows in back-table.txt", rows.size, expected.size)

        var checked = 0
        for (screen in Screen.entries) for (lic in listOf(false, true)) for (hint in listOf(false, true)) {
            val key = Triple(screen.name, lic, hint)
            val want = expected[key] ?: run { fail("no row for $key"); return }
            assertEquals("row $key", want, backActionFor(screen, lic, hint))
            checked++
        }
        // 9 screens x 2 x 2
        assertEquals(36, checked)
        assertEquals(36, rows.size)
    }

    @Test
    fun startOutcomeForEveryCombination() {
        // ip present x native ready x port bound, written out
        val table = listOf(
            Triple(null, false, null) to ServerStatus.NO_NETWORK,
            Triple(null, false, 7000) to ServerStatus.NO_NETWORK,
            Triple(null, true, null) to ServerStatus.NO_NETWORK,
            Triple(null, true, 7000) to ServerStatus.NO_NETWORK,
            Triple("192.168.8.11", false, null) to ServerStatus.START_FAILED,
            Triple("192.168.8.11", false, 7000) to ServerStatus.START_FAILED,
            Triple("192.168.8.11", true, null) to ServerStatus.PORT_IN_USE,
            Triple("192.168.8.11", true, 7000) to ServerStatus.RUNNING,
        )
        for ((input, want) in table) {
            assertEquals("$input", want, startOutcome(input.first, input.second, input.third))
        }
        assertEquals(8, table.size)
    }

    @Test
    fun onlyConnectingAndMirroringShowVideo() {
        val video = Screen.entries.filter { it.showsVideo() }.toSet()
        assertEquals(setOf(Screen.CONNECTING, Screen.MIRRORING), video)
        val errors = Screen.entries.filter { it.isError() }.toSet()
        assertEquals(setOf(Screen.NO_NETWORK, Screen.PORT_IN_USE, Screen.START_FAILED), errors)
    }

    @Test
    fun everyDeviceIsUnlockedUntilPhaseTwo() {
        assertEquals(Entitlement.UNLOCKED, LicenseGate.current())
    }
}
