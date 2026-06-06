import { useEffect } from 'react';
import { create } from 'zustand';

interface ClubContextState {
  /**
   * clubId of the club the user is currently viewing. Set by club-scoped pages
   * (club page, manage, event/skladchina detail) so the global "+" FAB can
   * pre-select this club and skip the club picker. `null` everywhere else.
   */
  clubId: string | null;
  setClubId: (id: string | null) => void;
}

export const useClubContextStore = create<ClubContextState>((set) => ({
  clubId: null,
  setClubId: (id) => set({ clubId: id }),
}));

/**
 * Mark the current club context for the lifetime of a page. Pass the club's id
 * (or `null`/`undefined` while it's still loading). Clears on unmount so the
 * FAB falls back to the normal "pick a club" flow on non-club screens.
 */
export function useSetClubContext(clubId: string | null | undefined): void {
  const setClubId = useClubContextStore((s) => s.setClubId);
  useEffect(() => {
    setClubId(clubId ?? null);
    return () => setClubId(null);
  }, [clubId, setClubId]);
}
