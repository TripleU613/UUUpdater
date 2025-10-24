package com.tripleu.updater.di

import androidx.work.WorkManager
import com.tripleu.updater.BuildConfig
import com.tripleu.updater.R
import com.tripleu.updater.data.ui.FdroidSource
import com.tripleu.updater.data.ui.IzzySource
import com.tripleu.updater.prefs.Prefs
import com.tripleu.updater.repository.ApkPureRepository
import com.tripleu.updater.repository.AppsRepository
import com.tripleu.updater.repository.AptoideRepository
import com.tripleu.updater.repository.AuthRepository
import com.tripleu.updater.repository.CustomFeedRepository
import com.tripleu.updater.repository.FdroidRepository
import com.tripleu.updater.repository.GitHubRepository
import com.tripleu.updater.repository.GitLabRepository
import com.tripleu.updater.repository.PlayRepository
import com.tripleu.updater.repository.SearchRepository
import com.tripleu.updater.repository.UpdatesRepository
import com.tripleu.updater.service.ApkPureService
import com.tripleu.updater.service.AptoideService
import com.tripleu.updater.service.FdroidService
import com.tripleu.updater.service.GitHubService
import com.tripleu.updater.service.GitLabService
import com.tripleu.updater.util.Badger
import com.tripleu.updater.util.Clipboard
import com.tripleu.updater.util.Downloader
import com.tripleu.updater.util.InstallLog
import com.tripleu.updater.util.SessionInstaller
import com.tripleu.updater.util.SnackBar
import com.tripleu.updater.util.Stringer
import com.tripleu.updater.util.Themer
import com.tripleu.updater.util.UpdatesNotification
import com.tripleu.updater.util.addUserAgentInterceptor
import com.tripleu.updater.util.isAndroidTv
import com.tripleu.updater.util.play.PlayHttpClient
import com.tripleu.updater.viewmodel.AppsViewModel
import com.tripleu.updater.viewmodel.MainViewModel
import com.tripleu.updater.viewmodel.AuthViewModel
import com.tripleu.updater.viewmodel.SearchViewModel
import com.tripleu.updater.viewmodel.SettingsViewModel
import com.tripleu.updater.viewmodel.UpdatesViewModel
import com.google.gson.GsonBuilder
import com.kryptoprefs.preferences.KryptoBuilder
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import java.util.concurrent.TimeUnit


