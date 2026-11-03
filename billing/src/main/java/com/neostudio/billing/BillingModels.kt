package com.neostudio.billing

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

enum class NeoStoreAvailability {
    AVAILABLE,
    NOT_INSTALLED,
    DISABLED,
    UPDATE_REQUIRED
}

enum class ResponseCode(val value: Int) {
    OK(0),
    USER_CANCELED(1),
    NOT_SIGNED_IN(2),
    ITEM_UNAVAILABLE(3),
    ALREADY_OWNED(4),
    INSUFFICIENT_FUNDS(5),
    OFFER_UNAVAILABLE(6),
    NETWORK_ERROR(7),
    SERVICE_UNAVAILABLE(8),
    RATE_LIMITED(9),
    DEVELOPER_ERROR(10),
    UNSUPPORTED_PROTOCOL(11),
    ERROR(12);

    companion object {
        internal fun from(value: Int?, name: String?): ResponseCode? =
            if (value != null) entries.firstOrNull { it.value == value }
            else entries.firstOrNull { it.name == name }
    }
}

data class NeoStoreBillingConfig(
    val appId: Long,
    val locale: java.util.Locale? = null,
    val debug: Boolean = false,
    val sdkVersion: String = "1.0.0"
)

data class BillingResult<out T>(
    val responseCode: ResponseCode,
    val debugMessage: String? = null,
    val data: T? = null,
    val rawResponseCode: String? = null,
    val rawResponseCodeValue: Int? = null
) {
    val isSuccess: Boolean
        get() = responseCode == ResponseCode.OK
}

fun interface BillingCallback<T> {
    fun onResult(result: BillingResult<T>)
}

interface PurchasesListener {
    fun onPurchasesUpdated(snapshot: PurchasesSnapshot)
}

interface NeoStoreBillingClient {
    fun checkAvailability(context: android.content.Context): NeoStoreAvailability
    suspend fun initialize(context: android.content.Context, config: NeoStoreBillingConfig): BillingResult<InitInfo>
    fun setPurchasesListener(listener: PurchasesListener?)
    suspend fun getCatalog(): BillingResult<Catalog>
    suspend fun queryPurchases(): BillingResult<PurchasesSnapshot>
    suspend fun getSubscription(subscriptionId: String): BillingResult<SubscriptionPurchase>
    suspend fun acknowledgePurchase(transactionId: String): BillingResult<Unit>
    fun release()
}

data class InitInfo(
    val protocolVersion: Int,
    val capabilities: Set<String>,
    val neoStoreVersion: String?,
    val userSignedIn: Boolean
)

data class Catalog(
    val appId: Long?,
    val products: List<ProductInfo>,
    val subscriptions: List<SubscriptionInfo>
)

data class ProductInfo(
    val identifier: String,
    val name: String,
    val description: String?,
    val type: String,
    val price: String?,
    val currency: String?,
    val formattedPrice: String?
)

data class SubscriptionInfo(
    val identifier: String,
    val name: String,
    val description: String?,
    val plans: List<PlanInfo>
)

data class PlanInfo(
    val identifier: String,
    val name: String,
    val description: String?,
    val billingType: String?,
    val periodValue: Int?,
    val periodUnit: String?,
    val price: String?,
    val currency: String?,
    val formattedPrice: String?
)

enum class PurchaseStatus { PAID, UNKNOWN }
enum class SubscriptionStatus { ACTIVE, PAST_DUE, EXPIRED, REVOKED, UNKNOWN }

data class ProductPurchase(
    val transactionId: String,
    val productIdentifier: String,
    val productName: String?,
    val productType: String,
    val status: PurchaseStatus,
    val rawStatus: String?,
    val deliveryStatus: String?,
    val acknowledgedAt: String?,
    val amount: String?,
    val currency: String?,
    val purchasedAt: String?,
    val acknowledged: Boolean
)

data class AppliedOffer(
    val id: String?,
    val identifier: String?,
    val name: String?
)

data class SubscriptionPurchase(
    val transactionId: String?,
    val subscriptionId: String,
    val subscriptionIdentifier: String?,
    val subscriptionName: String?,
    val planIdentifier: String?,
    val planName: String?,
    val status: SubscriptionStatus,
    val rawStatus: String?,
    val autoRenewing: Boolean,
    val billingType: String?,
    val amount: String?,
    val currency: String?,
    val startedAt: String?,
    val expiresAt: String?,
    val nextBillingAt: String?,
    val cancelledAt: String?,
    val currentPhaseOrder: Int?,
    val appliedOffer: AppliedOffer?
)

data class PurchasesSnapshot(
    val products: List<ProductPurchase>,
    val subscriptions: List<SubscriptionPurchase>
)

internal data class ParsedResponse(
    val code: ResponseCode,
    val debugMessage: String?,
    val data: JSONObject?,
    val rawCode: String?,
    val rawCodeValue: Int?
)

internal object BillingJson {
    fun parseResponse(json: JSONObject): ParsedResponse {
        val rawName = json.nullableString("responseCode")
        val rawValue = json.nullableInt("responseCodeValue")
        val code = ResponseCode.from(rawValue, rawName) ?: ResponseCode.ERROR
        val debug = json.nullableString("debugMessage")
            ?: if (code == ResponseCode.ERROR && (rawName != "ERROR" || rawValue != 12)) {
                "Unknown NeoStore response code: ${rawName ?: rawValue ?: "missing"}"
            } else {
                null
            }
        return ParsedResponse(
            code = code,
            debugMessage = debug,
            data = json.optJSONObject("data"),
            rawCode = rawName,
            rawCodeValue = rawValue
        )
    }

