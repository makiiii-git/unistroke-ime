package com.unistroke.ime

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
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

/**
 * プレミアムの鍵（**play フレーバー**）= Google Play の買い切りアイテム。
 *
 * 試用 [TRIAL_DAYS] 日のあと、アイテム [PRODUCT_ID]（非消費型）の購入で解錠する。
 * 購入は Google アカウントに紐づくので、機種変更や再インストールのあとも
 * [refresh] で鍵が戻る（＝「購入の復元」）。価格は Play Console で決めるもので、
 * ここには書かない。画面には Play が返す表示用の価格をそのまま出す。
 *
 * ### いつ問い合わせるか
 * アプリの画面（入口・設定）を開いたときだけ。**IME サービスからは呼ばない**。
 * IME は [Entitlement] が設定に残した結果を読むだけなので、入力中に Play へ
 * 問い合わせることも、購入画面が出ることもない。
 *
 * ### 検証について
 * 購入の検証は端末内だけで行う（サーバーを持たない）。ソースを公開している以上、
 * 自分でビルドすれば鍵は外せる。それは許容している。
 *
 * 形（定数と関数のシグネチャ）は `src/github` 側と揃えること。
 */
object PremiumGate {

    /** 購入しないと閉じる機能があるか。 */
    const val REQUIRES_PURCHASE = true

    /** 全機能を試せる日数。 */
    const val TRIAL_DAYS = 5

    /** Play Console に登録するアプリ内アイテム（1 回限りのアイテム）の ID。 */
    const val PRODUCT_ID = "premium_unlock"

    private val main = Handler(Looper.getMainLooper())
    private var client: BillingClient? = null
    private var appContext: Context? = null

    /** 進行中の購入操作の結果待ち。購入画面は 1 つしか出ないので 1 つで足りる。 */
    private var pendingPurchase: ((Entitlement.PurchaseResult) -> Unit)? = null

    /** 購入画面の結果。別アプリ（Play ストア）での購入がここへ届くこともある。 */
    private val purchasesListener = PurchasesUpdatedListener { result, purchases ->
        main.post {
            val context = appContext ?: return@post
            val outcome = when (result.responseCode) {
                BillingClient.BillingResponseCode.OK -> {
                    val list = purchases.orEmpty()
                    applyPurchases(context, list)
                    when {
                        list.any { it.owns() } -> Entitlement.PurchaseResult.PURCHASED
                        // コンビニ払いなど、支払いが済むまで鍵は開かない
                        list.any { it.isPending() } -> Entitlement.PurchaseResult.PENDING
                        else -> Entitlement.PurchaseResult.FAILED
                    }
                }
                BillingClient.BillingResponseCode.USER_CANCELED ->
                    Entitlement.PurchaseResult.CANCELED
                BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                    // 持っているのに鍵が閉じていた（再インストール直後など）。取り直す。
                    refresh(context)
                    Entitlement.PurchaseResult.PURCHASED
                }
                else -> Entitlement.PurchaseResult.FAILED
            }
            pendingPurchase?.invoke(outcome)
            pendingPurchase = null
        }
    }

    /**
     * Google Play に「このアイテムを持っているか」を尋ね、[Entitlement] へ反映する。
     * 「購入の復元」もこれ。失敗したとき（圏外・Play が無い）は前回の結果を変えない。
     */
    fun refresh(context: Context, onDone: () -> Unit = {}) {
        val app = context.applicationContext
        connect(app, onFail = { main.post(onDone) }) { billing ->
            val params = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
            billing.queryPurchasesAsync(params) { result, purchases ->
                main.post {
                    if (result.isOk()) applyPurchases(app, purchases)
                    onDone()
                }
            }
        }
    }

    /** 表示用の価格（Play が地域と通貨に合わせて整形した文字列）。取れなければ null。 */
    fun loadPrice(context: Context, onPrice: (String?) -> Unit) {
        val app = context.applicationContext
        queryProduct(app) { details ->
            onPrice(details?.oneTimePurchaseOfferDetails?.formattedPrice)
        }
    }

    /** 購入画面を出す。結果は [onResult] へ（メインスレッド）。 */
    fun purchase(activity: Activity, onResult: (Entitlement.PurchaseResult) -> Unit) {
        val app = activity.applicationContext
        queryProduct(app) { details ->
            val billing = client
            if (details == null || billing == null || activity.isFinishing || activity.isDestroyed) {
                onResult(Entitlement.PurchaseResult.UNAVAILABLE)
                return@queryProduct
            }
            val product = BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(details)
                .build()
            val flow = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(product))
                .build()
            pendingPurchase = onResult
            val launched = billing.launchBillingFlow(activity, flow)
            if (!launched.isOk()) {
                pendingPurchase = null
                onResult(
                    if (launched.responseCode ==
                        BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED
                    ) {
                        refresh(app)
                        Entitlement.PurchaseResult.PURCHASED
                    } else {
                        Entitlement.PurchaseResult.FAILED
                    },
                )
            }
        }
    }

    // ------------------------------------------------------------------ 内部

    private fun queryProduct(app: Context, onDetails: (ProductDetails?) -> Unit) {
        connect(app, onFail = { main.post { onDetails(null) } }) { billing ->
            val product = QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_ID)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(listOf(product))
                .build()
            billing.queryProductDetailsAsync(params) { result, found ->
                main.post {
                    onDetails(
                        if (result.isOk()) found.productDetailsList.firstOrNull() else null,
                    )
                }
            }
        }
    }

    /** 持っている購入を鍵へ反映し、未承認のものは承認する（3 日以内に承認しないと自動で払い戻される）。 */
    private fun applyPurchases(app: Context, purchases: List<Purchase>) {
        Entitlement.setPurchased(app, purchases.any { it.owns() })
        val billing = client ?: return
        for (purchase in purchases) {
            if (!purchase.owns() || purchase.isAcknowledged) continue
            val params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            // 失敗しても次の refresh でもう一度試す
            billing.acknowledgePurchase(params) { }
        }
    }

    private fun Purchase.owns(): Boolean =
        products.contains(PRODUCT_ID) && purchaseState == Purchase.PurchaseState.PURCHASED

    private fun Purchase.isPending(): Boolean =
        products.contains(PRODUCT_ID) && purchaseState == Purchase.PurchaseState.PENDING

    private fun BillingResult.isOk(): Boolean =
        responseCode == BillingClient.BillingResponseCode.OK

    /** Play との接続を用意してから [onReady] を呼ぶ。繋がらなければ [onFail]。 */
    private fun connect(app: Context, onFail: () -> Unit, onReady: (BillingClient) -> Unit) {
        appContext = app
        val existing = client
        if (existing != null && existing.isReady) {
            onReady(existing)
            return
        }
        val billing = existing ?: BillingClient.newBuilder(app)
            .setListener(purchasesListener)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
            )
            .build()
            .also { client = it }
        billing.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.isOk()) onReady(billing) else onFail()
            }

            // 切れたら次に使うときに繋ぎ直す（connect が isReady を見ている）
            override fun onBillingServiceDisconnected() = Unit
        })
    }
}
