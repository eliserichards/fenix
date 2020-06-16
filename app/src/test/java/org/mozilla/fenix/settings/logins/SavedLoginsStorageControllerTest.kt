/* This Source Code Form is subject to the terms of the Mozilla Public
   License, v. 2.0. If a copy of the MPL was not distributed with this
   file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.settings.logins

import android.os.Looper
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScope
import mozilla.components.concept.storage.Login
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.fenix.HomeActivity
import org.mozilla.fenix.R
import org.mozilla.fenix.components.Components
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.helpers.FenixRobolectricTestRunner
import org.mozilla.fenix.settings.logins.controller.SavedLoginsStorageController
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode

@ExperimentalCoroutinesApi
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(FenixRobolectricTestRunner::class)
class SavedLoginsStorageControllerTest {
    private lateinit var components: Components
    private val homeActivity: HomeActivity = mockk(relaxed = true)
    private lateinit var controller: SavedLoginsStorageController
    private val navController: NavController = mockk(relaxed = true)
    private val loginsFragmentStore: LoginsFragmentStore = mockk(relaxed = true)
    private val scope = TestCoroutineScope()
    private val loginMock: Login = mockk(relaxed = true)

    @Before
    fun setup() {
        every { navController.currentDestination } returns NavDestination("").apply {
            id = R.id.loginDetailFragment
        }
        coEvery { homeActivity.components.core.passwordsStorage.get(any()) } returns loginMock
        every { loginsFragmentStore.dispatch(any()) } returns mockk()
        coEvery { homeActivity.components.core.passwordsStorage } returns mockk(relaxed = true)
        components = mockk(relaxed = true)

        controller = SavedLoginsStorageController(
            context = homeActivity,
            viewLifecycleScope = MainScope(),
            navController = navController,
            loginsFragmentStore = loginsFragmentStore
        )
    }

    @After
    fun cleanUp() {
        scope.cleanupTestCoroutines()
    }

    @Test
    fun `WHEN a login is deleted, THEN navigate back to the previous page`() = runBlocking {
        val loginId = "id"
        // mock for deleteLoginJob: Deferred<Boolean>?
        coEvery { homeActivity.components.core.passwordsStorage.delete(any()) } returns true
        controller.delete(loginId)

        shadow()

        coVerify { homeActivity.components.core.passwordsStorage.delete(loginId) }
    }

    private fun shadow() {
        // solves issue with Roboelectric v4.3 and SDK 28
        // https://github.com/robolectric/robolectric/issues/5356
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `WHEN fetching the login list, THEN update the state in the store`() {
        val loginId = "id"
        // for deferredLogin: Deferred<List<Login>>?
        coEvery { homeActivity.components.core.passwordsStorage.list() } returns listOf()

        controller.fetchLoginDetails(loginId)

        coVerify { homeActivity.components.core.passwordsStorage.list() }
    }

    @Test
    fun `WHEN saving an update to an item, THEN navigate to login detail view`() {
        val login = Login(
            guid = "id",
            origin = "https://www.test.co.gov.org",
            username = "user123",
            password = "securePassword1",
            httpRealm = "httpRealm",
            formActionOrigin = ""
        )
        coEvery { homeActivity.components.core.passwordsStorage.get(any()) } returns loginMock

        controller.save(login.guid!!, login.username, login.password)

        coVerify { homeActivity.components.core.passwordsStorage.get(any()) }
    }

    @Test
    fun `WHEN finding login dupes, THEN update duplicates in the store`() {
        val login = Login(
            guid = "id",
            origin = "https://www.test.co.gov.org",
            username = "user123",
            password = "securePassword1",
            httpRealm = "httpRealm",
            formActionOrigin = ""
        )

        // for deferredLogin: Deferred<List<Login>>?
        coEvery {
            homeActivity.components.core.passwordsStorage.getPotentialDupesIgnoringUsername(any())
        } returns listOf()

        controller.findPotentialDuplicates(login.guid!!)
        shadow()
        coVerify {
            homeActivity.components.core.passwordsStorage.getPotentialDupesIgnoringUsername(login)
        }
    }
}
