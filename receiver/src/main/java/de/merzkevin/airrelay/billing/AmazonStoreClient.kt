/*
 * AirRelay, Copyright (C) 2026 Kevin Merz
 * SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-AirRelay-Amazon-Appstore-SDK-exception
 * GNU GPL version 3 or later, with the additional permission in LICENSE-EXCEPTION.md.
 */
package de.merzkevin.airrelay.billing

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.amazon.device.iap.PurchasingListener
import com.amazon.device.iap.PurchasingService
import com.amazon.device.iap.model.FulfillmentResult
import com.amazon.device.iap.model.ProductDataResponse
import com.amazon.device.iap.model.ProductType
import com.amazon.device.iap.model.PurchaseResponse
import com.amazon.device.iap.model.PurchaseUpdatesResponse
import com.amazon.device.iap.model.Receipt
import com.amazon.device.iap.model.UserDataResponse
import de.merzkevin.airrelay.core.PurchaseResult
import de.merzkevin.airrelay.core.ReceiptInfo

/**
 * Amazon Appstore SDK (In-App Purchasing v3). The only file that knows the SDK types.
 *
 * Verification happens on the device only (no receipt verification server): the SDK checks
 * the Appstore's signed answers with assets/AppstoreAuthenticationKey.pem, which comes from
 * the developer console (README "Building").
 *
 * Status values are mapped by name, so a value added in a later SDK becomes UNKNOWN
 * instead of breaking the build or unlocking by accident.
 */
class AmazonStoreClient : StoreClient, PurchasingListener {

    private val main = Handler(Looper.getMainLooper())
    private var listener: StoreClient.Listener? = null

    override fun start(context: Context, listener: StoreClient.Listener) {
        this.listener = listener
        try {
            PurchasingService.registerListener(context.applicationContext, this)
            Log.i(TAG, "Appstore SDK ${PurchasingService.SDK_VERSION} registered")
        } catch (e: Exception) {
            // no Appstore on this device: every request then answers "failed" below
            Log.w(TAG, "Appstore SDK not available", e)
        }
    }

    override fun requestUserData() = call("getUserData", { listener?.onUserData(false) }) {
        PurchasingService.getUserData()
    }

    override fun requestProducts(skus: Set<String>) =
        call("getProductData", { listener?.onProducts(false, emptyMap(), emptyMap(), emptySet()) }) {
            PurchasingService.getProductData(skus)
        }

    override fun requestReceipts(reset: Boolean) =
        call("getPurchaseUpdates", { listener?.onReceipts(false, emptyList(), false) }) {
            PurchasingService.getPurchaseUpdates(reset)
        }

    override fun purchase(sku: String) = call("purchase", { listener?.onPurchase(PurchaseResult.UNKNOWN, null) }) {
        PurchasingService.purchase(sku)
    }

    /** Only ever FULFILLED: every other result cancels and refunds the subscription. */
    override fun notifyFulfillment(receiptId: String) = call("notifyFulfillment", {}) {
        PurchasingService.notifyFulfillment(receiptId, FulfillmentResult.FULFILLED)
    }

    private fun call(what: String, onError: () -> Unit, block: () -> Any?) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "$what failed", e)
            main.post(onError)
        }
    }

    // ---- PurchasingListener ----

    override fun onUserDataResponse(r: UserDataResponse) {
        val ok = r.requestStatus?.name == "SUCCESSFUL"
        Log.i(TAG, "user data: ${r.requestStatus}")
        main.post { listener?.onUserData(ok) }
    }

    override fun onProductDataResponse(r: ProductDataResponse) {
        val ok = r.requestStatus?.name == "SUCCESSFUL"
        val products = if (ok) r.productData.orEmpty() else emptyMap()
        val prices = HashMap<String, String>()
        val trials = HashMap<String, String>()
        for ((sku, p) in products) {
            p.price?.let { prices[sku] = it }
            p.freeTrialPeriod?.let { trials[sku] = it }
        }
        Log.i(TAG, "product data: ${r.requestStatus}, prices for ${prices.keys}, trials $trials, unavailable ${r.unavailableSkus}")
        val unavailable = if (ok) r.unavailableSkus.orEmpty() else emptySet()
        main.post { listener?.onProducts(ok, prices, trials, unavailable) }
    }

    override fun onPurchaseUpdatesResponse(r: PurchaseUpdatesResponse) {
        val ok = r.requestStatus?.name == "SUCCESSFUL"
        val page = if (ok) r.receipts.orEmpty().mapNotNull(::info) else emptyList()
        Log.i(TAG, "purchase updates: ${r.requestStatus}, ${page.size} receipts, more=${ok && r.hasMore()}")
        main.post { listener?.onReceipts(ok, page, ok && r.hasMore()) }
    }

    override fun onPurchaseResponse(r: PurchaseResponse) {
        val result = when (r.requestStatus?.name) {
            "SUCCESSFUL" -> PurchaseResult.SUCCESSFUL
            "FAILED" -> PurchaseResult.FAILED
            "INVALID_SKU" -> PurchaseResult.INVALID_SKU
            "ALREADY_PURCHASED" -> PurchaseResult.ALREADY_PURCHASED
            "NOT_SUPPORTED" -> PurchaseResult.NOT_SUPPORTED
            "PENDING" -> PurchaseResult.PENDING
            else -> PurchaseResult.UNKNOWN
        }
        val receipt = if (result == PurchaseResult.SUCCESSFUL) r.receipt?.let(::info) else null
        main.post { listener?.onPurchase(result, receipt) }
    }

    private fun info(r: Receipt): ReceiptInfo? {
        val id = r.receiptId ?: return null
        return ReceiptInfo(
            receiptId = id,
            // subscriptions report the parent SKU here and the term in getTermSku()
            sku = r.sku ?: r.termSku ?: return null,
            isSubscription = r.productType == ProductType.SUBSCRIPTION,
            canceled = r.isCanceled,
            cancelDateMs = r.cancelDate?.time,
        )
    }

    private companion object {
        const val TAG = "AirRelayAmazon"
    }
}
