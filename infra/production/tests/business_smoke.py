#!/usr/bin/env python3
import argparse
import datetime
import hashlib
import http.cookiejar
import json
import shlex
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path


class ApiFailure(Exception):
    def __init__(self, status, code, message):
        super().__init__(f'HTTP {status} {code}: {message}')
        self.status = status
        self.code = code


class Client:
    def __init__(self, base_url):
        self.base_url = base_url.rstrip('/')
        self.cookies = http.cookiejar.CookieJar()
        self.http = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookies))
        self.user = None

    def login(self, username, password):
        self.user = self.api('POST', '/auth/login', {'username': username, 'password': password}, csrf=False)
        return self.user

    def api(self, method, path, body=None, key=None, csrf=True):
        headers = {'Accept': 'application/json'}
        data = None
        if body is not None:
            data = json.dumps(body).encode('utf-8')
            headers['Content-Type'] = 'application/json'
        if key:
            headers['Idempotency-Key'] = key
        if csrf and method in {'POST', 'PUT', 'PATCH', 'DELETE'}:
            token = next((cookie.value for cookie in self.cookies if cookie.name == 'CRM_CSRF'), None)
            if token:
                headers['X-CSRF-Token'] = token
        request = urllib.request.Request(self.base_url + '/api/v1' + path, data=data, headers=headers, method=method)
        try:
            with self.http.open(request, timeout=90) as response:
                result = json.load(response)
        except urllib.error.HTTPError as error:
            try:
                failure = json.loads(error.read().decode('utf-8'))
                code = failure.get('code', str(error.code))
                message = failure.get('message', '接口校验失败')
            except (ValueError, UnicodeDecodeError):
                code, message = str(error.code), '接口返回非 JSON 错误'
            raise ApiFailure(error.code, code, message) from error
        if result.get('code') != 'OK':
            raise ApiFailure(200, result.get('code', 'UNKNOWN'), result.get('message', '业务处理失败'))
        return result.get('data')

    def expect(self, method, path, status, body=None):
        request_key = f'expected-rejection-{uuid.uuid4()}' if method in {'POST', 'PUT', 'PATCH', 'DELETE'} else None
        try:
            self.api(method, path, body, key=request_key)
        except ApiFailure as error:
            if error.status != status:
                raise AssertionError(f'预期 HTTP {status}，实际 {error.status}：{error.code}') from error
            return error.code
        raise AssertionError(f'预期 HTTP {status}，但请求成功')

    def upload(self, content, mime_type, classification, institution_id=None):
        created = self.api('POST', '/files/upload-init', {
            'originalName': 'e2e-smoke.pdf' if mime_type == 'application/pdf' else 'e2e-smoke.txt',
            'mimeType': mime_type,
            'sizeBytes': len(content),
            'sha256': hashlib.sha256(content).hexdigest(),
            'classification': classification,
            **({} if institution_id is None else {'institutionId': institution_id}),
        })
        request = urllib.request.Request(created['uploadUrl'], data=content, method='PUT',
                                         headers={'Content-Type': mime_type, 'Origin': self.base_url})
        with self.http.open(request, timeout=90) as response:
            etag = response.headers.get('ETag')
            if response.status != 200 or not etag:
                raise AssertionError('预签名上传没有返回真实 ETag')
        self.api('POST', '/files/upload-complete', {'fileId': created['fileId'], 'etag': etag})
        for _ in range(60):
            metadata = self.api('GET', f"/files/{created['fileId']}")
            if metadata['scanStatus'] == 'CLEAN':
                return created['fileId']
            if metadata['scanStatus'] in {'BLOCKED', 'ERROR'}:
                raise AssertionError(f"文件校验失败：{metadata.get('lastError')}")
            time.sleep(1)
        raise AssertionError('病毒扫描超时')


def key(prefix):
    return f'e2e-{prefix}-{uuid.uuid4()}'


def now(offset_seconds=0):
    return (datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(seconds=offset_seconds)).isoformat().replace('+00:00', 'Z')


