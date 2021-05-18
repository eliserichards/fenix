/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.settings.logins.fragment

import android.app.KeyguardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.provider.Settings.ACTION_SECURITY_SETTINGS
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.SwitchPreference
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mozilla.components.service.fxa.SyncEngine
import org.mozilla.fenix.R
import org.mozilla.fenix.components.metrics.Event
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.ext.navigateBlockingForAsyncNavGraph
import org.mozilla.fenix.ext.requireComponents
import org.mozilla.fenix.ext.runIfFragmentIsAttached
import org.mozilla.fenix.ext.secure
import org.mozilla.fenix.ext.settings
import org.mozilla.fenix.ext.showToolbar
import org.mozilla.fenix.settings.SharedPreferenceUpdater
import org.mozilla.fenix.settings.SyncPreferenceView
import org.mozilla.fenix.settings.biometric.BiometricPromptPreferenceFragment
import org.mozilla.fenix.settings.requirePreference

class SavedLoginsAuthFragment : BiometricPromptPreferenceFragment() {

    /**
     * Used for toggling preferences on/off during authentication
     */
    private val loginsPreferences = listOf(
        R.string.pref_key_sync_logins,
        R.string.pref_key_save_logins_settings,
        R.string.pref_key_saved_logins
    )

    override fun unlockMessage() = getString(R.string.logins_biometric_prompt_message)

    override fun navigateOnSuccess() = navigateToSavedLogins()

    /**
     * Navigates to the [SavedLoginsFragment] with a slight delay.
     */
    private fun navigateToSavedLogins() {
        runIfFragmentIsAttached {
            viewLifecycleOwner.lifecycleScope.launch(Main) {
                // Workaround for likely biometric library bug
                // https://github.com/mozilla-mobile/fenix/issues/8438
                delay(SHORT_DELAY_MS)
                navigateToSavedLoginsFragment()
            }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.logins_preferences, rootKey)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setBiometricPrompt(view, loginsPreferences)
    }

    override fun onResume() {
        super.onResume()
        showToolbar(getString(R.string.preferences_passwords_logins_and_passwords))

        requirePreference<Preference>(R.string.pref_key_save_logins_settings).apply {
            summary = getString(
                if (context.settings().shouldPromptToSaveLogins)
                    R.string.preferences_passwords_save_logins_ask_to_save else
                    R.string.preferences_passwords_save_logins_never_save
            )
            setOnPreferenceClickListener {
                navigateToSaveLoginSettingFragment()
                true
            }
        }

        requirePreference<Preference>(R.string.pref_key_login_exceptions).apply {
            setOnPreferenceClickListener {
                navigateToLoginExceptionFragment()
                true
            }
        }

        requirePreference<SwitchPreference>(R.string.pref_key_autofill_logins).apply {
            isChecked = context.settings().shouldAutofillLogins
            onPreferenceChangeListener = object : SharedPreferenceUpdater() {
                override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
                    context.components.core.engine.settings.loginAutofillEnabled =
                        newValue as Boolean
                    return super.onPreferenceChange(preference, newValue)
                }
            }
        }

        requirePreference<Preference>(R.string.pref_key_saved_logins).setOnPreferenceClickListener {
            verifyCredentialsOrShowSetupWarning(it.context, loginsPreferences)
            true
        }

        SyncPreferenceView(
            syncPreference = requirePreference(R.string.pref_key_sync_logins),
            lifecycleOwner = viewLifecycleOwner,
            accountManager = requireComponents.backgroundServices.accountManager,
            syncEngine = SyncEngine.Passwords,
            loggedOffTitle = requireContext()
                .getString(R.string.preferences_passwords_sync_logins_across_devices),
            loggedInTitle = requireContext()
                .getString(R.string.preferences_passwords_sync_logins),
            onSignInToSyncClicked = {
                val directions =
                    SavedLoginsAuthFragmentDirections.actionSavedLoginsAuthFragmentToTurnOnSyncFragment()
                findNavController().navigateBlockingForAsyncNavGraph(directions)
            },
            onReconnectClicked = {
                val directions =
                    SavedLoginsAuthFragmentDirections.actionGlobalAccountProblemFragment()
                findNavController().navigateBlockingForAsyncNavGraph(directions)
            }
        )

        togglePrefsEnabledWhileAuthenticating(loginsPreferences, true)
    }

    /**
     * Show a warning to set up a pin/password when the device is not secured. This is only used
     * when BiometricPrompt is unavailable on the device.
     */
    override fun showPinDialogWarning(context: Context) {
        AlertDialog.Builder(context).apply {
            setTitle(getString(R.string.logins_warning_dialog_title))
            setMessage(
                getString(R.string.logins_warning_dialog_message)
            )

            setNegativeButton(getString(R.string.logins_warning_dialog_later)) { _: DialogInterface, _ ->
                navigateToSavedLoginsFragment()
            }

            setPositiveButton(getString(R.string.logins_warning_dialog_set_up_now)) { it: DialogInterface, _ ->
                it.dismiss()
                val intent = Intent(ACTION_SECURITY_SETTINGS)
                startActivity(intent)
            }
            create()
        }.show().secure(activity)
        context.settings().incrementShowLoginsSecureWarningCount()
    }

    /**
     * Create a prompt to confirm the device's pin/password and start activity based on the result.
     * This is only used when BiometricPrompt is unavailable on the device.
     *
     * @param manager The device [KeyguardManager]
     */
    @Suppress("Deprecation")
    override fun showPinVerification(manager: KeyguardManager) {
        val intent = manager.createConfirmDeviceCredentialIntent(
            getString(R.string.logins_biometric_prompt_message_pin),
            getString(R.string.logins_biometric_prompt_message)
        )
        startActivityForResult(intent, PIN_REQUEST)
    }

    /**
     * Called when authentication succeeds.
     */
    private fun navigateToSavedLoginsFragment() {
        context?.components?.analytics?.metrics?.track(Event.OpenLogins)
        val directions =
            SavedLoginsAuthFragmentDirections.actionSavedLoginsAuthFragmentToLoginsListFragment()
        findNavController().navigateBlockingForAsyncNavGraph(directions)
    }

    private fun navigateToSaveLoginSettingFragment() {
        val directions =
            SavedLoginsAuthFragmentDirections.actionSavedLoginsAuthFragmentToSavedLoginsSettingFragment()
        findNavController().navigateBlockingForAsyncNavGraph(directions)
    }

    private fun navigateToLoginExceptionFragment() {
        val directions =
            SavedLoginsAuthFragmentDirections.actionSavedLoginsAuthFragmentToLoginExceptionsFragment()
        findNavController().navigateBlockingForAsyncNavGraph(directions)
    }

    companion object {
        const val SHORT_DELAY_MS = 100L
        const val PIN_REQUEST = 303
    }
}
