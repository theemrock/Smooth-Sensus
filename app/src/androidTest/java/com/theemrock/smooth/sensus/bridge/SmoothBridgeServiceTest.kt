package com.theemrock.smooth.sensus.bridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PermissionInfo
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import com.theemrock.smooth.bridge.ISmoothBridge
import com.theemrock.smooth.bridge.SmoothBridge
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmoothBridgeServiceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun serviceIsExportedAndGuardedByTheSignaturePermission() {
        val service = context.packageManager.getServiceInfo(ComponentName(context, SmoothBridgeService::class.java), 0)
        assertTrue(service.exported)
        assertEquals(SmoothBridge.PERMISSION, service.permission)

        val permission = context.packageManager.getPermissionInfo(SmoothBridge.PERMISSION, 0)
        assertEquals(PermissionInfo.PROTECTION_SIGNATURE, permission.protection)
    }

    @Test
    fun describeReturnsBridgeVersionAndCapabilities() {
        val bound = CountDownLatch(1)
        var bridge: ISmoothBridge? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                bridge = ISmoothBridge.Stub.asInterface(binder)
                bound.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        try {
            assertTrue(context.bindService(Intent(context, SmoothBridgeService::class.java), connection, Context.BIND_AUTO_CREATE))
            assertTrue(bound.await(10, TimeUnit.SECONDS))

            val description = JsonParser.parseString(bridge!!.describe()).asJsonObject
            assertEquals(SmoothBridge.BRIDGE_VERSION, description.get("bridgeVersion").asInt)
            assertTrue(description.getAsJsonArray("capabilities").map { it.asString }.contains(SmoothBridge.CAPABILITY_STRUCTURED))
        } finally {
            context.unbindService(connection)
        }
    }
}
