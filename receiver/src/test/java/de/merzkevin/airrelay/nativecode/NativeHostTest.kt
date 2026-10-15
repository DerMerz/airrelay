/*
 * AirRelay, Copyright (C) 2026 Kevin Merz
 * SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-AirRelay-Amazon-Appstore-SDK-exception
 * GNU GPL version 3 or later, with the additional permission in LICENSE-EXCEPTION.md.
 */
package de.merzkevin.airrelay.nativecode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Native code that replaced GPL-only parts in 0.1.1, run on the build machine
 * (src/test/native/build_host.sh, Gradle task buildHostNativeTests):
 *
 * - the crypto rewrite (src/main/cpp/crypto) against the vectors measured from the 0.1.0
 *   binary (resources/native/crypto-vectors-0.1.0.txt, independently checked by
 *   tools/verify_vectors_independent.py). Among them the AirPlay identity: the public key
 *   that senders have seen for the device ids of the compatibility contract.
 * - the playfair helper program against resources/native/playfair-vectors-0.1.0.txt, and
 *   the process call around it (success, crash, timeout, broken output, no helper).
 *
 * A missing binary or fixture is a failure, never a skip.
 */
class NativeHostTest {
    companion object {
        private lateinit var bin: File
        private lateinit var res: File
        private lateinit var tmp: File

        @BeforeClass @JvmStatic fun locate() {
            val dir: String? = System.getProperty("airrelay.hostNative")
            if (dir == null) fail("system property airrelay.hostNative not set (Gradle task buildHostNativeTests)")
            bin = File(dir!!)
            val module = generateSequence(File("").absoluteFile) { it.parentFile }
                .first { File(it, "src/main/AndroidManifest.xml").exists() && File(it, "build.gradle.kts").exists() }
            res = File(module, "src/test/resources/native")
            for (n in listOf("crypto_vectors", "libairrelay_playfair", "playfair_pipe_test")) {
                assertTrue("host binary $n missing in $bin", File(bin, n).canExecute())
            }
            for (n in listOf("crypto-vectors-0.1.0.txt", "playfair-vectors-0.1.0.txt", "rfc8032-test1.seed.hex")) {
                assertTrue("fixture $n missing", File(res, n).isFile)
            }
            tmp = Files.createTempDirectory("airrelay-native").toFile().apply { deleteOnExit() }
        }

        /** Runs a command, returns stdout; fails on a non-zero exit or after 30 s. */
        fun run(vararg cmd: String): String {
            val p = ProcessBuilder(*cmd).redirectError(ProcessBuilder.Redirect.to(File("/dev/null"))).start()
            val out = p.inputStream.bufferedReader().readText()
            assertTrue("${cmd.first()} did not finish", p.waitFor(30, TimeUnit.SECONDS))
            assertEquals("${cmd.joinToString(" ")} exit code", 0, p.exitValue())
            return out
        }

        /**
         * The RFC 8032 test 1 seed as the PKCS#8 PEM file the crypto API reads as its key file.
         * Kept as hex in the repository: a PEM private key block in a public repository looks
         * like a leaked secret to scanners, although this one is printed in the RFC.
         */
        fun rfc8032Pem(seedFile: File): String {
            val seed = seedFile.readLines().map { it.trim() }.single { it.isNotEmpty() && !it.startsWith("#") }
            assertEquals("RFC 8032 seed length", 64, seed.length)
            val der = (PKCS8_ED25519_PREFIX + seed).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val b64 = java.util.Base64.getEncoder().encodeToString(der)
            val label = "PRIVATE" + " KEY"
            return "-----BEGIN $label-----\n$b64\n-----END $label-----\n"
        }

        private const val PKCS8_ED25519_PREFIX = "302e020100300506032b657004220420"

        fun lines(text: String): Map<String, String> = text.lines().filter { it.isNotBlank() }
            .associate { it.substringBefore(' ') to it.substringAfter(' ') }
    }

    private fun cryptoNow(): Map<String, String> {
        val pem = File(tmp, "rfc.pem").apply { writeText(rfc8032Pem(File(res, "rfc8032-test1.seed.hex"))) }
        val key = File(tmp, "scratch.key").apply { delete() }
        return lines(run(File(bin, "crypto_vectors").path, key.path, pem.path))
    }

