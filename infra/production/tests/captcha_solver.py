#!/usr/bin/env python3
"""Pure Python 3 stdlib CAPTCHA PNG decoder and login helper for Whale CRM."""

from __future__ import annotations

import argparse
import base64
from collections import deque
import http.cookiejar
import json
import os
from pathlib import Path
import struct
import sys
import urllib.error
import urllib.request
import zlib


class CaptchaError(RuntimeError):
    """Raised when a CAPTCHA challenge cannot be fetched or decoded reliably."""


class CaptchaParseError(CaptchaError):
    """Raised when an individual CAPTCHA PNG cannot be parsed with confidence."""


def _paeth_predictor(a: int, b: int, c: int) -> int:
    p = a + b - c
    pa = abs(p - a)
    pb = abs(p - b)
    pc = abs(p - c)
    if pa <= pb and pa <= pc:
        return a
    if pb <= pc:
        return b
    return c


def decode_png_rgb(png_bytes: bytes) -> tuple[int, int, list[list[tuple[int, int, int]]]]:
    """Decode an 8-bit non-interlaced RGB/RGBA PNG into (width, height, rgb_rows)."""
    if len(png_bytes) < 8 or png_bytes[:8] != b'\x89PNG\r\n\x1a\n':
        raise CaptchaParseError('Invalid PNG signature')

    pos = 8
    width = height = bit_depth = color_type = interlace = None
    idat_chunks: list[bytes] = []

    while pos + 8 <= len(png_bytes):
        length, chunk_type = struct.unpack('>I4s', png_bytes[pos:pos + 8])
        pos += 8
        if pos + length + 4 > len(png_bytes):
            raise CaptchaParseError('Truncated PNG chunk')
        data = png_bytes[pos:pos + length]
        pos += length + 4  # skip data + CRC

        if chunk_type == b'IHDR':
            if length != 13:
                raise CaptchaParseError('Invalid IHDR chunk length')
            width, height, bit_depth, color_type, comp, filt, interlace = struct.unpack('>IIBBBBB', data)
            if comp != 0 or filt != 0 or interlace != 0:
                raise CaptchaParseError(f'Unsupported PNG encoding: comp={comp}, filt={filt}, interlace={interlace}')
            if bit_depth != 8 or color_type not in (2, 6):
                raise CaptchaParseError(f'Unsupported PNG format: bit_depth={bit_depth}, color_type={color_type}')
        elif chunk_type == b'IDAT':
            idat_chunks.append(data)
        elif chunk_type == b'IEND':
            break

    if width is None or height is None or not idat_chunks:
        raise CaptchaParseError('Missing IHDR or IDAT chunk')

    bpp = 3 if color_type == 2 else 4
    stride = width * bpp
    raw = zlib.decompress(b''.join(idat_chunks))
    expected_len = height * (stride + 1)
    if len(raw) != expected_len:
        raise CaptchaParseError(f'Unexpected PNG raw length: {len(raw)} != {expected_len}')

    prev_row = bytearray(stride)
    rows: list[list[tuple[int, int, int]]] = []
    offset = 0

    for _ in range(height):
        filter_type = raw[offset]
        offset += 1
        scanline = bytearray(raw[offset:offset + stride])
        offset += stride

        if filter_type == 0:
            pass
        elif filter_type == 1:
            for i in range(bpp, stride):
                scanline[i] = (scanline[i] + scanline[i - bpp]) & 0xFF
        elif filter_type == 2:
            for i in range(stride):
                scanline[i] = (scanline[i] + prev_row[i]) & 0xFF
        elif filter_type == 3:
            for i in range(stride):
                left = scanline[i - bpp] if i >= bpp else 0
                up = prev_row[i]
                scanline[i] = (scanline[i] + ((left + up) >> 1)) & 0xFF
        elif filter_type == 4:
            for i in range(stride):
                a = scanline[i - bpp] if i >= bpp else 0
                b = prev_row[i]
                c = prev_row[i - bpp] if i >= bpp else 0
                scanline[i] = (scanline[i] + _paeth_predictor(a, b, c)) & 0xFF
        else:
            raise CaptchaParseError(f'Unsupported PNG filter type: {filter_type}')

        prev_row = scanline
        row_rgb = [
            (scanline[i], scanline[i + 1], scanline[i + 2])
            for i in range(0, stride, bpp)
        ]
        rows.append(row_rgb)

    return width, height, rows


