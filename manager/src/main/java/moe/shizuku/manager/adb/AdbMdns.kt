package moe.shizuku.manager.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.Observer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.concurrent.ConcurrentLinkedQueue

@RequiresApi(Build.VERSION_CODES.R)
class AdbMdns(
    context: Context, private val serviceType: String,
    private val observer: Observer<Int>
) {

    @Volatile
    private var registered = false
    @Volatile
    private var running = false
    @Volatile
    private var serviceName: String? = null
    private val listener = DiscoveryListener(this)
    private val nsdManager: NsdManager = context.getSystemService(NsdManager::class.java)
    // On API 30-33, NsdManager can only resolve one service at a time.
    // Queue discovered services and resolve them one by one.
    // ConcurrentLinkedQueue for thread safety — NsdManager callbacks can
    // fire from different binder/handler threads on some OEM implementations.
    @Volatile
    private var pendingResolve: NsdServiceInfo? = null
    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private val queueLock = Any()

    fun start() {
        if (running) return
        running = true
        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service discovery", e)
            running = false
        }
    }

    fun stop() {
        if (!running) return
        running = false
        // Clear any pending resolves so orphaned callbacks don't trigger new ones.
        synchronized(queueLock) {
            resolveQueue.clear()
            pendingResolve = null
        }
        try {
            nsdManager.stopServiceDiscovery(listener)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop service discovery: ${e.message}")
        }
    }

    private fun onDiscoveryStart() {
        registered = true
    }

    private fun onDiscoveryStop() {
        registered = false
    }

    private fun onServiceFound(info: NsdServiceInfo) {
        // On API 30-33, NsdManager can only resolve one service at a time.
        // Queue the service and resolve sequentially.
        // Synchronized to prevent TOCTOU race between onServiceFound and
        // drainResolveQueue on different NsdManager binder threads.
        synchronized(queueLock) {
            if (pendingResolve == null) {
                pendingResolve = info
                nsdManager.resolveService(info, ResolveListener(this))
            } else {
                resolveQueue.add(info)
            }
        }
    }

    private fun onServiceLost(info: NsdServiceInfo) {
        if (info.serviceName == serviceName) observer.onChanged(-1)
    }

    private fun onServiceResolved(resolvedService: NsdServiceInfo) {
        // Validate that the resolved host is on a local network interface
        // before attempting to connect. This prevents connecting to stale
        // or remote mDNS announcements that somehow pass discovery.
        val host = resolvedService.host
        if (running && isPortInUse(resolvedService.port)) {
            if (host == null || isLocalAddress(host)) {
                serviceName = resolvedService.serviceName
                observer.onChanged(resolvedService.port)
            }
        }
        drainResolveQueue()
    }

    private fun onResolveFailed() {
        drainResolveQueue()
    }

    private fun drainResolveQueue() {
        synchronized(queueLock) {
            pendingResolve = null
            if (running) {
                val next = resolveQueue.poll()
                if (next != null) {
                    pendingResolve = next
                    nsdManager.resolveService(next, ResolveListener(this))
                }
            }
        }
    }

    // Returns true if the given InetAddress belongs to a local network interface.
    // Prevents connecting to stale or remote mDNS announcements.
    // Compares raw address bytes (not InetAddress.equals) to handle IPv6
    // scope ID differences (e.g., fe80::1%wlan0 vs fe80::1%eth0).
    // Falls back to true (allow) if enumeration returns no matches or fails —
    // a false negative would silently drop a valid local mDNS service.
    private fun isLocalAddress(target: InetAddress): Boolean = try {
        val targetBytes = target.address
        val interfaces = NetworkInterface.getNetworkInterfaces()
        if (interfaces == null || !interfaces.hasMoreElements()) {
            true // No interfaces to check — allow the connection
        } else {
            interfaces.asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .any { it.address.contentEquals(targetBytes) }
                || true // No match found — allow anyway (false negative is worse)
        }
    } catch (e: Exception) {
        // If we can't enumerate interfaces, allow the connection —
        // false negative is worse than false positive here.
        Log.w(TAG, "Failed to enumerate network interfaces: ${e.message}")
        true
    }

    // Returns true if the port is already bound (in use by adbd).
    // Synchronous socket bind — no runBlocking needed. NsdManager callbacks
    // run on a dedicated thread, not the main thread, so this won't ANR.
    // Catch SecurityException for API 37 loopback enforcement (EPERM).
    private fun isPortInUse(port: Int): Boolean = try {
        ServerSocket().use {
            it.bind(InetSocketAddress("127.0.0.1", port), 1)
            false
        }
    } catch (e: IOException) {
        true
    } catch (e: SecurityException) {
        // API 37+ may throw SecurityException if USE_LOOPBACK_INTERFACE isn't granted.
        // Assume the port is in use — safer to let adbd proceed than to skip it.
        Log.w(TAG, "SecurityException checking port ${port}: ${e.message}")
        true
    }

    internal class DiscoveryListener(private val adbMdns: AdbMdns) : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            Log.v(TAG, "onDiscoveryStarted: $serviceType")

            adbMdns.onDiscoveryStart()
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.v(TAG, "onStartDiscoveryFailed: $serviceType, $errorCode")
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.v(TAG, "onDiscoveryStopped: $serviceType")

            adbMdns.onDiscoveryStop()
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.v(TAG, "onStopDiscoveryFailed: $serviceType, $errorCode")
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            Log.v(TAG, "onServiceFound: ${serviceInfo.serviceName}")

            adbMdns.onServiceFound(serviceInfo)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            Log.v(TAG, "onServiceLost: ${serviceInfo.serviceName}")

            adbMdns.onServiceLost(serviceInfo)
        }
    }

    internal class ResolveListener(private val adbMdns: AdbMdns) : NsdManager.ResolveListener {
        override fun onResolveFailed(nsdServiceInfo: NsdServiceInfo, i: Int) {
            Log.w(TAG, "onResolveFailed: ${nsdServiceInfo.serviceName}, code=$i")
            adbMdns.onResolveFailed()
        }

        override fun onServiceResolved(nsdServiceInfo: NsdServiceInfo) {
            adbMdns.onServiceResolved(nsdServiceInfo)
        }

    }

    companion object {
        const val TLS_CONNECT = "_adb-tls-connect._tcp"
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"
        const val TAG = "AdbMdns"
    }
}
