/*
 * AirRelay, Copyright (C) 2026 Kevin Merz
 * SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-AirRelay-Amazon-Appstore-SDK-exception
 * GNU GPL version 3 or later, with the additional permission in LICENSE-EXCEPTION.md.
 */
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
        for (n in listOf("screen-table.txt", "back-table.txt", "access-table.txt", "frame-table.txt", "wifi-table.txt", "hint-table.txt")) {
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

    @Test fun gplOnlyPartsStayOutOfTheLibrary() {
        val cmake = File(moduleDir, "src/main/cpp/CMakeLists.txt").readText()
        assertFalse("UxPlay lib/crypto.c is compiled again", Regex("UXPLAY_\\w+\\}/crypto\\.c").containsMatchIn(cmake))
        assertTrue("crypto.c/crypto.h are no longer left out of the staged lib/",
            cmake.contains("list(REMOVE_ITEM UXPLAY_LIB_FILES crypto.c crypto.h)"))
        assertTrue("own crypto is not compiled", cmake.contains("crypto/airrelay_crypto.c"))
        val link = cmake.substringAfter("target_link_libraries(airrelay_native").substringBefore(")")
        assertFalse("playfair is linked into the library", link.contains("playfair"))
        val lib = cmake.substringAfter("add_library(airrelay_native SHARED").substringBefore(")")
        assertFalse("playfair sources in the library", lib.contains("playfair/playfair.c") || lib.contains("omg_hax"))
        assertTrue("the playfair helper is no longer its own program",
            Regex("add_executable\\(airrelay_playfair[^)]*omg_hax\\.c", RegexOption.DOT_MATCHES_ALL).containsMatchIn(cmake))
        assertTrue("the include path holds UxPlay's crypto.h again", !Regex("\\$\\{UXPLAY_LIB\\}\\s*\\n").containsMatchIn(
            cmake.substringAfter("target_include_directories(airrelay_native SYSTEM PRIVATE").substringBefore(")")))
        assertTrue("post-link check is gone", cmake.contains("POST_BUILD") && cmake.contains("CheckLicenseSplit.cmake"))
        val check = File(moduleDir, "src/main/cpp/cmake/CheckLicenseSplit.cmake").readText()
        for (s in listOf("playfair_decrypt|garble", "Crypto error at", "^airrelay_playfair_decrypt$", "FATAL_ERROR")) {
            assertTrue("CheckLicenseSplit.cmake lost \"$s\"", check.contains(s))
        }
        val patch = File(moduleDir, "src/main/cpp/patches/UxPlay/0008-playfair-as-separate-program.patch")
        assertTrue("patch 0008 is missing", patch.exists())
        val p = patch.readText()
        assertTrue("0008 no longer routes the key through the helper", p.contains("+    return airrelay_fairplay_decrypt(fp->keymsg, input, output) == 0 ? 0 : -1;"))
        assertTrue("0008 no longer refuses the stream on failure", p.contains("500, \"Internal Server Error\""))
        // the crypto rewrite differs from 0.1.0 after aes_ctr_reset; harmless only while nobody calls it
        val uxplay = File(moduleDir, "src/main/cpp/third_party/UxPlay/lib")
        val callers = uxplay.listFiles()!!.filter { (it.extension == "c" || it.extension == "h") && !it.name.startsWith("crypto.") }
            .filter { it.readText().contains("aes_ctr_reset(") }.map { it.name }
        assertTrue("UxPlay now calls aes_ctr_reset (see NativeHostTest intendedDifferences): $callers", callers.isEmpty())
    }

    @Test fun playfairHelperIsPackagedExtractedAndFound() {
        val gradle = File(moduleDir, "build.gradle.kts").readText()
        assertTrue("native files are no longer extracted: the helper cannot be started",
            gradle.contains("jniLibs.useLegacyPackaging = true"))
        val cmake = File(moduleDir, "src/main/cpp/CMakeLists.txt").readText()
        val config = File(moduleDir, "src/main/java/de/merzkevin/airrelay/core/ReceiverConfig.kt").readText()
        val name = Regex("PLAYFAIR_HELPER = \"([^\"]+)\"").find(config)?.groupValues?.get(1)
        assertTrue("PLAYFAIR_HELPER is gone", name != null)
        assertTrue("CMake output name and PLAYFAIR_HELPER differ", cmake.contains("OUTPUT_NAME \"$name\""))
        assertTrue("helper must be named lib*.so to be packaged", name!!.startsWith("lib") && name.endsWith(".so"))
        val svc = File(moduleDir, "src/main/java/de/merzkevin/airrelay/ReceiverService.kt").readText()
        assertTrue("service no longer hands the helper path to the native side",
            svc.contains("File(applicationInfo.nativeLibraryDir, ReceiverConfig.PLAYFAIR_HELPER)") &&
                svc.contains("NativeBridge.nativeSetPlayfairHelper(helper.path)"))
    }

    @Test fun nativeHostTestsExistRunAndCannotSkip() {
        val f = File(moduleDir, "src/test/java/de/merzkevin/airrelay/nativecode/NativeHostTest.kt")
        assertTrue("NativeHostTest.kt is gone", f.exists())
        val t = f.readText()
        assertFalse("NativeHostTest must not be ignored", Regex("@[\\w.]*Ignore\\b").containsMatchIn(t))
        for (forbidden in listOf("Assume.", "assumeTrue", "assumeFalse", "assumeNotNull")) {
            assertFalse("NativeHostTest must not skip ($forbidden)", t.contains(forbidden))
        }
        for (s in listOf("assertEquals(\"frozen vector file shrank or grew\", 144", "assertEquals(\"compared values\", 141",
                "assertEquals(\"vector count\", 12", "processCallTimeoutKillsTheHelper", "processCallCrash",
                "processCallShortOutput", "airplayIdentityOfTheContractDevicesIsUnchanged")) {
            assertTrue("NativeHostTest lost \"$s\"", t.contains(s))
        }
        for (n in listOf("crypto-vectors-0.1.0.txt", "playfair-vectors-0.1.0.txt", "rfc8032-test1.seed.hex")) {
            assertTrue("fixture $n is missing", File(moduleDir, "src/test/resources/native/$n").exists())
        }
        val gradle = File(moduleDir, "build.gradle.kts").readText()
        assertTrue("unit tests no longer build the native host tests",
            Regex("withType<Test>\\(\\)\\.configureEach \\{\\s*dependsOn\\(buildHostNativeTests\\)").containsMatchIn(gradle))
    }

    @Test fun ownFilesCarryTheAdditionalPermission() {
        val root = moduleDir.parentFile
        val exception = File(root, "LICENSE-EXCEPTION.md")
        assertTrue("LICENSE-EXCEPTION.md is missing", exception.exists())
        val e = exception.readText()
        assertTrue(e.contains("Additional permission under GNU GPL version 3 section 7") && e.contains("Amazon Appstore SDK"))
        val own = File(moduleDir, "src").walkTopDown().filter { it.isFile && it.extension == "kt" && "third_party" !in it.path }.toList() +
            listOf("crypto/airrelay_crypto.c", "crypto/crypto.h", "playfair/playfair_pipe.c", "playfair/playfair_pipe.h",
                "playfair/playfair_bridge.c").map { File(moduleDir, "src/main/cpp/$it") }
        val missing = own.filterNot { it.readText().contains("LICENSE-EXCEPTION.md") }.map { it.name }
        assertTrue("files without the licence header: $missing", missing.isEmpty())
        assertTrue("own files found", own.size > 30)
    }

    @Test fun screenFitTestExistsCannotSkipAndKeepsItsMatrix() {
        // T-31: a text cut off on a stick. Everything left would stay green if this were deleted.
        val f = File(moduleDir, "src/test/java/de/merzkevin/airrelay/ui/ScreenFitTest.kt")
        assertTrue("ScreenFitTest.kt is gone", f.exists())
        val t = f.readText()
        assertFalse("ScreenFitTest must not be ignored", Regex("@[\\w.]*Ignore\\b").containsMatchIn(t))
        for (forbidden in listOf("Assume.", "assumeTrue", "assumeFalse", "assumeNotNull")) {
            assertFalse("ScreenFitTest must not skip ($forbidden)", t.contains(forbidden))
        }
        for (s in listOf("noTextOnAnyScreenIsCutOff", "const val CASES = 2 * 2 * 5 * (", "listOf(0.85f, 1.0f, 1.15f, 1.3f, 2.0f)",
                "GraphicsMode.Mode.NATIVE", "w960dp-h540dp-xhdpi", "w960dp-h540dp-tvdpi", "getEllipsisCount", "clipToPadding",
                "OfferMessage.values()", "ServerStatus.values()", "assertEquals(\"the matrix has shrunk or grown")) {
            assertTrue("ScreenFitTest lost \"$s\"", t.contains(s))
        }
        val gradle = File(moduleDir, "build.gradle.kts").readText()
        assertTrue("Robolectric is no longer a test dependency", gradle.contains("testImplementation(\"org.robolectric:robolectric:"))
        assertTrue("unit tests no longer see the resources", gradle.contains("isIncludeAndroidResources = true"))
    }

    @Test fun fontScaleCapAndOfferWidthAreStillInPlace() {
        // the two measures that keep the subscription card inside the screen (T-31)
        val main = File(moduleDir, "src/main/java/de/merzkevin/airrelay/MainActivity.kt").readText()
        assertTrue("MainActivity no longer caps the system font scale", main.contains("FontScale.capped(config.fontScale)"))
        val layout = File(moduleDir, "src/main/res/layout/activity_main.xml").readText()
        val body = layout.substringAfter("android:id=\"@+id/offer_body\"").substringBefore("/>")
        assertTrue("offer_body lost its maximum width", body.contains("android:maxWidth="))
        assertTrue("FontScale.kt is gone", File(moduleDir, "src/main/java/de/merzkevin/airrelay/core/FontScale.kt").exists())
    }

    @Test fun framePacingAndWifiHintTestsExistAndCannotSkip() {
        // T-31b: time-lapse after a stall, and the Wi-Fi hint. Deleting a test would leave green.
        val names = mapOf(
            "core/FramePacingTest.kt" to listOf("afterAStallThePictureJumpsAndNeverRunsInTimeLapse",
                "theStallReproducesTheTimeLapseOf012", "steadyStreamIsNeverDroppedAndRunsSmoothly",
                "aLastingLatencyShiftIsNotMistakenForABacklog"),
            "core/WifiAdviceTest.kt" to listOf("signalIsWeakBelowMinusSeventy", "theStickMeasuredOn19OctoberGetsTheHint",
                "landscapeOrNarrowStripsLeaveNoPlace"),
            "core/DecisionTablesTest.kt" to listOf("frameActionMatchesTheTableForEveryCombination",
                "wifiAdviceMatchesTheTableForEveryCombination", "wifiHintPlaceMatchesTheTableForEveryCombination"),
            "ui/WifiHintTest.kt" to listOf("itGoesByItselfAfterTwelveSecondsAndStaysGone", "anyKeyOfTheRemoteTakesItAway",
                "reopeningTheAppDoesNotRepeatAHintAlreadySeen"),
            "ui/ScreenFitTest.kt" to listOf("R.id.wifi_hint_video", "lies on the picture", "expectShown(a, R.id.wifi_hint_video"),
        )
        for ((path, wanted) in names) {
            val f = File(moduleDir, "src/test/java/de/merzkevin/airrelay/$path")
            assertTrue("$path is gone", f.exists())
            val t = f.readText()
            assertFalse("$path must not be ignored", Regex("@[\\w.]*Ignore\\b").containsMatchIn(t))
            for (forbidden in listOf("Assume.", "assumeTrue", "assumeFalse", "assumeNotNull")) {
                assertFalse("$path must not skip ($forbidden)", t.contains(forbidden))
            }
            for (w in wanted) assertTrue("$path lost \"$w\"", t.contains(w))
        }
        // the renderer still asks the pacer, and releases every picture through it
        val r = File(moduleDir, "src/main/java/de/merzkevin/airrelay/video/VideoRenderer.kt").readText()
        // (text pins: MediaCodec and WifiManager do not run in unit tests; the stick run checks them)
        for (s in listOf("pacer.onOutput(", "FrameAction.HIDE, FrameAction.DROP -> c.releaseOutputBuffer(index, false)",
                "FrameAction.PACE -> c.releaseOutputBuffer(index, paced.renderAtUs!! * 1000)", "listener.onCatchUp(")) {
            assertTrue("VideoRenderer lost \"$s\"", r.contains(s))
        }
        val svc = File(moduleDir, "src/main/java/de/merzkevin/airrelay/ReceiverService.kt").readText()
        for (s in listOf("catchUpsOnNetwork++", "refreshWifiAdvice(announce = true)", "it.withWifiAdvice(advice, announce)",
                "it.restarting(name, ip)")) {
            assertTrue("ReceiverService lost \"$s\"", svc.contains(s))
        }
    }

    @Test fun packagingWaitsForTheUnitTests() {
        val gradle = File(moduleDir, "build.gradle.kts").readText()
        assertTrue("build.gradle.kts no longer blocks packaging on the unit tests",
            Regex("package\\\$\\{variant\\}.*?dependsOn\\(\"test\\\$\\{variant\\}UnitTest\"\\)", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(gradle))
    }
}
