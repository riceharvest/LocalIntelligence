package dev.localintelligence.app.extras

import android.app.Activity
import android.content.Context
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
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import dev.localintelligence.android.cloud.CredentialSource
import dev.localintelligence.android.cloud.ExtrasBackend
import dev.localintelligence.android.cloud.ExtrasGrant
import dev.localintelligence.android.cloud.ExtrasReply
import dev.localintelligence.android.cloud.ExtrasSettings
import dev.localintelligence.app.settings.SettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.resume

// ===========================================================================
// Google Play Billing for Extras: one monthly subscription, product `extras_monthly`.
//
// The flow: query the price, launch Play's purchase sheet, and on a purchased result
// send the purchase token to the Extras backend, which verifies it with Google, acknowledges
// it and returns this phone's own capped keys. The app never acknowledges a purchase itself:
// the backend does, so a purchase is not refunded while the backend is reachable.
//
// WHY THE APP CALLS THE BACKEND AT START, WITH NO TIMER: Play keeps a purchase until it is
// acknowledged, so a purchase whose activation failed is found again on the next start and
// activated then. No polling, no background job.
//
// Nothing here runs unless BuildConfig.EXTRAS_BACKEND_URL is set.
// ===========================================================================

/** Buys, restores and refreshes Extras. One instance, owned by the app container. */
class ExtrasBilling(
    private val context: Context,
    private val settings: SettingsStore,
    /** The backend address from BuildConfig. Blank means Extras is off and nothing is called. */
    private val backendUrl: String,
) {
    private val appId: String = context.packageName

    /** The Play price string for one month, from Play itself. Null until it is loaded. */
    private val _price = MutableStateFlow<String?>(null)
    val price: StateFlow<String?> = _price.asStateFlow()

    /** The last plain sentence for the user about a purchase or a refresh. Null when there is none. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** The answer of the purchase sheet, delivered by Play's listener. */
    private var awaitingSheet: CompletableDeferred<Pair<BillingResult, List<Purchase>?>>? = null

    private val listener = PurchasesUpdatedListener { result, purchases ->
        awaitingSheet?.complete(result to purchases)
    }

    private var client: BillingClient? = null

    /** Connects to Play. Null when Play is not there or refuses the connection. */
    private suspend fun connect(): BillingClient? {
        val current = client ?: BillingClient.newBuilder(context)
            .setListener(listener)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().enablePrepaidPlans().build(),
            )
            .build()
            .also { client = it }
        if (current.isReady) return current
        val ready = suspendCancellableCoroutine { cont ->
            current.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (cont.isActive) cont.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
                }

                override fun onBillingServiceDisconnected() {
                    if (cont.isActive) cont.resume(false)
                }
            })
        }
        return current.takeIf { ready }
    }

    /** Loads the price string for the monthly plan. Leaves the price null when Play cannot say. */
    suspend fun loadPrice() {
        if (!enabled) return
        val play = connect() ?: return
        val details = productDetails(play) ?: return
        // The last pricing phase is the recurring price; any trial or intro phase comes first.
        _price.value = details.subscriptionOfferDetails?.firstOrNull()
            ?.pricingPhases?.pricingPhaseList?.lastOrNull()?.formattedPrice
    }

    /**
     * Runs Play's purchase sheet for Extras. On a purchased result the purchase is activated
     * with the backend. Cancelling is silent; every other outcome says one plain sentence.
     */
    suspend fun subscribe(activity: Activity) {
        if (!enabled) return
        _notice.value = null
        val play = connect() ?: return setNotice(PLAY_UNREACHABLE)
        val details = productDetails(play) ?: return setNotice("Extras is not available from Google Play on this phone right now.")
        val offer = details.subscriptionOfferDetails?.firstOrNull()
            ?: return setNotice("Extras is not available from Google Play on this phone right now.")
        val flow = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .setOfferToken(offer.offerToken)
                        .build(),
                ),
            )
            .build()
        val sheet = CompletableDeferred<Pair<BillingResult, List<Purchase>?>>()
        awaitingSheet = sheet
        try {
            val launched = play.launchBillingFlow(activity, flow)
            when (launched.responseCode) {
                BillingClient.BillingResponseCode.OK -> {
                    val (result, purchases) = withTimeoutOrNull(SHEET_WAIT_MS) { sheet.await() }
                        ?: return setNotice(PLAY_UNREACHABLE)
                    handleSheet(play, result, purchases)
                }
                BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> restoreOwned(play)
                BillingClient.BillingResponseCode.USER_CANCELED -> Unit
                else -> setNotice(PLAY_REFUSED)
            }
        } finally {
            awaitingSheet = null
        }
    }

    private suspend fun handleSheet(play: BillingClient, result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                val purchase = purchases.orEmpty().firstOrNull { it.products.contains(ExtrasSettings.PRODUCT_ID) }
                    ?: return setNotice(PLAY_REFUSED)
                when (purchase.purchaseState) {
                    Purchase.PurchaseState.PURCHASED -> activate(purchase.purchaseToken)
                    Purchase.PurchaseState.PENDING ->
                        setNotice("Google Play is still processing the payment. Extras starts once it clears.")
                    else -> setNotice(PLAY_REFUSED)
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> restoreOwned(play)
            else -> setNotice(PLAY_REFUSED)
        }
    }

    /** Play says the user already owns Extras: activate the purchase Play holds. */
    private suspend fun restoreOwned(play: BillingClient) {
        when (val owned = ownedPurchase(play)) {
            is Owned.Found -> activate(owned.purchase.purchaseToken)
            Owned.None -> setNotice("Google Play has no active Extras purchase for this account. Try again in a moment.")
            Owned.Unknown -> setNotice(PLAY_UNREACHABLE)
        }
    }

    /**
     * Run once at app start, and only when the backend is set. Activates a purchase that is not
     * activated on this phone yet, refreshes a grant that is near its end, and clears the local
     * keys when the subscription has ended. Errors stay quiet: nothing is shown at start.
     */
    suspend fun checkAtStart() {
        if (!enabled) return
        val play = connect() ?: return
        val storedToken = settings[ExtrasSettings.PURCHASE_TOKEN]
        val active = CredentialSource.extrasActive(settings)
        val nearEnd = ExtrasSettings.expiresAt(settings)?.let { it.isBefore(Instant.now().plus(REFRESH_WINDOW)) } ?: true
        when (val owned = ownedPurchase(play)) {
            Owned.Unknown -> return
            is Owned.Found -> {
                val token = owned.purchase.purchaseToken
                when {
                    !active -> activate(token, quiet = true)
                    token != storedToken -> activate(token, quiet = true)
                    nearEnd -> refresh(token, quiet = true)
                }
            }
            Owned.None -> if (storedToken.isNotBlank()) refresh(storedToken, quiet = true)
        }
    }

    private suspend fun activate(purchaseToken: String, quiet: Boolean = false) {
        if (!enabled) return
        val reply = withContext(Dispatchers.IO) {
            ExtrasBackend.activate(backendUrl, appId, purchaseToken, installId())
        }
        apply(reply, purchaseToken, quiet)
    }

    private suspend fun refresh(purchaseToken: String, quiet: Boolean) {
        if (!enabled) return
        val reply = withContext(Dispatchers.IO) {
            ExtrasBackend.refresh(backendUrl, purchaseToken, installId())
        }
        apply(reply, purchaseToken, quiet)
    }

    /** Stores a grant, or clears Extras when the backend says the subscription is not active. */
    private fun apply(reply: ExtrasReply, purchaseToken: String, quiet: Boolean) {
        when (reply) {
            is ExtrasReply.Granted -> {
                store(reply.grant, purchaseToken)
                if (!quiet) {
                    setNotice("Extras is on until ${shortDate(reply.grant.expiresAt)}.")
                }
            }
            ExtrasReply.NotActive -> {
                clear()
                if (!quiet) setNotice("Google Play says this Extras subscription is not active.")
            }
            is ExtrasReply.Failed -> if (!quiet) setNotice(reply.message)
        }
    }

    private fun store(grant: ExtrasGrant, purchaseToken: String) {
        settings.setSecret(ExtrasSettings.OPENROUTER_KEY, grant.openRouterKey)
        // A null Exa key clears any earlier one: search then uses the chosen or free provider.
        val exa = grant.exaKey
        if (exa.isNullOrBlank()) settings.clearSecret(ExtrasSettings.EXA_KEY)
        else settings.setSecret(ExtrasSettings.EXA_KEY, exa)
        settings.setSecret(ExtrasSettings.PURCHASE_TOKEN, purchaseToken)
        settings.set(ExtrasSettings.OPENROUTER_MODEL, grant.openRouterModel)
        settings.set(ExtrasSettings.EXPIRES_AT, grant.expiresAt)
    }

    /** Removes every Extras credential from this phone. The purchase itself stays with Play. */
    private fun clear() {
        settings.clearSecret(ExtrasSettings.OPENROUTER_KEY)
        settings.clearSecret(ExtrasSettings.EXA_KEY)
        settings.clearSecret(ExtrasSettings.PURCHASE_TOKEN)
        settings.set(ExtrasSettings.OPENROUTER_MODEL, "")
        settings.set(ExtrasSettings.EXPIRES_AT, "")
    }

    /** A random id for this install, made once and kept in Settings. It is not an identity. */
    private fun installId(): String {
        val existing = settings[ExtrasSettings.INSTALL_ID]
        if (existing.isNotBlank()) return existing
        return UUID.randomUUID().toString().also { settings.set(ExtrasSettings.INSTALL_ID, it) }
    }

    private suspend fun productDetails(play: BillingClient): ProductDetails? {
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(ExtrasSettings.PRODUCT_ID)
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        val params = QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()
        val result = play.queryProductDetails(params)
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) return null
        return result.productDetailsList?.firstOrNull()
    }

    /** The active Extras purchase Play holds, or why there is none. */
    private suspend fun ownedPurchase(play: BillingClient): Owned {
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        val result = play.queryPurchasesAsync(params)
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) return Owned.Unknown
        val purchase = result.purchasesList.firstOrNull {
            it.products.contains(ExtrasSettings.PRODUCT_ID) &&
                it.purchaseState == Purchase.PurchaseState.PURCHASED
        }
        return if (purchase == null) Owned.None else Owned.Found(purchase)
    }

    private sealed interface Owned {
        data object Unknown : Owned
        data object None : Owned
        class Found(val purchase: Purchase) : Owned
    }

    private fun setNotice(text: String) {
        _notice.value = text
    }

    private fun shortDate(iso: String): String = runCatching {
        java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy")
            .withZone(java.time.ZoneId.systemDefault())
            .format(Instant.parse(iso))
    }.getOrDefault("the end of this period")

    companion object {
        /** True when a backend address is set. Without it, Extras is off and nothing here runs. */
        val enabled: Boolean get() = dev.localintelligence.app.BuildConfig.EXTRAS_BACKEND_URL.isNotBlank()

        /** Start-time refresh window: a grant that ends within this is refreshed. */
        private val REFRESH_WINDOW: Duration = Duration.ofDays(3)
        private const val SHEET_WAIT_MS = 15 * 60 * 1000L
        private const val PLAY_UNREACHABLE = "Google Play could not be reached. Try again later."
        private const val PLAY_REFUSED = "Google Play did not start the Extras subscription. Try again later."

        /** The Play page where the user cancels or changes the subscription. */
        fun manageUrl(packageName: String): String =
            "https://play.google.com/store/account/subscriptions?sku=${ExtrasSettings.PRODUCT_ID}&package=$packageName"
    }
}
