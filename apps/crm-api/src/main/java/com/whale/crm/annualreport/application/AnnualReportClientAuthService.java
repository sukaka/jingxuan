package com.whale.crm.annualreport.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.annualreport.adapter.AnnualH5SmsSender;
import com.whale.crm.annualreport.adapter.WechatOfficialOAuthClient;
import com.whale.crm.annualreport.api.dto.PublicAnnualAuthConfigResponse;
import com.whale.crm.annualreport.api.dto.PublicAnnualBookingRequest;
import com.whale.crm.annualreport.api.dto.PublicAnnualBookingResponse;
import com.whale.crm.annualreport.api.dto.PublicAnnualMeResponse;
import com.whale.crm.annualreport.api.dto.PublicAnnualReportResponse;
import com.whale.crm.annualreport.api.dto.PublicAnnualSmsSendRequest;
import com.whale.crm.annualreport.api.dto.PublicAnnualSmsSendResponse;
import com.whale.crm.annualreport.api.dto.PublicAnnualSmsVerifyRequest;
import com.whale.crm.annualreport.api.dto.PublicAnnualSmsVerifyResponse;
import com.whale.crm.annualreport.api.dto.PublicAnnualTelemetryRequest;
import com.whale.crm.annualreport.api.dto.PublicAnnualTelemetryResponse;
import com.whale.crm.annualreport.config.AnnualH5Properties;
import com.whale.crm.annualreport.domain.H5ClientIdentityBinding;
import com.whale.crm.annualreport.domain.H5SmsVerification;
import com.whale.crm.annualreport.domain.H5UnifiedVisitSession;
import com.whale.crm.annualreport.domain.YouzanAnnualProfile;
import com.whale.crm.annualreport.domain.YouzanAnnualSnapshotRepository;
import com.whale.crm.customer.application.MobileSupport;
import com.whale.crm.platform.security.AuthSecurityProperties;
import com.whale.crm.platform.security.crypto.BlindIndex;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.Year;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * C 端年终 H5 统一免密自动登录与会话服务。
 * 支持组合入口：
 * 1. 微信服务号静默授权（snsapi_base，仅调用 /sns/oauth2/access_token，不干扰有赞第三方平台全局 token）；
 * 2. 有赞/微信小程序 web-view 一次性票据换取登录态（sns/jscode2session）；
 * 3. 微信外或 OpenID 未命中老客的手机号短信验证码验证 + 永久绑定；
 * 4. C 端 Cookie（WHALE_H5_SESSION）采用独立签名密钥与极窄路径作用域，与 B 端 CRM_TOKEN 完全隔离。
 */
@Service
public class AnnualReportClientAuthService {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final String COOKIE_PATH = "/api/v1/public/annual-reports";
    private static final String TOKEN_TYPE_SESSION = "H5_SESSION";
    private static final String TOKEN_TYPE_PENDING = "H5_PENDING";
    private static final String TOKEN_TYPE_STATE = "H5_OAUTH_STATE";
    private static final int MAX_SMS_ATTEMPTS = 5;
    private static final int MAX_SINGLE_DELTA_MS = 30_000;
    private static final long MAX_SESSION_DURATION_MS = 3_600_000L;

