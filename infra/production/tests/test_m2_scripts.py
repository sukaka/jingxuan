#!/usr/bin/env python3
"""Offline tests for M2 captcha solver, smoke scripts, load-smoke.sh, and install.sh."""

from __future__ import annotations

import base64
from concurrent.futures import ThreadPoolExecutor
from functools import lru_cache
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import http.cookiejar
import json
import os
from pathlib import Path
import shlex
import shutil
import stat
import struct
import subprocess
import sys
import tempfile
import threading
import unittest
import urllib.request
import zlib

TESTS_DIR = Path(__file__).resolve().parent
REPO_ROOT = TESTS_DIR.parents[2]
sys.path.insert(0, str(TESTS_DIR))

import captcha_solver  # noqa: E402


def _make_png(width: int, height: int, rows: list[list[tuple[int, int, int, int]]], *, rgba: bool = False, filter_type: int = 0) -> bytes:
    """Build a valid PNG with a chosen scanline filter type (0..4) for decoder testing."""
    bpp = 4 if rgba else 3
    stride = width * bpp
    raw_lines = bytearray()
    prev = bytearray(stride)

    for y in range(height):
        cur = bytearray()
        for x in range(width):
            r, g, b, a = rows[y][x]
            cur.extend((r, g, b, a) if rgba else (r, g, b))
        raw_lines.append(filter_type)
        if filter_type == 0:
            raw_lines.extend(cur)
        elif filter_type == 1:
            for i in range(stride):
                left = cur[i - bpp] if i >= bpp else 0
                raw_lines.append((cur[i] - left) & 0xFF)
        elif filter_type == 2:
            for i in range(stride):
                raw_lines.append((cur[i] - prev[i]) & 0xFF)
        elif filter_type == 3:
            for i in range(stride):
                left = cur[i - bpp] if i >= bpp else 0
                up = prev[i]
                raw_lines.append((cur[i] - ((left + up) >> 1)) & 0xFF)
        elif filter_type == 4:
            for i in range(stride):
                a = cur[i - bpp] if i >= bpp else 0
                b_val = prev[i]
                c = prev[i - bpp] if i >= bpp else 0
                raw_lines.append((cur[i] - captcha_solver._paeth_predictor(a, b_val, c)) & 0xFF)
        else:
            raise ValueError(filter_type)
        prev = cur

    def chunk(ctype: bytes, data: bytes) -> bytes:
        return struct.pack('>I', len(data)) + ctype + data + struct.pack('>I', zlib.crc32(ctype + data) & 0xFFFFFFFF)

    color_type = 6 if rgba else 2
    ihdr = struct.pack('>IIBBBBB', width, height, 8, color_type, 0, 0, 0)
    idat = zlib.compress(bytes(raw_lines))
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', ihdr) + chunk(b'IDAT', idat) + chunk(b'IEND', b'')


JAVA_CAPTCHA_GEN = r"""
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.Base64;
import javax.imageio.ImageIO;

public class GenCaptchaTest {
    public static void main(String[] args) throws Exception {
        SecureRandom random = new SecureRandom();
        for (int left = 1; left <= 9; left++) {
            for (int right = 1; right <= 9; right++) {
                for (boolean addition : new boolean[]{true, false}) {
                    int l = left, r = right;
                    if (!addition && l < r) { int t = l; l = r; r = t; }
                    String q = l + (addition ? " + " : " \u2212 ") + r + " = ?";
                    int ans = addition ? l + r : l - r;
                    BufferedImage image = new BufferedImage(160, 52, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = image.createGraphics();
                    g.setColor(new Color(246, 249, 248));
                    g.fillRect(0, 0, 160, 52);
                    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    for (int i = 0; i < 5; i++) {
                        g.setColor(new Color(105 + random.nextInt(90), 145 + random.nextInt(65), 140 + random.nextInt(65)));
                        g.setStroke(new BasicStroke(1.2f));
                        g.drawLine(random.nextInt(160), random.nextInt(52), random.nextInt(160), random.nextInt(52));
                    }
                    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 25));
                    g.setColor(new Color(36, 78, 76));
                    g.drawString(q, 18 + random.nextInt(8), 35 + random.nextInt(4));
                    g.dispose();
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    ImageIO.write(image, "png", out);
                    System.out.println(ans + "\t" + q + "\t" + Base64.getEncoder().encodeToString(out.toByteArray()));
                }
            }
        }
    }
}
"""


