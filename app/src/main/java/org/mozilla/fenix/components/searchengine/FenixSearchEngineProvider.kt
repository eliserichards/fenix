/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.components.searchengine

import android.content.Context
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mozilla.components.browser.search.SearchEngine
import mozilla.components.browser.search.provider.AssetsSearchEngineProvider
import mozilla.components.browser.search.provider.SearchEngineList
import mozilla.components.browser.search.provider.SearchEngineProvider
import mozilla.components.browser.search.provider.filter.SearchEngineFilter
import mozilla.components.browser.search.provider.localization.LocaleSearchLocalizationProvider
import mozilla.components.browser.search.provider.localization.SearchLocalizationProvider
import mozilla.components.service.location.LocationService
import mozilla.components.service.location.MozillaLocationService
import mozilla.components.service.location.search.RegionSearchLocalizationProvider
import org.mozilla.fenix.BuildConfig
import org.mozilla.fenix.Config
import org.mozilla.fenix.components.metrics.Event
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.ext.settings
import java.util.Locale

@SuppressWarnings("TooManyFunctions")
open class FenixSearchEngineProvider(
    private val context: Context
) : SearchEngineProvider, CoroutineScope by CoroutineScope(Job() + Dispatchers.IO) {
    private val shouldMockMLS = Config.channel.isDebug || BuildConfig.MLS_TOKEN.isNullOrEmpty()
    private val locationService: LocationService = if (shouldMockMLS) {
        LocationService.dummy()
    } else {
        MozillaLocationService(
            context,
            context.components.core.client,
            BuildConfig.MLS_TOKEN
        )
    }

    // We have two search engine types: one based on MLS reported region, one based only on Locale.
    // There are multiple steps involved in returning the default search engine for example.
    // Simplest and most effective way to make sure the MLS engines do not mix with Locale based engines
    // is to use the same type of engines for the entire duration of the app's run.
    // See fenix/issues/11875
    private val isRegionCachedByLocationService = locationService.hasRegionCached()

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    open val localizationProvider: SearchLocalizationProvider =
        RegionSearchLocalizationProvider(locationService)

    /**
     * Unfiltered list of search engines based on locale.
     */
    open var localizedSearchEngines = async {
        AssetsSearchEngineProvider(localizationProvider)
            .loadSearchEngines(context)
    }

    private val loadedRegion = async { localizationProvider.determineRegion() }

    // https://github.com/mozilla-mobile/fenix/issues/9935
    // Adds a Locale search engine provider as a fallback in case the MLS lookup takes longer
    // than the time it takes for a user to try to search.
    private val fallbackLocationService: SearchLocalizationProvider = LocaleSearchLocalizationProvider()
    private val fallBackProvider =
        AssetsSearchEngineProvider(fallbackLocationService)

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    open val fallbackEngines = async { fallBackProvider.loadSearchEngines(context) }
    private val fallbackRegion = async { fallbackLocationService.determineRegion() }

    /**
     * Default bundled search engines based on locale.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    open val bundledSearchEngines = async {
        val defaultEngineIdentifiers =
            localizedSearchEngines.await().list.map { it.identifier }.toSet()
        AssetsSearchEngineProvider(
            localizationProvider,
            filters = listOf(object : SearchEngineFilter {
                override fun filter(context: Context, searchEngine: SearchEngine): Boolean {
                    return BUNDLED_SEARCH_ENGINES.contains(searchEngine.identifier) &&
                            !defaultEngineIdentifiers.contains(searchEngine.identifier)
                }
            }),
            additionalIdentifiers = BUNDLED_SEARCH_ENGINES
        ).loadSearchEngines(context)
    }

    /**
     * Search engines that have been manually added by a user.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    open var customSearchEngines = async {
        CustomSearchEngineProvider().loadSearchEngines(context)
    }

    // https://github.com/mozilla-mobile/fenix/issues/9935
    // Create new getter that will return the fallback SearchEngineList if
    // the main one hasn't completed yet
    private val searchEngines: Deferred<SearchEngineList>
        get() =
            if (isRegionCachedByLocationService) {
                refreshInstalledEngineListAsync(localizedSearchEngines)
            } else {
                refreshInstalledEngineListAsync(fallbackEngines)
            }

    fun getDefaultEngine(context: Context): SearchEngine {
        val engines = installedSearchEngines(context)
        val selectedName = context.settings().defaultSearchEngineName

        return engines.list.find { it.name == selectedName } ?: engines.default ?: engines.list.first()
    }

    private fun setDefaultEngine(id: String) {
        context.settings().defaultSearchEngineName = id
    }

    /**
     * @return a list of all SearchEngines that are currently active. These are the engines that
     * are readily available throughout the app. Includes all installed engines, both
     * default and custom
     */
    fun installedSearchEngines(context: Context): SearchEngineList = runBlocking {
        val installedIdentifiers = installedSearchEngineIdentifiers(context)
        val defaultList = searchEngines.await()

        val installedDefaultEngines = defaultList.list.filter {
            installedIdentifiers.contains(it.identifier)
        }.sortedBy { it.name.toLowerCase(Locale.getDefault()) }

        val installedCustomEngines = customSearchEngines.await().list.filter {
            installedIdentifiers.contains(it.identifier)
        }.sortedBy { it.name.toLowerCase(Locale.getDefault()) }

        val fullList = installedDefaultEngines + installedCustomEngines

        defaultList.copy(
            list = fullList,
            default = defaultList.default?.let {
                if (installedIdentifiers.contains(it.identifier)) {
                    it
                } else {
                    null
                }
            }
        )
    }

    suspend fun allSearchEngineIdentifiers() =
        withContext(Dispatchers.Default) {
            refreshInstalledEngineListAsync(localizedSearchEngines).await().list.map { it.identifier }
        }

    fun uninstalledSearchEngines(context: Context): SearchEngineList = runBlocking {
        val installedIdentifiers = installedSearchEngineIdentifiers(context)
        val engineList = refreshInstalledEngineListAsync(localizedSearchEngines).await()

        engineList.copy(
            list = engineList.list.filterNot { installedIdentifiers.contains(it.identifier) }
        )
    }

    override suspend fun loadSearchEngines(context: Context): SearchEngineList {
        return installedSearchEngines(context)
    }

    fun installSearchEngine(
        context: Context,
        searchEngine: SearchEngine,
        isCustom: Boolean = false
    ) = runBlocking {
        if (isCustom) {
            val searchUrl = searchEngine.getSearchTemplate()
            CustomSearchEngineStore.addSearchEngine(context, searchEngine.name, searchUrl)
            reloadCustomSearchEngines()
        } else {
            val installedIdentifiers = installedSearchEngineIdentifiers(context).toMutableSet()
            installedIdentifiers.add(searchEngine.identifier)
            prefs(context).edit()
                .putStringSet(
                    localeAwareInstalledEnginesKey(), installedIdentifiers
                ).apply()
        }
    }

    fun uninstallSearchEngine(
        context: Context,
        searchEngine: SearchEngine,
        isCustom: Boolean = false
    ) = runBlocking {
        if (isCustom) {
            handleDefaultEngine(context, searchEngine.identifier)
            CustomSearchEngineStore.removeSearchEngine(context, searchEngine.identifier)
            context.components.analytics.metrics.track(Event.CustomEngineDeleted)
            reloadCustomSearchEngines()
        } else {
            val installedIdentifiers = installedSearchEngineIdentifiers(context).toMutableSet()
            installedIdentifiers.remove(searchEngine.identifier)
            prefs(context).edit().putStringSet(
                localeAwareInstalledEnginesKey(),
                installedIdentifiers
            ).apply()
        }
    }

    /**
     * Handles resetting the default search engine when an engine is uninstalled.
     * @param context The context
     * @param searchEngineId The id of the search engine that is being uninstalled
     */
    private fun handleDefaultEngine(
        context: Context,
        searchEngineId: String
    ) {
        val defaultEngineId = getDefaultEngine(context).identifier
        val engines = installedSearchEngines(context)

        val newDefault = if (defaultEngineId == searchEngineId) {
            engines.default ?: engines.list.first { it.identifier != defaultEngineId }
        } else {
            engines.list.first { it.identifier == defaultEngineId }
        }
        setDefaultEngine(newDefault.name)
    }

    fun reloadCustomSearchEngines() {
        launch {
            customSearchEngines = async { CustomSearchEngineProvider().loadSearchEngines(context) }
        }
    }

    // When we change the locale we need to update the baseSearchEngines list
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    open fun updateBaseSearchEngines() {
        localizedSearchEngines = async {
            AssetsSearchEngineProvider(localizationProvider)
                .loadSearchEngines(context)
        }
    }

    private fun refreshInstalledEngineListAsync(engines: Deferred<SearchEngineList>) = async {
        val engineList = engines.await()
        val bundledList = bundledSearchEngines.await().list
        val customList = customSearchEngines.await().list

        engineList.copy(list = engineList.list + bundledList + customList)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(
        PREF_FILE_SEARCH_ENGINES,
        Context.MODE_PRIVATE
    )

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    suspend fun installedSearchEngineIdentifiers(context: Context): Set<String> {
        val prefs = prefs(context)
        val installedEnginesKey = localeAwareInstalledEnginesKey()

        if (!prefs.contains(installedEnginesKey)) {
            val searchEngines =
                if (isRegionCachedByLocationService) {
                    localizedSearchEngines
                } else {
                    fallbackEngines
                }

            val defaultSet = searchEngines.await()
                .list
                .map { it.identifier }
                .toSet()

            prefs.edit().putStringSet(installedEnginesKey, defaultSet).apply()
        }

        val installedIdentifiers: Set<String> =
            prefs(context).getStringSet(installedEnginesKey, setOf()) ?: setOf()

        val customEngineIdentifiers =
            customSearchEngines.await().list.map { it.identifier }.toSet()

        return installedIdentifiers + customEngineIdentifiers
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    suspend fun localeAwareInstalledEnginesKey(): String {
        val tag = if (isRegionCachedByLocationService) {
            val localization = loadedRegion.await()
            val region = localization.region?.let {
                if (it.isEmpty()) "" else "-$it"
            }

            "${localization.languageTag}$region"
        } else {
            val localization = fallbackRegion.await()
            val region = localization.region?.let {
                if (it.isEmpty()) "" else "-$it"
            }

            "${localization.languageTag}$region-fallback"
        }

        return "$INSTALLED_ENGINES_KEY-$tag"
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    companion object {
        val BUNDLED_SEARCH_ENGINES = listOf("reddit", "youtube")
        const val PREF_FILE_SEARCH_ENGINES = "fenix-search-engine-provider"
        const val INSTALLED_ENGINES_KEY = "fenix-installed-search-engines"
    }
}
