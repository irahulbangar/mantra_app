package com.mantra.player.billing

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.mantra.player.BuildConfig
import com.mantra.player.PremiumPreferences

/**
 * Google Play Billing for the one-time Remove Ads product.
 */
object BillingManager : PurchasesUpdatedListener {
    private const val TAG = "BillingManager"

    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var client: BillingClient? = null
    private var started = false
    private var connecting = false
    private var productDetails: ProductDetails? = null

    private val readyCallbacks = mutableListOf<() -> Unit>()
    private var purchaseCallback: ((PurchaseResult) -> Unit)? = null

    enum class PurchaseResult {
        SUCCESS,
        CANCELED,
        ERROR,
        ALREADY_OWNED
    }

    fun start(context: Context) {
        if (started) {
            queryExistingPurchases(silent = true)
            return
        }
        started = true
        appContext = context.applicationContext
        val billing = BillingClient.newBuilder(context.applicationContext)
            .setListener(this)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
            )
            .build()
        client = billing
        connectAndSync()
    }

    fun whenReady(callback: () -> Unit) {
        main.post {
            val billing = client
            if (billing != null && billing.isReady) {
                callback()
            } else {
                readyCallbacks += callback
                if (!connecting) connectAndSync()
            }
        }
    }

    fun formattedPrice(): String? {
        val offer = productDetails?.oneTimePurchaseOfferDetails ?: return null
        return offer.formattedPrice
    }

    fun queryProductDetails(onResult: (ProductDetails?) -> Unit) {
        whenReady {
            val billing = client ?: run {
                onResult(null)
                return@whenReady
            }
            val productId = BuildConfig.REMOVE_ADS_PRODUCT_ID
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(
                    listOf(
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(productId)
                            .setProductType(BillingClient.ProductType.INAPP)
                            .build()
                    )
                )
                .build()
            billing.queryProductDetailsAsync(params) { result, detailsList ->
                main.post {
                    if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                        Log.w(TAG, "product details failed: ${result.debugMessage}")
                        onResult(null)
                        return@post
                    }
                    productDetails = detailsList.firstOrNull()
                    onResult(productDetails)
                }
            }
        }
    }

    fun launchPurchase(activity: Activity, onResult: (PurchaseResult) -> Unit) {
        purchaseCallback = onResult
        whenReady {
            if (PremiumPreferences(activity).adsRemoved) {
                purchaseCallback = null
                onResult(PurchaseResult.ALREADY_OWNED)
                return@whenReady
            }
            val details = productDetails
            if (details == null) {
                queryProductDetails { loaded ->
                    if (loaded == null) {
                        purchaseCallback = null
                        onResult(PurchaseResult.ERROR)
                    } else {
                        startBillingFlow(activity, loaded, onResult)
                    }
                }
                return@whenReady
            }
            startBillingFlow(activity, details, onResult)
        }
    }

    fun restore(onResult: (Boolean) -> Unit) {
        whenReady {
            queryExistingPurchases(silent = false) { owned ->
                onResult(owned)
            }
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        main.post {
            when (result.responseCode) {
                BillingClient.BillingResponseCode.OK -> {
                    val list = purchases.orEmpty()
                    if (list.isEmpty()) {
                        purchaseCallback?.invoke(PurchaseResult.ERROR)
                        purchaseCallback = null
                        return@post
                    }
                    handlePurchases(list)
                    purchaseCallback?.invoke(PurchaseResult.SUCCESS)
                    purchaseCallback = null
                }
                BillingClient.BillingResponseCode.USER_CANCELED -> {
                    purchaseCallback?.invoke(PurchaseResult.CANCELED)
                    purchaseCallback = null
                }
                BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                    queryExistingPurchases(silent = true)
                    purchaseCallback?.invoke(PurchaseResult.ALREADY_OWNED)
                    purchaseCallback = null
                }
                else -> {
                    Log.w(TAG, "purchase failed: ${result.responseCode} ${result.debugMessage}")
                    purchaseCallback?.invoke(PurchaseResult.ERROR)
                    purchaseCallback = null
                }
            }
        }
    }

    private fun startBillingFlow(
        activity: Activity,
        details: ProductDetails,
        onResult: (PurchaseResult) -> Unit
    ) {
        val billing = client
        if (billing == null || !billing.isReady) {
            purchaseCallback = null
            onResult(PurchaseResult.ERROR)
            return
        }
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .build()
                )
            )
            .build()
        val launchResult = billing.launchBillingFlow(activity, params)
        if (launchResult.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.w(TAG, "launchBillingFlow failed: ${launchResult.debugMessage}")
            purchaseCallback = null
            onResult(PurchaseResult.ERROR)
        }
    }

    private fun connectAndSync() {
        val billing = client ?: return
        if (billing.isReady) {
            flushReady()
            queryProductDetails {}
            queryExistingPurchases(silent = true)
            return
        }
        if (connecting) return
        connecting = true
        billing.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                connecting = false
                main.post {
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        flushReady()
                        queryProductDetails {}
                        queryExistingPurchases(silent = true)
                    } else {
                        Log.w(TAG, "billing setup failed: ${result.debugMessage}")
                        readyCallbacks.clear()
                    }
                }
            }

            override fun onBillingServiceDisconnected() {
                connecting = false
                main.postDelayed({ connectAndSync() }, 3_000L)
            }
        })
    }

    private fun flushReady() {
        val callbacks = readyCallbacks.toList()
        readyCallbacks.clear()
        callbacks.forEach { it() }
    }

    private fun queryExistingPurchases(
        silent: Boolean,
        onDone: ((Boolean) -> Unit)? = null
    ) {
        val billing = client
        if (billing == null || !billing.isReady) {
            onDone?.invoke(PremiumPreferences(appContext ?: return).adsRemoved)
            return
        }
        billing.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        ) { result, purchases ->
            main.post {
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    Log.w(TAG, "query purchases failed: ${result.debugMessage}")
                    onDone?.invoke(PremiumPreferences(appContext ?: return@post).adsRemoved)
                    return@post
                }
                val owned = handlePurchases(purchases)
                if (!silent) {
                    // Restore with no matching purchase clears nothing; keep local if already true
                    // only when Play confirms ownership. If empty, leave local cache as-is unless
                    // we explicitly want to revoke — keep cache sticky for offline.
                }
                onDone?.invoke(owned || PremiumPreferences(appContext ?: return@post).adsRemoved)
            }
        }
    }

    private fun handlePurchases(purchases: List<Purchase>): Boolean {
        val context = appContext ?: return false
        val productId = BuildConfig.REMOVE_ADS_PRODUCT_ID
        var owned = false
        purchases.forEach { purchase ->
            if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return@forEach
            if (!purchase.products.contains(productId)) return@forEach
            owned = true
            PremiumPreferences(context).adsRemoved = true
            if (!purchase.isAcknowledged) {
                acknowledge(purchase)
            }
        }
        return owned
    }

    private fun acknowledge(purchase: Purchase) {
        val billing = client ?: return
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        billing.acknowledgePurchase(params) { result ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "acknowledge failed: ${result.debugMessage}")
            }
        }
    }
}
