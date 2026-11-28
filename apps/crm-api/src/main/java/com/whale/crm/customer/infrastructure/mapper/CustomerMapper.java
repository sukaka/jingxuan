package com.whale.crm.customer.infrastructure.mapper;

import com.whale.crm.customer.domain.Customer;
import com.whale.crm.customer.domain.CustomerListRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface CustomerMapper {

    List<CustomerListRow> search(@Param("tenantId") long tenantId, @Param("scopeType") String scopeType,
                                 @Param("teamIds") List<Long> teamIds, @Param("selfId") long selfId,
                                 @Param("keyword") String keyword, @Param("mobileHashKeyword") String mobileHashKeyword,
                                 @Param("idCardHashKeyword") String idCardHashKeyword,
                                 @Param("emailHashKeyword") String emailHashKeyword,
                                 @Param("wechatIdHashKeyword") String wechatIdHashKeyword,
                                 @Param("status") String status, @Param("cityCode") String cityCode,
                                 @Param("provinceCode") String provinceCode,
                                 @Param("regionCityCode") String regionCityCode,
                                 @Param("districtCode") String districtCode,
                                 @Param("sourceCode") String sourceCode, @Param("channelCode") String channelCode,
                                 @Param("tierCode") String tierCode, @Param("ownerId") Long ownerId,
                                 @Param("followupDueBefore") Instant followupDueBefore,
                                 @Param("tagCodes") List<String> tagCodes, @Param("tagMatchMode") String tagMatchMode,
                                 @Param("offset") int offset, @Param("size") int size);

    long countSearch(@Param("tenantId") long tenantId, @Param("scopeType") String scopeType,
                     @Param("teamIds") List<Long> teamIds, @Param("selfId") long selfId,
                     @Param("keyword") String keyword, @Param("mobileHashKeyword") String mobileHashKeyword,
                     @Param("idCardHashKeyword") String idCardHashKeyword,
                     @Param("emailHashKeyword") String emailHashKeyword,
                     @Param("wechatIdHashKeyword") String wechatIdHashKeyword,
                     @Param("status") String status, @Param("cityCode") String cityCode,
                     @Param("provinceCode") String provinceCode,
                     @Param("regionCityCode") String regionCityCode,
                     @Param("districtCode") String districtCode,
                     @Param("sourceCode") String sourceCode, @Param("channelCode") String channelCode,
                     @Param("tierCode") String tierCode, @Param("ownerId") Long ownerId,
                     @Param("followupDueBefore") Instant followupDueBefore,
                     @Param("tagCodes") List<String> tagCodes, @Param("tagMatchMode") String tagMatchMode);

    Customer selectById(@Param("tenantId") long tenantId, @Param("id") long id);

    Customer selectByIdForUpdate(@Param("tenantId") long tenantId, @Param("id") long id);

    int insertCustomer(Customer customer);

    int backfillCustomerNo(@Param("tenantId") long tenantId, @Param("id") long id,
                           @Param("customerNo") String customerNo);

    int updateProfile(Customer customer);

    int updateOwner(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version,
                    @Param("newOwnerId") long newOwnerId, @Param("newTeamId") Long newTeamId);

    int updateLastFollowupAt(@Param("tenantId") long tenantId, @Param("id") long id,
                             @Param("lastFollowupAt") Instant lastFollowupAt);

    int refreshNextFollowupAt(@Param("tenantId") long tenantId, @Param("id") long id);

    int markFirstOrderIfNull(@Param("tenantId") long tenantId, @Param("id") long id, @Param("paidAt") Instant paidAt);

    int refreshPointsCached(@Param("tenantId") long tenantId, @Param("id") long id,
                            @Param("newBalance") int newBalance);

    int softDelete(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version);

    @org.apache.ibatis.annotations.Update("UPDATE crm_customer SET deleted=0,version=version+1 WHERE tenant_id=#{tenantId} AND id=#{id} AND version=#{version} AND deleted=1")
    int restore(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version);

    List<Long> selectCustomerIdsByMobileHash(@Param("tenantId") long tenantId,
                                             @Param("scopeType") String scopeType,
                                             @Param("teamIds") List<Long> teamIds,
                                             @Param("selfId") long selfId,
                                             @Param("mobileHash") String mobileHash);

    int countByMobileHash(@Param("tenantId") long tenantId, @Param("mobileHash") String mobileHash);

    List<String> selectDuplicatedMobileHashes(@Param("tenantId") long tenantId,
                                              @Param("scopeType") String scopeType,
                                              @Param("teamIds") List<Long> teamIds,
                                              @Param("selfId") long selfId);

    List<Long> selectCustomerIdsByExternalId(@Param("tenantId") long tenantId,
                                             @Param("scopeType") String scopeType,
                                             @Param("teamIds") List<Long> teamIds,
                                             @Param("selfId") long selfId,
                                             @Param("externalId") String externalId);

    List<String> selectDuplicatedExternalIds(@Param("tenantId") long tenantId,
                                             @Param("scopeType") String scopeType,
                                             @Param("teamIds") List<Long> teamIds,
                                             @Param("selfId") long selfId);

    List<Customer> selectActiveByIds(@Param("tenantId") long tenantId, @Param("ids") List<Long> ids);

    List<Customer> selectActiveByMobileHashes(@Param("tenantId") long tenantId,
                                              @Param("mobileHashes") List<String> mobileHashes);

    List<String> selectTenantDuplicatedMobileHashes(@Param("tenantId") long tenantId);

    List<String> selectTenantDuplicatedIdentityExternalIds(@Param("tenantId") long tenantId);
}