    private final AnnualH5Properties properties;
    private final AuthSecurityProperties authProperties;
    private final YouzanAnnualSnapshotRepository snapshotRepository;
    private final YouzanAnnualSnapshotService snapshotService;
    private final AnnualReportTelemetryService telemetryService;
    private final WechatOfficialOAuthClient wechatOAuthClient;
    private final AnnualH5SmsSender smsSender;
    private final MobileSupport mobileSupport;
    private final BlindIndex blindIndex;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    public AnnualReportClientAuthService(AnnualH5Properties properties,
                                         AuthSecurityProperties authProperties,
                                         YouzanAnnualSnapshotRepository snapshotRepository,
                                         YouzanAnnualSnapshotService snapshotService,
                                         AnnualReportTelemetryService telemetryService,
                                         WechatOfficialOAuthClient wechatOAuthClient,
                                         AnnualH5SmsSender smsSender,
                                         MobileSupport mobileSupport,
                                         BlindIndex blindIndex,
                                         ObjectMapper objectMapper) {
        this.properties = properties;
        this.authProperties = authProperties;
        this.snapshotRepository = snapshotRepository;
        this.snapshotService = snapshotService;
        this.telemetryService = telemetryService;
        this.wechatOAuthClient = wechatOAuthClient;
        this.smsSender = smsSender;
        this.mobileSupport = mobileSupport;
        this.blindIndex = blindIndex;
        this.objectMapper = objectMapper;
    }

    public PublicAnnualAuthConfigResponse authConfig() {
        return new PublicAnnualAuthConfigResponse(
                properties.isWechatMpConfigured(),
                properties.isSmsEnabled(),
                properties.isSmsMockMode(),
                Year.now(SHANGHAI).getValue(),
                "/api/v1/public/annual-reports/auth/wechat/authorize"
        );
    }

    public String startWechatAuthorize(Integer reportYear, String shopId, HttpServletResponse response) {
        if (!properties.isWechatMpConfigured()) {
            throw new BusinessException(ErrorCode.CONFLICT, "微信服务号静默授权未启用，请先配置服务号 AppID/AppSecret 与网页授权回调域名");
        }
        int year = resolveYear(reportYear);
        String nonce = randomBase64Url(12);
        Map<String, Object> statePayload = new LinkedHashMap<>();
        statePayload.put("typ", TOKEN_TYPE_STATE);
        statePayload.put("tid", properties.defaultTenantId());
        statePayload.put("yr", year);
        statePayload.put("shop", shopId == null ? "" : shopId.trim());
        statePayload.put("nonce", nonce);
        statePayload.put("exp", Instant.now().plusSeconds(300).getEpochSecond());
        String state = signToken(statePayload);

        addCookie(response, properties.stateCookieName(), nonce, Duration.ofMinutes(5));
        AnnualH5Properties.WechatMp mp = properties.wechatMp();
        return mp.authorizeUrl()
                + "?appid=" + encode(mp.appId())
                + "&redirect_uri=" + encode(mp.callbackUrl())
                + "&response_type=code"
                + "&scope=snsapi_base"
                + "&state=" + encode(state)
                + "#wechat_redirect";
    }

    @Transactional
    public String handleWechatCallback(String code, String state,
                                       HttpServletRequest request, HttpServletResponse response) {
        Map<String, Object> stateData = verifyToken(state, TOKEN_TYPE_STATE);
        String expectedNonce = asString(stateData.get("nonce"));
        String cookieNonce = readCookie(request, properties.stateCookieName());
        clearCookie(response, properties.stateCookieName());
        if (expectedNonce == null || cookieNonce == null || !constantTimeEquals(expectedNonce, cookieNonce)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "微信授权状态校验失败，请重新从入口打开");
        }

        long tenantId = asLong(stateData.get("tid"), properties.defaultTenantId());
        int reportYear = (int) asLong(stateData.get("yr"), Year.now(SHANGHAI).getValue());
        String shopId = blankToNull(asString(stateData.get("shop")));

        WechatOfficialOAuthClient.OAuthIdentity identity = wechatOAuthClient.exchangeMpCode(code);
        String openidHash = snapshotService.hashWechatOpenId(identity.openid());
        String unionidHash = snapshotService.hashWechatUnionId(identity.unionid());

