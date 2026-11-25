package com.whale.crm.product.domain;

import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class PackageSelectionRules {
    private static final Comparator<PackageSelectionGroup> GROUP_ORDER = Comparator
            .comparingInt((PackageSelectionGroup group) -> group.sortOrder() == null ? 0 : group.sortOrder().intValue())
            .thenComparingLong(group -> group.id() == null ? 0L : group.id().longValue())
            .thenComparing(PackageSelectionGroup::groupCode);

    private PackageSelectionRules() {
    }

    public static void validateConfiguration(List<PackageSelectionGroup> groups, List<PackageProject> projects) {
        Map<String, PackageSelectionGroup> byCode = new HashMap<>();
        for (PackageSelectionGroup group : groups) {
            if (group.groupCode() == null || group.groupCode().isBlank()
                    || group.minSelections() == null || group.maxSelections() == null
                    || group.minSelections() <= 0 || group.maxSelections() < group.minSelections()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "选项组编码及最少/最多选择数无效");
            }
            if (byCode.putIfAbsent(group.groupCode().toLowerCase(Locale.ROOT), group) != null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "选项组编码不能重复");
            }
        }
        Set<Long> projectIds = new HashSet<>();
        for (PackageProject project : projects) {
            if (!projectIds.add(project.projectId())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "分组套餐中同一子项目不能重复配置");
            }
            PackageSelectionGroup group = byCode.get(project.optionGroup().toLowerCase(Locale.ROOT));
            if (group == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "子项目必须属于已声明的选项组");
            }
            if (project.referencePriceFen() == null || project.referencePriceFen() < 0) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "单品参考价必须为非负整数分");
            }
        }
        for (PackageSelectionGroup group : groups) {
            long optionCount = projects.stream().filter(project -> group.groupCode().equals(project.optionGroup())).count();
            if (optionCount < group.maxSelections()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "选项组 " + group.groupName() + " 至少配置 " + group.maxSelections() + " 个可选子项目");
            }
        }
    }

    public static List<PackageProject> select(String selectionMode, List<PackageSelectionGroup> groups,
                                               List<PackageProject> projects,
                                               List<PackageOptionSelection> selections) {
        if ("USE_N".equals(selectionMode)) {
            throw new BusinessException(ErrorCode.PENDING_BUSINESS_RULE,
                    "任选次数套餐规则尚未确认，暂不支持发放权益",
                    List.of(new ApiResponse.ErrorDetail("Q01", "任选次数共享池规则待运营确认")));
        }
        if ("FIXED".equals(selectionMode)) {
            if (selections != null && !selections.isEmpty()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "固定套餐不能提交自选项目");
            }
            if (projects.stream().anyMatch(project -> !"ACTIVE".equals(project.projectStatus()))) {
                throw new BusinessException(ErrorCode.CONFLICT, "所选子项目未上架或已停用，不能创建订单");
            }
            return List.copyOf(projects);
        }
        if (!"CHOOSE_N".equals(selectionMode)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "未知选择模式");
        }
        if (groups.isEmpty()) {
            throw new BusinessException(ErrorCode.PENDING_BUSINESS_RULE, "自选项目套餐未配置选项组，暂不支持发放权益");
        }
        validateConfiguration(groups, projects);
        List<PackageSelectionGroup> orderedGroups = groups.stream().sorted(GROUP_ORDER).toList();
        Map<String, PackageSelectionGroup> groupByCode = new LinkedHashMap<>();
        Map<String, List<PackageProject>> optionsByGroup = new HashMap<>();
        for (PackageSelectionGroup group : orderedGroups) {
            groupByCode.put(group.groupCode(), group);
            optionsByGroup.put(group.groupCode(), projects.stream()
                    .filter(project -> group.groupCode().equals(project.optionGroup())).toList());
        }
        List<PackageOptionSelection> requested = selections == null ? List.of() : selections;
        Set<String> uniquePairs = new HashSet<>();
        Set<Long> uniqueProjects = new HashSet<>();
        Map<String, Long> selectedProjectByGroup = new HashMap<>();
        for (PackageOptionSelection selection : requested) {
            if (selection == null || selection.groupCode() == null || selection.projectId() == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "套餐自选内容必须包含选项组和子项目");
            }
            PackageSelectionGroup group = groupByCode.get(selection.groupCode());
            if (group == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "所选选项组不属于该套餐版本");
            }
            if (!uniquePairs.add(selection.groupCode() + "|" + selection.projectId())
                    || !uniqueProjects.add(selection.projectId())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "同一订单行不能重复选择子项目");
            }
            PackageProject option = optionsByGroup.get(selection.groupCode()).stream()
                    .filter(project -> project.projectId().equals(selection.projectId())).findFirst()
                    .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED, "所选子项目不属于选项组或不在该套餐中"));
            if (!"ACTIVE".equals(option.projectStatus())) {
                throw new BusinessException(ErrorCode.CONFLICT, "所选子项目未上架或已停用，不能创建订单");
            }
            selectedProjectByGroup.merge(selection.groupCode(), 1L, Long::sum);
        }
        List<PackageProject> result = new ArrayList<>();
        for (PackageSelectionGroup group : orderedGroups) {
            long count = selectedProjectByGroup.getOrDefault(group.groupCode(), 0L);
            if (count < group.minSelections()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "选项组 " + group.groupName() + " 至少选择 " + group.minSelections() + " 项");
            }
            if (count > group.maxSelections()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "选项组 " + group.groupName() + " 最多选择 " + group.maxSelections() + " 项");
            }
            for (PackageProject option : optionsByGroup.get(group.groupCode())) {
                if (uniqueProjects.contains(option.projectId())) {
                    result.add(option);
                }
            }
        }
        return List.copyOf(result);
    }

    public static long referenceAmountFen(List<PackageProject> selectedProjects) {
        long total = 0L;
        for (PackageProject project : selectedProjects) {
            total = Math.addExact(total, Math.multiplyExact(project.referencePriceFen(), project.grantCount().longValue()));
        }
        return total;
    }
}
