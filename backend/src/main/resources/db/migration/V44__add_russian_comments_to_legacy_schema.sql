-- V44: Ретро-докрытие русских комментариев схемы (COMMENT ON TABLE / COMMENT ON COLUMN).
--
-- Исторические миграции V1–V36 создавали таблицы без COMMENT ON (правило DoD про обязательные
-- русские комментарии появилось позже, с V37+). Эта миграция закрывает пробел: каждая таблица
-- и каждая колонка, у которых комментария ещё нет, получают описание на русском — чтобы `\d+`
-- в psql читался без угадывания и без чтения кода.
--
-- Структуру НЕ меняет: только COMMENT ON (метаданные каталога). Уже существующие комментарии
-- (memberships.status и de-Stars-колонки из V37/V38/V41, clubs.payment_link/payment_method_note
-- из V39, club_awards из V40) НЕ переопределяются — они остаются как есть.
--
-- Семантика выверена по фактическому коду (ReputationPolicy, Stage2Service, JooqMembershipRepository,
-- SubscriptionPlanPolicy и т.д.) и docs/modules/*.md на момент V43.

-- ============================================================================
-- users — Telegram-профили пользователей
-- ============================================================================

COMMENT ON TABLE users IS
    'Пользователи: Telegram-профили, создаются и синхронизируются при авторизации через подписанный initData Mini App.';
COMMENT ON COLUMN users.id IS 'Суррогатный первичный ключ (UUID).';
COMMENT ON COLUMN users.telegram_id IS 'Telegram ID пользователя (уникальный). Главный идентификатор при авторизации: по нему находим/создаём профиль.';
COMMENT ON COLUMN users.telegram_username IS 'Username в Telegram, без @ (NULL = не задан или скрыт настройками Telegram). Обновляется при каждом логине.';
COMMENT ON COLUMN users.first_name IS 'Имя из Telegram-профиля (обязательное — Telegram всегда его отдаёт).';
COMMENT ON COLUMN users.last_name IS 'Фамилия из Telegram-профиля (NULL = не указана в Telegram).';
COMMENT ON COLUMN users.avatar_url IS 'URL аватара пользователя (NULL = нет аватара).';
COMMENT ON COLUMN users.city IS 'Город пользователя из редактируемого профиля, свободный текст (NULL = не указан).';
COMMENT ON COLUMN users.country IS 'Страна пользователя из редактируемого профиля, короткий код до 8 символов (NULL = не указана).';
COMMENT ON COLUMN users.bio IS 'Короткое «о себе» из профиля, до 280 символов (NULL = не заполнено). Показывается на карточке участника.';
COMMENT ON COLUMN users.created_at IS 'Когда профиль создан (первый вход в приложение).';
COMMENT ON COLUMN users.updated_at IS 'Когда профиль последний раз обновлялся (синхронизация при логине или правка профиля).';

-- ============================================================================
-- clubs — клубы (офлайн-сообщества)
-- ============================================================================

COMMENT ON TABLE clubs IS
    'Клубы — платные или бесплатные офлайн-сообщества по интересам. Ядро продукта: членство, события и складчины привязаны к клубу.';
COMMENT ON COLUMN clubs.id IS 'Суррогатный первичный ключ (UUID).';
COMMENT ON COLUMN clubs.owner_id IS 'Владелец-организатор клуба (FK users.id). Его собственная строка в memberships имеет role = organizer; репутацию в своём клубе он не копит (анти-фарм).';
COMMENT ON COLUMN clubs.name IS 'Название клуба, до 60 символов.';
COMMENT ON COLUMN clubs.description IS 'Описание клуба, до 500 символов.';
COMMENT ON COLUMN clubs.category IS 'Категория клуба (enum club_category): sport = спорт, creative = творчество, food = еда, board_games = настольные игры, cinema = кино, education = образование, travel = путешествия, other = другое. Задаётся один раз при создании (пути редактирования нет); используется в discovery-фильтрах и категорийном лидерборде L3.';
COMMENT ON COLUMN clubs.access_type IS 'Тип доступа (enum access_type): open = вступление свободное в один тап; closed = вступление по заявке с одобрением организатора; private = клуб не виден в каталоге, вступление только по invite-ссылке.';
COMMENT ON COLUMN clubs.city IS 'Город клуба (обязателен) — основной discovery-фильтр.';
COMMENT ON COLUMN clubs.district IS 'Район города (NULL = не указан). Уточнение к city.';
COMMENT ON COLUMN clubs.member_limit IS 'Лимит числа участников клуба, от 10 до 80 (CHECK).';
COMMENT ON COLUMN clubs.subscription_price IS 'Месячный членский взнос в рублях (0 = бесплатный клуб). После de-Stars деньги идут участник -> организатор мимо платформы (СБП-реквизиты в payment_link); поле задаёт размер взноса и признак «платный клуб» (влияет на ёмкость организаторского плана).';
COMMENT ON COLUMN clubs.avatar_url IS 'URL аватара/обложки клуба (NULL = нет).';
COMMENT ON COLUMN clubs.rules IS 'Правила клуба, свободный текст (NULL = не заданы).';
COMMENT ON COLUMN clubs.application_question IS 'Вопрос анкеты для заявки в closed-клуб, до 200 символов (NULL = вступление без вопроса). Ответ хранится в applications.answer_text.';
COMMENT ON COLUMN clubs.invite_link IS 'Уникальный invite-код для вступления по ссылке (NULL = ещё не сгенерирован). Единственный способ входа в private-клуб.';
COMMENT ON COLUMN clubs.telegram_group_id IS 'ID привязанной Telegram-группы клуба (NULL = группа не привязана). Привязывается организатором через бота.';
COMMENT ON COLUMN clubs.is_active IS 'FALSE = клуб деактивирован: скрыт из каталога и из поиска по invite-коду.';
COMMENT ON COLUMN clubs.created_at IS 'Когда клуб создан.';
COMMENT ON COLUMN clubs.updated_at IS 'Когда клуб последний раз редактировался.';

-- ============================================================================
-- memberships — членство пользователя в клубе
-- (status и de-Stars-колонки уже прокомментированы в V37/V38/V41)
-- ============================================================================

COMMENT ON TABLE memberships IS
    'Членство пользователя в клубе: статусный жизненный цикл (active/frozen/cancelled + легаси grace_period/expired), роль и учёт внеплатформенного взноса (de-Stars). Одна строка на пару (user, club).';
COMMENT ON COLUMN memberships.id IS 'Суррогатный первичный ключ (UUID).';
COMMENT ON COLUMN memberships.user_id IS 'Участник (FK users.id). Пара (user_id, club_id) уникальна.';
COMMENT ON COLUMN memberships.club_id IS 'Клуб (FK clubs.id).';
COMMENT ON COLUMN memberships.role IS 'Роль в клубе (enum membership_role): member = обычный участник; organizer = владелец клуба (его собственная строка членства, создаётся вместе с клубом).';
COMMENT ON COLUMN memberships.joined_at IS 'Когда пользователь вступил в клуб (момент создания членства).';
COMMENT ON COLUMN memberships.subscription_expires_at IS 'Конец оплаченного окна доступа из легаси Stars-биллинга (NULL = оплаченного окна нет, в т.ч. бесплатный клуб). После de-Stars НЕ управляет доступом (доступ ведёт организатор через status = frozen); сохраняется для ветки «вышел, но оплачено до даты» и истории.';
COMMENT ON COLUMN memberships.created_at IS 'Когда строка членства создана.';
COMMENT ON COLUMN memberships.updated_at IS 'Когда членство последний раз менялось (статус, заморозка, отметка взноса и т.п.).';

-- ============================================================================
-- applications — заявки на вступление в closed-клубы
-- ============================================================================

COMMENT ON TABLE applications IS
    'Заявки на вступление в клубы с access_type = closed. Активная (pending/approved) заявка — максимум одна на пару (user, club); терминальные могут повторяться при повторных подачах.';
COMMENT ON COLUMN applications.id IS 'Суррогатный первичный ключ (UUID).';
COMMENT ON COLUMN applications.user_id IS 'Податель заявки (FK users.id).';
COMMENT ON COLUMN applications.club_id IS 'Клуб, в который подана заявка (FK clubs.id).';
COMMENT ON COLUMN applications.answer_text IS 'Ответ подателя на вопрос анкеты клуба (clubs.application_question). NULL = вопрос не задан или ответ не требовался.';
COMMENT ON COLUMN applications.status IS 'Статус заявки (enum application_status): pending = ждёт решения организатора; approved = одобрена (создано членство); rejected = отклонена организатором (причина в rejected_reason); auto_rejected = авто-отклонение планировщиком через 48 часов без ответа; cancelled = отозвана самим подателем до решения.';
COMMENT ON COLUMN applications.rejected_reason IS 'Причина отказа, которую видит податель (NULL = не указана или заявка не отклонялась).';
COMMENT ON COLUMN applications.created_at IS 'Когда заявка подана.';
COMMENT ON COLUMN applications.resolved_at IS 'Когда заявка перешла в терминальный статус — одобрена/отклонена/отозвана (NULL = ещё pending).';

-- ============================================================================
-- events — события клуба (двухэтапное подтверждение участия)
-- ============================================================================

COMMENT ON TABLE events IS
    'События клуба с двухэтапным подтверждением участия: Этап 1 — голосование «пойду/может быть», Этап 2 (~за 24 часа) — подтверждение брони в пределах лимита, затем отметка явки организатором и начисление репутации.';
COMMENT ON COLUMN events.id IS 'Суррогатный первичный ключ (UUID).';
COMMENT ON COLUMN events.club_id IS 'Клуб, которому принадлежит событие (FK clubs.id).';
COMMENT ON COLUMN events.created_by IS 'Организатор, создавший событие (FK users.id).';
COMMENT ON COLUMN events.title IS 'Название события, до 255 символов.';
COMMENT ON COLUMN events.description IS 'Описание события (NULL = без описания).';
COMMENT ON COLUMN events.location_text IS 'Место встречи свободным текстом, до 500 символов.';
COMMENT ON COLUMN events.event_datetime IS 'Дата и время начала события.';
COMMENT ON COLUMN events.participant_limit IS 'Лимит участников (> 0). На Этапе 2 подтвердившиеся сверх лимита попадают в лист ожидания (waitlisted).';
COMMENT ON COLUMN events.voting_opens_days_before IS 'За сколько дней до события открывается голосование Этапа 1 (от 1 до 14, дефолт 14). Окно: event_datetime - N дней <= now.';
COMMENT ON COLUMN events.status IS 'Статус события (enum event_status): upcoming = создано, Этап-1 голосование идёт в своём окне; stage_1 = зарезервировано и фактически не проставляется (Этап 1 живёт в upcoming); stage_2 = открыт Этап 2 — подтверждение брони; completed = событие прошло (проставляется после event_datetime); cancelled = отменено организатором.';
COMMENT ON COLUMN events.stage_2_triggered IS 'TRUE = Этап 2 уже запускался (дедуп триггера, срабатывающего ~за 24 часа до начала, events.stage2-trigger-minutes-before).';
COMMENT ON COLUMN events.attendance_marked IS 'TRUE = организатор отметил явку участников. Открывает окно оспаривания отметок.';
COMMENT ON COLUMN events.attendance_finalized IS 'TRUE = явка финализирована: окно оспаривания закрыто, исходы переданы в репутационный конвейер. Отметки после этого не меняются.';
COMMENT ON COLUMN events.created_at IS 'Когда событие создано.';
COMMENT ON COLUMN events.updated_at IS 'Когда событие последний раз менялось.';
COMMENT ON COLUMN events.photo_url IS 'URL фото события для ленты активностей (NULL = без фото). Зеркалит skladchinas.photo_url.';
COMMENT ON COLUMN events.reputation_processed IS 'Идемпотентный маркер: по событию уже начислена репутация. Захватывается атомарным условным UPDATE, чтобы событийный listener и часовой поллер не обработали событие дважды.';
COMMENT ON COLUMN events.confirm_reminder_sent IS 'TRUE = DM-напоминание «подтверди участие» (~за 2 часа до события, голосовавшим going/maybe без подтверждения) уже отправлено — дедуп планировщика.';
COMMENT ON COLUMN events.attendance_reminder_sent IS 'TRUE = DM-напоминание организатору «отметь явку» (~через 24 часа после события) уже отправлено — дедуп планировщика.';
COMMENT ON COLUMN events.attendance_marked_at IS 'Когда организатор фактически отметил явку (NULL = не отмечена). От этого момента отсчитывается окно оспаривания — защита участника от поздней отметки.';
COMMENT ON COLUMN events.cancellation_reason IS 'Причина отмены, указанная организатором; показывается заинтересованным участникам в DM и на странице события (NULL = причина не указана).';

-- ============================================================================
-- event_responses — отклики участников на событие
-- ============================================================================

COMMENT ON TABLE event_responses IS
    'Отклики участников на событие: голос Этапа 1, подтверждение Этапа 2, финальный статус и отметка явки. Одна строка на пару (event, user).';
COMMENT ON COLUMN event_responses.id IS 'Суррогатный первичный ключ (UUID).';
COMMENT ON COLUMN event_responses.event_id IS 'Событие (FK events.id). Пара (event_id, user_id) уникальна.';
COMMENT ON COLUMN event_responses.user_id IS 'Участник (FK users.id).';
COMMENT ON COLUMN event_responses.stage_1_vote IS 'Голос Этапа 1 (enum stage_1_vote): going = «пойду», maybe = «может быть», not_going = «не пойду». NULL = ещё не голосовал.';
COMMENT ON COLUMN event_responses.stage_1_timestamp IS 'Когда отдан голос Этапа 1 (NULL = не голосовал). Задаёт порядок очереди при продвижении из листа ожидания.';
COMMENT ON COLUMN event_responses.stage_2_vote IS 'Действие Этапа 2 (enum stage_2_vote): confirmed = подтвердил бронь; declined = отказался; waitlisted = в листе ожидания (лимит исчерпан); expired_no_confirm = бронь сгорела — не подтвердил до начала события (авто-статус, отличается от явного отказа). NULL = ещё не действовал на Этапе 2.';
COMMENT ON COLUMN event_responses.stage_2_timestamp IS 'Когда совершено действие Этапа 2 (NULL = не действовал).';
COMMENT ON COLUMN event_responses.final_status IS 'Итоговый статус участия (enum final_status): confirmed = в финальном списке (только по нему начисляется репутация); waitlisted = остался в листе ожидания; declined = отказался; expired_no_confirm = не подтвердил до начала. NULL = итог не определён.';
COMMENT ON COLUMN event_responses.attendance IS 'Отметка явки организатором (enum attendance_status): attended = пришёл; absent = не пришёл; disputed = участник оспорил отметку (спор решает организатор). NULL = явка не отмечена.';
COMMENT ON COLUMN event_responses.attendance_finalized IS 'Легаси-флаг финализации на уровне отдельного отклика; фактически не используется — финализация отслеживается на уровне события (events.attendance_finalized).';
COMMENT ON COLUMN event_responses.created_at IS 'Когда отклик создан (первый голос).';
COMMENT ON COLUMN event_responses.updated_at IS 'Когда отклик последний раз менялся.';
COMMENT ON COLUMN event_responses.dispute_note IS 'Комментарий участника при оспаривании отметки явки; показывается организатору в блоке «Оспоренные отметки» (NULL = без комментария).';
COMMENT ON COLUMN event_responses.dispute_terminal IS 'TRUE = организатор окончательно разрешил спор в absent — повторное оспаривание закрыто (защита от пинг-понга споров).';

-- ============================================================================
-- user_club_reputation — производный кэш пер-клубной репутации
-- ============================================================================

COMMENT ON TABLE user_club_reputation IS
    'Производный кэш пер-клубной репутации: агрегаты, пересчитываемые из reputation_ledger (единственного источника истины). Одна строка на пару (user, club). Не редактируется вручную — только recompute.';
COMMENT ON COLUMN user_club_reputation.id IS 'Суррогатный первичный ключ (UUID).';
COMMENT ON COLUMN user_club_reputation.user_id IS 'Участник (FK users.id). Пара (user_id, club_id) уникальна.';
COMMENT ON COLUMN user_club_reputation.club_id IS 'Клуб (FK clubs.id). Владелец клуба строк в своём клубе не имеет (анти-фарм правило 1).';
COMMENT ON COLUMN user_club_reputation.reliability_index IS 'Индекс надёжности = сумма points по леджеру за всю историю (может быть отрицательным). UI скрывает число до 3 исходов — показывается «Новичок» (порог презентационный, здесь хранится истинное значение).';
COMMENT ON COLUMN user_club_reputation.promise_fulfillment_pct IS 'Процент сдержанных обещаний по явке: (ironclad + spontaneous) / total_confirmations * 100, два знака после запятой. 0 при отсутствии attendance-исходов.';
COMMENT ON COLUMN user_club_reputation.total_confirmations IS 'Число attendance-исходов в леджере (подтверждённых броней, дошедших до отметки явки).';
COMMENT ON COLUMN user_club_reputation.total_attendances IS 'Число реальных приходов (kind = ironclad или spontaneous).';
COMMENT ON COLUMN user_club_reputation.spontaneity_count IS 'Число «спонтанных» приходов (kind = spontaneous: голосовал maybe, но пришёл). Отображаемая черта характера, на балл не влияет.';
COMMENT ON COLUMN user_club_reputation.created_at IS 'Когда строка кэша создана.';
COMMENT ON COLUMN user_club_reputation.updated_at IS 'Момент последнего пересчёта (при rebuild из леджера = MAX(occurred_at)).';
COMMENT ON COLUMN user_club_reputation.outcome_count IS 'Полное число исходов пользователя в клубе (attendance + finance). Вход порога «Право на ошибку»: < 3 — UI показывает «Новичок» без числа.';
COMMENT ON COLUMN user_club_reputation.kept_count IS 'Число сдержанных обещаний (kind = ironclad, spontaneous, skladchina_paid). Числитель байесовского Trust (P1b); классификация по kind, не по points.';
COMMENT ON COLUMN user_club_reputation.broke_count IS 'Число нарушенных обещаний (kind = no_show, spectator, skladchina_expired).';
COMMENT ON COLUMN user_club_reputation.neutral_count IS 'Число нейтральных исходов (kind = confirmed_unresolved и исторический skladchina_declined) — исключаются из байесовского знаменателя. Инвариант: neutral_count = outcome_count - kept_count - broke_count.';

-- ============================================================================
-- transactions — замороженный леджер платежей Telegram Stars (легаси)
-- ============================================================================

COMMENT ON TABLE transactions IS
    'Легаси-леджер платежей Telegram Stars за членство (монетизация v1). После отказа от Stars (de-Stars) новые строки не пишутся — таблица заморожена как аудит-история; текущая монетизация живёт в service_subscription.';
COMMENT ON COLUMN transactions.id IS 'Суррогатный первичный ключ (UUID).';
COMMENT ON COLUMN transactions.user_id IS 'Плательщик — участник, оплативший подписку (FK users.id).';
COMMENT ON COLUMN transactions.club_id IS 'Клуб, за членство в котором заплачено (FK clubs.id).';
COMMENT ON COLUMN transactions.membership_id IS 'Членство, которое оплатила/продлила транзакция (FK memberships.id; NULL = не привязано, например исторические строки).';
COMMENT ON COLUMN transactions.type IS 'Тип платежа (enum transaction_type): subscription = первичная оплата подписки при вступлении; renewal = продление.';
COMMENT ON COLUMN transactions.status IS 'Статус платежа (enum transaction_status): completed = успешен; failed = не прошёл; refunded = возвращён.';
COMMENT ON COLUMN transactions.amount IS 'Сумма платежа в Telegram Stars (XTR), >= 0.';
COMMENT ON COLUMN transactions.platform_fee IS 'Комиссия платформы в Telegram Stars (XTR), часть amount.';
COMMENT ON COLUMN transactions.organizer_revenue IS 'Доля организатора в Telegram Stars (XTR), часть amount.';
COMMENT ON COLUMN transactions.telegram_payment_charge_id IS 'Идентификатор платежа из Telegram (successful_payment). Частичный UNIQUE-индекс даёт идемпотентность вебхука при ретраях; NULL у исторических/неуспешных строк.';
COMMENT ON COLUMN transactions.created_at IS 'Когда транзакция записана.';

-- ============================================================================
-- skladchinas — складчины (сборы денег внутри клуба)
-- ============================================================================

COMMENT ON TABLE skladchinas IS
    'Складчины — сборы денег внутри клуба (на аренду, инвентарь, деление счёта и т.п.). Honor-system: деньги идут участник -> организатор напрямую (СБП) мимо платформы, приложение ведёт учёт статусов и напоминания.';
COMMENT ON COLUMN skladchinas.id IS 'Суррогатный первичный ключ (UUID).';
COMMENT ON COLUMN skladchinas.club_id IS 'Клуб, в котором объявлен сбор (FK clubs.id).';
COMMENT ON COLUMN skladchinas.creator_id IS 'Создатель сбора — организатор клуба (FK users.id). Только он управляет сбором.';
COMMENT ON COLUMN skladchinas.title IS 'Название сбора, до 255 символов.';
COMMENT ON COLUMN skladchinas.description IS 'Описание — на что собираем (NULL = без описания).';
COMMENT ON COLUMN skladchinas.rules IS 'Условия сбора свободным текстом (NULL = не заданы).';
COMMENT ON COLUMN skladchinas.photo_url IS 'URL фото для карточки сбора в ленте активностей (NULL = без фото).';
COMMENT ON COLUMN skladchinas.payment_mode IS 'Режим взносов (enum skladchina_mode): fixed_equal = цель делится поровну между участниками (доля считается сервером); fixed_individual = организатор задаёт долю каждому участнику; voluntary = добровольные взносы, сумму указывает сам участник.';
COMMENT ON COLUMN skladchinas.total_goal_kopecks IS 'Целевая сумма сбора в КОПЕЙКАХ (NULL = без цели, типично для voluntary). Для fixed_individual = сумма назначенных долей.';
COMMENT ON COLUMN skladchinas.payment_link IS 'Реквизиты для перевода (СБП-ссылка / номер телефона / ссылка банка). Обязательное поле — участнику всегда есть куда платить.';
COMMENT ON COLUMN skladchinas.payment_method_note IS 'Подсказка к реквизитам, например «Тинькофф, СБП по номеру» (NULL = без подсказки).';
COMMENT ON COLUMN skladchinas.deadline IS 'Срок, до которого участник должен ответить (оплатить или отказаться). После дедлайна молчавшие переводятся в expired_no_response.';
COMMENT ON COLUMN skladchinas.affects_reputation IS 'TRUE = исходы участия влияют на финансовую репутацию (paid +10, expired_no_response -40; отказ нейтрален). FALSE = сбор без репутационных последствий.';
COMMENT ON COLUMN skladchinas.status IS 'Статус сбора (enum skladchina_status): active = сбор идёт; closed_success = закрыт успешно (цель достигнута / все ответили); closed_failed = закрыт неуспешно; cancelled = отменён создателем.';
COMMENT ON COLUMN skladchinas.closed_at IS 'Когда сбор закрыт (NULL = ещё активен). Якорь occurred_at для финансовых строк репутационного леджера.';
COMMENT ON COLUMN skladchinas.closed_by IS 'Кто закрыл сбор (FK users.id). NULL = не закрыт либо закрыт автоматически (по дедлайну или достижению цели).';
COMMENT ON COLUMN skladchinas.created_at IS 'Когда сбор создан.';
COMMENT ON COLUMN skladchinas.updated_at IS 'Когда сбор последний раз менялся.';
COMMENT ON COLUMN skladchinas.reminder_sent_at IS 'Когда отправлено DM-напоминание о дедлайне ожидающим участникам, ~за 24 часа (NULL = ещё не отправлялось). Timestamp вместо boolean — момент отправки аудируем; штраф за молчание легитимен только после двух предупреждений.';
COMMENT ON COLUMN skladchinas.template IS 'Шаблон сбора (enum skladchina_template): custom = обычная складчина; split_bill = деление счёта прошедшего события (отказ — только через запрос с одобрением); gear = инвентарь, booking = бронирование, birthday = день рождения — зарезервированы под фазы B-D роадмапа.';
COMMENT ON COLUMN skladchinas.event_id IS 'Событие-источник для template = split_bill — чей счёт делим (FK events.id). NULL для остальных шаблонов.';

-- ============================================================================
-- skladchina_participants — участие членов клуба в складчине
-- ============================================================================

COMMENT ON TABLE skladchina_participants IS
    'Участие члена клуба в складчине: назначенная/заявленная сумма, статус ответа и репутационная отметка. Составной PK (skladchina_id, user_id).';
COMMENT ON COLUMN skladchina_participants.skladchina_id IS 'Сбор (FK skladchinas.id, удаляется каскадно вместе со сбором).';
COMMENT ON COLUMN skladchina_participants.user_id IS 'Участник сбора (FK users.id).';
COMMENT ON COLUMN skladchina_participants.expected_amount_kopecks IS 'Назначенная доля участника в КОПЕЙКАХ: для fixed_equal — расчётная (цель / число участников), для fixed_individual — заданная организатором. NULL для voluntary (доля не назначается).';
COMMENT ON COLUMN skladchina_participants.declared_amount_kopecks IS 'Фактически зафиксированная оплаченная сумма в КОПЕЙКАХ: для voluntary её заявляет участник, для fixed-режимов записывается назначенная доля при отметке оплаты. NULL = оплата не отмечена. Сумма по paid-строкам = собрано.';
COMMENT ON COLUMN skladchina_participants.status IS 'Статус участия (enum skladchina_participant_status): pending = ждёт ответа; paid = оплатил (отмечено); declined = отказался явно (нейтрально для репутации); expired_no_response = промолчал до дедлайна (-40 к репутации при affects_reputation); released = освобождён — сбор закрыли ДО дедлайна, обещание не нарушено (нейтрально, без записи в леджер).';
COMMENT ON COLUMN skladchina_participants.paid_at IS 'Когда отмечена оплата (NULL = не оплачено). Сбрасывается при снятии отметки организатором.';
COMMENT ON COLUMN skladchina_participants.declined_at IS 'Когда участник отказался (NULL = не отказывался).';
COMMENT ON COLUMN skladchina_participants.reputation_applied IS 'TRUE = репутационный исход по участнику уже обработан (дедуп начисления). Ставится даже при нулевой дельте.';
COMMENT ON COLUMN skladchina_participants.created_at IS 'Когда участник добавлен в сбор.';
COMMENT ON COLUMN skladchina_participants.decline_note IS 'Обоснование отказа от участника (обязательно для шаблонов с одобрением отказа, например split_bill). NULL = запроса на отказ не было.';
COMMENT ON COLUMN skladchina_participants.decline_requested_at IS 'Когда подан запрос на отказ, ожидающий решения организатора (NULL = запроса нет). Участник при этом остаётся pending.';
COMMENT ON COLUMN skladchina_participants.decline_rejected IS 'TRUE = организатор отклонил запрос на отказ: участник должен платить, повторный запрос закрыт (аналог dispute_terminal у событий).';
COMMENT ON COLUMN skladchina_participants.decline_reject_note IS 'Обоснование организатора при отклонении запроса на отказ; участник видит его на странице сбора и в DM (NULL = нет).';

-- ============================================================================
-- interests — словарь интересов
-- ============================================================================

COMMENT ON TABLE interests IS
    'Общий словарь интересов для профилей пользователей. Имена нормализуются на сервере (trim, одиночные пробелы, lowercase, ё -> е), чтобы дубликаты схлопывались; словарь питает префиксный автокомплит.';
COMMENT ON COLUMN interests.id IS 'Суррогатный первичный ключ (UUID).';
COMMENT ON COLUMN interests.name IS 'Нормализованное имя интереса, до 40 символов, уникальное.';
COMMENT ON COLUMN interests.usage_count IS 'Сколько пользователей сейчас выбрали этот интерес (инкремент/декремент при правке профиля, не ниже 0). Сортировка подсказок автокомплита.';
COMMENT ON COLUMN interests.created_at IS 'Когда интерес впервые появился в словаре.';

-- ============================================================================
-- user_interests — связь пользователь-интерес (M:N)
-- ============================================================================

COMMENT ON TABLE user_interests IS
    'Связь M:N «пользователь — интерес» (выбранные интересы профиля). Составной PK (user_id, interest_id).';
COMMENT ON COLUMN user_interests.user_id IS 'Пользователь (FK users.id, каскадное удаление вместе с пользователем).';
COMMENT ON COLUMN user_interests.interest_id IS 'Интерес (FK interests.id, каскадное удаление вместе с интересом).';

-- ============================================================================
-- reputation_ledger — append-only источник истины репутации v2
-- ============================================================================

COMMENT ON TABLE reputation_ledger IS
    'Append-only леджер репутационных исходов (источник истины репутации v2). Одна строка на пару (участник, источник); кэш user_club_reputation — производный и пересчитывается отсюда. Строки не редактируются и не удаляются.';
COMMENT ON COLUMN reputation_ledger.id IS 'Суррогатный первичный ключ (UUID).';
COMMENT ON COLUMN reputation_ledger.user_id IS 'Участник, получивший исход (FK users.id). Владелец клуба в своём клубе исходов не получает (анти-фарм).';
COMMENT ON COLUMN reputation_ledger.club_id IS 'Клуб, в котором произошёл исход (FK clubs.id).';
COMMENT ON COLUMN reputation_ledger.axis IS 'Ось репутации (enum reputation_axis): attendance = явка на события; finance = складчины.';
COMMENT ON COLUMN reputation_ledger.kind IS 'Вид исхода (enum reputation_kind): ironclad = обещал (going) и пришёл; no_show = обещал и не пришёл; spontaneous = голосовал maybe и пришёл; spectator = голосовал maybe и не пришёл; confirmed_unresolved = подтвердил, но явка не выяснена/спор (0 очков); skladchina_paid = оплатил складчину; skladchina_declined = исторический — с редизайна 2026-06 отказ не пишется в леджер вовсе; skladchina_expired = промолчал до дедлайна складчины.';
COMMENT ON COLUMN reputation_ledger.points IS 'Очки, начисленные на момент записи (текущая политика: ironclad/spontaneous +100, no_show/spectator -200, skladchina_paid +10, skladchina_expired -40, confirmed_unresolved 0). Читаются как сохранены — исторические строки со старыми величинами не пересчитываются.';
COMMENT ON COLUMN reputation_ledger.occurred_at IS 'Время ПОВЕДЕНИЯ, а не обработки: для attendance = events.event_datetime, для finance = skladchinas.closed_at. Неизменяемый якорь для recency-decay.';
COMMENT ON COLUMN reputation_ledger.source_type IS 'Тип источника исхода (enum reputation_source): event = событие; skladchina = складчина.';
COMMENT ON COLUMN reputation_ledger.source_id IS 'Идентификатор источника: events.id или skladchinas.id (по source_type; FK не объявлен намеренно — леджер переживает удаление источника). UNIQUE (user_id, source_type, source_id) — ровно один исход на источник, повторная обработка = no-op.';
COMMENT ON COLUMN reputation_ledger.created_at IS 'Когда строка записана в леджер (время обработки).';

-- ============================================================================
-- user_club_reputation_pre_v18 — форензик-снапшот кэша перед V18
-- ============================================================================

COMMENT ON TABLE user_club_reputation_pre_v18 IS
    'Форензик-снапшот user_club_reputation, снятый миграцией V18 ПЕРЕД перестройкой кэша из леджера. Данные испорчены багом почасовой ре-инфляции (bug B) — только для сравнения/расследований, восстанавливать из него нельзя. Без FK и индексов; колонок kept/broke/neutral (V25) здесь нет.';
COMMENT ON COLUMN user_club_reputation_pre_v18.id IS 'Снапшот user_club_reputation.id на момент V18.';
COMMENT ON COLUMN user_club_reputation_pre_v18.user_id IS 'Снапшот user_club_reputation.user_id на момент V18.';
COMMENT ON COLUMN user_club_reputation_pre_v18.club_id IS 'Снапшот user_club_reputation.club_id на момент V18.';
COMMENT ON COLUMN user_club_reputation_pre_v18.reliability_index IS 'Снапшот user_club_reputation.reliability_index на момент V18 (завышен багом инфляции).';
COMMENT ON COLUMN user_club_reputation_pre_v18.promise_fulfillment_pct IS 'Снапшот user_club_reputation.promise_fulfillment_pct на момент V18.';
COMMENT ON COLUMN user_club_reputation_pre_v18.total_confirmations IS 'Снапшот user_club_reputation.total_confirmations на момент V18 (завышен багом инфляции).';
COMMENT ON COLUMN user_club_reputation_pre_v18.total_attendances IS 'Снапшот user_club_reputation.total_attendances на момент V18 (завышен багом инфляции).';
COMMENT ON COLUMN user_club_reputation_pre_v18.spontaneity_count IS 'Снапшот user_club_reputation.spontaneity_count на момент V18.';
COMMENT ON COLUMN user_club_reputation_pre_v18.created_at IS 'Снапшот user_club_reputation.created_at на момент V18.';
COMMENT ON COLUMN user_club_reputation_pre_v18.updated_at IS 'Снапшот user_club_reputation.updated_at на момент V18.';
COMMENT ON COLUMN user_club_reputation_pre_v18.outcome_count IS 'Снапшот user_club_reputation.outcome_count на момент V18 (тогда ещё нулевой — колонка добавлена V17 без backfill).';

-- ============================================================================
-- membership_history — append-only лог жизненного цикла членства
-- ============================================================================

COMMENT ON TABLE membership_history IS
    'Append-only лог переходов статуса членства для retention/churn-аналитики. Пишется в одной транзакции со сменой статуса; строки не меняются и не удаляются. Backfill истории не делался — лог строится с момента V31. Членство самого организатора не логируется.';
COMMENT ON COLUMN membership_history.id IS 'Суррогатный первичный ключ (UUID).';
COMMENT ON COLUMN membership_history.user_id IS 'Участник (FK users.id).';
COMMENT ON COLUMN membership_history.club_id IS 'Клуб (FK clubs.id).';
COMMENT ON COLUMN membership_history.event IS 'Тип перехода (enum membership_event): joined = создано новое членство (вступление); left = участник вышел/отменил — фиксируется момент решения об уходе, не момент потери доступа; rejoined = вернулся из cancelled/expired (продление при активном членстве не логируется); expired = подписка истекла после grace-периода.';
COMMENT ON COLUMN membership_history.occurred_at IS 'Когда произошёл переход.';

-- ============================================================================
-- club_rank — внутренний скрытый ранг клуба (L3)
-- ============================================================================

COMMENT ON TABLE club_rank IS
    'Внутренний скрытый ранг клуба L3 — результат периодического пересчёта (ClubRankScheduler). Служебные величины (rank_score, effective_k) никогда не сериализуются в DTO; наружу уходит только бейдж «Топ-5 в категории», выводимый отсюда на чтении.';
COMMENT ON COLUMN club_rank.club_id IS 'Клуб (PK и FK clubs.id) — одна строка ранга на клуб.';
COMMENT ON COLUMN club_rank.owner_id IS 'Владелец клуба (FK users.id), денормализован из clubs: категорийный лидерборд ограничивает владельца одним ранжируемым клубом на категорию без повторного JOIN.';
COMMENT ON COLUMN club_rank.category IS 'Категория клуба (enum club_category, копия clubs.category — категория задаётся один раз при создании). Ранжирование ведётся внутри категории.';
COMMENT ON COLUMN club_rank.rank_score IS 'Композитный L3-балл в диапазоне [0..~1]. PROVISIONAL: веса/якоря — принципиальные дефолты без калибровки. Строго внутренний — наружу не отдаётся.';
COMMENT ON COLUMN club_rank.is_ranked IS 'TRUE = клуб прошёл credibility-взвешенный гейт существования (сумма credibility ядра >= EFFECTIVE_K). FALSE = клуб НЕ ранжируется — это «недостаточно данных», а не «низкий ранг».';
COMMENT ON COLUMN club_rank.effective_k IS 'Сумма credibility «достоверного ядра» участников — внутренний отладочный вход гейта. Наружу не отдаётся.';
COMMENT ON COLUMN club_rank.computed_at IS 'Когда ранг последний раз пересчитан планировщиком.';

-- ============================================================================
-- service_subscription — сервисная подписка платформы (монетизация v2)
-- ============================================================================

COMMENT ON TABLE service_subscription IS
    'Рекуррентная сервисная подписка платформы (монетизация v2): организатор платит за план-ёмкость (сколько платных клубов можно вести). Плоская месячная плата — не приостанавливается, действует до конца оплаченного периода. Поток member-pays построен, но выключен флагом MEMBER_PAYS_ENABLED.';
COMMENT ON COLUMN service_subscription.id IS 'Суррогатный первичный ключ (UUID).';
COMMENT ON COLUMN service_subscription.payer_user_id IS 'Плательщик (FK users.id). Для payer_role = ORGANIZER действует частичный UNIQUE: не более одной живой (не ENDED) организаторской подписки на пользователя.';
COMMENT ON COLUMN service_subscription.payer_role IS 'Кто платит (enum subscription_payer_role): ORGANIZER = организатор платит платформе за ёмкость платных клубов (живой поток); MEMBER = участник платит за клуб (фаза 2, за выключенным флагом).';
COMMENT ON COLUMN service_subscription.plan IS 'План-ёмкость (enum subscription_plan): FREE = до 1 платного клуба, 0 руб. — неявный дефолт, строка с FREE не создаётся; TRIO = до 3 платных клубов; UNLIMITED = без лимита. Цены — в subscription_pricing.';
COMMENT ON COLUMN service_subscription.subject_club_id IS 'Клуб-предмет подписки для member-pays (FK clubs.id). NULL = подписка платформенная (организаторский план ёмкости).';
COMMENT ON COLUMN service_subscription.status IS 'Статус подписки (enum subscription_status): ACTIVE = действует; CANCELLED_PENDING_END = отменена, доживает оплаченный период; PAST_DUE = платёж за продление не прошёл (транзитный статус); ENDED = завершена (терминальный — не блокирует новую подписку).';
COMMENT ON COLUMN service_subscription.current_period_end IS 'Конец текущего оплаченного периода. Единственный драйвер «выключается в конце периода»: планировщик продлевает или завершает подписку по этой дате.';
COMMENT ON COLUMN service_subscription.provider_token IS 'Токен рекуррентного списания у эквайера (сохранённая карта / СБП-подписка). NULL = токена нет, в т.ч. у стаб-провайдера (деньги не двигаются).';
COMMENT ON COLUMN service_subscription.created_at IS 'Когда подписка оформлена.';
COMMENT ON COLUMN service_subscription.updated_at IS 'Когда подписка последний раз менялась (статус, продление периода).';

-- ============================================================================
-- subscription_event — идемпотентный леджер вебхуков провайдера
-- ============================================================================

COMMENT ON TABLE subscription_event IS
    'Идемпотентный леджер входящих вебхуков платёжного провайдера по подпискам: защита от ретраев и внеочередной доставки (аналог дедупа по charge_id у Stars, V12).';
COMMENT ON COLUMN subscription_event.id IS 'Суррогатный первичный ключ (UUID).';
COMMENT ON COLUMN subscription_event.subscription_id IS 'Подписка, к которой относится событие (FK service_subscription.id, каскадное удаление вместе с подпиской).';
COMMENT ON COLUMN subscription_event.provider_event_id IS 'Идентификатор события на стороне провайдера, UNIQUE — повторная доставка того же события отбрасывается.';
COMMENT ON COLUMN subscription_event.kind IS 'Тип события провайдера строкой до 64 символов (например payment_succeeded / payment_failed) — как пришло от провайдера, без enum.';
COMMENT ON COLUMN subscription_event.created_at IS 'Когда событие принято и записано.';

-- ============================================================================
-- subscription_pricing — версионируемый прайс планов
-- ============================================================================

COMMENT ON TABLE subscription_pricing IS
    'Прайс планов подписки, версионируемый по effective_from: цены правятся вставкой новой строки без миграции. Сервер всегда берёт сумму отсюда — клиенту не доверяет. Ёмкость планов здесь не хранится (константа SubscriptionPlanPolicy в коде).';
COMMENT ON COLUMN subscription_pricing.id IS 'Суррогатный первичный ключ (UUID).';
COMMENT ON COLUMN subscription_pricing.plan IS 'План (enum subscription_plan): FREE / TRIO / UNLIMITED. Стартовая сетка: FREE = 0, TRIO = 20000 (200 руб.), UNLIMITED = 40000 (400 руб.).';
COMMENT ON COLUMN subscription_pricing.price_kopecks IS 'Цена плана за месяц в КОПЕЙКАХ (>= 0). Инвариант сетки: UNLIMITED <= 2 x TRIO (no-cliff), FREE = 0.';
COMMENT ON COLUMN subscription_pricing.effective_from IS 'С какого момента строка действует. Актуальная цена плана = строка с максимальным effective_from <= now().';

-- ============================================================================
-- club_awards — докрытие: единственная незакомментированная колонка (V40
-- прокомментировал таблицу и остальные колонки)
-- ============================================================================

COMMENT ON COLUMN club_awards.id IS 'Суррогатный первичный ключ награды (UUID).';
