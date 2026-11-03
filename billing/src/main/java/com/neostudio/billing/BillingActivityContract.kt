package com.neostudio.billing

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

enum class BillingActivityAction {
    PURCHASE_PRODUCT,
    PURCHASE_SUBSCRIPTION,
    SIGN_IN
}

class BillingActivityRequest private constructor(
    val action: BillingActivityAction,
    val productIdentifier: String? = null,
    val subscriptionIdentifier: String? = null,
    val planIdentifier: String? = null
) {
    companion object {
        @JvmStatic
        fun purchaseProduct(productIdentifier: String) =
            BillingActivityRequest(BillingActivityAction.PURCHASE_PRODUCT, productIdentifier = productIdentifier)

        @JvmStatic
        fun purchaseSubscription(subscriptionIdentifier: String, planIdentifier: String) =
            BillingActivityRequest(
                BillingActivityAction.PURCHASE_SUBSCRIPTION,
                subscriptionIdentifier = subscriptionIdentifier,
                planIdentifier = planIdentifier
            )

        @JvmStatic
        fun signIn() = BillingActivityRequest(BillingActivityAction.SIGN_IN)
    }
}

data class BillingActivityData(
    val productPurchase: ProductPurchase? = null,
    val subscriptionPurchase: SubscriptionPurchase? = null
)

typealias BillingActivityResult = BillingResult<BillingActivityData>

class BillingLaunchException(
    val responseCode: ResponseCode,
    message: String
) : IllegalStateException(message)

class BillingActivityContract : ActivityResultContract<BillingActivityRequest, BillingActivityResult>() {
    internal companion object {
        private const val NEOSTORE_PACKAGE = "com.neostudio.neostore"
        private const val ACTION_PURCHASE_PRODUCT =
            "com.neostudio.neostore.billing.action.PURCHASE_PRODUCT"
        private const val ACTION_PURCHASE_SUBSCRIPTION =
            "com.neostudio.neostore.billing.action.PURCHASE_SUBSCRIPTION"
        private const val ACTION_SIGN_IN = "com.neostudio.neostore.billing.action.SIGN_IN"
        private const val EXTRA_PROTOCOL_VERSION = "protocolVersion"
        private const val EXTRA_PRODUCT_IDENTIFIER = "productIdentifier"
        private const val EXTRA_SUBSCRIPTION_IDENTIFIER = "subscriptionIdentifier"
        private const val EXTRA_PLAN_IDENTIFIER = "planIdentifier"
        private const val EXTRA_BILLING_RESULT = "billingResult"
        private const val PROTOCOL_VERSION = 1
        private val interactiveOperationActive = AtomicBoolean(false)

        fun resetActiveOperation() {
            interactiveOperationActive.set(false)
        }
    }

    override fun createIntent(context: Context, input: BillingActivityRequest): Intent {
        validate(input)
        NeoStoreBilling.validateActivityRequest(input)
        if (NeoStoreBilling.checkAvailability(context) != NeoStoreAvailability.AVAILABLE) {
            throw BillingLaunchException(
                ResponseCode.SERVICE_UNAVAILABLE,
                "NeoStore is not installed or enabled"
            )
        }
        if (!interactiveOperationActive.compareAndSet(false, true)) {
            throw BillingLaunchException(
                ResponseCode.DEVELOPER_ERROR,
                "A NeoStore billing Activity is already active"
            )
        }

        val action = when (input.action) {
            BillingActivityAction.PURCHASE_PRODUCT -> ACTION_PURCHASE_PRODUCT
            BillingActivityAction.PURCHASE_SUBSCRIPTION -> ACTION_PURCHASE_SUBSCRIPTION
            BillingActivityAction.SIGN_IN -> ACTION_SIGN_IN
        }
        return Intent(action)
            .setPackage(NEOSTORE_PACKAGE)
            .putExtra(EXTRA_PROTOCOL_VERSION, PROTOCOL_VERSION)
            .apply {
                input.productIdentifier?.let { putExtra(EXTRA_PRODUCT_IDENTIFIER, it) }
                input.subscriptionIdentifier?.let { putExtra(EXTRA_SUBSCRIPTION_IDENTIFIER, it) }
                input.planIdentifier?.let { putExtra(EXTRA_PLAN_IDENTIFIER, it) }
            }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): BillingActivityResult {
        interactiveOperationActive.set(false)
        val payload = intent?.getStringExtra(EXTRA_BILLING_RESULT)
        if (payload.isNullOrBlank()) {
            return if (resultCode == Activity.RESULT_CANCELED) {
                BillingResult(ResponseCode.USER_CANCELED, "Billing Activity was canceled without a result")
            } else {
                BillingResult(ResponseCode.ERROR, "Billing Activity returned no billingResult payload")
            }
        }

        val response = try {
            BillingJson.parseResponse(JSONObject(payload))
        } catch (_: JSONException) {
            return BillingResult(ResponseCode.ERROR, "Billing Activity returned malformed JSON")
        }

        if (resultCode == Activity.RESULT_OK && response.code != ResponseCode.OK) {
            return BillingResult(
                responseCode = ResponseCode.ERROR,
                debugMessage = "RESULT_OK did not contain responseCode=OK",
                rawResponseCode = response.rawCode,
                rawResponseCodeValue = response.rawCodeValue
            )
        }
        if (resultCode == Activity.RESULT_CANCELED && response.code == ResponseCode.OK) {
            return BillingResult(
                responseCode = ResponseCode.ERROR,
                debugMessage = "RESULT_CANCELED contained responseCode=OK",
                rawResponseCode = response.rawCode,
                rawResponseCodeValue = response.rawCodeValue
            )
        }

        val parsedData = try {
            when {
                response.code != ResponseCode.OK && response.code != ResponseCode.ALREADY_OWNED -> null
                response.data == null -> null
                response.data.has("kind") &&
                    response.data.optString("kind").equals("PRODUCT", ignoreCase = true) ->
                    BillingActivityData(productPurchase = BillingJson.productPurchase(response.data))
                response.data.has("kind") &&
                    response.data.optString("kind").equals("SUBSCRIPTION", ignoreCase = true) ->
                    BillingActivityData(subscriptionPurchase = BillingJson.subscriptionPurchase(response.data))
                else -> BillingActivityData()
            }
        } catch (_: JSONException) {
            return BillingResult(
                responseCode = ResponseCode.ERROR,
                debugMessage = "Billing Activity returned an invalid purchase payload",
                rawResponseCode = response.rawCode,
                rawResponseCodeValue = response.rawCodeValue
            )
        }

        return BillingResult(
            responseCode = response.code,
            debugMessage = response.debugMessage,
            data = parsedData,
            rawResponseCode = response.rawCode,
            rawResponseCodeValue = response.rawCodeValue
        )
    }

    private fun validate(request: BillingActivityRequest) {
        val valid = when (request.action) {
            BillingActivityAction.PURCHASE_PRODUCT ->
                !request.productIdentifier.isNullOrBlank() &&
                    request.subscriptionIdentifier == null && request.planIdentifier == null
            BillingActivityAction.PURCHASE_SUBSCRIPTION ->
                !request.subscriptionIdentifier.isNullOrBlank() &&
                    !request.planIdentifier.isNullOrBlank() && request.productIdentifier == null
            BillingActivityAction.SIGN_IN ->
                request.productIdentifier == null &&
                    request.subscriptionIdentifier == null && request.planIdentifier == null
        }
        if (!valid) {
            throw BillingLaunchException(
                ResponseCode.DEVELOPER_ERROR,
                "Invalid fields for ${request.action}"
            )
        }
    }

}