def clean_pdf():
    objects = [
        '<< /Type /Catalog /Pages 2 0 R >>',
        '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
        '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Contents 4 0 R >>',
        '<< /Length 0 >>\nstream\n\nendstream',
    ]
    result = b'%PDF-1.4\n'
    offsets = []
    for index, obj in enumerate(objects, 1):
        offsets.append(len(result))
        result += f'{index} 0 obj\n{obj}\nendobj\n'.encode('ascii')
    xref = len(result)
    result += f'xref\n0 {len(objects) + 1}\n'.encode('ascii')
    result += b'0000000000 65535 f \n'
    for offset in offsets:
        result += f'{offset:010d} 00000 n \n'.encode('ascii')
    result += f'trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n'.encode('ascii')
    return result


def create_user(admin, base_url, role_id, username, user_type='EMPLOYEE'):
    password = 'E2E-Synthetic-Only-2026'
    created = admin.api('POST', '/users', {
        'username': username,
        'displayName': '安装验收用户',
        'password': password,
        'userType': user_type,
        'roleIds': [role_id],
    })
    client = Client(base_url)
    client.login(username, password)
    return created['id'], client


def sql_text(value):
    return f"CONVERT(0x{value.encode('utf-8').hex()} USING utf8mb4)"


def map_operator(compose_dir, institution_id, username):
    sql = ("INSERT INTO crm_institution_user (tenant_id,institution_id,user_id,institution_role,status) "
           f"SELECT 1,{int(institution_id)},id,'OPERATOR','ACTIVE' FROM sys_user "
           f"WHERE tenant_id=1 AND username={sql_text(username)}")
    command = 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" medical_crm -e ' + shlex.quote(sql)
    result = subprocess.run(['docker', 'compose', '--env-file', '.env', '-f', 'compose.yml',
                             'exec', '-T', 'mysql', 'sh', '-c', command],
                            cwd=compose_dir, capture_output=True, text=True)
    if result.returncode:
        raise AssertionError(f'机构操作员测试夹具初始化失败：{result.stderr[-300:]}')


def map_employee_scope(compose_dir, user_id, resource, scope_type):
    sql = ("INSERT INTO sys_data_scope (tenant_id,user_id,resource,scope_type,scope_ref_id) "
           f"SELECT 1,{int(user_id)},{sql_text(resource)},{sql_text(scope_type)},NULL")
    command = 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" medical_crm -e ' + shlex.quote(sql)
    result = subprocess.run(['docker', 'compose', '--env-file', '.env', '-f', 'compose.yml',
                             'exec', '-T', 'mysql', 'sh', '-c', command],
                            cwd=compose_dir, capture_output=True, text=True)
    if result.returncode:
        raise AssertionError(f'员工数据范围测试夹具初始化失败：{result.stderr[-300:]}')


def create_institution(business, admin, finance, label):
    suffix = uuid.uuid4().hex[:12]
    profile = business.api('POST', '/institution-profiles', {
        'institutionNo': f'E2E-{label}-{suffix}',
        'fullName': f'安装验收机构{label}',
        'shortName': '验收机构',
        'businessTypeCode': 'MED_AESTHETIC',
        'ownershipCode': 'PRIVATE',
        'gradeCode': 'B',
        'settlementType': 'MONTHLY',
        'onlineRate': '0.18',
        'offlineRate': '0.12',
        'platformRate': '0.15',
    }, key('institution-create'))
    evidence = admin.upload(clean_pdf(), 'application/pdf', 'CHANGE_EVIDENCE', profile['id'])
    change = business.api('POST', f"/institution-profiles/{profile['id']}/activation-requests", {
        'baseVersion': profile['version'],
        'reason': '安装验收机构激活',
        'evidenceFileId': evidence,
    }, key('institution-activate'))
    reviewed = admin.api('POST', f"/institution-change-requests/{change['id']}/market-review", {
        'version': change['version'], 'decision': 'APPROVE', 'comment': '安装验收市场审批',
    }, key('institution-market'))
    if reviewed['currentStep'] == 'FINANCE':
        reviewed = finance.api('POST', f"/institution-change-requests/{change['id']}/finance-review", {
            'version': reviewed['version'], 'decision': 'APPROVE', 'comment': '安装验收财务审批',
        }, key('institution-finance'))
    if reviewed['status'] != 'APPROVED' or reviewed['currentStep'] != 'COMPLETED':
        raise AssertionError(f"机构审批未完成：{reviewed['status']}/{reviewed['currentStep']}")
    detail = admin.api('GET', f"/institution-profiles/{profile['id']}")
    if detail['status'] != 'ACTIVE':
        raise AssertionError('机构未激活')
    return profile['id']


