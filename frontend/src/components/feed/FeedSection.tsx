import { FC, ReactNode } from 'react';

interface FeedSectionProps {
  title: string;
  count: number;
  children: ReactNode;
}

// Заголовки секций единообразно нейтральные (PO 2026-07-23): «Требует действия» больше
// не подсвечивается акцентом — срочность несут сами карточки (бейдж call-to-action),
// а не цвет заголовка. Дефолтный цвет rd-section-sub-h корректен в обеих темах.
export const FeedSection: FC<FeedSectionProps> = ({ title, count, children }) => (
  <>
    <div className="rd-section-sub-h">
      {title} <span className="rd-count">· {count}</span>
    </div>
    <div>{children}</div>
  </>
);
