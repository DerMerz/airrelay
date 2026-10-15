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

    # ---- subscription (Amazon In-App Purchasing) ----
    ("access: stale cached subscription unlocks", CORE + "Subscription.kt",
     "!fromCache || withinGrace -> Access.UNLOCKED", "true -> Access.UNLOCKED"),
    ("access: unknown status offline shown as checking", CORE + "Subscription.kt",
     "else locked(Access.UNREACHABLE, Access.UNREACHABLE_AFTER_ATTEMPT)", "else locked(Access.CHECKING, Access.CHECKING_AFTER_ATTEMPT)"),
    ("grace: three days become four", CORE + "Subscription.kt",
     "const val OFFLINE_GRACE_MS = 3L * 24 * 60 * 60 * 1000", "const val OFFLINE_GRACE_MS = 4L * 24 * 60 * 60 * 1000"),
    ("grace: last millisecond of day three excluded", CORE + "Subscription.kt",
     "return age <= SubscriptionRules.OFFLINE_GRACE_MS", "return age < SubscriptionRules.OFFLINE_GRACE_MS"),
    ("grace: clock set back grants grace", CORE + "Subscription.kt",
     "    if (age < -SubscriptionRules.CLOCK_SKEW_TOLERANCE_MS) return false\n", ""),
    ("receipts: cancelled copy does not win", CORE + "Subscription.kt",
     "val knownCancels = known != null && (known.canceled || known.cancelDateMs != null)", "val knownCancels = false"),
    ("receipts: reached cancel date still running", CORE + "Subscription.kt",
     "if (date != null) return date <= nowMs", "if (date != null) return date < nowMs"),
    ("receipts: cancel flag ignored", CORE + "Subscription.kt",
     "    return r.canceled\n}", "    return false\n}"),
    ("receipts: foreign SKU unlocks", CORE + "Subscription.kt",
     "it.isSubscription && it.sku in Skus.ALL && !isCancelledAt(it, nowMs)", "it.isSubscription && !isCancelledAt(it, nowMs)"),
    ("receipts: one-time purchase unlocks", CORE + "Subscription.kt",
     "it.isSubscription && it.sku in Skus.ALL && !isCancelledAt(it, nowMs)", "it.sku in Skus.ALL && !isCancelledAt(it, nowMs)"),
    ("purchase: cancel shows nothing", CORE + "Subscription.kt",
     "PurchaseResult.FAILED -> OfferMessage.PURCHASE_CANCELLED", "PurchaseResult.FAILED -> OfferMessage.NONE"),
    ("sku: yearly term renamed", CORE + "Subscription.kt",
     "const val YEARLY = \"de.merzkevin.airrelay.pro.yearly\"", "const val YEARLY = \"de.merzkevin.airrelay.pro.annual\""),
    ("cache: read error counts as no subscription", CORE + "EntitlementCache.kt",
     "(this as? CacheRead.Loaded)?.entry?.status ?: SubscriptionStatus.UNKNOWN", "(this as? CacheRead.Loaded)?.entry?.status ?: SubscriptionStatus.EXPIRED"),
    ("cache: unknown status value counts as expired", CORE + "EntitlementCache.kt",
     "it.value == values[KEY_STATUS] }?.key ?: SubscriptionStatus.UNKNOWN", "it.value == values[KEY_STATUS] }?.key ?: SubscriptionStatus.EXPIRED"),
    ("cache: stored key renamed", CORE + "EntitlementCache.kt",
     "const val KEY_CONFIRMED_AT = \"confirmedAtMs\"", "const val KEY_CONFIRMED_AT = \"confirmedAt\""),
    ("cache: corrupt file deleted", CORE + "EntitlementCache.kt",
     "f.renameTo(File(dir, f.name + \".corrupt-\" + System.currentTimeMillis()))", "f.delete()"),
    ("cache: backup never read", CORE + "EntitlementCache.kt",
     "return if (bak is CacheRead.Loaded) bak else main", "return main"),
    ("cache: backup never written", CORE + "EntitlementCache.kt",
     "            if (old is CacheRead.Loaded) file.copyTo(backup, overwrite = true)\n", ""),
    ("cache: keys of a newer build dropped", CORE + "EntitlementCache.kt",
     "val extras = (old as? CacheRead.Loaded)?.entry?.extras.orEmpty() + e.extras", "val extras = e.extras"),
    ("cache: frozen v1 fixture no longer matches", "receiver/src/test/resources/de/merzkevin/airrelay/core/fixtures/entitlement-v1-active.txt",
     "status=active", "status=expired"),
    ("offer: free trial promised without Amazon's word", CORE + "OfferText.kt",
     "    if (trialPeriod.isNullOrBlank()) return TermText(TermLabel.PRICE_ONLY)\n", ""),
    ("price: saving rounded up", CORE + "PriceText.kt",
     "saved.setScale(0, RoundingMode.FLOOR)", "saved.setScale(0, RoundingMode.HALF_UP)"),
    ("price: saving across currencies", CORE + "PriceText.kt",
     "    if (y.prefix.trim() != m.prefix.trim() || y.suffix.trim() != m.suffix.trim()) return null\n", ""),
    ("gate: starts open", CORE + "ScreenDecision.kt",
     "@Volatile var access: Access = Access.CHECKING", "@Volatile var access: Access = Access.UNLOCKED"),
    ("gate: service admits everybody", MAIN + "ReceiverService.kt",
     "val admitted = LicenseGate.admitsSender()", "val admitted = true"),
    ("gate: admission ignores the subscription", CORE + "ScreenDecision.kt",
     "fun admitsSender(): Boolean = access.admitsSender", "fun admitsSender(): Boolean = true"),
    ("gate: native admission fails open", "receiver/src/main/cpp/android_raop_callbacks.c",
     "    *admit = false;\n    JNIEnv *env = _get_env(ctx);", "    *admit = true;\n    JNIEnv *env = _get_env(ctx);"),
    ("gate: native admission not wired", "receiver/src/main/cpp/android_raop_callbacks.c",
     "    cbs->report_client_request = _report_client_request;\n", ""),
    ("gate: refused sender gets an empty 200", "receiver/src/main/cpp/patches/UxPlay/0007-refuse-unadmitted-client-with-403.patch",
     "403, \"Forbidden\"", "200, \"OK\""),
    ("amazon: fulfillment result refunds", MAIN + "billing/AmazonStoreClient.kt",
     "FulfillmentResult.FULFILLED)", "FulfillmentResult.UNAVAILABLE)"),
    ("fire os 8: alert also on Fire OS 7", CORE + "ReceiverConfig.kt",
     "fun needsFullScreenAlert(sdkInt: Int): Boolean = sdkInt >= 29", "fun needsFullScreenAlert(sdkInt: Int): Boolean = sdkInt >= 28"),
    ("contract: store file moved to another directory", MAIN + "billing/Subscriptions.kt",
     "EntitlementStore(app.filesDir)", "EntitlementStore(app.noBackupFilesDir)"),
    ("guard: access table lost rows", "receiver/src/test/resources/de/merzkevin/airrelay/core/access-table.txt",
     "UNKNOWN    1  1  1  1   CHECKING_AFTER_ATTEMPT\n", ""),
    # ---- licence split 0.1.1: crypto rewrite, playfair helper and its process call ----
    ("crypto: device key derivation changed (new AirPlay identity)", "receiver/src/main/cpp/crypto/airrelay_crypto.c",
     '"UxPlay-Persistent-Not-Secure-Public-Key"', '"AirRelay-Persistent-Public-Key"'),
    ("crypto: fresh block keeps the old keystream", "receiver/src/main/cpp/crypto/airrelay_crypto.c",
     "if (!ctx || ctx->block_used == 0) return;", "if (ctx) return;"),
    ("crypto: GCM with the default 12-byte IV", "receiver/src/main/cpp/crypto/airrelay_crypto.c",
     "ok = ok && EVP_CIPHER_CTX_ctrl(c, EVP_CTRL_GCM_SET_IVLEN, AES_128_BLOCK_SIZE, NULL) == 1;", ""),
    ("crypto: unusable X25519 peer gives a known secret", "receiver/src/main/cpp/crypto/airrelay_crypto.c",
     "if (!ok && RAND_bytes(secret, X25519_KEY_SIZE) != 1) {", "if (!ok) { memset(secret, 0, X25519_KEY_SIZE); } if (0) {"),
    ("crypto: CBC chaining restarts every call", "receiver/src/main/cpp/crypto/airrelay_crypto.c",
     "    int whole = len - len % AES_128_BLOCK_SIZE;\n    aes_run(ctx, in, out, whole);",
     "    int whole = len - len % AES_128_BLOCK_SIZE;\n    aes_start(ctx);\n    aes_run(ctx, in, out, whole);"),
    ("pipe: helper not killed on timeout", "receiver/src/main/cpp/playfair/playfair_pipe.c",
     "if (kill_first) kill(pid, SIGKILL);", "(void)kill_first;"),
    ("pipe: output length not checked", "receiver/src/main/cpp/playfair/playfair_pipe.c",
     "else if (got != PLAYFAIR_KEY_LEN) result = PLAYFAIR_ERR_OUTPUT;", ""),
    ("pipe: exit code ignored", "receiver/src/main/cpp/playfair/playfair_pipe.c",
     "else if (!WIFEXITED(status) || WEXITSTATUS(status) != 0) result = PLAYFAIR_ERR_EXIT;", ""),
    ("pipe: crash reported as exit code", "receiver/src/main/cpp/playfair/playfair_pipe.c",
     "if (WIFSIGNALED(status)) result = PLAYFAIR_ERR_CRASHED;\n    else if", "if"),
    ("pipe: answer trusted without the exit", "receiver/src/main/cpp/playfair/playfair_pipe.c",
     "        if (timed_out) return PLAYFAIR_ERR_TIMEOUT;\n        return PLAYFAIR_ERR_CRASHED;",
     "        if (got != PLAYFAIR_KEY_LEN) return PLAYFAIR_ERR_TIMEOUT;\n        memcpy(key_out, out, PLAYFAIR_KEY_LEN); return PLAYFAIR_OK;"),
    ("helper: invalid FairPlay mode accepted", "receiver/src/main/cpp/playfair/playfair_helper.c",
     "    if (in[12] > 3) return 4;\n", ""),
    ("build: playfair linked into the library again", "receiver/src/main/cpp/CMakeLists.txt",
     "target_link_libraries(airrelay_native\n    llhttp\n", "target_link_libraries(airrelay_native\n    llhttp\n    playfair\n"),
    ("build: UxPlay crypto.h back on the include path", "receiver/src/main/cpp/CMakeLists.txt",
     "    ${UXPLAY_STAGE}\n    ${UXPLAY_LIB}/llhttp\n", "    ${UXPLAY_STAGE}\n    ${UXPLAY_LIB}\n    ${UXPLAY_LIB}/llhttp\n"),
    ("build: native files not extracted (helper cannot start)", "receiver/build.gradle.kts",
     "        jniLibs.useLegacyPackaging = true\n", ""),
    ("build: unit tests no longer build the native host tests", "receiver/build.gradle.kts",
     "    dependsOn(buildHostNativeTests)\n    inputs.dir(hostNativeDir)\n", "    inputs.dir(hostNativeDir)\n"),
    ("patch 0008: stream continues with a zero key", "receiver/src/main/cpp/patches/UxPlay/0008-playfair-as-separate-program.patch",
     '+            http_response_init(response, "RTSP/1.0", 500, "Internal Server Error");\n', ""),
    ("service: helper path not handed to the native side", MAIN + "ReceiverService.kt",
     "!NativeBridge.nativeSetPlayfairHelper(helper.path)", "false"),
    ("fixture: frozen crypto vectors emptied", "receiver/src/test/resources/native/crypto-vectors-0.1.0.txt",
     "sha512.abc ", "sha512.abc0 "),
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