    /** The only differences to 0.1.0 allowed, each with the reason. Everything else must be equal. */
    private val intendedDifferences = mapOf(
        // 0.1.0 kept counting consumed bytes across aes_ctr_reset; no caller resets a CTR context
        "ctr.fresh_after_reset.16" to { now: Map<String, String> ->
            assertEquals("after a reset the keystream starts on a block boundary, nothing is skipped",
                "668bd517521404d169de14b149765277", now["ctr.fresh_after_reset.16"])
        },
        // 0.1.0 ended the process on a partial CBC block; callers only pass whole blocks
        "cbc.partial" to { now ->
            assertEquals("whole blocks decrypted, the partial rest left untouched",
                "3189507667324ae8a7824c5791d573b8" + "aa".repeat(16), now["cbc.partial.dec20"])
            assertEquals("nothing carried into the next call", "3189507667324ae8a7824c5791d573b8", now["cbc.partial.after_reset16"])
        },
        // 0.1.0 ended the whole app when a sender offered the all-zero X25519 key
        "x.zero_point" to { now ->
            assertEquals("secret written", "1", now["x.zero_point.written"])
            assertEquals("secret is not the all-zero value an attacker knows", "1", now["x.zero_point.not_zero"])
            assertEquals("secret is random, so the handshake fails", "1", now["x.zero_point.unpredictable"])
        },
    )

    @Test fun cryptoRewriteGivesTheResultsOfTheShippedBinary() {
        val frozen = File(res, "crypto-vectors-0.1.0.txt").readLines().filter { it.isNotBlank() }
        assertEquals("frozen vector file shrank or grew", 144, frozen.size)
        val now = cryptoNow()
        var equal = 0
        for (line in frozen) {
            val name = line.substringBefore(' ')
            val check = intendedDifferences[name]
            if (check != null) { check(now); continue }
            assertEquals("crypto vector $name differs from 0.1.0", line.substringAfter(' '), now[name])
            equal++
        }
        assertEquals("compared values", 141, equal)
        val known = frozen.map { it.substringBefore(' ') }.toSet()
        val extra = now.keys.filter { it !in known && intendedDifferences.keys.none { k -> it.startsWith("$k.") } }
        assertTrue("new values without a frozen counterpart: $extra", extra.isEmpty())
    }

    @Test fun airplayIdentityOfTheContractDevicesIsUnchanged() {
        // device ids of contract/compat-contract.txt; the key is announced as "pk" in dns-sd
        val now = cryptoNow()
        assertEquals("73d4373f6ae3fa5ea147333d562fa67d2e468376975776d21d755eff9f47a7c6", now["ed.derived.5.pk"])
        assertEquals("9395bef2907c6c2faf2e590a278adbe7867ce89625e7b508090c6f4db3539a60", now["ed.derived.6.pk"])
        assertEquals("a9a0fc3003faaaa3edfb2a7ea7329aed138804e7c18af3676f730168a618b310", now["ed.derived.7.pk"])
        for (i in 0..7) assertEquals("key for device $i not stable", "1", now["ed.derived.$i.stable"])
    }

    @Test fun cryptoRewriteMeetsRfc8032() {
        val now = cryptoNow()
        assertEquals("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", now["ed.rfc8032_1.pk"])
        assertEquals("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
            now["ed.rfc8032_1.sig"])
        assertEquals("1", now["ed.rfc8032_1.verify"])
        assertEquals("ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f", now["sha512.abc"])
        assertEquals("900150983cd24fb0d6963f7d28e17f72", now["get_md5.abc"])
        assertEquals("1", now["x.agree"])
    }

    private fun call(helper: String, timeoutMs: Int = 2000, mode: Int = 0): Map<String, String> =
        lines(run(File(bin, "playfair_pipe_test").path, "call", helper, "$timeoutMs", "$mode"))

    private fun fake(name: String, body: String): String {
        val f = File(tmp, name)
        f.writeText("#!/bin/sh\n$body\n")
        f.setExecutable(true)
        return f.path
    }

