-- V65: выравнивание кэш-счётчиков kept/broke/neutral в user_club_reputation (решение PO 2026-07-21).
-- Причина: с V45 вид abandoned_slot классифицирован в TrustPolicy как «нарушение» (BROKE), но
-- в SQL-список brokeKinds recompute его не добавили — счётчик broke_count занижался у всех, кто
-- бросал подтверждённый слот (сами очки и Trust были корректны, расходился только счётчик).
-- Код теперь ВЫВОДИТ списки из TrustPolicy (рассинхрон невозможен), а эта миграция один раз
-- пересчитывает три производных счётчика для ВСЕХ пар (user, club) по текущей классификации:
--   kept  = ironclad, spontaneous, skladchina_paid
--   broke = no_show, spectator, skladchina_expired, abandoned_slot, open_no_show
--   neutral = все остальные (confirmed_unresolved, исторический skladchina_declined)
-- Остальные поля кэша (reliability_index и др.) не трогаем — они считались верно.

UPDATE user_club_reputation ucr
SET kept_count    = agg.kept,
    broke_count   = agg.broke,
    neutral_count = agg.outcome - agg.kept - agg.broke
FROM (
    SELECT user_id,
           club_id,
           COUNT(*) FILTER (WHERE kind IN ('ironclad', 'spontaneous', 'skladchina_paid'))                          AS kept,
           COUNT(*) FILTER (WHERE kind IN ('no_show', 'spectator', 'skladchina_expired',
                                           'abandoned_slot', 'open_no_show'))                                       AS broke,
           COUNT(*)                                                                                                AS outcome
    FROM reputation_ledger
    GROUP BY user_id, club_id
) agg
WHERE ucr.user_id = agg.user_id
  AND ucr.club_id = agg.club_id;