JAVA_ADVERSARIAL_CAPTCHA_GEN = r"""
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;
import javax.imageio.ImageIO;

public class GenAdversarialCaptchaTest {
    public static void main(String[] args) throws Exception {
        Set<String> avail = new HashSet<>(Arrays.asList(
            GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()
        ));
        String[] candidates = {
            Font.SANS_SERIF, Font.DIALOG, "Arial", "Helvetica", "Helvetica Neue",
            "Lucida Sans", "Lucida Grande", "Noto Sans SC", "PT Sans", "Verdana", "Tahoma"
        };
        int[][] jitters = {{18, 35}, {21, 38}, {23, 36}, {25, 37}};

        // 1. Deterministic intersecting (105, 145, 140) noise lines on SansSerif (including 4+1, 4+3, 6+2, 6+4)
        int[][] worstPairs = {
            {4, 1}, {4, 3}, {4, 5}, {4, 9},
            {6, 2}, {6, 4}, {6, 6}, {6, 9},
            {7, 3}, {3, 7}, {2, 8}, {9, 1}
        };
        for (int[] pair : worstPairs) {
            for (int[] j : jitters) {
                for (boolean addition : new boolean[]{true, false}) {
                    int l = pair[0], r = pair[1];
                    if (!addition && l < r) { int t = l; l = r; r = t; }
                    String q = l + (addition ? " + " : " \u2212 ") + r + " = ?";
                    int ans = addition ? l + r : l - r;
                    BufferedImage image = new BufferedImage(160, 52, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = image.createGraphics();
                    g.setColor(new Color(246, 249, 248));
                    g.fillRect(0, 0, 160, 52);
                    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g.setColor(new Color(105, 145, 140));
                    g.setStroke(new BasicStroke(1.2f));
                    g.drawLine(15, 18, 145, 38);
                    g.drawLine(18, 40, 140, 16);
                    g.drawLine(25, 14, 30, 42);
                    g.drawLine(50, 14, 70, 42);
                    g.drawLine(10, 28, 150, 28);
                    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 25));
                    g.setColor(new Color(36, 78, 76));
                    g.drawString(q, j[0], j[1]);
                    g.dispose();
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    ImageIO.write(image, "png", out);
                    System.out.println("WORST\t" + Font.SANS_SERIF + "\t" + ans + "\t" + q + "\t" + j[0] + "," + j[1] + "\t" + Base64.getEncoder().encodeToString(out.toByteArray()));
                }
            }
        }

        // 1b. Arial '4' and '9' under WORST1 intersecting (105, 145, 140) noise lines (including (23, 36) and size 24/25)
        if (avail.contains("Arial")) {
            int[][] arialPairs25 = {{4, 1}, {4, 5}, {4, 9}, {9, 4}, {9, 1}};
            int[][] arialPairs24 = {{4, 5}, {4, 9}, {9, 4}};
            for (int fontSize : new int[]{24, 25}) {
                int[][] pairs = (fontSize == 25) ? arialPairs25 : arialPairs24;
                for (int[] pair : pairs) {
                    for (int[] j : jitters) {
                        for (boolean addition : new boolean[]{true, false}) {
                            int l = pair[0], r = pair[1];
                            if (!addition && l < r) { int t = l; l = r; r = t; }
                            String q = l + (addition ? " + " : " \u2212 ") + r + " = ?";
                            int ans = addition ? l + r : l - r;
                            BufferedImage image = new BufferedImage(160, 52, BufferedImage.TYPE_INT_RGB);
                            Graphics2D g = image.createGraphics();
                            g.setColor(new Color(246, 249, 248));
                            g.fillRect(0, 0, 160, 52);
                            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                            g.setColor(new Color(105, 145, 140));
                            g.setStroke(new BasicStroke(1.2f));
                            g.drawLine(18, 22, 130, 42);
                            g.drawLine(18, 42, 130, 22);
                            g.drawLine(22, 22, 22, 42);
                            g.drawLine(45, 22, 65, 42);
                            g.drawLine(18, 32, 130, 32);
                            g.setFont(new Font("Arial", Font.BOLD, fontSize));
                            g.setColor(new Color(36, 78, 76));
                            g.drawString(q, j[0], j[1]);
                            g.dispose();
                            ByteArrayOutputStream out = new ByteArrayOutputStream();
                            ImageIO.write(image, "png", out);
                            System.out.println("WORST1_ARIAL_" + fontSize + "\tArial\t" + ans + "\t" + q + "\t" + j[0] + "," + j[1] + "\t" + Base64.getEncoder().encodeToString(out.toByteArray()));
                        }
                    }
                }
            }
        }

        // 1c. Deterministic FuzzNoise (seed 20261120L) targeting reported edge cases (242, 592, 1482, 1914, 2776)
        String[] fuzzFonts = {
            Font.SANS_SERIF, Font.DIALOG, "Arial", "Helvetica",
            "Noto Sans SC", "PT Sans", "Verdana", "Tahoma"
        };
        Set<Integer> fuzzTargets = new HashSet<>(Arrays.asList(242, 592, 1482, 1914, 2776));
        Random fuzzRng = new Random(20261120L);
        for (int i = 0; i < 3200; i++) {
            String font = fuzzFonts[i % fuzzFonts.length];
            int l = 1 + fuzzRng.nextInt(9);
            int r = 1 + fuzzRng.nextInt(9);
            boolean add = fuzzRng.nextBoolean();
            if (!add && l < r) { int t = l; l = r; r = t; }
            String q = l + (add ? " + " : " \u2212 ") + r + " = ?";
            int ans = add ? l + r : l - r;
            int x = 18 + fuzzRng.nextInt(8);
            int y = 35 + fuzzRng.nextInt(4);
            BufferedImage image = new BufferedImage(160, 52, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            g.setColor(new Color(246, 249, 248));
            g.fillRect(0, 0, 160, 52);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            for (int k = 0; k < 5; k++) {
                g.setColor(new Color(105, 145, 140));
                g.setStroke(new BasicStroke(1.2f));
                g.drawLine(fuzzRng.nextInt(160), fuzzRng.nextInt(52), fuzzRng.nextInt(160), fuzzRng.nextInt(52));
            }
            g.setFont(new Font(font, Font.BOLD, 25));
            g.setColor(new Color(36, 78, 76));
            g.drawString(q, x, y);
            g.dispose();
            if (fuzzTargets.contains(i)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ImageIO.write(image, "png", out);
                System.out.println("FUZZ_" + i + "\t" + font + "\t" + ans + "\t" + q + "\t" + x + "," + y + "\t" + Base64.getEncoder().encodeToString(out.toByteArray()));
            }
        }

        // 2. Multi-font sans-serif suite covering digits 1..9 with + and -
        for (String font : candidates) {
            if (!font.equals(Font.SANS_SERIF) && !font.equals(Font.DIALOG) && !avail.contains(font)) {
                continue;
            }
            for (int d = 1; d <= 9; d++) {
                for (boolean addition : new boolean[]{true, false}) {
                    int l = d, r = (d % 9) + 1;
                    if (!addition && l < r) { int t = l; l = r; r = t; }
                    String q = l + (addition ? " + " : " \u2212 ") + r + " = ?";
                    int ans = addition ? l + r : l - r;
                    BufferedImage image = new BufferedImage(160, 52, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = image.createGraphics();
                    g.setColor(new Color(246, 249, 248));
                    g.fillRect(0, 0, 160, 52);
                    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g.setFont(new Font(font, Font.BOLD, 25));
                    g.setColor(new Color(36, 78, 76));
                    g.drawString(q, 21, 37);
                    g.dispose();
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    ImageIO.write(image, "png", out);
                    System.out.println("FONT\t" + font + "\t" + ans + "\t" + q + "\t21,37\t" + Base64.getEncoder().encodeToString(out.toByteArray()));
                }
            }
        }
    }
}
"""