        ResolvedClientIdentity resolved = resolveByWechatIdentity(tenantId, "WECHAT_MP", openidHash, unionidHash, shopId);
        if (resolved != null) {
            snapshotRepository.upsertIdentityBinding(new H5ClientIdentityBinding(
                    null, tenantId, "WECHAT_MP", openidHash, unionidHash,
                    resolved.shopId(), resolved.yzOpenId(), resolved.customerId(),
                    resolved.mobileHash(), "WECHAT_OAUTH", null, null
            ));
            clearCookie(response, properties.pendingCookieName());
            issueSessionCookie(response, tenantId, resolved, "WECHAT_OAUTH");
            return buildEntryUrl(reportYear, shopId, "auth=ok");
        }

        issuePendingWechatCookie(response, tenantId, "WECHAT_MP", openidHash, unionidHash);
        return buildEntryUrl(reportYear, shopId, "needBind=1");
    }

    @Transactional
    public PublicAnnualSmsSendResponse sendSmsCode(PublicAnnualSmsSendRequest request) {
        if (!properties.isSmsEnabled()) {
            throw new BusinessException(ErrorCode.CONFLICT, "当前未开启短信验证码通道，请联系管家获取专属链接");
        }
        long tenantId = properties.defaultTenantId();
        String normalized = mobileSupport.normalize(request.mobile());
        String mobileHash = mobileSupport.hash(normalized);
        Instant now = Instant.now();

        Optional<H5SmsVerification> latest = snapshotRepository.findLatestSmsVerification(tenantId, mobileHash);
        if (latest.isPresent() && latest.orElseThrow().createdAt() != null
                && latest.orElseThrow().createdAt().plusSeconds(properties.sms().cooldownSeconds()).isAfter(now)) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "验证码发送过于频繁，请稍后再试");
        }

        String code = String.format("%06d", secureRandom.nextInt(1_000_000));
        String codeHash = hashSmsCode(mobileHash, code);
        int ttl = properties.sms().ttlSeconds();
        snapshotRepository.insertSmsVerification(new H5SmsVerification(
                null, tenantId, mobileHash, codeHash, 0, now.plusSeconds(ttl), null, now
        ));
        AnnualH5SmsSender.SendResult sendResult = smsSender.sendVerificationCode(normalized, code, ttl);
        return new PublicAnnualSmsSendResponse(
                true,
                ttl,
                properties.sms().cooldownSeconds(),
                MobileSupport.mask(normalized),
                properties.isSmsMockMode() ? sendResult.devPreviewCode() : null
        );
    }

    @Transactional
    public PublicAnnualSmsVerifyResponse verifySmsCode(PublicAnnualSmsVerifyRequest request,
                                                       HttpServletRequest httpRequest,
                                                       HttpServletResponse httpResponse) {
        long tenantId = properties.defaultTenantId();
        String normalized = mobileSupport.normalize(request.mobile());
        String mobileHash = mobileSupport.hash(normalized);
        Instant now = Instant.now();

        H5SmsVerification record = snapshotRepository.findLatestSmsVerificationForUpdate(tenantId, mobileHash)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED, "请先获取短信验证码"));
        if (record.consumedAt() != null || record.expiresAt().isBefore(now)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "验证码已失效，请重新获取");
        }
        if (record.attemptCount() >= MAX_SMS_ATTEMPTS) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "验证码尝试次数过多，请重新获取");
        }
        String candidateHash = hashSmsCode(mobileHash, request.code().trim());
        if (!constantTimeEquals(candidateHash, record.codeHash())) {
            snapshotRepository.incrementSmsAttempt(tenantId, record.id());
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "验证码不正确，请核对后重试");
        }
        snapshotRepository.consumeSmsVerification(tenantId, record.id(), now);

        String shopId = blankToNull(request.shopId());
        ResolvedClientIdentity resolved = resolveByMobileHash(tenantId, mobileHash, shopId);

        // 若此前是从微信静默授权跳转过来未命中 OpenID，此处完成永久绑定，下次再打开自动静默免登
        boolean wechatBound = false;
        PendingWechatContext pending = readPendingWechat(httpRequest);
        if (pending != null && resolved != null) {
            snapshotRepository.upsertIdentityBinding(new H5ClientIdentityBinding(
                    null, tenantId, pending.channelType(), pending.openidHash(), pending.unionidHash(),
                    resolved.shopId(), resolved.yzOpenId(), resolved.customerId(),
                    mobileHash, "SMS_VERIFY", null, null
            ));
            clearCookie(httpResponse, properties.pendingCookieName());
            wechatBound = true;
        }

        if (resolved == null) {
            return new PublicAnnualSmsVerifyResponse(
                    false, false, false, null,
                    "暂未在鲸选美学有赞或 CRM 档案中找到该手机号对应的购买记录，请核对下单手机号或联系专属管家。"
            );
        }

        issueSessionCookie(httpResponse, tenantId, resolved, "SMS_VERIFY");
        String displayName = resolveDisplayName(tenantId, resolved);
        return new PublicAnnualSmsVerifyResponse(
                true, true, wechatBound, displayName,
                wechatBound ? "验证成功，已自动绑定当前微信，下次打开可直接免密进入。" : "验证成功，正在开启您的年度美学轨迹。"
        );
    }

    @Transactional(readOnly = true)
    public PublicAnnualMeResponse getMyReport(Integer reportYear, HttpServletRequest request) {
        int year = resolveYear(reportYear);
        ClientSession session = readSession(request);
        if (session == null) {
            PendingWechatContext pending = readPendingWechat(request);
            return new PublicAnnualMeResponse(
                    false,
                    pending != null || !properties.isWechatMpConfigured(),
                    pending != null,
                    properties.isWechatMpConfigured(),
                    properties.isSmsEnabled(),
                    properties.isSmsMockMode(),
                    false,
                    null,
                    null,
                    null,
                    null
            );
        }
        ResolvedClientIdentity resolved = new ResolvedClientIdentity(
                session.shopId(), session.yzOpenId(), session.customerId(), null);
        return buildAuthenticatedMeResponse(session.tenantId(), resolved, session.authChannel(), year);
    }

    @Transactional
    public PublicAnnualTelemetryResponse recordMyTelemetry(Integer reportYear,
                                                           PublicAnnualTelemetryRequest body,
                                                           HttpServletRequest request) {
        ClientSession session = requireSession(request);
        int year = resolveYear(reportYear);
        String activeShareKey = findActiveShareKey(session.tenantId(), session.customerId(), year);
        if (activeShareKey != null) {
            return telemetryService.recordTelemetry(activeShareKey, body);
        }

        String buyerKey = session.yzOpenId() != null ? session.yzOpenId() : ("CRM:" + session.customerId());
        String shopId = session.shopId() != null ? session.shopId() : "CRM";
        String displayName = resolveDisplayName(session.tenantId(),
                new ResolvedClientIdentity(session.shopId(), session.yzOpenId(), session.customerId(), null));
        Instant now = Instant.now();
        int sceneIndex = body.sceneIndex();
        int safeDelta = Math.min(MAX_SINGLE_DELTA_MS,
                Math.max(0, body.deltaDurationMs() == null ? 0 : body.deltaDurationMs()));
        String device = body.clientDevice() == null || body.clientDevice().isBlank() ? "UNKNOWN" : body.clientDevice();

        Optional<H5UnifiedVisitSession> existingOpt = snapshotRepository.findUnifiedVisitForUpdate(
                session.tenantId(), year, buyerKey, body.sessionKey());
        H5UnifiedVisitSession updated;
        if (existingOpt.isEmpty()) {
            Map<String, Long> durations = new LinkedHashMap<>();
            durations.put(Integer.toString(sceneIndex), (long) safeDelta);
            H5UnifiedVisitSession created = new H5UnifiedVisitSession(
                    null, session.tenantId(), shopId, buyerKey, session.customerId(), displayName,
                    year, body.sessionKey(), session.authChannel(), now, now, safeDelta,
                    sceneIndex, 1, writeJson(List.of(sceneIndex)), writeJson(durations),
                    "IGNITE_LAMP".equals(body.eventType()),
                    "VIEW_ORDER".equals(body.eventType()) ? 1 : 0,
                    "TOGGLE_PRIVACY".equals(body.eventType()),
                    "GENERATE_POSTER".equals(body.eventType()),
                    "BOOKING_INTENT".equals(body.eventType()),
                    device, null, null
            );
            snapshotRepository.insertUnifiedVisit(created);
            updated = snapshotRepository.findUnifiedVisitForUpdate(
                    session.tenantId(), year, buyerKey, body.sessionKey()).orElseThrow();
        } else {
            H5UnifiedVisitSession cur = existingOpt.orElseThrow();
            Set<Integer> scenes = new TreeSet<>(readJson(cur.viewedScenesJson(), new TypeReference<List<Integer>>() {
            }, List.of()));
            scenes.add(sceneIndex);
            Map<String, Long> durations = new LinkedHashMap<>(readJson(cur.sceneDurationsJson(),
                    new TypeReference<Map<String, Long>>() {
                    }, Map.of()));
            long allowedDelta = Math.max(0L, Math.min(safeDelta, MAX_SESSION_DURATION_MS - cur.visibleDurationMs()));
            String sceneKey = Integer.toString(sceneIndex);
            durations.put(sceneKey, durations.getOrDefault(sceneKey, 0L) + allowedDelta);
            updated = new H5UnifiedVisitSession(
                    cur.id(), cur.tenantId(), cur.shopId(), cur.yzOpenId(), cur.customerId(), cur.displayNameSnapshot(),
                    cur.reportYear(), cur.sessionKey(), cur.entryChannel(), cur.firstOpenAt(), now,
                    cur.visibleDurationMs() + allowedDelta,
                    Math.max(cur.maxSceneReached(), sceneIndex),
                    scenes.size(),
                    writeJson(new ArrayList<>(scenes)),
                    writeJson(durations),
                    cur.ignitedLamp() || "IGNITE_LAMP".equals(body.eventType()),
                    cur.openedOrderCount() + ("VIEW_ORDER".equals(body.eventType()) ? 1 : 0),
                    cur.toggledPrivacy() || "TOGGLE_PRIVACY".equals(body.eventType()),
                    cur.generatedPoster() || "GENERATE_POSTER".equals(body.eventType()),
                    cur.clickedBookButler() || "BOOKING_INTENT".equals(body.eventType()),
                    "UNKNOWN".equals(device) ? cur.clientDevice() : device,
                    cur.createdAt(), now
            );
            snapshotRepository.updateUnifiedVisit(updated);
        }
        return new PublicAnnualTelemetryResponse(true, updated.visibleDurationMs(),
                updated.viewedSceneCount(), updated.maxSceneReached());
    }

    @Transactional
    public PublicAnnualBookingResponse submitMyBookingIntent(Integer reportYear,
                                                             PublicAnnualBookingRequest body,
                                                             HttpServletRequest request) {
        ClientSession session = requireSession(request);
        int year = resolveYear(reportYear);
        String activeShareKey = findActiveShareKey(session.tenantId(), session.customerId(), year);
        if (activeShareKey != null) {
            return telemetryService.submitBookingIntent(activeShareKey, body);
        }
        recordMyTelemetry(year, new PublicAnnualTelemetryRequest(
                body.sessionKey(), "BOOKING_INTENT", 5, 0, "UNKNOWN", body.note()), request);
        return new PublicAnnualBookingResponse(
                true,
                false,
                "鲸选美学专属顾问",
                "已登记您的年度焕美预约意向，请联系专属顾问确认到院时间（正式核销以合作机构到院确认为准）。"
        );
    }

    public void logout(HttpServletResponse response) {
        clearCookie(response, properties.sessionCookieName());
        clearCookie(response, properties.pendingCookieName());
        clearCookie(response, properties.stateCookieName());
    }

    private PublicAnnualMeResponse buildAuthenticatedMeResponse(long tenantId,
                                                                ResolvedClientIdentity resolved,
                                                                String authChannel,
                                                                int reportYear) {
        String activeShareKey = findActiveShareKey(tenantId, resolved.customerId(), reportYear);
        if (activeShareKey != null) {
            PublicAnnualReportResponse report = telemetryService.publicView(activeShareKey);
            return new PublicAnnualMeResponse(
                    true, false, false, properties.isWechatMpConfigured(),
                    properties.isSmsEnabled(), properties.isSmsMockMode(),
                    report.orderCount() > 0, authChannel, "CRM_SNAPSHOT", activeShareKey, report
            );
        }

        Optional<YouzanAnnualProfile> profileOpt = Optional.empty();
        if (resolved.shopId() != null && resolved.yzOpenId() != null) {
            profileOpt = snapshotRepository.findProfileByKey(tenantId, resolved.shopId(), resolved.yzOpenId());
        }
        if (profileOpt.isEmpty() && resolved.customerId() != null) {
            profileOpt = snapshotRepository.findProfileByCustomerId(tenantId, resolved.customerId());
        }
        if (profileOpt.isPresent()) {
            PublicAnnualReportResponse report = snapshotService.buildPublicReportFromYouzanProfile(
                    tenantId, profileOpt.orElseThrow(), reportYear);
            return new PublicAnnualMeResponse(
                    true, false, false, properties.isWechatMpConfigured(),
                    properties.isSmsEnabled(), properties.isSmsMockMode(),
                    report.orderCount() > 0, authChannel, "YOUZAN_READONLY_SNAPSHOT", null, report
            );
        }

        return new PublicAnnualMeResponse(
                true, false, false, properties.isWechatMpConfigured(),
                properties.isSmsEnabled(), properties.isSmsMockMode(),
                false, authChannel, "EMPTY", null, null
        );
    }

    private ResolvedClientIdentity resolveByWechatIdentity(long tenantId, String channelType,
                                                           String openidHash, String unionidHash, String shopId) {
        Optional<H5ClientIdentityBinding> binding = snapshotRepository.findIdentityBinding(
                tenantId, channelType, openidHash);
        if (binding.isEmpty() && unionidHash != null) {
            binding = snapshotRepository.findIdentityBindingByUnionId(tenantId, unionidHash);
        }
        if (binding.isPresent()) {
            H5ClientIdentityBinding b = binding.orElseThrow();
            return new ResolvedClientIdentity(b.shopId(), b.yzOpenId(), b.customerId(), b.mobileHash());
        }

        Optional<YouzanAnnualProfile> profile = Optional.empty();
        if (unionidHash != null) {
            profile = snapshotRepository.findProfileByUnionIdHash(tenantId, unionidHash, shopId);
        }
        if (profile.isEmpty() && openidHash != null) {
            profile = snapshotRepository.findProfileByOpenIdHash(tenantId, openidHash, shopId);
        }
        return profile.map(p -> new ResolvedClientIdentity(p.shopId(), p.yzOpenId(), p.customerId(), p.mobileHash()))
                .orElse(null);
    }

    private ResolvedClientIdentity resolveByMobileHash(long tenantId, String mobileHash, String shopId) {
        Optional<YouzanAnnualProfile> profile = snapshotRepository.findProfileByMobileHash(tenantId, mobileHash, shopId);
        if (profile.isPresent()) {
            YouzanAnnualProfile p = profile.orElseThrow();
            Long customerId = p.customerId();
            if (customerId == null) {
                List<Long> matched = snapshotRepository.findCustomerIdsByMobileHash(tenantId, mobileHash);
                if (matched.size() == 1) {
                    customerId = matched.getFirst();
                }
            }
            return new ResolvedClientIdentity(p.shopId(), p.yzOpenId(), customerId, mobileHash);
        }
        List<Long> matchedCustomers = snapshotRepository.findCustomerIdsByMobileHash(tenantId, mobileHash);
        if (matchedCustomers.size() == 1) {
            return new ResolvedClientIdentity(null, null, matchedCustomers.getFirst(), mobileHash);
        }
        return null;
    }

    private String findActiveShareKey(long tenantId, Long customerId, int reportYear) {
        if (customerId == null || customerId <= 0) {
            return null;
        }
        return snapshotRepository.findActiveReportShareKeyByCustomerYear(tenantId, customerId, reportYear).orElse(null);
    }

    private String resolveDisplayName(long tenantId, ResolvedClientIdentity resolved) {
        if (resolved.shopId() != null && resolved.yzOpenId() != null) {
            Optional<YouzanAnnualProfile> p = snapshotRepository.findProfileByKey(
                    tenantId, resolved.shopId(), resolved.yzOpenId());
            if (p.isPresent()) {
                return p.orElseThrow().displayName();
            }
        }
        if (resolved.customerId() != null) {
            Optional<YouzanAnnualProfile> p = snapshotRepository.findProfileByCustomerId(
                    tenantId, resolved.customerId());
            if (p.isPresent()) {
                return p.orElseThrow().displayName();
            }
        }
        return "鲸选贵宾";
    }

    private void issueSessionCookie(HttpServletResponse response, long tenantId,
                                    ResolvedClientIdentity resolved, String authChannel) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("typ", TOKEN_TYPE_SESSION);
        payload.put("tid", tenantId);
        payload.put("shop", resolved.shopId() == null ? "" : resolved.shopId());
        payload.put("yz", resolved.yzOpenId() == null ? "" : resolved.yzOpenId());
        payload.put("cid", resolved.customerId() == null ? Long.valueOf(0L) : resolved.customerId());
        payload.put("ch", authChannel);
        payload.put("exp", Instant.now().plusSeconds(properties.sessionTtlSeconds()).getEpochSecond());
        addCookie(response, properties.sessionCookieName(), signToken(payload),
                Duration.ofSeconds(properties.sessionTtlSeconds()));
    }

    private void issuePendingWechatCookie(HttpServletResponse response, long tenantId,
                                          String channelType, String openidHash, String unionidHash) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("typ", TOKEN_TYPE_PENDING);
        payload.put("tid", tenantId);
        payload.put("ch", channelType);
        payload.put("oh", openidHash);
        payload.put("uh", unionidHash == null ? "" : unionidHash);
        payload.put("exp", Instant.now().plusSeconds(600).getEpochSecond());
        addCookie(response, properties.pendingCookieName(), signToken(payload), Duration.ofMinutes(10));
    }

    private ClientSession requireSession(HttpServletRequest request) {
        ClientSession session = readSession(request);
        if (session == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先通过微信自动登录或手机号验证后再操作");
        }
        return session;
    }

    private ClientSession readSession(HttpServletRequest request) {
        String raw = readCookie(request, properties.sessionCookieName());
        if (raw == null) {
            return null;
        }
        try {
            Map<String, Object> data = verifyToken(raw, TOKEN_TYPE_SESSION);
            long tid = asLong(data.get("tid"), properties.defaultTenantId());
            String shop = blankToNull(asString(data.get("shop")));
            String yz = blankToNull(asString(data.get("yz")));
            long cidVal = asLong(data.get("cid"), 0L);
            Long cid = cidVal > 0 ? cidVal : null;
            String ch = asString(data.get("ch"));
            return new ClientSession(tid, shop, yz, cid, ch == null ? "UNIFIED" : ch);
        } catch (BusinessException ex) {
            return null;
        }
    }

    private PendingWechatContext readPendingWechat(HttpServletRequest request) {
        String raw = readCookie(request, properties.pendingCookieName());
        if (raw == null) {
            return null;
        }
        try {
            Map<String, Object> data = verifyToken(raw, TOKEN_TYPE_PENDING);
            String oh = blankToNull(asString(data.get("oh")));
            if (oh == null) {
                return null;
            }
            String ch = asString(data.get("ch"));
            String uh = blankToNull(asString(data.get("uh")));
            return new PendingWechatContext(ch == null ? "WECHAT_MP" : ch, oh, uh);
        } catch (BusinessException ex) {
            return null;
        }
    }

    private String signToken(Map<String, Object> payload) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(writeJson(payload).getBytes(StandardCharsets.UTF_8));
        String sig = hmacBase64Url(encoded);
        return encoded + "." + sig;
    }

    private Map<String, Object> verifyToken(String token, String expectedType) {
        if (token == null || !token.contains(".")) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "凭证无效或已过期");
        }
        int dot = token.lastIndexOf('.');
        String encoded = token.substring(0, dot);
        String sig = token.substring(dot + 1);
        if (!constantTimeEquals(hmacBase64Url(encoded), sig)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "凭证签名无效");
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            Map<String, Object> data = objectMapper.readValue(bytes, new TypeReference<Map<String, Object>>() {
            });
            if (!expectedType.equals(asString(data.get("typ")))) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "凭证用途不匹配");
            }
            long exp = asLong(data.get("exp"), 0L);
            if (exp <= Instant.now().getEpochSecond()) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "凭证已过期，请重新进入");
            }
            return data;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "凭证解析失败");
        }
    }

    private String hmacBase64Url(String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(deriveSigningKey(authProperties.jwtSecret()), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("H5 会话签名失败", ex);
        }
    }

    private byte[] deriveSigningKey(String jwtSecret) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest((jwtSecret + ":whale-annual-h5-client-v1").getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("派生 H5 签名密钥失败", ex);
        }
    }

    private String hashSmsCode(String mobileHash, String code) {
        return blindIndex.hash("h5sms:" + mobileHash + ":" + code);
    }

    private void addCookie(HttpServletResponse response, String name, String value, Duration maxAge) {
        ResponseCookie cookie = ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(authProperties.cookieSecure())
                .sameSite("Lax")
                .path(COOKIE_PATH)
                .maxAge(maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearCookie(HttpServletResponse response, String name) {
        ResponseCookie cookie = ResponseCookie.from(name, "")
                .httpOnly(true)
                .secure(authProperties.cookieSecure())
                .sameSite("Lax")
                .path(COOKIE_PATH)
                .maxAge(Duration.ZERO)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private String readCookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private String buildEntryUrl(int reportYear, String shopId, String extraQuery) {
        StringBuilder sb = new StringBuilder(properties.publicOrigin())
                .append("/h5/annual-reports/entry?year=").append(reportYear);
        if (shopId != null && !shopId.isBlank()) {
            sb.append("&shopId=").append(encode(shopId));
        }
        if (extraQuery != null && !extraQuery.isBlank()) {
            sb.append('&').append(extraQuery);
        }
        return sb.toString();
    }

    private int resolveYear(Integer year) {
        return year != null ? year : Year.now(SHANGHAI).getValue();
    }

    private String randomBase64Url(int byteLen) {
        byte[] bytes = new byte[byteLen];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "序列化失败");
        }
    }

    private <T> T readJson(String raw, TypeReference<T> ref, T fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return objectMapper.readValue(raw, ref);
        } catch (Exception ex) {
            return fallback;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long asLong(Object value, long fallback) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ex) {
                return fallback;
            }
        }
        return fallback;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record ResolvedClientIdentity(String shopId, String yzOpenId, Long customerId, String mobileHash) {
    }

    private record ClientSession(long tenantId, String shopId, String yzOpenId, Long customerId, String authChannel) {
    }

    private record PendingWechatContext(String channelType, String openidHash, String unionidHash) {
    }
}
