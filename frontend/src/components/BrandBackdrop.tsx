import { FC } from 'react';

const STYLE: React.CSSProperties = {
  position: 'absolute',
  top: 0, left: 0, right: 0,
  height: 1750,
  pointerEvents: 'none',
  zIndex: 0,
  overflow: 'hidden',
  // top→bottom fade so cards lower on the page aren't competing with decor
  WebkitMaskImage:
    'linear-gradient(to bottom, rgba(0,0,0,1) 0%, rgba(0,0,0,1) 27%, rgba(0,0,0,0.70) 42%, rgba(0,0,0,0.45) 60%, rgba(0,0,0,0.30) 80%, rgba(0,0,0,0.18) 100%)',
  maskImage:
    'linear-gradient(to bottom, rgba(0,0,0,1) 0%, rgba(0,0,0,1) 27%, rgba(0,0,0,0.70) 42%, rgba(0,0,0,0.45) 60%, rgba(0,0,0,0.30) 80%, rgba(0,0,0,0.18) 100%)',
};

/**
 * Abstract decorative pattern for brand pages (Discovery / ClubPage / MyClubs / etc.):
 *   navy mood-blobs + brass radial glows + topographic contour lines
 *   + brass orb-dots + ghost-curves on the side. Top→bottom mask fade so
 *   content lower on the page reads cleanly over the decor.
 *
 * Static — no props, no state. Inline SVG is cheap (~2 KB) and lets us
 * use the brand palette directly via gradients/strokes.
 */
