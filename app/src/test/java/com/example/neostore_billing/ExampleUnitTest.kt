package com.example.neostore_billing

import com.neostudio.billing.BillingActivityAction
import com.neostudio.billing.BillingActivityRequest
import org.junit.Assert.assertEquals
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun productRequestKeepsOnlyThePublicIdentifier() {
        val request = BillingActivityRequest.purchaseProduct("remove_ads")
        assertEquals(BillingActivityAction.PURCHASE_PRODUCT, request.action)
        assertEquals("remove_ads", request.productIdentifier)
        assertEquals(null, request.subscriptionIdentifier)
    }
}
