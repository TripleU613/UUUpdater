package com.tripleu.updater.application

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.tripleu.updater.BuildConfig
import com.tripleu.updater.di.mainModule
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.tripleu.updater.prefs.Prefs
import com.tripleu.updater.util.Downloader
import com.tripleu.updater.util.play.PlayDeviceSpoofManager
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.context.startKoin

class App : Application(), ImageLoaderFactory, KoinComponent {

	override fun onCreate() {
		super.onCreate()

		FirebaseApp.initializeApp(this)

                startKoin {
                        androidLogger()
                        androidContext(this@App)
                        modules(mainModule)
                }

                get<Downloader>().clearStaleDownloads()

                PlayDeviceSpoofManager.initialize(this)

                if (BuildConfig.TECHEAZ_PRIVILEGED_INSTALLER) {
                        val prefs: Prefs = get()
                        val signedIn = runCatching { FirebaseAuth.getInstance().currentUser }.getOrNull() != null
                        if (signedIn) {
                                prefs.playSpoofRebootPending.put(true)
                                prefs.playSpoofRebootHandled.put(false)
                        }
                }
	}

	override fun newImageLoader() = ImageLoader
		.Builder(this)
		.okHttpClient(get<OkHttpClient>())
		//.logger(DebugLogger())
		.build()

}
