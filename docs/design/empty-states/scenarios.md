# Сюжеты лиса для пустых экранов

> Источник сценариев: `docs/backlog/empty-states-audit.md`. Канон персонажа:
> `ref/nano/fox-hero-full.png` (полный рост) — **прикладывать к каждой генерации** в nano banana.
> Пайплайн после генерации: вырезка фона/тени + PNG8 — рецепт в `docs/backlog/empty-states-handoff.md` §5.
> Сцена «Клуб · Активности» (лис в кафе) — ✅ уже в коде, здесь не повторяется.

## Общий префикс (копировать перед каждым сюжетом)

```
Edit the attached image, keep this EXACT fox character and art style: flat vector
cartoon, bold dark-orange outlines, glossy highlights, dark-brown trapezoid
sunglasses, unbuttoned cream blazer over white chest fur, sticker style with thick
white outline, plain white background, single character, full body visible,
no text, no letters, no words anywhere.
```

Где по сюжету нужны глаза — заменить упоминание очков на:
`sunglasses hooked on his blazer chest pocket (NOT on his face), big friendly amber eyes visible`.

---

## 1. Каталог: в городе нет клубов (P0)
Когда: новичок открывает «Поиск», в его городе ноль клубов. CTA «Создать свой клуб» + «Сменить город».
Идея экрана: не «пусто», а «непаханое поле — будь первым».

- **1a. Первопроходец:** `Scene: the fox proudly plants a tall flag with a simple orange spark emblem into the ground like an explorer claiming new land, one paw on his hip, confident smile, small cartoon hills horizon line under his feet only.`
- **1b. Дозорный с биноклем:** `Scene: the fox stands tall and looks far into the distance through binoculars held in both paws, leaning slightly forward with curiosity, his tail raised in excitement.`
- **1c. Афиша:** `Scene: the fox glues a blank poster sheet onto a street lamp post with a happy inviting smile, a paint brush in one paw, the blank poster shows only a big orange spark symbol, no words.`

## 2. Каталог: фильтры ничего не нашли (P1)
Когда: фильтры/поиск активны, результатов ноль. CTA «Сбросить фильтры».
Идея: честный сыщик — «искал, не нашёл, давай ослабим условия».

- **2a. Сыщик:** `Scene: the fox as a detective in a beige trench coat worn over his blazer, holding a big magnifying glass up to one sunglasses lens, the other paw spread palm-up in a light shrug — nothing found.`
- **2b. Картотека:** `Scene: the fox digs through an open card-file drawer box, holding one single blank card in his paw and raising an eyebrow above his sunglasses, one ear folded, slight puzzled smile.`
- **2c. Пустая коробка:** `Scene: the fox holds a cardboard box turned upside down, shaking it, only one tiny button falling out, his ears folded in comic disappointment, sunglasses slid down his nose a little.`

## 3. Ошибка загрузки / нет сети (P1, урок F5-20)
Когда: запрос упал — каталог, «Мои клубы», интересы, детали. CTA «Повторить».
Идея: техническая заминка с юмором, без паники — «сейчас воткнём обратно».

- **3a. Штекер:** `Scene: the fox holds a big unplugged electric plug with a curly cable in his paws right in front of his chest, looking down at it puzzled, mouth in a small o, one ear folded down.`
- **3b. Антенна:** `Scene: the fox as a repairman taps a small fallen satellite dish antenna with a wrench, other paw scratching his head, sunglasses pushed up onto his forehead, eyes visible and focused.`
- **3c. Запутался в проводах:** `Scene: the fox comically tangled in a long orange cable wrapped around his body and one raised paw, he smiles awkwardly, sunglasses tilted askew on his face.`

## 4. Активности (глобальный таб) · организатор без событий (P1)
Когда: у организатора есть клуб, но ни одного события. CTA «Создать событие».
Идея: планирование — «возьми карандаш, назначь дату».

- **4a. Карандаш и календарь:** `Scene: the fox in a cozy cream turtleneck sweater instead of the blazer, holding a huge orange pencil in one paw and a big blank paper calendar sheet in the other, inspired smile, sunglasses on his face.`
- **4b. Доска планов:** `Scene: the fox pins a blank sticky note onto a cork board with round pins, standing on his toes, tail up, focused happy expression, sunglasses on.`
- **4c. Дирижёр:** `Scene: the fox stands like an orchestra conductor with a thin baton raised in one paw, in front of a few empty simple chairs arranged in a semicircle, confident smile — the show is about to start.`

## 5. Профиль: интересы не заполнены (P1)
Когда: в профиле пустая секция интересов. CTA «Добавить интересы».
Идея: самовыражение — «покажи, что тебе близко».

- **5a. Значки на лацкан:** `Scene: the fox pins three big round blank badges onto his blazer lapel, one paw attaching a badge, proud smile, chest puffed out, sunglasses on.`
- **5b. Коробка хобби:** `Scene: the fox looks into an open box full of hobby items — a tiny guitar, a ball, a book, a chess piece — choosing with a delighted smile, sunglasses pushed up on his forehead, eyes visible and curious.`
- **5c. У зеркала:** `Scene: the fox in front of a simple standing mirror holds two different scarves, one orange and one cream, comparing them with a playful thinking face, sunglasses hooked on his blazer pocket, eyes visible.`

