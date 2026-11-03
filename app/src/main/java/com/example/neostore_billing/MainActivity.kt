package com.example.neostore_billing

import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import com.neostudio.billing.BillingActivityRequest
import com.neostudio.billing.BillingActivityResult
import com.neostudio.billing.NeoStoreBilling
import com.neostudio.billing.NeoStoreBillingConfig
import com.neostudio.billing.NeoStoreAvailability

class MainActivity : AppCompatActivity() {
    private lateinit var resultText: TextView
    private lateinit var appIdInput: EditText
    private lateinit var billingLauncher: ActivityResultLauncher<BillingActivityRequest>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        billingLauncher = registerForActivityResult(NeoStoreBilling.activityResultContract(), ::showActivityResult)
        appIdInput = EditText(this).apply {
            hint = "NeoStore appId"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        resultText = TextView(this).apply { text = "Enter appId, initialize, then try catalog or sign-in." }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(appIdInput, matchWidth())
            addView(button("Initialize", ::initializeBilling), matchWidth())
            addView(button("Load catalog", ::loadCatalog), matchWidth())
            addView(button("Restore purchases", ::restorePurchases), matchWidth())
            addView(
                button("Buy remove_ads", {
                    billingLauncher.launch(BillingActivityRequest.purchaseProduct("remove_ads"))
                }),
                matchWidth()
            )
            addView(
                button("Subscribe premium/monthly", {
                    billingLauncher.launch(
                        BillingActivityRequest.purchaseSubscription("premium", "monthly")
                    )
                }),
                matchWidth()
            )
            addView(button("Sign in", { billingLauncher.launch(BillingActivityRequest.signIn()) }), matchWidth())
            addView(resultText, matchWidth())
        }
        setContentView(content)
    }

    override fun onDestroy() {
        if (isFinishing) NeoStoreBilling.release()
        super.onDestroy()
    }

    private fun initializeBilling() {
        if (NeoStoreBilling.checkAvailability(this) != NeoStoreAvailability.AVAILABLE) {
            resultText.text = "NeoStore is not installed or enabled."
            return
        }
        val appId = appIdInput.text.toString().toLongOrNull()
        if (appId == null || appId <= 0L) {
            resultText.text = "Enter a positive appId from the NeoStore developer console."
            return
        }
        NeoStoreBilling.initializeAsync(this, NeoStoreBillingConfig(appId = appId)) { result ->
            resultText.text = result.data?.let {
                "Initialized: NeoStore ${it.neoStoreVersion ?: "unknown"}, capabilities: ${it.capabilities}"
            } ?: "${result.responseCode}: ${result.debugMessage.orEmpty()}"
        }
    }

    private fun loadCatalog() {
        NeoStoreBilling.getCatalogAsync { result ->
            resultText.text = result.data?.let {
                "Catalog loaded: ${it.products.size} products, ${it.subscriptions.size} subscriptions"
            } ?: "${result.responseCode}: ${result.debugMessage.orEmpty()}"
        }
    }

    private fun restorePurchases() {
        NeoStoreBilling.queryPurchasesAsync { result ->
            resultText.text = result.data?.let {
                "Restored: ${it.products.size} products, ${it.subscriptions.size} subscriptions"
            } ?: "${result.responseCode}: ${result.debugMessage.orEmpty()}"
        }
    }

    private fun showActivityResult(result: BillingActivityResult) {
        resultText.text = buildString {
            append(result.responseCode)
            result.data?.productPurchase?.let { append(": product ${it.productIdentifier}") }
            result.data?.subscriptionPurchase?.let { append(": subscription ${it.subscriptionId}") }
            result.debugMessage?.let { append(": $it") }
        }
    }

    private fun button(label: String, onClick: () -> Unit) =
        Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )
}
