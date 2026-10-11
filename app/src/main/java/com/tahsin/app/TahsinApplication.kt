package com.tahsin.app

import android.app.Application
import com.tahsin.app.asr.WhisperModelManager
import com.tahsin.app.audio.RecordingStore
import com.tahsin.app.data.quran.QuranDatabase
import com.tahsin.app.data.quran.QuranRepository
import com.tahsin.app.data.user.UserDatabase
import com.tahsin.app.data.user.UserRepository
import com.tahsin.app.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TahsinApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.applicationScope.launch {
            // Bundled models and orphaned recordings are both cheap to reconcile
            // and confusing if left alone, so do it once per process start.
            container.modelManager.installBundledModels()
            // A session killed mid-write leaves valid audio behind a header that
            // claims zero length; repair those before anything tries to play them.
            container.recordingStore.repairTruncatedHeaders()
            container.recordingStore.deleteOrphans(container.userRepository.knownAudioPaths())
        }
    }
}

/** Hand-rolled dependency container; the graph is small enough not to need DI. */
class AppContainer(application: Application) {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val quranDatabase by lazy { QuranDatabase.create(application) }
    private val userDatabase by lazy { UserDatabase.create(application) }

    val quranRepository by lazy { QuranRepository(quranDatabase.quranDao()) }
    val userRepository by lazy { UserRepository(userDatabase.userDao()) }
    val settingsRepository by lazy { SettingsRepository(application) }
    val modelManager by lazy { WhisperModelManager(application) }
    val recordingStore by lazy { RecordingStore(application) }
}
