package com.neostudio.billing

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

object NeoStoreBilling : NeoStoreBillingClient {
    private const val TAG = "NeoStoreBilling"
    private const val NEOSTORE_PACKAGE = "com.neostudio.neostore"
    private const val ACTION_BIND = "com.neostudio.neostore.billing.BIND"
    private const val MESSAGE_WHAT = 1
    private const val REQUEST_KEY = "requestJson"
    private const val RESPONSE_KEY = "responseJson"
    private const val PROTOCOL_VERSION = 1
    private const val CONNECT_TIMEOUT_MS = 5_000L
    private const val REQUEST_TIMEOUT_MS = 20_000L

    private val lock = Any()
    private val thread = HandlerThread("NeoStoreBilling-IPC").apply { start() }
    private val handler = Handler(thread.looper)
    private val callbackHandler = Handler(Looper.getMainLooper())
    private val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectionWaiters = mutableListOf<CancellableContinuation<Messenger>>()
    private val pendingRequests = ConcurrentHashMap<String, PendingRequest>()
    private val restoreRunnable = Runnable {
        if (initInfo?.capabilities?.contains("queryPurchases") == true) {
            callbackScope.launch { queryPurchases() }
        }
    }

    @Volatile
    private var applicationContext: Context? = null
    @Volatile
    private var serviceMessenger: Messenger? = null
    @Volatile
    private var serviceConnection: ServiceConnection? = null
    @Volatile
    private var connectTimeout: Runnable? = null
    @Volatile
    private var isBound = false
    @Volatile
    private var initializedConfig: NeoStoreBillingConfig? = null
    @Volatile
    private var initInfo: InitInfo? = null
    @Volatile
    private var debugLogging = false
    @Volatile
    private var lifecycleApplication: Application? = null
    @Volatile
    private var lifecycleCallbacks: Application.ActivityLifecycleCallbacks? = null
    private var startedActivityCount = 0
    @Volatile
    private var sdkGeneration = 0L

    private val replyMessenger = Messenger(IncomingHandler(handler))

