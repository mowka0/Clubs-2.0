package com.clubs.reputation

import java.util.UUID

/**
 * Narrow read-port the `reputation` module exposes so `clubquality` (L3 hidden rank) can consume
 * ledger-derived credibility inputs WITHOUT importing [JooqReputationRepository] or any Trust type
 * directly — the module-boundary rule (`principles.md`, design §10: "features don't import each
 * other directly"). The port returns only distinct-club COUNTS grouped by owner, never a Trust
 * magnitude, which keeps the structural invariant **club-L3 ≠ average member-Trust** machine-enforced:
 * a `clubquality` caller physically cannot reach a Trust number through this surface. The counts are
 * used only for owner-counting (footprintW) and the owner-concentration share — never as a score.
 */
interface LedgerReadPort {

    /**
     * Cross-owner ledger footprint for each given user: a map `ownerId → distinct clubs owned by that
     * owner where the user earned a KEPT outcome`. "Kept" = `ironclad` / `spontaneous` ONLY —
     * `skladchina_paid` is owner-authorable (organizer flips `paid` with no `charge_id`) and is banned
     * from every L3 input (anti-farm invariant #1).
     *
     * Powers the L3 credibility weight: the count of distinct OWNERS is the Sybil-tax (an account
     * earning outcomes across many independent owners is expensive to fake), and the share concentrated
     * in one owner's clubs is the sock-puppet signature. Empty input → empty map; a user with no kept
     * outcomes is simply absent.
     */
    fun footprintByUser(userIds: Collection<UUID>): Map<UUID, Map<UUID, Int>>
}
