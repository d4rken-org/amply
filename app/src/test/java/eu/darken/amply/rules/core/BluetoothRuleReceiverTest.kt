package eu.darken.amply.rules.core

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * The receiver is the only live "is it connected" signal, so what it persists is what every later
 * evaluation believes. Robolectric because a real [BluetoothDevice] extra is what the parsing has to
 * survive — the interesting failure is an intent shape, not a decision.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BluetoothRuleReceiverTest {

    private val address = "AA:BB:CC:DD:EE:FF"
    private lateinit var scope: CoroutineScope
    private lateinit var tempDir: File
    private lateinit var store: ChargeRulesStore
    private lateinit var receiver: BluetoothRuleReceiver

    @Before
    fun setup() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        tempDir = Files.createTempDirectory("rules-receiver").toFile()
        val dataStore = testDataStore(scope, tempDir)
        store = testRulesStore(dataStore)
        receiver = BluetoothRuleReceiver().apply {
            applier = RuleApplier(
                store = store,
                gateway = FakeChargeGateway(),
                preferences = testPreferences(dataStore),
                upgradeRepo = FakeUpgradeRepo(),
                bluetooth = FakeBluetoothSource(),
                bootCountProvider = bootCount(3),
                qualificationRunStore = testQualificationRunStore(dataStore),
            )
        }
    }

    @After
    fun teardown() {
        scope.cancel()
        tempDir.deleteRecursively()
    }

    private fun aclIntent(action: String): Intent = Intent(action).putExtra(
        BluetoothDevice.EXTRA_DEVICE,
        BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address),
    )

    private fun deliver(action: String) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val update = BluetoothRuleReceiver.parse(aclIntent(action))!!
        receiver.handleUpdate(context, update)
    }

    @Test
    fun `an ACL connect adds the device to the snapshot`() {
        deliver(BluetoothDevice.ACTION_ACL_CONNECTED)

        runBlocking { store.btSnapshotNow() }.let {
            it.addresses shouldBe setOf(address)
            it.bootCount shouldBe 3
        }
    }

    @Test
    fun `an ACL disconnect removes the device again`() {
        deliver(BluetoothDevice.ACTION_ACL_CONNECTED)
        deliver(BluetoothDevice.ACTION_ACL_DISCONNECTED)

        runBlocking { store.btSnapshotNow() }.addresses.shouldBeEmpty()
    }

    @Test
    fun `unrelated broadcasts are ignored`() {
        BluetoothRuleReceiver.parse(aclIntent(BluetoothDevice.ACTION_FOUND)) shouldBe null
        BluetoothRuleReceiver.parse(Intent(BluetoothDevice.ACTION_ACL_CONNECTED)) shouldBe null
    }
}
