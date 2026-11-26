package com.whale.crm.annualreport.infrastructure.mapper;

import com.whale.crm.annualreport.domain.YouzanAnnualSnapshotRepository;
import org.apache.ibatis.annotations.Mapper;

/**
 * 有赞历史订单只读快照与 C 端统一自动登录 MyBatis Mapper。
 */
@Mapper
public interface YouzanAnnualSnapshotMapper extends YouzanAnnualSnapshotRepository {
}
