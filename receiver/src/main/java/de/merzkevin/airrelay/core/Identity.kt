/*
 * AirRelay, Copyright (C) 2026 Kevin Merz
 * SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-AirRelay-Amazon-Appstore-SDK-exception
 * GNU GPL version 3 or later, with the additional permission in LICENSE-EXCEPTION.md.
 */
package de.merzkevin.airrelay.core

import java.security.MessageDigest

const val FALLBACK_RECEIVER_NAME = "Fire TV"

/**
 * UTF-8 byte budget for the receiver name. A dns-sd instance label holds at most 63 bytes
 * and the _raop._tcp name is "<12 hex digits>@<name>", which leaves 50.
 */
const val MAX_NAME_BYTES = 50

/**
 * Receiver name shown on the iPhone: the Fire TV's own device name, cleaned up so it is a
 * valid dns-sd label, or [FALLBACK_RECEIVER_NAME].
 */
fun receiverName(deviceName: String?, maxBytes: Int = MAX_NAME_BYTES): String {
    if (deviceName == null) return FALLBACK_RECEIVER_NAME
    val cleaned = deviceName
        .filterNot { it.isISOControl() }
        .replace(Regex("\\s+"), " ")
        .trim()
        .trimEnd('.')
        .trim()
    if (cleaned.isEmpty() || cleaned.trim('.').equals("local", ignoreCase = true)) return FALLBACK_RECEIVER_NAME
    return truncateUtf8(cleaned, maxBytes)
}

/**
 * Name announced on both dns-sd services and shown on the TV. The store package announces
 * [receiverName] unchanged; the test package appends [TestPackage.NAME_SUFFIX] and keeps the
 * whole name within [MAX_NAME_BYTES].
 */
fun announcedReceiverName(deviceName: String?, testPackage: Boolean): String {
    if (!testPackage) return receiverName(deviceName)
    val room = MAX_NAME_BYTES - TestPackage.NAME_SUFFIX.toByteArray(Charsets.UTF_8).size
    return receiverName(deviceName, room) + TestPackage.NAME_SUFFIX
}

/**
 * Cuts [s] to at most [maxBytes] UTF-8 bytes without splitting a character, and at a
 * word boundary when the last space is in the second half ("... neben dem" instead of
 * "... neben dem Kami").
 */
fun truncateUtf8(s: String, maxBytes: Int): String {
    if (s.toByteArray(Charsets.UTF_8).size <= maxBytes) return s
    var bytes = 0
    val out = StringBuilder()
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        val n = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
        if (bytes + n > maxBytes) break
        out.appendCodePoint(cp)
        bytes += n
        i += Character.charCount(cp)
    }
    val cutInsideWord = i < s.length && !s[i].isWhitespace()
    val lastSpace = out.lastIndexOf(' ')
    if (cutInsideWord && lastSpace > out.length / 2) out.setLength(lastSpace)
    return out.toString().trim()
}

/** Settings.Secure.ANDROID_ID value that some old builds return for every device. */
private const val BROKEN_ANDROID_ID = "9774d56d682e549c"

private val FALLBACK_DEVICE_ID = byteArrayOf(0x0A, 0x53, 0x50, 0x49, 0x45, 0x47)

/**
 * The 6-byte AirPlay device id. Derived from ANDROID_ID instead of the Wi-Fi MAC (not
 * readable on newer Android) or a random value (would have to be stored). Stable for the
 * life of the installation, nothing written to storage.
 *
 * The result is a locally administered unicast MAC (bit 1 set, bit 0 clear in byte 0).
 * UxPlay also seeds the pairing key from this id, so the key is stable too.
 */
fun deviceIdFor(androidId: String?): ByteArray {
    val id = androidId?.trim()?.lowercase()
    if (id.isNullOrEmpty() || id == BROKEN_ANDROID_ID) return FALLBACK_DEVICE_ID.copyOf()
    // The salt keeps its working-title spelling on purpose: changing it changes the id every
    // iPhone has already paired with.
    val hash = MessageDigest.getInstance("SHA-256")
        .digest("spiegel-device-id:$id".toByteArray(Charsets.UTF_8))
    val mac = hash.copyOf(6)
    mac[0] = ((mac[0].toInt() or 0x02) and 0xFE).toByte()
    return mac
}

/** "AA:BB:CC:DD:EE:FF" */
fun formatDeviceId(mac: ByteArray): String = mac.joinToString(":") { "%02X".format(it) }
