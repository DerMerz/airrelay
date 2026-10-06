package de.merzkevin.airrelay.billing

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import de.merzkevin.airrelay.core.Access
import de.merzkevin.airrelay.core.CacheRead
import de.merzkevin.airrelay.core.CachedEntitlement
import de.merzkevin.airrelay.core.EntitlementStore
import de.merzkevin.airrelay.core.LicenseGate
import de.merzkevin.airrelay.core.OfferMessage
import de.merzkevin.airrelay.core.PurchaseResult
import de.merzkevin.airrelay.core.ReceiptInfo
import de.merzkevin.airrelay.core.Skus
import de.merzkevin.airrelay.core.SubscriptionRules
import de.merzkevin.airrelay.core.SubscriptionStatus
import de.merzkevin.airrelay.core.accessFor
import de.merzkevin.airrelay.core.activeReceipts
import de.merzkevin.airrelay.core.cachedAtMs
import de.merzkevin.airrelay.core.cachedStatus
import de.merzkevin.airrelay.core.messageForPurchase
import de.merzkevin.airrelay.core.statusFromReceipts
import de.merzkevin.airrelay.core.withinGrace

/**
 * Owns everything about the subscription for the whole process (service and activity):
 * the stored copy, the Amazon client, the inputs of [accessFor] and the offer box state.
 * Main thread only, except [access] and [LicenseGate], which are read from UxPlay threads.
 *
 * Idle cost: nothing runs on a timer. Amazon is asked when the activity resumes, when a
 * purchase or restore is started, and when a sender is turned away while the status is
 * not confirmed.
 */
object Subscriptions : StoreClient.Listener {

    interface Listener {
        fun onSubscriptionChanged()
        /** A purchase or restore just unlocked the receiver: show a short confirmation. */
        fun onSubscriptionConfirmed(restored: Boolean) {}
    }

    /**
     * What the offer box needs. Prices are Amazon's display strings, missing until known.
     * [trials] holds a term only while Amazon offers this customer its free trial, so
     * "free" is never promised to somebody who already had it.
     */
    data class Offer(
        val prices: Map<String, String> = emptyMap(),
        val trials: Map<String, String> = emptyMap(),
        val productsLoaded: Boolean = false,
        val unavailable: Set<String> = emptySet(),
        val message: OfferMessage = OfferMessage.NONE,
    )

    private const val TAG = "AirRelaySubscription"
    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<Listener>()

    private lateinit var app: Context
    private lateinit var store: EntitlementStore
    private lateinit var client: StoreClient

    // inputs of accessFor
    private var status = SubscriptionStatus.UNKNOWN
    private var fromCache = true
    private var confirmedAtMs = 0L
    private var amazonFailed = false
    private var attempt = false

    /** Debug builds: a state set through DebugHooks is not overwritten by Amazon. */
    private var debugPinned = false

    private var receipts = ArrayList<ReceiptInfo>()
    private var updatesRunning = false
    private var restoreRequested = false
    private val fulfilled = HashSet<String>()

    var offer = Offer()
        private set

    /** Current decision; volatile so UxPlay threads can read it through [LicenseGate]. */
    @Volatile var access: Access = LicenseGate.access
        private set

    private val timeout = Runnable {
        if (updatesRunning) {
            Log.w(TAG, "no answer from Amazon after ${SubscriptionRules.AMAZON_TIMEOUT_MS} ms")
            updatesRunning = false
            amazonFailed = true
            if (restoreRequested) setMessage(OfferMessage.AMAZON_UNREACHABLE)
            restoreRequested = false
            publish()
        }
    }

