package de.merzkevin.airrelay.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The stored subscription status. The fixtures in resources/.../fixtures are typed by hand
 * and frozen: they are what a shipped build wrote, not what the current encoder writes.
 * A missing fixture fails, it never skips.
 */
class EntitlementCacheTest {

    private val dir = kotlin.io.path.createTempDirectory("entitlement").toFile()

    @After fun cleanUp() {
        dir.setWritable(true)
        dir.deleteRecursively()
    }

    private fun fixture(name: String): String {
        val s = javaClass.getResourceAsStream("fixtures/$name")
        assertNotNull("fixture $name is missing", s)
        return s!!.bufferedReader().readText()
    }

    private fun loaded(r: CacheRead): CachedEntitlement {
        assertTrue("expected a loaded entry, got $r", r is CacheRead.Loaded)
        return (r as CacheRead.Loaded).entry
    }

    // ---- frozen files ----

    @Test fun frozenVersion1ActiveFileDecodesToConcreteValues() {
        val e = loaded(EntitlementCache.decode(fixture("entitlement-v1-active.txt")))
        assertEquals(SubscriptionStatus.ACTIVE, e.status)
        assertEquals(1_790_000_000_000L, e.confirmedAtMs)
        assertEquals(1, e.schemaVersion)
        assertTrue(e.extras.isEmpty())
    }

    @Test fun frozenVersion1ExpiredFileDecodesToConcreteValues() {
        val e = loaded(EntitlementCache.decode(fixture("entitlement-v1-expired.txt")))
        assertEquals(SubscriptionStatus.EXPIRED, e.status)
        assertEquals(1_789_990_000_000L, e.confirmedAtMs)
    }

    @Test fun fileOfANewerBuildIsReadTolerantly() {
        // unknown status value -> UNKNOWN (asks Amazon again), not EXPIRED
        val e = loaded(EntitlementCache.decode(fixture("entitlement-v3-from-newer-build.txt")))
        assertEquals(SubscriptionStatus.UNKNOWN, e.status)
        assertEquals(1_790_000_000_000L, e.confirmedAtMs)
        assertEquals(3, e.schemaVersion)
        assertEquals("de.merzkevin.airrelay.pro.yearly", e.extras["lastTermSku"])
        assertEquals("a=b", e.extras["futureFlag"])
    }

    @Test fun thisBuildWritesExactlyTheFrozenVersion1Format() {
        val written = EntitlementCache.encode(CachedEntitlement(SubscriptionStatus.ACTIVE, 1_790_000_000_000L))
        assertEquals(fixture("entitlement-v1-active.txt"), written)
    }

    @Test fun everyStatusSurvivesARoundTrip() {
        for (s in SubscriptionStatus.entries) {
            val e = loaded(EntitlementCache.decode(EntitlementCache.encode(CachedEntitlement(s, 42L))))
            assertEquals(s, e.status)
            assertEquals(42L, e.confirmedAtMs)
        }
        assertEquals("every status has a raw value", SubscriptionStatus.entries.toSet(), EntitlementCache.STATUS_RAW.keys)
        assertEquals("raw values are unique", EntitlementCache.STATUS_RAW.size, EntitlementCache.STATUS_RAW.values.toSet().size)
    }

    @Test fun missingFieldsGetSafeDefaults() {
        val onlyStatus = loaded(EntitlementCache.decode("status=active\n"))
        assertEquals(SubscriptionStatus.ACTIVE, onlyStatus.status)
        assertEquals("no time means no grace", 0L, onlyStatus.confirmedAtMs)
        assertFalse(withinGrace(onlyStatus.confirmedAtMs, 1_790_000_000_000L))
        val onlyTime = loaded(EntitlementCache.decode("confirmedAtMs=5\n"))
        assertEquals(SubscriptionStatus.UNKNOWN, onlyTime.status)
        val badNumber = loaded(EntitlementCache.decode("status=active\nconfirmedAtMs=soon\n"))
        assertEquals(0L, badNumber.confirmedAtMs)
    }

    @Test fun garbageIsCorruptNotExpired() {
        for (text in listOf("", "\n\n", "\u0000\u0001\u0002binary", "{\"status\":\"active\"}", "hello=world\n")) {
            val r = EntitlementCache.decode(text)
            assertTrue("'$text' -> $r", r is CacheRead.Corrupt)
            assertEquals(SubscriptionStatus.UNKNOWN, r.cachedStatus())
        }
    }

    @Test fun readFailuresAreUnknownNeverExpired() {
        assertEquals(SubscriptionStatus.UNKNOWN, CacheRead.Missing.cachedStatus())
        assertEquals(SubscriptionStatus.UNKNOWN, CacheRead.Unreadable("io").cachedStatus())
        assertEquals(SubscriptionStatus.UNKNOWN, CacheRead.Corrupt("x").cachedStatus())
        assertEquals(0L, CacheRead.Corrupt("x").cachedAtMs())
    }

