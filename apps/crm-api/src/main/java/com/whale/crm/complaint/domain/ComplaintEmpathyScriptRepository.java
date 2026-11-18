package com.whale.crm.complaint.domain;

import java.util.List;
import java.util.Optional;

public interface ComplaintEmpathyScriptRepository {
    List<ComplaintEmpathyScript> findScripts(long tenantId);

    List<ComplaintEmpathyScript> findActiveScripts(long tenantId);

    Optional<ComplaintEmpathyScript> findScriptById(long tenantId, long id);

    Optional<ComplaintEmpathyScript> findScriptByIdForUpdate(long tenantId, long id);

    Optional<ComplaintEmpathyScript> findScriptByTitle(long tenantId, String title);

    void insertScript(ComplaintEmpathyScript script);

    int updateScript(long tenantId, long id, long version, String title, String script);

    int disableScript(long tenantId, long id, long version);
}
