# Module: Membership

## TASK-010 — Вступление в открытый клуб

### Endpoint
```
POST /api/clubs/{id}/join
  Response 201: MembershipDto
  Errors: 400, 409
```

### Бизнес-правила
- Клуб должен существовать → 404
- Клуб должен иметь `access_type = open` → 400 "Club is not open for joining"
- Юзер не должен быть уже участником (active/grace_period) → 409 "Already a member"
- Количество active memberships < `member_limit` → 400 "Club is full"
- При создании: `status = active`, `role = member`
- `joined_at = now()`
- `subscription_expires_at = now() + 30 days`
- После создания membership обновить `clubs.member_count + 1`

### MembershipDto
```json
{
  "id": "uuid",
  "userId": "uuid",
  "clubId": "uuid",
  "status": "active",
  "role": "member",
  "joinedAt": "ISO datetime",
  "subscriptionExpiresAt": "ISO datetime"
}
```

### Corner Cases
- `POST /api/clubs/{unknownId}/join` → 404 NOT_FOUND
- `POST /api/clubs/{closedClubId}/join` → 400 "Club is not open for joining"
- `POST /api/clubs/{fullClubId}/join` → 400 "Club is full"
- Повторное вступление (уже active) → 409 CONFLICT "Already a member"
- Вступление без токена → 401