    override fun checkAvailability(context: Context): NeoStoreAvailability {
        val pm = context.packageManager
        val applicationInfo = try {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getApplicationInfo(
                    NEOSTORE_PACKAGE,
                    PackageManager.ApplicationInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(NEOSTORE_PACKAGE, 0)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            return NeoStoreAvailability.NOT_INSTALLED
        } catch (_: SecurityException) {
            return NeoStoreAvailability.NOT_INSTALLED
        }

        if (!applicationInfo.enabled) return NeoStoreAvailability.DISABLED
        return NeoStoreAvailability.AVAILABLE
    }

    override suspend fun initialize(
        context: Context,
        config: NeoStoreBillingConfig
    ): BillingResult<InitInfo> {
        if (config.appId <= 0L) {
            return BillingResult(ResponseCode.DEVELOPER_ERROR, "appId must be a positive integer")
        }
        if (!SEMVER.matches(config.sdkVersion)) {
            return BillingResult(ResponseCode.DEVELOPER_ERROR, "sdkVersion must be SemVer")
        }

        val appContext = context.applicationContext
        val generation = synchronized(lock) {
            if (initializedConfig != null && initializedConfig != config) {
                return BillingResult(
                    ResponseCode.DEVELOPER_ERROR,
                    "Call release() before initializing with a different configuration"
                )
            }
            applicationContext = appContext
            debugLogging = config.debug
            sdkGeneration
        }
        if (initializedConfig == config) {
            initInfo?.let { return BillingResult(ResponseCode.OK, data = it) }
        }

        val payload = withContext(Dispatchers.IO) {
            JSONObject()
                .put("method", "initialize")
                .put("protocolVersion", PROTOCOL_VERSION)
                .put("requestId", UUID.randomUUID().toString())
                .put("appId", config.appId)
                .put("sdkVersion", config.sdkVersion)
                .put("debug", config.debug)
                .apply { config.locale?.let { put("locale", it.toLanguageTag()) } }
        }

        val result = executeRaw(payload, generation)
        if (result.code != ResponseCode.OK) return result.asResult()
        val data = result.data
            ?: return BillingResult(ResponseCode.ERROR, "initialize returned no data")

        val info = try {
            withContext(Dispatchers.IO) { BillingJson.initInfo(data) }
        } catch (e: JSONException) {
            return BillingResult(ResponseCode.ERROR, "Invalid initialize response: ${e.message}")
        }
        if (generation != sdkGeneration) {
            return BillingResult(ResponseCode.SERVICE_UNAVAILABLE, "SDK was released during initialize")
        }
        if (info.protocolVersion != PROTOCOL_VERSION) {
            return BillingResult(
                ResponseCode.UNSUPPORTED_PROTOCOL,
                "NeoStore returned unsupported protocol ${info.protocolVersion}"
            )
        }
        initializedConfig = config
        initInfo = info
        registerLifecycleCallbacks(appContext)
        scheduleRestore()
        return BillingResult(ResponseCode.OK, data = info)
    }

    override fun setPurchasesListener(listener: PurchasesListener?) {
        purchasesListener = listener
    }

    @Volatile
    private var purchasesListener: PurchasesListener? = null

    @JvmStatic
    fun initializeAsync(
        context: Context,
        config: NeoStoreBillingConfig,
        callback: BillingCallback<InitInfo>
    ) = dispatch(callback) { initialize(context, config) }

    @JvmStatic
    fun getCatalogAsync(callback: BillingCallback<Catalog>) =
        dispatch(callback) { getCatalog() }

    @JvmStatic
    fun queryPurchasesAsync(callback: BillingCallback<PurchasesSnapshot>) =
        dispatch(callback) { queryPurchases() }

    @JvmStatic
    fun getSubscriptionAsync(
        subscriptionId: String,
        callback: BillingCallback<SubscriptionPurchase>
    ) = dispatch(callback) { getSubscription(subscriptionId) }

    @JvmStatic
    fun acknowledgePurchaseAsync(
        transactionId: String,
        callback: BillingCallback<Unit>
    ) = dispatch(callback) { acknowledgePurchase(transactionId) }

    override suspend fun getCatalog(): BillingResult<Catalog> =
        call("getCatalog") { BillingJson.catalog(it) }

    override suspend fun queryPurchases(): BillingResult<PurchasesSnapshot> {
        val result = call("queryPurchases") { BillingJson.purchases(it) }
        if (result.isSuccess) {
            result.data?.let { snapshot ->
                val listener = purchasesListener
                if (listener != null) {
                    callbackHandler.post { listener.onPurchasesUpdated(snapshot) }
                }
            }
        }
        return result
    }

    override suspend fun getSubscription(subscriptionId: String): BillingResult<SubscriptionPurchase> {
        if (!SUBSCRIPTION_ID.matches(subscriptionId)) {
            return BillingResult(ResponseCode.DEVELOPER_ERROR, "Invalid subscriptionId format")
        }
        return call("getSubscription", "subscriptionId" to subscriptionId) {
            BillingJson.subscriptionPurchase(it)
        }
    }

    override suspend fun acknowledgePurchase(transactionId: String): BillingResult<Unit> {
        if (!TRANSACTION_ID.matches(transactionId)) {
            return BillingResult(ResponseCode.DEVELOPER_ERROR, "Invalid transactionId format")
        }
        return call("acknowledgePurchase", "transactionId" to transactionId) { Unit }
    }

    @JvmStatic
    fun activityResultContract(): BillingActivityContract = BillingActivityContract()

    @JvmStatic
    fun cancelPendingActivityResult() {
        BillingActivityContract.resetActiveOperation()
    }

    internal fun validateActivityRequest(request: BillingActivityRequest) {
        val capability = when (request.action) {
            BillingActivityAction.PURCHASE_PRODUCT -> "purchaseProduct"
            BillingActivityAction.PURCHASE_SUBSCRIPTION -> "purchaseSubscription"
            BillingActivityAction.SIGN_IN -> "signIn"
        }
        val info = initInfo ?: throw BillingLaunchException(
            ResponseCode.DEVELOPER_ERROR,
            "Call initialize() before launching a NeoStore billing Activity"
        )
        if (capability !in info.capabilities) {
            throw BillingLaunchException(
                ResponseCode.DEVELOPER_ERROR,
                "NeoStore does not support capability: $capability"
            )
        }
    }

    override fun release() {
        val context: Context?
        val connection: ServiceConnection?
        val application: Application?
        val callbacks: Application.ActivityLifecycleCallbacks?
        val shouldUnbind: Boolean
        synchronized(lock) {
            sdkGeneration += 1
            context = applicationContext
            connection = serviceConnection
            application = lifecycleApplication
            callbacks = lifecycleCallbacks
            shouldUnbind = isBound
            isBound = false
            connectTimeout?.let(handler::removeCallbacks)
            connectTimeout = null
            serviceConnection = null
            lifecycleApplication = null
            lifecycleCallbacks = null
            startedActivityCount = 0
            serviceMessenger = null
            initializedConfig = null
            initInfo = null
            applicationContext = null
            debugLogging = false
            connectionWaiters.forEach {
                if (it.isActive) it.resumeWith(Result.failure(BillingFailure(ResponseCode.SERVICE_UNAVAILABLE, "SDK released")))
            }
            connectionWaiters.clear()
        }
        callbackHandler.removeCallbacks(restoreRunnable)
        if (application != null && callbacks != null) {
            application.unregisterActivityLifecycleCallbacks(callbacks)
        }
        pendingRequests.values.forEach {
            handler.removeCallbacks(it.timeout)
            if (it.continuation.isActive) {
                it.continuation.resumeWith(Result.failure(BillingFailure(ResponseCode.SERVICE_UNAVAILABLE, "SDK released")))
            }
        }
        pendingRequests.clear()
        purchasesListener = null
        if (shouldUnbind && context != null && connection != null) {
            try {
                context.unbindService(connection)
            } catch (_: IllegalArgumentException) {
                log("Service was already unbound")
            }
        }
    }

    private suspend fun <T> call(
        method: String,
        vararg fields: Pair<String, String>,
        parser: (JSONObject) -> T
    ): BillingResult<T> {
        val info = initInfo
            ?: return BillingResult(ResponseCode.DEVELOPER_ERROR, "Call initialize() before using billing methods")
        if (method !in info.capabilities) {
            return BillingResult(ResponseCode.DEVELOPER_ERROR, "NeoStore does not support capability: $method")
        }
        val generation = sdkGeneration
        val payload = withContext(Dispatchers.IO) {
            JSONObject()
                .put("method", method)
                .put("protocolVersion", PROTOCOL_VERSION)
                .put("requestId", UUID.randomUUID().toString())
                .apply { fields.forEach { (key, value) -> put(key, value) } }
        }

        val response = executeRaw(payload, generation)
        if (response.code != ResponseCode.OK) return response.asResult()
        val data = response.data
            ?: return BillingResult(ResponseCode.ERROR, "NeoStore returned no data")
        return try {
            BillingResult(
                responseCode = ResponseCode.OK,
                debugMessage = response.debugMessage,
                data = withContext(Dispatchers.IO) { parser(data) },
                rawResponseCode = response.rawCode,
                rawResponseCodeValue = response.rawCodeValue
            )
        } catch (e: JSONException) {
            BillingResult(
                ResponseCode.ERROR,
                "Invalid $method response: ${e.message}",
                rawResponseCode = response.rawCode,
                rawResponseCodeValue = response.rawCodeValue
            )
        }
    }

    private suspend fun executeRaw(payload: JSONObject, generation: Long): ParsedResponse {
        return try {
            withContext(Dispatchers.IO) {
                val messenger = awaitService(generation)
                val responseText = sendRequest(messenger, payload, generation)
                BillingJson.parseResponse(JSONObject(responseText))
            }
        } catch (e: BillingFailure) {
            ParsedResponse(e.code, e.message, null, null, null)
        } catch (e: JSONException) {
            ParsedResponse(ResponseCode.ERROR, "Malformed NeoStore response JSON", null, null, null)
        } catch (e: RemoteException) {
            ParsedResponse(ResponseCode.SERVICE_UNAVAILABLE, "NeoStore IPC failed", null, null, null)
        } catch (e: SecurityException) {
            ParsedResponse(ResponseCode.DEVELOPER_ERROR, "NeoStore service binding was denied", null, null, null)
        } catch (e: CancellationException) {
            throw e
        }
    }

    private fun <T> dispatch(callback: BillingCallback<T>, operation: suspend () -> BillingResult<T>) {
        callbackScope.launch {
            val result = operation()
            callbackHandler.post { callback.onResult(result) }
        }
    }

    private suspend fun awaitService(generation: Long): Messenger = suspendCancellableCoroutine { continuation ->
        var shouldBind = false
        var bindContext: Context? = null
        var connection: ServiceConnection? = null

        synchronized(lock) {
            if (generation != sdkGeneration) {
                continuation.resumeWith(
                    Result.failure(BillingFailure(ResponseCode.SERVICE_UNAVAILABLE, "SDK was released"))
                )
                return@suspendCancellableCoroutine
            }
            serviceMessenger?.let {
                continuation.resume(it)
                return@suspendCancellableCoroutine
            }
            val context = applicationContext
            if (context == null) {
                continuation.resumeWith(
                    Result.failure(BillingFailure(ResponseCode.DEVELOPER_ERROR, "initialize() has not set application context"))
                )
                return@suspendCancellableCoroutine
            }
            connectionWaiters.add(continuation)
            if (serviceConnection == null) {
                val created = createServiceConnection()
                serviceConnection = created
                shouldBind = true
                bindContext = context
                connection = created
            }
        }

        continuation.invokeOnCancellation {
            synchronized(lock) { connectionWaiters.remove(continuation) }
        }
        if (shouldBind) {
            val ctx = bindContext!!
            val conn = connection!!
            val bound = try {
                ctx.bindService(
                    Intent(ACTION_BIND).setPackage(NEOSTORE_PACKAGE),
                    conn,
                    Context.BIND_AUTO_CREATE
                )
            } catch (e: SecurityException) {
                failConnectionWaiters(
                    BillingFailure(ResponseCode.DEVELOPER_ERROR, "NeoStore service binding was denied"),
                    conn
                )
                false
            }
            val keepBinding = synchronized(lock) {
                if (serviceConnection === conn) {
                    isBound = bound
                    true
                } else {
                    false
                }
            }
            if (bound && !keepBinding) {
                try {
                    ctx.unbindService(conn)
                } catch (_: IllegalArgumentException) {
                    log("Late service binding was already unbound")
                }
            }
            if (!bound) {
                failConnectionWaiters(
                    BillingFailure(ResponseCode.SERVICE_UNAVAILABLE, "NeoStore BillingService is unavailable"),
                    conn
                )
            } else if (keepBinding) {
                val timeout = Runnable {
                    failConnectionWaiters(
                        BillingFailure(ResponseCode.SERVICE_UNAVAILABLE, "Timed out connecting to NeoStore"),
                        conn
                    )
                }
                synchronized(lock) {
                    if (serviceMessenger == null) {
                        connectTimeout = timeout
                        handler.postDelayed(timeout, CONNECT_TIMEOUT_MS)
                    }
                }
            }
        }
    }

    private fun createServiceConnection() = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null) {
                failConnectionWaiters(
                    BillingFailure(ResponseCode.SERVICE_UNAVAILABLE, "NeoStore returned an empty service binder"),
                    this
                )
                return
            }
            val connected = Messenger(binder)
            val waiters: List<CancellableContinuation<Messenger>>
            synchronized(lock) {
                if (serviceConnection !== this) return
                connectTimeout?.let(handler::removeCallbacks)
                connectTimeout = null
                serviceMessenger = connected
                waiters = connectionWaiters.toList()
                connectionWaiters.clear()
            }
            waiters.forEach { if (it.isActive) it.resume(connected) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            onTransportDisconnected(this)
        }

