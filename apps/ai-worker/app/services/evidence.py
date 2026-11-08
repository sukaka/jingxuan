from app.schemas import ExtractionRequest, Insight


def validate_evidence(insight: Insight, request: ExtractionRequest) -> Insight:
    message_ids = {message.id for message in request.messages}
    if not set(insight.evidence_message_ids).issubset(message_ids):
        raise ValueError("证据消息 ID 不属于本次输入")
    project_codes = {project.code for project in request.projects}
    if not set(insight.project_codes).issubset(project_codes):
        raise ValueError("项目编码不属于本次授权字典")
    institutions = {institution.name for institution in request.institutions}
    if insight.mentioned_institution is not None and insight.mentioned_institution not in institutions:
        raise ValueError("机构不属于本次授权字典")
    if insight.customer_id != request.customer_id:
        raise ValueError("输出客户与任务客户不一致")
    return insight