    fun initInfo(data: JSONObject): InitInfo {
        val protocol = data.optInt("protocolVersion", -1)
        if (protocol < 0) throw JSONException("Missing protocolVersion")
        val capabilities = data.optJSONArray("capabilities").stringValues()
        return InitInfo(
            protocolVersion = protocol,
            capabilities = capabilities,
            neoStoreVersion = data.nullableString("neoStoreVersion"),
            userSignedIn = data.optBoolean("userSignedIn", false)
        )
    }

    fun catalog(data: JSONObject): Catalog = Catalog(
        appId = data.nullableLong("appId"),
        products = data.optJSONArray("products").objects().map {
            ProductInfo(
                identifier = it.requiredString("identifier"),
                name = it.requiredString("name"),
                description = it.nullableString("description"),
                type = it.nullableString("type") ?: "UNKNOWN",
                price = it.nullableString("price"),
                currency = it.nullableString("currency"),
                formattedPrice = it.nullableString("formattedPrice")
            )
        },
        subscriptions = data.optJSONArray("subscriptions").objects().map {
            SubscriptionInfo(
                identifier = it.requiredString("identifier"),
                name = it.requiredString("name"),
                description = it.nullableString("description"),
                plans = it.optJSONArray("plans").objects().map { plan ->
                    PlanInfo(
                        identifier = plan.requiredString("identifier"),
                        name = plan.nullableString("name") ?: plan.requiredString("identifier"),
                        description = plan.nullableString("description"),
                        billingType = plan.nullableString("billingType"),
                        periodValue = plan.nullableInt("periodValue"),
                        periodUnit = plan.nullableString("periodUnit"),
                        price = plan.nullableString("price"),
                        currency = plan.nullableString("currency"),
                        formattedPrice = plan.nullableString("formattedPrice")
                    )
                }
            )
        }
    )

    fun productPurchase(data: JSONObject): ProductPurchase {
        val rawStatus = data.nullableString("status")
        return ProductPurchase(
            transactionId = data.requiredString("transactionId"),
            productIdentifier = data.requiredString("productIdentifier"),
            productName = data.nullableString("productName"),
            productType = data.nullableString("productType") ?: "UNKNOWN",
            status = if (rawStatus.equals("paid", ignoreCase = true)) PurchaseStatus.PAID else PurchaseStatus.UNKNOWN,
            rawStatus = rawStatus,
            deliveryStatus = data.nullableString("deliveryStatus"),
            acknowledgedAt = data.nullableString("acknowledgedAt"),
            amount = data.nullableString("amount"),
            currency = data.nullableString("currency"),
            purchasedAt = data.nullableString("purchasedAt"),
            acknowledged = data.optBoolean("acknowledged", false)
        )
    }

    fun subscriptionPurchase(data: JSONObject): SubscriptionPurchase {
        val rawStatus = data.nullableString("status")
        val status = when (rawStatus?.uppercase()) {
            "ACTIVE" -> SubscriptionStatus.ACTIVE
            "PAST_DUE" -> SubscriptionStatus.PAST_DUE
            "EXPIRED" -> SubscriptionStatus.EXPIRED
            "REVOKED" -> SubscriptionStatus.REVOKED
            else -> SubscriptionStatus.UNKNOWN
        }
        val offer = data.optJSONObject("appliedOffer")
        return SubscriptionPurchase(
            transactionId = data.nullableString("transactionId"),
            subscriptionId = data.requiredString("subscriptionId"),
            subscriptionIdentifier = data.nullableString("subscriptionIdentifier"),
            subscriptionName = data.nullableString("subscriptionName"),
            planIdentifier = data.nullableString("planIdentifier"),
            planName = data.nullableString("planName"),
            status = status,
            rawStatus = rawStatus,
            autoRenewing = data.optBoolean("autoRenewing", false),
            billingType = data.nullableString("billingType"),
            amount = data.nullableString("amount"),
            currency = data.nullableString("currency"),
            startedAt = data.nullableString("startedAt"),
            expiresAt = data.nullableString("expiresAt"),
            nextBillingAt = data.nullableString("nextBillingAt"),
            cancelledAt = data.nullableString("cancelledAt"),
            currentPhaseOrder = data.nullableInt("currentPhaseOrder"),
            appliedOffer = offer?.let {
                AppliedOffer(
                    id = it.nullableString("id"),
                    identifier = it.nullableString("identifier"),
                    name = it.nullableString("name")
                )
            }
        )
    }

    fun purchases(data: JSONObject): PurchasesSnapshot = PurchasesSnapshot(
        products = data.optJSONArray("products").objects().map(::productPurchase),
        subscriptions = data.optJSONArray("subscriptions").objects().map(::subscriptionPurchase)
    )

    private fun JSONObject.requiredString(key: String): String =
        nullableString(key)?.takeIf(String::isNotBlank)
            ?: throw JSONException("Missing required field: $key")

    private fun JSONObject.nullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val value = opt(key) ?: return null
        return if (value is String) value else value.toString()
    }

    private fun JSONObject.nullableInt(key: String): Int? =
        if (!has(key) || isNull(key)) null else optInt(key).takeIf { !isNull(key) }

    private fun JSONObject.nullableLong(key: String): Long? =
        if (!has(key) || isNull(key)) null else optLong(key).takeIf { !isNull(key) }

    private fun JSONArray?.objects(): List<JSONObject> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optJSONObject(it) }
    }

    private fun JSONArray?.stringValues(): Set<String> {
        if (this == null) return emptySet()
        return (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }.toSet()
    }
}
