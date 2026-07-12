import { describe, it, expect } from 'vitest';
import {
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