@lru_cache(maxsize=1)
def _render_java_captchas() -> list[tuple[str, str, bytes]]:
    if not shutil.which('java'):
        return []
    with tempfile.TemporaryDirectory() as td:
        src = Path(td) / 'GenCaptchaTest.java'
        src.write_text(JAVA_CAPTCHA_GEN, encoding='utf-8')
        proc = subprocess.run(['java', str(src)], capture_output=True, text=True, check=True)
    items = []
    for line in proc.stdout.splitlines():
        ans, question, b64 = line.split('\t')
        items.append((ans, question, base64.b64decode(b64)))
    return items


@lru_cache(maxsize=1)
def _render_adversarial_java_captchas() -> list[tuple[str, str, str, str, str, bytes]]:
    if not shutil.which('java'):
        return []
    with tempfile.TemporaryDirectory() as td:
        src = Path(td) / 'GenAdversarialCaptchaTest.java'
        src.write_text(JAVA_ADVERSARIAL_CAPTCHA_GEN, encoding='utf-8')
        proc = subprocess.run(['java', str(src)], capture_output=True, text=True, check=True)
    items = []
    for line in proc.stdout.splitlines():
        mode, font, ans, question, jitter, b64 = line.split('\t')
        items.append((mode, font, ans, question, jitter, base64.b64decode(b64)))
    return items


