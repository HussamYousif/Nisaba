package com.nisaba.epd

import android.os.IBinder
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Second OEC probe: transaction-code constants, and a live sweep of
 * setAppScopeRefreshMode(int, boolean) values with read-back, so the app can
 * pick the fastest writing waveform (docs/04 §2).
 */
@RunWith(AndroidJUnit4::class)
class OecProbeTest {

    private fun service(): Any {
        val sm = Class.forName("android.os.ServiceManager")
        val binder = sm.getMethod("getService", String::class.java).invoke(null, "oec_service") as IBinder
        val stub = Class.forName("android.onyx.optimization.IOECService\$Stub")
        return stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
    }

    @Test
    fun dumpFullApi() {
        val s = runCatching { service() }.getOrNull()
        if (s == null) {
            Log.w("NisabaOec", "service unavailable (expected off-Boox)")
            assertTrue(true)
            return
        }
        for (m in s.javaClass.methods.sortedBy { it.name }) {
            if (m.declaringClass.name.contains("Proxy")) {
                val params = m.parameterTypes.joinToString(", ") { it.name }
                Log.w("NisabaOec", "API ${m.name}($params) -> ${m.returnType.name}")
            }
        }
        val stubClass = runCatching { Class.forName("android.onyx.optimization.IOECService\$Stub") }.getOrNull()
        stubClass?.declaredFields?.filter { it.name.startsWith("TRANSACTION_") }?.forEach { f ->
            Log.w("NisabaOec", "CODE ${f.getInt(null)} = ${f.name}")
        }
        assertTrue(true)
    }

    @Test
    fun dumpEacConfigFieldsAndApply() {
        // 1. EACAppConfig field names = candidate Bundle keys.
        val cfgClass = runCatching { Class.forName("android.onyx.optimization.EACAppConfig") }.getOrNull()
        if (cfgClass != null) {
            for (f in cfgClass.declaredFields) {
                Log.w("NisabaOec", "CFG ${f.name}")
            }
        } else {
            Log.w("NisabaOec", "EACAppConfig class not found")
        }
        // 2. Try writing our refresh config via Bundle.
        val s = runCatching { service() }.getOrNull() ?: return
        val applier = s.javaClass.methods.firstOrNull { it.name == "applyAppConfigToService" }
        if (applier != null) {
            val bundle = android.os.Bundle().apply {
                putString("refreshModeIndex", "refresh_mode_1")
                putInt("updateMode", 3)
            }
            try {
                applier.invoke(s, listOf("com.nisaba"), bundle)
                Log.w("NisabaOec", "applyAppConfigToService called OK")
            } catch (e: Exception) {
                Log.w("NisabaOec", "apply FAILED: ${e.cause?.message ?: e.message}")
            }
        }
        assertTrue(true)
    }

    @Test
    fun dumpTransactionCodesAndSweepModes() {
        val stubClass = runCatching { Class.forName("android.onyx.optimization.IOECService\$Stub") }.getOrNull()
            ?: run {
                Log.w("NisabaOec", "service unavailable (expected off-Boox)")
                assertTrue(true)
                return
            }
        val constants = stubClass.declaredFields
            .filter { it.name.startsWith("TRANSACTION_") }
            .sortedBy { runCatching { it.getInt(null) }.getOrDefault(Int.MAX_VALUE) }
        for (f in constants) {
            Log.w("NisabaOec", "CODE ${f.getInt(null)} = ${f.name}")
        }

        val s = runCatching { service() }.getOrNull() ?: return
        val setter = s.javaClass.methods.first { it.name == "setAppScopeRefreshMode" }
        val getter = s.javaClass.methods.first { it.name == "getAppScopeRefreshMode" }
        val current = getter.invoke(s)
        Log.w("NisabaOec", "current mode = $current")

        for (mode in 0..40) {
            try {
                setter.invoke(s, mode, false)
                val back = getter.invoke(s)
                if (back != 0) Log.w("NisabaOec", "*** set($mode) -> readback $back ***")
            } catch (e: Exception) {
                Log.w("NisabaOec", "set($mode) FAILED: ${e.cause?.message ?: e.message}")
            }
        }
        for (mode in listOf(41, 50, 64, 100, 101, 128, 200, 255)) {
            try {
                setter.invoke(s, mode, true)
                val back = getter.invoke(s)
                Log.w("NisabaOec", "set($mode, true) -> readback $back")
            } catch (e: Exception) {
                Log.w("NisabaOec", "set($mode, true) FAILED: ${e.cause?.message ?: e.message}")
            }
        }
        assertTrue(true)
    }
}
