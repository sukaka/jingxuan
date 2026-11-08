#!/usr/bin/env python3
import argparse
import hashlib
import http.cookiejar
import json
import time
import urllib.request
from pathlib import Path


class SmokeTest:
    def __init__(self, base_url, username, password, check_files):
        self.base_url = base_url.rstrip('/')
        self.check_files = check_files
        self.cookies = http.cookiejar.CookieJar()
        self.client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookies))
        self.username = username
        self.password = password

    def call(self, method, url, payload=None, csrf=True):
        headers = {'Accept': 'application/json'}
        data = None
        if payload is not None:
            data = json.dumps(payload).encode('utf-8')
            headers['Content-Type'] = 'application/json'
        if csrf and method in {'POST', 'PUT', 'PATCH', 'DELETE'}:
            token = next((cookie.value for cookie in self.cookies if cookie.name == 'CRM_CSRF'), None)
            if token:
                headers['X-CSRF-Token'] = token
        request = urllib.request.Request(url, data=data, headers=headers, method=method)
        with self.client.open(request, timeout=60) as response:
            return json.load(response)

    def api(self, method, path, payload=None, csrf=True):
        result = self.call(method, self.base_url + '/api/v1' + path, payload, csrf)
        if result.get('code') != 'OK':
            raise AssertionError(f"接口返回失败：{result.get('code')}")
        return result.get('data')

    def run(self):
        login = self.api('POST', '/auth/login', {'username': self.username, 'password': self.password}, csrf=False)
        if 'ADMIN' not in login['roles']:
            raise AssertionError('初始化账号没有管理员角色')
        me = self.api('GET', '/auth/me')
        if me['userId'] != login['userId']:
            raise AssertionError('登录会话与当前用户不一致')
        print('登录、会话与管理员权限验证通过')
        self.api('GET', '/customers?page=1&size=1')
        self.api('GET', '/ai/knowledge/documents?page=1&size=1')
        for route in ('/login', '/knowledge/documents'):
            with self.client.open(self.base_url + route, timeout=30) as response:
                if response.status != 200 or b'<div id="app"></div>' not in response.read():
                    raise AssertionError(f'页面入口不可用：{route}')
        print('真实客户查询、知识接口与前端路由验证通过')
        if self.check_files:
            self.check_file_lifecycle()
        self.api('POST', '/auth/logout', {})
        print('安装包运行验收通过')

    def check_file_lifecycle(self):
        content = '安装包验证：隔离环境中的合成普通文本，不含客户数据。'.encode('utf-8')
        created = self.api('POST', '/files/upload-init', {
            'originalName': 'installer-smoke.txt',
            'mimeType': 'text/plain',
            'sizeBytes': len(content),
            'sha256': hashlib.sha256(content).hexdigest(),
            'classification': 'MATERIAL',
        })
        upload = urllib.request.Request(created['uploadUrl'], data=content, method='PUT', headers={'Content-Type': 'text/plain'})
        with self.client.open(upload, timeout=60) as response:
            etag = response.headers.get('ETag')
            if response.status != 200 or not etag:
                raise AssertionError('预签名上传没有返回真实 ETag')
        self.api('POST', '/files/upload-complete', {'fileId': created['fileId'], 'etag': etag})
        for _ in range(60):
            metadata = self.api('GET', f"/files/{created['fileId']}")
            if metadata['scanStatus'] == 'CLEAN':
                break
            if metadata['scanStatus'] in {'BLOCKED', 'ERROR'}:
                raise AssertionError('合成文本未通过病毒扫描')
            time.sleep(2)
        else:
            raise AssertionError('病毒扫描等待超时')
        material = self.api('POST', '/materials', {
            'title': '安装包合成验证素材',
            'categoryCode': 'GUIDE',
            'summary': '仅用于隔离安装环境验证',
            'tags': [],
            'fileId': created['fileId'],
            'changeNote': '隔离安装验收',
        })
        revision_id = material['revisions'][0]['id']
        download = self.api('GET', f"/materials/{material['id']}/revisions/{revision_id}/download")
        with self.client.open(download['downloadUrl'], timeout=60) as response:
            if response.read() != content:
                raise AssertionError('下载内容与上传内容不一致')
        print('公开地址上传、ClamAV 扫描、素材修订预签名下载及内容一致性验证通过')


def main():
    parser = argparse.ArgumentParser(description='隔离安装环境验收；不会打印登录密码、Cookie 或文件签名地址')
    parser.add_argument('--url', required=True)
    parser.add_argument('--credentials', type=Path, required=True)
    parser.add_argument('--files', action='store_true')
    args = parser.parse_args()
    credentials = {}
    for line in args.credentials.read_text(encoding='utf-8').splitlines():
        key, separator, value = line.partition('=')
        if separator and key in {'username', 'password'}:
            credentials[key] = value
    if not credentials.get('username') or not credentials.get('password'):
        raise SystemExit('凭据文件缺少账号或密码')
    SmokeTest(args.url, credentials['username'], credentials['password'], args.files).run()


if __name__ == '__main__':
    main()
