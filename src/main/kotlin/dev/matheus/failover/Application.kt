package dev.matheus.failover

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class ChargeBody(val amountCents: Long)

/** Simulated provider so the demo runs without credentials. */
class SimulatedProvider(override val name: String) : PaymentProvider {
    override fun charge(request: ChargeRequest): ChargeResult =
        ChargeResult.Approved(name, UUID.nameUUIDFromBytes(request.idempotencyKey.toByteArray()).toString())
}

@RestController
class ChargeController(private val router: ProviderRouter) {
    @PostMapping("/charges")
    fun charge(@RequestHeader("Idempotency-Key") idempotencyKey: String, @RequestBody body: ChargeBody): ChargeResult =
        router.charge(ChargeRequest(idempotencyKey, body.amountCents))

    @ExceptionHandler(NoProviderAvailableException::class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    fun noProvider(exception: NoProviderAvailableException) = mapOf("error" to exception.message)
}

@SpringBootApplication
class Application {
    @Bean
    fun providerRouter() = ProviderRouter(listOf(SimulatedProvider("primary"), SimulatedProvider("secondary")))
}

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}
