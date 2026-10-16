package com.nisaba.device

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Whether there is a network at all (a question that failed offline is asked again when it comes back). */
object ConnectivityDevice {
    fun online(context: Context): StateFlow<Boolean> {
        val flow = MutableStateFlow(false)
        val cm = context.getSystemService(ConnectivityManager::class.java)
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { flow.value = true }
                override fun onLost(network: Network) { flow.value = false }
            })
        }
        return flow
    }
}
