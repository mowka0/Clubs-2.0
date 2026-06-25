-- Drop the vestigial `clubs.member_count` column.
--
-- It was a denormalized counter maintained by a scattered, incomplete set of increment/decrement
-- call sites, which let it drift out of sync (e.g. leave→rejoin→leave double-decremented it to 0
-- for a 2-person club). The member_count drift fix (#80) moved all display, discovery sorting and
-- tagging to a live count computed straight from `memberships` (active + grace_period, including
-- the organizer), so the column is no longer read anywhere. This migration removes the column; its
-- write machinery (increment/decrement repository methods and call sites) is removed in the same change.
ALTER TABLE clubs DROP COLUMN IF EXISTS member_count;
