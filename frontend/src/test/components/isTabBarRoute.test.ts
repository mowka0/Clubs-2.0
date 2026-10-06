import { describe, it, expect } from 'vitest';
import { isTabBarRoute } from '../../components/BottomTabBar';

// Док и нативный «назад» взаимоисключающие (Layout: useBackButton(!showTabBar)).
describe('isTabBarRoute', () => {
  it('«/» — экран с доком, «назад» там спрятан', () => {
    expect(isTabBarRoute('/')).toBe(true);
  });

  it('подключение чата изнутри приложения — без дока, значит с нативным «назад»', () => {
    expect(isTabBarRoute('/connect-chat')).toBe(false);
  });
});
