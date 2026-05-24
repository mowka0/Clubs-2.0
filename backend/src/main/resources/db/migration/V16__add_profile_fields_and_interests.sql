-- Profile editing: user country + bio, plus a shared interests dictionary.
-- Interests are normalized (trim, single spaces, lowercase, ё→е) on the server
-- so duplicates collapse; the dictionary powers prefix autocomplete.

ALTER TABLE users ADD COLUMN IF NOT EXISTS country VARCHAR(8);
ALTER TABLE users ADD COLUMN IF NOT EXISTS bio VARCHAR(280);

CREATE TABLE IF NOT EXISTS interests (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(40) NOT NULL UNIQUE,
    usage_count INTEGER NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- varchar_pattern_ops makes prefix LIKE 'foo%' use the btree index regardless of
-- the DB locale — keeps autocomplete fast as the dictionary grows.
CREATE INDEX IF NOT EXISTS idx_interests_name_prefix ON interests (name varchar_pattern_ops);

CREATE TABLE IF NOT EXISTS user_interests (
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    interest_id UUID NOT NULL REFERENCES interests(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, interest_id)
);

CREATE INDEX IF NOT EXISTS idx_user_interests_user ON user_interests (user_id);
