package com.stripe.android.paymentsheet.viewmodels

import android.app.Application
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asFlow
import androidx.lifecycle.distinctUntilChanged
import androidx.lifecycle.viewModelScope
import com.stripe.android.Logger
import com.stripe.android.model.PaymentIntent
import com.stripe.android.model.PaymentMethod
import com.stripe.android.model.SetupIntent
import com.stripe.android.model.StripeIntent
import com.stripe.android.payments.core.injection.InjectorKey
import com.stripe.android.paymentsheet.BasePaymentMethodsListFragment
import com.stripe.android.paymentsheet.PaymentOptionsActivity
import com.stripe.android.paymentsheet.PaymentSheet
import com.stripe.android.paymentsheet.PaymentSheetActivity
import com.stripe.android.paymentsheet.PrefsRepository
import com.stripe.android.paymentsheet.analytics.EventReporter
import com.stripe.android.paymentsheet.model.Amount
import com.stripe.android.paymentsheet.model.FragmentConfig
import com.stripe.android.paymentsheet.model.PaymentSelection
import com.stripe.android.paymentsheet.model.SavedSelection
import com.stripe.android.paymentsheet.model.SupportedPaymentMethod
import com.stripe.android.paymentsheet.paymentdatacollection.CardDataCollectionFragment
import com.stripe.android.paymentsheet.repositories.CustomerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.TestOnly
import kotlin.coroutines.CoroutineContext
import com.stripe.android.paymentsheet.BaseAddPaymentMethodFragment

/**
 * Base `ViewModel` for activities that use `BottomSheet`.
 */
