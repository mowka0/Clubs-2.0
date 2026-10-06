import { FC, ReactNode } from 'react';
import foxClubCreatedArt from '../../assets/mascot/fox-club-created.png';

interface ClubCreatedSceneProps {
  clubName: string;
  /** Подзаголовок: что делать дальше — у формы «позовите своих», у клуба из чата «сначала наполни». */
  lead: ReactNode;
  /** Кнопки и всё, что под подзаголовком. */
  children: ReactNode;
}

/**
 * Поздравление «Клуб создан» (club-invites, кадр E): лис с ленточкой, название, следующий шаг.
 * Одна сцена на оба способа создать клуб — формой с нуля (CreateClubModal) и из чата
 * (ClubCreatedSheet) — различаются только подзаголовок и кнопки.
 */
export const ClubCreatedScene: FC<ClubCreatedSceneProps> = ({ clubName, lead, children }) => (
  <div style={{ padding: '26px 24px 24px', textAlign: 'center' }}>
    <img src={foxClubCreatedArt} alt="" className="rd-foxcreated-art" draggable={false} />
    <h3 style={{ fontSize: 20, fontWeight: 700, margin: '0 0 8px' }}>
      Клуб «{clubName}» создан
    </h3>
    <p style={{ fontSize: 13, color: 'var(--text-dim)', lineHeight: 1.55, margin: '0 0 22px' }}>
      {lead}
    </p>
    {children}
  </div>
);