def _extract_foreground_mask(rows: list[list[tuple[int, int, int]]]) -> list[list[bool]]:
    """Isolate dark-teal glyph pixels (36, 78, 76) while rejecting noise (>=105, >=145, >=140) and faint AA fringe."""
    return [
        [r < 90 and g < 130 and b < 126 for (r, g, b) in row]
        for row in rows
    ]


def _segment_tokens(mask: list[list[bool]]) -> list[list[list[bool]]]:
    """Split horizontal projection intervals into cropped 2D token masks."""
    height = len(mask)
    width = len(mask[0]) if height else 0
    col_has_ink = [any(mask[y][x] for y in range(height)) for x in range(width)]

    spans: list[tuple[int, int]] = []
    start: int | None = None
    for x, active in enumerate(col_has_ink):
        if active and start is None:
            start = x
        elif not active and start is not None:
            spans.append((start, x))
            start = None
    if start is not None:
        spans.append((start, width))

    tokens: list[list[list[bool]]] = []
    for x0, x1 in spans:
        ys = [y for y in range(height) if any(mask[y][x] for x in range(x0, x1))]
        if not ys:
            continue
        y0, y1 = min(ys), max(ys) + 1
        token = [row[x0:x1] for row in mask[y0:y1]]
        pixel_count = sum(1 for row in token for cell in row if cell)
        if pixel_count >= 4:
            tokens.append(token)
    return tokens


