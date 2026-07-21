package eu.darken.amply.common

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.amplyDataStore by preferencesDataStore(name = "amply")

@Singleton
class AppDataStore internal constructor(
    val store: DataStore<Preferences>,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.amplyDataStore)
}
