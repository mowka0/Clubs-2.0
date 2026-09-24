package com.clubs.bot

import com.clubs.common.util.Money
import com.clubs.generated.jooq.enums.SubscriptionPlan
import com.clubs.subscription.SubscriptionRepository
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow
import org.telegram.telegrambots.meta.api.objects.webapp.WebAppInfo

/**
 * Обязательная информация для продажи внутри Telegram. Robokassa принимает магазином ссылку на
 * бота вместо сайта (звонок PO 2026-09-19), но требует, чтобы описание услуги с ценой, реквизиты и
 * контакты продавца, условия, оферта и политика были на том же ресурсе, где идёт продажа. Поэтому
 * всё живёт в сообщениях бота, а не по ссылке на сайт, который из РФ без VPN может не открыться.
 *
 * Экраны «шторки»: стартовый блок, оферта, политика постранично — и клавиатуры к ним. Бот только
 * шлёт и правит сообщения (транспорт), как с ростером и сборами.
 *
 * Оферта генерируется из `docs/legal/oferta.md` (`OfferSections.kt`, см. `scripts/gen-oferta.py`) и
 * совпадает с фронтом. Политика дублирует `frontend/src/pages/privacyText.ts` руками: сборки фронта
 * и бэка изолированы (разные Docker-контексты). Намеренные отличия политики от фронта: в п. 4 и
 * п. 10 упомянут этот бот и команда /terms; контакты разделены запятой, не «или».
 */
