#!/usr/bin/env python3
"""
有赞云全量订单原始 JSON 导出工具（零第三方依赖，Python 3.9+ 标准库）。

核心能力：
1. 复用有赞云官方协议：/auth/token (silent) + youzan.trades.sold.get/4.0.2 + youzan.trade.get/4.0.2；
2. 自动按 ≤89 天切分时间窗口；若单窗超过 100 页 × page_size（默认 10,000 单）自动按秒二分，防止第 100 页后漏单；
3. 相邻窗口共享边界秒 + 按 tid 去重，避免临界秒丢单；
4. 自动节流（默认 120ms/次）、429/限流指数退避重试、Token 过期自动 refresh=true 刷新重试；
5. 流式写入 orders.jsonl + state.json，支持 --resume 断点续传，拉完后低内存流式汇总为纯有赞原始结构数组 orders.json；
6. 密钥仅通过环境变量或命令行参数传入，绝不写入磁盘或日志；默认输出到 .gitignore 忽略的 tmp/ 目录。

示例：
  export YOUZAN_CLIENT_ID="你的client_id"
  export YOUZAN_CLIENT_SECRET="你的client_secret"
  export YOUZAN_SHOP_ID="你的shop_id(kdt_id)"
  python3 scripts/export_youzan_orders.py --start 2020-01-01
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlparse
from urllib.request import HTTPRedirectHandler, OpenerDirector, Request, build_opener
from zoneinfo import ZoneInfo

SHANGHAI_TZ = ZoneInfo("Asia/Shanghai")
TIME_FMT = "%Y-%m-%d %H:%M:%S"
DEFAULT_BASE_URL = "https://open.youzanyun.com"
MAX_WINDOW_DAYS = 89
MAX_PAGES_PER_WINDOW = 100
RATE_LIMIT_CODES = {4101, 429, 40009}
TOKEN_ERROR_CODES = {4201, 4202, 4203, 40010, 40011, 40012}


class YouzanApiError(RuntimeError):
    def __init__(
        self,
        message: str,
        *,
        code: int | None = None,
        auth_error: bool = False,
        rate_limited: bool = False,
        retryable: bool = False,
        retry_after: float | None = None,
    ) -> None:
        super().__init__(message)
        self.code = code
        self.auth_error = auth_error
        self.rate_limited = rate_limited
        self.retryable = retryable
        self.retry_after = retry_after


class _NoRedirectHandler(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise HTTPError(req.full_url, code, f"Unexpected redirect to {newurl}", headers, fp)


def _is_rate_limit(code: int | None, message: str) -> bool:
    if code in RATE_LIMIT_CODES:
        return True
    lower = (message or "").lower()
    return "限流" in lower or "rate" in lower or "frequency" in lower


def _is_token_error(code: int | None, message: str) -> bool:
    if code in TOKEN_ERROR_CODES:
        return True
    lower = (message or "").lower()
    credential = any(k in lower for k in ("token", "授权", "凭证"))
    invalid = any(k in lower for k in ("过期", "失效", "无效", "不存在", "expired", "invalid", "not exist"))
    return credential and invalid


def _parse_retry_after(value: str | None) -> float | None:
    if not value:
        return None
    try:
        return max(0.0, min(60.0, float(value.strip())))
    except ValueError:
        return None


def parse_shanghai_time(raw: str, *, end_of_day_if_date_only: bool = False) -> datetime:
    text = raw.strip()
    if len(text) == 10:
        dt = datetime.strptime(text, "%Y-%m-%d")
        if end_of_day_if_date_only:
            dt = dt.replace(hour=23, minute=59, second=59)
        return dt.replace(tzinfo=SHANGHAI_TZ)
    dt = datetime.strptime(text, TIME_FMT)
    return dt.replace(tzinfo=SHANGHAI_TZ)


def format_shanghai_time(dt: datetime) -> str:
    return dt.astimezone(SHANGHAI_TZ).strftime(TIME_FMT)


def extract_tid(record: Any) -> str | None:
    if not isinstance(record, dict):
        return None
    full = record.get("full_order_info")
    if isinstance(full, dict):
        order_info = full.get("order_info")
        if isinstance(order_info, dict):
            tid = order_info.get("tid")
            if isinstance(tid, str) and tid.strip():
                return tid.strip()
    order_info = record.get("order_info")
    if isinstance(order_info, dict):
        tid = order_info.get("tid")
        if isinstance(tid, str) and tid.strip():
            return tid.strip()
    return None


def extract_created(record: Any) -> str:
    if not isinstance(record, dict):
        return ""
    full = record.get("full_order_info")
    if isinstance(full, dict):
        order_info = full.get("order_info")
        if isinstance(order_info, dict):
            created = order_info.get("created")
            if isinstance(created, str):
                return created
    return ""


def has_complete_list_detail(wrapper: Any) -> bool:
    if not isinstance(wrapper, dict):
        return False
    full = wrapper.get("full_order_info")
    if not isinstance(full, dict):
        return False
    order_info = full.get("order_info")
    pay_info = full.get("pay_info")
    buyer_info = full.get("buyer_info")
    remark_info = full.get("remark_info")
    orders = full.get("orders")
    if not all(isinstance(x, dict) for x in (order_info, pay_info, buyer_info, remark_info)):
        return False
    if "trade_memo" not in remark_info:
        return False
    if not order_info.get("tid") or not order_info.get("status") or not order_info.get("update_time"):
        return False
    if not buyer_info.get("yz_open_id") or pay_info.get("payment") in (None, ""):
        return False
    if not isinstance(orders, list) or not orders:
        return False
    for item in orders:
        if not isinstance(item, dict) or item.get("item_id") in (None, "") or int(item.get("num") or 0) <= 0:
            return False
    return True


def merge_intervals(intervals: list[tuple[int, int]]) -> list[tuple[int, int]]:
    if not intervals:
        return []
    ordered = sorted((min(a, b), max(a, b)) for a, b in intervals)
    merged: list[tuple[int, int]] = [ordered[0]]
    for start, end in ordered[1:]:
        prev_start, prev_end = merged[-1]
        if start <= prev_end:
            merged[-1] = (prev_start, max(prev_end, end))
        else:
            merged.append((start, end))
    return merged


def is_interval_covered(merged: list[tuple[int, int]], start: int, end: int) -> bool:
    for m_start, m_end in merged:
        if m_start <= start and end <= m_end:
            return True
    return False


class YouzanClient:
    def __init__(
        self,
        *,
        base_url: str,
        shop_id: str,
        client_id: str | None = None,
        client_secret: str | None = None,
        access_token: str | None = None,
        min_interval_ms: int = 120,
        max_retries: int = 5,
        timeout_seconds: float = 30.0,
    ) -> None:
        parsed = urlparse(base_url)
        if parsed.scheme not in ("http", "https") or not parsed.netloc:
            raise ValueError(f"无效的 base_url: {base_url}")
        self.base_url = base_url.rstrip("/")
        self.shop_id = shop_id
        self.client_id = client_id
        self.client_secret = client_secret
        self._access_token = access_token
        self._token_expires_at: float = float("inf") if access_token and not client_id else 0.0
        self.min_interval_sec = max(0.0, min_interval_ms / 1000.0)
        self.max_retries = max(1, max_retries)
        self.timeout_seconds = timeout_seconds
        self._next_call_at = 0.0
        self._opener: OpenerDirector = build_opener(_NoRedirectHandler)
        self.api_calls = 0

    def _throttle(self) -> None:
        if self.min_interval_sec <= 0:
            return
        now = time.monotonic()
        if now < self._next_call_at:
            time.sleep(self._next_call_at - now)
        self._next_call_at = time.monotonic() + self.min_interval_sec

    def _post_json(self, url: str, payload: dict[str, Any]) -> dict[str, Any]:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        req = Request(
            url,
            data=body,
            headers={
                "Content-Type": "application/json; charset=utf-8",
                "Accept": "application/json",
            },
            method="POST",
        )
        self._throttle()
        self.api_calls += 1
        try:
            with self._opener.open(req, timeout=self.timeout_seconds) as resp:
                raw = resp.read().decode("utf-8")
        except HTTPError as exc:
            retry_after = _parse_retry_after(exc.headers.get("Retry-After") if exc.headers else None)
            if exc.code == 429:
                raise YouzanApiError(
                    "有赞接口限流 (HTTP 429)",
                    code=429,
                    rate_limited=True,
                    retryable=True,
                    retry_after=retry_after,
                ) from exc
            if exc.code in (401, 403):
                raise YouzanApiError(
                    f"有赞授权或 Token 失效 (HTTP {exc.code})",
                    code=exc.code,
                    auth_error=True,
                ) from exc
            if exc.code >= 500:
                raise YouzanApiError(
                    f"有赞服务端临时错误 (HTTP {exc.code})",
                    code=exc.code,
                    retryable=True,
                    retry_after=retry_after,
                ) from exc
            raise YouzanApiError(f"有赞 HTTP 请求失败: {exc.code}", code=exc.code) from exc
        except (URLError, TimeoutError, OSError) as exc:
            raise YouzanApiError(f"网络请求失败: {exc}", retryable=True) from exc

        try:
            data = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise YouzanApiError("有赞接口返回了非 JSON 响应", retryable=True) from exc

        if not isinstance(data, dict):
            raise YouzanApiError("有赞接口返回结构异常")
        return self._validate_response(data)

    @staticmethod
    def _validate_response(response: dict[str, Any]) -> dict[str, Any]:
        gw = response.get("gw_err_resp")
        if isinstance(gw, dict):
            err_code = int(gw.get("err_code") or 0)
            err_msg = str(gw.get("err_msg") or "")
            if _is_rate_limit(err_code, err_msg):
                raise YouzanApiError(
                    f"有赞网关限流 ({err_code}): {err_msg}",
                    code=err_code,
                    rate_limited=True,
                    retryable=True,
                )
            if _is_token_error(err_code, err_msg):
                raise YouzanApiError(
                    f"有赞网关 Token 失效 ({err_code}): {err_msg}",
                    code=err_code,
                    auth_error=True,
                )
            raise YouzanApiError(f"有赞网关错误 ({err_code}): {err_msg}", code=err_code)

        if response.get("success") is not True or int(response.get("code") or 0) != 200:
            code = int(response.get("code") or 0)
            msg = str(response.get("message") or "")
            if _is_rate_limit(code, msg):
                raise YouzanApiError(
                    f"有赞业务限流 ({code}): {msg}",
                    code=code,
                    rate_limited=True,
                    retryable=True,
                )
            if _is_token_error(code, msg):
                raise YouzanApiError(
                    f"有赞 Token 失效 ({code}): {msg}",
                    code=code,
                    auth_error=True,
                )
            raise YouzanApiError(f"有赞接口返回失败 ({code}): {msg}", code=code)
        return response

    def _fetch_token(self, *, refresh: bool) -> str:
        if not self.client_id or not self.client_secret:
            raise YouzanApiError("未提供 client_id / client_secret，无法获取或刷新 Token")
        payload = {
            "client_id": self.client_id,
            "client_secret": self.client_secret,
            "authorize_type": "silent",
            "grant_id": self.shop_id,
            "refresh": refresh,
        }
        try:
            resp = self._post_json(f"{self.base_url}/auth/token", payload)
        except YouzanApiError as exc:
            if exc.code == 1000:
                raise YouzanApiError(
                    f"{exc} —— 请检查 YOUZAN_CLIENT_ID / YOUZAN_CLIENT_SECRET 是否与有赞云控制台「应用设置」一致、"
                    f"是否填成了应用ID/有多余空格，以及应用类型是否为「自用型应用」且已授权店铺 {self.shop_id}",
                    code=exc.code,
                ) from exc
            raise
        data = resp.get("data") or {}
        token = str(data.get("access_token") or "").strip()
        expires_ms = int(data.get("expires") or 0)
        if not token or expires_ms <= 0:
            raise YouzanApiError("有赞 /auth/token 响应缺少 access_token 或 expires")
        self._access_token = token
        self._token_expires_at = expires_ms / 1000.0
        return token

    def get_token(self, *, force_refresh: bool = False) -> str:
        now = time.time()
        if force_refresh or not self._access_token or now >= (self._token_expires_at - 120.0):
            return self._fetch_token(refresh=force_refresh)
        return self._access_token

    def call_api(self, api_name: str, version: str, body: dict[str, Any]) -> dict[str, Any]:
        token_refreshed = False
        for attempt in range(self.max_retries):
            token = self.get_token(force_refresh=False)
            url = f"{self.base_url}/api/{api_name}/{version}?access_token={quote(token, safe='')}"
            try:
                return self._post_json(url, body)
            except YouzanApiError as exc:
                if exc.auth_error and not token_refreshed and self.client_id and self.client_secret:
                    token_refreshed = True
                    _log("检测到有赞 Token 失效，正在使用 refresh=true 刷新后重试...")
                    self.get_token(force_refresh=True)
                    continue
                if exc.retryable and attempt + 1 < self.max_retries:
                    backoff = exc.retry_after if exc.retry_after is not None else min(30.0, 0.5 * (2**attempt))
                    _log(f"调用 {api_name}/{version} 遇临时/限流错误（{exc}），{backoff:.1f}s 后第 {attempt + 2} 次重试...")
                    time.sleep(backoff)
                    continue
                raise
        raise YouzanApiError(f"调用 {api_name}/{version} 超过最大重试次数")


def _log(message: str) -> None:
    ts = datetime.now(SHANGHAI_TZ).strftime(TIME_FMT)
    print(f"[{ts}] {message}", file=sys.stderr, flush=True)


class YouzanOrderExporter:
    def __init__(
        self,
        *,
        client: YouzanClient,
        out_dir: Path,
        start_dt: datetime,
        end_dt: datetime,
        window_days: int,
        page_size: int,
        query_type: str,
        status: str | None,
        detail_mode: str,
        list_version: str,
        detail_version: str,
        pretty: bool,
        resume: bool,
        overwrite: bool,
    ) -> None:
        self.client = client
        self.out_dir = out_dir
        self.start_dt = start_dt
        self.end_dt = end_dt
        self.window_days = window_days
        self.page_size = page_size
        self.query_type = query_type
        self.status = status
        self.detail_mode = detail_mode
        self.list_version = list_version
        self.detail_version = detail_version
        self.pretty = pretty
        self.resume = resume
        self.overwrite = overwrite

        self.jsonl_path = out_dir / "orders.jsonl"
        self.json_path = out_dir / "orders.json"
        self.state_path = out_dir / "state.json"
        self.failed_path = out_dir / "failed_tids.jsonl"
        self.summary_path = out_dir / "summary.json"

        self.seen_tids: set[str] = set()
        self.completed_intervals: list[tuple[int, int]] = []
        self.failed_tids: dict[str, str] = {}
        self.warnings: list[str] = []
        self.new_saved_count = 0
        self.detail_fetched_count = 0
        self.skipped_existing_count = 0

    def run(self) -> dict[str, Any]:
        started_at = time.monotonic()
        # 先验证鉴权可用，避免凭据错误时提前留下空的 state.json
        self.client.get_token(force_refresh=False)
        self._prepare_workspace()

        # 先重试此前失败的 tid（断点续传场景）
        if self.resume and self.failed_tids:
            self._retry_previous_failed_tids()

        # 生成初始时间窗口（每窗 ≤ window_days，相邻窗共享边界秒防丢单）
        initial_windows = self._build_initial_windows()
        _log(
            f"开始拉取店铺 {self.client.shop_id} 订单："
            f"{format_shanghai_time(self.start_dt)} ~ {format_shanghai_time(self.end_dt)}，"
            f"共 {len(initial_windows)} 个初始窗口，detail_mode={self.detail_mode}"
        )

        with self.jsonl_path.open("a", encoding="utf-8") as jsonl_fp:
            for idx, (win_start, win_end) in enumerate(initial_windows, start=1):
                _log(
                    f"[窗口 {idx}/{len(initial_windows)}] "
                    f"{format_shanghai_time(win_start)} ~ {format_shanghai_time(win_end)}"
                )
                self._process_window(win_start, win_end, jsonl_fp)

        self._write_failed_tids()
        total_unique = self._assemble_final_json()
        elapsed = round(time.monotonic() - started_at, 2)

        summary = {
            "shop_id": self.client.shop_id,
            "query_type": self.query_type,
            "status_filter": self.status,
            "detail_mode": self.detail_mode,
            "list_version": self.list_version,
            "detail_version": self.detail_version,
            "start": format_shanghai_time(self.start_dt),
            "end": format_shanghai_time(self.end_dt),
            "total_unique_orders": total_unique,
            "newly_saved_orders": self.new_saved_count,
            "skipped_existing_orders": self.skipped_existing_count,
            "detail_api_calls": self.detail_fetched_count,
            "total_api_calls": self.client.api_calls,
            "failed_tids_count": len(self.failed_tids),
            "warnings": self.warnings,
            "files": {
                "orders_json": str(self.json_path),
                "orders_jsonl": str(self.jsonl_path),
                "failed_tids_jsonl": str(self.failed_path) if self.failed_tids else None,
            },
            "elapsed_seconds": elapsed,
            "generated_at": format_shanghai_time(datetime.now(SHANGHAI_TZ)),
        }
        self._atomic_write_json(self.summary_path, summary)
        _log(
            f"导出完成！唯一订单 {total_unique} 笔（本次新增 {self.new_saved_count}，失败 {len(self.failed_tids)}），"
            f"总请求 {self.client.api_calls} 次，耗时 {elapsed}s -> {self.json_path}"
        )
        return summary

    def _prepare_workspace(self) -> None:
        self.out_dir.mkdir(parents=True, exist_ok=True)
        existing_files = [p for p in (self.jsonl_path, self.state_path) if p.exists()]
        if existing_files and not self.resume and not self.overwrite:
            raise SystemExit(
                f"输出目录已存在历史拉取文件：{self.out_dir}\n"
                f"请追加 --resume 断点续传，或 --overwrite 清空重拉，或指定新的 --out-dir。"
            )
        if self.overwrite:
            for p in (self.jsonl_path, self.json_path, self.state_path, self.failed_path, self.summary_path):
                if p.exists():
                    p.unlink()
            self._save_state()
            return

        if self.resume:
            self._load_resume_state()
        else:
            self._save_state()

    def _load_resume_state(self) -> None:
        if self.state_path.exists():
            state = json.loads(self.state_path.read_text(encoding="utf-8"))
            expected = {
                "shop_id": self.client.shop_id,
                "query_type": self.query_type,
                "status": self.status,
                "detail_mode": self.detail_mode,
            }
            for k, v in expected.items():
                if state.get(k) != v:
                    raise SystemExit(
                        f"--resume 参数与历史 state.json 不一致（{k}: 历史={state.get(k)!r}, 当前={v!r}）。"
                        f"请保持一致或改用 --overwrite / 新 --out-dir。"
                    )
            raw_intervals = state.get("completed_intervals") or []
            self.completed_intervals = merge_intervals([(int(a), int(b)) for a, b in raw_intervals])
            self.warnings = list(state.get("warnings") or [])

        if self.jsonl_path.exists():
            with self.jsonl_path.open("r", encoding="utf-8") as fp:
                for line_no, line in enumerate(fp, start=1):
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        obj = json.loads(line)
                    except json.JSONDecodeError:
                        _log(f"警告：orders.jsonl 第 {line_no} 行不完整，已忽略")
                        continue
                    tid = extract_tid(obj)
                    if tid:
                        self.seen_tids.add(tid)

        if self.failed_path.exists():
            with self.failed_path.open("r", encoding="utf-8") as fp:
                for line in fp:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        entry = json.loads(line)
                    except json.JSONDecodeError:
                        continue
                    tid = entry.get("tid")
                    if isinstance(tid, str) and tid and tid not in self.seen_tids:
                        self.failed_tids[tid] = str(entry.get("error") or "")

        _log(
            f"断点续传已恢复：已缓存唯一订单 {len(self.seen_tids)} 笔，"
            f"已完成时间区间 {len(self.completed_intervals)} 段，待重试失败单 {len(self.failed_tids)} 笔"
        )

    def _retry_previous_failed_tids(self) -> None:
        pending = list(self.failed_tids.keys())
        _log(f"正在重试上次失败的 {len(pending)} 笔订单详情...")
        with self.jsonl_path.open("a", encoding="utf-8") as jsonl_fp:
            for tid in pending:
                if tid in self.seen_tids:
                    self.failed_tids.pop(tid, None)
                    continue
                try:
                    detail = self._fetch_order_detail(tid)
                    self._append_record(jsonl_fp, tid, detail)
                    self.failed_tids.pop(tid, None)
                except YouzanApiError as exc:
                    self.failed_tids[tid] = str(exc)
                    _log(f"重试订单 {tid} 仍失败：{exc}")

    def _build_initial_windows(self) -> list[tuple[datetime, datetime]]:
        windows: list[tuple[datetime, datetime]] = []
        delta = timedelta(days=self.window_days)
        cur = self.start_dt
        while cur < self.end_dt:
            nxt = min(cur + delta, self.end_dt)
            windows.append((cur, nxt))
            if nxt >= self.end_dt:
                break
            cur = nxt
        return windows

    def _process_window(self, start_dt: datetime, end_dt: datetime, jsonl_fp) -> None:
        start_ts = int(start_dt.timestamp())
        end_ts = int(end_dt.timestamp())
        if is_interval_covered(self.completed_intervals, start_ts, end_ts):
            _log(f"  ↳ 跳过已完成窗口 {format_shanghai_time(start_dt)} ~ {format_shanghai_time(end_dt)}")
            return

        max_items = MAX_PAGES_PER_WINDOW * self.page_size
        page_no = 1
        while page_no <= MAX_PAGES_PER_WINDOW:
            resp = self._fetch_list_page(start_dt, end_dt, page_no)
            data = resp.get("data") or {}
            total = int(data.get("total_results") or 0)
            items = data.get("full_order_info_list") or []
            has_next = bool(data.get("has_next"))

            # 超出单窗 100 页上限：优先对半切分时间窗口防漏单
            if total > max_items or (page_no == MAX_PAGES_PER_WINDOW and (page_no * self.page_size < total or has_next)):
                span_seconds = int((end_dt - start_dt).total_seconds())
                if span_seconds > 1:
                    mid_dt = start_dt + timedelta(seconds=span_seconds // 2)
                    _log(
                        f"  ↳ 窗口订单数 {total} 超过单窗上限 {max_items}，"
                        f"自动二分：{format_shanghai_time(start_dt)} ~ {format_shanghai_time(mid_dt)} / "
                        f"{format_shanghai_time(mid_dt)} ~ {format_shanghai_time(end_dt)}"
                    )
                    self._process_window(start_dt, mid_dt, jsonl_fp)
                    self._process_window(mid_dt, end_dt, jsonl_fp)
                    return
                warning = (
                    f"1秒窗口 {format_shanghai_time(start_dt)} ~ {format_shanghai_time(end_dt)} "
                    f"仍超上限（total_results={total}），最多仅能拉取前 {max_items} 笔"
                )
                _log(f"  ↳ 警告：{warning}")
                self.warnings.append(warning)

            if page_no == 1:
                _log(
                    f"  ↳ 命中 {total} 笔（{format_shanghai_time(start_dt)} ~ {format_shanghai_time(end_dt)}）"
                )

            if not items:
                break

            for wrapper in items:
                self._handle_list_item(wrapper, jsonl_fp)

            if page_no * self.page_size >= total and not has_next:
                break
            page_no += 1

        self.completed_intervals = merge_intervals(self.completed_intervals + [(start_ts, end_ts)])
        self._save_state()

    def _fetch_list_page(self, start_dt: datetime, end_dt: datetime, page_no: int) -> dict[str, Any]:
        body: dict[str, Any] = {
            "page_no": page_no,
            "page_size": self.page_size,
        }
        start_str = format_shanghai_time(start_dt)
        end_str = format_shanghai_time(end_dt)
        if self.query_type == "updated":
            body["start_update"] = start_str
            body["end_update"] = end_str
        else:
            body["start_created"] = start_str
            body["end_created"] = end_str
        if self.status:
            body["status"] = self.status
        return self.client.call_api("youzan.trades.sold.get", self.list_version, body)

    def _fetch_order_detail(self, tid: str) -> dict[str, Any]:
        resp = self.client.call_api("youzan.trade.get", self.detail_version, {"tid": tid})
        self.detail_fetched_count += 1
        data = resp.get("data")
        if not isinstance(data, dict) or not data:
            raise YouzanApiError(f"youzan.trade.get 返回空 data (tid={tid})")
        return data

    def _handle_list_item(self, wrapper: Any, jsonl_fp) -> None:
        tid = extract_tid(wrapper)
        if not tid:
            _log("  ↳ 跳过缺少 tid 的异常列表项")
            return
        if tid in self.seen_tids:
            self.skipped_existing_count += 1
            return

        need_detail = (
            self.detail_mode == "always"
            or (self.detail_mode == "missing" and not has_complete_list_detail(wrapper))
        )
        if need_detail:
            try:
                record = self._fetch_order_detail(tid)
            except YouzanApiError as exc:
                self.failed_tids[tid] = str(exc)
                self._write_failed_tids()
                _log(f"  ↳ 拉取订单详情失败 tid={tid}：{exc}（已记入 failed_tids.jsonl，继续拉取其余订单）")
                return
        else:
            record = wrapper

        self._append_record(jsonl_fp, tid, record)
        if tid in self.failed_tids:
            self.failed_tids.pop(tid, None)
            self._write_failed_tids()

    def _append_record(self, jsonl_fp, tid: str, record: dict[str, Any]) -> None:
        jsonl_fp.write(json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n")
        jsonl_fp.flush()
        self.seen_tids.add(tid)
        self.new_saved_count += 1
        if self.new_saved_count % 100 == 0:
            _log(f"  ↳ 进度：本次已落盘 {self.new_saved_count} 笔，累计唯一订单 {len(self.seen_tids)} 笔")

    def _save_state(self) -> None:
        state = {
            "shop_id": self.client.shop_id,
            "query_type": self.query_type,
            "status": self.status,
            "detail_mode": self.detail_mode,
            "completed_intervals": [[a, b] for a, b in self.completed_intervals],
            "warnings": self.warnings,
            "updated_at": format_shanghai_time(datetime.now(SHANGHAI_TZ)),
        }
        self._atomic_write_json(self.state_path, state)

    def _write_failed_tids(self) -> None:
        if not self.failed_tids:
            if self.failed_path.exists():
                self.failed_path.unlink()
            return
        tmp_path = self.failed_path.with_suffix(".jsonl.tmp")
        with tmp_path.open("w", encoding="utf-8") as fp:
            for tid, err in sorted(self.failed_tids.items()):
                fp.write(json.dumps({"tid": tid, "error": err}, ensure_ascii=False) + "\n")
        tmp_path.replace(self.failed_path)

    def _assemble_final_json(self) -> int:
        # 仅在内存中保存 tid -> (created, byte_offset, byte_len)，流式组装 orders.json，避免大店内存溢出
        index: dict[str, tuple[str, int, int]] = {}
        if self.jsonl_path.exists():
            with self.jsonl_path.open("rb") as fp:
                while True:
                    offset = fp.tell()
                    raw_line = fp.readline()
                    if not raw_line:
                        break
                    stripped = raw_line.strip()
                    if not stripped:
                        continue
                    try:
                        obj = json.loads(stripped.decode("utf-8"))
                    except (UnicodeDecodeError, json.JSONDecodeError):
                        continue
                    tid = extract_tid(obj)
                    if not tid:
                        continue
                    created = extract_created(obj)
                    index[tid] = (created, offset, len(raw_line))

        ordered = sorted(index.items(), key=lambda item: (item[1][0], item[0]))
        tmp_json = self.json_path.with_suffix(".json.tmp")
        with tmp_json.open("w", encoding="utf-8") as out_fp:
            if not ordered:
                out_fp.write("[]\n")
            else:
                out_fp.write("[\n")
                with self.jsonl_path.open("rb") as src_fp:
                    last_idx = len(ordered) - 1
                    for idx, (_tid, (_created, offset, length)) in enumerate(ordered):
                        src_fp.seek(offset)
                        raw = src_fp.read(length).decode("utf-8").strip()
                        if self.pretty:
                            obj = json.loads(raw)
                            formatted = json.dumps(obj, ensure_ascii=False, indent=2)
                            indented = "\n".join("  " + line for line in formatted.splitlines())
                            out_fp.write(indented)
                        else:
                            out_fp.write("  " + raw)
                        out_fp.write(",\n" if idx < last_idx else "\n")
                out_fp.write("]\n")
        tmp_json.replace(self.json_path)
        return len(ordered)

    @staticmethod
    def _atomic_write_json(path: Path, data: Any) -> None:
        tmp = path.with_suffix(path.suffix + ".tmp")
        tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        tmp.replace(path)


def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="直接调用有赞云官方 API 全量拉取订单原始详细 JSON（自动切窗、二分防漏单、支持断点续传）"
    )
    parser.add_argument("--shop-id", default=os.environ.get("YOUZAN_SHOP_ID"), help="有赞店铺 shop_id (kdt_id)，也可通过 YOUZAN_SHOP_ID 传入")
    parser.add_argument("--client-id", default=os.environ.get("YOUZAN_CLIENT_ID"), help="有赞云自用型应用 client_id，也可通过 YOUZAN_CLIENT_ID 传入")
    parser.add_argument("--client-secret", default=os.environ.get("YOUZAN_CLIENT_SECRET"), help="有赞云自用型应用 client_secret，推荐通过 YOUZAN_CLIENT_SECRET 传入")
    parser.add_argument("--access-token", default=os.environ.get("YOUZAN_ACCESS_TOKEN"), help="可选：直接传入已有 access_token（不建议长任务使用，过期无法自动刷新）")
    parser.add_argument("--base-url", default=os.environ.get("YOUZAN_API_BASE_URL", DEFAULT_BASE_URL), help=f"有赞云网关地址（默认 {DEFAULT_BASE_URL}）")
    parser.add_argument("--start", default="2018-01-01 00:00:00", help="起始时间（北京时间 YYYY-MM-DD 或 YYYY-MM-DD HH:MM:SS，默认 2018-01-01 00:00:00，建议填开店日期）")
    parser.add_argument("--end", default=None, help="结束时间（北京时间 YYYY-MM-DD 或 YYYY-MM-DD HH:MM:SS，默认当前时间）")
    parser.add_argument("--window-days", type=int, default=MAX_WINDOW_DAYS, help=f"单次查询时间窗天数（1~{MAX_WINDOW_DAYS}，默认 {MAX_WINDOW_DAYS}，遇超限自动二分）")
    parser.add_argument("--page-size", type=int, default=100, help="列表每页条数（1~100，默认 100）")
    parser.add_argument("--query-type", choices=("created", "updated"), default="created", help="按创建时间 created（默认）或更新时间 updated 拉取")
    parser.add_argument("--status", default=None, help="可选：仅拉取指定有赞状态（默认不传，拉取全量状态）")
    parser.add_argument(
        "--detail-mode",
        choices=("always", "missing", "never"),
        default="always",
        help="详情模式：always=逐单调 youzan.trade.get/4.0.2 保存最全原始 data（默认）；missing=缺字段才补调；never=仅存列表原始项",
    )
    parser.add_argument("--list-version", default="4.0.2", help="youzan.trades.sold.get 版本（默认 4.0.2）")
    parser.add_argument("--detail-version", default="4.0.2", help="youzan.trade.get 版本（默认 4.0.2）")
    parser.add_argument("--min-interval-ms", type=int, default=120, help="请求最小间隔毫秒数（默认 120ms，防限流）")
    parser.add_argument("--max-retries", type=int, default=5, help="限流/网络异常最大重试次数（默认 5）")
    parser.add_argument("--out-dir", default=None, help="输出目录（默认 tmp/youzan-orders-<shop_id>，已在 .gitignore 忽略）")
    parser.add_argument("--compact", action="store_true", help="orders.json 使用紧凑格式（默认缩进排版便于阅读）")
    parser.add_argument("--resume", action="store_true", help="从输出目录已有 orders.jsonl + state.json 断点续传并重试失败单")
    parser.add_argument("--overwrite", action="store_true", help="清空输出目录后重新全量拉取")
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_arg_parser()
    args = parser.parse_args(argv)

    if not args.shop_id:
        parser.error("缺少 shop_id：请设置环境变量 YOUZAN_SHOP_ID 或传入 --shop-id")
    if not args.access_token and (not args.client_id or not args.client_secret):
        parser.error("缺少认证参数：请设置 YOUZAN_CLIENT_ID 与 YOUZAN_CLIENT_SECRET（或传入 --access-token）")
    if not (1 <= args.window_days <= MAX_WINDOW_DAYS):
        parser.error(f"--window-days 必须在 1..{MAX_WINDOW_DAYS} 之间（有赞官方限制单窗不超过 90 天）")
    if not (1 <= args.page_size <= 100):
        parser.error("--page-size 必须在 1..100 之间")
    if args.resume and args.overwrite:
        parser.error("--resume 与 --overwrite 不能同时使用")

    start_dt = parse_shanghai_time(args.start, end_of_day_if_date_only=False)
    end_dt = (
        parse_shanghai_time(args.end, end_of_day_if_date_only=True)
        if args.end
        else datetime.now(SHANGHAI_TZ).replace(microsecond=0)
    )
    if end_dt <= start_dt:
        parser.error("结束时间必须晚于起始时间")

    root = Path(__file__).resolve().parent.parent
    out_dir = Path(args.out_dir) if args.out_dir else (root / "tmp" / f"youzan-orders-{args.shop_id}")

    client = YouzanClient(
        base_url=args.base_url,
        shop_id=args.shop_id.strip(),
        client_id=args.client_id.strip() if args.client_id else None,
        client_secret=args.client_secret.strip() if args.client_secret else None,
        access_token=args.access_token.strip() if args.access_token else None,
        min_interval_ms=args.min_interval_ms,
        max_retries=args.max_retries,
    )
    exporter = YouzanOrderExporter(
        client=client,
        out_dir=out_dir,
        start_dt=start_dt,
        end_dt=end_dt,
        window_days=args.window_days,
        page_size=args.page_size,
        query_type=args.query_type,
        status=args.status,
        detail_mode=args.detail_mode,
        list_version=args.list_version,
        detail_version=args.detail_version,
        pretty=not args.compact,
        resume=args.resume,
        overwrite=args.overwrite,
    )
    try:
        exporter.run()
    except KeyboardInterrupt:
        _log("收到中断信号：已完成窗口与已拉取订单均已落盘，下次追加 --resume 即可断点续传。")
        return 130
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
