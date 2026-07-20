#!/usr/bin/env python3
"""Прямой вызов Recraft REST API — MCP-обёртка не даёт controls/negative_prompt и v4.1.

Ключ читаем из локального конфига Claude Code, чтобы не таскать секрет по командам.
Usage: gen.py <name> <prompt> [--model M] [--style S] [--substyle SS] [--n N] [--raster]
"""
import base64, json, os, sys, urllib.request, argparse, uuid, mimetypes

CFG = os.path.expanduser('~/.claude.json')
OUT = '/Users/ivanvarlamov/Desktop/Clubs 2.0/docs/design/empty-states/generated'
API = 'https://external.api.recraft.ai/v1/images/generations'
# image-to-image: исходник задаёт персонажа, strength — насколько далеко от него уходить.
API_I2I = 'https://external.api.recraft.ai/v1/images/imageToImage'

# Бренд Banco-Plata: акцент и фон приложения — модель обязана попадать в них, а не выдумывать палитру.
ACCENT = [244, 123, 60]   # #F47B3C
BG     = [11, 11, 14]     # #0B0B0E

# Паспорт героя: style_id переносит только манеру отрисовки (обводка/палитра/глянец),
# а анатомию и одежду модель выдумывает заново — без этого блока в сценах выходил
# пухлый лисёнок без пиджака вместо канона (стройный взрослый стиляга, hero-01.png).
# Вшивается в промпт каждой сцены флагом --hero: ДЕЙСТВИЕ идёт первым (иначе модель
# его теряет и повторяет позу исходника), паспорт — хвостом.
PASSPORT = ('The character is the exact same fox mascot as always: sleek slender ADULT fox with elegant '
            'proportions, long legs, bold dark-orange outlines, flat vector shading with glossy '
            'highlights, big triangular pointed ears with cream inner fur, white angular muzzle and '
            'cheek fur ending in sharp points, small dark-brown nose, confident sly smile, '
            '{glasses}{outfit}fluffy orange tail with cream tip. ')
# Очки на морде — по умолчанию; где нужны глаза (дремлет, растерялся) очки уходят в реквизит,
# и их место описывает сам промпт сцены (на вороте, в лапе).
GLASSES_ON = 'dark-brown retro trapezoid sunglasses on his face, '
# Гардероб по умолчанию — канон; сцены могут переодевать лиса через --outfit,
# но одежду стоит продублировать и в начале промпта сцены — из хвоста модель её теряет.
OUTFIT = 'wearing an unbuttoned cream blazer with sleeves pushed up over his white chest fur, '

# Анти-лисёнок: в сценах модель стабильно скатывалась в чиби-детёныша.
NEG_CHIBI = ', chibi, baby animal, toddler, chubby, plump round body, red panda, short stubby legs'


def key():
    c = json.load(open(CFG))
    for scope in (c.get('projects', {}).get('/Users/ivanvarlamov/Desktop/Clubs 2.0', {}), c):
        s = (scope.get('mcpServers') or {}).get('recraft')
        if s and s.get('env', {}).get('RECRAFT_API_KEY'):
            return s['env']['RECRAFT_API_KEY']
    sys.exit('нет ключа в ~/.claude.json')


