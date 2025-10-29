package com.tripleu.updater.repository

import com.tripleu.updater.BuildConfig
import com.tripleu.updater.util.NoOpLog as Log
import com.tripleu.updater.data.ui.AppUpdate
import com.tripleu.updater.data.ui.blockKey
import com.tripleu.updater.prefs.Prefs
import com.tripleu.updater.util.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

class SearchRepository(
    private val fdroidRepository: FdroidRepository,
    private val izzyRepository: FdroidRepository,
    private val aptoideRepository: AptoideRepository,
    private val gitHubRepository: GitHubRepository,
    private val apkPureRepository: ApkPureRepository,
    private val gitLabRepository: GitLabRepository,
    private val playRepository: PlayRepository,
    private val customFeedRepository: CustomFeedRepository,
    private val prefs: Prefs
) {

    fun search(text: String) = flow {
        val privilegedFlavor = BuildConfig.TECHEAZ_PRIVILEGED_INSTALLER
        val sources = mutableListOf<Flow<Result<List<AppUpdate>>>>()
        if (!privilegedFlavor && prefs.useFdroid.get()) sources.add(fdroidRepository.search(text))
        if (!privilegedFlavor && prefs.useIzzy.get()) sources.add(izzyRepository.search(text))
        if (!privilegedFlavor && prefs.useAptoide.get()) sources.add(aptoideRepository.search(text))
        if (!privilegedFlavor && prefs.useGitHub.get()) sources.add(gitHubRepository.search(text))
        if (!privilegedFlavor && prefs.useApkPure.get()) sources.add(apkPureRepository.search(text))
        if (!privilegedFlavor && prefs.useGitLab.get()) sources.add(gitLabRepository.search(text))
        if (prefs.usePlay.get() || privilegedFlavor) sources.add(playRepository.search(text))
        if (customFeedRepository.isEnabled) sources.add(customFeedRepository.search(text))

        if (sources.isNotEmpty()) {
            sources.combine { updates ->
                val blockedCertificates = prefs.blockedCertificateSources.get().toHashSet()
                val blockedInstallFailures = prefs.blockedInstallFailures.get().toHashSet()
                val result = updates.filter { it.isSuccess }.mapNotNull { it.getOrNull() }
                val flattened = result.flatten()
                    .filterNot { it.isBlacklisted() }
                    .filterNot {
                        val key = it.blockKey()
                        blockedCertificates.contains(key) || blockedInstallFailures.contains(key)
                    }
                    .sortedBy { it.name }
                emit(Result.success(flattened))
            }.collect()
        } else {
            emit(Result.success(emptyList()))
        }
    }.catch {
        emit(Result.failure(it))
        Log.e("SearchRepository", "Error searching.", it)
    }
}
