package ai.muse.gadgeteverywhere

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.chaquo.python.PyObject
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Android BLE peripheral implementing upstream's setup Transport
 * (`send_packets` / `mtu` / `disconnect`). Same service/characteristic UUIDs
 * and advertisement content as the BlueZ server, so the Muse app pairs
 * unchanged. Pairing crypto stays in Python (`SetupController`).
 *
 * Notification pacing differs by necessity: Android requires waiting for
 * `onNotificationSent` before the next notify; BlueZ staggers with sleeps.
 */
@SuppressLint("MissingPermission") // PairActivity guarantees runtime grants before open().
// Deliberately context-free: `active` is a static singleton, so this class
// must never hold ANY Context (even the application one trips StaticFieldLeak
// and pins the field for the process lifetime). Callers pass one per call.
class BleTransport {

    companion object {
        private const val TAG = "BleTransport"
        private val SERVICE_UUID = UUID.fromString("7fdd3d1c-38ea-46cf-8b46-314ecf5f240c")
        private val RX_UUID = UUID.fromString("4d593029-28a2-4a6e-a1f0-3c2d5e8f9b01")
        private val TX_UUID = UUID.fromString("d75dc4ca-7b2b-4e9c-8f0a-1d2e3f4a5b6c")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val PAIRED_FLAG_COMPANY_ID = 0xFFFF
        private const val DEFAULT_MTU = 23
        private const val NOTIFY_TIMEOUT_S = 5L
        private var active: BleTransport? = null
    }

    @Volatile private var controller: PyObject? = null
    private val pendingWrites = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
    @Volatile private var mtu = DEFAULT_MTU
    @Volatile private var device: BluetoothDevice? = null
    @Volatile private var notifying = false
    @Volatile private var notifyLatch: CountDownLatch? = null
    @Volatile private var lastNotifyStatus = BluetoothGatt.GATT_SUCCESS
    @Volatile private var opened = false

    @Volatile private var closed = false
    private val serviceLatch = CountDownLatch(1)
    private val advertiseLatch = CountDownLatch(1)
    @Volatile private var serviceStatus = -1
    @Volatile private var advertiseStatus = -1
    private val peers = java.util.concurrent.ConcurrentHashMap<String, Int>()

    @Volatile private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private val handler = Handler(Looper.getMainLooper())

    // -- Transport protocol (called from Python) -------------------------------

    /** Called once by Python so GATT callbacks can reach SetupController. */
    fun attach_controller(controller: PyObject) = synchronized(pendingWrites) {
        this.controller = controller
        // Replay writes that arrived between open() and attach (Chaquo import
        // takes seconds; the phone may already be writing).
        while (true) {
            val packet = pendingWrites.poll() ?: break
            try {
                controller.callAttr("on_write", packet)
            } catch (e: Exception) {
                Log.w(TAG, "replay on_write failed")
            }
        }
    }

    fun adapterName(context: Context): String {
        return try {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            manager.adapter?.name ?: "unknown"
        } catch (e: SecurityException) {
            "unknown (no permission)"
        }
    }

    fun mtu(): Int = mtu
    fun is_open(): Boolean = opened && !closed

    /**
     * Notify one packet; blocks until sent. Per-packet because Chaquopy
     * cannot convert a Python list to java.util.List (TypeError) — the
     * Python adapter loops this instead. Returns false when dropped.
     */
    private fun drop(reason: String): Boolean {
        Log.w(TAG, "send_packet dropped: $reason")
        return false
    }

    @Synchronized fun send_packet(packet: ByteArray): Boolean {
        if (!is_open() || packet.size > mtu - 3) return drop("transport closed or packet too large")
        val server = gattServer ?: return drop("no server")
        val dev = device ?: return drop("no bound peer")
        val tx = txChar ?: return drop("no TX char")
        if (!notifying) return drop("${packet.size}B, not subscribed")
        tx.value = packet
        val latch = CountDownLatch(1)
        lastNotifyStatus = BluetoothGatt.GATT_FAILURE
        notifyLatch = latch
        @Suppress("DEPRECATION")
        val accepted = server.notifyCharacteristicChanged(dev, tx, false)
        if (!accepted) {
            Log.w(TAG, "notify rejected (${packet.size}B)")
            return false
        }
        if (!latch.await(NOTIFY_TIMEOUT_S, TimeUnit.SECONDS)) {
            Log.w(TAG, "notify timeout (${packet.size}B)")
            shutdown()
            return false
        }
        if (lastNotifyStatus != BluetoothGatt.GATT_SUCCESS) {
            Log.w(TAG, "notify status=$lastNotifyStatus (${packet.size}B)")
            return false
        }
        return true
    }

    fun disconnect(delaySec: Double) {
        val dev = device ?: return
        handler.postDelayed(
            {
                try {
                    gattServer?.cancelConnection(dev)
                } catch (e: SecurityException) {
                    Log.w(TAG, "cancelConnection denied", e)
                }
            },
            (delaySec * 1000).toLong(),
        )
    }

    // -- Lifecycle --------------------------------------------------------------

