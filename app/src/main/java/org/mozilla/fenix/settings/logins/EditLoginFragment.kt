/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.settings.logins

import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.Button
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import kotlinx.android.synthetic.main.fragment_edit_login.*
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import mozilla.components.lib.state.ext.consumeFrom
import mozilla.components.service.sync.logins.InvalidRecordException
import mozilla.components.service.sync.logins.LoginsStorageException
import mozilla.components.service.sync.logins.NoSuchRecordException
import mozilla.components.support.ktx.android.view.hideKeyboard
import org.mozilla.fenix.R
import org.mozilla.fenix.components.StoreProvider
import org.mozilla.fenix.components.metrics.Event
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.ext.redirectToReAuth
import org.mozilla.fenix.ext.requireComponents
import org.mozilla.fenix.ext.settings
import org.mozilla.fenix.theme.ThemeManager

/**
 * Interactor for the edit login screen.
 */
class EditLoginInteractor(private val editLoginController: EditSavedLoginsController) {
    fun findDuplicates(item: SavedLogin) {
        // What scope should be used here?
        GlobalScope.launch(IO) { editLoginController.findPotentialDuplicates(item) }
    }
}

/**
 * Displays the editable saved login information for a single website.
 */
@ExperimentalCoroutinesApi
@Suppress("TooManyFunctions", "NestedBlockDepth", "ForbiddenComment")
class EditLoginFragment : Fragment(R.layout.fragment_edit_login) {

    fun String.toEditable(): Editable = Editable.Factory.getInstance().newEditable(this)

    private val args by navArgs<EditLoginFragmentArgs>()
    private lateinit var loginsFragmentStore: LoginsFragmentStore
    private lateinit var editLoginsInteractor: EditLoginInteractor
    private lateinit var datastore: LoginsDataStore

    private lateinit var oldLogin: SavedLogin
    private var listOfPossibleDupes: List<SavedLogin>? = null
    private var usernameChanged: Boolean = false
    private var passwordChanged: Boolean = false
    private var saveEnabled: Boolean = false
    private var validPassword = true
    private var validUsername = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        oldLogin = args.savedLoginItem
        loginsFragmentStore = StoreProvider.get(this) {
            LoginsFragmentStore(
                LoginsListState(
                    isLoading = true,
                    loginList = listOf(),
                    filteredItems = listOf(),
                    searchedForText = null,
                    sortingStrategy = requireContext().settings().savedLoginsSortingStrategy,
                    highlightedItem = requireContext().settings().savedLoginsMenuHighlightedItem,
                    duplicateLogins = null
                )
            )
        }
        val controller = EditSavedLoginsController(
            context = requireContext(),
            loginsFragmentStore = loginsFragmentStore
        )
        editLoginsInteractor = EditLoginInteractor(controller)
        editLoginsInteractor.findDuplicates(args.savedLoginItem)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        datastore = LoginsDataStore(this, loginsFragmentStore)

        // ensure hostname isn't editable
        hostnameText.text = args.savedLoginItem.origin.toEditable()
        hostnameText.isClickable = false
        hostnameText.isFocusable = false

        usernameText.text = args.savedLoginItem.username.toEditable()
        passwordText.text = args.savedLoginItem.password.toEditable()

        // TODO: extend PasswordTransformationMethod() to change bullets to asterisks
        passwordText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        usernameChanged = false
        passwordChanged = false

        val saveButton = activity?.findViewById<Button>(R.id.save_login_button)
        saveButton?.compoundDrawableTintList = ContextCompat.getColorStateList(
                requireContext(),
                R.color.save_enabled_ic_color
            )
        saveEnabled = false // don't enable saving until something has been changed