class TestCaptchaSolver(unittest.TestCase):
    def test_png_decoder_all_filters_rgb_and_rgba(self) -> None:
        rows = [
            [((10 * x + 20 * y) % 256, (30 + x) % 256, (70 + y) % 256, 255) for x in range(8)]
            for y in range(6)
        ]
        expected = [[(r, g, b) for (r, g, b, _) in row] for row in rows]
        for rgba in (False, True):
            for filter_type in range(5):
                png = _make_png(8, 6, rows, rgba=rgba, filter_type=filter_type)
                w, h, decoded = captcha_solver.decode_png_rgb(png)
                self.assertEqual((w, h), (8, 6))
                self.assertEqual(decoded, expected)

    def test_rejects_corrupt_png(self) -> None:
        with self.assertRaises(captcha_solver.CaptchaParseError):
            captcha_solver.solve_captcha_png(b'not-a-png')

    def test_micro_hole_noise_pocket_does_not_classify_as_eight(self) -> None:
        # Construct a '4'-like 14x18 mask with a real upper loop plus a 2-pixel noise pocket at bottom
        rows = [[False] * 14 for _ in range(18)]
        # Upper loop box: rows 2..9, cols 3..10 (hole inside rows 3..8, cols 4..9 -> 30 px)
        for x in range(3, 11):
            rows[2][x] = True
            rows[9][x] = True
        for y in range(2, 10):
            rows[y][3] = True
            rows[y][10] = True
        # Right vertical stem
        for y in range(10, 18):
            rows[y][9] = True
            rows[y][10] = True
        # Add a tiny 2-pixel enclosed noise pocket on the right stem inside rows 11..15, cols 7..10
        for y in range(11, 16):
            for x in range(7, 11):
                rows[y][x] = True
        rows[13][8] = False
        rows[13][9] = False
        holes = captcha_solver._find_holes(rows)
        self.assertEqual(len(holes), 1)
        self.assertEqual(captcha_solver._classify_digit(rows), 4)

        # Two large holes in the SAME vertical half must raise CaptchaParseError (never return 8)
        same_half = [[False] * 14 for _ in range(18)]
        for y in range(1, 8):
            for x in range(1, 13):
                same_half[y][x] = True
        for y in range(2, 6):
            for x in range(2, 5):
                same_half[y][x] = False
            for x in range(8, 11):
                same_half[y][x] = False
        with self.assertRaises(captcha_solver.CaptchaParseError):
            captcha_solver._classify_digit(same_half)

    def test_four_vs_nine_crossbar_aa_and_ambiguous_guard(self) -> None:
        # Exact Arial '4' bitmap under WORST1 (23, 36): crossbar AA at y=14 and widened stem at y=16
        arial_4 = [
            list('.......###..'),
            list('......####..'),
            list('.....#####..'),
            list('....######..'),
            list('....######..'),
            list('...###.###..'),
            list('..####.###..'),
            list('..###..###..'),
            list('.####..###..'),
            list('.###...###..'),
            list('###....###..'),
            list('############'),
            list('############'),
            list('############'),
            list('.##....###..'),
            list('.......###..'),
            list('......#####.'),
            list('.......###..'),
        ]
        arial_4_mask = [[ch == '#' for ch in row] for row in arial_4]
        self.assertEqual(captcha_solver._classify_digit(arial_4_mask), 4)

        # Ambiguous 1-hole digit: crossbar at y=11..13, widened bottom stem at y=16 (6/12 = 0.50 > 0.45),
        # and NO bottom-left curve/tail -> must raise CaptchaParseError rather than silently returning 9.
        ambiguous_rows = [row[:] for row in arial_4]
        ambiguous_rows[16] = list('......######')
        ambiguous_mask = [[ch == '#' for ch in row] for row in ambiguous_rows]
        with self.assertRaises(captcha_solver.CaptchaParseError):
            captcha_solver._classify_digit(ambiguous_mask)

        # Genuine '9': upper-half loop + bottom curve/tail across left & bottom rows -> returns 9.
        genuine_9 = [
            list('..########..'),
            list('.##########.'),
            list('####....####'),
            list('###......###'),
            list('###......###'),
            list('###......###'),
            list('.####...####'),
            list('..##########'),
            list('...######.##'),
            list('.........###'),
            list('.........###'),
            list('.........###'),
            list('........####'),
            list('.###....####'),
            list('.###...####.'),
            list('.##########.'),
            list('..########..'),
            list('...######...'),
        ]
        genuine_9_mask = [[ch == '#' for ch in row] for row in genuine_9]
        self.assertEqual(captcha_solver._classify_digit(genuine_9_mask), 9)

    def test_five_with_bottom_noise_does_not_misclassify_as_seven(self) -> None:
        # SansSerif '5' stretched to h=19 by a 1-pixel noise speck at row 18
        noisy_5 = [
            list('.##########.'),
            list('.##########.'),
            list('.#########..'),
            list('.##.........'),
            list('###.........'),
            list('.##.........'),
            list('.##.........'),
            list('.#######....'),
            list('.########...'),
            list('.....#####..'),
            list('.......####.'),
            list('.......####.'),
            list('.......#####'),
            list('.......#####'),
            list('.......####.'),
            list('#.....####..'),
            list('#########...'),
            list('########....'),
            list('..#.........'),
        ]
        noisy_5_mask = [[ch == '#' for ch in row] for row in noisy_5]
        got = captcha_solver._classify_digit(noisy_5_mask)
        self.assertEqual(got, 5)

    def test_java21_captcha_service_100_percent_accuracy(self) -> None:
        samples = _render_java_captchas()
        if not samples:
            self.skipTest('java runtime not available on PATH')
        self.assertEqual(len(samples), 162)
        for expected_ans, question, png in samples:
            with self.subTest(question=question):
                self.assertEqual(captcha_solver.solve_captcha_png(png), expected_ans)

    def test_java21_adversarial_intersecting_noise_and_cross_font_zero_wrong_answers(self) -> None:
        samples = _render_adversarial_java_captchas()
        if not samples:
            self.skipTest('java runtime not available on PATH')
        self.assertGreaterEqual(len(samples), 198)
        for mode, font, expected_ans, question, jitter, png in samples:
            with self.subTest(mode=mode, font=font, question=question, jitter=jitter):
                if mode.startswith('FUZZ_'):
                    try:
                        got = captcha_solver.solve_captcha_png(png)
                    except captcha_solver.CaptchaParseError:
                        continue
                    self.assertEqual(got, expected_ans)
                else:
                    self.assertEqual(captcha_solver.solve_captcha_png(png), expected_ans)

    def test_obtain_captcha_env_override_short_circuits_http(self) -> None:
        old_id = os.environ.get('CRM_CAPTCHA_ID')
        old_ans = os.environ.get('CRM_CAPTCHA_ANSWER')
        try:
            os.environ['CRM_CAPTCHA_ID'] = 'override-id-123'
            os.environ['CRM_CAPTCHA_ANSWER'] = '11'
            opener = urllib.request.build_opener()
            cid, ans = captcha_solver.obtain_captcha_challenge(opener, 'http://127.0.0.1:1')
            self.assertEqual((cid, ans), ('override-id-123', '11'))
        finally:
            if old_id is None:
                os.environ.pop('CRM_CAPTCHA_ID', None)
            else:
                os.environ['CRM_CAPTCHA_ID'] = old_id
            if old_ans is None:
                os.environ.pop('CRM_CAPTCHA_ANSWER', None)
            else:
                os.environ['CRM_CAPTCHA_ANSWER'] = old_ans


