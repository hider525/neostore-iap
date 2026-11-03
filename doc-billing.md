# NeoStore Billing SDK — руководство для разработчиков

Руководство по подключению Android SDK `NeoStore-Billing` сторонними приложениями. В нём приведены инструкции отдельно для Kotlin и Java, описание методов, моделей и результатов.

> SDK — клиентская обёртка над IPC NeoStore. Он не обращается к backend NeoStore напрямую, не получает токены, не выбирает платёжные предложения и не подтверждает права на контент. Покупку и вход выполняет приложение NeoStore. Постоянные права на товар проверяйте на backend своего приложения.

## Содержание

1. [Возможности и требования](#1-возможности-и-требования)
2. [Подключение библиотеки](#2-подключение-библиотеки)
3. [Порядок интеграции](#3-порядок-интеграции)
4. [Kotlin](#4-интеграция-kotlin)
5. [Java](#5-интеграция-java)
6. [Методы и Activity Result](#6-методы-и-activity-result)
7. [Модели данных](#7-модели-данных)
8. [Коды ответов и обработка ошибок](#8-коды-ответов-и-обработка-ошибок)
9. [Безопасность, восстановление и выдача товара](#9-безопасность-восстановление-и-выдача-товара)
10. [Ограничения и диагностика](#10-ограничения-и-диагностика)

## 1. Возможности и требования

| Параметр | Значение |
|---|---|
| Android package NeoStore | `com.neostudio.neostore` |
| Версия IPC | `1` |
| Минимальная версия Android | API 24 |
| Формат поставки | Android AAR |
| Инициализация | Положительный `appId`, зарегистрированный в NeoStore для приложения |
| Взаимодействие с сервисом | Android `Messenger`, JSON |
| UI-операции | Activity Result API |

SDK предоставляет:

- проверку установки и включённости NeoStore;
- инициализацию интеграции и чтение `capabilities`;
- загрузку каталога;
- восстановление покупок;
- запрос подписки по ID;
- подтверждение выдачи consumable;
- запуск покупки товара, покупки подписки и входа через NeoStore Activity;
- типизированные модели и стабильные коды ошибок;
- Java callback-фасады для методов сервиса и Fake для host unit-тестов.

SDK не предоставляет `openSubscriptionManagement`: соответствующего экрана и capability в протоколе нет.

## 2. Подключение библиотеки

### 2.1. Требования проекта

- Android Gradle Plugin 9.1 или новее;
- `compileSdk` 37 или новее;
- `minSdk` не ниже 24;
- AndroidX Activity Result API.

SDK требует `androidx.activity:activity-ktx` и coroutines; они включены в Gradle/Maven-метаданные библиотеки. Если вы вручную подключаете только AAR-файл, настройте зависимости библиотеки в своём проекте:

```kotlin
implementation("androidx.activity:activity-ktx:1.10.1")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
```

Для хост-приложения в этом репозитории SDK подключается как `implementation(project(":billing"))`. Release AAR после сборки находится в `billing/build/outputs/aar/billing-release.aar`. Gradle Maven publication настроена с координатами:

```text
com.neostudio:neostore-billing:1.0.0
```

Версию AAR и опубликованные координаты следует согласовывать с командой, поставляющей библиотеку.

### 2.2. Видимость пакета

Manifest библиотеки добавляет через manifest merger только видимость NeoStore:

```xml
<queries>
    <package android:name="com.neostudio.neostore" />
</queries>
```

Проверьте итоговый manifest host-приложения, особенно если отключён manifest merger или AAR подключается нестандартно. SDK не добавляет разрешения `INTERNET` или `QUERY_ALL_PACKAGES`.

## 3. Порядок интеграции

1. Вызовите `checkAvailability(context)`.
2. Если NeoStore доступен, вызовите `initialize(context, config)`.
3. Убедитесь, что результат имеет код `OK`; сохраните или проверьте `InitInfo.capabilities`.
4. До вызова методов сервиса или запуска UI убедитесь, что соответствующая capability присутствует.
5. При старте и возвращении host-приложения на передний план обновляйте покупки через `queryPurchases()`.
6. После завершения работы интеграции вызовите `release()`.

`initialize` проверяет `appId > 0`, передаёт `protocolVersion = 1` и привязывает сессию к приложению, которое NeoStore определяет по Android UID/пакету вызывающего. Не передавайте `appId` в Intent покупки. Повторный вызов с той же конфигурацией возвращает текущую информацию и не создаёт новую сессию. Для смены конфигурации сначала вызовите `release()`.

`checkAvailability` локально проверяет пакет через `PackageManager`. `AVAILABLE` не гарантирует, что сервис успешно привяжется: ошибка bind или отсутствующий сервис вернут `SERVICE_UNAVAILABLE`. SDK не может определить `UPDATE_REQUIRED` по версии NeoStore; проверяйте результат протокола и capabilities.

## 4. Интеграция Kotlin

### 4.1. Инициализация и методы без UI

Вызовы IPC — `suspend`-функции. Вызывайте их из coroutine, не блокируя UI-поток.

```kotlin
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.neostudio.billing.*
import kotlinx.coroutines.launch
import java.util.Locale

class StoreActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)

        if (NeoStoreBilling.checkAvailability(this) != NeoStoreAvailability.AVAILABLE) {
            showMessage("Установите и включите NeoStore")
            return
        }

        lifecycleScope.launch {
            val init = NeoStoreBilling.initialize(
                context = this@StoreActivity,
                config = NeoStoreBillingConfig(
                    appId = 13L,
                    locale = Locale.getDefault(),
                    debug = false
                )
            )

            if (init.responseCode != ResponseCode.OK) {
                showBillingError(init)
                return@launch
            }

            val info = init.data ?: return@launch
            if ("getCatalog" in info.capabilities) {
                val catalog = NeoStoreBilling.getCatalog()
                if (catalog.responseCode == ResponseCode.OK) {
                    renderCatalog(catalog.data)
                } else {
                    showBillingError(catalog)
                }
            }
        }
    }

    private fun showMessage(message: String) { /* показать сообщение приложения */ }
    private fun renderCatalog(catalog: Catalog?) { /* показать каталог */ }
    private fun showBillingError(result: BillingResult<*>) { /* обработать код */ }
}
```

### 4.2. Покупка, подписка и вход

Покупки и вход запускаются через `ActivityResultContract`. Зарегистрируйте launcher до перехода Activity в состояние `STARTED`, обычно в `onCreate`.

```kotlin
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.neostudio.billing.*
import kotlinx.coroutines.launch

class StoreActivity : AppCompatActivity() {
    private lateinit var billingLauncher: ActivityResultLauncher<BillingActivityRequest>

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)

        billingLauncher = registerForActivityResult(
            NeoStoreBilling.activityResultContract()
        ) { result ->
            when (result.responseCode) {
                ResponseCode.OK -> {
                    result.data?.productPurchase?.let { product ->
                        // Сначала проверьте/выдайте товар на backend host-приложения.
                        onProductPurchase(product)
                    }
                    result.data?.subscriptionPurchase?.let { subscription ->
                        // Проверьте подписку на backend host-приложения.
                        onSubscriptionPurchase(subscription)
                    }
                }
                ResponseCode.ALREADY_OWNED -> {
                    // Данные существующей покупки могут быть в result.data.
                    // Обновите права через queryPurchases() и backend-проверку.
                    restore()
                }
                ResponseCode.USER_CANCELED -> Unit
                else -> showBillingError(result)
            }
        }

        // Вызывайте из обработчика действий пользователя:
        // billingLauncher.launch(BillingActivityRequest.purchaseProduct("remove_ads"))
        // billingLauncher.launch(
        //     BillingActivityRequest.purchaseSubscription("premium", "monthly")
        // )
        // billingLauncher.launch(BillingActivityRequest.signIn())
    }

    private fun restore() {
        lifecycleScope.launch {
            val result = NeoStoreBilling.queryPurchases()
            if (result.responseCode == ResponseCode.OK) {
                renderPurchases(result.data)
            } else {
                showBillingError(result)
            }
        }
    }

    private fun onProductPurchase(purchase: ProductPurchase) { /* проверка на backend */ }
    private fun onSubscriptionPurchase(purchase: SubscriptionPurchase) { /* проверка на backend */ }
    private fun renderPurchases(snapshot: PurchasesSnapshot?) { /* обновление UI */ }
    private fun showBillingError(result: BillingResult<*>) { /* обработка ошибки */ }
}
```

Для запуска доступны только фабрики:

```kotlin
BillingActivityRequest.purchaseProduct(productIdentifier)
BillingActivityRequest.purchaseSubscription(subscriptionIdentifier, planIdentifier)
BillingActivityRequest.signIn()
```

Одновременно разрешена одна интерактивная операция. Запуск без успешной инициализации, без нужной capability или при недоступном NeoStore выбрасывает `BillingLaunchException`; у исключения есть `responseCode`. Если Android отклонил запуск и callback Activity Result не будет вызван, перед следующей попыткой вызовите `NeoStoreBilling.cancelPendingActivityResult()`.

### 4.3. Автоматическое восстановление и listener

После успешной инициализации SDK планирует `queryPurchases()`, а также обновляет покупки при возвращении host-приложения на передний план. Автоматические обновления выполняются только если NeoStore объявил capability `queryPurchases`.

Чтобы получать снимки, установите listener:

```kotlin
NeoStoreBilling.setPurchasesListener(object : PurchasesListener {
    override fun onPurchasesUpdated(snapshot: PurchasesSnapshot) {
        // Callback вызывается на main thread.
        // Обрабатывайте выдачу идемпотентно: доставка может повториться.
    }
})
```

Уведомления не гарантируют exactly-once доставку. Приложение должно само сверять транзакции и права на своём backend.

## 5. Интеграция Java

В Java используйте callback-фасады для IPC-методов без UI. Callback вызывается на main thread.

### 5.1. Инициализация и загрузка каталога

`NeoStoreBillingConfig` — Kotlin data class без Java-перегрузок конструктора. В Java передавайте все четыре аргумента: `appId`, `Locale`, `debug`, `sdkVersion`.

```java
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.neostudio.billing.BillingCallback;
import com.neostudio.billing.BillingResult;
import com.neostudio.billing.Catalog;
import com.neostudio.billing.InitInfo;
import com.neostudio.billing.NeoStoreAvailability;
import com.neostudio.billing.NeoStoreBilling;
import com.neostudio.billing.NeoStoreBillingConfig;
import com.neostudio.billing.ResponseCode;

import java.util.Locale;

public class StoreActivity extends AppCompatActivity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (NeoStoreBilling.INSTANCE.checkAvailability(this)
                != NeoStoreAvailability.AVAILABLE) {
            showMessage("Установите и включите NeoStore");
            return;
        }

        NeoStoreBilling.initializeAsync(
                this,
                new NeoStoreBillingConfig(13L, Locale.getDefault(), false, "1.0.0"),
                new BillingCallback<InitInfo>() {
                    @Override
                    public void onResult(BillingResult<InitInfo> result) {
                        if (result.getResponseCode() != ResponseCode.OK) {
                            showError(result);
                            return;
                        }

                        InitInfo info = result.getData();
                        if (info != null && info.getCapabilities().contains("getCatalog")) {
                            loadCatalog();
                        }
                    }
                }
        );
    }

    private void loadCatalog() {
        NeoStoreBilling.getCatalogAsync(new BillingCallback<Catalog>() {
            @Override
            public void onResult(BillingResult<Catalog> result) {
                if (result.getResponseCode() == ResponseCode.OK) {
                    renderCatalog(result.getData());
                } else {
                    showError(result);
                }
            }
        });
    }

    private void showMessage(String message) { /* показать сообщение приложения */ }
    private void renderCatalog(Catalog catalog) { /* показать каталог */ }
    private void showError(BillingResult<?> result) { /* обработать ошибку */ }
}
```

У `BillingResult<T>` доступны Java getters: `getResponseCode()`, `getDebugMessage()`, `getData()`, `getRawResponseCode()`, `getRawResponseCodeValue()` и `isSuccess()`.

### 5.2. Activity Result в Java

Зарегистрируйте launcher до состояния `STARTED`:

```java
import androidx.activity.result.ActivityResultLauncher;
import com.neostudio.billing.BillingActivityContract;
import com.neostudio.billing.BillingActivityData;
import com.neostudio.billing.BillingActivityRequest;
import com.neostudio.billing.BillingResult;
import com.neostudio.billing.ResponseCode;

private ActivityResultLauncher<BillingActivityRequest> billingLauncher;

@Override
protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    BillingActivityContract contract = NeoStoreBilling.activityResultContract();
    billingLauncher = registerForActivityResult(contract, this::onBillingResult);

    // Вызовите из обработчика действий пользователя:
    // billingLauncher.launch(BillingActivityRequest.purchaseProduct("remove_ads"));
    // billingLauncher.launch(
    //     BillingActivityRequest.purchaseSubscription("premium", "monthly")
    // );
    // billingLauncher.launch(BillingActivityRequest.signIn());
}

private void onBillingResult(BillingResult<BillingActivityData> result) {
    if (result.getResponseCode() == ResponseCode.OK) {
        BillingActivityData data = result.getData();
        if (data != null && data.getProductPurchase() != null) {
            verifyAndDeliverProduct(data.getProductPurchase());
        }
        if (data != null && data.getSubscriptionPurchase() != null) {
            verifySubscription(data.getSubscriptionPurchase());
        }
    } else if (result.getResponseCode() == ResponseCode.ALREADY_OWNED) {
        restorePurchases();
    } else if (result.getResponseCode() != ResponseCode.USER_CANCELED) {
        showError(result);
    }
}
```

Фабрики `BillingActivityRequest.purchaseProduct(...)`, `purchaseSubscription(...)` и `signIn()` доступны из Java как статические методы. В Kotlin результат контракта объявлен как `BillingActivityResult`, это typealias для `BillingResult<BillingActivityData>`. Kotlin typealias не создаёт отдельный Java-класс, поэтому в Java используйте `BillingResult<BillingActivityData>`.

### 5.3. Java-фасады методов сервиса

Доступны следующие callback-фасады, с callback на main thread:

```java
NeoStoreBilling.initializeAsync(context, config, initCallback);
NeoStoreBilling.getCatalogAsync(catalogCallback);
NeoStoreBilling.queryPurchasesAsync(purchasesCallback);
NeoStoreBilling.getSubscriptionAsync(subscriptionId, subscriptionCallback);
NeoStoreBilling.acknowledgePurchaseAsync(transactionId, acknowledgeCallback);
```

`checkAvailability`, `setPurchasesListener` и `release` — методы Kotlin `object`, вызывайте их в Java через `NeoStoreBilling.INSTANCE`. Kotlin `suspend`-методы не предназначены для прямого вызова из Java; используйте перечисленные callback-фасады.

Для acknowledgement тип callback — `BillingCallback<kotlin.Unit>`; успешный результат доступен как `kotlin.Unit.INSTANCE`.

Чтобы установить Java listener:

```java
NeoStoreBilling.INSTANCE.setPurchasesListener(snapshot -> {
    // Callback на main thread; обработайте снимок идемпотентно.
});
```

## 6. Методы и Activity Result

Все операции сервиса, кроме `initialize`, требуют предварительной успешной инициализации и проверки соответствующей capability.

| Метод SDK | Capability | Вход | Успешные данные |
|---|---|---|---|
| `checkAvailability(context)` | — | Android `Context` | `NeoStoreAvailability` |
| `initialize(context, config)` | — | `appId`, locale, debug, версия SDK | `InitInfo` |
| `getCatalog()` | `getCatalog` | Нет | `Catalog` |
| `queryPurchases()` | `queryPurchases` | Нет | `PurchasesSnapshot` |
| `getSubscription(id)` | `getSubscription` | ID вида `sub_[A-Za-z0-9]+` | `SubscriptionPurchase` |
| `acknowledgePurchase(id)` | `acknowledgePurchase` | ID вида `check_[A-Za-z0-9]+` | `Unit` / `void` |
| `purchaseProduct` через launcher | `purchaseProduct` | Идентификатор товара из каталога | `ProductPurchase` в `BillingActivityData` |
| `purchaseSubscription` через launcher | `purchaseSubscription` | Идентификатор подписки и плана из каталога | `SubscriptionPurchase` в `BillingActivityData` |
| `signIn` через launcher | `signIn` | Нет | Успешный `BillingResult` с пустым `BillingActivityData` |
| `setPurchasesListener(listener)` | — | Listener или `null` | Нет |
| `release()` | — | Нет | Нет |

### 6.1. `checkAvailability`

Синхронно проверяет видимость, установку и включённость пакета NeoStore. Возможные значения:

- `AVAILABLE` — приложение найдено и включено;
- `NOT_INSTALLED` — приложение не найдено/недоступно через `PackageManager`;
- `DISABLED` — приложение найдено, но выключено;
- `UPDATE_REQUIRED` — зарезервировано; SDK не вычисляет его по версии NeoStore.

### 6.2. `initialize`

Параметры `NeoStoreBillingConfig`:

| Поле | Тип | Значение |
|---|---|---|
| `appId` | `Long` / `long` | Положительный ID приложения в NeoStore |
| `locale` | `Locale?` / `Locale` | Необязательная локаль BCP-47; например, `ru-RU` |
| `debug` | `Boolean` / `boolean` | Флаг SDK; не включает логирование payload или секретов |
| `sdkVersion` | `String` | SemVer SDK, по умолчанию `1.0.0` |

Успешный `InitInfo` содержит версию протокола, набор capabilities, версию NeoStore и состояние входа пользователя. Используйте только объявленные capabilities. Неизвестные capability не являются обещанием доступности метода в старых SDK.

### 6.3. `getCatalog`

Возвращает продукты и подписки с планами. Цены передаются десятичными строками, не `Float`/`Double`. В модели каталога нет внутренних ID объектов или списка персонализированных offers. Поле `description` и часть данных могут быть `null`.

### 6.4. `queryPurchases`

Восстанавливает снимок покупок NeoStore текущего пользователя:

- `products` содержит подтверждённые non-consumable и consumable, ожидающие выдачи;
- уже подтверждённые consumable не возвращаются как ожидающие выдачи;
- `subscriptions` содержит нормализованные подписки;
- `amount` и `currency` для элементов restore могут быть `null`.

Операция безопасна для повторения; она не создаёт новый платёж и не подтверждает выдачу. Результат и listener не обеспечивают exactly-once.

### 6.5. `getSubscription`

Возвращает подписку по публичному ID формата `sub_[A-Za-z0-9]+`. Для результата restore/lookup `transactionId` и `appliedOffer` обычно `null`. Подписка с отключённым автопродлением остаётся `ACTIVE` до окончания оплаченного срока; смотрите `autoRenewing` и `cancelledAt`.

### 6.6. `acknowledgePurchase`

Передаёт NeoStore ID транзакции формата `check_[A-Za-z0-9]+`. Метод предназначен для consumable и идемпотентен. Вызывайте его только после того, как backend вашего приложения подтвердил фактическую выдачу товара. Ответ SDK имеет `BillingResult<Unit>` в Kotlin и `BillingResult<kotlin.Unit>` в Java.

### 6.7. Покупка и вход через Activity

SDK формирует явный Intent к NeoStore с package `com.neostudio.neostore` и `protocolVersion = 1`. Для покупки передаются только identifier товара либо identifier подписки и плана. Не передавайте цену, сумму, валюту, `appId`, offer ID, package name, user ID или idempotency key.

Покупка выполняется внутри NeoStore. Приложение может показать системную аутентификацию устройства, если настроена безопасная блокировка. Предложения и их выбор обрабатывает NeoStore; SDK и host-приложение не должны самостоятельно подбирать offer.

Результат контракта:

- `RESULT_OK` с корректным `billingResult` и `responseCode = OK` — успешная операция;
- `RESULT_CANCELED` с payload — разберите код, там могут быть полезные данные, включая `ALREADY_OWNED`;
- `RESULT_CANCELED` без payload — `USER_CANCELED`;
- `RESULT_OK` без корректного JSON/payload — `ERROR`;
- конфликт между Activity result code и `responseCode` преобразуется SDK в `ERROR`.

## 7. Модели данных

Все модели находятся в `com.neostudio.billing`.

### `BillingResult<T>`

| Поле / getter Java | Тип | Описание |
|---|---|---|
| `responseCode` / `getResponseCode()` | `ResponseCode` | Нормализованный код результата |
| `debugMessage` / `getDebugMessage()` | `String?` | Диагностика; не показывайте пользователю как текст ошибки |
| `data` / `getData()` | `T?` | Данные результата; может отсутствовать и при ошибке, и в отдельных UI-операциях |
| `rawResponseCode` / `getRawResponseCode()` | `String?` | Исходное имя кода для диагностики |
| `rawResponseCodeValue` / `getRawResponseCodeValue()` | `Int?` | Исходное числовое значение |
| `isSuccess()` | `Boolean` | `true` только для `OK`; `ALREADY_OWNED` не считается `OK` |

Не делайте вывод об успешной выдаче товара только по `RESULT_OK`: проверяйте `responseCode`, данные и backend-право.

### `NeoStoreAvailability`

`AVAILABLE`, `NOT_INSTALLED`, `DISABLED`, `UPDATE_REQUIRED`.

### `InitInfo`

| Поле | Тип | Описание |
|---|---|---|
| `protocolVersion` | `Int` | Версия IPC, ожидается `1` |
| `capabilities` | `Set<String>` | Доступные методы/UI-функции NeoStore |
| `neoStoreVersion` | `String?` | Версия установленного приложения |
| `userSignedIn` | `Boolean` | Состояние входа в момент ответа |

Наличие/отсутствие входа может измениться после инициализации. Методы без UI возвращают `NOT_SIGNED_IN`, если нужен вход.

### `Catalog`, `ProductInfo`, `SubscriptionInfo`, `PlanInfo`

- `Catalog`: `appId: Long?`, `products: List<ProductInfo>`, `subscriptions: List<SubscriptionInfo>`.
- `ProductInfo`: `identifier`, `name`, `description: String?`, `type: String`, `price: String?`, `currency: String?`, `formattedPrice: String?`.
- `SubscriptionInfo`: `identifier`, `name`, `description: String?`, `plans: List<PlanInfo>`.
- `PlanInfo`: `identifier`, `name`, `description: String?`, `billingType: String?`, `periodValue: Int?`, `periodUnit: String?`, `price: String?`, `currency: String?`, `formattedPrice: String?`.

`type`, `billingType`, `periodUnit` — строки, а не закрытые enum: новые значения протокола не должны ломать клиент. `formattedPrice` предназначен для отображения; `price` остаётся точной десятичной строкой.

### `ProductPurchase`

| Поле | Тип | Описание |
|---|---|---|
| `transactionId` | `String` | ID вида `check_...` |
| `productIdentifier` | `String` | Публичный identifier товара |
| `productName` | `String?` | Название, если доступно |
| `productType` | `String` | Например, `CONSUMABLE` или `NON_CONSUMABLE`; возможны будущие значения |
| `status` | `PurchaseStatus` | `PAID` или `UNKNOWN` |
| `rawStatus` | `String?` | Исходная строка статуса |
| `deliveryStatus` | `String?` | Статус выдачи, если присутствует |
| `acknowledgedAt` | `String?` | Время подтверждения, если присутствует |
| `amount` | `String?` | Десятичная сумма; в restore может быть `null` |
| `currency` | `String?` | Код валюты; в restore может быть `null` |
| `purchasedAt` | `String?` | ISO-8601; lookup для обогащения покупки может быть недоступен |
| `acknowledged` | `Boolean` | Информационное поле; не заменяет проверку выдачи на вашем backend |

Известный статус `paid` преобразуется в `PurchaseStatus.PAID`; неизвестные значения дают `UNKNOWN`, а исходная строка сохраняется в `rawStatus`.

### `SubscriptionPurchase` и `AppliedOffer`

`SubscriptionPurchase` содержит:

- `transactionId: String?`;
- `subscriptionId: String`;
- `subscriptionIdentifier`, `subscriptionName`, `planIdentifier`, `planName`: `String?`;
- `status: SubscriptionStatus`, `rawStatus: String?`;
- `autoRenewing: Boolean`, `billingType: String?`;
- `amount`, `currency`: `String?`;
- `startedAt`, `expiresAt`, `nextBillingAt`, `cancelledAt`: `String?` (ISO-8601);
- `currentPhaseOrder: Int?`;
- `appliedOffer: AppliedOffer?`.

`SubscriptionStatus`: `ACTIVE`, `PAST_DUE`, `EXPIRED`, `REVOKED`, `UNKNOWN`. Отмена автопродления сама по себе не означает истечение доступа: активный оплаченный период остаётся `ACTIVE`, смотрите `autoRenewing` и `cancelledAt`. Неизвестный статус сохраняется в `rawStatus`.

`AppliedOffer` содержит `id`, `identifier`, `name` — все nullable. На restore и в `getSubscription` обычно `null`; после покупки может содержать выбранное NeoStore предложение.

### `PurchasesSnapshot`

- `products: List<ProductPurchase>`;
- `subscriptions: List<SubscriptionPurchase>`.

### `BillingActivityData`

Для результата интерактивной операции содержит `productPurchase: ProductPurchase?` либо `subscriptionPurchase: SubscriptionPurchase?`. При входе успешный результат может иметь пустые поля покупки.

## 8. Коды ответов и обработка ошибок

Код ответа доступен как `BillingResult.responseCode`. Числовые значения стабильны для протокола v1.

| Код | Число | Рекомендуемое поведение |
|---|---:|---|
| `OK` | 0 | Разобрать данные; право на товар подтвердить на backend host-приложения |
| `USER_CANCELED` | 1 | Штатная отмена; обычно не показывать как ошибку |
| `NOT_SIGNED_IN` | 2 | Предложить пользователю войти через `signIn`, затем повторить действие явно |
| `ITEM_UNAVAILABLE` | 3 | Обновить каталог и отключить недоступный объект |
| `ALREADY_OWNED` | 4 | Не создавать покупку повторно; восстановить/проверить существующее право |
| `INSUFFICIENT_FUNDS` | 5 | Показать сообщение о недостатке средств; не повторять автоматически |
| `OFFER_UNAVAILABLE` | 6 | Условия изменились; продолжать только после нового подтверждения пользователем |
| `NETWORK_ERROR` | 7 | При неопределённом исходе покупки сначала вызвать `queryPurchases()` |
| `SERVICE_UNAVAILABLE` | 8 | Сообщить о временной недоступности; повторить позже |
| `RATE_LIMITED` | 9 | Выждать; не делать немедленный повтор |
| `DEVELOPER_ERROR` | 10 | Исправить интеграцию, параметры, `appId`, инициализацию или capability |
| `UNSUPPORTED_PROTOCOL` | 11 | Обновить SDK или NeoStore до совместимых версий |
| `ERROR` | 12 | Обработать как общую ошибку; диагностику оставить в логах разработки без секретов |

Неизвестный ответ протокола SDK сводит к `ERROR`; исходные `rawResponseCode` и `rawResponseCodeValue` сохраняются для диагностики. Неизвестные поля JSON игнорируются, nullable-поля остаются nullable, неизвестные товарные/подписочные статусы не должны приводить к падению.

Методы без UI возвращают `BillingResult<T>`. Java async-фасады возвращают тот же результат через callback. Интерактивные методы возвращают его через Activity Result callback. Не полагайтесь только на `debugMessage` и не выводите его напрямую пользователю.

## 9. Безопасность, восстановление и выдача товара

1. После покупки отправьте ID транзакции на backend своего приложения и проверьте его по доверенному серверному каналу.
2. Выдавайте постоянный доступ только после проверки, а не на основании клиентского callback.
3. Для consumable сначала зафиксируйте идемпотентную выдачу на своём backend, и только потом вызовите `acknowledgePurchase(transactionId)`.
4. Если callback покупки потерян, приложение завершилось или вернулась сетевая ошибка с неопределённым исходом, запросите `queryPurchases()` до следующей покупки.
5. Обрабатывайте выдачу идемпотентно: снимок restore и listener могут повторно сообщить о покупке.
6. Запрашивайте снимок при запуске/возврате приложения; у SDK нет push-канала подписочных событий.

SDK не передаёт host-приложению Sanctum-токен и не принимает от него цену, баланс, offer ID, сумму, валюту, user ID или idempotency key. Не логируйте содержимое IPC, токены, персональные данные и подробные диагностические сообщения в production.

## 10. Ограничения и диагностика

- Минимальная поддерживаемая версия NeoStore не опубликована; `UPDATE_REQUIRED` не вычисляется по версии.
- Протокол принимает точную версию `1`; диапазона согласования версий нет.
- Список `capabilities` от `initialize` — источник информации о доступных методах.
- Нет UI управления подписками, API пополнения баланса и push-уведомлений SDK.
- Политика доступа для `PAST_DUE` зависит от backend host-приложения.
- Сетевые запросы к backend выполняет NeoStore. SDK ограничивает подключение к сервису 5 секундами и ожидание IPC-ответа 20 секундами; повторов оплаты SDK не выполняет.
- `release()` безопасен при повторном вызове: снимает lifecycle listener, очищает SDK-сессию и listener покупок, отменяет ожидающие IPC-вызовы и закрывает привязку. После `release()` разрешена новая инициализация.
- Для Java используйте callback-фасады, а не вызывайте `suspend`-методы напрямую.

При диагностике сначала проверьте: доступность NeoStore, manifest package visibility, корректность `appId`, успешную инициализацию, наличие нужной capability, формат идентификаторов и связь устройства с NeoStore. Ошибки транспорта не следует превращать в пустой успешный каталог или список покупок.
