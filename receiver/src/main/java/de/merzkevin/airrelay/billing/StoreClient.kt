package de.merzkevin.airrelay.billing

import android.content.Context
import de.merzkevin.airrelay.core.PurchaseResult
import de.merzkevin.airrelay.core.ReceiptInfo

/**
 * The store as [Subscriptions] sees it. The Amazon implementation is the only code that
 * touches the Appstore SDK types. Every answer arrives on the main thread.
 */
interface StoreClient {
    interface Listener {
        fun onUserData(ok: Boolean)
        /**
         * Display prices by SKU, free trial periods by SKU (Amazon sends one only while
         * this customer can still get the trial), [unavailable] are SKUs the store does
         * not know.
         */
        fun onProducts(ok: Boolean, prices: Map<String, String>, trials: Map<String, String>, unavailable: Set<String>)
        /** One page of receipts; more pages follow while [hasMore]. */
        fun onReceipts(ok: Boolean, page: List<ReceiptInfo>, hasMore: Boolean)
        fun onPurchase(result: PurchaseResult, receipt: ReceiptInfo?)
    }

    fun start(context: Context, listener: Listener)
    fun requestUserData()
    fun requestProducts(skus: Set<String>)
    fun requestReceipts(reset: Boolean)
    fun purchase(sku: String)
    fun notifyFulfillment(receiptId: String)
}
