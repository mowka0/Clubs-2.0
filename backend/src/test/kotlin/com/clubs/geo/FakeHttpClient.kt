package com.clubs.geo

import java.io.IOException
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession

/**
 * Подмена HTTP для гео-сервисов: сеть в тестах не гоняется, но проверить нужно именно то,
 * что происходит ВОКРУГ ответа Яндекса — сколько запросов ушло, с какими параметрами, и во
 * что превращается не-2xx. Разбор тела покрывается отдельно, вызовом приватных методов
 * рефлексией (см. `GeocoderServiceTest`), здесь же — поведение всего похода целиком.
 *
 * Заготовки отдаются строго по очереди; лишний запрос роняет тест с понятным сообщением —
 * на этом держится проверка «повтор без рамки ровно один, а не бесконечный».
 */

/** Что фейковый клиент отдаст на очередной запрос. */
sealed interface CannedReply {

    /** Нормальный HTTP-ответ: код и тело, как их прислал бы Яндекс. */
    data class Answer(val status: Int, val body: String) : CannedReply

    /** Обрыв связи или таймаут: клиент бросает IOException, как настоящий. */
    data class NetworkFailure(val reason: String) : CannedReply
}

/** Успешный ответ Яндекса с готовым JSON-телом. */
fun ok(body: String): CannedReply = CannedReply.Answer(200, body)

/** Ответ с не-2xx кодом: 403 «домен не разрешён», 429 «лимит исчерпан» и прочее. */
fun failed(status: Int, body: String = ""): CannedReply = CannedReply.Answer(status, body)

/** Сеть отвалилась, ответа нет вовсе. */
fun networkFailure(reason: String = "connection reset"): CannedReply =
    CannedReply.NetworkFailure(reason)

/**
 * HTTP-клиент, который не ходит в сеть, а раздаёт заготовки по порядку и записывает запросы.
 */
class FakeHttpClient(replies: List<CannedReply>) : HttpClient() {

    /** Все ушедшие запросы в порядке отправки — по ним проверяется сборка URL и заголовки. */
    val requests = mutableListOf<HttpRequest>()

    private val pending = ArrayDeque(replies)

    /** Сколько запросов реально ушло наружу. */
    val requestCount: Int get() = requests.size

    /** URL запроса под номером [index], начиная с нуля. */
    fun urlOf(index: Int): String = requests[index].uri().toString()

    /** Значение заголовка [name] у запроса под номером [index]; пусто — заголовка нет. */
    fun headerOf(index: Int, name: String): String =
        requests[index].headers().firstValue(name).orElse("")

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any?> send(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>
    ): HttpResponse<T> {
        requests += request
        val reply = pending.removeFirstOrNull()
            ?: error("Лишний запрос в Яндекс (#${requests.size}): ${request.uri()}")

        return when (reply) {
            is CannedReply.NetworkFailure -> throw IOException(reply.reason)
            is CannedReply.Answer -> CannedHttpResponse(reply.status, reply.body, request) as HttpResponse<T>
        }
    }

    // ── дальше только заглушки: сервисы пользуются одним синхронным send ────────────────

    override fun <T : Any?> sendAsync(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>
    ): CompletableFuture<HttpResponse<T>> = throw UnsupportedOperationException("Только синхронный send")

    override fun <T : Any?> sendAsync(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
        pushPromiseHandler: HttpResponse.PushPromiseHandler<T>?
    ): CompletableFuture<HttpResponse<T>> = throw UnsupportedOperationException("Только синхронный send")

    override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()
    override fun connectTimeout(): Optional<Duration> = Optional.empty()
    override fun followRedirects(): Redirect = Redirect.NEVER
    override fun proxy(): Optional<ProxySelector> = Optional.empty()
    override fun sslContext(): SSLContext = SSLContext.getDefault()
    override fun sslParameters(): SSLParameters = SSLParameters()
    override fun authenticator(): Optional<Authenticator> = Optional.empty()
    override fun version(): Version = Version.HTTP_1_1
    override fun executor(): Optional<Executor> = Optional.empty()
}

/**
 * Ставит фейковый клиент вместо приватного `http` у сервиса.
 *
 * Через рефлексию, а не через конструктор: клиент — деталь реализации сервиса (свои таймауты,
 * своя политика редиректов), и вытаскивать его в зависимость ради тестов значило бы менять
 * продакшн-контракт под тест.
 */
fun installFakeHttp(service: Any, replies: List<CannedReply>): FakeHttpClient {
    val fake = FakeHttpClient(replies)
    val field = service.javaClass.getDeclaredField("http")
    field.isAccessible = true
    field.set(service, fake)
    return fake
}

/** Ответ Яндекса как объект `HttpResponse`: сервисам нужны только код и тело. */
private class CannedHttpResponse(
    private val status: Int,
    private val text: String,
    private val sentRequest: HttpRequest
) : HttpResponse<String> {

    override fun statusCode(): Int = status
    override fun request(): HttpRequest = sentRequest
    override fun previousResponse(): Optional<HttpResponse<String>> = Optional.empty()
    override fun headers(): HttpHeaders = EMPTY_HEADERS
    override fun body(): String = text
    override fun sslSession(): Optional<SSLSession> = Optional.empty()
    override fun uri(): URI = sentRequest.uri()
    override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1

    private companion object {
        /** Заголовки ответа сервисам не нужны — отдаём один пустой набор на всех. */
        val EMPTY_HEADERS: HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }
    }
}
