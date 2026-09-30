package de.merzkevin.spiegel.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityTest {

    @Test fun deviceNameIsUsedAsIs() {
        assertEquals("Kevins 2. FireTVStick", receiverName("Kevins 2. FireTVStick"))
    }

    @Test fun missingOrBlankNameFallsBackToFireTv() {
        assertEquals("Fire TV", receiverName(null))
        assertEquals("Fire TV", receiverName(""))
        assertEquals("Fire TV", receiverName("   \t "))
        assertEquals("Fire TV", receiverName("\u0000\u0007"))
    }

    @Test fun controlCharactersAndRepeatedSpacesAreRemoved() {
        assertEquals("Wohnzimmer TV", receiverName("  Wohnzimmer\n\t  TV  "))
    }

    @Test fun trailingDotsAreRemovedBecauseDnsSdWouldTreatThemAsDomain() {
        assertEquals("Küche", receiverName("Küche..."))
    }

    @Test fun reservedNameLocalFallsBack() {
        assertEquals("Fire TV", receiverName("local"))
        assertEquals("Fire TV", receiverName(".local"))
    }

    @Test fun longNamesAreCutToFiftyUtf8BytesWithoutSplittingCharacters() {
        val name = receiverName("Ä".repeat(40)) // 80 bytes
        assertEquals(25, name.length)
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES)
        val emoji = receiverName("TV " + "📺".repeat(20)) // 3 + 80 bytes
        assertTrue(emoji.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES)
        assertFalse("no broken surrogate", emoji.any { Character.isSurrogate(it) } && emoji.codePoints().anyMatch { it in 0xD800..0xDFFF })
        assertEquals("TV " + "📺".repeat(11), emoji)
    }

    @Test fun longNamesAreCutAtAWordBoundary() {
        assertEquals("Wohnzimmer Fernseher im Erdgeschoss neben dem",
            receiverName("Wohnzimmer Fernseher im Erdgeschoss neben dem Kamin"))
        // a cut right before a space keeps the whole last word
        assertEquals("a".repeat(24) + " " + "b".repeat(25), receiverName("a".repeat(24) + " " + "b".repeat(25) + " cc"))
        // an early space is not used: the name would lose too much
        assertEquals("ab " + "c".repeat(47), receiverName("ab " + "c".repeat(60)))
    }

    @Test fun nameExactlyAtTheLimitIsKept() {
        val s = "a".repeat(MAX_NAME_BYTES)
        assertEquals(s, receiverName(s))
        assertEquals("a".repeat(MAX_NAME_BYTES), receiverName("a".repeat(MAX_NAME_BYTES + 1)))
    }

    @Test fun deviceIdIsStableForTheSameAndroidId() {
        assertArrayEquals(deviceIdFor("3f2a9c01d4e5b6a7"), deviceIdFor("3f2a9c01d4e5b6a7"))
        assertArrayEquals(deviceIdFor("3F2A9C01D4E5B6A7 "), deviceIdFor("3f2a9c01d4e5b6a7"))
    }

    @Test fun deviceIdDiffersBetweenDevices() {
        assertFalse(deviceIdFor("3f2a9c01d4e5b6a7").contentEquals(deviceIdFor("3f2a9c01d4e5b6a8")))
    }

    @Test fun deviceIdIsALocallyAdministeredUnicastMac() {
        for (id in listOf("a", "3f2a9c01d4e5b6a7", "ffffffffffffffff", "0000000000000000", "zz")) {
            val mac = deviceIdFor(id)
            assertEquals(6, mac.size)
            assertEquals("locally administered bit for $id", 0x02, mac[0].toInt() and 0x02)
            assertEquals("unicast bit for $id", 0, mac[0].toInt() and 0x01)
        }
    }

    @Test fun deviceIdIsPinned() {
        // frozen: changing the derivation gives every installed receiver a new identity,
        // so iPhones would list it as a new device
        assertEquals("62:40:D4:9A:D3:FA", formatDeviceId(deviceIdFor("3f2a9c01d4e5b6a7")))
    }

    @Test fun missingOrBrokenAndroidIdGivesTheFixedFallback() {
        val fallback = deviceIdFor(null)
        assertEquals("0A:53:50:49:45:47", formatDeviceId(fallback))
        assertArrayEquals(fallback, deviceIdFor(""))
        assertArrayEquals(fallback, deviceIdFor("9774d56d682e549c"))
        // callers cannot corrupt the shared fallback
        fallback[0] = 0
        assertEquals("0A:53:50:49:45:47", formatDeviceId(deviceIdFor(null)))
    }
}
