package eu.darken.amply.common.worker

import android.content.Context
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Injectable [WorkManager]. Consumers that are constructed during Application field injection must
 * take a `Provider<WorkManager>`, not the instance: resolving it here calls
 * [WorkManager.getInstance], which triggers WorkManager's on-demand initialization — and that reads
 * the Application's worker factory, which isn't injected yet at that point.
 */
@InstallIn(SingletonComponent::class)
@Module
class WorkManagerModule {

    @Provides
    @Singleton
    fun workManager(@ApplicationContext context: Context): WorkManager = WorkManager.getInstance(context)
}