    @Test fun playfairHelperGivesTheKeysOfTheShippedPlayfair() {
        val out = run(File(bin, "playfair_pipe_test").path, "vectors", File(bin, "libairrelay_playfair").path,
            File(res, "playfair-vectors-0.1.0.txt").path)
        val rows = out.lines().filter { it.startsWith("vector ") }
        assertEquals("vector count", 12, rows.size)
        for (r in rows) assertTrue("helper result differs: $r", r.contains(" result 0 match 1 "))
        assertTrue(out.contains("children_left 0"))
        val us = rows.map { it.substringAfter(" us ").trim().toLong() }.sorted()
        println("playfair helper on this machine: median ${us[us.size / 2] / 1000.0} ms, max ${us.last() / 1000.0} ms")
    }

    @Test fun playfairHelperRefusesAnInvalidMode() {
        val r = call(File(bin, "libairrelay_playfair").path, mode = 4)
        assertEquals("mode 4 is undefined in playfair: refused with exit code 4", "-7", r["result"])
        assertEquals("0".repeat(32), r["key"])
    }

    @Test fun processCallSuccess() {
        val r = call(fake("ok.sh", "cat >/dev/null; printf 'ABCDEFGHIJKLMNOP'"))
        assertEquals("0", r["result"])
        assertEquals("4142434445464748494a4b4c4d4e4f50", r["key"])
        assertEquals("0", r["children_left"])
    }

    @Test fun processCallCrash() {
        val r = call(fake("crash.sh", "cat >/dev/null; kill -SEGV $$"))
        assertEquals("-6", r["result"])
        assertEquals("key zeroed", "0".repeat(32), r["key"])
        assertEquals("0", r["children_left"])
    }

    @Test fun processCallTimeoutKillsTheHelper() {
        val r = call(fake("hang.sh", "exec sleep 20"), timeoutMs = 300)
        assertEquals("-5", r["result"])
        assertEquals("0".repeat(32), r["key"])
        val ms = r.getValue("ms").toLong()
        assertTrue("timeout took $ms ms", ms in 250..1500)
        assertEquals("helper not reaped", "0", r["children_left"])
    }

    @Test fun processCallTimeoutWhenTheHelperAnswersButDoesNotExit() {
        val r = call(fake("linger.sh", "cat >/dev/null; printf 'ABCDEFGHIJKLMNOP'; exec sleep 20"), timeoutMs = 300)
        assertEquals("-5", r["result"])
        assertEquals("an answer without an exit is not trusted", "0".repeat(32), r["key"])
        assertEquals("0", r["children_left"])
    }

    @Test fun processCallSlowButInTime() {
        val r = call(fake("slow.sh", "cat >/dev/null; sleep 0.2; printf 'ABCDEFGHIJKLMNOP'"), timeoutMs = 2000)
        assertEquals("0", r["result"])
        assertEquals("4142434445464748494a4b4c4d4e4f50", r["key"])
    }

    @Test fun processCallShortOutput() {
        val r = call(fake("short.sh", "cat >/dev/null; printf 'ABCDEFGHIJKLMNO'"))
        assertEquals("-8", r["result"])
        assertEquals("0".repeat(32), r["key"])
    }

    @Test fun processCallLongOutput() {
        val r = call(fake("long.sh", "cat >/dev/null; printf 'ABCDEFGHIJKLMNOPQ'"))
        assertEquals("-8", r["result"])
        assertEquals("0".repeat(32), r["key"])
    }

    @Test fun processCallNonZeroExit() {
        val r = call(fake("exit1.sh", "cat >/dev/null; printf 'ABCDEFGHIJKLMNOP'; exit 1"))
        assertEquals("-7", r["result"])
        assertEquals("0".repeat(32), r["key"])
    }

    @Test fun processCallMissingHelper() {
        val r = call(File(tmp, "does-not-exist").path)
        assertEquals("-3", r["result"])
        assertEquals("0", r["children_left"])
    }

    @Test fun processCallWithoutHelperPath() {
        val r = call("-")
        assertEquals("-1", r["result"])
        assertEquals("0".repeat(32), r["key"])
    }
}
