package com.whale.crm.exporting.application;

import com.whale.crm.exporting.domain.ExportColumn;
import com.whale.crm.exporting.domain.ExportType;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class ExportColumnPolicy {
    private static final Map<ExportType, List<ExportColumn>> COLUMNS = Map.of(
            ExportType.CUSTOMER, columns(
                    column("customerNo", "客户编号"), column("displayName", "客户称呼"),
                    column("nickname", "客户昵称"), column("remarkName", "备注名"),
                    column("mobile", "手机号（脱敏）"), column("cityCode", "城市"),
                    column("sourceCode", "客户来源"), column("channelCode", "渠道"),
                    column("customerStatus", "客户状态"), column("tierCode", "客户分层"),
                    column("intentLevel", "意向级别"), column("spendingLevel", "消费能力"),
                    column("birthday", "生日"), column("points", "积分"),
                    column("attentionProjects", "关注项目"), column("blockerNote", "卡点情况"),
                    column("remark", "备注"), column("ownerStaffId", "归属管家ID"),
                    column("ownerName", "归属管家"), column("teamId", "团队ID"),
                    column("firstAddedAt", "首次添加时间"), column("firstOrderAt", "首次下单时间"),
                    column("lastFollowupAt", "最后跟进时间"), column("createdAt", "创建时间")),
            ExportType.ORDER, columns(
                    column("orderNo", "订单编号"), column("customerNo", "客户编号"),
                    column("customerName", "客户称呼"), column("soldByUserId", "成交管家ID"),
                    column("ownerAtSaleId", "销售时归属管家ID"), column("externalOrderNo", "外部订单号"),
                    column("sourceSystem", "订单来源"), column("orderTypeCode", "订单类型"),
                    column("campaignId", "活动ID"), column("campaignBatchId", "批次ID"),
                    column("status", "订单状态"), column("receiverType", "收款方类型"),
                    column("listAmountFen", "原价（分）"), column("discountAmountFen", "优惠（分）"),
                    column("payableFen", "应付（分）"), column("paidFen", "实付（分）"),
                    column("refundedFen", "已退款（分）"), column("orderedAt", "下单时间"),
                    column("paidAt", "支付完成时间"), column("createdAt", "创建时间")),
            ExportType.REDEMPTION, columns(
                    column("redemptionId", "核销ID"), column("appointmentId", "预约ID"),
                    column("customerNo", "客户编号"), column("customerName", "客户称呼"),
                    column("institutionId", "机构ID"), column("ownerStaffId", "归属管家ID"),
                    column("teamId", "团队ID"), column("serviceAt", "服务时间"),
                    column("status", "核销状态"), column("settlementStatus", "结算状态"),
                    column("postedBy", "确认操作员ID"), column("postedAt", "确认时间"),
                    column("createdAt", "创建时间")),
            ExportType.FOLLOWUP, columns(
                    column("followupId", "跟进ID"), column("customerNo", "客户编号"),
                    column("customerName", "客户称呼"), column("ownerStaffId", "管家ID"),
                    column("ownerName", "管家"), column("channelCode", "沟通方式"),
                    column("contactAt", "沟通时间"), column("summary", "沟通摘要"),
                    column("intentProject", "意向项目"), column("estimatedAmountFen", "预计金额（分）"),
                    column("nextFollowupAt", "下次跟进时间"), column("blockerNote", "卡点"),
                    column("createdBy", "登记人ID"), column("createdAt", "创建时间")));

    private static final Map<ExportType, List<String>> DEFAULTS = Map.of(
            ExportType.CUSTOMER, List.of("customerNo", "displayName", "mobile", "customerStatus", "ownerStaffId", "teamId", "createdAt"),
            ExportType.ORDER, List.of("orderNo", "customerNo", "status", "payableFen", "paidFen", "refundedFen", "orderedAt"),
            ExportType.REDEMPTION, List.of("redemptionId", "customerNo", "institutionId", "serviceAt", "status", "postedAt"),
            ExportType.FOLLOWUP, List.of("followupId", "customerNo", "ownerStaffId", "channelCode", "contactAt", "nextFollowupAt"));

    public List<ExportColumn> validate(ExportType type, List<String> requested) {
        List<String> codes = requested == null || requested.isEmpty() ? DEFAULTS.get(type) : requested;
        Map<String, ExportColumn> allowed = new LinkedHashMap<>();
        COLUMNS.get(type).forEach(column -> allowed.put(column.code(), column));
        Set<String> unique = new HashSet<>();
        return codes.stream().map(code -> {
            if (!unique.add(code)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "导出字段不能重复");
            }
            ExportColumn column = allowed.get(code);
            if (column == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "导出字段不在授权白名单内");
            }
            return column;
        }).toList();
    }

    public List<String> codes(List<ExportColumn> columns) {
        return columns.stream().map(ExportColumn::code).toList();
    }

    private static ExportColumn column(String code, String label) {
        return new ExportColumn(code, label);
    }

    private static List<ExportColumn> columns(ExportColumn... values) {
        return List.of(values);
    }
}
