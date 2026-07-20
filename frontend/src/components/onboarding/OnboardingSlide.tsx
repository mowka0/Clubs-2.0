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
export const OnboardingSlide: FC<OnboardingSlideProps> = ({ slide }) => {
  const hasBrand = slide.title.some((part) => part.brand);

  return (
    <div className="ob-slide">
      <div className="ob-art">
        <img className="ob-art-img" src={slide.artSrc} alt="" draggable={false} />
      </div>

      {/* Слоган со вшитым названием длиннее обычных заголовков, поэтому у него свой кегль:
          иначе он съедает высоту и выдавливает преимущества за пределы экрана. */}
      <h2 className={hasBrand ? 'ob-title ob-title-brand' : 'ob-title'}>
        {slide.title.map((part) => {
          if (part.brand) {
            return (
              <span className="ob-brand" key={part.text}>
                {part.text}
              </span>
            );
          }
          if (part.accent) return <em key={part.text}>{part.text}</em>;
          return <span key={part.text}>{part.text}</span>;
        })}
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
};
