import { FC } from 'react';
import type { OnboardingSlideData } from './slides';

interface OnboardingSlideProps {
  slide: OnboardingSlideData;
}

/**
 * Содержимое одного слайда: арт, заголовок, подзаголовок и список преимуществ.
 * Чистое представление — кнопки живут в карусели (OnboardingFlow), она же владеет
 * переходами и завершением онбординга.
 */
export const OnboardingSlide: FC<OnboardingSlideProps> = ({ slide }) => (
  <div className="ob-slide">
    <div className="ob-art">
      <div className="ob-art-ring" aria-hidden="true">
        {slide.art}
      </div>
    </div>

    <h2 className="ob-title">
      {slide.title}
      <em>{slide.titleAccent}</em>
    </h2>

    <p className="ob-sub">
      {slide.subtitle}
      {slide.subtitleStrong && <b>{slide.subtitleStrong}</b>}
    </p>

    <div className="ob-perks">
      {slide.perks.map((perk) => (
        <div className="ob-perk" key={perk.title}>
          <div className="ob-perk-ic" aria-hidden="true">
            {perk.icon}
          </div>
          <div>
            <div className="ob-perk-t">{perk.title}</div>
            <div className="ob-perk-d">{perk.text}</div>
          </div>
        </div>
      ))}
    </div>
  </div>
);
