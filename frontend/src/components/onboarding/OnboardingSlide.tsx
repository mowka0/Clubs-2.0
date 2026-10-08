import { FC } from 'react';
import type { OnboardingSlideData } from './slides';

interface OnboardingSlideProps {
  slide: OnboardingSlideData;
  /** Первый слайд виден сразу — его арт качается раньше двух других. */
  isFirst: boolean;
}

/**
 * Содержимое одного слайда интро: арт, заголовок, микро-строка. Чистое представление —
 * кнопки, точки и жест живут в карусели (OnboardingFlow).
 *
 * Заголовок разбивается по `\n` вручную: перенос — часть композиции (две коротких строки
 * читаются как слоган, одна длинная расползается на четыре и выдавливает арт).
 */
export const OnboardingSlide: FC<OnboardingSlideProps> = ({ slide, isFirst }) => (
  <div className="ob-slide">
    <div className="ob-art">
      <img className="ob-art-img" src={slide.artSrc} alt="" draggable={false} fetchPriority={isFirst ? 'high' : 'low'} />
    </div>

    <h2 className="ob-title">
      {slide.title.map((part) =>
        part.accent ? (
          <em key={part.text}>{renderWithBreaks(part.text)}</em>
        ) : (
          <span key={part.text}>{renderWithBreaks(part.text)}</span>
        ),
      )}
    </h2>

    <p className="ob-micro">{slide.micro}</p>
  </div>
);

/** Переносы в тексте сегмента — в реальные <br/>. */
function renderWithBreaks(text: string) {
  const lines = text.split('\n');
  return lines.map((line, i) => (
    <span key={line + String(i)}>
      {line}
      {i < lines.length - 1 && <br />}
    </span>
  ));
}
