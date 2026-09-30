#!/usr/bin/env python3
"""Counter-check for the unit tests: removes one safeguard at a time and requires the
tests to go red. A test that stays green without its safeguard protects nothing.

    python3 tools/mutation_check.py            # all mutations
    python3 tools/mutation_check.py 3 7        # only numbers 3 and 7

Every file is restored after each mutation, also on Ctrl-C. Needs JAVA_HOME and
ANDROID_HOME like the normal build.
"""
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MAIN = "receiver/src/main/java/de/merzkevin/airrelay/"
CORE = MAIN + "core/"

# (description, file, original text, mutated text)
MUTATIONS = [
    ("screen: entitlement no longer checked first", CORE + "ScreenDecision.kt",
     "if (entitlement == Entitlement.LOCKED) return Screen.LOCKED", ""),
    ("screen: mirroring shown before the first picture", CORE + "ScreenDecision.kt",
     "mirroring && firstFrameShown -> Screen.MIRRORING", "mirroring -> Screen.MIRRORING"),
    ("screen: audio wins over mirroring", CORE + "ScreenDecision.kt",
     "            mirroring && firstFrameShown -> Screen.MIRRORING\n            mirroring -> Screen.CONNECTING\n            audioSession -> Screen.AUDIO",
     "            audioSession -> Screen.AUDIO\n            mirroring && firstFrameShown -> Screen.MIRRORING\n            mirroring -> Screen.CONNECTING"),
    ("back: licence overlay ignored", CORE + "BackDecision.kt",
     "if (licensesOpen) return BackAction.CLOSE_LICENSES", ""),
    ("back: first press stops mirroring", CORE + "BackDecision.kt",
     "if (stopHintVisible) BackAction.STOP_MIRRORING else BackAction.SHOW_STOP_HINT", "BackAction.STOP_MIRRORING"),
    ("start: port failure reported as start failure", CORE + "ReceiverConfig.kt",
     "boundPort == null -> ServerStatus.PORT_IN_USE", "boundPort == null -> ServerStatus.START_FAILED"),
    ("name: control characters kept", CORE + "Identity.kt",
     ".filterNot { it.isISOControl() }", ""),
    ("name: reserved 'local' accepted", CORE + "Identity.kt",
     " || cleaned.trim('.').equals(\"local\", ignoreCase = true)", ""),
    ("name: cut inside a word", CORE + "Identity.kt",
     "if (cutInsideWord && lastSpace > out.length / 2) out.setLength(lastSpace)", ""),
    ("name: byte limit ignored", CORE + "Identity.kt",
     "if (bytes + n > maxBytes) break", ""),
    ("device id: multicast bit not cleared", CORE + "Identity.kt",
     "mac[0] = ((mac[0].toInt() or 0x02) and 0xFE).toByte()", "mac[0] = (mac[0].toInt() or 0x02).toByte()"),
    ("device id: derivation changed (new identity for every TV)", CORE + "Identity.kt",
     "\"spiegel-device-id:$id\"", "\"spiegel-id:$id\""),
    ("device id: broken ANDROID_ID accepted", CORE + "Identity.kt",
     " || id == BROKEN_ANDROID_ID", ""),
    ("fit: picture not centred", CORE + "VideoGeometry.kt",
     "return Rect((container.width - w) / 2, (container.height - h) / 2, w, h)", "return Rect(0, 0, w, h)"),
    ("fit: portrait stretched to full width", CORE + "VideoGeometry.kt",
     "val contentWider = content.width.toLong() * container.height >= container.width.toLong() * content.height",
     "val contentWider = true"),
    ("size: 1080p cap removed", CORE + "VideoGeometry.kt",
     "minOf(source.width, limit.width, MAX_MIRROR_SIZE.width)", "minOf(source.width, limit.width)"),
    ("size: decoder limit ignored", CORE + "VideoGeometry.kt",
     "val limit = decoderMax?.takeIf { it.isValid } ?: MAX_MIRROR_SIZE", "val limit = MAX_MIRROR_SIZE"),
    ("size: decoder crop ignored", CORE + "VideoGeometry.kt",
     "return if (hasCrop) Size(cropRight - cropLeft + 1, cropBottom - cropTop + 1) else Size(width, height)",
     "return Size(width, height)"),
    ("keyframe: SPS not recognised", CORE + "FrameBuffers.kt",
     "if (type == 5 || type == 7) return true", "if (type == 5) return true"),
    ("keyframe: 3-byte start codes not recognised", CORE + "FrameBuffers.kt",
     "data[i + 2] == 1.toByte() -> i + 3", "false -> i + 3"),
    ("gop: overflow keeps growing", CORE + "FrameBuffers.kt",
     "            if (bytes + frame.data.size > maxBytes) {\n                frames.clear()",
     "            if (false) {\n                frames.clear()"),
    ("gop: delta frame accepted without keyframe", CORE + "FrameBuffers.kt",
     "            if (frames.isEmpty()) return false\n", ""),
    ("queue: replay counted as backlog", CORE + "FrameBuffers.kt",
     "if (frames.size >= maxFrames + replayAllowance) {", "if (frames.size >= maxFrames) {"),
    ("queue: allowance never used up", CORE + "FrameBuffers.kt",
     "if (replayAllowance > 0) replayAllowance--", ""),
    ("volume: no floor at -30 dB", CORE + "Media.kt",
     "val clamped = db.coerceAtLeast(AIRPLAY_MIN_DB)", "val clamped = db"),
    ("volume: mute not honoured", CORE + "Media.kt",
     "if (db.isNaN() || db <= AIRPLAY_MUTE_DB) return 0", "if (db.isNaN()) return 0"),
    ("decoder: software decoder preferred", CORE + "Media.kt",
     "candidates.firstOrNull { !it.softwareOnly && !looksLikeSoftwareDecoder(it.name) }",
     "candidates.firstOrNull()"),
    ("ladder: API 30 key on Android 9", CORE + "Media.kt",
     "if (sdkInt >= 30) full[\"low-latency\"] = 1", "full[\"low-latency\"] = 1"),
    ("ip: link-local address used", CORE + "Media.kt",
     "if (parts[0] == \"169\" && parts[1] == \"254\") return false", ""),
    ("ip: other interfaces used", CORE + "Media.kt",
     "if (!name.startsWith(prefix)) continue", ""),
    ("wake lock: held for idle connections", CORE + "ReceiverState.kt",
     "connections > 0 && (mirroring || audioSession)", "connections > 0"),
    ("session end: connection count kept", CORE + "ReceiverState.kt",
     "copy(connections = 0, mirroring", "copy(mirroring"),
    ("config: ALAC announced", CORE + "ReceiverConfig.kt",
     "const val ADVERTISE_ALAC = false", "const val ADVERTISE_ALAC = true"),
    ("contract: a SharedPreferences key appears", MAIN + "ReceiverService.kt",
     "    private var wakeLock: PowerManager.WakeLock? = null",
     "    private var wakeLock: PowerManager.WakeLock? = null\n    private val prefs by lazy { getSharedPreferences(\"settings\", 0) }"),
    ("contract: notification channel renamed", MAIN + "ReceiverService.kt",
     "CHANNEL_ID = \"receiver\"", "CHANNEL_ID = \"receiver_v2\""),
    ("contract: boot receiver removed from the manifest", "receiver/src/main/AndroidManifest.xml",
     "<action android:name=\"android.intent.action.BOOT_COMPLETED\" />", ""),
    ("guard: packaging no longer waits for the tests", "receiver/build.gradle.kts",
     "tasks.matching { it.name == \"package${variant}\" }.configureEach { dependsOn(\"test${variant}UnitTest\") }",
     "tasks.matching { it.name == \"package${variant}\" }.configureEach { }"),
    ("guard: contract test made skippable", "receiver/src/test/java/de/merzkevin/airrelay/core/CompatContractTest.kt",
     "    @Test\n    fun contractIsUnchanged() {", "    @org.junit.Ignore @Test\n    fun contractIsUnchanged() {"),
    ("guard: screen table lost rows", "receiver/src/test/resources/de/merzkevin/airrelay/core/screen-table.txt",
     "UNLOCKED  RUNNING       1 1 1  MIRRORING\n", ""),
]


def run_tests() -> int:
    r = subprocess.run(["./gradlew", ":receiver:testDebugUnitTest", "--offline", "-q", "--console=plain"],
                       cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return r.returncode


def main():
    wanted = {int(a) for a in sys.argv[1:]}
    red = green = 0
    for n, (desc, rel, old, new) in enumerate(MUTATIONS, 1):
        if wanted and n not in wanted:
            continue
        path = ROOT / rel
        original = path.read_text()
        if original.count(old) != 1:
            print(f"{n:2} SETUP ERROR, text not found exactly once: {desc}")
            green += 1
            continue
        try:
            path.write_text(original.replace(old, new))
            code = run_tests()
        finally:
            path.write_text(original)
        if code != 0:
            red += 1
            print(f"{n:2} red   (good)  {desc}")
        else:
            green += 1
            print(f"{n:2} GREEN (BAD)   {desc}")
    print(f"\n{red} of {red + green} mutations caught")
    # the contract test rewrites a missing file; make sure the frozen one is untouched
    subprocess.run(["git", "diff", "--exit-code", "--", "receiver/contract"], cwd=ROOT)
    sys.exit(0 if green == 0 else 1)


if __name__ == "__main__":
    main()
