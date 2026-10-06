package de.merzkevin.airrelay.core

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
        val f = File(moduleDir, "src/test/java/de/merzkevin/airrelay/core/CompatContractTest.kt")
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
        for (line in listOf("device-id(\"3f2a9c01d4e5b6a7\"): 62:40:D4:9A:D3:FA",
                "application-id: ", "dns-sd-type: _airplay._tcp", "dns-sd-type: _raop._tcp",
                "stored-file: filesDir/entitlement.txt",
                "stored-key: entitlement.txt status enum", "stored-key: entitlement.txt confirmedAtMs number",
                "stored-key: entitlement.txt schemaVersion number",
                "stored-value: entitlement.txt status=active", "stored-value: entitlement.txt status=expired",
                "iap-sku: parent de.merzkevin.airrelay.pro", "iap-sku: term de.merzkevin.airrelay.pro.monthly",
                "iap-sku: term de.merzkevin.airrelay.pro.yearly")) {
            assertTrue("contract lost \"$line\"", c.contains(line))
        }
    }

    @Test fun decisionTablesExist() {
        for (n in listOf("screen-table.txt", "back-table.txt", "access-table.txt")) {
            assertTrue("$n is missing", File(moduleDir, "src/test/resources/de/merzkevin/airrelay/core/$n").exists())
        }
    }

    @Test fun oldDataFixturesExistAndAreRead() {
        for (n in listOf("entitlement-v1-active.txt", "entitlement-v1-expired.txt", "entitlement-v3-from-newer-build.txt")) {
            assertTrue("fixture $n is missing", File(moduleDir, "src/test/resources/de/merzkevin/airrelay/core/fixtures/$n").exists())
        }
        val t = File(moduleDir, "src/test/java/de/merzkevin/airrelay/core/EntitlementCacheTest.kt").readText()
        assertTrue("EntitlementCacheTest no longer reads the frozen v1 file", t.contains("fixture(\"entitlement-v1-active.txt\")"))
        assertFalse("EntitlementCacheTest must not skip", Regex("@[\\w.]*Ignore\\b").containsMatchIn(t) || t.contains("assume"))
    }

    @Test fun onlyFulfilledIsEverReportedToAmazon() {
        // UNAVAILABLE, NOT_ELIGIBLE and EXISTING_PURCHASE cancel and refund a subscription
        val src = File(moduleDir, "src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }
        for (bad in listOf("FulfillmentResult.UNAVAILABLE", "FulfillmentResult.NOT_ELIGIBLE", "FulfillmentResult.EXISTING_PURCHASE")) {
            assertFalse("$bad refunds the customer", src.contains(bad))
        }
        assertTrue(src.contains("FulfillmentResult.FULFILLED"))
    }

    @Test fun senderAdmissionIsWiredToTheGate() {
        // native side asks Kotlin at the first SETUP; Kotlin answers from the subscription gate
        val cb = File(moduleDir, "src/main/cpp/android_raop_callbacks.c").readText()
        assertTrue("report_client_request no longer wired", cb.contains("cbs->report_client_request = _report_client_request"))
        assertTrue("admission must fail closed", cb.contains("*admit = false;"))
        val patch = File(moduleDir, "src/main/cpp/patches/UxPlay/0007-refuse-unadmitted-client-with-403.patch")
        assertTrue("403 patch is missing", patch.exists() && patch.readText().contains("403, \"Forbidden\""))
        val svc = File(moduleDir, "src/main/java/de/merzkevin/airrelay/ReceiverService.kt").readText()
        assertTrue(svc.contains("override fun onClientRequest(): Boolean"))
        // the exact call: the same comparison appears elsewhere in the service, so looking for
        // the comparison alone would not notice an admission that lets everybody in
        assertTrue("onClientRequest no longer asks the gate", svc.contains("val admitted = LicenseGate.admitsSender()"))
        assertTrue(svc.contains("return admitted"))
    }

    @Test fun packagingWaitsForTheUnitTests() {
        val gradle = File(moduleDir, "build.gradle.kts").readText()
        assertTrue("build.gradle.kts no longer blocks packaging on the unit tests",
            Regex("package\\\$\\{variant\\}.*?dependsOn\\(\"test\\\$\\{variant\\}UnitTest\"\\)", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(gradle))
    }
}