def _find_holes(token: list[list[bool]]) -> list[tuple[int, float, float]]:
    """Find enclosed background holes (area, norm_cx, norm_cy) inside a token mask."""
    h = len(token)
    w = len(token[0])
    padded_h = h + 2
    padded_w = w + 2
    visited = [[False] * padded_w for _ in range(padded_h)]

    def is_fg(py: int, px: int) -> bool:
        if 1 <= py <= h and 1 <= px <= w:
            return token[py - 1][px - 1]
        return False

    queue: deque[tuple[int, int]] = deque([(0, 0)])
    visited[0][0] = True
    while queue:
        cy, cx = queue.popleft()
        for dy, dx in ((-1, 0), (1, 0), (0, -1), (0, 1)):
            ny, nx = cy + dy, cx + dx
            if 0 <= ny < padded_h and 0 <= nx < padded_w and not visited[ny][nx] and not is_fg(ny, nx):
                visited[ny][nx] = True
                queue.append((ny, nx))

    min_hole_area = max(5, (w * h) // 60)
    holes: list[tuple[int, float, float]] = []
    for py in range(1, h + 1):
        for px in range(1, w + 1):
            if not visited[py][px] and not is_fg(py, px):
                comp: list[tuple[int, int]] = []
                q: deque[tuple[int, int]] = deque([(py, px)])
                visited[py][px] = True
                while q:
                    cy, cx = q.popleft()
                    comp.append((cy - 1, cx - 1))
                    for dy, dx in ((-1, 0), (1, 0), (0, -1), (0, 1)):
                        ny, nx = cy + dy, cx + dx
                        if 1 <= ny <= h and 1 <= nx <= w and not visited[ny][nx] and not is_fg(ny, nx):
                            visited[ny][nx] = True
                            q.append((ny, nx))
                if len(comp) >= min_hole_area:
                    avg_x = sum(x for _, x in comp) / len(comp) / max(1, w - 1)
                    avg_y = sum(y for y, _ in comp) / len(comp) / max(1, h - 1)
                    holes.append((len(comp), avg_x, avg_y))
    return holes


def _region_density(token: list[list[bool]], y0_frac: float, y1_frac: float, x0_frac: float, x1_frac: float) -> float:
    h = len(token)
    w = len(token[0])
    y0 = max(0, min(h - 1, int(round(y0_frac * h))))
    y1 = max(y0 + 1, min(h, int(round(y1_frac * h))))
    x0 = max(0, min(w - 1, int(round(x0_frac * w))))
    x1 = max(x0 + 1, min(w, int(round(x1_frac * w))))
    total = (y1 - y0) * (x1 - x0)
    filled = sum(1 for y in range(y0, y1) for x in range(x0, x1) if token[y][x])
    return filled / total


def _row_transitions(row: list[bool]) -> int:
    transitions = 0
    prev = False
    for cell in row:
        if cell and not prev:
            transitions += 1
        prev = cell
    return transitions


def _classify_operator(token: list[list[bool]]) -> str:
    h = len(token)
    w = len(token[0])
    if h <= 6 and w >= 6 and (w / h) >= 1.7:
        return '-'
    if h >= 8 and w >= 8 and 0.6 <= (w / h) <= 1.5:
        mid_row = max(
            sum(1 for x in range(w) if token[y][x]) / w
            for y in range(max(0, h // 2 - 1), min(h, h // 2 + 2))
        )
        mid_col = max(
            sum(1 for y in range(h) if token[y][x]) / h
            for x in range(max(0, w // 2 - 1), min(w, w // 2 + 2))
        )
        corners = (
            _region_density(token, 0.0, 0.25, 0.0, 0.25)
            + _region_density(token, 0.0, 0.25, 0.75, 1.0)
            + _region_density(token, 0.75, 1.0, 0.0, 0.25)
            + _region_density(token, 0.75, 1.0, 0.75, 1.0)
        ) / 4.0
        if mid_row >= 0.75 and mid_col >= 0.75 and corners <= 0.20:
            return '+'
    raise CaptchaParseError(f'Unrecognized operator glyph ({w}x{h})')


def _classify_digit(token: list[list[bool]]) -> int:
    h = len(token)
    w = len(token[0])
    if h < 12 or w < 3:
        raise CaptchaParseError(f'Digit glyph too small ({w}x{h})')

    holes = _find_holes(token)
    min_loop_area = max(5, (w * h) // 50)
    if len(holes) >= 2:
        # '8' must have two substantial, vertically separated enclosed loops (upper < 0.50, lower > 0.50).
        largest_two = sorted(holes, key=lambda item: item[0], reverse=True)[:2]
        upper_cy = min(largest_two[0][2], largest_two[1][2])
        lower_cy = max(largest_two[0][2], largest_two[1][2])
        if (
            largest_two[0][0] >= min_loop_area
            and largest_two[1][0] >= min_loop_area
            and upper_cy < 0.50
            and lower_cy > 0.50
        ):
            return 8
        raise CaptchaParseError(f'Ambiguous multi-hole digit ({w}x{h}): holes={holes}')

    if len(holes) == 1:
        hole_area, _, hole_cy = holes[0]
        if hole_area < min_loop_area:
            raise CaptchaParseError(f'Undersized hole ({hole_area} < {min_loop_area}) in {w}x{h}')
        # '6' has loop clearly in lower half (hole_cy > 0.58 across tested sans-serif fonts)
        if hole_cy > 0.58:
            return 6
        # Distinguish '4' vs '9' (both have hole in upper/middle half):
        # '4' ends at the bottom with a narrow right-side vertical stem; '9' curves across the bottom rows.
        last_rows_max_width = max(
            sum(1 for x in range(w) if token[y][x]) / w
            for y in range(max(0, h - 3), h)
        )
        # Measure strictly below '4''s crossbar (bottom ~15%, i.e., bottom 3 rows on 18-19px glyphs)
        # so crossbar anti-aliasing near 0.80*h does not disqualify '4'.
        bottom_left = _region_density(token, 0.85, 1.0, 0.0, 0.45)
        if last_rows_max_width <= 0.45 and (bottom_left <= 0.05 or (last_rows_max_width <= 0.35 and hole_cy >= 0.38)):
            return 4
        # Require positive geometric evidence for '9': upper-half loop AND bottom-left curve/tail.
        if hole_cy <= 0.40 and bottom_left >= 0.20 and (last_rows_max_width >= 0.45 or hole_cy <= 0.38):
            return 9
        raise CaptchaParseError(
            f'Ambiguous 1-hole digit: w={w}, h={h}, hole_cy={hole_cy:.2f}, '
            f'last_rows_max_width={last_rows_max_width:.2f}, bottom_left={bottom_left:.2f}'
        )

    # 0 holes: 1, 2, 3, 5, 7
    aspect = w / h
    top_fill = _region_density(token, 0.0, 0.20, 0.0, 1.0)
    bot_fill = _region_density(token, 0.82, 1.0, 0.0, 1.0)
    bot_right = _region_density(token, 0.78, 1.0, 0.55, 1.0)
    upper_mid_left = _region_density(token, 0.22, 0.42, 0.0, 0.40)
    upper_mid_right = _region_density(token, 0.22, 0.42, 0.58, 1.0)
    lower_mid_left = _region_density(token, 0.58, 0.80, 0.0, 0.40)
    lower_mid_right = _region_density(token, 0.58, 0.80, 0.58, 1.0)

    # Evaluate '7' BEFORE '1': strong top bar, weak bottom overall/right, and upper-right stroke (not '5''s upper-left bar)
    if (
        top_fill >= 0.55
        and bot_fill <= 0.50
        and bot_right <= 0.30
        and upper_mid_right > upper_mid_left + 0.15
    ):
        return 7

    # Measure horizontal extent of the lower-middle stem (y in 40%..78%, avoiding top flags and base serifs)
    stem_y0 = max(1, int(h * 0.40))
    stem_y1 = max(stem_y0 + 1, int(h * 0.78))
    stem_xs = [x for y in range(stem_y0, stem_y1) for x in range(w) if token[y][x]]
    stem_x_extent = ((max(stem_xs) - min(stem_xs) + 1) / w) if stem_xs else 1.0
    stem_max_runs = max((_row_transitions(token[y]) for y in range(stem_y0, stem_y1)), default=0)

    if aspect <= 0.46 or (stem_x_extent <= 0.56 and stem_max_runs <= 1):
        return 1

    # '5': upper-left stroke + lower-right curve
    if upper_mid_left > upper_mid_right + 0.15 and lower_mid_right > lower_mid_left + 0.15 and lower_mid_right >= 0.50:
        return 5

    # '2': upper-right curve + open lower-right + strong flat bottom bar
    if upper_mid_right > upper_mid_left + 0.15 and lower_mid_right <= 0.25 and bot_fill >= 0.65:
        return 2

    # '3': both upper-right and lower-right strokes present, left sides open
    if upper_mid_right > upper_mid_left + 0.15 and lower_mid_right > lower_mid_left + 0.15 and lower_mid_right >= 0.50:
        return 3

    # '3' in steep/narrow-waist sans-serif fonts (e.g. PT Sans): strong top/bottom bars, open left, heavy lower-right
    if (
        top_fill >= 0.50
        and bot_fill >= 0.55
        and upper_mid_left <= 0.40
        and lower_mid_right >= 0.55
        and lower_mid_right > lower_mid_left + 0.25
    ):
        return 3

    # Strictly guarded fallback for '2' under slight kerning/antialiasing shifts
    if bot_fill >= 0.65 and lower_mid_left > lower_mid_right + 0.10 and upper_mid_right > upper_mid_left + 0.10 and lower_mid_right <= 0.30:
        return 2

    raise CaptchaParseError(
        f'Unrecognized 0-hole digit ({w}x{h}): top={top_fill:.2f}, bot={bot_fill:.2f}, '
        f'ul={upper_mid_left:.2f}, ur={upper_mid_right:.2f}, ll={lower_mid_left:.2f}, lr={lower_mid_right:.2f}'
    )


def solve_captcha_png(png_bytes: bytes) -> str:
    """Decode a CaptchaService PNG and return the integer answer string."""
    _, _, rows = decode_png_rgb(png_bytes)
    mask = _extract_foreground_mask(rows)
    tokens = _segment_tokens(mask)
    if len(tokens) != 5:
        raise CaptchaParseError(f'Expected 5 tokens in captcha, got {len(tokens)}')

    left = _classify_digit(tokens[0])
    operator = _classify_operator(tokens[1])
    right = _classify_digit(tokens[2])

    # Basic sanity check for '=' (tokens[3]) and '?' (tokens[4])
    eq_h, eq_w = len(tokens[3]), len(tokens[3][0])
    q_h, q_w = len(tokens[4]), len(tokens[4][0])
    if eq_w < 6 or eq_h < 4 or q_h < 10 or q_w < 5:
        raise CaptchaParseError('Trailing "= ?" tokens failed sanity check')

    if operator == '+':
        return str(left + right)
    if left < right:
        raise CaptchaParseError(f'Unexpected negative subtraction {left} - {right}')
    return str(left - right)


def _fetch_captcha(opener: urllib.request.OpenerDirector, base_url: str, timeout: int = 30) -> tuple[str, bytes]:
    req = urllib.request.Request(base_url.rstrip('/') + '/api/v1/auth/captcha', headers={'Accept': 'application/json'}, method='GET')
    with opener.open(req, timeout=timeout) as resp:
        payload = json.load(resp)
    if payload.get('code') != 'OK':
        raise CaptchaError(f"Captcha endpoint returned {payload.get('code')}")
    data = payload.get('data') or {}
    captcha_id = data.get('captchaId')
    image_b64 = data.get('imageBase64')
    if not captcha_id or not image_b64:
        raise CaptchaError('Captcha response missing captchaId or imageBase64')
    if ',' in image_b64 and image_b64.startswith('data:'):
        image_b64 = image_b64.split(',', 1)[1]
    return captcha_id, base64.b64decode(image_b64)


def obtain_captcha_challenge(
    opener: urllib.request.OpenerDirector,
    base_url: str,
    max_attempts: int = 5,
    timeout: int = 30,
) -> tuple[str, str]:
    """Return (captchaId, captchaAnswer) from env overrides or solved /api/v1/auth/captcha."""
    env_id = os.environ.get('CRM_CAPTCHA_ID', '').strip()
    env_answer = os.environ.get('CRM_CAPTCHA_ANSWER', '').strip()
    if env_id and env_answer:
        return env_id, env_answer

    last_error: Exception | None = None
    for _ in range(max_attempts):
        captcha_id, png_bytes = _fetch_captcha(opener, base_url, timeout=timeout)
        try:
            answer = solve_captcha_png(png_bytes)
            return captcha_id, answer
        except CaptchaParseError as exc:
            last_error = exc
    raise CaptchaError(f'Failed to decode captcha after {max_attempts} attempts: {last_error}')


def login_with_captcha(
    opener: urllib.request.OpenerDirector,
    base_url: str,
    username: str,
    password: str,
    tenant_code: str | None = None,
    max_captcha_attempts: int = 5,
    timeout: int = 60,
) -> dict:
    """Fetch/solve captcha and call POST /api/v1/auth/login, returning the login data dict."""
    base = base_url.rstrip('/')
    captcha_id, captcha_answer = obtain_captcha_challenge(
        opener, base, max_attempts=max_captcha_attempts, timeout=min(timeout, 30)
    )
    body: dict[str, str] = {
        'username': username,
        'password': password,
        'captchaId': captcha_id,
        'captchaAnswer': captcha_answer,
    }
    if tenant_code:
        body['tenantCode'] = tenant_code

    req = urllib.request.Request(
        base + '/api/v1/auth/login',
        data=json.dumps(body).encode('utf-8'),
        headers={'Accept': 'application/json', 'Content-Type': 'application/json'},
        method='POST',
    )
    try:
        with opener.open(req, timeout=timeout) as resp:
            result = json.load(resp)
    except urllib.error.HTTPError as err:
        raw = err.read().decode('utf-8', errors='replace')
        raise CaptchaError(f'Login failed with HTTP {err.code}: {raw}') from err

    if result.get('code') != 'OK':
        raise CaptchaError(f"Login failed with API code {result.get('code')}: {result.get('message')}")
    return result.get('data') or {}


def _cli() -> int:
    parser = argparse.ArgumentParser(description='Solve CAPTCHA and log in without passing credentials on CLI argv.')
    parser.add_argument('--base-url', required=True)
    parser.add_argument('--username-env', default='CRM_USERNAME')
    parser.add_argument('--password-env', default='CRM_PASSWORD')
    parser.add_argument('--tenant-code-env', default='CRM_TENANT_CODE')
    parser.add_argument('--cookie-jar', type=Path, required=True)
    parser.add_argument('--out-json', type=Path, required=True)
    args = parser.parse_args()

    username = os.environ.get(args.username_env, '')
    password = os.environ.get(args.password_env, '')
    tenant_code = os.environ.get(args.tenant_code_env, '').strip() or None
    if not username or not password:
        raise SystemExit(f'Missing credentials in environment variables {args.username_env}/{args.password_env}')

    jar = http.cookiejar.MozillaCookieJar(str(args.cookie_jar))
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    data = login_with_captcha(opener, args.base_url, username, password, tenant_code=tenant_code)

    args.cookie_jar.parent.mkdir(parents=True, exist_ok=True)
    for cookie in jar:
        if cookie.expires is None:
            cookie.expires = 0
            cookie.discard = False
    jar.save(ignore_discard=True, ignore_expires=True)
    os.chmod(args.cookie_jar, 0o600)

    args.out_json.parent.mkdir(parents=True, exist_ok=True)
    args.out_json.write_text(json.dumps({'code': 'OK', 'data': data}, ensure_ascii=False) + '\n', encoding='utf-8')
    os.chmod(args.out_json, 0o600)
    return 0


if __name__ == '__main__':
    sys.exit(_cli())
