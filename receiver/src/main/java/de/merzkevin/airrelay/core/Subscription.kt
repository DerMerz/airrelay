package de.merzkevin.airrelay.core

/**
 * Store identifiers of the one subscription. These strings are a contract with the Amazon
 * developer console AND with every existing subscriber: a renamed SKU means nobody's
 * subscription is recognised any more. They are pinned in contract/compat-contract.txt.
 */
object Skus {
    const val PARENT = "de.merzkevin.airrelay.pro"
    const val MONTHLY = "de.merzkevin.airrelay.pro.monthly"
    const val YEARLY = "de.merzkevin.airrelay.pro.yearly"

    /** Terms that are offered, yearly first (it is preselected). */
    val TERMS = listOf(YEARLY, MONTHLY)

    /** Every SKU a receipt of this subscription can carry (Amazon reports the term SKU,
     *  older receipts may carry the parent). */
    val ALL = setOf(PARENT, MONTHLY, YEARLY)
}

/** What Amazon (or the cache) says about the subscription. */
enum class SubscriptionStatus {
    /** A paid period is running. */
    ACTIVE,
    /** The free trial is running. Amazon receipts carry no trial flag, so the Amazon
     *  adapter reports a running trial as [ACTIVE]; both unlock the same way. */
    TRIAL,
    /** There is no running subscription (never bought, cancelled and run out, refunded). */
    EXPIRED,
    /** Nothing is known: no answer yet, nothing stored, or the stored copy was unreadable. */
    UNKNOWN,
}

/**
 * What the receiver may do and what the TV shows about the subscription.
 * Only [UNLOCKED] admits a sender. The *_AFTER_ATTEMPT variants are shown after an iPhone
 * has just been turned away, so the TV says why.
 */
enum class Access(val admitsSender: Boolean) {
    /** Mirroring works, nothing about the subscription is shown. */
    UNLOCKED(true),
    /** No subscription: offer box with both terms and "restore". */
    OFFER(false),
    OFFER_AFTER_ATTEMPT(false),
    /** Asking Amazon right now. */
    CHECKING(false),
    CHECKING_AFTER_ATTEMPT(false),
    /** Nothing usable is known and Amazon cannot be reached. */
    UNREACHABLE(false),
    UNREACHABLE_AFTER_ATTEMPT(false),
    /** A subscription was confirmed, but longer ago than [SubscriptionRules.OFFLINE_GRACE_MS]
     *  and Amazon cannot be reached to confirm it again. */
    CONFIRMATION_OVERDUE(false),
    CONFIRMATION_OVERDUE_AFTER_ATTEMPT(false),
}

object SubscriptionRules {
    /** A subscription confirmed by Amazon keeps working offline for this long. */
    const val OFFLINE_GRACE_MS = 3L * 24 * 60 * 60 * 1000

    /** A clock that runs a little behind the stored confirmation (NTP correction) is not
     *  a reason to lock; a clock far behind is (see [withinGrace]). */
    const val CLOCK_SKEW_TOLERANCE_MS = 10L * 60 * 1000

    /** Without any answer from Amazon after this long, Amazon counts as unreachable. */
    const val AMAZON_TIMEOUT_MS = 15_000L
}

/**
 * Whether a confirmation made at [confirmedAtMs] still counts at [nowMs] (both wall clock).
 * A confirmation from the future beyond a small tolerance (clock was set back, or the
 * stored value is nonsense) does not count; the next online check fixes it.
 */
fun withinGrace(confirmedAtMs: Long, nowMs: Long): Boolean {
    val age = nowMs - confirmedAtMs
    if (age < -SubscriptionRules.CLOCK_SKEW_TOLERANCE_MS) return false
    return age <= SubscriptionRules.OFFLINE_GRACE_MS
}

/**
 * The one decision about the subscription. Pure: no Android, no clock, no storage.
 *
 * @param status what Amazon said, or what the cache holds
 * @param online the device has internet and the last Amazon request did not fail
 * @param fromCache [status] comes from storage, not from an answer in this process
 * @param withinGrace the stored confirmation is at most three days old ([withinGrace]);
 *        only matters for a cached subscription
 * @param attempt an iPhone has just tried to connect and was turned away
 */
fun accessFor(
    status: SubscriptionStatus,
    online: Boolean,
    fromCache: Boolean,
    withinGrace: Boolean,
    attempt: Boolean,
): Access {
    fun locked(plain: Access, afterAttempt: Access) = if (attempt) afterAttempt else plain
    return when (status) {
        SubscriptionStatus.ACTIVE, SubscriptionStatus.TRIAL -> when {
            !fromCache || withinGrace -> Access.UNLOCKED
            online -> locked(Access.CHECKING, Access.CHECKING_AFTER_ATTEMPT)
            else -> locked(Access.CONFIRMATION_OVERDUE, Access.CONFIRMATION_OVERDUE_AFTER_ATTEMPT)
        }
        SubscriptionStatus.EXPIRED -> locked(Access.OFFER, Access.OFFER_AFTER_ATTEMPT)
        SubscriptionStatus.UNKNOWN ->
            if (online) locked(Access.CHECKING, Access.CHECKING_AFTER_ATTEMPT)
            else locked(Access.UNREACHABLE, Access.UNREACHABLE_AFTER_ATTEMPT)
    }
}