def create_sellable_sku(admin, institution_id):
    project = admin.api('POST', '/projects', {
        'projectCode': f'E2E-PJ-{uuid.uuid4().hex[:10]}',
        'projectName': '安装验收项目',
        'listPriceFen': 10000,
    })
    package = admin.api('POST', '/packages', {
        'packageCode': f'E2E-PK-{uuid.uuid4().hex[:10]}',
        'packageName': '安装验收套餐',
    })
    version = admin.api('POST', f"/packages/{package['id']}/versions", {
        'selectionMode': 'FIXED',
        'choiceCount': 1,
        'projects': [{'projectId': project['id'], 'grantCount': 3, 'allocationPriceFen': 10000}],
    })
    admin.api('POST', f"/package-versions/{version['id']}/activate", {'version': version['version']})
    sku = admin.api('POST', '/skus', {
        'skuCode': f'E2E-SK-{uuid.uuid4().hex[:10]}',
        'packageVersionId': version['id'],
        'specName': '安装验收三次卡',
        'salePriceFen': 20000,
        'institutionIds': [institution_id],
    })
    admin.api('POST', f"/skus/{sku['id']}/activate", {'version': sku['version']})
    return sku['id']


def create_and_review_material(business, admin):
    content = '安装验收：独立合成普通文本，不包含任何客户数据。'.encode('utf-8')
    file_id = business.upload(content, 'text/plain', 'MATERIAL')
    material = business.api('POST', '/materials', {
        'title': '安装验收素材',
        'categoryCode': 'GUIDE',
        'summary': '仅用于安装环境端到端验收',
        'tags': [],
        'fileId': file_id,
        'changeNote': '安装验收创建',
    })
    revision = material['revisions'][0]
    submitted = business.api('POST', f"/materials/{material['id']}/revisions/{revision['id']}/submit",
                             {'version': revision['version']}, key('material-submit'))
    revision = submitted['revisions'][0]
    reviewed = admin.api('POST', f"/materials/{material['id']}/revisions/{revision['id']}/review", {
        'version': revision['version'], 'decision': 'APPROVED', 'reviewNote': '安装验收审核通过',
    }, key('material-review'))
    revision = reviewed['revisions'][0]
    if revision['status'] != 'APPROVED':
        raise AssertionError('素材未审核通过')
    download = admin.api('GET', f"/materials/{material['id']}/revisions/{revision['id']}/download")
    with admin.http.open(download['downloadUrl'], timeout=60) as response:
        if response.read() != content:
            raise AssertionError('素材下载内容不一致')
    return material['id'], revision


def check_mock_knowledge(admin, material_id, revision):
    config = admin.api('GET', '/ai/knowledge/config')
    admin.api('PUT', '/ai/knowledge/config', {
        'provider': 'mock',
        'baseUrl': 'https://mock.local',
        'datasetId': 'e2e-dataset',
        'timeoutSeconds': 10,
        'enabled': True,
        'syncEnabled': True,
        'version': config['version'],
    }, key('knowledge-config'))
    admin.api('POST', '/ai/knowledge/documents/publications', {
        'materialId': material_id,
        'revisionId': revision['id'],
        'version': revision['version'],
    }, key('knowledge-publish'))
    for _ in range(90):
        documents = admin.api('GET', '/ai/knowledge/documents?page=1&size=100')
        document = next((item for item in documents['items'] if item['materialId'] == material_id), None)
        if document and document['status'] == 'ACTIVE' and document['latestJobStatus'] == 'SUCCEEDED':
            disabled = admin.api('POST', f"/ai/knowledge/documents/{document['id']}/disable", {}, key('knowledge-disable'))
            if disabled['status'] != 'DISABLED':
                raise AssertionError('知识资料停用失败')
            return
        if document and document['status'] == 'FAILED':
            raise AssertionError(f"知识同步失败：{document.get('lastErrorMasked')}")
        time.sleep(1)
    raise AssertionError('Mock 知识同步超时')


