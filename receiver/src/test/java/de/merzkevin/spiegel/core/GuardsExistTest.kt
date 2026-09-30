package de.merzkevin.spiegel.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the guard. A deleted contract test, a deleted contract file or a build that no
 * longer runs the tests before packaging would all go unnoticed, because everything left
 * would still be green.
 */
class GuardsExistTest {
    private val moduleDir: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "src/main/AndroidManifest.xml").exists() && File(it, "build.gradle.kts").exists() }

    @Test fun contractTestExistsAndCannotSkip() {
        val f = File(moduleDir, "src/test/java/de/merzkevin/spiegel/core/CompatContractTest.kt")
        assertTrue("CompatContractTest.kt is gone", f.exists())
        val t = f.readText()
        // also catches fully qualified forms such as @org.junit.Ignore
        assertFalse("CompatContractTest must not be ignored", Regex("@[\\w.]*Ignore\\b").containsMatchIn(t))
        for (forbidden in listOf("Assume.", "assumeTrue", "assumeFalse", "assumeNotNull")) {
            assertFalse("CompatContractTest must not skip ($forbidden)", t.contains(forbidden))
        }
        assertTrue("detector self-test is gone", t.contains("detectorSeesStoredPreferencesThatAppear"))
    }

    @Test fun frozenContractExistsAndHoldsTheIdentity() {
        val f = File(moduleDir, "contract/compat-contract.txt")
        assertTrue("contract/compat-contract.txt is missing", f.exists())
        val c = f.readText()
        for (line in listOf("persistence: none", "device-id(\"3f2a9c01d4e5b6a7\"): 62:40:D4:9A:D3:FA",
                "application-id: ", "dns-sd-type: _airplay._tcp", "dns-sd-type: _raop._tcp")) {
            assertTrue("contract lost \"$line\"", c.contains(line))
        }
    }

    @Test fun decisionTablesExist() {
        for (n in listOf("screen-table.txt", "back-table.txt")) {
            assertTrue("$n is missing", File(moduleDir, "src/test/resources/de/merzkevin/spiegel/core/$n").exists())
        }
    }

    @Test fun packagingWaitsForTheUnitTests() {
        val gradle = File(moduleDir, "build.gradle.kts").readText()
        assertTrue("build.gradle.kts no longer blocks packaging on the unit tests",
            Regex("package\\\$\\{variant\\}.*?dependsOn\\(\"test\\\$\\{variant\\}UnitTest\"\\)", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(gradle))
    }
}