class TestMockAuthAndLoadSmoke(unittest.TestCase):
    def test_login_retries_ambiguous_captcha_and_load_smoke_passes(self) -> None:
        samples = _render_java_captchas()
        if not samples:
            self.skipTest('java runtime not available on PATH')
        valid_ans, _, valid_png = samples[0]
        valid_b64 = base64.b64encode(valid_png).decode('ascii')

        blank_rows = [[(246, 249, 248, 255) for _ in range(160)] for _ in range(52)]
        bad_b64 = base64.b64encode(_make_png(160, 52, blank_rows)).decode('ascii')

        state = {'captcha_calls': 0, 'login_calls': 0, 'last_login': None}
        lock = threading.Lock()

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, format, *args):  # noqa: A002
                return

            def _send_json(self, code: int, payload: dict, cookies: list[str] | None = None) -> None:
                body = json.dumps(payload).encode('utf-8')
                self.send_response(code)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(body)))
                if cookies:
                    for c in cookies:
                        self.send_header('Set-Cookie', c)
                self.end_headers()
                self.wfile.write(body)

            def do_GET(self) -> None:  # noqa: N802
                if self.path == '/actuator/health':
                    self._send_json(200, {'status': 'UP'})
                    return
                if self.path == '/api/v1/auth/captcha':
                    with lock:
                        state['captcha_calls'] += 1
                        call_no = state['captcha_calls']
                    if call_no == 1:
                        self._send_json(200, {'code': 'OK', 'data': {'captchaId': 'bad-1', 'imageBase64': bad_b64, 'expiresIn': 300}})
                    else:
                        self._send_json(200, {'code': 'OK', 'data': {'captchaId': f'good-{call_no}', 'imageBase64': valid_b64, 'expiresIn': 300}})
                    return
                if self.path.startswith('/api/v1/customers') or self.path == '/api/v1/settlements/reconciliation':
                    cookie = self.headers.get('Cookie', '')
                    if 'CRM_TOKEN=tok-secret' not in cookie or 'CRM_CSRF=csrf-secret' not in cookie:
                        self._send_json(401, {'code': 'UNAUTHORIZED'})
                        return
                    self._send_json(200, {'code': 'OK', 'data': {'items': []}})
                    return
                self._send_json(404, {'code': 'NOT_FOUND'})

            def do_POST(self) -> None:  # noqa: N802
                if self.path == '/api/v1/auth/login':
                    length = int(self.headers.get('Content-Length', '0'))
                    body = json.loads(self.rfile.read(length).decode('utf-8'))
                    with lock:
                        state['login_calls'] += 1
                        state['last_login'] = body
                    if body.get('captchaAnswer') != valid_ans or not body.get('captchaId', '').startswith('good-'):
                        self._send_json(400, {'code': 'VALIDATION_FAILED', 'message': 'bad captcha'})
                        return
                    self._send_json(
                        200,
                        {'code': 'OK', 'data': {'userId': 1, 'username': body['username'], 'roles': ['ADMIN']}},
                        cookies=['CRM_TOKEN=tok-secret; Path=/; HttpOnly', 'CRM_CSRF=csrf-secret; Path=/'],
                    )
                    return
                self._send_json(404, {'code': 'NOT_FOUND'})

        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        port = server.server_port
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            jar = http.cookiejar.CookieJar()
            opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
            user = captcha_solver.login_with_captcha(opener, f'http://127.0.0.1:{port}', 'admin', 'SyntheticPass123!')
            self.assertEqual(user['userId'], 1)
            self.assertEqual(state['captcha_calls'], 2)
            self.assertEqual(state['login_calls'], 1)
            self.assertEqual(state['last_login']['captchaId'], 'good-2')
            self.assertEqual(state['last_login']['captchaAnswer'], valid_ans)

            # Now exercise scripts/load-smoke.sh end-to-end
            env = os.environ.copy()
            env['CRM_LOAD_BASE_URL'] = f'http://127.0.0.1:{port}'
            env['CRM_LOAD_REQUESTS'] = '6'
            env['CRM_LOAD_CONCURRENCY'] = '2'
            env['CRM_LOAD_P95_LIMIT_MS'] = '2000'
            env['CRM_LOAD_USERNAME'] = 'admin'
            env['CRM_LOAD_PASSWORD'] = 'SyntheticPass123!'
            env.pop('CRM_CAPTCHA_ID', None)
            env.pop('CRM_CAPTCHA_ANSWER', None)

            proc = subprocess.run(
                ['bash', str(REPO_ROOT / 'scripts/load-smoke.sh')],
                cwd=REPO_ROOT,
                env=env,
                capture_output=True,
                text=True,
                check=True,
            )
            evidence_line = [ln for ln in proc.stdout.splitlines() if ln.startswith('证据目录:')]
            self.assertTrue(evidence_line, proc.stdout)
            work_dir = Path(evidence_line[0].split(':', 1)[1].strip())
            try:
                summary = json.loads((work_dir / 'summary.json').read_text(encoding='utf-8'))
                self.assertEqual(len(summary['results']), 2)
                for res in summary['results']:
                    self.assertEqual(res['completeRequests'], 6)
                    self.assertEqual(res['failedRequests'], 0)
                    self.assertEqual(res['non2xxResponses'], 0)
            finally:
                shutil.rmtree(work_dir, ignore_errors=True)
        finally:
            server.shutdown()
            server.server_close()


