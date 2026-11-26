package com.whale.crm.annualreport.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.annualreport.api.dto.PublicAnnualBookingRequest;
import com.whale.crm.annualreport.api.dto.PublicAnnualBookingResponse;
import com.whale.crm.annualreport.api.dto.PublicAnnualReportResponse;
import com.whale.crm.annualreport.api.dto.PublicAnnualTelemetryRequest;
import com.whale.crm.annualreport.api.dto.PublicAnnualTelemetryResponse;
import com.whale.crm.annualreport.domain.AnnualReport;
import com.whale.crm.annualreport.domain.AnnualReportRepository;
import com.whale.crm.annualreport.domain.AnnualReportVisitEvent;
import com.whale.crm.annualreport.domain.AnnualReportVisitSession;
import com.whale.crm.customer.application.TaskApplicationService;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.quickreply.application.QuickReplyTextMasker;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * C 端公开年终 H5 只读访问、停留/页数埋点采集与预约意向服务。
 * 安全约束：
 * 1. 仅凭 43 位高随机 ACTIVE shareKey 访问，绝不泄露内部 ID/手机号；
 * 2. 单次心跳增量硬封顶 30 秒，防止设备锁屏或伪造请求刷高停留时长；
 * 3. 客户点击「预约专属管家」只创建去重的 FOLLOWUP 待办，绝不自动确认预约、订单或核销。
 */
@Service
public class AnnualReportTelemetryService {

    private static final Pattern SHARE_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    private static final int MAX_SINGLE_DELTA_MS = 30_000;
    private static final long MAX_SESSION_DURATION_MS = 3_600_000L; // 单会话累计上限 1 小时，防恶意灌水

    private final AnnualReportRepository repository;
    private final TaskApplicationService taskService;
    private final QuickReplyTextMasker masker;
    private final ObjectMapper objectMapper;

    public AnnualReportTelemetryService(AnnualReportRepository repository,
                                        TaskApplicationService taskService,
                                        QuickReplyTextMasker masker,
                                        ObjectMapper objectMapper) {
        this.repository = repository;
        this.taskService = taskService;
        this.masker = masker;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public PublicAnnualReportResponse publicView(String shareKey) {
        AnnualReport report = requireActiveReport(shareKey);
        try {
            return objectMapper.readValue(report.snapshotJson(), PublicAnnualReportResponse.class);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "年度报告快照解析失败");
        }
    }

    @Transactional
    public PublicAnnualTelemetryResponse recordTelemetry(String shareKey, PublicAnnualTelemetryRequest request) {
        AnnualReport report = requireActiveReport(shareKey);
        Instant now = Instant.now();
        int sceneIndex = request.sceneIndex();
        int safeDelta = Math.min(MAX_SINGLE_DELTA_MS,
                Math.max(0, request.deltaDurationMs() == null ? 0 : request.deltaDurationMs()));
        String device = request.clientDevice() == null || request.clientDevice().isBlank()
                ? "UNKNOWN" : request.clientDevice();

        Optional<AnnualReportVisitSession> existingOpt = repository.findSessionByKeyForUpdate(
                report.tenantId(), report.id(), request.sessionKey());

        AnnualReportVisitSession updatedSession;
        if (existingOpt.isEmpty()) {
            List<Integer> scenes = List.of(sceneIndex);
            Map<String, Long> sceneDurations = new LinkedHashMap<>();
            sceneDurations.put(Integer.toString(sceneIndex), (long) safeDelta);

            AnnualReportVisitSession created = new AnnualReportVisitSession(
                    null,
                    report.tenantId(),
                    report.id(),
                    report.customerId(),
                    report.ownerStaffId(),
                    report.teamId(),
                    report.reportYear(),
                    request.sessionKey(),
                    now,
                    now,
                    safeDelta,
                    sceneIndex,
                    1,
                    writeJson(scenes),
                    writeJson(sceneDurations),
                    "IGNITE_LAMP".equals(request.eventType()),
                    "VIEW_ORDER".equals(request.eventType()) ? 1 : 0,
                    "TOGGLE_PRIVACY".equals(request.eventType()),
                    "GENERATE_POSTER".equals(request.eventType()),
                    "BOOKING_INTENT".equals(request.eventType()),
                    null,
                    device,
                    null,
                    null
            );
            repository.insertSession(created);
            updatedSession = repository.findSessionByKeyForUpdate(
                    report.tenantId(), report.id(), request.sessionKey()).orElseThrow();
        } else {
            AnnualReportVisitSession current = existingOpt.orElseThrow();
            Set<Integer> scenes = new TreeSet<>(readJson(current.viewedScenesJson(), new TypeReference<List<Integer>>() {
            }, List.of()));
            scenes.add(sceneIndex);

            Map<String, Long> sceneDurations = new LinkedHashMap<>(
                    readJson(current.sceneDurationsJson(), new TypeReference<Map<String, Long>>() {
                    }, Map.of()));
            long allowedDelta = Math.max(0L, Math.min(safeDelta,
                    MAX_SESSION_DURATION_MS - current.visibleDurationMs()));
            long nextTotalDuration = current.visibleDurationMs() + allowedDelta;
            String sceneKey = Integer.toString(sceneIndex);
            sceneDurations.put(sceneKey, sceneDurations.getOrDefault(sceneKey, 0L) + allowedDelta);

            updatedSession = new AnnualReportVisitSession(
                    current.id(),
                    current.tenantId(),
                    current.reportId(),
                    current.customerId(),
                    current.ownerStaffId(),
                    current.teamId(),
                    current.reportYear(),
                    current.sessionKey(),
                    current.firstOpenAt(),
                    now,
                    nextTotalDuration,
                    Math.max(current.maxSceneReached(), sceneIndex),
                    scenes.size(),
                    writeJson(new ArrayList<>(scenes)),
                    writeJson(sceneDurations),
                    current.ignitedLamp() || "IGNITE_LAMP".equals(request.eventType()),
                    current.openedOrderCount() + ("VIEW_ORDER".equals(request.eventType()) ? 1 : 0),
                    current.toggledPrivacy() || "TOGGLE_PRIVACY".equals(request.eventType()),
                    current.generatedPoster() || "GENERATE_POSTER".equals(request.eventType()),
                    current.clickedBookButler() || "BOOKING_INTENT".equals(request.eventType()),
                    current.bookingTaskId(),
                    "UNKNOWN".equals(device) ? current.clientDevice() : device,
                    current.createdAt(),
                    now
            );
            repository.updateSession(updatedSession);
            safeDelta = (int) allowedDelta;
        }

        String detailJson = request.detail() == null || request.detail().isBlank()
                ? null
                : writeJson(Map.of("note", masker.mask(request.detail(), 200)));
        repository.insertEvent(new AnnualReportVisitEvent(
                null,
                report.tenantId(),
                report.id(),
                updatedSession.id(),
                report.customerId(),
                request.eventType(),
                sceneIndex,
                safeDelta,
                detailJson,
                now,
                null
        ));

        return new PublicAnnualTelemetryResponse(
                true,
                updatedSession.visibleDurationMs(),
                updatedSession.viewedSceneCount(),
                updatedSession.maxSceneReached()
        );
    }

