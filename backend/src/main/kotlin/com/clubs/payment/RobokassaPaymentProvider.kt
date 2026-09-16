package com.clubs.payment

import com.clubs.common.exception.ForbiddenException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Адаптер Robokassa (platform-billing.md § 6.2, по docs.robokassa.ru на 2026-09-07):
 * чекаут — страница `Merchant/Index.aspx` с `Recurring=true`; дочернее списание —
 * `POST Merchant/Recurring` (PreviousInvoiceID **не входит** в подпись); ResultURL — подпись
 * `OutSum:InvId:Password#2:Shp_club=…` и allowlist IP; опрос — XML `OpStateExt`.
 * Чек НПД формирует «Робочеки СМЗ» на стороне провайдера, поэтому `Receipt` не передаём.
 * Пароли живут только в env и в логи не попадают.
 */
@Component
@ConditionalOnProperty(name = ["billing.provider"], havingValue = "robokassa")
class RobokassaPaymentProvider(
    @Value("\${billing.robokassa.merchant-login}") private val merchantLogin: String,
    @Value("\${billing.robokassa.password1}") private val password1: String,
    @Value("\${billing.robokassa.password2}") private val password2: String,
    // IsTest=1 — платежи не проводятся, ResultURL подписывается тестовыми паролями (staging).
    @Value("\${billing.robokassa.test-mode:true}") private val testMode: Boolean,
    // Должен совпадать с алгоритмом в настройках магазина Robokassa.
    @Value("\${billing.robokassa.hash:SHA256}") hashAlgorithm: String,
    // Адреса, с которых Robokassa шлёт ResultURL; пусто = отвергать всё (fail-close).
    @Value("\${billing.robokassa.allowed-ips:185.59.216.65,185.59.217.65}") allowedIps: String,
    @Value("\${billing.robokassa.base-url:https://auth.robokassa.ru}") private val baseUrl: String,
) : PaymentProvider {

    private val log = LoggerFactory.getLogger(RobokassaPaymentProvider::class.java)
    private val signature = RobokassaSignature(hashAlgorithm)
    private val allowedIps: Set<String> = allowedIps.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    override val id = "robokassa"

    init {
        require(merchantLogin.isNotBlank() && password1.isNotBlank() && password2.isNotBlank()) {
            "billing.provider=robokassa requires ROBOKASSA_MERCHANT_LOGIN, ROBOKASSA_PASSWORD_1 and ROBOKASSA_PASSWORD_2"
        }
        log.info("Robokassa provider active: merchantLogin={} testMode={} hash={}", merchantLogin, testMode, hashAlgorithm)
    }

    override fun createCheckout(request: CheckoutRequest): CheckoutUrl {
        val outSum = formatOutSum(request.amountKopecks)
        val shp = "Shp_club=${request.clubId}"
        // Модификаторы — в порядке документации; из них у нас только SuccessUrl2/FailUrl2 с методами.
        val signed = signature.hash(
            listOf(
                merchantLogin, outSum, request.invId.toString(),
                request.successUrl, REDIRECT_METHOD, request.failUrl, REDIRECT_METHOD,
                password1, shp,
            ).joinToString(":"),
        )
        val params = buildList {
            add("MerchantLogin" to merchantLogin)
            add("OutSum" to outSum)
            add("InvId" to request.invId.toString())
            add("Description" to sanitizeDescription(request.description))
            add("Culture" to "ru")
            if (request.recurring) add("Recurring" to "true")
            add("SuccessUrl2" to request.successUrl)
            add("SuccessUrl2Method" to REDIRECT_METHOD)
            add("FailUrl2" to request.failUrl)
            add("FailUrl2Method" to REDIRECT_METHOD)
            if (testMode) add("IsTest" to "1")
            add("Shp_club" to request.clubId.toString())
            add("SignatureValue" to signed)
        }
        return CheckoutUrl("$baseUrl/Merchant/Index.aspx?" + encode(params))
    }

    override fun charge(request: RecurringChargeRequest): ChargeAccepted {
        val outSum = formatOutSum(request.amountKopecks)
        val shp = "Shp_club=${request.clubId}"
        // PreviousInvoiceID в подпись не входит (документация Robokassa, раздел «Периодические платежи»).
        val signed = signature.hash(listOf(merchantLogin, outSum, request.invId.toString(), password1, shp).joinToString(":"))
        val form = encode(
            listOf(
                "MerchantLogin" to merchantLogin,
                "InvoiceID" to request.invId.toString(),
                "PreviousInvoiceID" to request.previousInvId.toString(),
                "OutSum" to outSum,
                "Description" to sanitizeDescription(request.description),
                "Shp_club" to request.clubId.toString(),
                "SignatureValue" to signed,
            ),
        )
        val httpRequest = HttpRequest.newBuilder(URI.create("$baseUrl/Merchant/Recurring"))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build()
        return try {
            val response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString())
            val body = response.body().trim()
            val accepted = response.statusCode() == 200 && body.startsWith("OK", ignoreCase = true)
            if (!accepted) log.warn("Robokassa recurring rejected: invId={} status={} body={}", request.invId, response.statusCode(), body.take(200))
            ChargeAccepted(accepted, body.take(200))
        } catch (e: java.io.IOException) {
            log.warn("Robokassa recurring call failed: invId={} reason={}", request.invId, e.message)
            ChargeAccepted(accepted = false, providerMessage = e.message)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            ChargeAccepted(accepted = false, providerMessage = "interrupted")
        }
    }

    override fun trustsResultSource(clientIp: String): Boolean = clientIp in allowedIps

    override fun parseResultNotification(params: Map<String, String>): ResultNotification {
        val outSum = params["OutSum"] ?: throw ForbiddenException("Robokassa result without OutSum")
        val invId = params["InvId"]?.toLongOrNull() ?: throw ForbiddenException("Robokassa result without InvId")
        // Shp_* — по алфавиту, как в подписи запроса; у нас один параметр.
        val shp = params.filterKeys { it.startsWith("Shp_") }.toSortedMap().map { (k, v) -> "$k=$v" }
        val expected = signature.hash((listOf(outSum, invId.toString(), password2) + shp).joinToString(":"))
        if (!signature.matches(expected, params["SignatureValue"])) {
            log.warn("Robokassa result signature mismatch: invId={}", invId)
            throw ForbiddenException("Invalid Robokassa signature")
        }
        return ResultNotification(
            invId = invId,
            amountKopecks = parseKopecks(outSum),
            paymentMethod = params["PaymentMethod"]?.takeIf { it.isNotBlank() },
            fee = params["Fee"]?.toBigDecimalOrNull(),
        )
    }

    override fun queryState(invId: Long): PaymentStateResult {
        val signed = signature.hash(listOf(merchantLogin, invId.toString(), password2).joinToString(":"))
        val query = encode(listOf("MerchantLogin" to merchantLogin, "InvoiceID" to invId.toString(), "Signature" to signed))
        val httpRequest = HttpRequest.newBuilder(URI.create("$baseUrl/Merchant/WebService/Service.asmx/OpStateExt?$query"))
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build()
        val body = try {
            http.send(httpRequest, HttpResponse.BodyHandlers.ofString()).body()
        } catch (e: java.io.IOException) {
            log.warn("Robokassa OpStateExt call failed: invId={} reason={}", invId, e.message)
            return PaymentStateResult(PaymentState.PENDING)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return PaymentStateResult(PaymentState.PENDING)
        }
        return parseOpState(invId, body)
    }

    /** Разбор ответа OpStateExt: код результата 0 — операция найдена; коды состояния — по документации. */
    internal fun parseOpState(invId: Long, xml: String): PaymentStateResult {
        val doc = try {
            secureDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            log.warn("Robokassa OpStateExt unparseable response: invId={} reason={}", invId, e.message)
            return PaymentStateResult(PaymentState.PENDING)
        }
        val resultCode = doc.getElementsByTagName("Result").item(0)?.let { firstChildText(it, "Code") }?.toIntOrNull()
        if (resultCode != 0) {
            log.warn("Robokassa OpStateExt result code {}: invId={}", resultCode, invId)
            return PaymentStateResult(PaymentState.PENDING)
        }
        val stateCode = doc.getElementsByTagName("State").item(0)?.let { firstChildText(it, "Code") }?.toIntOrNull()
        val paymentMethod = doc.getElementsByTagName("Info").item(0)?.let { firstChildText(it, "PaymentMethod") }?.takeIf { it.isNotBlank() }
        val state = when (stateCode) {
            STATE_COMPLETED -> PaymentState.SUCCEEDED
            STATE_CANCELLED, STATE_REFUNDED -> PaymentState.FAILED
            else -> PaymentState.PENDING
        }
        return PaymentStateResult(state, paymentMethod)
    }

    private fun firstChildText(node: org.w3c.dom.Node, tag: String): String? {
        val children = node.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeName == tag) return child.textContent?.trim()
        }
        return null
    }

    private fun secureDocumentBuilder() = DocumentBuilderFactory.newInstance().apply {
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        isXIncludeAware = false
        isExpandEntityReferences = false
    }.newDocumentBuilder()

    private fun encode(params: List<Pair<String, String>>): String =
        params.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }

    companion object {
        private const val REDIRECT_METHOD = "GET"
        // Коды состояния операции OpStateExt (docs.robokassa.ru, «XML интерфейсы»).
        private const val STATE_COMPLETED = 100
        private const val STATE_CANCELLED = 10
        private const val STATE_REFUNDED = 60
        // Ограничение Robokassa на Description.
        private const val DESCRIPTION_MAX = 100

        /** OutSum — рубли с двумя знаками через точку («199.00»). */
        fun formatOutSum(kopecks: Int): String = "%d.%02d".format(kopecks / 100, kopecks % 100)

        /** ResultURL присылает OutSum с произвольным числом знаков («199.000000»). */
        fun parseKopecks(outSum: String): Int =
            BigDecimal(outSum.trim()).movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValueExact()

        /** Без спецсимволов (требование провайдера) и не длиннее лимита. */
        fun sanitizeDescription(raw: String): String =
            raw.replace(Regex("[^\\p{L}\\p{N} .,:;()!?-]"), "").replace(Regex("\\s+"), " ").trim().take(DESCRIPTION_MAX)
    }
}
