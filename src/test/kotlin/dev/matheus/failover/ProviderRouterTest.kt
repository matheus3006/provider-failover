package dev.matheus.failover

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

private class MutableClock : Clock() {
    var now: Instant = Instant.parse("2026-01-01T00:00:00Z")
    override fun instant() = now
    override fun getZone(): ZoneId = ZoneId.of("UTC")
    override fun withZone(zone: ZoneId) = this
}

private class FakeProvider(override val name: String) : PaymentProvider {
    var isDown = false
    var declines = false
    val receivedKeys = mutableListOf<String>()

    override fun charge(request: ChargeRequest): ChargeResult {
        receivedKeys += request.idempotencyKey
        if (isDown) throw ProviderUnavailableException("$name is down")
        if (declines) return ChargeResult.Declined(name, "insufficient_funds")
        return ChargeResult.Approved(name, "ref-$name")
    }
}

class ProviderRouterTest {
    private val clock = MutableClock()
    private val primary = FakeProvider("primary")
    private val secondary = FakeProvider("secondary")
    private val policy = HealthPolicy(minimumSamples = 4, errorRateToOpen = 0.5, cooldown = Duration.ofSeconds(30))
    private val router = ProviderRouter(listOf(primary, secondary), policy, clock)

    private fun chargeTimes(times: Int) = repeat(times) { attempt -> router.charge(ChargeRequest("key-$attempt", 100)) }

    @Test
    fun `fails over with the same idempotency key`() {
        primary.isDown = true
        val result = router.charge(ChargeRequest("key-1", 100))
        assertEquals("secondary", assertIs<ChargeResult.Approved>(result).providerName)
        assertEquals(listOf("key-1"), primary.receivedKeys)
        assertEquals(listOf("key-1"), secondary.receivedKeys)
    }

    @Test
    fun `a decline never fails over`() {
        primary.declines = true
        assertIs<ChargeResult.Declined>(router.charge(ChargeRequest("key-1", 100)))
        assertEquals(emptyList(), secondary.receivedKeys)
    }

    @Test
    fun `stops calling a provider once the error rate opens the breaker`() {
        primary.isDown = true
        chargeTimes(4)
        assertEquals(4, primary.receivedKeys.size)
        chargeTimes(3)
        assertEquals(4, primary.receivedKeys.size)
    }

    @Test
    fun `does not open below the minimum samples`() {
        primary.isDown = true
        chargeTimes(3)
        primary.isDown = false
        assertEquals("primary", assertIs<ChargeResult.Approved>(router.charge(ChargeRequest("next", 100))).providerName)
    }

    @Test
    fun `samples outside the window do not count`() {
        primary.isDown = true
        chargeTimes(3)
        clock.now = clock.now.plus(Duration.ofSeconds(61))
        chargeTimes(1)
        chargeTimes(1)
        assertEquals(5, primary.receivedKeys.size)
    }

    @Test
    fun `a successful probe after the cooldown closes the breaker`() {
        primary.isDown = true
        chargeTimes(4)
        primary.isDown = false
        clock.now = clock.now.plus(Duration.ofSeconds(31))
        assertEquals("primary", assertIs<ChargeResult.Approved>(router.charge(ChargeRequest("probe", 100))).providerName)
        assertEquals("primary", assertIs<ChargeResult.Approved>(router.charge(ChargeRequest("after", 100))).providerName)
    }

    @Test
    fun `a failed probe restarts the cooldown`() {
        primary.isDown = true
        chargeTimes(4)
        clock.now = clock.now.plus(Duration.ofSeconds(31))
        chargeTimes(1)
        assertEquals(5, primary.receivedKeys.size)
        chargeTimes(1)
        assertEquals(5, primary.receivedKeys.size)
    }

    @Test
    fun `reports unavailable when every provider fails`() {
        primary.isDown = true
        secondary.isDown = true
        assertFailsWith<NoProviderAvailableException> { router.charge(ChargeRequest("key-1", 100)) }
    }
}