def main():
    parser = argparse.ArgumentParser(description='独立安装环境的核心业务端到端验收，全部使用合成数据')
    parser.add_argument('--url', required=True)
    parser.add_argument('--credentials', type=Path, required=True)
    parser.add_argument('--compose-dir', required=True)
    parser.add_argument('--mock-knowledge', action='store_true')
    args = parser.parse_args()
    credentials = {}
    for line in args.credentials.read_text(encoding='utf-8').splitlines():
        name, separator, value = line.partition('=')
        if separator and name in {'username', 'password'}:
            credentials[name] = value
    base_url = args.url.rstrip('/')
    admin = Client(base_url)
    admin.login(credentials['username'], credentials['password'])
    roles = {role['roleCode']: role['id'] for role in admin.api('GET', '/roles')}
    business_id, business = create_user(admin, base_url, roles['BUSINESS'], f'e2e_business_{uuid.uuid4().hex[:10]}')
    manager_id, manager = create_user(admin, base_url, roles['MANAGER'], f'e2e_manager_{uuid.uuid4().hex[:10]}')
    finance_id, finance = create_user(admin, base_url, roles['FINANCE'], f'e2e_finance_{uuid.uuid4().hex[:10]}')
    map_employee_scope(args.compose_dir, business_id, 'institution', 'ALL')
    map_employee_scope(args.compose_dir, manager_id, 'customer', 'SELF')
    map_employee_scope(args.compose_dir, manager_id, 'institution', 'ALL')
    map_employee_scope(args.compose_dir, finance_id, 'institution', 'ALL')
    _, operator = create_user(admin, base_url, roles['INSTITUTION_STAFF'],
                              f'e2e_operator_{uuid.uuid4().hex[:10]}', 'INSTITUTION')
    _, other_operator = create_user(admin, base_url, roles['INSTITUTION_STAFF'],
                                    f'e2e_other_{uuid.uuid4().hex[:10]}', 'INSTITUTION')
    if operator.api('GET', '/auth/me')['institutionRoles']:
        raise AssertionError('新建机构用户不应自动获得机构映射')
    print('机构用户创建与角色检查通过；机构映射需要单独配置')

    institution_id = create_institution(business, admin, finance, '主机构')
    other_institution_id = create_institution(business, admin, finance, '其他机构')
    map_operator(args.compose_dir, institution_id, operator.user['username'])
    map_operator(args.compose_dir, other_institution_id, other_operator.user['username'])
    if operator.api('GET', '/auth/me')['institutionRoles'].get(str(institution_id)) != 'OPERATOR':
        raise AssertionError('机构操作员映射未生效')
    print('机构创建、双级审批激活与机构操作员映射通过（映射使用测试夹具）')

    sku_id = create_sellable_sku(admin, institution_id)
    customer = manager.api('POST', '/customers', {'nickname': '安装验收客户'}, key('customer-create'))
    order = manager.api('POST', '/orders', {
        'customerId': customer['id'],
        'receiverType': 'PLATFORM',
        'orderTypeCode': 'REGULAR',
        'payableFen': 20000,
        'validTo': now(86400 * 365)[:10],
        'items': [{'skuId': sku_id, 'quantity': 1}],
    })
    order_id = order['order']['order']['id']
    manager.api('POST', f'/orders/{order_id}/submit', {'version': 0}, key('order-submit'))
    payment = {
        'externalTradeNo': key('manual-payment'),
        'paymentChannel': 'BANK_TRANSFER',
        'amountFen': 20000,
        'paidAt': now(),
        'evidenceRef': 'E2E人工支付确认，非真实资金',
    }
    paid = admin.api('POST', f'/orders/{order_id}/payment-confirmations', payment, payment['externalTradeNo'])
    if paid['order']['order']['status'] != 'PAID' or len(paid['entitlements']) != 1:
        raise AssertionError('支付确认或权益发放失败')
    entitlement = paid['entitlements'][0]
    if entitlement['grantedCount'] != 3 or entitlement['availableCount'] != 3:
        raise AssertionError('支付后权益次数不正确')
    replay = admin.api('POST', f'/orders/{order_id}/payment-confirmations', payment, payment['externalTradeNo'])
    if len(replay['order']['payments']) != 1 or len(replay['entitlements']) != 1:
        raise AssertionError('支付幂等重试产生重复事实')
    print('项目、套餐、SKU、客户、下单、人工支付确认与幂等发放权益通过')

    appointment = manager.api('POST', '/appointments', {
        'customerId': customer['id'],
        'institutionId': institution_id,
        'scheduledAt': now(86400),
        'remark': '安装验收预约',
        'items': [{'entitlementId': entitlement['id'], 'count': 2}],
    }, key('appointment-create'))
    appointment_id = appointment['appointment']['id']
    for action in ('confirm', 'check-in', 'service-done'):
        appointment = operator.api('POST', f'/appointments/{appointment_id}/{action}', {
            'version': appointment['appointment']['version'], 'reason': f'E2E机构{action}',
        }, key(action))
    redemption_request = {
        'appointmentVersion': appointment['appointment']['version'],
        'serviceAt': now(),
        'evidenceRef': 'E2E机构核销凭证',
        'lines': [{'entitlementId': entitlement['id'], 'count': 2}],
    }
    manager.expect('POST', f'/appointments/{appointment_id}/redemptions', 403, redemption_request)
    redemption = operator.api('POST', f'/appointments/{appointment_id}/redemptions',
                              redemption_request, key('redemption-post'))
    if redemption['redemption']['status'] != 'POSTED':
        raise AssertionError('机构未正式核销')
    balance = admin.api('GET', f"/entitlements/{entitlement['id']}")
    if balance['availableBalance'] != 1:
        raise AssertionError('正式核销没有正确扣减可用次数')
    if admin.api('GET', f'/appointments/{appointment_id}')['appointment']['status'] != 'REDEEMED':
        raise AssertionError('预约未进入已核销状态')
    if admin.api('GET', f'/orders/{order_id}')['order']['order']['status'] != 'PARTIALLY_REDEEMED':
        raise AssertionError('两次核销、剩余一次权益的订单应为部分履约')
    other_operator.expect('GET', f'/appointments/{appointment_id}', 404)
    operator.expect('POST', f'/orders/{order_id}/payment-confirmations', 403, payment)
    print('管家核销拦截、机构确认预约与正式核销、权益扣减、跨机构拒绝与越权支付拦截通过')

    refund = manager.api('POST', '/refunds', {
        'orderId': order_id,
        'requestedFen': 10000,
        'reasonCode': 'CUSTOMER_REQUEST',
        'reasonDetail': '安装验收部分退款',
        'items': [{'entitlementId': entitlement['id'], 'revokeCount': 1, 'allocatedFen': 10000}],
    }, key('refund-create'))
    refund_id = refund['refund']['id']
    reviewed = admin.api('POST', f'/refunds/{refund_id}/review', {
        'version': refund['refund']['version'], 'decision': 'APPROVED', 'approvedFen': 10000,
        'reason': '安装验收审批',
    }, key('refund-review'))
    external_refund_no = key('manual-refund')
    admin.api('POST', f'/refunds/{refund_id}/executions', {
        'version': reviewed['refund']['version'],
        'provider': 'BANK_TRANSFER',
        'externalRefundNo': external_refund_no,
        'amountFen': 10000,
        'evidenceRef': 'E2E人工退款执行',
    }, key('refund-execute'))
    voucher_id = admin.upload(clean_pdf(), 'application/pdf', 'PAYMENT')
    succeeded = admin.api('POST', f'/refunds/{refund_id}/success', {
        'externalRefundNo': external_refund_no,
        'amountFen': 10000,
        'succeededAt': now(),
        'evidenceRef': 'E2E人工退款核验，非真实资金',
        'voucherFileId': voucher_id,
    }, key('refund-success'))
    if succeeded['refund']['status'] != 'SUCCEEDED':
        raise AssertionError('人工核验退款未成功')
    refunded_order = admin.api('GET', f'/orders/{order_id}')['order']['order']
    if refunded_order['refundedFen'] != 10000:
        raise AssertionError('退款金额未按实际成功结果入账')
    if admin.api('GET', f"/entitlements/{entitlement['id']}")['availableBalance'] != 0:
        raise AssertionError('退款成功后未回收剩余权益')
    print('退款申请、审批、执行、人工成功核验、金额入账与剩余权益回收通过')

    material_id, revision = create_and_review_material(business, admin)
    print('文本上传、病毒扫描、素材创建、提交、审核与专用下载通过')
    if args.mock_knowledge:
        check_mock_knowledge(admin, material_id, revision)
        print('Mock Dataset 发布、同步完成与停用通过（非真实 Dify 联通）')
    print('核心业务端到端验收全部通过')


if __name__ == '__main__':
    main()