/** Access shown as the coarse gate the screen decision uses. */
fun Access.entitlement(): Entitlement = if (admitsSender) Entitlement.UNLOCKED else Entitlement.LOCKED

/** Offer box with the two terms and "restore". */
fun Access.showsOffer(): Boolean = this == Access.OFFER || this == Access.OFFER_AFTER_ATTEMPT

fun Access.afterAttempt(): Boolean = this == Access.OFFER_AFTER_ATTEMPT ||
    this == Access.CHECKING_AFTER_ATTEMPT || this == Access.UNREACHABLE_AFTER_ATTEMPT ||
    this == Access.CONFIRMATION_OVERDUE_AFTER_ATTEMPT

// ---- receipts ----

/** The parts of an Amazon receipt this app looks at, free of the SDK types. */
data class ReceiptInfo(
    val receiptId: String,
    val sku: String,
    val isSubscription: Boolean,
    val canceled: Boolean,
    /** Wall clock ms, null if Amazon sent none. */
    val cancelDateMs: Long?,
)

/**
 * Merges receipts from several pages or answers: one entry per receipt id. When the same
 * receipt arrives twice, a cancelled copy wins, because Amazon only ever cancels a
 * receipt, it never revives one.
 */
fun dedupeReceipts(receipts: List<ReceiptInfo>): List<ReceiptInfo> {
    val byId = LinkedHashMap<String, ReceiptInfo>()
    for (r in receipts) {
        val known = byId[r.receiptId]
        val knownCancels = known != null && (known.canceled || known.cancelDateMs != null)
        byId[r.receiptId] = if (knownCancels) known!! else r
    }
    return byId.values.toList()
}

/** Whether a receipt counts as cancelled at [nowMs]. A cancel date in the future
 *  (end of the paid period) still counts as running. */
fun isCancelledAt(r: ReceiptInfo, nowMs: Long): Boolean {
    val date = r.cancelDateMs
    if (date != null) return date <= nowMs
    return r.canceled
}

/** Receipts of this subscription that are running at [nowMs]. */
fun activeReceipts(receipts: List<ReceiptInfo>, nowMs: Long): List<ReceiptInfo> =
    dedupeReceipts(receipts).filter { it.isSubscription && it.sku in Skus.ALL && !isCancelledAt(it, nowMs) }

/** Status from the complete receipt list of one getPurchaseUpdates(reset = true) run. */
fun statusFromReceipts(receipts: List<ReceiptInfo>, nowMs: Long): SubscriptionStatus =
    if (activeReceipts(receipts, nowMs).isEmpty()) SubscriptionStatus.EXPIRED else SubscriptionStatus.ACTIVE

// ---- purchase answers ----

/** Amazon's answer to purchase(), free of the SDK types. */
enum class PurchaseResult { SUCCESSFUL, FAILED, INVALID_SKU, ALREADY_PURCHASED, NOT_SUPPORTED, PENDING, UNKNOWN }

/** What the offer box says after a purchase or restore. */
enum class OfferMessage {
    NONE,
    /** Cancelled by the user or failed; Amazon does not say which. Nothing was charged. */
    PURCHASE_CANCELLED,
    /** The SKU is not known to Amazon (not published yet, wrong marketplace). */
    PRODUCT_NOT_FOUND,
    /** Already subscribed: a restore is started. */
    ALREADY_SUBSCRIBED,
    /** Amazon is not reachable or does not support purchases here. */
    AMAZON_UNREACHABLE,
    PURCHASE_PENDING,
    /** Restore finished and found nothing. */
    NOTHING_TO_RESTORE,
    /** A purchase or restore is running. */
    WORKING,
}

/** Message shown after purchase() answered. */
fun messageForPurchase(result: PurchaseResult): OfferMessage = when (result) {
    PurchaseResult.SUCCESSFUL -> OfferMessage.NONE
    PurchaseResult.FAILED -> OfferMessage.PURCHASE_CANCELLED
    PurchaseResult.INVALID_SKU -> OfferMessage.PRODUCT_NOT_FOUND
    PurchaseResult.ALREADY_PURCHASED -> OfferMessage.ALREADY_SUBSCRIBED
    PurchaseResult.NOT_SUPPORTED, PurchaseResult.UNKNOWN -> OfferMessage.AMAZON_UNREACHABLE
    PurchaseResult.PENDING -> OfferMessage.PURCHASE_PENDING
}
