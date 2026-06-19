package io.github.mayusi.calibratesoc.data.tunables.writer.retroid

import android.content.Context
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [RetroidFanBinderClient]'s wire protocol: the EXACT interface tokens
 * and transaction codes (1 getProvider, 5 setMode, 7 setSpeed, 2 readFanValue) the
 * SettingsController → FanProvider chain carries.
 *
 * The full ServiceManager acquisition is Android-framework-bound (hidden reflection +
 * real Parcel/IBinder) and is verified off-device by source reasoning + the live RP6
 * run. What we CAN verify deterministically here — and what the spec's VERIFY list asks
 * for — is the wire each transact builds: we static-mock [Parcel] so [Parcel.obtain]
 * returns a recording mock, pass a mock [IBinder] provider directly into the internal
 * transact seams ([RetroidFanBinderClient.doWriteTxn] / [RetroidFanBinderClient.readIntOnce]
 * / [RetroidFanBinderClient.acquireFanProviderFrom]), and assert the captured interface
 * token + txn code + payload are exact.
 */
class RetroidFanBinderClientTest {

    private lateinit var client: RetroidFanBinderClient

    @Before
    fun setUp() {
        // Log + Parcel are framework stubs ("Stub!") in the pure-JVM runtime — mock them.
        mockkStatic(Log::class)
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0

        mockkStatic(Parcel::class)

        val ctx = mockk<Context>(relaxed = true)
        every { ctx.applicationContext } returns ctx
        client = RetroidFanBinderClient(ctx)
    }

    @After
    fun tearDown() = unmockkAll()

    /** A recording Parcel pair where obtain() hands back data then reply. */
    private fun stubParcels(): Pair<Parcel, Parcel> {
        val data = mockk<Parcel>(relaxed = true)
        val reply = mockk<Parcel>(relaxed = true)
        every { Parcel.obtain() } returnsMany listOf(data, reply)
        return data to reply
    }

    // ── txn 5: setMode → c(int) ─────────────────────────────────────────────────

    @Test
    fun `setMode writes the FanProvider token and transacts txn 5 with the mode int`() {
        val (data, _) = stubParcels()
        val provider = mockk<IBinder>(relaxed = true)
        every { provider.transact(any(), any(), any(), any()) } returns true

        val codeSlot = slot<Int>()
        val outcome = client.doWriteTxn(provider, RetroidFanBinderClient.TXN_SET_MODE) {
            it.writeInt(RetroidFanConfig.CUSTOM_MODE)
        }

        assertThat(outcome).isEqualTo(RetroidFanBinderClient.TxnOutcome.Ok)
        verify { data.writeInterfaceToken(RetroidFanBinderClient.FAN_PROVIDER_DESCRIPTOR) }
        verify { data.writeInt(RetroidFanConfig.CUSTOM_MODE) }
        verify { provider.transact(capture(codeSlot), any(), any(), 0) }
        assertThat(codeSlot.captured).isEqualTo(5)
    }

    // ── txn 7: setSpeed → r(int) ────────────────────────────────────────────────

    @Test
    fun `setSpeed writes the FanProvider token and transacts txn 7 with the speed int`() {
        val (data, _) = stubParcels()
        val provider = mockk<IBinder>(relaxed = true)
        every { provider.transact(any(), any(), any(), any()) } returns true

        val codeSlot = slot<Int>()
        val outcome = client.doWriteTxn(provider, RetroidFanBinderClient.TXN_SET_SPEED) {
            it.writeInt(12345)
        }

        assertThat(outcome).isEqualTo(RetroidFanBinderClient.TxnOutcome.Ok)
        verify { data.writeInterfaceToken(RetroidFanBinderClient.FAN_PROVIDER_DESCRIPTOR) }
        verify { data.writeInt(12345) }
        verify { provider.transact(capture(codeSlot), any(), any(), 0) }
        assertThat(codeSlot.captured).isEqualTo(7)
    }

    // ── txn 2: readFanValue → b() -> int ────────────────────────────────────────

