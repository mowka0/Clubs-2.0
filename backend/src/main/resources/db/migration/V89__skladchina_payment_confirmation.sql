-- V89: подтверждение оплат организатором (складчина).
--
-- До этой миграции «Я оплатил» было самодекларацией: участник нажал — деньги засчитаны,
-- а при отсутствии pending сбор закрывался успешным сам. Организатор при этом мог денег не
-- получить. Теперь деньги сверяет организатор — списком в конце, как отметку явки.
-- Спека: docs/modules/skladchina.md § «Подтверждение оплат организатором».
--
-- Смысл статуса 'paid' НЕ меняется — это по-прежнему «заявил оплату». Новые значения описывают
-- то, что происходит с заявкой ПОСЛЕ сверки, поэтому весь код, считающий 'paid' по ходу сбора,
-- остаётся верным, а «деньги засчитаны» выражается предикатом status IN ('paid','payment_confirmed').
--
-- ALTER TYPE ... ADD VALUE в PostgreSQL 12+ работает внутри транзакции Flyway (V45/V63/V83 —
-- тот же приём), но добавленное значение нельзя использовать в этой же транзакции: поэтому ниже
-- нет ни одного UPDATE/предиката с новыми значениями.
ALTER TYPE skladchina_participant_status ADD VALUE IF NOT EXISTS 'payment_confirmed';
ALTER TYPE skladchina_participant_status ADD VALUE IF NOT EXISTS 'payment_rejected';
ALTER TYPE skladchina_participant_status ADD VALUE IF NOT EXISTS 'payment_disputed';

-- Сверка оплаты организатором.
ALTER TABLE skladchina_participants ADD COLUMN IF NOT EXISTS payment_confirmed_at TIMESTAMPTZ;
ALTER TABLE skladchina_participants ADD COLUMN IF NOT EXISTS payment_rejected_at  TIMESTAMPTZ;
ALTER TABLE skladchina_participants ADD COLUMN IF NOT EXISTS payment_reject_note  TEXT;

-- Спор участника: чек обязателен (спорить «на словах» нельзя), заметка — по желанию.
ALTER TABLE skladchina_participants ADD COLUMN IF NOT EXISTS receipt_url  VARCHAR(500);
ALTER TABLE skladchina_participants ADD COLUMN IF NOT EXISTS receipt_note TEXT;
ALTER TABLE skladchina_participants ADD COLUMN IF NOT EXISTS disputed_at  TIMESTAMPTZ;

-- Отклонённый спор терминален: без этого флага участник переоткрывал бы спор бесконечно
-- (тот же приём, что event_responses.dispute_terminal в V24).
ALTER TABLE skladchina_participants
    ADD COLUMN IF NOT EXISTS dispute_terminal BOOLEAN NOT NULL DEFAULT FALSE;

-- Штамп дедупликации DM «сбор завершён, сверьте деньги» — организатора зовут сверять один раз.
ALTER TABLE skladchinas ADD COLUMN IF NOT EXISTS confirmation_requested_at TIMESTAMPTZ;

-- Фиды шедулера: «отклонённые без спора старше 48 ч» и «споры старше недели». Предикат по
-- enum-значению здесь недоступен (см. выше), поэтому индексы частичные по NOT NULL —
-- строк с этими штампами на порядки меньше, чем участников.
CREATE INDEX IF NOT EXISTS idx_skladchina_participants_payment_rejected_at
    ON skladchina_participants (payment_rejected_at) WHERE payment_rejected_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_skladchina_participants_disputed_at
    ON skladchina_participants (disputed_at) WHERE disputed_at IS NOT NULL;

COMMENT ON COLUMN skladchina_participants.payment_confirmed_at IS 'Когда организатор сверил платёж с выпиской и засчитал его (статус payment_confirmed).';
COMMENT ON COLUMN skladchina_participants.payment_rejected_at IS 'Когда организатор не нашёл платёж (статус payment_rejected). Начало 48-часового окна на чек: молчание после него = −40.';
COMMENT ON COLUMN skladchina_participants.payment_reject_note IS 'Необязательная причина организатора, почему платёж не засчитан («в выписке 833 ₽ от вас нет»). Видна отклонённому участнику.';
COMMENT ON COLUMN skladchina_participants.receipt_url IS 'Чек участника (фото или скриншот из банка) — обязателен для спора: спорить без чека нельзя.';
COMMENT ON COLUMN skladchina_participants.receipt_note IS 'Необязательный комментарий участника к чеку («перевела 833 ₽ 3 сентября в 21:10»).';
COMMENT ON COLUMN skladchina_participants.disputed_at IS 'Когда участник приложил чек (статус payment_disputed). Списание заморожено, пока спор открыт; неразобранный неделю спор закрывается нейтрально.';
COMMENT ON COLUMN skladchina_participants.dispute_terminal IS 'Организатор рассмотрел чек и платёж не подтвердил — повторно оспорить нельзя.';
COMMENT ON COLUMN skladchinas.confirmation_requested_at IS 'Когда организатору ушёл DM «сбор завершён, сверьте деньги». Штамп дедупликации: зовём сверять один раз.';