    /** Idempotent; the service and the activity both call it. */
    fun init(context: Context, storeClient: StoreClient = AmazonStoreClient()) {
        if (::app.isInitialized) return
        app = context.applicationContext
        store = EntitlementStore(app.filesDir)
        val read = store.read()
        if (read !is CacheRead.Loaded && read != CacheRead.Missing) Log.w(TAG, "stored status not usable: $read")
        status = read.cachedStatus()
        confirmedAtMs = read.cachedAtMs()
        fromCache = true
        client = storeClient
        client.start(app, this)
        publish()
    }

    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }

    // ---- actions ----

    /** Activity resumed: user data, prices and the complete receipt list. */
    fun onResume() {
        client.requestUserData()
        // parent and terms, as Amazon asks; only the terms carry a price
        client.requestProducts(Skus.ALL)
        refreshReceipts()
    }

    /** "Try again" after Amazon could not be reached: shows "checking" at once, so the
     *  press has a visible effect, then asks Amazon like a resume (15 s limit again). */
    fun retry() {
        amazonFailed = false
        publish()
        onResume()
    }

    /** "Restore purchase": the complete receipt list again, with a visible result. */
    fun restore() {
        restoreRequested = true
        setMessage(OfferMessage.WORKING)
        refreshReceipts()
    }

    fun purchase(termSku: String) {
        require(termSku in Skus.TERMS) { "unknown term $termSku" }
        // no "one moment" of our own: Amazon's purchase dialog is the feedback, and Amazon's
        // IAP guidelines ask apps not to show their own transaction status (cancel excepted)
        setMessage(OfferMessage.NONE)
        publish()
        client.purchase(termSku)
    }

    /** A sender was turned away (UxPlay thread). Shows the reason on the TV and, if the
     *  status is not confirmed, asks Amazon so a subscriber's next try goes through. */
    fun onSenderRefused() {
        main.post {
            attempt = true
            if (status == SubscriptionStatus.UNKNOWN || fromCache) refreshReceipts()
            publish()
        }
    }

    /** The TV no longer shows the app: the "an iPhone was turned away" line is old news. */
    fun onAppHidden() {
        if (!attempt && offer.message == OfferMessage.NONE) return
        attempt = false
        offer = offer.copy(message = OfferMessage.NONE)
        publish()
    }

    // ---- StoreClient.Listener (main thread) ----

    override fun onUserData(ok: Boolean) {
        if (debugPinned) return
        if (!ok) {
            amazonFailed = true
            publish()
        }
    }

    override fun onProducts(ok: Boolean, prices: Map<String, String>, trials: Map<String, String>, unavailable: Set<String>) {
        if (debugPinned) return
        if (ok) {
            // replaced as a whole: a trial that is no longer offered must disappear
            offer = offer.copy(prices = prices, trials = trials, productsLoaded = true, unavailable = unavailable)
            if (unavailable.isNotEmpty()) Log.w(TAG, "Amazon does not know: $unavailable")
        } else {
            Log.w(TAG, "product data failed")
        }
        publish()
    }

    override fun onReceipts(ok: Boolean, page: List<ReceiptInfo>, hasMore: Boolean) {
        if (debugPinned) {
            updatesRunning = false
            main.removeCallbacks(timeout)
            if (restoreRequested && status != SubscriptionStatus.ACTIVE) setMessage(OfferMessage.NOTHING_TO_RESTORE)
            restoreRequested = false
            publish()
            return
        }
        if (!ok) {
            updatesRunning = false
            main.removeCallbacks(timeout)
            amazonFailed = true
            if (restoreRequested) setMessage(OfferMessage.AMAZON_UNREACHABLE)
            restoreRequested = false
            publish()
            return
        }
        receipts.addAll(page)
        if (hasMore) {
            client.requestReceipts(reset = false)
            return
        }
        updatesRunning = false
        main.removeCallbacks(timeout)
        val now = System.currentTimeMillis()
        val result = statusFromReceipts(receipts, now)
        for (r in activeReceipts(receipts, now)) fulfill(r.receiptId)
        receipts = ArrayList()
        confirm(result, now)
        if (restoreRequested) {
            setMessage(if (result == SubscriptionStatus.ACTIVE) OfferMessage.NONE else OfferMessage.NOTHING_TO_RESTORE)
            if (result == SubscriptionStatus.ACTIVE) confirmation(restored = true)
        }
        restoreRequested = false
        publish()
    }

    override fun onPurchase(result: PurchaseResult, receipt: ReceiptInfo?) {
        Log.i(TAG, "purchase answer: $result")
        setMessage(messageForPurchase(result))
        when (result) {
            PurchaseResult.SUCCESSFUL -> {
                if (receipt != null && receipt.isSubscription && receipt.sku in Skus.ALL) {
                    fulfill(receipt.receiptId)
                    confirm(SubscriptionStatus.ACTIVE, System.currentTimeMillis())
                    confirmation(restored = false)
                } else {
                    // no usable receipt in the answer: take the full list instead of guessing
                    refreshReceipts()
                }
            }
            PurchaseResult.ALREADY_PURCHASED -> {
                restoreRequested = true
                refreshReceipts()
            }
            PurchaseResult.NOT_SUPPORTED, PurchaseResult.UNKNOWN -> amazonFailed = true
            else -> Unit
        }
        publish()
    }

    // ---- internals ----

    private fun refreshReceipts() {
        if (updatesRunning) return
        updatesRunning = true
        receipts = ArrayList()
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, SubscriptionRules.AMAZON_TIMEOUT_MS)
        // complete list every time: the status is derived from all receipts, none are stored
        client.requestReceipts(reset = true)
        publish()
    }

    private fun fulfill(receiptId: String) {
        if (fulfilled.add(receiptId)) client.notifyFulfillment(receiptId)
    }

    /** A fresh answer from Amazon: used at once and stored for the next start. */
    private fun confirm(result: SubscriptionStatus, now: Long) {
        status = result
        fromCache = false
        confirmedAtMs = now
        amazonFailed = false
        if (result == SubscriptionStatus.ACTIVE) attempt = false
        if (!store.write(CachedEntitlement(result, now))) Log.w(TAG, "status could not be stored")
    }

    private fun confirmation(restored: Boolean) {
        for (l in listeners.toList()) l.onSubscriptionConfirmed(restored)
    }

    private fun setMessage(m: OfferMessage) {
        offer = offer.copy(message = m)
    }

    private fun hasInternet(): Boolean = try {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (_: Exception) {
        false
    }

    /** Recomputes [access] from the inputs and tells everyone. */
    private fun publish() {
        val now = System.currentTimeMillis()
        val online = hasInternet() && !amazonFailed
        val a = accessFor(status, online, fromCache, withinGrace(confirmedAtMs, now), attempt)
        access = a
        LicenseGate.access = a
        for (l in listeners.toList()) l.onSubscriptionChanged()
    }

    /** Debug builds only (DebugHooks): pretend Amazon answered. R8 drops it in release. */
    fun debugSet(result: SubscriptionStatus?, cachedAgeMs: Long?, offline: Boolean,
                 prices: Map<String, String>?, trials: Map<String, String>?) {
        if (result != null) {
            status = result
            fromCache = cachedAgeMs != null
            confirmedAtMs = System.currentTimeMillis() - (cachedAgeMs ?: 0L)
        }
        amazonFailed = offline
        attempt = false
        offer = if (prices != null) offer.copy(prices = prices, trials = trials.orEmpty(), productsLoaded = true)
        else Offer()
        debugPinned = true
        publish()
    }

    /** Debug builds only: show one of the offer messages. */
    fun debugMessage(m: OfferMessage) {
        setMessage(m)
        publish()
    }
}
