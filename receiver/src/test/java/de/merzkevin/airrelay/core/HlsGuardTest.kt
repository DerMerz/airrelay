/*
 * AirRelay, Copyright (C) 2026 Kevin Merz
 * SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-AirRelay-Amazon-Appstore-SDK-exception
 * GNU GPL version 3 or later, with the additional permission in LICENSE-EXCEPTION.md.
 */
package de.merzkevin.airrelay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * HLS video is a test-package feature for now (0.1.5 Durchstich). These checks keep the store
 * build exactly as 0.1.4: no player library in it, HLS announced by nothing it ships, every
 * native HLS callback inert unless switched on, and every method native code looks up by
 * name kept by R8.
 */
class HlsGuardTest {
    private val moduleDir: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "src/main/AndroidManifest.xml").exists() && File(it, "build.gradle.kts").exists() }

    private fun read(path: String): String {
        val f = File(moduleDir, path)
        assertTrue("$path is missing", f.exists())
        return f.readText()
    }

    @Test fun playerLibraryIsTestBuildOnly() {
        val gradle = read("build.gradle.kts")
        val media3 = gradle.lines().filter { "androidx.media3" in it && !it.trim().startsWith("//") }
        assertTrue("no media3 dependency found, the test player is gone", media3.isNotEmpty())
        for (line in media3) {
            assertTrue("media3 must stay debugImplementation: $line", line.trim().startsWith("debugImplementation("))
        }
    }

    @Test fun releaseFactoryHasNoPlayer() {
        val f = read("src/release/java/de/merzkevin/airrelay/video/HlsVideoFactory.kt")
        assertTrue("release HlsVideoFactory must say AVAILABLE = false", f.contains("const val AVAILABLE = false"))
        assertTrue("release HlsVideoFactory must return null", Regex("""fun create\([^)]*\): HlsVideo\? = null""").containsMatchIn(f))
        assertFalse("release HlsVideoFactory must not touch media3", f.contains("media3"))
        for (dir in listOf("src/main/java", "src/release/java")) {
            File(moduleDir, dir).walkTopDown().filter { it.isFile }.forEach {
                assertFalse("$it uses media3, only src/debug may", it.readText().contains("androidx.media3"))
            }
        }
    }

    @Test fun serviceAsksThePolicy() {
        val s = read("src/main/java/de/merzkevin/airrelay/ReceiverService.kt")
        assertTrue("ReceiverService must create the player only through HlsPolicy",
            s.contains("hls = if (HlsPolicy.enabled(packageName, HlsVideoFactory.AVAILABLE)) HlsVideoFactory.create(this, hlsReporter) else null"))
        assertEquals("nativeSetHlsEnabled must be called exactly once, behind the player check", 1,
            Regex("""NativeBridge\.nativeSetHlsEnabled\(""").findAll(s).count())
        val call = s.indexOf("NativeBridge.nativeSetHlsEnabled(")
        val guard = s.lastIndexOf("if (hls != null) {", call)
        assertTrue("nativeSetHlsEnabled must sit inside 'if (hls != null)'", guard in 0 until call && call - guard < 400)
        assertTrue("HLS must be switched on before the server starts and announces itself",
            call < s.indexOf("NativeBridge.nativeStart(handle"))
    }

    @Test fun nativeDefaultsStayOff() {
        val c = read("src/main/cpp/native_bridge.cpp")
        val init = c.substringAfter("Java_de_merzkevin_airrelay_bridge_NativeBridge_nativeInit")
            .substringBefore("Java_de_merzkevin_airrelay_bridge_NativeBridge_nativeStart")
        for (line in listOf("raop_set_plist(ctx->raop, \"hls\", 0);",
                "dnssd_set_airplay_features(ctx->dnssd, FEATURE_VIDEO, 0);",
                "dnssd_set_airplay_features(ctx->dnssd, FEATURE_HLS, 0);")) {
            assertTrue("nativeInit lost its default '$line'", init.contains(line))
        }
        assertFalse("nativeInit must not switch HLS on", init.contains("\"hls\", 1"))
    }

    /** Every HLS callback returns before doing anything unless nativeSetHlsEnabled ran. */
    @Test fun nativeHlsCallbacksAreInertUnlessEnabled() {
        val c = read("src/main/cpp/android_raop_callbacks.c")
        for (fn in listOf("_video_stop", "_video_reset", "_video_play", "_video_scrub", "_video_rate",
                "_video_acquire_playback_info", "_video_playlist_remove")) {
            val head = Regex("""static [a-z ]+\b$fn\(""").find(c)
            assertTrue("$fn is missing", head != null)
            val body = c.substring(head!!.range.last, c.indexOf("\n}", head.range.last))
            assertTrue("$fn must check ctx->hls_enabled first", Regex("""if \(!ctx->hls_enabled\b""").containsMatchIn(body))
        }
        val enable = c.substringAfter("int android_callbacks_enable_hls(").substringBefore("\n}")
        assertTrue("enable_hls must refuse without the Kotlin methods", enable.contains("if (!ctx->hls_methods_ok)"))
    }

    /** A method native code looks up by name must exist in NativeCallbacks and be kept by R8. */
    @Test fun nativeLookupsAreDeclaredAndKept() {
        val c = read("src/main/cpp/android_raop_callbacks.c")
        val kt = read("src/main/java/de/merzkevin/airrelay/bridge/NativeCallbacks.kt")
        val pro = read("proguard-rules.pro")
        val names = Regex("""GetMethodID\(env, cls, "(\w+)"""").findAll(c).map { it.groupValues[1] }.toList()
        assertEquals("expected 9 required plus 4 HLS lookups", 13, names.size)
        for (n in names) {
            assertTrue("NativeCallbacks lacks $n", Regex("""fun $n\(""").containsMatchIn(kt))
            assertTrue("proguard-rules.pro does not keep $n", Regex("""public \S+ $n\(""").containsMatchIn(pro))
        }
    }
}
