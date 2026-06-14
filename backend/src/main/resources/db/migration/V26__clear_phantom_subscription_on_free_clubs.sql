-- V26: free clubs must not carry a subscription expiry.
-- A bug in JooqMembershipRepository.create (the free-join path) set
-- subscription_expires_at = now() + 30 days for EVERY free-club membership. That made free members
-- look like cancelled-in-period paid subscribers: a phantom "доступ до DATE" banner, a free leaver
-- lingering as a member for 30 days, and (with the paid-period leave routing) penalty-free exits.
-- The code bug is fixed; this clears the phantom data.
--
-- Scope: any membership in a CURRENTLY-free club (subscription_price <= 0 / NULL). A club switched
-- paid→free grants free access to everyone anyway, so a lingering paid expiry there is moot too.
UPDATE memberships m
SET subscription_expires_at = NULL
FROM clubs c
WHERE m.club_id = c.id
  AND COALESCE(c.subscription_price, 0) <= 0
  AND m.subscription_expires_at IS NOT NULL;
