-- V42: Relax the applications uniqueness.
--
-- The original UNIQUE (user_id, club_id, status) (V4) wrongly forbade two applications with the SAME
-- terminal status for the same (user, club): a user who applied → was rejected, then applied again and
-- was rejected a second time, hit a duplicate-key error on (user_id, club_id, 'rejected').
--
-- Correct invariant: a user may have at most ONE *active* application (pending/approved) per club at a
-- time, but terminal statuses (rejected / auto_rejected) may repeat across re-apply cycles. The
-- application-level guards (ApplicationService.submitApplication, findActiveByUserAndClub.fetchOne) already
-- assume "≤1 active per (user, club)"; this partial unique index enforces exactly that and nothing more.

ALTER TABLE applications DROP CONSTRAINT IF EXISTS applications_user_id_club_id_status_key;

CREATE UNIQUE INDEX IF NOT EXISTS applications_one_active_per_user_club
    ON applications (user_id, club_id)
    WHERE status IN ('pending', 'approved');

COMMENT ON INDEX applications_one_active_per_user_club IS
    'Не более одной активной заявки (status pending/approved) на пару (участник, клуб). Терминальные статусы (rejected/auto_rejected) могут повторяться — пользователь может пройти цикл «подал → отклонили» в один клуб несколько раз.';
