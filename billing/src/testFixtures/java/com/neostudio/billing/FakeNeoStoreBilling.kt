package com.neostudio.billing

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

class FakeNeoStoreBilling : NeoStoreBillingClient {
    var availability = NeoStoreAvailability.AVAILABLE
    var initializeResult = BillingResult(
        ResponseCode.OK,
        data = InitInfo(
            protocolVersion = 1,
            capabilities = setOf(
                "getCatalog",
                "queryPurchases",
                "getSubscription",
                "acknowledgePurchase",
                "purchaseProduct",
                "purchaseSubscription",
                "signIn"
            ),
            neoStoreVersion = "fake",
            userSignedIn = false
        )
    )
    var catalogResult = BillingResult(ResponseCode.OK, data = Catalog(null, emptyList(), emptyList()))
    var purchasesResult = BillingResult(
        ResponseCode.OK,
        data = PurchasesSnapshot(emptyList(), emptyList())
    )
    var acknowledgeResult = BillingResult(ResponseCode.OK, data = Unit)
    var released = false
        private set
    val subscriptions = ConcurrentHashMap<String, BillingResult<SubscriptionPurchase>>()
    val acknowledgedTransactionIds = mutableListOf<String>()

    private var purchasesListener: PurchasesListener? = null

    override fun checkAvailability(context: Context) = availability

    override suspend fun initialize(
        context: Context,
        config: NeoStoreBillingConfig
    ): BillingResult<InitInfo> {
        released = false
        return initializeResult
    }

    override fun setPurchasesListener(listener: PurchasesListener?) {
        purchasesListener = listener
    }

    override suspend fun getCatalog() = catalogResult

    override suspend fun queryPurchases(): BillingResult<PurchasesSnapshot> {
        purchasesResult.data?.let { purchasesListener?.onPurchasesUpdated(it) }
        return purchasesResult
    }

    override suspend fun getSubscription(subscriptionId: String) =
        subscriptions[subscriptionId] ?: BillingResult(ResponseCode.ITEM_UNAVAILABLE)

    override suspend fun acknowledgePurchase(transactionId: String): BillingResult<Unit> {
        acknowledgedTransactionIds += transactionId
        return acknowledgeResult
    }

    override fun release() {
        released = true
        purchasesListener = null
    }
}
