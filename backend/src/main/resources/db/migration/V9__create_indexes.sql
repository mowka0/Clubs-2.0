-- V9: Create indexes for query performance
-- Covers discovery, membership lookup, event calendar, voting, applications, and finance queries

-- clubs: discovery queries
CREATE INDEX idx_clubs_category ON clubs(category);
CREATE INDEX idx_clubs_city ON clubs(city);
CREATE INDEX idx_clubs_access_type ON clubs(access_type);
CREATE INDEX idx_clubs_activity_rating ON clubs(activity_rating DESC);
CREATE INDEX idx_clubs_owner_id ON clubs(owner_id);

-- memberships: lookup
CREATE INDEX idx_memberships_user_id ON memberships(user_id);
CREATE INDEX idx_memberships_club_id ON memberships(club_id);
CREATE INDEX idx_memberships_status ON memberships(status);

-- events: club calendar
CREATE INDEX idx_events_club_id_datetime ON events(club_id, event_datetime DESC);
CREATE INDEX idx_events_status ON events(status);

-- event_responses: voting queries
CREATE INDEX idx_event_responses_event_id ON event_responses(event_id);
CREATE INDEX idx_event_responses_user_id ON event_responses(user_id);

-- applications: pending lookup
CREATE INDEX idx_applications_club_id_status ON applications(club_id, status);
CREATE INDEX idx_applications_user_id ON applications(user_id);

-- transactions: finance aggregation
CREATE INDEX idx_transactions_club_id_created ON transactions(club_id, created_at);
CREATE INDEX idx_transactions_user_id ON transactions(user_id);