@Component
class LegalSheet(
    @Value("\${billing.recipient-name:}") private val recipientName: String,
    @Value("\${billing.recipient-inn:}") private val recipientInn: String,
    @Value("\${billing.provider}") private val billingProvider: String,
    @Value("\${telegram.support-username}") private val supportUsername: String,
    @Value("\${telegram.support-email:}") private val supportEmail: String,
    @Value("\${telegram.webapp-base-url}") private val webAppBaseUrl: String,
    @Value("\${billing.trial-days}") private val trialDays: Int,
    private val subscriptionRepository: SubscriptionRepository,
) {
    companion object {
        /** Лимит Bot API на текст одного сообщения; оферта и политика режутся на страницы под него. */
        const val TELEGRAM_TEXT_LIMIT = 4096

        /** Запас под подпись «Страница N из M», которая дописывается к странице после разбиения. */
        const val PAGE_FOOTER_RESERVE = 40

        /** Дата редакции политики — та же, что на сайте (`privacyText.ts`). */
        const val PRIVACY_UPDATED = "17 сентября 2026 года"

        /** Префикс callback-кнопок: `legal:info` | `legal:offer:<страница>` | `legal:privacy:<страница>`. */
        const val CALLBACK_PREFIX = "legal:"

        /** Алерт, когда сообщение не удалось поправить (переслано, недоступно, flood-wait). */
        const val EDIT_FAILED_ALERT = "Не получилось открыть здесь — отправь боту /terms."
    }

    private val log = LoggerFactory.getLogger(LegalSheet::class.java)

    /** Текст и кнопки одного вида «шторки». */
    data class Screen(val text: String, val markup: InlineKeyboardMarkup)

    /**
     * Без ФИО и ИНН бот молча покажет продавца без реквизитов, а для Robokassa это обязательное
     * условие модерации — заметно только глазами модератора, поэтому шумим при старте.
     */
    @PostConstruct
    fun warnIfRequisitesMissing() {
        if (billingProvider == "robokassa" && (recipientName.isBlank() || recipientInn.isBlank())) {
            log.warn("BILLING_RECIPIENT_NAME / BILLING_RECIPIENT_INN не заданы: стартовое сообщение бота без реквизитов продавца")
        }
    }

    /** Стартовое сообщение с кнопками — ответ на /start и /terms. */
    fun startScreen(): Screen = Screen(infoBlock(), infoKeyboard())

    /**
     * Экран по действию кнопки без префикса: `offer:<n>` (голое `offer` — старые кнопки), `privacy:<n>`,
     * иначе стартовый. Номер
     * страницы из callback — пользовательский ввод: мусор и выход за диапазон прижимаются к валидному.
     */
    fun render(action: String, limit: Int = TELEGRAM_TEXT_LIMIT): Screen = when {
        // «offer» без номера — кнопки в уже отправленных сообщениях (до постраничной оферты).
        action == "offer" || action.startsWith("offer:") -> paged("offer", action.substringAfter(':', ""), offerPages(limit))
        action.startsWith("privacy:") -> paged("privacy", action.removePrefix("privacy:"), privacyPages(limit))
        else -> startScreen()
    }

    private fun paged(kind: String, pageText: String, pages: List<String>): Screen {
        val page = pageText.toIntOrNull()?.coerceIn(0, pages.size - 1) ?: 0
        return Screen(pages[page], pagesKeyboard(kind, page, pages.size))
    }

    private fun callback(text: String, action: String) =
        InlineKeyboardButton.builder().text(text).callbackData(CALLBACK_PREFIX + action).build()

    private fun backRow() = InlineKeyboardRow(callback("← Назад", "info"))

    /**
     * Кнопка Mini App — прямой URL приложения, как у всех WebApp-кнопок бота: формат
     * `t.me/<бот>/app` требует регистрации short name через /newapp (на staging давал «Bot App Not Found»).
     */
    private fun infoKeyboard() = InlineKeyboardMarkup(listOf(
        InlineKeyboardRow(InlineKeyboardButton.builder().text("\uD83C\uDFE0 Открыть Clubs").webApp(WebAppInfo(webAppBaseUrl)).build()),
        // Три документные кнопки одним рядом (PO): подписи короткие, чтобы влезали на телефоне.
        InlineKeyboardRow(
            callback("\uD83D\uDCC4 Оферта", "offer"),
            callback("\uD83D\uDD12 Политика", "privacy:0"),
            InlineKeyboardButton.builder().text("\uD83D\uDCAC Поддержка").url("https://t.me/$supportUsername").build(),
        ),
    ))

    private fun pagesKeyboard(kind: String, page: Int, pages: Int): InlineKeyboardMarkup {
        val nav = mutableListOf<InlineKeyboardButton>()
        if (page > 0) nav += callback("‹ Стр. $page", "$kind:${page - 1}")
        if (page < pages - 1) nav += callback("Стр. ${page + 2} ›", "$kind:${page + 1}")
        // Пустой ряд кнопок Bot API отвергает, поэтому навигация добавляется только когда есть куда.
        return InlineKeyboardMarkup(listOfNotNull(nav.takeIf { it.isNotEmpty() }?.let { InlineKeyboardRow(it) }, backRow()))
    }

    private val seller: String
        get() {
            val name = recipientName.ifBlank { "исполнитель" }
            val inn = recipientInn.takeIf { it.isNotBlank() }?.let { ", ИНН $it" } ?: ""
            return "самозанятый $name$inn"
        }

    private val contacts: String
        get() = "@$supportUsername" + supportEmail.takeIf { it.isNotBlank() }?.let { ", $it" }.orEmpty()

    // Цена читается при каждом показе: в оферте она юридически значима, кэш дал бы устаревшую.
    private fun priceLabel(): String =
        Money.rub(subscriptionRepository.currentPriceKopecks(SubscriptionPlan.CHAT).toLong())

    /**
     * Стартовое сообщение: позиционирование PO 2026-09-20 — «прокачай чат до настоящего клуба», живым
     * тоном (встречи, сборы, повод видеться чаще, память и статистика, лицо клуба) — плюс всё, что
     * Robokassa требует видеть
     * до покупки — цена, условия, продавец, контакты.
     */
    fun infoBlock(): String = listOf(
        "👋 Привет, это Clubs — бот, который должен быть в каждом чате!",
        "🗓 Встречи собираются сами. Создал — бот спросил «кто идёт», напомнил и подвёл итог. " +
            "Никаких уговоров по сто раз на дню.",
        "💸 Деньги больше не в голове. Сходили в ресторан — поделили счёт на всех прямо в приложении. " +
            "Скинуться на подарок или мероприятие — в пару нажатий.",
        "🔥 Видеться чаще станет проще. Когда собрать людей легко, встречи случаются, а не откладываются «на потом».",
        "📸 Ничего не пропадёт. История встреч и статистика клуба остаются с вами: есть что вспомнить и чем гордиться.",
        "✨ У чата появится лицо. Создай свой клуб со своими правилами и темами — это ваш клуб, а не «ещё один чат».",
        "Прокачай свой чат — пусть это будет настоящий клуб, а не просто место для переписки!",
        "💳 Первые ${days(trialDays)} после первой встречи — бесплатно, дальше ${priceLabel()} в месяц за клуб.\n" +
            "👤 Продавец: $seller.\n" +
            "✉️ Поддержка: $contacts",
    ).joinToString("\n\n")

    /** «1 день», «2 дня», «15 дней»: staging живёт с trial-days=1, и «1 дней» в витрине выглядит опечаткой. */
    private fun days(n: Int): String {
        val form = when {
            n % 100 in 11..14 -> "дней"
            n % 10 == 1 -> "день"
            n % 10 in 2..4 -> "дня"
            else -> "дней"
        }
        return "$n $form"
    }

    /**
     * Публичная оферта постранично. Текст — из `OfferSections.kt`, который генерирует
     * `scripts/gen-oferta.py` из `docs/legal/oferta.md` (та же генерация даёт копию фронту).
     * Преамбула (исполнитель, сайт) — первый раздел без номера; дата редакции — в шапке каждой страницы.
     */
    fun offerPages(limit: Int = TELEGRAM_TEXT_LIMIT): List<String> {
        val sections = offerSections(
            recipientName = recipientName.ifBlank { "исполнитель" },
            inn = recipientInn.ifBlank { "не указан" },
            priceLabel = priceLabel(),
            trialDaysLabel = days(trialDays),
            supportTelegram = "@$supportUsername",
            supportEmail = supportEmail.ifBlank { "—" },
        )
        val (docTitle, preamble) = sections.first()
        val header = "📄 $docTitle\nРедакция от $OFFER_UPDATED"
        val body = listOf(preamble.joinToString("\n")) +
            sections.drop(1).map { (title, paragraphs) -> (listOf(title) + paragraphs).joinToString("\n") }
        return paginate(header, body, limit)
    }

    /**
     * Политика обработки персональных данных постранично: каждая страница влезает в одно сообщение.
     * Сейчас текст короче лимита и страница одна; разбиение — страховка на случай роста текста.
     */
    fun privacyPages(limit: Int = TELEGRAM_TEXT_LIMIT): List<String> {
        val header = "🔒 Политика обработки персональных данных\nРедакция от $PRIVACY_UPDATED"
        val sections = privacySections(seller, contacts).map { (title, paragraphs) -> (listOf(title) + paragraphs).joinToString("\n") }
        return paginate(header, sections, limit)
    }

    /** Режет разделы на страницы под лимит сообщения, не разрывая раздел; подпись «Страница N из M». */
    private fun paginate(header: String, sections: List<String>, limit: Int): List<String> {
        val pages = mutableListOf<String>()
        var current = header
        for (section in sections) {
            // Раздел, не влезающий в страницу, Telegram отверг бы целиком — лучше упасть в тесте.
            check(section.length <= limit - PAGE_FOOTER_RESERVE - header.length - 16) {
                "раздел длиннее страницы: ${section.take(40)}…"
            }
            val candidate = "$current\n\n$section"
            if (candidate.length > limit - PAGE_FOOTER_RESERVE) {
                pages += current
                current = "$header (продолжение)\n\n$section"
            } else {
                current = candidate
            }
        }
        pages += current
        return pages.mapIndexed { i, page -> "$page\n\nСтраница ${i + 1} из ${pages.size}" }
    }

}
