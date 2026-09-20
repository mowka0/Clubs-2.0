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
 * Оферта и политика дублируют `frontend/src/components/billing/offerText.ts` и
 * `frontend/src/pages/privacyText.ts`: сборки фронта и бэка изолированы (разные Docker-контексты),
 * общий файл невозможен. Правишь слова — правь в обоих местах. Намеренные отличия от фронта:
 * в п. 5 оферты добавлены контакты поддержки (в боте нет кнопки «Сообщить о проблеме»); в п. 4 и
 * п. 10 политики упомянут этот бот и команда /terms; контакты разделены запятой, не «или».
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
        /** Лимит Bot API на текст одного сообщения; политика режется на страницы под него. */
        const val TELEGRAM_TEXT_LIMIT = 4096

        /** Запас под подпись «Страница N из M», которая дописывается к странице после разбиения. */
        const val PAGE_FOOTER_RESERVE = 40

        /** Дата редакции политики — та же, что на сайте (`privacyText.ts`). */
        const val PRIVACY_UPDATED = "17 сентября 2026 года"

        /** Префикс callback-кнопок: `legal:info` | `legal:offer` | `legal:privacy:<страница>`. */
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
     * Экран по действию кнопки без префикса: `offer`, `privacy:<n>`, иначе стартовый. Номер
     * страницы из callback — пользовательский ввод: мусор и выход за диапазон прижимаются к валидному.
     */
    fun render(action: String, limit: Int = TELEGRAM_TEXT_LIMIT): Screen = when {
        action == "offer" -> Screen(offer(), InlineKeyboardMarkup(listOf(backRow())))
        action.startsWith("privacy:") -> {
            val pages = privacyPages(limit)
            val page = action.removePrefix("privacy:").toIntOrNull()?.coerceIn(0, pages.size - 1) ?: 0
            Screen(pages[page], privacyKeyboard(page, pages.size))
        }
        else -> startScreen()
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

    private fun privacyKeyboard(page: Int, pages: Int): InlineKeyboardMarkup {
        val nav = mutableListOf<InlineKeyboardButton>()
        if (page > 0) nav += callback("‹ Стр. $page", "privacy:${page - 1}")
        if (page < pages - 1) nav += callback("Стр. ${page + 2} ›", "privacy:${page + 1}")
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
     * Стартовое сообщение: выгоды для владельца клуба (PO 2026-09-20: «не надо всё запоминать,
     * договариваться по сто раз, держать долги в голове, легче собирать всех — повод видеться чаще»)
     * плюс всё, что Robokassa требует видеть
     * до покупки — цена, условия, продавец, контакты.
     */
    fun infoBlock(): String = listOf(
        "👋 Привет! Clubs — помощник для клубов по интересам внутри Telegram.",
        "Подключи бота к чату клуба, и он возьмёт рутину на себя:\n" +
            "• встречи и опрос «кто идёт» — больше не нужно договариваться по сто раз на дню;\n" +
            "• напоминания и итог явки — ничего не нужно запоминать и записывать;\n" +
            "• сборы и долги участников — кто скинулся и кто должен, видно в одном месте, а не в голове;\n" +
            "• собрать всех на любую встречу проще — и повод видеться чаще.",
        "💳 Стоимость: первые $trialDays дней после первой встречи — бесплатно, дальше ${priceLabel()} за " +
            "30 дней для одного клуба (чата). Оплата картой или СБП через Robokassa внутри приложения, чек " +
            "приходит автоматически.",
        "📄 Условия: доступ открывается сразу после подтверждения оплаты. Отказаться от продления можно в " +
            "любой момент на странице клуба — доступ сохранится до конца оплаченного периода. Вопросы по " +
            "возврату — в поддержку, ответ в течение 3 рабочих дней. Подробно — в оферте.",
        "👤 Продавец: $seller. Налог на профессиональный доход, без НДС.",
        "✉️ Связь: $contacts",
        "Оферта и политика данных — по кнопкам ниже, прямо здесь. Команда /terms покажет это сообщение снова.",
    ).joinToString("\n\n")

    /** Публичная оферта — тот же текст, что в шите оплаты Mini App (`offerText.ts`). */
    fun offer(): String = listOf(
        "📄 Условия (публичная оферта)",
        "1. Исполнитель ($seller) предоставляет владельцу клуба доступ к функциям сервиса Clubs для клуба, " +
            "привязанного к чату Telegram: ведение встреч ботом, опросы, сбор ответов, напоминания и итог явки.",
        "2. Стоимость доступа — ${priceLabel()} за 30 дней с момента подтверждения оплаты. Оплата проходит через " +
            "платёжный сервис Robokassa. Исполнитель применяет налог на профессиональный доход, НДС не облагается; " +
            "чек формируется автоматически и приходит на e-mail или в Telegram.",
        "3. При включённом автопродлении в день окончания оплаченного периода списывается та же сумма с " +
            "сохранённой карты. Отключить автопродление можно в любой момент на странице клуба — доступ " +
            "сохраняется до конца оплаченного периода.",
        "4. Если оплата не поступила, в течение 7 дней после окончания периода сервис работает без ограничений; " +
            "затем становится недоступным создание новых встреч, уже начатые встречи и данные клуба сохраняются.",
        "5. Оплата означает принятие этих условий. Вопросы по оплате, чекам и возвратам — через «Сообщить о " +
            "проблеме» в приложении или $contacts; ответ в течение 3 рабочих дней.",
    ).joinToString("\n\n")

    /**
     * Политика обработки персональных данных постранично: каждая страница влезает в одно сообщение.
     * Сейчас текст короче лимита и страница одна; разбиение — страховка на случай роста текста.
     */
    fun privacyPages(limit: Int = TELEGRAM_TEXT_LIMIT): List<String> {
        val header = "🔒 Политика обработки персональных данных\nРедакция от $PRIVACY_UPDATED"
        val sections = privacySections().map { (title, paragraphs) ->
            (listOf(title) + paragraphs).joinToString("\n")
        }
        val pages = mutableListOf<String>()
        var current = header
        for (section in sections) {
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

    private fun privacySections(): List<Pair<String, List<String>>> = listOf(
        "1. Оператор" to listOf(
            "Оператором персональных данных является $seller (далее — «мы»). Сервис Clubs работает как " +
                "приложение и бот внутри Telegram и помогает сообществам проводить офлайн-встречи.",
            "Вопросы об обработке данных, отзыв согласия и запросы на удаление — $contacts. Ответ в течение " +
                "трёх рабочих дней.",
        ),
        "2. Какие данные мы обрабатываем" to listOf(
            "Из Telegram при первом входе: идентификатор аккаунта, имя и фамилия, имя пользователя, язык " +
                "интерфейса и фотография профиля, если она открыта. Телефон и переписку мы не получаем.",
            "То, что вы вводите сами: город, описание клуба, темы, названия и адреса встреч, комментарии, " +
                "фотографии, которые вы загружаете.",
            "То, что возникает при пользовании: участие во встречах и отметки явки, репутация и уровень, " +
                "членство в клубах, суммы сборов и отметки об оплате взносов между участниками.",
            "Технические данные: IP-адрес и время запроса в журналах сервера — они нужны для защиты от " +
                "перебора и разбора сбоев.",
            "Платёжные данные мы не получаем и не храним: реквизиты карты вводятся на стороне платёжного " +
                "сервиса Robokassa. Нам возвращается только факт оплаты, её сумма, способ (карта или СБП) и " +
                "номер счёта.",
        ),
        "3. Зачем мы их обрабатываем" to listOf(
            "Чтобы предоставить сам сервис: опознать вас при входе, показать клубы и встречи, собрать «кто " +
                "идёт», прислать напоминания, посчитать явку и репутацию, вести сборы.",
            "Чтобы принимать оплату подписки за клуб и формировать чек — этого требует закон о налоге на " +
                "профессиональный доход.",
            "Чтобы отвечать на обращения в поддержку и разбирать сбои.",
        ),
        "4. На каком основании" to listOf(
            "Обработка нужна для исполнения договора с вами — публичной оферты, условия которой доступны в " +
                "этом боте и на сайте, — и для соблюдения требований закона в части чеков и налогов. Начиная " +
                "пользоваться сервисом, вы даёте согласие на обработку перечисленных данных.",
        ),
        "5. Кому передаём" to listOf(
            "Telegram — как площадке, внутри которой работает сервис: сообщения бота, кнопки и приложение " +
                "доставляются через неё.",
            "Robokassa — как платёжному сервису: при оплате передаются сумма, номер счёта и назначение " +
                "платежа. Оператор фискальных данных формирует чек по требованиям закона.",
            "Другим участникам вашего клуба видны имя, фотография профиля, участие во встречах, репутация и " +
                "долги по сборам внутри этого клуба — в этом и состоит работа сервиса.",
            "Мы не продаём персональные данные и не передаём их третьим лицам для рекламы.",
        ),
        "6. Где хранятся данные" to listOf(
            "Данные хранятся и обрабатываются на серверах, расположенных на территории Российской Федерации. " +
                "Доступ к ним есть только у оператора.",
        ),
        "7. Сколько храним и как удалить" to listOf(
            "Данные хранятся, пока вы пользуетесь сервисом. Вы можете выйти из клубов, а владелец — удалить " +
                "клуб; при удалении клуба удаляются его встречи, сборы и участия.",
            "Чтобы удалить профиль целиком, напишите нам: $contacts. Мы удалим данные в течение 30 дней, кроме " +
                "сведений, которые обязаны хранить по закону — например, сведений о состоявшихся платежах и чеках.",
        ),
        "8. Ваши права" to listOf(
            "Вы вправе узнать, какие ваши данные мы обрабатываем, потребовать их исправления или удаления, " +
                "отозвать согласие. Отзыв согласия означает прекращение пользования сервисом: без " +
                "идентификатора Telegram приложение работать не может.",
            "Для любого из этих обращений напишите $contacts.",
        ),
        "9. Cookie и аналитика" to listOf(
            "Рекламных и аналитических систем слежения на сайте и в приложении нет. Браузер и Telegram " +
                "сохраняют на вашем устройстве технические значения — например, выбранную тему оформления и " +
                "признак входа; они не передаются третьим лицам.",
        ),
        "10. Изменения" to listOf(
            "Мы можем обновлять эту политику. Действующая редакция всегда доступна по команде /terms в этом " +
                "боте и по адресу clubsapp.ru/privacy; дата последнего изменения указана в начале текста.",
        ),
    )
}
