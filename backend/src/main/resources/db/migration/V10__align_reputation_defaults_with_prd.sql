-- V10: Align user_club_reputation.reliability_index default with PRD §4.4.4
-- PRD specifies: "Индекс надёжности = Σ всех начислений за всю историю (может быть отрицательным)"
-- Default starting value is 0, not 100. Existing records are not modified.

ALTER TABLE user_club_reputation
    ALTER COLUMN reliability_index SET DEFAULT 0;
