-- V103: оплата клуба участником (docs/modules/billing-member-pays.md, решение PO 2026-10-09).
--
-- До этой версии за чат платил только владелец клуба, и «кто платил» выводилось из клуба. Теперь
-- заплатить может любой участник — плательщика храним на счёте. Бэкфилл владельцем клуба честен:
-- раньше никто другой платить не мог (и дочерние списания всегда шли с карты владельца).
--
-- Новые значения enum в этой же миграции не используются — правило PostgreSQL для ADD VALUE
-- внутри транзакции (как V45, V83).

ALTER TABLE platform_payment ADD COLUMN payer_user_id UUID REFERENCES users(id);

UPDATE platform_payment p
SET payer_user_id = c.owner_id
FROM clubs c
WHERE c.id = p.club_id;

ALTER TABLE platform_payment ALTER COLUMN payer_user_id SET NOT NULL;

COMMENT ON TABLE platform_payment IS
    'Платежи платформе за чат клуба через провайдера (Robokassa). Один ряд = один счёт (InvId); материнский платёж (MOTHER) со страницы оплаты — от владельца или любого участника клуба, дочерние (RECURRING) — автосписания по сохранённой карте владельца.';
COMMENT ON COLUMN platform_payment.payer_user_id IS
    'Кто платит по счёту (FK users.id). MOTHER — тот, кто открыл оплату: владелец (карта может сохраниться для автопродления) или участник (разовая оплата, карта не сохраняется). RECURRING — владелец клуба: списание идёт с его сохранённой карты. Старые счета заполнены владельцем клуба (V103).';

ALTER TYPE reputation_kind ADD VALUE IF NOT EXISTS 'club_billing_paid';
ALTER TYPE reputation_source ADD VALUE IF NOT EXISTS 'club_billing';

COMMENT ON COLUMN reputation_ledger.kind IS
    'Вид исхода (enum reputation_kind): ironclad = обещал (going) и пришёл; no_show = обещал и не пришёл; spontaneous = голосовал maybe и пришёл; spectator = голосовал maybe и не пришёл; confirmed_unresolved = подтвердил, но явка не выяснена/спор (0 очков); skladchina_paid = долг сбора закрыт до срока; skladchina_declined = исторический — с редизайна 2026-06 отказ не пишется в леджер вовсе; skladchina_expired = долг сбора просрочен дольше трёх недель; abandoned_slot = отказ от подтверждённого места без замены; open_no_show = зарезервирован, не выдаётся; late_decline_covered / late_decline_uncovered = поздний отказ с заменой / без замены; club_billing_paid = участник (не владелец) оплатил подписку клуба за чат, не чаще раза в 30 дней.';
COMMENT ON COLUMN reputation_ledger.axis IS
    'Ось репутации (enum reputation_axis): attendance = явка на события; finance = деньги — долги сборов и оплата подписки клуба участником.';
COMMENT ON COLUMN reputation_ledger.source_type IS
    'Тип источника исхода (enum reputation_source): event = событие; skladchina = складчина (сбор); club_billing = оплата подписки клуба за чат.';
COMMENT ON COLUMN reputation_ledger.source_id IS
    'Идентификатор источника: events.id, skladchinas.id или platform_payment.id (по source_type; FK не объявлен намеренно — леджер переживает удаление источника). UNIQUE (user_id, source_type, source_id) — ровно один исход на источник, повторная обработка = no-op.';