        override fun onBindingDied(name: ComponentName?) {
            onTransportDisconnected(this)
        }

        override fun onNullBinding(name: ComponentName?) {
            failConnectionWaiters(
                BillingFailure(ResponseCode.SERVICE_UNAVAILABLE, "NeoStore returned a null service binding"),
                this
            )
        }
    }

    private suspend fun sendRequest(messenger: Messenger, payload: JSONObject, generation: Long): String =
        suspendCancellableCoroutine { continuation ->
            val requestId = payload.getString("requestId")
            val timeout = Runnable {
                val pending = pendingRequests.remove(requestId)
                if (pending != null && pending.continuation.isActive) {
                    pending.continuation.resumeWith(
                        Result.failure(BillingFailure(ResponseCode.SERVICE_UNAVAILABLE, "NeoStore IPC request timed out"))
                    )
                }
            }
            val pending = PendingRequest(continuation, timeout)
            val accepted = synchronized(lock) {
                if (generation != sdkGeneration) {
                    false
                } else {
                    pendingRequests[requestId] = pending
                    true
                }
            }
            if (!accepted) {
                continuation.resumeWith(
                    Result.failure(BillingFailure(ResponseCode.SERVICE_UNAVAILABLE, "SDK was released"))
                )
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation {
                pendingRequests.remove(requestId)
                handler.removeCallbacks(timeout)
            }
            handler.postDelayed(timeout, REQUEST_TIMEOUT_MS)
            val message = Message.obtain(null, MESSAGE_WHAT).apply {
                data = Bundle().apply { putString(REQUEST_KEY, payload.toString()) }
                replyTo = replyMessenger
            }
            try {
                messenger.send(message)
            } catch (e: RemoteException) {
                pendingRequests.remove(requestId)
                handler.removeCallbacks(timeout)
                if (continuation.isActive) {
                    continuation.resumeWith(
                        Result.failure(BillingFailure(ResponseCode.SERVICE_UNAVAILABLE, "Unable to send NeoStore IPC request"))
                    )
                }
                onTransportDisconnected(expectedMessenger = messenger)
            }
        }

    private fun failConnectionWaiters(
        failure: BillingFailure,
        expectedConnection: ServiceConnection? = null
    ) {
        val context: Context?
        val connection: ServiceConnection?
        val shouldUnbind: Boolean
        val waiters = synchronized(lock) {
            if (expectedConnection != null && serviceConnection !== expectedConnection) return
            context = applicationContext
            connection = serviceConnection
            shouldUnbind = isBound
            isBound = false
            serviceMessenger = null
            connectTimeout?.let(handler::removeCallbacks)
            connectTimeout = null
            val current = connectionWaiters.toList()
            connectionWaiters.clear()
            serviceConnection = null
            current
        }
        if (shouldUnbind && context != null && connection != null) {
            try {
                context.unbindService(connection)
            } catch (_: IllegalArgumentException) {
                log("Timed-out service was already unbound")
            }
        }
        waiters.forEach { if (it.isActive) it.resumeWith(Result.failure(failure)) }
    }

    private fun onTransportDisconnected(
        expectedConnection: ServiceConnection? = null,
        expectedMessenger: Messenger? = null
    ) {
        val context: Context?
        val connection: ServiceConnection?
        val shouldUnbind: Boolean
        synchronized(lock) {
            if (expectedConnection != null && serviceConnection !== expectedConnection) return
            if (expectedMessenger != null && serviceMessenger !== expectedMessenger) return
            context = applicationContext
            connection = serviceConnection
            shouldUnbind = isBound
            serviceMessenger = null
            serviceConnection = null
            isBound = false
            connectTimeout?.let(handler::removeCallbacks)
            connectTimeout = null
        }

        if (shouldUnbind && context != null && connection != null) {
            try {
                context.unbindService(connection)
            } catch (_: IllegalArgumentException) {
                log("Disconnected service was already unbound")
            }
        }
        pendingRequests.values.forEach { pending ->
            handler.removeCallbacks(pending.timeout)
            if (pending.continuation.isActive) {
                pending.continuation.resumeWith(
                    Result.failure(BillingFailure(ResponseCode.SERVICE_UNAVAILABLE, "NeoStore service disconnected"))
                )
            }
        }
        pendingRequests.clear()
    }

    private fun registerLifecycleCallbacks(context: Context) {
        val application = context.applicationContext as? Application ?: return
        synchronized(lock) {
            if (lifecycleApplication === application) return
            lifecycleApplication?.let { old ->
                lifecycleCallbacks?.let(old::unregisterActivityLifecycleCallbacks)
            }
            val callbacks = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    val becameForeground = synchronized(lock) {
                        startedActivityCount += 1
                        startedActivityCount == 1
                    }
                    if (becameForeground) scheduleRestore()
                }

                override fun onActivityStopped(activity: Activity) {
                    synchronized(lock) {
                        startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
                    }
                }

                override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            }
            lifecycleApplication = application
            lifecycleCallbacks = callbacks
            application.registerActivityLifecycleCallbacks(callbacks)
        }
    }

    private fun scheduleRestore() {
        callbackHandler.removeCallbacks(restoreRunnable)
        callbackHandler.postDelayed(restoreRunnable, RESTORE_DEBOUNCE_MS)
    }

    private fun failMalformedPendingResponse() {
        if (pendingRequests.size != 1) return
        val entry = pendingRequests.entries.firstOrNull() ?: return
        if (pendingRequests.remove(entry.key, entry.value)) {
            handler.removeCallbacks(entry.value.timeout)
            if (entry.value.continuation.isActive) {
                entry.value.continuation.resumeWith(
                    Result.failure(BillingFailure(ResponseCode.ERROR, "Malformed NeoStore response"))
                )
            }
        }
    }

    private fun log(message: String) {
        if (debugLogging) Log.d(TAG, message)
    }

    private data class PendingRequest(
        val continuation: CancellableContinuation<String>,
        val timeout: Runnable
    )

    private class IncomingHandler(looperHandler: Handler) : Handler(looperHandler.looper) {
        override fun handleMessage(message: Message) {
            if (message.what != MESSAGE_WHAT) return
            val response = message.data?.getString(RESPONSE_KEY)
            if (response.isNullOrBlank()) {
                failMalformedPendingResponse()
                return
            }
            val requestId = try {
                JSONObject(response).optString("requestId").takeIf(String::isNotBlank)
            } catch (_: JSONException) {
                failMalformedPendingResponse()
                return
            } ?: run {
                failMalformedPendingResponse()
                return
            }

            val pending = pendingRequests.remove(requestId) ?: return
            handler.removeCallbacks(pending.timeout)
            if (pending.continuation.isActive) pending.continuation.resume(response)
        }
    }

    private class BillingFailure(
        val code: ResponseCode,
        override val message: String
    ) : Exception(message)

    private fun ParsedResponse.asResult(): BillingResult<Nothing> = BillingResult(
        responseCode = code,
        debugMessage = debugMessage,
        rawResponseCode = rawCode,
        rawResponseCodeValue = rawCodeValue
    )

    private val SEMVER = Regex("""\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?""")
    private val SUBSCRIPTION_ID = Regex("""sub_[A-Za-z0-9]+""")
    private val TRANSACTION_ID = Regex("""check_[A-Za-z0-9]+""")
    private const val RESTORE_DEBOUNCE_MS = 400L
}
