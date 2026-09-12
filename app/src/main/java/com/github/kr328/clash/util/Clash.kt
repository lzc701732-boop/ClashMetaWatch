package com.github.kr328.clash.util

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.github.kr328.clash.common.compat.startForegroundServiceCompat
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.service.ClashService
import com.github.kr328.clash.service.TunService
import com.github.kr328.clash.service.util.sendBroadcastSelf

fun Context.startClashService(): Intent? {
    val startTun = UiStore(this).enableVpn

    if (startTun) {
        val vpnRequest = try {
            VpnService.prepare(this)
        } catch (e: Exception) {
            // Some firmwares (e.g. Samsung Wear OS builds) ship without the
            // vpn_management system service; prepare() throws an NPE and TUN mode
            // is impossible on this device. Fall back to proxy-only mode instead
            // of crashing.
            Log.w("VPN service unavailable: ${e.message}; starting proxy-only mode")
            startForegroundServiceCompat(ClashService::class.intent)
            return null
        }
        if (vpnRequest != null)
            return vpnRequest

        startForegroundServiceCompat(TunService::class.intent)
    } else {
        startForegroundServiceCompat(ClashService::class.intent)
    }

    return null
}

fun Context.stopClashService() {
    sendBroadcastSelf(Intent(Intents.ACTION_CLASH_REQUEST_STOP))
}