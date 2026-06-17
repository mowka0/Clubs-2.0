import { describe, it, expect } from 'vitest';
import { activityLevel, attendanceLevel, cohesionLevel } from '../../components/club/qualityLevels';

describe('cohesionLevel (ядро по distinct ≥3 явки)', () => {
  it('0 → empty, grows generously to 4 at 20+', () => {
    expect(cohesionLevel(0)).toBe(0);
    expect(cohesionLevel(3)).toBe(1);
    expect(cohesionLevel(4)).toBe(2);
    expect(cohesionLevel(7)).toBe(2);
    expect(cohesionLevel(8)).toBe(3);
    expect(cohesionLevel(19)).toBe(3);
    expect(cohesionLevel(20)).toBe(4);
    expect(cohesionLevel(120)).toBe(4);
  });
});

describe('activityLevel (встреч в месяц, насыщение)', () => {
  it('0 → empty, saturates to 4 at 8+/мес', () => {
    expect(activityLevel(0)).toBe(0);
    expect(activityLevel(1.3)).toBe(1);
    expect(activityLevel(2)).toBe(2);
    expect(activityLevel(4)).toBe(3);
    expect(activityLevel(7.9)).toBe(3);
    expect(activityLevel(8)).toBe(4);
  });
});

describe('attendanceLevel (доля среднего прихода от участников)', () => {
  it('0 when nobody comes or no members', () => {
    expect(attendanceLevel(0, 40)).toBe(0);
    expect(attendanceLevel(10, 0)).toBe(0);
  });

  it('scales by ratio, capping at 4 for ≥60%', () => {
    expect(attendanceLevel(4, 40)).toBe(1);   // 10%
    expect(attendanceLevel(10, 40)).toBe(2);  // 25%
    expect(attendanceLevel(20, 40)).toBe(3);  // 50%
    expect(attendanceLevel(28, 40)).toBe(4);  // 70%
    expect(attendanceLevel(50, 40)).toBe(4);  // >100% clamps to 4
  });
});