    fun open(context: Context, bleName: String) {
        check(!closed) { "setup was cancelled" }
        if (opened) return
        try {
        // Singleton: a previous window's server/advertiser must never linger
        // beside a new one (centrals pick instances arbitrarily). Take over
        // only AFTER we fully open: if anything below throws, `active`
        // must not point at a half-open transport.
        synchronized(BleTransport::class.java) {
            active?.shutdown()
        }
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter = manager.adapter
            ?: throw IllegalStateException("no Bluetooth adapter")
        if (!adapter.isEnabled) throw IllegalStateException("Bluetooth is off")
        advertiser = adapter.bluetoothLeAdvertiser
            ?: throw IllegalStateException("BLE advertising not supported")

        val service = android.bluetooth.BluetoothGattService(
            SERVICE_UUID,
            android.bluetooth.BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )
        val rx = BluetoothGattCharacteristic(
            RX_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val tx = BluetoothGattCharacteristic(
            TX_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        tx.addDescriptor(
            BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or
                    BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )
        service.addCharacteristic(rx)
        service.addCharacteristic(tx)
        txChar = tx

        // Application context: the framework holds this past our calls,
        // and the caller's Activity may die while we advertise.
        gattServer = manager.openGattServer(context.applicationContext, serverCallback)
            ?: throw IllegalStateException("openGattServer failed")
        if (!gattServer!!.addService(service)) {
            throw IllegalStateException("addService failed")
        }

        check(serviceLatch.await(5, TimeUnit.SECONDS) && serviceStatus == BluetoothGatt.GATT_SUCCESS && !closed) { "GATT service registration failed or cancelled" }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        // Mirror BlueZ: service UUID in the packet, name + paired flag in the
        // scan response. NOTE: apps cannot set a custom advertise name
        // (setName needs BLUETOOTH_PRIVILEGED), so the record carries the
        // adapter's name; the Muse app is expected to scan by service UUID.
        // If it filters by name instead, rename the Chromecast to bleName.
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addManufacturerData(PAIRED_FLAG_COMPANY_ID, byteArrayOf(0))
            .build()
        advertiser!!.startAdvertising(settings, data, scanResponse, advertiseCallback)
        check(advertiseLatch.await(5, TimeUnit.SECONDS) && advertiseStatus == 0 && !closed) { "BLE advertising failed ($advertiseStatus) or cancelled" }
        Log.i(TAG, "advertising service for $bleName (record name is the adapter name)")
        opened = true
        synchronized(BleTransport::class.java) {
            check(!closed) { "setup was cancelled" }
            active = this
        }
        } catch (e: Exception) { shutdown(); throw e }
    }

    fun shutdown() {
        // Whole check-and-teardown under one lock: shutdown races pairing
        // completion (UI thread vs pairing thread); double-teardown must be
        // impossible, not merely harmless.
        synchronized(BleTransport::class.java) {
            closed = true
            opened = false
            if (active === this) active = null
        }
        advertiseLatch.countDown()
        serviceLatch.countDown()
        notifyLatch?.countDown()
        handler.removeCallbacksAndMessages(null)
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (_: Exception) {
        }
        try {
            gattServer?.close()
        } catch (_: Exception) {
        }
        gattServer = null
        advertiser = null
        txChar = null
        device = null
        mtu = DEFAULT_MTU
        notifying = false
        pendingWrites.clear()
        peers.clear()
        // controller deliberately NOT cleared: attach is bind-once per
        // instance, and instances are never reopened after shutdown.
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            advertiseStatus = 0
            advertiseLatch.countDown()
        }

        override fun onStartFailure(errorCode: Int) {
            advertiseStatus = errorCode
            advertiseLatch.countDown()
        }
    }

    @Suppress("DEPRECATION")
    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: android.bluetooth.BluetoothGattService) {
            serviceStatus = status
            serviceLatch.countDown()
        }

        override fun onConnectionStateChange(dev: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                peers.remove(dev.address)
                if (dev.address == device?.address) {
                    device = null
                    mtu = DEFAULT_MTU
                    notifying = false
                    pendingWrites.clear()
                    notifyLatch?.countDown()
                    try { controller?.callAttr("on_disconnect") }
                    catch (_: Exception) { Log.w(TAG, "on_disconnect failed") }
                }
            }
        }

        private fun bind(dev: BluetoothDevice): Boolean = synchronized(pendingWrites) {
            if (!is_open() || (device != null && device?.address != dev.address)) false
            else {
                device = dev
                mtu = peers[dev.address] ?: DEFAULT_MTU
                true
            }
        }

        override fun onCharacteristicWriteRequest(
            dev: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            val accepted = characteristic.uuid == RX_UUID && !preparedWrite && offset == 0 &&
                value.size in 1..512 && pendingWrites.size < 64 && bind(dev)
            if (responseNeeded) gattServer?.sendResponse(dev, requestId,
                if (accepted) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
            if (!accepted) return
            synchronized(pendingWrites) {
                val ctrl = controller
                if (ctrl == null) pendingWrites.add(value.copyOf())
                else try { ctrl.callAttr("on_write", value) }
                catch (_: Exception) { Log.w(TAG, "on_write failed") }
            }
        }

        override fun onCharacteristicReadRequest(
            dev: BluetoothDevice, requestId: Int, offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            // Pairing replies are notifications, never readable by another peer.
            gattServer?.sendResponse(dev, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null)
        }

        override fun onDescriptorWriteRequest(
            dev: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            val enabled = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            val disabled = value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
            val accepted = descriptor.uuid == CCCD_UUID && !preparedWrite && offset == 0 &&
                (enabled || disabled) && bind(dev)
            if (accepted) notifying = enabled
            if (responseNeeded) gattServer?.sendResponse(dev, requestId,
                if (accepted) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
        }

        override fun onNotificationSent(dev: BluetoothDevice, status: Int) {
            if (dev.address != device?.address) return
            lastNotifyStatus = status
            notifyLatch?.countDown()
        }

        override fun onMtuChanged(dev: BluetoothDevice, mtuValue: Int) {
            if (peers.size < 32 || peers.containsKey(dev.address)) peers[dev.address] = mtuValue.coerceIn(23, 517)
            if (dev.address == device?.address) mtu = mtuValue.coerceIn(23, 517)
        }
    }
}
