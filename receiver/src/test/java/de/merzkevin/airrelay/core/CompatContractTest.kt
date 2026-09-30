package de.merzkevin.airrelay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Breaking-change detection. Asks "does the code still promise what installed builds and
 * existing devices rely on", not "does the code work".
 *
 * The contract is built from the real sources (manifest, Gradle file, Kotlin sources and
 * the identity function), never from a hand-kept list, and compared line by line with
 * the frozen copy in contract/compat-contract.txt.
 *
 * What survives an update of this app:
 *  - stored data: none. No SharedPreferences, no files, no database. Any persistence API
 *    that appears in src/main shows up here and fails the test until it is frozen on
 *    purpose (and then needs its own old-data fixture test).
 *  - the AirPlay identity (device id and the pairing key UxPlay derives from it): a change
 *    makes every iPhone list the TV as a new device.
 *  - applicationId, launcher/boot components, notification channel id, dns-sd types.
 *
 * Refreeze only on purpose: AIRRELAY_FREEZE_CONTRACT=1 ./gradlew :receiver:testDebugUnitTest
 * and commit the diff of compat-contract.txt with the change that caused it.
 */
class CompatContractTest {

    private val moduleDir: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "src/main/AndroidManifest.xml").exists() && File(it, "build.gradle.kts").exists() }
    private val frozen = File(moduleDir, "contract/compat-contract.txt")

    @Test
    fun contractIsUnchanged() {
        val current = CompatContract.build(moduleDir)
        if (System.getenv("AIRRELAY_FREEZE_CONTRACT") == "1") {
            File(moduleDir, "contract").mkdirs()
            frozen.writeText(current)
            fail("contract frozen to ${frozen.path}; this run proved nothing, run again without AIRRELAY_FREEZE_CONTRACT")
        }
        if (!frozen.exists()) {
            File(moduleDir, "contract").mkdirs()
            frozen.writeText(current)
            fail("contract file was missing and has been written to ${frozen.path}. This run proved nothing: " +
                "review it, commit it, run again.")
        }
        val want = frozen.readText().lines()
        val got = current.lines()
        if (want != got) {
            val removed = want.filterNot { it in got }
            val added = got.filterNot { it in want }
            fail(buildString {
                appendLine("Compatibility contract changed.")
                if (removed.isNotEmpty()) appendLine("BREAKING (no longer promised):\n  " + removed.joinToString("\n  "))
                if (added.isNotEmpty()) appendLine("NEW (not yet protected):\n  " + added.joinToString("\n  "))
                appendLine("Before refreezing, answer: what happens to devices that already run the old build?")
                appendLine("Refreeze on purpose with AIRRELAY_FREEZE_CONTRACT=1 and commit the diff.")
            })
        }
    }

    @Test
    fun detectorSeesStoredPreferencesThatAppear() {
        // re-enact the failure this guards against: somebody adds a SharedPreferences key
        val tmp = copyModule()
        File(tmp, "src/main/java/X.kt").writeText(
            "class X { fun f(c: android.content.Context) = c.getSharedPreferences(\"settings\", 0) }")
        val changed = CompatContract.build(tmp)
        assertNotEquals(CompatContract.build(moduleDir), changed)
        assertTrue(changed, changed.contains("persistence: getSharedPreferences"))
        tmp.deleteRecursively()
    }

    @Test
    fun detectorSeesARenamedApplicationIdAndChannel() {
        val tmp = copyModule()
        val gradle = File(tmp, "build.gradle.kts")
        gradle.writeText(gradle.readText().replace("val appId = \"de.merzkevin.airrelay\"", "val appId = \"de.merzkevin.other\""))
        val svc = File(tmp, "src/main/java/de/merzkevin/airrelay/ReceiverService.kt")
        svc.writeText(svc.readText().replace("CHANNEL_ID = \"receiver\"", "CHANNEL_ID = \"receiver2\""))
        val changed = CompatContract.build(tmp)
        assertTrue(changed.contains("application-id: de.merzkevin.other"))
        assertTrue(changed.contains("notification-channel: receiver2"))
        tmp.deleteRecursively()
    }

    @Test
    fun harmlessCodeChangesDoNotTouchTheContract() {
        val tmp = copyModule()
        File(tmp, "src/main/java/Y.kt").writeText("class Y { val cache = HashMap<String, Int>() }")
        assertEquals(CompatContract.build(moduleDir), CompatContract.build(tmp))
        tmp.deleteRecursively()
    }

    private fun copyModule(): File {
        val tmp = kotlin.io.path.createTempDirectory("contract").toFile()
        File(moduleDir, "build.gradle.kts").copyTo(File(tmp, "build.gradle.kts"))
        File(moduleDir, "src/main").copyRecursively(File(tmp, "src/main"))
        return tmp
    }
}