def main():
    p = argparse.ArgumentParser()
    p.add_argument('name'); p.add_argument('prompt')
    p.add_argument('--model', default='recraftv4_1_pro_vector')
    p.add_argument('--style', default='vector_illustration')
    p.add_argument('--substyle', default=None)
    p.add_argument('--style-id', default=None)
    p.add_argument('--n', type=int, default=1)
    p.add_argument('--size', default='1024x1024')
    p.add_argument('--no-bg', action='store_true', help='не фиксировать цвет фона')
    p.add_argument('--free-color', action='store_true', help='не запирать палитру в бренд-акцент (нужно для 3D-персонажа со своими цветами)')
    p.add_argument('--colors', default=None, help='палитра персонажа "r,g,b;r,g,b" — у 3D-героя их несколько (тело/пузо/обводка)')
    p.add_argument('--hero', action='store_true', help='вшить паспорт героя в промпт (сцены с нашим лисом)')
    p.add_argument('--no-glasses', action='store_true', help='очки не на морде: место очков задаёт промпт сцены')
    p.add_argument('--outfit', default=None, help='одежда сцены вместо кремового пиджака ("wearing ...")')
    p.add_argument('--i2i', default=None, metavar='IMG', help='image-to-image: путь к исходнику (канон героя)')
    p.add_argument('--strength', type=float, default=0.5, help='i2i: 0=копия исходника, 1=совсем другой рисунок')
    args = p.parse_args()

    if args.hero:
        outfit = (args.outfit.rstrip(', ') + ', ') if args.outfit else OUTFIT
        args.prompt = (args.prompt.rstrip('. ') + '. '
                       + PASSPORT.format(glasses='' if args.no_glasses else GLASSES_ON, outfit=outfit))

    # v4.1 Pro Vector не поддерживает no_text — шлём только там, где принимают.
    # Персонажу в 3D-стиле нужна СВОЯ палитра (у уточки: жёлтое тело + оранжевый клюв + синий
    # телефон) — лок на один бренд-акцент её убивает, поэтому цвет фиксируем не всегда.
    controls = {} if args.free_color else {'colors': [{'rgb': ACCENT}]}
    if args.colors:
        controls['colors'] = [{'rgb': [int(x) for x in c.split(',')]} for c in args.colors.split(';')]
    # no_text принимает ТОЛЬКО recraftv3: и v2, и v4.1 Pro Vector на нём падают с 400.
    if args.model.startswith('recraftv3'):
        controls['no_text'] = True
    if not args.no_bg:
        controls['background_color'] = {'rgb': BG}

    body = {
        'prompt': args.prompt,
        'model': args.model,
        'size': args.size,
        'n': args.n,
        'response_format': 'b64_json',
        'controls': controls,
    }
    # v4.1 отвергает negative_prompt («cannot be specified for the selected model») — шлём только там,
    # где он поддерживается; для v4.1 лишнее отсекаем формулировкой промпта и controls.
    if args.model.startswith('recraftv3') or args.model.startswith('recraftv2'):
        body['negative_prompt'] = ('scenery, landscape, background objects, bushes, trees, grass, '
                                   'ground line, horizon, frame, border, text, letters, watermark, clutter'
                                   + (NEG_CHIBI if args.hero else '')
                                   + (', sunglasses on his face, glasses over eyes' if args.no_glasses else ''))
    if args.style_id:
        body['style_id'] = args.style_id
    else:
        body['style'] = args.style
        if args.substyle:
            body['substyle'] = args.substyle

    if args.i2i:
        # multipart вручную: у i2i-эндпоинта поля формой, вложенные controls он не принимает —
        # цвета и так приходят из исходника + style_id.
        body.pop('controls', None)
        body.pop('size', None)
        body['strength'] = args.strength
        boundary = uuid.uuid4().hex
        parts = b''
        for k, v in body.items():
            parts += (f'--{boundary}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n').encode()
        img = open(args.i2i, 'rb').read()
        ctype = mimetypes.guess_type(args.i2i)[0] or 'image/png'
        parts += (f'--{boundary}\r\nContent-Disposition: form-data; name="image"; '
                  f'filename="{os.path.basename(args.i2i)}"\r\nContent-Type: {ctype}\r\n\r\n').encode()
        parts += img + f'\r\n--{boundary}--\r\n'.encode()
        req = urllib.request.Request(
            API_I2I, data=parts,
            headers={'Authorization': f'Bearer {key()}',
                     'Content-Type': f'multipart/form-data; boundary={boundary}'})
    else:
        req = urllib.request.Request(
            API, data=json.dumps(body).encode(),
            headers={'Authorization': f'Bearer {key()}', 'Content-Type': 'application/json'})
    try:
        res = json.load(urllib.request.urlopen(req, timeout=180))
    except urllib.error.HTTPError as e:
        sys.exit(f'HTTP {e.code}: {e.read().decode()[:600]}')

    def sniff(raw):
        # Со style_id формат заранее неизвестен — определяем по содержимому, а не по имени модели.
        head = raw[:200].lstrip()
        return 'svg' if head.startswith(b'<svg') or head.startswith(b'<?xml') else 'png'
    os.makedirs(OUT, exist_ok=True)
    for i, item in enumerate(res.get('data', [])):
        # b64_json, а не url: подписанные ссылки Recraft отдают 403 на urllib — забираем тело сразу.
        raw = base64.b64decode(item['b64_json'])
        fn = f'{OUT}/{args.name}{"" if args.n == 1 else f"-{i+1}"}.{sniff(raw)}'
        with open(fn, 'wb') as f:
            f.write(raw)
        print(fn)


if __name__ == '__main__':
    main()
