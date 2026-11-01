package dev.matheus.failover

import java.time.Clock
import java.time.Duration
import java.time.Instant

data class ChargeRequest(val idempotencyKey: String, val amountCents: Long)

sealed interface ChargeResult {
    data class Approved(val providerName: String, val providerReference: String) : ChargeResult
    data class Declined(val providerName: String, val reason: String) : ChargeResult
}

/** Provider failure, as opposed to a business decline: only this one triggers failover. */
class ProviderUnavailableException(message: String) : RuntimeException(message)

class NoProviderAvailableException : RuntimeException("no payment provider available")

interface PaymentProvider {
    val name: String
    fun charge(request: ChargeRequest): ChargeResult
}

data class HealthPolicy(
    val window: Duration = Duration.ofSeconds(60),
    val minimumSamples: Int = 10,
    val errorRateToOpen: Double = 0.5,
    val cooldown: Duration = Duration.ofSeconds(30),
)

/** Sliding-window breaker. After the cooldown, one probe decides whether the provider recovers. */
class ProviderHealth(private val policy: HealthPolicy, private val clock: Clock) {
    private data class Sample(val recordedAt: Instant, val failed: Boolean)

    private val samples = ArrayDeque<Sample>()
    private var openedAt: Instant? = null
    private var probeInFlight = false

    @Synchronized
    fun tryAcquire(): Boolean {
        val openedSince = openedAt ?: return true
        if (Duration.between(openedSince, clock.instant()) < policy.cooldown || probeInFlight) return false
        probeInFlight = true
        return true
    }

    @Synchronized
    fun recordSuccess() {
        if (openedAt != null) {
            openedAt = null
            probeInFlight = false
            samples.clear()
        }
        addSample(failed = false)
    }

    @Synchronized
    fun recordFailure() {
        if (openedAt != null) {
            openedAt = clock.instant()
            probeInFlight = false
            return
        }
        addSample(failed = true)
        val failedCount = samples.count { sample -> sample.failed }
        val reachedErrorRate = failedCount.toDouble() / samples.size >= policy.errorRateToOpen
        if (samples.size >= policy.minimumSamples && reachedErrorRate) openedAt = clock.instant()
    }

    private fun addSample(failed: Boolean) {
        val now = clock.instant()
        samples.addLast(Sample(now, failed))
        val oldestAllowed = now.minus(policy.window)
        while (samples.first().recordedAt < oldestAllowed) samples.removeFirst()
    }
}

/**
 * Tries providers in priority order. A decline is a final answer and never fails over,
 * and every attempt carries the same idempotency key.
 */
class ProviderRouter(
    providers: List<PaymentProvider>,
    policy: HealthPolicy = HealthPolicy(),
    clock: Clock = Clock.systemUTC(),
) {
    private val routes = providers.map { provider -> provider to ProviderHealth(policy, clock) }

    fun charge(request: ChargeRequest): ChargeResult {
        for ((provider, health) in routes) {
            if (!health.tryAcquire()) continue
            try {
                val result = provider.charge(request)
                health.recordSuccess()
                return result
            } catch (_: ProviderUnavailableException) {
                health.recordFailure()
            }
        }
        throw NoProviderAvailableException()
    }
}