/** Builds the contract text from a module directory. */
object CompatContract {
    private val PERSISTENCE_APIS = listOf(
        "getSharedPreferences", "PreferenceManager", "openFileOutput", "openFileInput", "filesDir",
        "noBackupFilesDir", "getExternalFilesDir", "cacheDir", "getDatabasePath", "SQLiteOpenHelper",
        "openOrCreateDatabase", "dataStore", "RoomDatabase", "AccountManager", "KeyStore",
    )

    fun build(moduleDir: File): String {
        val lines = ArrayList<String>()
        val gradle = File(moduleDir, "build.gradle.kts").readText()
        lines += "application-id: " + (Regex("val appId = \"([^\"]+)\"").find(gradle)?.groupValues?.get(1) ?: "MISSING")
        lines += "code-package: " + (Regex("namespace = \"([^\"]+)\"").find(gradle)?.groupValues?.get(1) ?: "MISSING")

        val manifest = File(moduleDir, "src/main/AndroidManifest.xml").readText()
        Regex("<(activity|service|receiver)\\s[^>]*?android:name=\"([^\"]+)\"[^>]*>(.*?)</\\1>|<(activity|service|receiver)\\s[^>]*?android:name=\"([^\"]+)\"[^>]*/>",
            RegexOption.DOT_MATCHES_ALL).findAll(manifest).forEach { m ->
            val kind = m.groupValues[1].ifEmpty { m.groupValues[4] }
            val name = m.groupValues[2].ifEmpty { m.groupValues[5] }
            val body = m.groupValues[3]
            val actions = Regex("android:name=\"(android\\.intent\\.(?:action|category)\\.[A-Z_]+)\"").findAll(body)
                .map { it.groupValues[1] }.sorted().joinToString(",")
            lines += "component: $kind $name [$actions]"
        }

        val sources = File(moduleDir, "src/main/java").walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }
            .sortedBy { it.path }.toList()
        val all = sources.joinToString("\n") { it.readText() }
        Regex("CHANNEL_ID = \"([^\"]+)\"").findAll(all).forEach { lines += "notification-channel: ${it.groupValues[1]}" }
        Regex("\"(_[a-z]+\\._tcp)\"").findAll(all).map { it.groupValues[1] }.toSortedSet()
            .forEach { lines += "dns-sd-type: $it" }
        Regex("DEVICE_NAME_SETTING = \"([^\"]+)\"").findAll(all).forEach { lines += "reads-setting: global/${it.groupValues[1]}" }

        val found = PERSISTENCE_APIS.filter { api -> Regex("\\b$api\\b").containsMatchIn(all) }
        if (found.isEmpty()) lines += "persistence: none"
        else found.sorted().forEach { lines += "persistence: $it" }

        // identity: pinned samples of the real function
        for (id in listOf("3f2a9c01d4e5b6a7", "0123456789abcdef", "")) {
            lines += "device-id(\"$id\"): ${formatDeviceId(deviceIdFor(id))}"
        }
        lines += "receiver-name-fallback: $FALLBACK_RECEIVER_NAME"
        lines += "receiver-name-max-bytes: $MAX_NAME_BYTES"
        return lines.joinToString("\n") + "\n"
    }
}
