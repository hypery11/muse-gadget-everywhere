package ai.muse.gadgettv

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
class BleTransport(private val context: Context) {

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

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private val handler = Handler(Looper.getMainLooper())

    // -- Transport protocol (called from Python) -------------------------------

    /** Called once by Python so GATT callbacks can reach SetupController. */
    fun attach_controller(controller: PyObject) {
        this.controller = controller
        // Replay writes that arrived between open() and attach (Chaquo import
        // takes seconds; the phone may already be writing).
        while (true) {
            val packet = pendingWrites.poll() ?: break
            try {
                controller.callAttr("on_write", packet)
            } catch (e: Exception) {
                Log.w(TAG, "replay on_write failed", e)
            }
        }
    }

    fun adapterName(): String {
        return try {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            manager.adapter?.name ?: "unknown"
        } catch (e: SecurityException) {
            "unknown (no permission)"
        }
    }

    fun mtu(): Int = mtu

    /**
     * Notify one packet; blocks until sent. Per-packet because Chaquopy
     * cannot convert a Python list to java.util.List (TypeError) — the
     * Python adapter loops this instead. Returns false when dropped.
     */
    private fun drop(reason: String): Boolean {
        Log.w(TAG, "send_packet dropped: $reason")
        return false
    }

    fun send_packet(packet: ByteArray): Boolean {
        val server = gattServer ?: return drop("no server")
        val dev = device ?: return drop("no bound peer")
        val tx = txChar ?: return drop("no TX char")
        if (!notifying) return drop("${packet.size}B, not subscribed")
        tx.value = packet
        val latch = CountDownLatch(1)
        notifyLatch = latch
        @Suppress("DEPRECATION")
        val accepted = server.notifyCharacteristicChanged(dev, tx, false)
        if (!accepted) {
            Log.w(TAG, "notify rejected (${packet.size}B)")
            return false
        }
        if (!latch.await(NOTIFY_TIMEOUT_S, TimeUnit.SECONDS)) {
            Log.w(TAG, "notify timeout (${packet.size}B)")
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

    fun open(bleName: String) {
        if (opened) return
        // Singleton: a previous window's server/advertiser must never linger
        // beside a new one (centrals pick instances arbitrarily).
        synchronized(BleTransport::class.java) {
            active?.shutdown()
            active = this
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

        gattServer = manager.openGattServer(context, serverCallback)
            ?: throw IllegalStateException("openGattServer failed")
        if (!gattServer!!.addService(service)) {
            throw IllegalStateException("addService failed")
        }

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
        Log.i(TAG, "advertising service for $bleName (record name is the adapter name)")
        opened = true
    }

    fun shutdown() {
        if (!opened) return
        opened = false
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
        device = null
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "advertising started")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "advertising failed: $errorCode")
        }
    }

    @Suppress("DEPRECATION")
    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(dev: BluetoothDevice, status: Int, newState: Int) {
            // The callback fires for EVERY BLE link while our server is open
            // (e.g. the Chromecast Remote reconnecting). Only the peer that
            // writes our RX characteristic is the setup phone; ignore the rest.
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "BLE connected: ${dev.address} (bound=${device?.address})")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "BLE disconnected: ${dev.address} (bound=${device?.address})")
                if (dev.address == device?.address) {
                    device = null
                    mtu = DEFAULT_MTU
                    notifying = false
                    try {
                        controller?.callAttr("on_disconnect")
                    } catch (e: Exception) {
                        Log.w(TAG, "on_disconnect failed", e)
                    }
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            dev: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(dev, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
            if (characteristic.uuid == RX_UUID) {
                if (device == null) {
                    device = dev
                    Log.i(TAG, "setup peer bound: ${dev.address}")
                }
                val ctrl = controller
                if (ctrl == null) {
                    pendingWrites.add(value)
                    Log.i(TAG, "queued ${value.size}B pre-attach write")
                } else {
                    try {
                        ctrl.callAttr("on_write", value)
                    } catch (e: Exception) {
                        Log.w(TAG, "on_write failed", e)
                    }
                }
            }
        }

        override fun onCharacteristicReadRequest(
            dev: BluetoothDevice, requestId: Int, offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val value = characteristic.value ?: ByteArray(0)
            gattServer?.sendResponse(dev, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onDescriptorWriteRequest(
            dev: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                notifying = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                Log.i(TAG, "notifications ${if (notifying) "enabled" else "disabled"}")
            }
            if (responseNeeded) {
                gattServer?.sendResponse(dev, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        override fun onNotificationSent(dev: BluetoothDevice, status: Int) {
            lastNotifyStatus = status
            notifyLatch?.countDown()
        }

        override fun onMtuChanged(dev: BluetoothDevice, mtuValue: Int) {
            mtu = mtuValue
            Log.i(TAG, "ATT MTU $mtuValue")
        }
    }
}
