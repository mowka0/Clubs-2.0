import { describe, it, expect } from 'vitest';
import {
  ASSIGNABLE_ROLES,
  ROLE_DESCRIPTIONS,
  isActiveManagerMembership,
  isManagerRole,
  membershipRoleLabel,
} from '../../utils/membershipRole';

describe('isManagerRole', () => {
  it('returns true for organizer and co_organizer', () => {
    expect(isManagerRole('organizer')).toBe(true);
    expect(isManagerRole('co_organizer')).toBe(true);
  });

  it('returns false for member, undefined and null', () => {
    expect(isManagerRole('member')).toBe(false);
    expect(isManagerRole(undefined)).toBe(false);
    expect(isManagerRole(null)).toBe(false);
  });
});

describe('membershipRoleLabel', () => {
  it('maps all three roles to Russian labels', () => {
    expect(membershipRoleLabel('organizer')).toBe('Организатор');
    expect(membershipRoleLabel('co_organizer')).toBe('Со-организатор');
    expect(membershipRoleLabel('member')).toBe('Участник');
  });

  it('falls back to «Участник» for unknown/absent values', () => {
    expect(membershipRoleLabel('moderator')).toBe('Участник');
    expect(membershipRoleLabel(undefined)).toBe('Участник');
  });
});

describe('ASSIGNABLE_ROLES / ROLE_DESCRIPTIONS (club-roles селектор)', () => {
  it('exposes exactly the assignable roles in selector order, without organizer', () => {
    expect([...ASSIGNABLE_ROLES]).toEqual(['member', 'co_organizer']);
    expect(ASSIGNABLE_ROLES).not.toContain('organizer');
  });

  it('has a non-empty Russian description for every assignable role', () => {
    for (const role of ASSIGNABLE_ROLES) {
      expect(ROLE_DESCRIPTIONS[role].length).toBeGreaterThan(0);
    }
    // Co-organizer description enumerates the owner-only exclusions (spec §3).
    expect(ROLE_DESCRIPTIONS.co_organizer).toMatch(/Не может/);
  });
});

describe('isActiveManagerMembership (fail-close, критерий приёмки №14)', () => {
  it('owner is a manager regardless of status', () => {
    expect(isActiveManagerMembership({ role: 'organizer', status: 'active' })).toBe(true);
  });

  it('co-organizer is a manager only while membership is active', () => {
    expect(isActiveManagerMembership({ role: 'co_organizer', status: 'active' })).toBe(true);
    expect(isActiveManagerMembership({ role: 'co_organizer', status: 'frozen' })).toBe(false);
    expect(isActiveManagerMembership({ role: 'co_organizer', status: 'expired' })).toBe(false);
    expect(isActiveManagerMembership({ role: 'co_organizer', status: 'cancelled' })).toBe(false);
  });

  it('regular member and missing membership are not managers', () => {
    expect(isActiveManagerMembership({ role: 'member', status: 'active' })).toBe(false);
    expect(isActiveManagerMembership(undefined)).toBe(false);
    expect(isActiveManagerMembership(null)).toBe(false);
  });
});
