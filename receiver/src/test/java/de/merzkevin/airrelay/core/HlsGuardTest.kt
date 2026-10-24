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
 * HLS video ships in the store package since 0.1.5. These checks keep what that means: the
 * player is in every build (not only the test package), a sender without a subscription is
 * refused at /play natively and again in Kotlin, nothing in the app texts names a brand, every
 * native HLS callback stays inert unless switched on, and every method native code looks up by
 * name is kept by R8.
 */
class HlsGuardTest {
    private val moduleDir: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "src/main/AndroidManifest.xml").exists() && File(it, "build.gradle.kts").exists() }

    private fun read(path: String): String {
        val f = File(moduleDir, path)
        assertTrue("$path is missing", f.exists())
        return f.readText()
    }

    /** Replaces releaseFactoryHasNoPlayer (0.1.4): the store package now carries the player. */
    @Test fun playerShipsInStorePackage() {
        val gradle = read("build.gradle.kts")
        val media3 = gradle.lines().filter { "androidx.media3" in it && !it.trim().startsWith("//") }
        for (lib in listOf("androidx.media3:media3-exoplayer:", "androidx.media3:media3-exoplayer-hls:")) {
            assertTrue("$lib is no longer a dependency", media3.any { lib in it })
        }
        for (line in media3) {
            assertTrue("media3 must be implementation(...) so the store package has it: $line",
                line.trim().startsWith("implementation("))
        }
        for (variant in listOf("debug", "release")) {
            for (name in listOf("HlsVideoFactory.kt", "HlsActivity.kt")) {
                assertFalse("src/$variant must not have its own $name (one player for both packages)",
                    File(moduleDir, "src/$variant/java/de/merzkevin/airrelay/video/$name").exists())
            }
        }
        val factory = read("src/main/java/de/merzkevin/airrelay/video/HlsVideoFactory.kt")
        assertTrue("HlsVideoFactory.create must always return the ExoPlayer implementation",
            Regex("""fun create\(context: Context, reporter: HlsVideo\.Reporter\): HlsVideo =\s*ExoHlsVideo\(""")
                .containsMatchIn(factory))
        val manifest = read("src/main/AndroidManifest.xml")
        assertTrue("HlsActivity must be declared in the main manifest",
            Regex("""<activity\s+android:name="\.video\.HlsActivity"\s+android:exported="false"""").containsMatchIn(manifest))
        val tag = manifest.substringAfter("android:name=\".video.HlsActivity\"").substringBefore("/>")
        assertFalse("HlsActivity must not be switched off", tag.contains("android:enabled"))
        assertTrue("the player reads the playlist from localhost: networkSecurityConfig is gone",
            manifest.contains("android:networkSecurityConfig=\"@xml/hls_localhost\""))
        val net = read("src/main/res/xml/hls_localhost.xml")
        assertFalse("plain HTTP must stay limited to this device", net.contains("base-config"))
        assertEquals("exactly localhost and 127.0.0.1 may use plain HTTP",
            listOf("localhost", "127.0.0.1"), Regex("""<domain[^>]*>([^<]+)</domain>""").findAll(net).map { it.groupValues[1] }.toList())
        assertFalse("the debug manifest must not carry HLS parts of its own",
            read("src/debug/AndroidManifest.xml").contains("Hls"))
    }

    @Test fun serviceAlwaysCreatesThePlayerAndSwitchesItOnBeforeStart() {
        val s = read("src/main/java/de/merzkevin/airrelay/ReceiverService.kt")
        assertTrue("ReceiverService must create the player in every package",
            s.contains("hls = HlsVideoFactory.create(this, hlsReporter)"))
        assertEquals("nativeSetHlsEnabled must be called exactly once, behind the player check", 1,
            Regex("""NativeBridge\.nativeSetHlsEnabled\(""").findAll(s).count())
        val call = s.indexOf("NativeBridge.nativeSetHlsEnabled(")
        val guard = s.lastIndexOf("if (hls != null) {", call)
        assertTrue("nativeSetHlsEnabled must sit inside 'if (hls != null)'", guard in 0 until call && call - guard < 400)
        assertTrue("HLS must be switched on before the server starts and announces itself",
            call < s.indexOf("NativeBridge.nativeStart(handle"))
    }

    /** Second lock: a /play that native code let through still plays nothing without the gate. */
    @Test fun playAsksTheGateBeforeThePlayer() {
        val s = read("src/main/java/de/merzkevin/airrelay/ReceiverService.kt")
        val body = s.substringAfter("override fun onVideoPlay(url: String, startSec: Float) {")
            .substringBefore("\n    }\n")
        val gate = body.indexOf("if (!LicenseGate.admitsSender()) {")
        val play = body.indexOf("hls?.play(url, startSec)")
        assertTrue("onVideoPlay no longer asks the gate", gate >= 0)
        assertTrue("onVideoPlay no longer plays", play >= 0)
        assertTrue("the gate must come before the player", gate < play)
        val refused = body.substring(gate, play)
        assertTrue("a refused /play must return before the player", refused.contains("return"))
        assertTrue("a refused /play must tell the sender the video ended",
            refused.contains("hlsReporter.report(0f, -1f, 0f, false)"))
        assertTrue("a refused /play must bring up the offer like a refused mirroring",
            refused.contains("bringToFront(FrontReason.SENDER_REFUSED)"))
    }

    /** First lock: UxPlay answers 403 at /play before parsing anything (patch 0009). */
    @Test fun nativePlayRefusesUnadmittedSender() {
        val p = read("src/main/cpp/patches/UxPlay/0009-refuse-unadmitted-client-on-play.patch")
        assertTrue("0009 must patch lib/http_handlers.h", p.contains("+++ b/lib/http_handlers.h"))
        val added = p.lines().filter { it.startsWith("+") && !it.startsWith("+++") }.joinToString("\n")
        assertTrue("0009 must fail closed", added.contains("bool admit_client = false;"))
        assertTrue("0009 must ask the subscription callback",
            added.contains("raop->callbacks.report_client_request(raop->callbacks.cls, NULL, NULL, NULL, &admit_client);"))
        assertTrue("0009 must answer 403", added.contains("http_response_init(response, \"HTTP/1.1\", 403, \"Forbidden\");"))
        val ifAt = added.indexOf("if (admit_client == false) {")
        assertTrue("0009 must return after the 403", ifAt >= 0 && added.indexOf("return;", ifAt) > ifAt)
        val context = p.lines().filter { it.startsWith(" ") }.joinToString("\n")
        assertTrue("0009 must sit in http_handler_play", context.contains("\"http_handler_play\");"))
        val gradle = read("build.gradle.kts")
        assertTrue("patches are no longer applied from the patches folder", gradle.contains("applyUxplayPatches"))
    }

    /** Store texts must not name a video service (Amazon review, trademark). */
    @Test fun noBrandNameInAppTexts() {
        val res = File(moduleDir, "src/main/res")
        val files = res.walkTopDown().filter { it.isFile && it.name == "strings.xml" }.toList()
        assertTrue("no strings.xml found", files.size >= 2)
        for (f in files) {
            assertFalse("${f.parentFile?.name}/strings.xml names YouTube", f.readText().contains("youtube", ignoreCase = true))
        }
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
