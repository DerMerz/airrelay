package de.merzkevin.airrelay.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * The last answer Amazon gave about the subscription, kept so that a confirmed subscriber
 * can mirror right after boot and up to three days without internet.
 *
 * This is the app's only stored data. Keys, value kinds, raw status values and the file
 * name are pinned in contract/compat-contract.txt (built from [EntitlementCache.encode],
 * not from a hand-kept list). Rules for every future build:
 *  - keys and raw values are never renamed or removed; a replaced key keeps being written
 *  - unknown keys from a newer build are kept when this build rewrites the file
 *  - an unreadable file is never taken as "no subscription" and is never deleted
 */
data class CachedEntitlement(
    val status: SubscriptionStatus,
    /** Wall clock ms of the Amazon answer this was taken from. */
    val confirmedAtMs: Long,
    val schemaVersion: Int = EntitlementCache.SCHEMA_VERSION,
    /** Keys this build does not know (written by a newer build), kept on rewrite. */
    val extras: Map<String, String> = emptyMap(),
)

/** Result of reading the stored copy. Every case except [Loaded] means "unknown". */
sealed class CacheRead {
    data class Loaded(val entry: CachedEntitlement) : CacheRead()
    object Missing : CacheRead()
    /** The file exists but could not be read (I/O). Left untouched. */
    data class Unreadable(val reason: String) : CacheRead()
    /** The file was read but makes no sense. Moved aside, never deleted. */
    data class Corrupt(val reason: String) : CacheRead()
}

/**
 * Status taken from a stored copy. Anything that is not a readable copy is UNKNOWN,
 * never EXPIRED: a read error must not take a paying customer's subscription away.
 */
fun CacheRead.cachedStatus(): SubscriptionStatus =
    (this as? CacheRead.Loaded)?.entry?.status ?: SubscriptionStatus.UNKNOWN

/** Confirmation time of a stored copy, 0 (= long ago, no grace) if there is none. */
fun CacheRead.cachedAtMs(): Long = (this as? CacheRead.Loaded)?.entry?.confirmedAtMs ?: 0L

object EntitlementCache {
    const val FILE_NAME = "entitlement.txt"
    const val SCHEMA_VERSION = 1

    const val KEY_SCHEMA = "schemaVersion"
    const val KEY_STATUS = "status"
    const val KEY_CONFIRMED_AT = "confirmedAtMs"

    /** Raw values on disk. Frozen: never rename, never remove. */
    val STATUS_RAW: Map<SubscriptionStatus, String> = linkedMapOf(
        SubscriptionStatus.ACTIVE to "active",
        SubscriptionStatus.TRIAL to "trial",
        SubscriptionStatus.EXPIRED to "expired",
        SubscriptionStatus.UNKNOWN to "unknown",
    )

    fun encode(e: CachedEntitlement): String = buildString {
        append(KEY_SCHEMA).append('=').append(SCHEMA_VERSION).append('\n')
        append(KEY_STATUS).append('=').append(STATUS_RAW.getValue(e.status)).append('\n')
        append(KEY_CONFIRMED_AT).append('=').append(e.confirmedAtMs).append('\n')
        for ((k, v) in e.extras.toSortedMap()) {
            if (k in KNOWN_KEYS || !safeKey(k) || !safeValue(v)) continue
            append(k).append('=').append(v).append('\n')
        }
    }

    /**
     * Hand-written, tolerant decoder: unknown keys are kept aside, a missing or unknown
     * status becomes UNKNOWN, a missing time becomes 0 (= long ago, so it grants no grace).
     * Only text that is not this format at all is [CacheRead.Corrupt].
     */
    fun decode(text: String): CacheRead {
        val values = LinkedHashMap<String, String>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) return CacheRead.Corrupt("line without key: ${line.take(40)}")
            values[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
        }
        if (values.isEmpty()) return CacheRead.Corrupt("empty")
        if (KEY_STATUS !in values && KEY_CONFIRMED_AT !in values) return CacheRead.Corrupt("no known key")
        val status = STATUS_RAW.entries.firstOrNull { it.value == values[KEY_STATUS] }?.key ?: SubscriptionStatus.UNKNOWN
        val confirmed = values[KEY_CONFIRMED_AT]?.toLongOrNull() ?: 0L
        val schema = values[KEY_SCHEMA]?.toIntOrNull() ?: 1
        return CacheRead.Loaded(CachedEntitlement(status, confirmed, schema, values.filterKeys { it !in KNOWN_KEYS }))
    }

    private val KNOWN_KEYS = setOf(KEY_SCHEMA, KEY_STATUS, KEY_CONFIRMED_AT)
    private fun safeValue(s: String) = s.none { it == '\n' || it == '\r' }
    private fun safeKey(s: String) = s.isNotBlank() && safeValue(s) && '=' !in s && !s.trim().startsWith("#")
}

/**
 * The file behind [EntitlementCache], plain java.io so it runs in JVM tests.
 * Writes go to a temp file, are synced and renamed over the old one; the previous file is
 * kept as ".bak" and read when the main file is missing or corrupt.
 */
class EntitlementStore(private val dir: File) {
    val file = File(dir, EntitlementCache.FILE_NAME)
    private val backup = File(dir, EntitlementCache.FILE_NAME + ".bak")
    private val temp = File(dir, EntitlementCache.FILE_NAME + ".tmp")

    fun read(): CacheRead {
        val main = readOne(file, quarantine = true)
        if (main is CacheRead.Loaded || main is CacheRead.Unreadable) return main
        val bak = readOne(backup, quarantine = false)
        return if (bak is CacheRead.Loaded) bak else main
    }

    /** Writes [e], keeping unknown keys of the current file. Returns false on I/O failure. */
    fun write(e: CachedEntitlement): Boolean {
        val old = readOne(file, quarantine = false)
        val extras = (old as? CacheRead.Loaded)?.entry?.extras.orEmpty() + e.extras
        val text = EntitlementCache.encode(e.copy(extras = extras))
        return try {
            dir.mkdirs()
            FileOutputStream(temp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (old is CacheRead.Loaded) file.copyTo(backup, overwrite = true)
            if (!temp.renameTo(file)) throw IOException("rename failed")
            // read back: a write that does not read back is not a write
            file.readText(Charsets.UTF_8) == text
        } catch (ex: IOException) {
            temp.delete()
            false
        }
    }

    private fun readOne(f: File, quarantine: Boolean): CacheRead {
        if (!f.exists()) return CacheRead.Missing
        val text = try {
            f.readText(Charsets.UTF_8)
        } catch (ex: IOException) {
            return CacheRead.Unreadable(ex.message ?: "io")
        }
        val r = EntitlementCache.decode(text)
        if (r is CacheRead.Corrupt && quarantine) quarantine(f)
        return r
    }

    /** Moves a corrupt file aside instead of overwriting it. At most [MAX_QUARANTINED]
     *  copies are kept; beyond that the corrupt file is left where it is and replaced by
     *  the next write. */
    private fun quarantine(f: File) {
        val existing = dir.listFiles { x -> x.name.startsWith(f.name + ".corrupt-") }?.size ?: 0
        if (existing >= MAX_QUARANTINED) return
        f.renameTo(File(dir, f.name + ".corrupt-" + System.currentTimeMillis()))
    }

    companion object {
        const val MAX_QUARANTINED = 3
    }
}
