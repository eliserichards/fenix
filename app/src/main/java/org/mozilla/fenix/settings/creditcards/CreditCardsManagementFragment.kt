/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.settings.creditcards

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import kotlinx.android.synthetic.main.fragment_saved_cards.view.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import mozilla.components.lib.state.ext.consumeFrom
import org.mozilla.fenix.HomeActivity
import org.mozilla.fenix.R
import org.mozilla.fenix.components.StoreProvider
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.ext.redirectToCreditCardReAuth
import org.mozilla.fenix.ext.showToolbar
import org.mozilla.fenix.settings.creditcards.controller.DefaultCreditCardsManagementController
import org.mozilla.fenix.settings.creditcards.interactor.CreditCardsManagementInteractor
import org.mozilla.fenix.settings.creditcards.interactor.DefaultCreditCardsManagementInteractor
import org.mozilla.fenix.settings.creditcards.view.CreditCardsManagementView

/**
 * Displays a list of saved credit cards.
 */
class CreditCardsManagementFragment : Fragment() {

    private lateinit var creditCardsStore: CreditCardsFragmentStore
    private lateinit var interactor: CreditCardsManagementInteractor
    private lateinit var creditCardsView: CreditCardsManagementView
    private lateinit var toolbarChildContainer: FrameLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_saved_cards, container, false)

        creditCardsStore = StoreProvider.get(this) {
            CreditCardsFragmentStore(CreditCardsListState(creditCards = emptyList()))
        }

        interactor = DefaultCreditCardsManagementInteractor(
            controller = DefaultCreditCardsManagementController(
                navController = findNavController()
            )
        )

        creditCardsView = CreditCardsManagementView(view.saved_cards_layout, interactor)
        toolbarChildContainer = initChildContainerFromToolbar()
        loadCreditCards()

        return view
    }

    @ExperimentalCoroutinesApi
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        consumeFrom(creditCardsStore) { state ->
            creditCardsView.update(state)
        }
    }

    override fun onResume() {
        super.onResume()
        activity?.window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        showToolbar(getString(R.string.credit_cards_saved_cards))
    }

    /**
     * If we pause this fragment, we want to pop users back to the settings page to reauthenticate.
     */
    override fun onPause() {
        toolbarChildContainer.removeAllViews()
        toolbarChildContainer.visibility = View.GONE

        (activity as HomeActivity).getSupportActionBarAndInflateIfNecessary()
            .setDisplayShowTitleEnabled(true)
        setHasOptionsMenu(false)

        // don't redirect if the user is navigating to the editor fragment
        redirectToCreditCardReAuth(
            listOf(R.id.creditCardEditorFragment),
            findNavController().currentDestination?.id
        )

        super.onPause()
    }

    /**
     * Fetches all the credit cards from the autofill storage and updates the
     * [CreditCardsFragmentStore] with the list of credit cards.
     */
    private fun loadCreditCards() {
        lifecycleScope.launch(Dispatchers.IO) {
            val creditCards = requireContext().components.core.autofillStorage.getAllCreditCards()

            lifecycleScope.launch(Dispatchers.Main) {
                creditCardsStore.dispatch(CreditCardsAction.UpdateCreditCards(creditCards))
            }
        }
    }

    /**
     * Initialize toolbar container and set visibility for authentication.
     */
    private fun initChildContainerFromToolbar(): FrameLayout {
        val activity = activity as? AppCompatActivity
        val toolbar = (activity as HomeActivity).findViewById<Toolbar>(R.id.navigationToolbar)

        return (toolbar.findViewById(R.id.toolbar_child_container) as FrameLayout).apply {
            visibility = View.VISIBLE
        }
    }
}