internal abstract class BaseSheetViewModel<TransitionTargetType>(
    application: Application,
    internal val config: PaymentSheet.Configuration?,
    internal val eventReporter: EventReporter,
    protected val customerRepository: CustomerRepository,
    protected val prefsRepository: PrefsRepository,
    protected val workContext: CoroutineContext = Dispatchers.IO,
    protected val logger: Logger,
    @InjectorKey private val injectorKey: String
) : AndroidViewModel(application) {
    internal val customerConfig = config?.customer
    internal val merchantName = config?.merchantDisplayName
        ?: application.applicationInfo.loadLabel(application.packageManager).toString()

    // a fatal error
    protected val _fatal = MutableLiveData<Throwable>()

    @VisibleForTesting
    internal val _isGooglePayReady = MutableLiveData<Boolean>()
    internal val isGooglePayReady: LiveData<Boolean> = _isGooglePayReady.distinctUntilChanged()

    private val _stripeIntent = MutableLiveData<StripeIntent?>()
    internal val stripeIntent: LiveData<StripeIntent?> = _stripeIntent

    internal var supportedPaymentMethods = emptyList<SupportedPaymentMethod>()

    protected val _paymentMethods = MutableLiveData<List<PaymentMethod>>()
    internal val paymentMethods: LiveData<List<PaymentMethod>> = _paymentMethods

    @VisibleForTesting
    internal val _amount = MutableLiveData<Amount>()
    internal val amount: LiveData<Amount> = _amount

    /**
     * Request to retrieve the value from the repository happens when initialize any fragment
     * and any fragment will re-update when the result comes back.
     * Represents what the user last selects (add or buy) on the
     * [PaymentOptionsActivity]/[PaymentSheetActivity], and saved/restored from the preferences.
     */
    private val _savedSelection = MutableLiveData<SavedSelection>()
    private val savedSelection: LiveData<SavedSelection> = _savedSelection

    private val _transition = MutableLiveData<Event<TransitionTargetType?>>(Event(null))
    internal val transition: LiveData<Event<TransitionTargetType?>> = _transition

    /**
     * On [CardDataCollectionFragment] this is set every time the details in the add
     * card fragment is determined to be valid (not necessarily selected)
     * On [BasePaymentMethodsListFragment] this is set when a user selects one of the options
     */
    private val _selection = MutableLiveData<PaymentSelection?>()
    internal val selection: LiveData<PaymentSelection?> = _selection

    private val editing = MutableLiveData(false)

    @VisibleForTesting
    internal val _processing = MutableLiveData(true)
    val processing: LiveData<Boolean> = _processing

    /**
     * This should be initialized from the starter args, and then from that
     * point forward it will be the last valid card seen or entered in the add card view.
     * In contrast to selection, this field will not be updated by the list fragment. On the
     * Payment Sheet it is used to save a new card that is added for when you go back to the list
     * and reopen the card view. It is used on the Payment Options sheet similar to what is
     * described above, and when you have an unsaved card.
     */
    abstract var newCard: PaymentSelection.New.Card?

    abstract fun onFatal(throwable: Throwable)

    val ctaEnabled = MediatorLiveData<Boolean>().apply {
        listOf(
            processing,
            selection,
            editing
        ).forEach { source ->
            addSource(source) {
                value = processing.value != true &&
                    selection.value != null &&
                    editing.value != true
            }
        }
    }.distinctUntilChanged()

    init {
        viewModelScope.launch {
            val savedSelection = withContext(workContext) {
                prefsRepository.getSavedSelection(isGooglePayReady.asFlow().first())
            }
            _savedSelection.value = savedSelection
        }
    }

    val fragmentConfig = MediatorLiveData<FragmentConfig?>().apply {
        listOf(
            savedSelection,
            stripeIntent,
            paymentMethods,
            isGooglePayReady
        ).forEach { source ->
            addSource(source) {
                value = createFragmentConfig()
            }
        }
    }.distinctUntilChanged()

    private fun createFragmentConfig(): FragmentConfig? {
        val stripeIntentValue = stripeIntent.value
        val paymentMethodsValue = paymentMethods.value
        val isGooglePayReadyValue = isGooglePayReady.value
        val savedSelectionValue = savedSelection.value

        return if (
            stripeIntentValue != null &&
            paymentMethodsValue != null &&
            isGooglePayReadyValue != null &&
            savedSelectionValue != null
        ) {
            FragmentConfig(
                stripeIntent = stripeIntentValue,
                paymentMethods = paymentMethodsValue,
                isGooglePayReady = isGooglePayReadyValue,
                savedSelection = savedSelectionValue
            )
        } else {
            null
        }
    }

    open fun transitionTo(target: TransitionTargetType) {
        _transition.postValue(Event(target))
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    fun setStripeIntent(stripeIntent: StripeIntent?) {
        _stripeIntent.value = stripeIntent

        /**
         * The settings of values in this function is so that
         * they will be ready in the onViewCreated method of
         * the [BaseAddPaymentMethodFragment]
         */

        supportedPaymentMethods = getSupportedPaymentMethods(stripeIntent)

        if (stripeIntent != null && supportedPaymentMethods.isEmpty()) {
            onFatal(
                IllegalArgumentException(
                    "None of the requested payment methods" +
                        " (${stripeIntent.paymentMethodTypes})" +
                        " match the supported payment types" +
                        " (${SupportedPaymentMethod.values().toList()})"
                )
            )
        }

        if (stripeIntent is PaymentIntent) {
            runCatching {
                _amount.value =
                    Amount(
                        requireNotNull(stripeIntent.amount),
                        requireNotNull(stripeIntent.currency)
                    )
            }.onFailure {
                onFatal(
                    IllegalStateException("PaymentIntent must contain amount and currency.")
                )
            }
        }
    }

    @VisibleForTesting
    internal fun getSupportedPaymentMethods(
        stripeIntentParameter: StripeIntent?
    ): List<SupportedPaymentMethod> {
        stripeIntentParameter?.let { stripeIntent ->
            return stripeIntent.paymentMethodTypes.asSequence().mapNotNull {
                SupportedPaymentMethod.fromCode(it)
            }.filter {
                config?.allowsDelayedPaymentMethods == true ||
                    PaymentMethod.Type.fromCode(it.code)?.hasDelayedSettlement() == false
            }.filterNot {
                // AfterpayClearpay requires a shipping address, filter it out if not provided
                val excludeAfterPay = it == SupportedPaymentMethod.AfterpayClearpay &&
                    (stripeIntent as? PaymentIntent)?.shipping == null
                if (excludeAfterPay) {
                    logger.debug(
                        "AfterPay will not be shown. It requires that Shipping is " +
                            "included in the Payment or Setup Intent"
                    )
                }
                excludeAfterPay
            }.filterNot { supportedPaymentMethod ->
                val excludeRequiresMandate =
                    (stripeIntent is SetupIntent) && supportedPaymentMethod.requiresMandate
                if (excludeRequiresMandate) {
                    logger.debug(
                        "${supportedPaymentMethod.name} will not be shown. It " +
                            "requires a mandate which is incompatible with SetupIntents"
                    )
                }
                excludeRequiresMandate
            }.filterNot { supportedPaymentMethod ->
                val excludeRequiresMandate = (stripeIntent is PaymentIntent) &&
                    supportedPaymentMethod.requiresMandate &&
                    stripeIntent.setupFutureUsage == StripeIntent.Usage.OffSession
                if (excludeRequiresMandate) {
                    logger.debug(
                        "${supportedPaymentMethod.name} will not be shown.  It " +
                            "requires a mandate which is incompatible with off_session " +
                            "PaymentIntents"
                    )
                }
                excludeRequiresMandate
            }.filter { it == SupportedPaymentMethod.Card }
                .toList()
        }

        return emptyList()
    }

    fun updateSelection(selection: PaymentSelection?) {
        _selection.value = selection
    }

    fun setEditing(isEditing: Boolean) {
        editing.value = isEditing
    }

    fun removePaymentMethod(paymentMethod: PaymentMethod) = runBlocking {
        launch {
            if (customerConfig != null && paymentMethod.id != null) {
                customerRepository.detachPaymentMethod(
                    customerConfig,
                    requireNotNull(paymentMethod.id)
                )
            }
        }
    }

    abstract fun onUserCancel()

    data class UserErrorMessage(val message: String)

    /**
     * Used as a wrapper for data that is exposed via a LiveData that represents an event.
     * From https://medium.com/androiddevelopers/livedata-with-snackbar-navigation-and-other-events-the-singleliveevent-case-ac2622673150
     * TODO(brnunes): Migrate to Flows once stable: https://medium.com/androiddevelopers/a-safer-way-to-collect-flows-from-android-uis-23080b1f8bda
     */
    class Event<out T>(private val content: T) {

        var hasBeenHandled = false
            private set // Allow external read but not write

        /**
         * Returns the content and prevents its use again.
         */
        fun getContentIfNotHandled(): T? {
            return if (hasBeenHandled) {
                null
            } else {
                hasBeenHandled = true
                content
            }
        }

        /**
         * Returns the content, even if it's already been handled.
         */
        @TestOnly
        fun peekContent(): T = content
    }
}
