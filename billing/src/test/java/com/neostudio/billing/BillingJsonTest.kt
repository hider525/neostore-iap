package com.neostudio.billing

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BillingJsonTest {
    @Test
    fun responseCodesUseStableValuesAndRejectUnknownValues() {
        assertEquals(ResponseCode.OK, ResponseCode.from(0, "OK"))
        assertEquals(ResponseCode.INSUFFICIENT_FUNDS, ResponseCode.from(5, "INSUFFICIENT_FUNDS"))
        assertNull(ResponseCode.from(99, "OK"))
        assertNull(ResponseCode.from(null, "NOT_A_PROTOCOL_CODE"))
    }

    @Test
    fun unknownResponseCodeBecomesErrorAndIsRetained() {
        val response = BillingJson.parseResponse(
            JSONObject(
                """{"responseCode":"FUTURE_CODE","responseCodeValue":99,"debugMessage":null,"data":null}"""
            )
        )
        assertEquals(ResponseCode.ERROR, response.code)
        assertEquals("FUTURE_CODE", response.rawCode)
        assertEquals(99, response.rawCodeValue)
        assertTrue(response.debugMessage.orEmpty().contains("FUTURE_CODE"))
    }

    @Test
    fun purchasesAcceptNullAmountsAndUnknownStatuses() {
        val snapshot = BillingJson.purchases(
            JSONObject(
                """{
                  "products":[{"transactionId":"check_abc","productIdentifier":"coins","productType":"CONSUMABLE","status":"paid","amount":null,"currency":null}],
                  "subscriptions":[{"subscriptionId":"sub_abc","status":"FUTURE_STATUS","autoRenewing":false,"appliedOffer":null}]
                }"""
            )
        )
        assertEquals("coins", snapshot.products.single().productIdentifier)
        assertEquals(PurchaseStatus.PAID, snapshot.products.single().status)
        assertNull(snapshot.products.single().amount)
        assertEquals(SubscriptionStatus.UNKNOWN, snapshot.subscriptions.single().status)
        assertEquals("FUTURE_STATUS", snapshot.subscriptions.single().rawStatus)
        assertFalse(snapshot.subscriptions.single().autoRenewing)
        assertNull(snapshot.subscriptions.single().appliedOffer)
    }

    @Test
    fun catalogIgnoresUnknownFieldsAndKeepsUnknownProductType() {
        val catalog = BillingJson.catalog(
            JSONObject(
                """{"appId":13,"futureField":true,"products":[{"identifier":"coins","name":"Coins","type":"LIMITED_EDITION","price":"1.20","currency":"RUB","future":1}],"subscriptions":[]}"""
            )
        )
        assertEquals("LIMITED_EDITION", catalog.products.single().type)
        assertEquals("1.20", catalog.products.single().price)
        assertEquals(emptyList<Any>(), catalog.subscriptions)
    }

    @Test
    fun malformedRequiredPurchaseFieldsAreRejected() {
        assertThrows(org.json.JSONException::class.java) {
            BillingJson.productPurchase(JSONObject("""{"productIdentifier":"coins"}"""))
        }
    }
}
