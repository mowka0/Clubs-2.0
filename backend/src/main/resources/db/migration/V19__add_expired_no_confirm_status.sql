-- Two-stage confirmation, Feature A (PRD §4.4.2 / §623 "авто-отклонение"):
-- a going/maybe voter who never confirms in Stage 2 must end in an explicit
-- terminal state at event start, not linger as stage_2_vote/final_status = NULL.
-- A dedicated value keeps the roster honest — "бронь сгорела" (forgot to confirm)
-- is NOT the same as "отказался" (explicitly declined), even though both score 0
-- reputation. The NAMING follows application_status.auto_rejected and
-- skladchina_participant_status.expired_no_response (both defined via CREATE TYPE).
-- NB: this is the codebase's first use of ALTER TYPE ... ADD VALUE — the mechanism
-- is proven by Stage2ExpireRepositoryTest (Testcontainers runs the full Flyway chain
-- on postgres:16 and then writes the new value), not by those precedents.
--
-- Reputation is unaffected: JooqReputationRepository.findConfirmedResponses gates
-- on final_status = 'confirmed', so 'expired_no_confirm' rows are ignored (0 points),
-- exactly like the legacy NULL holes they replace.
--
-- IF NOT EXISTS keeps the migration idempotent if the value was applied manually
-- for local jOOQ codegen. PostgreSQL 16 allows ALTER TYPE ... ADD VALUE inside the
-- Flyway transaction because the new value is not used within this migration.

ALTER TYPE stage_2_vote ADD VALUE IF NOT EXISTS 'expired_no_confirm';
ALTER TYPE final_status ADD VALUE IF NOT EXISTS 'expired_no_confirm';