    @Transactional
    public PublicAnnualBookingResponse submitBookingIntent(String shareKey, PublicAnnualBookingRequest request) {
        AnnualReport report = requireActiveReport(shareKey);
        Instant now = Instant.now();

        // 先确保该会话已存在并加锁，保证同一次会话幂等
        Optional<AnnualReportVisitSession> sessionOpt = repository.findSessionByKeyForUpdate(
                report.tenantId(), report.id(), request.sessionKey());
        AnnualReportVisitSession session;
        if (sessionOpt.isEmpty()) {
            recordTelemetry(shareKey, new PublicAnnualTelemetryRequest(
                    request.sessionKey(), "BOOKING_INTENT", 5, 0, "UNKNOWN", request.note()));
            session = repository.findSessionByKeyForUpdate(
                    report.tenantId(), report.id(), request.sessionKey()).orElseThrow();
        } else {
            session = sessionOpt.orElseThrow();
        }

        String sourceRef = "ANNUAL_REPORT:" + report.id();
        String safeNote = request.note() == null || request.note().isBlank()
                ? "客户在 " + report.reportYear() + " 年度购买轨迹 H5 权益页点击预约专属管家"
                : masker.mask(request.note(), 200);
        String title = "年终H5预约意向：" + report.displayNameSnapshot()
                + "（" + report.reportYear() + "年度报告）";
        String description = safeNote + "；当前剩余可用权益 " + report.remainingEntitlementCount()
                + " 次，请管家主动联系客户确认到院时间（正式核销以合作机构到院确认为准）。";

        TaskApplicationService.AnnualReportTaskResult taskResult = taskService.createFromAnnualReportBookingIntent(
                report.tenantId(),
                report.customerId(),
                report.ownerStaffId(),
                report.teamId(),
                sourceRef,
                title,
                description,
                now.plus(1, ChronoUnit.DAYS)
        );

        AnnualReportVisitSession updated = new AnnualReportVisitSession(
                session.id(),
                session.tenantId(),
                session.reportId(),
                session.customerId(),
                session.ownerStaffId(),
                session.teamId(),
                session.reportYear(),
                session.sessionKey(),
                session.firstOpenAt(),
                now,
                session.visibleDurationMs(),
                Math.max(session.maxSceneReached(), 5),
                session.viewedSceneCount(),
                session.viewedScenesJson(),
                session.sceneDurationsJson(),
                session.ignitedLamp(),
                session.openedOrderCount(),
                session.toggledPrivacy(),
                session.generatedPoster(),
                true,
                taskResult.taskId(),
                session.clientDevice(),
                session.createdAt(),
                now
        );
        repository.updateSession(updated);

        repository.insertEvent(new AnnualReportVisitEvent(
                null,
                report.tenantId(),
                report.id(),
                session.id(),
                report.customerId(),
                "BOOKING_INTENT",
                5,
                0,
                writeJson(Map.of("createdTask", taskResult.created())),
                now,
                null
        ));

        return new PublicAnnualBookingResponse(
                true,
                !taskResult.created(),
                report.butlerNameSnapshot(),
                taskResult.created()
                        ? "已向专属管家 " + report.butlerNameSnapshot() + " 推送预约提醒，请静候联系确认到院时间。"
                        : "已为您登记预约意向，专属管家 " + report.butlerNameSnapshot() + " 会尽快与您联系。"
        );
    }

    private AnnualReport requireActiveReport(String shareKey) {
        if (shareKey == null || !SHARE_KEY_PATTERN.matcher(shareKey).matches()) {
            throw notFound();
        }
        return repository.findByShareKey(shareKey)
                .filter(item -> "ACTIVE".equals(item.status()))
                .orElseThrow(this::notFound);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "埋点数据序列化失败");
        }
    }

    private <T> T readJson(String raw, TypeReference<T> typeRef, T fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return objectMapper.readValue(raw, typeRef);
        } catch (Exception ex) {
            return fallback;
        }
    }

    private BusinessException notFound() {
        return new BusinessException(ErrorCode.NOT_FOUND, "年度报告链接不存在或已停用");
    }
}
