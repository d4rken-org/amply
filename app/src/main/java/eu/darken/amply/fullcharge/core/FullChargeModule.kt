package eu.darken.amply.fullcharge.core

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** The system boot counter, abstracted so interruption bookkeeping stays pure-JVM testable. */
fun interface BootCountProvider {
    fun current(): Int?
}

@Module
@InstallIn(SingletonComponent::class)
object FullChargeModule {
    @Provides
    @Singleton
    fun bootCountProvider(@ApplicationContext context: Context): BootCountProvider =
        BootCountProvider { ServiceDispatch.currentBootCount(context) }

    @Provides
    @Singleton
    fun exitSource(impl: ProcessExitSource): ExitSource = impl
}