val mainModule = module {

	single { GsonBuilder().create() }

	single { Cache(androidContext().cacheDir, 1024 * 1024 * 1024) }

	single {
		HttpLoggingInterceptor().apply {
			level = HttpLoggingInterceptor.Level.BODY
		}
	}

	single {
		OkHttpClient.Builder()
			.connectTimeout(60, TimeUnit.SECONDS)
			.readTimeout(180, TimeUnit.SECONDS)
			.writeTimeout(180, TimeUnit.SECONDS)
			.retryOnConnectionFailure(true)
			.cache(get())
			.addUserAgentInterceptor("APKUpdater-v" + BuildConfig.VERSION_NAME)
			//.addInterceptor(get<HttpLoggingInterceptor>())
			.build()
	}

	single {
		Retrofit.Builder()
			.client(get())
			.baseUrl("https://api.github.com")
			.addConverterFactory(GsonConverterFactory.create(get()))
			.build()
			.create(GitHubService::class.java)
	}

	single {
		Retrofit.Builder()
			.client(get())
			.baseUrl("https://gitlab.com")
			.addConverterFactory(GsonConverterFactory.create(get()))
			.build()
			.create(GitLabService::class.java)
	}

	single {
		Retrofit.Builder()
			.client(get())
			.baseUrl("https://f-droid.org/repo/")
			.addConverterFactory(GsonConverterFactory.create(get()))
			.build()
			.create(FdroidService::class.java)
	}

	single {
		val client = OkHttpClient.Builder()
			.connectTimeout(60, TimeUnit.SECONDS)
			.readTimeout(180, TimeUnit.SECONDS)
			.writeTimeout(180, TimeUnit.SECONDS)
			.retryOnConnectionFailure(true)
			.cache(get())
			.addUserAgentInterceptor(AptoideRepository.UserAgent)
			.build()

		Retrofit.Builder()
			.client(client)
			.baseUrl("https://ws75.aptoide.com/api/7/")
			.addConverterFactory(GsonConverterFactory.create(get()))
			.build()
			.create(AptoideService::class.java)
	}

	single {
		Retrofit.Builder()
			.client(get())
			.baseUrl("https://tapi.pureapk.com/")
			.addConverterFactory(GsonConverterFactory.create(get()))
			.build()
			.create(ApkPureService::class.java)
	}

	single {
		val client = OkHttpClient.Builder()
			.followRedirects(true)
			.connectTimeout(60, TimeUnit.SECONDS)
			.readTimeout(0, TimeUnit.MILLISECONDS)
			.writeTimeout(0, TimeUnit.MILLISECONDS)
			.callTimeout(0, TimeUnit.MILLISECONDS)
			.retryOnConnectionFailure(true)
			.cache(get())
			.build()
		val auroraClient = OkHttpClient.Builder()
			.followRedirects(true)
			.connectTimeout(60, TimeUnit.SECONDS)
			.readTimeout(0, TimeUnit.MILLISECONDS)
			.writeTimeout(0, TimeUnit.MILLISECONDS)
			.callTimeout(0, TimeUnit.MILLISECONDS)
			.retryOnConnectionFailure(true)
			.cache(get())
			.addUserAgentInterceptor("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
			.build()
		val apkPureClient = OkHttpClient.Builder()
			.followRedirects(true)
			.connectTimeout(60, TimeUnit.SECONDS)
			.readTimeout(0, TimeUnit.MILLISECONDS)
			.writeTimeout(0, TimeUnit.MILLISECONDS)
			.callTimeout(0, TimeUnit.MILLISECONDS)
			.retryOnConnectionFailure(true)
			.cache(get())
			.addUserAgentInterceptor("APKPure/3.19.39 (Aegon)")
			.build()
		val dir = File(androidContext().cacheDir, "downloads").apply { mkdirs() }
		Downloader(client, apkPureClient, auroraClient, dir)
	}

	single { AppsRepository(get(), get()) }

	single { GitHubRepository(get(), get()) }

	single { GitLabRepository(get(), get()) }

	single { ApkPureRepository(get(), get(), get()) }

	single { AptoideRepository(get(), get(), get()) }

	single { PlayRepository(get(), get(), get(), get()) }

	single { CustomFeedRepository(get(), get()) }

	single(named("main")) { FdroidRepository(get(), "https://f-droid.org/repo/", FdroidSource, get()) }

	single(named("izzy")) { FdroidRepository(get(), "https://apt.izzysoft.de/fdroid/repo/", IzzySource, get()) }

	single {
		UpdatesRepository(
			get(),
			get(),
			get(named("main")),
			get(named("izzy")),
			get(),
			get(),
			get(),
			get(),
			get(),
			get()
		)
	}

	single {
		SearchRepository(
			get(named("main")),
			get(named("izzy")),
			get(),
			get(),
			get(),
			get(),
			get(),
			get(),
			get()
		)
	}

	single { KryptoBuilder.nocrypt(get(), androidContext().getString(R.string.app_name)) }

	single { Prefs(get(), androidContext().isAndroidTv()) }

	single { UpdatesNotification(get()) }

	single { Clipboard(androidContext()) }

	single { SessionInstaller(get(), get()) }

	single { SnackBar() }

	single { Badger() }

	single { Themer(get()) }

	single { Stringer(androidContext()) }

	single { InstallLog() }

	single { PlayHttpClient(get()) }

	single { FirebaseAuth.getInstance() }

	single { FirebaseDatabase.getInstance() }

	single { AuthRepository(get(), get()) }

	viewModel { MainViewModel(get(), get()) }

	viewModel { AuthViewModel(get()) }

	viewModel { AppsViewModel(get(), get(), get()) }

	viewModel { UpdatesViewModel(get(), get(), get(), get(), get(), get(), get(), get()) }

	viewModel { SettingsViewModel(get(), get(), WorkManager.getInstance(get()), get(), get(), get(), get()) }

	viewModel { SearchViewModel(get(), get(), get(), get(), get(), get(), get(), get()) }

}