    // ---- the file ----

    @Test fun nothingStoredIsMissing() {
        assertEquals(CacheRead.Missing, EntitlementStore(dir).read())
    }

    @Test fun writeThenReadGivesTheSameValues() {
        val store = EntitlementStore(dir)
        assertTrue(store.write(CachedEntitlement(SubscriptionStatus.ACTIVE, 123L)))
        val e = loaded(store.read())
        assertEquals(SubscriptionStatus.ACTIVE, e.status)
        assertEquals(123L, e.confirmedAtMs)
        assertFalse("temp file left behind", File(dir, "entitlement.txt.tmp").exists())
    }

    @Test fun overwritingKeepsTheOldFileAsBackup() {
        val store = EntitlementStore(dir)
        store.write(CachedEntitlement(SubscriptionStatus.ACTIVE, 1L))
        store.write(CachedEntitlement(SubscriptionStatus.EXPIRED, 2L))
        assertEquals(SubscriptionStatus.EXPIRED, loaded(store.read()).status)
        val bak = File(dir, "entitlement.txt.bak")
        assertTrue(bak.exists())
        assertEquals(SubscriptionStatus.ACTIVE, loaded(EntitlementCache.decode(bak.readText())).status)
    }

    @Test fun corruptFileIsMovedAsideNeverDeletedAndTheBackupIsUsed() {
        val store = EntitlementStore(dir)
        store.write(CachedEntitlement(SubscriptionStatus.ACTIVE, 1L))
        store.write(CachedEntitlement(SubscriptionStatus.ACTIVE, 2L))
        store.file.writeText("\u0000garbage")
        val r = store.read()
        assertEquals("backup used", SubscriptionStatus.ACTIVE, loaded(r).status)
        assertEquals(1L, loaded(r).confirmedAtMs)
        val quarantined = dir.listFiles()!!.filter { it.name.startsWith("entitlement.txt.corrupt-") }
        assertEquals(1, quarantined.size)
        assertEquals("\u0000garbage", quarantined[0].readText())
    }

    @Test fun corruptFileWithoutBackupIsUnknownAndKept() {
        val store = EntitlementStore(dir)
        store.file.writeText("not a cache file at all")
        val r = store.read()
        assertTrue(r is CacheRead.Corrupt)
        assertEquals(SubscriptionStatus.UNKNOWN, r.cachedStatus())
        assertEquals(1, dir.listFiles()!!.count { it.name.startsWith("entitlement.txt.corrupt-") })
    }

    @Test fun quarantineIsBounded() {
        val store = EntitlementStore(dir)
        repeat(EntitlementStore.MAX_QUARANTINED + 2) { i ->
            store.file.writeText("junk $i")
            store.read()
            Thread.sleep(2)
        }
        assertEquals(EntitlementStore.MAX_QUARANTINED, dir.listFiles()!!.count { it.name.startsWith("entitlement.txt.corrupt-") })
        assertTrue("the last corrupt file stays in place", store.file.exists())
    }

    @Test fun unreadableFileIsLeftAloneAndUnknown() {
        val store = EntitlementStore(dir)
        store.file.mkdirs() // a directory where the file should be: reading throws
        val r = store.read()
        assertTrue("$r", r is CacheRead.Unreadable)
        assertEquals(SubscriptionStatus.UNKNOWN, r.cachedStatus())
        assertTrue(store.file.isDirectory)
    }

    @Test fun failedWriteReportsFalseAndKeepsTheOldFile() {
        val store = EntitlementStore(dir)
        store.write(CachedEntitlement(SubscriptionStatus.ACTIVE, 7L))
        dir.setWritable(false)
        assertFalse(store.write(CachedEntitlement(SubscriptionStatus.EXPIRED, 8L)))
        dir.setWritable(true)
        assertEquals(SubscriptionStatus.ACTIVE, loaded(store.read()).status)
    }

    @Test fun keysOfANewerBuildAreKeptWhenThisBuildRewrites() {
        val store = EntitlementStore(dir)
        store.file.writeText(fixture("entitlement-v3-from-newer-build.txt"))
        assertTrue(store.write(CachedEntitlement(SubscriptionStatus.ACTIVE, 9L)))
        val text = store.file.readText()
        assertTrue(text, text.contains("lastTermSku=de.merzkevin.airrelay.pro.yearly\n"))
        assertTrue(text, text.contains("futureFlag=a=b\n"))
        assertTrue(text, text.startsWith("schemaVersion=1\nstatus=active\nconfirmedAtMs=9\n"))
    }
}