class TestInstallShCliAndBackups(unittest.TestCase):
    def _setup_install_sandbox(self, tmp: Path) -> tuple[Path, Path, Path]:
        install_dir = tmp / 'pkg'
        install_dir.mkdir()
        shutil.copy2(REPO_ROOT / 'infra/production/install.sh', install_dir / 'install.sh')
        os.chmod(install_dir / 'install.sh', 0o755)
        shutil.copy2(REPO_ROOT / 'infra/production/.env.example', install_dir / '.env.example')
        shutil.copy2(REPO_ROOT / 'infra/production/compose.yml', install_dir / 'compose.yml')
        shutil.copy2(REPO_ROOT / 'infra/production/Caddyfile.core', install_dir / 'Caddyfile.core')
        shutil.copy2(REPO_ROOT / 'infra/production/Caddyfile.files', install_dir / 'Caddyfile.files')

        env_file = install_dir / '.env'
        env_text = (install_dir / '.env.example').read_text(encoding='utf-8')
        env_text = env_text.replace('https://crm.example.com', 'https://crm.test.local')
        for placeholder_key in (
            'MYSQL_ROOT_PASSWORD',
            'MYSQL_PASSWORD',
            'REDIS_PASSWORD',
            'MINIO_ROOT_USER',
            'MINIO_ROOT_PASSWORD',
            'JWT_SECRET',
            'APP_AES_KEY_BASE64',
            'APP_BLIND_INDEX_PEPPER',
            'AI_INTERNAL_TOKEN',
        ):
            env_text = env_text.replace(f'{placeholder_key}=REPLACE_ON_INSTALL', f'{placeholder_key}=SYNTHETIC_{placeholder_key}_VAL')
        env_file.write_text(env_text, encoding='utf-8')
        os.chmod(env_file, 0o600)

        bin_dir = tmp / 'bin'
        bin_dir.mkdir()
        log_file = tmp / 'docker-calls.log'
        stub_docker = bin_dir / 'docker'
        stub_docker.write_text(
            f"""#!/usr/bin/env python3
import json, os, sys
log_path = {str(log_file)!r}
args = sys.argv[1:]
with open(log_path, 'a', encoding='utf-8') as f:
    f.write(json.dumps(args) + '\\n')

if args == ['compose', 'version']:
    print('Docker Compose version v2.38.1')
    sys.exit(0)
if args == ['info']:
    sys.exit(0)
if args[:1] == ['compose']:
    if 'ps' in args and '-q' in args and 'minio' in args:
        print('stub-minio-cid')
        sys.exit(0)
    if 'exec' in args and 'mysql' in args:
        sys.stdout.write('-- stub mysqldump output\\nCREATE TABLE t(id INT);\\n')
        sys.exit(0)
    sys.exit(0)
if args[:1] == ['run']:
    # Simulate tar -C /data -cf - . output
    import io, tarfile
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode='w') as tf:
        info = tarfile.TarInfo('sample.txt')
        data = b'minio-stub-object'
        info.size = len(data)
        tf.addfile(info, io.BytesIO(data))
    sys.stdout.buffer.write(buf.getvalue())
    sys.exit(0)
sys.exit(0)
""",
            encoding='utf-8',
        )
        os.chmod(stub_docker, 0o755)
        return install_dir, bin_dir, log_file

    def test_logs_positional_args_and_invalid_args(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            install_dir, bin_dir, log_file = self._setup_install_sandbox(Path(td))
            env = os.environ.copy()
            env['PATH'] = f"{bin_dir}:{env['PATH']}"

            # logs crm-api caddy succeeds and forwards service names
            res = subprocess.run(
                ['bash', str(install_dir / 'install.sh'), 'logs', 'crm-api', 'caddy'],
                cwd=install_dir,
                env=env,
                capture_output=True,
                text=True,
                check=True,
            )
            self.assertEqual(res.returncode, 0)
            calls = [json.loads(line) for line in log_file.read_text(encoding='utf-8').splitlines()]
            self.assertIn(['compose', '--env-file', str(install_dir / '.env'), '-f', str(install_dir / 'compose.yml'), 'logs', '--tail', '100', 'crm-api', 'caddy'], calls)

            # Invalid service name in logs fails
            bad_svc = subprocess.run(
                ['bash', str(install_dir / 'install.sh'), 'logs', '../etc'],
                cwd=install_dir,
                env=env,
                capture_output=True,
                text=True,
            )
            self.assertNotEqual(bad_svc.returncode, 0)

            # Non-logs commands reject unexpected positional args
            bad_pos = subprocess.run(
                ['bash', str(install_dir / 'install.sh'), 'status', 'crm-api'],
                cwd=install_dir,
                env=env,
                capture_output=True,
                text=True,
            )
            self.assertNotEqual(bad_pos.returncode, 0)

    def test_backup_creates_mysql_minio_and_encrypted_or_plain_env_without_cli_secrets(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            install_dir, bin_dir, log_file = self._setup_install_sandbox(Path(td))
            env = os.environ.copy()
            env['PATH'] = f"{bin_dir}:{env['PATH']}"

            # 1. Unencrypted backup emits warning + mode 600 files
            res_plain = subprocess.run(
                ['bash', str(install_dir / 'install.sh'), 'backup'],
                cwd=install_dir,
                env=env,
                capture_output=True,
                text=True,
                check=True,
            )
            self.assertIn('BACKUP_ENCRYPTION_PASSPHRASE', res_plain.stderr)
            backups = install_dir / 'backups'
            sql_gz = list(backups.glob('crm-*.sql.gz'))
            minio_gz = list(backups.glob('crm-minio-*.tar.gz'))
            env_plain = list(backups.glob('crm-env-*.env'))
            self.assertEqual(len(sql_gz), 1)
            self.assertEqual(len(minio_gz), 1)
            self.assertEqual(len(env_plain), 1)
            for p in sql_gz + minio_gz + env_plain:
                self.assertEqual(stat.S_IMODE(p.stat().st_mode), 0o600)

            # 2. Encrypted backup with BACKUP_ENCRYPTION_PASSPHRASE
            env_enc_run = env.copy()
            env_enc_run['BACKUP_ENCRYPTION_PASSPHRASE'] = 'synthetic-backup-pass-2026'
            subprocess.run(
                ['bash', str(install_dir / 'install.sh'), 'backup'],
                cwd=install_dir,
                env=env_enc_run,
                capture_output=True,
                text=True,
                check=True,
            )
            enc_files = list(backups.glob('crm-env-*.env.enc'))
            self.assertEqual(len(enc_files), 1)
            self.assertEqual(stat.S_IMODE(enc_files[0].stat().st_mode), 0o600)
            dec = subprocess.run(
                [
                    'openssl', 'enc', '-d', '-aes-256-cbc', '-pbkdf2', '-iter', '100000',
                    '-pass', 'env:BACKUP_ENCRYPTION_PASSPHRASE', '-in', str(enc_files[0]),
                ],
                env=env_enc_run,
                capture_output=True,
                text=True,
                check=True,
            )
            self.assertEqual(dec.stdout, (install_dir / '.env').read_text(encoding='utf-8'))

            # 3. Verify zero secrets in docker stub argv
            all_calls = [json.loads(line) for line in log_file.read_text(encoding='utf-8').splitlines()]
            flattened = '\n'.join(' '.join(call) for call in all_calls)
            self.assertNotIn('-p"$MYSQL_ROOT_PASSWORD"', flattened)
            self.assertNotIn('SYNTHETIC_MYSQL_ROOT_PASSWORD_VAL', flattened)
            self.assertNotIn('synthetic-backup-pass-2026', flattened)
            self.assertIn('MYSQL_PWD="$MYSQL_ROOT_PASSWORD"', flattened)

    def test_set_env_preserves_special_chars_without_sed(self) -> None:
        install_sh = (REPO_ROOT / 'infra/production/install.sh').read_text(encoding='utf-8')
        self.assertNotIn('sed -i', install_sh)
        self.assertIn('ENVIRON["ENV_VAL"]', install_sh)

        with tempfile.TemporaryDirectory() as td:
            td_path = Path(td)
            env_file = td_path / '.env'
            env_file.write_text('JWT_SECRET=old\nOTHER=keep\n', encoding='utf-8')
            special_val = r'abc&def\ghi|jkl=mno'
            # Extract and run set_env directly via bash
            cmd = (
                f'ENV_FILE={shlex.quote(str(env_file))}; '
                'fail() { echo "$*" >&2; exit 1; }; '
                + install_sh.split('set_env() {', 1)[1].split('\n}\n', 1)[0].join(['set_env() {', '\n}\n'])
                + f'set_env JWT_SECRET {shlex.quote(special_val)}; '
                + "set_env NEW_KEY 'val2'"
            )
            subprocess.run(['bash', '-euo', 'pipefail', '-c', cmd], check=True)
            lines = env_file.read_text(encoding='utf-8').splitlines()
            self.assertIn(f'JWT_SECRET={special_val}', lines)
            self.assertIn('OTHER=keep', lines)
            self.assertIn('NEW_KEY=val2', lines)


if __name__ == '__main__':
    unittest.main()
