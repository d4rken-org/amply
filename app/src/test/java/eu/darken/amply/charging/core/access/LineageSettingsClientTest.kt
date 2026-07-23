package eu.darken.amply.charging.core.access

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LineageSettingsClientTest {

    private lateinit var provider: FakeLineageProvider
    private lateinit var client: LineageSettingsClient

    @Before
    fun setup() {
        val info = ProviderInfo().apply { authority = "lineagesettings" }
        provider = Robolectric.buildContentProvider(FakeLineageProvider::class.java).create(info).get()
        client = LineageSettingsClient(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `present and absent keys collapse into one consistent snapshot`() = runTest {
        provider.rows = listOf(
            LineageSettingsClient.KEY_ENABLED to "1",
            LineageSettingsClient.KEY_LIMIT to "80",
            // mode intentionally absent
        )
        client.readChargeControl() shouldBe LineageChargeReadout.Values(enabled = "1", mode = null, limit = "80")
    }

    @Test
    fun `an empty table reads as all-absent, not a failure`() = runTest {
        provider.rows = emptyList()
        client.readChargeControl() shouldBe LineageChargeReadout.Values(enabled = null, mode = null, limit = null)
    }

    @Test
    fun `a duplicate row for a key is unreadable, never guessed`() = runTest {
        provider.rows = listOf(
            LineageSettingsClient.KEY_ENABLED to "1",
            LineageSettingsClient.KEY_ENABLED to "0",
        )
        client.readChargeControl().shouldBeInstanceOf<LineageChargeReadout.Unreadable>()
    }

    @Test
    fun `a missing value column is unreadable, never a positional guess`() = runTest {
        provider.columns = arrayOf(LineageSettingsClient.COL_NAME) // no "value" column
        provider.rows = listOf(LineageSettingsClient.KEY_ENABLED to "1")
        client.readChargeControl().shouldBeInstanceOf<LineageChargeReadout.Unreadable>()
    }

    class FakeLineageProvider : ContentProvider() {
        var rows: List<Pair<String, String?>> = emptyList()
        var columns: Array<String> = arrayOf(LineageSettingsClient.COL_NAME, LineageSettingsClient.COL_VALUE)

        override fun onCreate() = true
        override fun query(
            uri: Uri,
            projection: Array<String>?,
            selection: String?,
            selectionArgs: Array<String>?,
            sortOrder: String?,
        ): Cursor = MatrixCursor(columns).apply {
            rows.forEach { (name, value) ->
                addRow(if (columns.size == 1) arrayOf<Any?>(name) else arrayOf<Any?>(name, value))
            }
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
    }
}
