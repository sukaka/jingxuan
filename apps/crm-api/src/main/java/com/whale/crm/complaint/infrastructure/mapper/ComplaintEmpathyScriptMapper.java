package com.whale.crm.complaint.infrastructure.mapper;

import com.whale.crm.complaint.domain.ComplaintEmpathyScript;
import com.whale.crm.complaint.domain.ComplaintEmpathyScriptRepository;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface ComplaintEmpathyScriptMapper extends ComplaintEmpathyScriptRepository {
    @Override
    List<ComplaintEmpathyScript> findScripts(@Param("tenantId") long tenantId);

    @Override
    List<ComplaintEmpathyScript> findActiveScripts(@Param("tenantId") long tenantId);

    @Override
    Optional<ComplaintEmpathyScript> findScriptById(@Param("tenantId") long tenantId, @Param("id") long id);

    @Override
    Optional<ComplaintEmpathyScript> findScriptByIdForUpdate(@Param("tenantId") long tenantId, @Param("id") long id);

    @Override
    Optional<ComplaintEmpathyScript> findScriptByTitle(@Param("tenantId") long tenantId,
                                                        @Param("title") String title);

    @Override
    void insertScript(@Param("e") ComplaintEmpathyScript script);

    @Override
    int updateScript(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version,
                     @Param("title") String title, @Param("script") String script);

    @Override
    int disableScript(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version);
}