## 6. Управление · Статистика: новый клуб (P1)
Когда: владелец открывает «Статистику» клуба без встреч. CTA «Создать событие».
Идея: рост впереди — «цифры оживут после первой встречи».

- **6a. Тренер у графика:** `Scene: the fox in a cream sporty track jacket with a whistle on a cord around his neck, pointing up with one paw at a rising zigzag arrow chart drawn in the air, energetic smile, sunglasses on.`
- **6b. Садовник:** `Scene: the fox waters a small sprout with a watering can, the sprout grows in the shape of a rising arrow, he smiles warmly, sleeves of his blazer rolled up, sunglasses on.`
- **6c. Старт секундомера:** `Scene: the fox holds a big stopwatch in one paw, other paw raised like a race starter about to signal go, playful grin, tail up, sunglasses on.`

## 7. Приглашение недействительно (P1)
Когда: человек перешёл по мёртвой инвайт-ссылке. CTA «Смотреть клубы в каталоге».
Идея: мягкое «опоздал, но ничего страшного — вот дверь рядом».

- **7a. Порванный билет:** `Scene: the fox embarrassed, holding a torn paper ticket, one half in each paw, apologetic shy smile, one ear folded down, sunglasses on.`
- **7b. Закрытая дверь:** `Scene: the fox stands by a closed simple door with a blank hanging sign, scratching the back of his head with one paw, awkward smile, sunglasses hooked on his blazer pocket, eyes visible and kind.`
- **7c. Сдувшийся шарик:** `Scene: the fox looks at a small deflated orange balloon lying on his open palm, one eyebrow raised above his sunglasses, half-smile — the party has already ended.`

## 8. Мои клубы: пусто (эталон в проде — добавить маскота)
Когда: у новичка нет ни одного клуба. Две двери: «Найти клуб» / «Создать свой».
Идея: гостеприимство на пороге — это «прихожая» всего продукта.

- **8a. Канон-приветствие:** взять `fox-hero-full.png` как есть (машет) — нулевая работа, идеальная консистентность.
- **8b. Две двери:** `Scene: the fox stands between two simple doors, gesturing invitingly with both paws — one door marked with a magnifying glass icon, the other with a plus sign icon, welcoming smile, sunglasses on. Icons only, no letters.`
- **8c. Ключ:** `Scene: the fox holds up a big golden key with an orange spark-shaped keychain, offering it to the viewer with a warm smile, other paw on his hip, sunglasses on.`
- **8d. Ковровая дорожка:** `Scene: the fox unrolls a bright orange welcome carpet towards the viewer with a wide inviting gesture, the carpet unrolling from his paws to the front bottom edge, warm hospitable smile, sunglasses on.`
- **8e. Компас:** `Scene: the fox holds a big round golden compass in one paw and points forward with the other paw, adventurous smile, tail up, one foot stepping forward as if starting a journey, sunglasses on.`

## 9. Клуб создан (экран-поздравление после создания)
Когда: организатор только что создал клуб (сейчас там эмодзи 🎉). Идея: маленький праздник.

- **9a. Хлопушка:** `Scene: the fox joyfully fires a party popper cone held in one paw, orange and cream confetti and thin streamers bursting upward, his other paw raised in celebration, big happy open-mouth smile, tail up, sunglasses on.`

## 10. Онбординг — три слайда карусели первого входа
Тексты слайдов утверждены (docs/modules/onboarding.md) — лис их иллюстрирует, не дублирует.

- **10a. Слайд 1 «Объединяйтесь в Clubs, чтобы встречаться вживую»:** канон-приветствие (`fox-hero-full.png`, машет) как есть — ноль работы; либо объятия: `Scene: the fox throws both paws wide open in a warm welcoming gesture, leaning slightly forward as if greeting an old friend, joyful open smile, tail swishing, sunglasses on.`
- **10b. Слайд 2 «Ходи на встречи, которые готовят другие» (участник):** целый билет — намеренный контраст с порванным из сцены приглашения: `Scene: the fox happily holds up one big intact admission ticket with both paws, showing it to the viewer, excited smile, one foot stepping forward as if already heading to the event, sunglasses on.`
- **10c. Слайд 3 «Или веди свой клуб» (организатор):** `Scene: the fox as a confident host speaking into a small megaphone raised in one paw, the other paw waving invitingly, chest out, proud warm smile, tail up, sunglasses on.`

---

## Технические напоминания

- Один персонаж, полный рост, белый фон — префикс это уже требует; следить в выдаче.
- **Никакого текста в картинке** (модели любят вписывать буквы в таблички/постеры — префикс запрещает, но проверять глазами; таблички оставлять пустыми или с иконкой).
- Соотношение: квадрат или лёгкая вертикаль (3:4) — в UI слот ~162px шириной.
- После выбора картинки: пайплайн вырезки из handoff §5 (flood-fill фона, кеинг тени, PNG8) → `frontend/src/assets/mascot/`.
- Пар/блик/пыль добавляются не в картинку, а CSS-слоями (см. `.rd-foxcafe*`) — процентные якоря эффектов переснимать по факту арта.
