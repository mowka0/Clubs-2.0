-- V30: Retire the dead activity_rating column.
--
-- The field was written 0 at club creation and only ever decremented (clamped at 0) by the
-- application auto-reject scheduler, so in practice it was permanently 0. Yet it drove two
-- discovery behaviours: ORDER BY activity_rating DESC (a no-op sort) and the "Популярный" tag
-- (threshold = top-10% of all-zeros = 0 → the tag landed on every club). Both behaviours are
-- rebuilt as derived signals (recent non-cancelled events / member_count) in JooqClubRepository.
-- The stored field carries no salvageable data, so it is dropped outright.

DROP INDEX IF EXISTS idx_clubs_activity_rating;
ALTER TABLE clubs DROP COLUMN IF EXISTS activity_rating;