export const BrandBackdrop: FC = () => (
  <div className="brand-backdrop" style={STYLE} aria-hidden="true">
    <svg width="100%" height="100%" viewBox="0 0 390 1750" preserveAspectRatio="none" xmlns="http://www.w3.org/2000/svg">
      <defs>
        <radialGradient id="brand-blob-navy" cx="50%" cy="50%" r="50%">
          <stop offset="0%"  stopColor="#4A5680" stopOpacity="0.75" />
          <stop offset="55%" stopColor="#384270" stopOpacity="0.35" />
          <stop offset="100%" stopColor="#1A2138" stopOpacity="0" />
        </radialGradient>
        <radialGradient id="brand-blob-brass" cx="50%" cy="50%" r="50%">
          <stop offset="0%"  stopColor="#DDB87A" stopOpacity="0.50" />
          <stop offset="55%" stopColor="#B58A4D" stopOpacity="0.22" />
          <stop offset="100%" stopColor="#B58A4D" stopOpacity="0" />
        </radialGradient>
        <radialGradient id="brand-orb" cx="35%" cy="35%" r="65%">
          <stop offset="0%"  stopColor="#F2D098" stopOpacity="0.95" />
          <stop offset="55%" stopColor="#C9A063" stopOpacity="0.65" />
          <stop offset="100%" stopColor="#8A6730" stopOpacity="0.15" />
        </radialGradient>
      </defs>

      {/* Large soft blobs — visibly lighter than bg */}
      <ellipse cx="60"  cy="120" rx="220" ry="200" fill="url(#brand-blob-navy)" />
      <ellipse cx="340" cy="380" rx="200" ry="220" fill="url(#brand-blob-navy)" opacity="0.85" />
      <ellipse cx="40"  cy="640" rx="230" ry="200" fill="url(#brand-blob-navy)" opacity="0.75" />
      <ellipse cx="360" cy="940" rx="210" ry="230" fill="url(#brand-blob-navy)" opacity="0.7" />

      {/* Brass-glow blobs */}
      <ellipse cx="320" cy="160" rx="180" ry="150" fill="url(#brand-blob-brass)" />
      <ellipse cx="80"  cy="420" rx="120" ry="110" fill="url(#brand-blob-brass)" opacity="0.55" />

      {/* Topographic contour lines */}
      <g fill="none" stroke="#C9A063" strokeWidth="1.1" strokeLinecap="round">
        <path d="M-20 280 Q 100 240, 220 290 T 420 270" opacity="0.28" />
        <path d="M-20 310 Q 110 270, 230 320 T 430 300" opacity="0.22" />
        <path d="M-20 340 Q 120 300, 240 350 T 440 330" opacity="0.16" />
        <path d="M-20 370 Q 110 340, 230 380 T 430 365" opacity="0.10" />
        <path d="M-20 580 Q 100 540, 220 590 T 420 570" opacity="0.20" />
        <path d="M-20 610 Q 110 580, 230 620 T 430 600" opacity="0.15" />
        <path d="M-20 760 Q 90 720, 200 770 T 410 750"  opacity="0.16" />
        <path d="M-20 790 Q 100 760, 210 800 T 420 780" opacity="0.12" />
      </g>

      {/* Ghost-lines top-right (reference to handoff aesthetic) */}
      <g fill="none" stroke="#F0EEE7" strokeWidth="1" strokeLinecap="round">
        <path d="M260 60  Q 340 100, 410 80"  opacity="0.16" />
        <path d="M250 80  Q 330 120, 410 100" opacity="0.14" />
        <path d="M240 100 Q 320 140, 410 120" opacity="0.12" />
        <path d="M230 120 Q 310 160, 410 140" opacity="0.10" />
        <path d="M220 140 Q 300 180, 410 160" opacity="0.08" />
      </g>

      {/* Mirror ghost-curves on left */}
      <g fill="none" stroke="#F0EEE7" strokeWidth="1" strokeLinecap="round">
        <path d="M-20 480 Q 60 460, 140 490" opacity="0.10" />
        <path d="M-20 500 Q 60 480, 140 510" opacity="0.08" />
        <path d="M-20 520 Q 60 500, 140 530" opacity="0.06" />
      </g>

      {/* Brass orb-dots — (orb near logo intentionally absent) */}
      <circle cx="354" cy="490" r="6"   fill="url(#brand-orb)" opacity="0.9" />
      <circle cx="32"  cy="560" r="5"   fill="url(#brand-orb)" opacity="0.85" />
      <circle cx="370" cy="780" r="8"   fill="url(#brand-orb)" opacity="0.75" />
      <circle cx="20"  cy="900" r="6"   fill="url(#brand-orb)" opacity="0.6" />
      <circle cx="280" cy="340" r="4"   fill="url(#brand-orb)" opacity="0.85" />
      <circle cx="140" cy="240" r="3.5" fill="url(#brand-orb)" opacity="0.8" />

      {/* Dotted clusters */}
      <g fill="#C9A063" opacity="0.55">
        <circle cx="180" cy="40"  r="1.5" />
        <circle cx="200" cy="50"  r="1.5" />
        <circle cx="220" cy="60"  r="1.5" />
        <circle cx="240" cy="40"  r="1.5" />
        <circle cx="200" cy="68"  r="1.5" />
        <circle cx="222" cy="80"  r="1.5" />

        <circle cx="40"  cy="450" r="1.5" />
        <circle cx="60"  cy="460" r="1.5" />
        <circle cx="80"  cy="450" r="1.5" />
        <circle cx="100" cy="468" r="1.5" />
        <circle cx="50"  cy="470" r="1.5" />

        <circle cx="320" cy="690" r="1.5" />
        <circle cx="340" cy="700" r="1.5" />
        <circle cx="360" cy="690" r="1.5" />
        <circle cx="338" cy="712" r="1.5" />
      </g>

      {/* Lower-half repetition — masked into subtler presence */}
      <ellipse cx="320" cy="1200" rx="220" ry="200" fill="url(#brand-blob-navy)" opacity="0.75" />
      <ellipse cx="60"  cy="1380" rx="200" ry="190" fill="url(#brand-blob-navy)" opacity="0.70" />
      <ellipse cx="350" cy="1580" rx="210" ry="200" fill="url(#brand-blob-navy)" opacity="0.70" />
      <ellipse cx="80"  cy="1700" rx="190" ry="180" fill="url(#brand-blob-navy)" opacity="0.65" />
      <ellipse cx="340" cy="1320" rx="150" ry="130" fill="url(#brand-blob-brass)" opacity="0.85" />
      <ellipse cx="40"  cy="1620" rx="130" ry="120" fill="url(#brand-blob-brass)" opacity="0.70" />

      <g fill="none" stroke="#C9A063" strokeWidth="1.1" strokeLinecap="round">
        <path d="M-20 1100 Q 100 1060, 220 1110 T 420 1090" opacity="0.24" />
        <path d="M-20 1130 Q 110 1090, 230 1140 T 430 1120" opacity="0.18" />
        <path d="M-20 1160 Q 120 1120, 240 1170 T 440 1150" opacity="0.12" />
        <path d="M-20 1430 Q 100 1390, 220 1440 T 420 1420" opacity="0.20" />
        <path d="M-20 1460 Q 110 1430, 230 1470 T 430 1450" opacity="0.15" />
        <path d="M-20 1620 Q 90 1580, 200 1630 T 410 1610"  opacity="0.16" />
        <path d="M-20 1650 Q 100 1620, 210 1660 T 420 1640" opacity="0.12" />
      </g>
      <g fill="none" stroke="#F0EEE7" strokeWidth="1" strokeLinecap="round">
        <path d="M260 1240 Q 340 1280, 410 1260" opacity="0.12" />
        <path d="M250 1260 Q 330 1300, 410 1280" opacity="0.10" />
        <path d="M240 1280 Q 320 1320, 410 1300" opacity="0.08" />
        <path d="M-20 1500 Q 60 1480, 140 1510"  opacity="0.10" />
        <path d="M-20 1520 Q 60 1500, 140 1530"  opacity="0.08" />
      </g>
      <circle cx="50"  cy="1150" r="6"   fill="url(#brand-orb)" opacity="0.9" />
      <circle cx="360" cy="1280" r="5"   fill="url(#brand-orb)" opacity="0.8" />
      <circle cx="30"  cy="1430" r="4.5" fill="url(#brand-orb)" opacity="0.85" />
      <circle cx="350" cy="1500" r="7"   fill="url(#brand-orb)" opacity="0.75" />
      <circle cx="60"  cy="1700" r="5"   fill="url(#brand-orb)" opacity="0.7" />
      <circle cx="370" cy="1700" r="4"   fill="url(#brand-orb)" opacity="0.65" />

      <g fill="#C9A063" opacity="0.55">
        <circle cx="180" cy="1180" r="1.5" />
        <circle cx="200" cy="1190" r="1.5" />
        <circle cx="220" cy="1180" r="1.5" />
        <circle cx="238" cy="1196" r="1.5" />
        <circle cx="80"  cy="1380" r="1.5" />
        <circle cx="100" cy="1390" r="1.5" />
        <circle cx="120" cy="1380" r="1.5" />
        <circle cx="300" cy="1560" r="1.5" />
        <circle cx="320" cy="1572" r="1.5" />
        <circle cx="340" cy="1560" r="1.5" />
        <circle cx="180" cy="1680" r="1.5" />
        <circle cx="200" cy="1690" r="1.5" />
        <circle cx="220" cy="1680" r="1.5" />
      </g>
    </svg>
  </div>
);