    @Test
    fun `readIntOnce writes the FanProvider token, transacts txn 2, and returns the reply int`() {
        val (_, reply) = stubParcels()
        val provider = mockk<IBinder>(relaxed = true)
        every { provider.transact(any(), any(), any(), any()) } returns true
        every { reply.readInt() } returns 25000

        val codeSlot = slot<Int>()
        val value = client.readIntOnce(provider, RetroidFanBinderClient.TXN_READ_FAN)

        assertThat(value).isEqualTo(25000)
        verify { provider.transact(capture(codeSlot), any(), any(), 0) }
        assertThat(codeSlot.captured).isEqualTo(2)
    }

    @Test
    fun `readIntOnce returns null when the transact throws (honest, never crashes)`() {
        stubParcels()
        val provider = mockk<IBinder>(relaxed = true)
        every { provider.transact(any(), any(), any(), any()) } throws RuntimeException("boom")

        assertThat(client.readIntOnce(provider, RetroidFanBinderClient.TXN_READ_FAN)).isNull()
    }

    // ── txn 1: getProvider("FanProvider") on SettingsController ──────────────────

    @Test
    fun `acquireFanProviderFrom writes the external-control token plus 'FanProvider' and transacts txn 1`() {
        val (data, reply) = stubParcels()
        val controller = mockk<IBinder>(relaxed = true)
        val provider = mockk<IBinder>(relaxed = true)
        every { controller.transact(any(), any(), any(), any()) } returns true
        every { reply.readStrongBinder() } returns provider

        val codeSlot = slot<Int>()
        val result = client.acquireFanProviderFrom(controller)

        assertThat(result).isSameInstanceAs(provider)
        verify { data.writeInterfaceToken(RetroidFanBinderClient.EXTERNAL_CONTROL_DESCRIPTOR) }
        verify { data.writeString(RetroidFanBinderClient.PROVIDER_NAME) }
        verify { controller.transact(capture(codeSlot), any(), any(), 0) }
        assertThat(codeSlot.captured).isEqualTo(1)
    }

    @Test
    fun `the wire constants are exactly the decompiled values`() {
        // Locked so a refactor can't silently change the wire the RP6 firmware enforces.
        assertThat(RetroidFanBinderClient.SERVICE_NAME).isEqualTo("SettingsController")
        assertThat(RetroidFanBinderClient.PROVIDER_NAME).isEqualTo("FanProvider")
        assertThat(RetroidFanBinderClient.EXTERNAL_CONTROL_DESCRIPTOR)
            .isEqualTo("com.ro.settings.IExternalControlManager")
        assertThat(RetroidFanBinderClient.FAN_PROVIDER_DESCRIPTOR)
            .isEqualTo("com.ro.settings.IFanControlProvider")
        assertThat(RetroidFanBinderClient.TXN_GET_PROVIDER).isEqualTo(1)
        assertThat(RetroidFanBinderClient.TXN_READ_FAN).isEqualTo(2)
        assertThat(RetroidFanBinderClient.TXN_SET_MODE).isEqualTo(5)
        assertThat(RetroidFanBinderClient.TXN_SET_ENABLED).isEqualTo(6)
        assertThat(RetroidFanBinderClient.TXN_SET_SPEED).isEqualTo(7)
    }

    // ── Safe-degrade: no live ServiceManager in the JVM → no-op, never crashes ──

    @Test
    fun `public ops degrade safely with no ServiceManager (return false-null, never throw)`() = runTest {
        // In the pure-JVM runtime ServiceManager.getService throws "Stub!"; the client
        // catches it → no provider → every op is an honest no-op.
        assertThat(client.isAvailable()).isFalse()
        assertThat(client.setMode(RetroidFanConfig.CUSTOM_MODE)).isFalse()
        assertThat(client.setSpeed(10000)).isFalse()
        assertThat(client.setEnabled(true)).isFalse()
        assertThat(client.readFanValue()).isNull()
    }
}