        setUpClickListeners()
        setUpTextListeners()
        consumeFrom(loginsFragmentStore) {
            listOfPossibleDupes = loginsFragmentStore.state.duplicateLogins
        }

    }

    private fun setUpClickListeners() {
        clearUsernameTextButton.setOnClickListener {
            usernameText.text?.clear()
            usernameText.isCursorVisible = true
            usernameText.hasFocus()
            inputLayoutUsername.hasFocus()
        }
        clearPasswordTextButton.setOnClickListener {
            passwordText.text?.clear()
            passwordText.isCursorVisible = true
            passwordText.hasFocus()
            inputLayoutPassword.hasFocus()
        }
        revealPasswordButton.setOnClickListener {
            togglePasswordReveal()
        }
        passwordText.setOnClickListener {
            togglePasswordReveal()
        }
    }

    private fun setUpTextListeners() {
        val frag = view?.findViewById<View>(R.id.editLoginFragment)
        frag?.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                view?.hideKeyboard()
            }
        }

        usernameText.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (usernameText.text?.toString().equals(oldLogin.username)) {
                    usernameChanged = false
                    validUsername = true
                    inputLayoutUsername.error = null
                } else if (!(usernameText.text?.toString().equals(oldLogin.username))) {
                    usernameChanged = true
                    setDupeError()
                }
                setSaveButtonState()
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                // NOOP
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // NOOP
            }
        })

        passwordText.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (passwordText.text?.toString() == "") {
                    passwordChanged = true
                    setPasswordError()
                } else if (passwordText.text?.toString().equals(oldLogin.password)) {
                    passwordChanged = false
                    inputLayoutPassword.error = null
                    validPassword = true
                } else {
                    passwordChanged = true
                    inputLayoutPassword.error = null
                    validPassword = true
                }
                setSaveButtonState()
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                // NOOP
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // NOOP
            }
        })
    }

    private fun isDupe(username: String): Boolean =
        loginsFragmentStore.state.duplicateLogins
            ?.filter { it.username == username }?.any() ?: false

    private fun setDupeError() {
        if (isDupe(usernameText.text.toString())) {
            inputLayoutUsername?.let {
                it.setErrorIconDrawable(R.drawable.mozac_ic_warning)
                it.error = context?.getString(R.string.saved_login_duplicate)
                validUsername = false
            }
        } else {
            inputLayoutUsername.error = null
            passwordChanged = true
            validUsername = true
        }
    }

    private fun setPasswordError() {
        inputLayoutPassword?.let {
            it.setErrorIconDrawable(R.drawable.mozac_ic_warning)
            it.error = context?.getString(R.string.saved_login_password_required)
            validPassword = false
        }
    }

    // possibly use BrowserMenuItemToolbar.TwoStateButton()?
    private fun setSaveButtonState() {
        val saveButton = activity?.findViewById<Button>(R.id.save_login_button)

        if (validUsername && validPassword && (usernameChanged || passwordChanged)) {
            saveButton?.setBackgroundColor(
                ContextCompat.getColor(
                    requireContext(),
                    ThemeManager.resolveAttribute(R.attr.enabled, requireContext())
                )
            )
            saveEnabled = true
        } else {
            saveButton?.setBackgroundColor(
                ContextCompat.getColor(
                    requireContext(),
                    ThemeManager.resolveAttribute(R.attr.disabled, requireContext())
                )
            )
            saveEnabled = false
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.login_save, menu)
    }

    override fun onPause() {
        redirectToReAuth(
            listOf(R.id.loginDetailFragment),
            findNavController().currentDestination?.id
        )
        super.onPause()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.save_login_button -> {
            view?.hideKeyboard()
            if (saveEnabled) {
                try {
//                    attemptSaveAndExit()
                    datastore.save(
                        args.savedLoginItem.guid,
                        usernameText.text.toString(),
                        passwordText.text.toString()
                    )
                    requireComponents.analytics.metrics.track(Event.EditLoginSave)
                } catch (loginException: LoginsStorageException) {
                    when (loginException) {
                        is NoSuchRecordException,
                        is InvalidRecordException -> {
                            Log.e("Edit login",
                                "Failed to save edited login.", loginException)
                        }
                        else -> Log.e("Edit login",
                            "Failed to save edited login.", loginException)
                    }
                }
            }
            true
        }
        else -> false
    }

//    // This includes Delete, Update/Edit, Create
//    private fun attemptSaveAndExit() {
//        var saveLoginJob: Deferred<Unit>? = null
//        viewLifecycleOwner.lifecycleScope.launch(IO) {
//            saveLoginJob = async {
//                // must retrieve from storage to get the httpsRealm and formActionOrigin
//                val oldLogin =
//                    requireContext().components.core.passwordsStorage.get(args.savedLoginItem.guid)
//
//                // Update requires a Login type, which needs at least one of
//                // httpRealm or formActionOrigin
//                val loginToSave = Login(
//                    guid = oldLogin?.guid,
//                    origin = oldLogin?.origin!!,
//                    username = usernameText.text.toString(), // new value
//                    password = passwordText.text.toString(), // new value
//                    httpRealm = oldLogin.httpRealm,
//                    formActionOrigin = oldLogin.formActionOrigin
//                )
//
//                save(loginToSave)
//                syncAndUpdateList(loginToSave)
//            }
//            saveLoginJob?.await()
//            withContext(Main) {
//                val directions =
//                    EditLoginFragmentDirections
//                        .actionEditLoginFragmentToLoginDetailFragment(args.savedLoginItem.guid)
//                findNavController().navigate(directions)
//            }
//        }
//        saveLoginJob?.invokeOnCompletion {
//            if (it is CancellationException) {
//                saveLoginJob?.cancel()
//            }
//        }
//    }

    // TODO: create helper class for toggling passwords. Used in login info and edit fragments.
    private fun togglePasswordReveal() {
        val currText = passwordText.text
        if (passwordText.inputType == InputType.TYPE_TEXT_VARIATION_PASSWORD
            or InputType.TYPE_CLASS_TEXT
        ) {
            context?.components?.analytics?.metrics?.track(Event.ViewLoginPassword)
            passwordText.inputType = InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            revealPasswordButton.setImageDrawable(
                resources.getDrawable(R.drawable.mozac_ic_password_hide, null)
            )
            revealPasswordButton.contentDescription =
                resources.getString(R.string.saved_login_hide_password)
        } else {
            passwordText.inputType =
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            revealPasswordButton.setImageDrawable(
                resources.getDrawable(R.drawable.mozac_ic_password_reveal, null)
            )
            revealPasswordButton.contentDescription =
                context?.getString(R.string.saved_login_reveal_password)
        }
        // For the new type to take effect you need to reset the text to it's current edited version
        passwordText?.text = currText
    }
}
