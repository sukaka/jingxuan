package com.whale.crm.customer.application;

import com.whale.crm.customer.api.dto.CustomerStewardRelationItemResponse;
import com.whale.crm.customer.api.dto.CustomerStewardSummaryResponse;
import com.whale.crm.customer.api.dto.MultiStewardCustomerGroupResponse;
import com.whale.crm.customer.api.dto.MultiStewardSummaryResponse;
import com.whale.crm.customer.domain.Customer;
import com.whale.crm.customer.domain.CustomerIdentity;
import com.whale.crm.customer.domain.CustomerIdentityRepository;
import com.whale.crm.customer.domain.CustomerRepository;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.user.domain.UserRepository;
import com.whale.crm.platform.web.PageResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 客户唯一性多管家排查服务（只读，不修改/不合并任何客户档案）。
 * 依据同租户内手机号盲索引（mobile_hash）、外部身份映射（crm_customer_identity）与企微活跃跟进关系
 * （crm_wecom_contact，follow_status='ACTIVE'）做连通分量聚合，计算同一顾客添加了哪几位管家。
 *
 * 权限与跨团队隔离（A2）：
 * - 仅具备 customer:merge 权限的角色（管家主管 / 管理员）可查看。
 * - 管家主管（TEAM 范围）仅可见“至少包含本组客户或本组企微跟进关系”的顾客分组；
 *   对于同顾客添加的跨团队管家，仅返回管家姓名、所属团队与总数汇总（inMyScope=false），
 *   跨组客户 ID/编号/称呼/手机号等敏感明细一律置空，且关键词检索不匹配跨组客户私有字段。
 */
@Service
public class CustomerUniquenessService {

    public static final String PERMISSION_UNIQUENESS_VIEW = "customer:merge";
    public static final String RELATION_CUSTOMER_OWNER = "CUSTOMER_OWNER";
    public static final String RELATION_WECOM_FOLLOW = "WECOM_FOLLOW";
    public static final String REASON_MOBILE_HASH = "MOBILE_HASH";
    public static final String REASON_IDENTITY = "IDENTITY";
    public static final String REASON_WECOM_CONTACT = "WECOM_CONTACT";

    private final CustomerRepository customerRepository;
    private final CustomerIdentityRepository identityRepository;
    private final UserRepository userRepository;
    private final CustomerScopeSupport scopeSupport;
    private final MobileSupport mobileSupport;
    private final CustomerWecomFollowReader wecomFollowReader;

    public CustomerUniquenessService(CustomerRepository customerRepository,
                                     CustomerIdentityRepository identityRepository,
                                     UserRepository userRepository,
                                     CustomerScopeSupport scopeSupport,
                                     MobileSupport mobileSupport) {
        this(customerRepository, identityRepository, userRepository, scopeSupport, mobileSupport,
                CustomerWecomFollowReader.NOOP);
    }

    @Autowired
    public CustomerUniquenessService(CustomerRepository customerRepository,
                                     CustomerIdentityRepository identityRepository,
                                     UserRepository userRepository,
                                     CustomerScopeSupport scopeSupport,
                                     MobileSupport mobileSupport,
                                     CustomerWecomFollowReader wecomFollowReader) {
        this.customerRepository = customerRepository;
        this.identityRepository = identityRepository;
        this.userRepository = userRepository;
        this.scopeSupport = scopeSupport;
        this.mobileSupport = mobileSupport;
        this.wecomFollowReader = wecomFollowReader != null ? wecomFollowReader : CustomerWecomFollowReader.NOOP;
    }

    public boolean canView(CurrentUser user) {
        return user != null && user.permissions().contains(PERMISSION_UNIQUENESS_VIEW);
    }

    @Transactional(readOnly = true)
    public MultiStewardSummaryResponse summary(long tenantId, CurrentUser user) {
        DataScope scope = scopeSupport.scope(user);
        if (!canView(user) || scopeSupport.isEmptyScope(scope)) {
            return new MultiStewardSummaryResponse(0, 0, 0);
        }
        List<ClusterView> clusters = loadCandidateClusters(tenantId, user, scope);
        long multiCount = 0;
        long crossTeamCount = 0;
        int maxStewards = 0;
        for (ClusterView cluster : clusters) {
            if (cluster.response().stewardCount() < 2) {
                continue;
            }
            multiCount++;
            if (cluster.response().crossTeam()) {
                crossTeamCount++;
            }
            if (cluster.response().stewardCount() > maxStewards) {
                maxStewards = cluster.response().stewardCount();
            }
        }
        return new MultiStewardSummaryResponse(multiCount, crossTeamCount, maxStewards);
    }

    @Transactional(readOnly = true)
    public PageResponse<MultiStewardCustomerGroupResponse> listMultiStewardGroups(long tenantId, CurrentUser user,
                                                                                  String keyword, int minStewards,
                                                                                  boolean crossTeamOnly,
                                                                                  int page, int size) {
        DataScope scope = scopeSupport.scope(user);
        if (!canView(user) || scopeSupport.isEmptyScope(scope)) {
            return new PageResponse<>(List.of(), 0, page, size);
        }
        int threshold = Math.max(2, minStewards);
        String trimmedKeyword = keyword == null ? "" : keyword.strip();
        String lowerKeyword = trimmedKeyword.toLowerCase(Locale.ROOT);
        String mobileHashKeyword = !trimmedKeyword.isEmpty() && mobileSupport.looksLikeMobile(trimmedKeyword)
                ? mobileSupport.hash(mobileSupport.normalize(trimmedKeyword)) : null;

        List<MultiStewardCustomerGroupResponse> matched = loadCandidateClusters(tenantId, user, scope).stream()
                .filter(cluster -> cluster.response().stewardCount() >= threshold)
                .filter(cluster -> !crossTeamOnly || cluster.response().crossTeam())
                .filter(cluster -> matchesKeyword(cluster, lowerKeyword, mobileHashKeyword))
                .map(ClusterView::response)
                .sorted(Comparator
                        .comparingInt(MultiStewardCustomerGroupResponse::stewardCount).reversed()
                        .thenComparing(MultiStewardCustomerGroupResponse::latestAddedAt,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(MultiStewardCustomerGroupResponse::groupKey))
                .toList();

        int offset = (page - 1) * size;
        if (offset >= matched.size()) {
            return new PageResponse<>(List.of(), matched.size(), page, size);
        }
        int end = Math.min(offset + size, matched.size());
        return new PageResponse<>(matched.subList(offset, end), matched.size(), page, size);
    }

    @Transactional(readOnly = true)
    public CustomerStewardSummaryResponse customerStewards(long tenantId, CurrentUser user, long customerId) {
        Customer visible = scopeSupport.requireVisible(tenantId, customerId, user);
        Map<Long, CustomerStewardSummaryResponse> summaries = summarizeForCustomers(tenantId, user, List.of(visible));
        CustomerStewardSummaryResponse hit = summaries.get(customerId);
        if (hit != null) {
            return hit;
        }
        return fallbackSingleSteward(tenantId, user, visible);
    }

    /**
     * 批量计算可见客户列表的“已加管家数与明细”。无 customer:merge 权限时直接返回空 Map，不泄露跨管家信息。
     */
    @Transactional(readOnly = true)
    public Map<Long, CustomerStewardSummaryResponse> summarizeForCustomers(long tenantId, CurrentUser user,
                                                                           List<Customer> visibleCustomers) {
        if (!canView(user) || visibleCustomers == null || visibleCustomers.isEmpty()) {
            return Map.of();
        }
        DataScope scope = scopeSupport.scope(user);
        if (scopeSupport.isEmptyScope(scope)) {
            return Map.of();
        }
        GraphData graph = expandFromCustomers(tenantId, visibleCustomers);
        List<ClusterView> clusters = buildClusters(tenantId, user, scope, graph);
        Map<Long, CustomerStewardSummaryResponse> result = new LinkedHashMap<>();
        for (ClusterView cluster : clusters) {
            for (Long customerId : cluster.inScopeCustomerIds()) {
                result.put(customerId, new CustomerStewardSummaryResponse(
                        customerId,
                        cluster.response().stewardCount(),
                        cluster.response().inScopeStewardCount(),
                        cluster.response().crossTeamStewardCount(),
                        cluster.response().matchReasons(),
                        cluster.response().evidenceMasked(),
                        cluster.response().stewards()));
            }
        }
        for (Customer c : visibleCustomers) {
            result.computeIfAbsent(c.getId(), id -> fallbackSingleSteward(tenantId, user, c));
        }
        return result;
    }

    // ========== 图加载与扩展 ==========

    private List<ClusterView> loadCandidateClusters(long tenantId, CurrentUser user, DataScope scope) {
        List<String> dupHashes = customerRepository.findTenantDuplicatedMobileHashes(tenantId);
        List<String> dupExtIds = customerRepository.findTenantDuplicatedIdentityExternalIds(tenantId);
        List<CustomerWecomFollowReader.WecomFollowFact> candidateWecom =
                wecomFollowReader.findCandidateMultiStewardFollows(tenantId);

        if (dupHashes.isEmpty() && dupExtIds.isEmpty() && candidateWecom.isEmpty()) {
            return List.of();
        }

        Map<Long, Customer> customersById = new LinkedHashMap<>();
        Map<Long, CustomerIdentity> identitiesById = new LinkedHashMap<>();
        Map<Long, CustomerWecomFollowReader.WecomFollowFact> wecomById = new LinkedHashMap<>();

        for (Customer c : customerRepository.findActiveByMobileHashes(tenantId, dupHashes)) {
            customersById.put(c.getId(), c);
        }
        List<CustomerIdentity> dupIdentities = identityRepository.findByExternalIds(tenantId, dupExtIds);
        Set<Long> seedCustomerIds = new LinkedHashSet<>();
        for (CustomerIdentity identity : dupIdentities) {
            identitiesById.put(identity.getId(), identity);
            if (identity.getCustomerId() != null) {
                seedCustomerIds.add(identity.getCustomerId());
            }
        }
        for (CustomerWecomFollowReader.WecomFollowFact fact : candidateWecom) {
            wecomById.put(fact.id(), fact);
            if (fact.customerId() != null) {
                seedCustomerIds.add(fact.customerId());
            }
        }
        seedCustomerIds.removeAll(customersById.keySet());
        if (!seedCustomerIds.isEmpty()) {
            for (Customer c : customerRepository.findActiveByIds(tenantId, List.copyOf(seedCustomerIds))) {
                customersById.put(c.getId(), c);
            }
        }

        expandGraph(tenantId, customersById, identitiesById, wecomById, 2);
        return buildClusters(tenantId, user, scope, new GraphData(customersById, identitiesById, wecomById));
    }

    private GraphData expandFromCustomers(long tenantId, List<Customer> seedCustomers) {
        Map<Long, Customer> customersById = new LinkedHashMap<>();
        Map<Long, CustomerIdentity> identitiesById = new LinkedHashMap<>();
        Map<Long, CustomerWecomFollowReader.WecomFollowFact> wecomById = new LinkedHashMap<>();
        for (Customer c : seedCustomers) {
            if (c != null && c.getId() != null && !c.isDeleted()) {
                customersById.put(c.getId(), c);
            }
        }
        expandGraph(tenantId, customersById, identitiesById, wecomById, 2);
        return new GraphData(customersById, identitiesById, wecomById);
    }

    private void expandGraph(long tenantId,
                             Map<Long, Customer> customersById,
                             Map<Long, CustomerIdentity> identitiesById,
                             Map<Long, CustomerWecomFollowReader.WecomFollowFact> wecomById,
                             int maxHops) {
        Set<Long> queriedCustomerIds = new LinkedHashSet<>();
        Set<String> queriedHashes = new LinkedHashSet<>();
        Set<String> queriedIdentityExtIds = new LinkedHashSet<>();
        Set<String> queriedWecomExts = new LinkedHashSet<>();
        Set<String> queriedUnionids = new LinkedHashSet<>();

        for (int hop = 0; hop < maxHops; hop++) {
            boolean progressed = false;

            List<String> newHashes = customersById.values().stream()
                    .map(Customer::getMobileHash)
                    .filter(h -> h != null && !h.isBlank() && queriedHashes.add(h))
                    .toList();
            if (!newHashes.isEmpty()) {
                for (Customer c : customerRepository.findActiveByMobileHashes(tenantId, newHashes)) {
                    if (customersById.putIfAbsent(c.getId(), c) == null) {
                        progressed = true;
                    }
                }
            }

            List<Long> newCustomerIds = customersById.keySet().stream()
                    .filter(queriedCustomerIds::add)
                    .toList();
            if (!newCustomerIds.isEmpty()) {
                for (CustomerIdentity id : identityRepository.findByCustomerIds(tenantId, newCustomerIds)) {
                    if (identitiesById.putIfAbsent(id.getId(), id) == null) {
                        progressed = true;
                    }
                }
            }

            List<String> newIdentityExts = identitiesById.values().stream()
                    .map(CustomerIdentity::getExternalId)
                    .filter(e -> e != null && !e.isBlank() && queriedIdentityExtIds.add(e))
                    .toList();
            if (!newIdentityExts.isEmpty()) {
                for (CustomerIdentity id : identityRepository.findByExternalIds(tenantId, newIdentityExts)) {
                    if (identitiesById.putIfAbsent(id.getId(), id) == null) {
                        progressed = true;
                    }
                }
            }

            Set<String> wecomExtToQuery = new LinkedHashSet<>();
            for (CustomerWecomFollowReader.WecomFollowFact fact : wecomById.values()) {
                if (fact.externalUserid() != null && !fact.externalUserid().isBlank()
                        && queriedWecomExts.add(fact.externalUserid())) {
                    wecomExtToQuery.add(fact.externalUserid());
                }
            }
            for (CustomerIdentity id : identitiesById.values()) {
                if ("WECOM".equalsIgnoreCase(id.getIdentityType())
                        && id.getExternalId() != null && !id.getExternalId().isBlank()
                        && queriedWecomExts.add(id.getExternalId())) {
                    wecomExtToQuery.add(id.getExternalId());
                }
            }
            List<String> unionidsToQuery = wecomById.values().stream()
                    .map(CustomerWecomFollowReader.WecomFollowFact::unionid)
                    .filter(u -> u != null && !u.isBlank() && queriedUnionids.add(u))
                    .toList();

            if (!newCustomerIds.isEmpty() || !wecomExtToQuery.isEmpty() || !unionidsToQuery.isEmpty()) {
                List<CustomerWecomFollowReader.WecomFollowFact> found = wecomFollowReader
                        .findActiveByCustomersOrKeys(tenantId, newCustomerIds, wecomExtToQuery, unionidsToQuery);
                for (CustomerWecomFollowReader.WecomFollowFact fact : found) {
                    if (wecomById.putIfAbsent(fact.id(), fact) == null) {
                        progressed = true;
                    }
                }
            }

            Set<Long> missingCustomerIds = new LinkedHashSet<>();
            for (CustomerIdentity id : identitiesById.values()) {
                if (id.getCustomerId() != null && !customersById.containsKey(id.getCustomerId())) {
                    missingCustomerIds.add(id.getCustomerId());
                }
            }
            for (CustomerWecomFollowReader.WecomFollowFact fact : wecomById.values()) {
                if (fact.customerId() != null && !customersById.containsKey(fact.customerId())) {
                    missingCustomerIds.add(fact.customerId());
                }
            }
            if (!missingCustomerIds.isEmpty()) {
                for (Customer c : customerRepository.findActiveByIds(tenantId, List.copyOf(missingCustomerIds))) {
                    if (customersById.putIfAbsent(c.getId(), c) == null) {
                        progressed = true;
                    }
                }
            }

            if (!progressed) {
                break;
            }
        }
    }

    // ========== 连通分量聚合与数据范围脱敏 ==========

    private List<ClusterView> buildClusters(long tenantId, CurrentUser user, DataScope scope, GraphData graph) {
        UnionFind uf = new UnionFind();

        for (Customer c : graph.customersById().values()) {
            String cToken = customerToken(c.getId());
            uf.add(cToken);
            if (c.getMobileHash() != null && !c.getMobileHash().isBlank()) {
                uf.union(cToken, "M:" + c.getMobileHash());
            }
        }
        Set<String> wecomIdentityExternalIds = new LinkedHashSet<>();
        for (CustomerIdentity id : graph.identitiesById().values()) {
            if (id.getCustomerId() == null || !graph.customersById().containsKey(id.getCustomerId())) {
                continue;
            }
            if (id.getExternalId() != null && !id.getExternalId().isBlank()) {
                String cToken = customerToken(id.getCustomerId());
                uf.union(cToken, "I:" + id.getExternalId());
                if ("WECOM".equalsIgnoreCase(id.getIdentityType())) {
                    wecomIdentityExternalIds.add(id.getExternalId());
                    if (id.getSourceApp() != null && !id.getSourceApp().isBlank()) {
                        uf.union(cToken, "W:" + id.getSourceApp() + "|" + id.getExternalId());
                    }
                }
            }
        }
        for (CustomerWecomFollowReader.WecomFollowFact fact : graph.wecomById().values()) {
            String wToken = wecomToken(fact);
            uf.add(wToken);
            if (fact.externalUserid() != null && wecomIdentityExternalIds.contains(fact.externalUserid())) {
                uf.union(wToken, "I:" + fact.externalUserid());
            }
            if (fact.unionid() != null && !fact.unionid().isBlank()) {
                uf.union(wToken, "U:" + fact.unionid());
            }
            if (fact.customerId() != null && graph.customersById().containsKey(fact.customerId())) {
                uf.union(wToken, customerToken(fact.customerId()));
            }
        }

        Map<String, ClusterAccumulator> accumulators = new LinkedHashMap<>();
        for (Customer c : graph.customersById().values()) {
            String root = uf.find(customerToken(c.getId()));
            accumulators.computeIfAbsent(root, k -> new ClusterAccumulator()).customers.add(c);
        }
        for (CustomerIdentity id : graph.identitiesById().values()) {
            if (id.getCustomerId() == null || !graph.customersById().containsKey(id.getCustomerId())) {
                continue;
            }
            String root = uf.find(customerToken(id.getCustomerId()));
            ClusterAccumulator acc = accumulators.get(root);
            if (acc != null) {
                acc.identities.add(id);
            }
        }
        for (CustomerWecomFollowReader.WecomFollowFact fact : graph.wecomById().values()) {
            String root = uf.find(wecomToken(fact));
            accumulators.computeIfAbsent(root, k -> new ClusterAccumulator()).wecomFollows.add(fact);
        }

        Set<Long> allStaffIds = new LinkedHashSet<>();
        for (Customer c : graph.customersById().values()) {
            if (c.getOwnerStaffId() != null) {
                allStaffIds.add(c.getOwnerStaffId());
            }
        }
        for (CustomerWecomFollowReader.WecomFollowFact fact : graph.wecomById().values()) {
            if (fact.staffUserId() != null) {
                allStaffIds.add(fact.staffUserId());
            }
        }
        Map<Long, UserRepository.StewardOptionRow> staffProfiles = loadStaffProfiles(tenantId, allStaffIds);

        List<ClusterView> views = new ArrayList<>();
        for (ClusterAccumulator acc : accumulators.values()) {
            ClusterView view = toClusterView(user, scope, acc, staffProfiles);
            if (view != null) {
                views.add(view);
            }
        }
        return views;
    }

    private ClusterView toClusterView(CurrentUser user, DataScope scope, ClusterAccumulator acc,
                                      Map<Long, UserRepository.StewardOptionRow> staffProfiles) {
        List<Customer> inScopeCustomers = acc.customers.stream()
                .filter(c -> scopeSupport.isVisible(scope, user, c))
                .sorted(Comparator.comparing(Customer::getId))
                .toList();
        List<CustomerWecomFollowReader.WecomFollowFact> inScopeWecom = acc.wecomFollows.stream()
                .filter(w -> isStaffInScope(scope, user, w.staffUserId(), staffProfiles.get(w.staffUserId())))
                .toList();

        // 若该簇与当前用户数据范围无任何交集，彻底不可见（防跨组泄漏）
        if (inScopeCustomers.isEmpty() && inScopeWecom.isEmpty()) {
            return null;
        }

        Map<Long, StewardBucket> buckets = new LinkedHashMap<>();
        for (Customer c : acc.customers) {
            if (c.getOwnerStaffId() == null) {
                continue;
            }
            StewardBucket bucket = buckets.computeIfAbsent(c.getOwnerStaffId(), id -> new StewardBucket(id));
            bucket.relationTypes.add(RELATION_CUSTOMER_OWNER);
            boolean visible = scopeSupport.isVisible(scope, user, c);
            if (visible) {
                bucket.inMyScope = true;
                if (bucket.visibleCustomer == null || c.getId() < bucket.visibleCustomer.getId()) {
                    bucket.visibleCustomer = c;
                }
            }
            bucket.recordAddedAt(c.getFirstAddedAt() != null ? c.getFirstAddedAt() : c.getCreatedAt());
        }
        for (CustomerWecomFollowReader.WecomFollowFact fact : acc.wecomFollows) {
            if (fact.staffUserId() == null) {
                continue;
            }
            StewardBucket bucket = buckets.computeIfAbsent(fact.staffUserId(), id -> new StewardBucket(id));
            bucket.relationTypes.add(RELATION_WECOM_FOLLOW);
            if (isStaffInScope(scope, user, fact.staffUserId(), staffProfiles.get(fact.staffUserId()))) {
                bucket.inMyScope = true;
            }
            bucket.recordAddedAt(fact.followCreatedAt() != null ? fact.followCreatedAt() : fact.lastSyncedAt());
        }

        List<CustomerStewardRelationItemResponse> stewards = new ArrayList<>();
        int inScopeCount = 0;
        int crossTeamCount = 0;
        Set<Long> distinctTeams = new LinkedHashSet<>();
        boolean hasNullTeam = false;
        Instant latestAddedAt = null;

        for (StewardBucket bucket : buckets.values()) {
            UserRepository.StewardOptionRow profile = staffProfiles.get(bucket.staffUserId);
            String staffName = profile != null && profile.displayName() != null && !profile.displayName().isBlank()
                    ? profile.displayName()
                    : "员工 #" + bucket.staffUserId;
            Long teamId = profile != null ? profile.teamId()
                    : (bucket.visibleCustomer != null ? bucket.visibleCustomer.getTeamId() : null);
            String teamName = profile != null ? profile.teamName() : null;
            boolean inMyScope = bucket.inMyScope || isStaffInScope(scope, user, bucket.staffUserId, profile);
            if (inMyScope) {
                inScopeCount++;
            } else {
                crossTeamCount++;
            }
            if (teamId != null) {
                distinctTeams.add(teamId);
            } else {
                hasNullTeam = true;
            }
            if (bucket.addedAt != null && (latestAddedAt == null || bucket.addedAt.isAfter(latestAddedAt))) {
                latestAddedAt = bucket.addedAt;
            }
            // A2 硬约束：跨组管家不返回跨组客户 ID/编号/称呼
            Customer visibleCustomer = inMyScope ? bucket.visibleCustomer : null;
            Long visibleCustomerId = visibleCustomer != null ? visibleCustomer.getId() : null;
            String visibleCustomerNo = visibleCustomer != null ? visibleCustomer.getCustomerNo() : null;
            String visibleCustomerName = visibleCustomer != null ? displayLabelOf(visibleCustomer) : null;

            stewards.add(new CustomerStewardRelationItemResponse(
                    bucket.staffUserId,
                    staffName,
                    teamId,
                    teamName,
                    inMyScope,
                    visibleCustomerId,
                    visibleCustomerNo,
                    visibleCustomerName,
                    List.copyOf(bucket.relationTypes),
                    bucket.addedAt));
        }
        stewards.sort(Comparator
                .comparing(CustomerStewardRelationItemResponse::inMyScope).reversed()
                .thenComparing(CustomerStewardRelationItemResponse::addedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(CustomerStewardRelationItemResponse::staffUserId));

        Customer primary = inScopeCustomers.isEmpty() ? null : inScopeCustomers.getFirst();
        String displayName = primary != null ? primary.getDisplayName() : firstWecomContactName(inScopeWecom);
        String nickname = primary != null ? primary.getNickname() : null;
        String mobileMasked = primary != null ? mobileSupport.maskFromCipher(primary.getMobileCipher()) : null;

        List<String> matchReasons = resolveMatchReasons(acc);
        String evidenceMasked = resolveEvidenceMasked(inScopeCustomers, acc, mobileMasked);
        boolean crossTeam = crossTeamCount > 0
                || distinctTeams.size() > 1
                || (distinctTeams.size() == 1 && hasNullTeam && stewards.size() > 1);
        String groupKey = buildGroupKey(acc);

        MultiStewardCustomerGroupResponse response = new MultiStewardCustomerGroupResponse(
                groupKey,
                primary != null ? primary.getId() : null,
                primary != null ? primary.getCustomerNo() : null,
                displayName,
                nickname,
                mobileMasked,
                stewards.size(),
                inScopeCount,
                crossTeamCount,
                crossTeam,
                matchReasons,
                evidenceMasked,
                latestAddedAt,
                stewards);

        Set<Long> inScopeCustomerIds = new LinkedHashSet<>();
        Set<String> inScopeMobileHashes = new LinkedHashSet<>();
        for (Customer c : inScopeCustomers) {
            inScopeCustomerIds.add(c.getId());
            if (c.getMobileHash() != null && !c.getMobileHash().isBlank()) {
                inScopeMobileHashes.add(c.getMobileHash());
            }
        }
        return new ClusterView(response, inScopeCustomers, inScopeCustomerIds, inScopeMobileHashes);
    }

    private List<String> resolveMatchReasons(ClusterAccumulator acc) {
        LinkedHashSet<String> reasons = new LinkedHashSet<>();
        Map<String, Set<Long>> ownersByHash = new HashMap<>();
        for (Customer c : acc.customers) {
            if (c.getMobileHash() != null && !c.getMobileHash().isBlank() && c.getOwnerStaffId() != null) {
                ownersByHash.computeIfAbsent(c.getMobileHash(), k -> new LinkedHashSet<>()).add(c.getOwnerStaffId());
            }
        }
        if (ownersByHash.values().stream().anyMatch(set -> set.size() > 1)) {
            reasons.add(REASON_MOBILE_HASH);
        }

        Map<String, Set<Long>> customersByExt = new HashMap<>();
        for (CustomerIdentity id : acc.identities) {
            if (id.getExternalId() != null && !id.getExternalId().isBlank() && id.getCustomerId() != null) {
                customersByExt.computeIfAbsent(id.getExternalId(), k -> new LinkedHashSet<>()).add(id.getCustomerId());
            }
        }
        if (customersByExt.values().stream().anyMatch(set -> set.size() > 1)) {
            reasons.add(REASON_IDENTITY);
        }

        Set<Long> wecomStaffs = new LinkedHashSet<>();
        Set<Long> customerOwners = new LinkedHashSet<>();
        for (Customer c : acc.customers) {
            if (c.getOwnerStaffId() != null) {
                customerOwners.add(c.getOwnerStaffId());
            }
        }
        for (CustomerWecomFollowReader.WecomFollowFact fact : acc.wecomFollows) {
            if (fact.staffUserId() != null) {
                wecomStaffs.add(fact.staffUserId());
            }
        }
        if (wecomStaffs.size() > 1 || (!wecomStaffs.isEmpty() && !customerOwners.containsAll(wecomStaffs))) {
            reasons.add(REASON_WECOM_CONTACT);
        }
        return List.copyOf(reasons);
    }

    private String resolveEvidenceMasked(List<Customer> inScopeCustomers, ClusterAccumulator acc,
                                         String primaryMobileMasked) {
        if (primaryMobileMasked != null && !primaryMobileMasked.isBlank()) {
            return primaryMobileMasked;
        }
        for (Customer c : inScopeCustomers) {
            String masked = mobileSupport.maskFromCipher(c.getMobileCipher());
            if (masked != null && !masked.isBlank()) {
                return masked;
            }
        }
        Set<Long> visibleIds = new LinkedHashSet<>();
        for (Customer c : inScopeCustomers) {
            visibleIds.add(c.getId());
        }
        for (CustomerIdentity id : acc.identities) {
            if (visibleIds.contains(id.getCustomerId()) && id.getExternalId() != null && !id.getExternalId().isBlank()) {
                return CustomerApplicationService.maskExternalId(id.getExternalId());
            }
        }
        for (CustomerWecomFollowReader.WecomFollowFact fact : acc.wecomFollows) {
            if (fact.externalUserid() != null && !fact.externalUserid().isBlank()) {
                return CustomerApplicationService.maskExternalId(fact.externalUserid());
            }
        }
        return null;
    }

    private boolean matchesKeyword(ClusterView cluster, String lowerKeyword, String mobileHashKeyword) {
        if (lowerKeyword.isEmpty()) {
            return true;
        }
        if (mobileHashKeyword != null && cluster.inScopeMobileHashes().contains(mobileHashKeyword)) {
            return true;
        }
        for (Customer c : cluster.inScopeCustomers()) {
            if (containsIgnoreCase(c.getCustomerNo(), lowerKeyword)
                    || containsIgnoreCase(c.getDisplayName(), lowerKeyword)
                    || containsIgnoreCase(c.getNickname(), lowerKeyword)
                    || containsIgnoreCase(c.getRemarkName(), lowerKeyword)) {
                return true;
            }
        }
        if (containsIgnoreCase(cluster.response().displayName(), lowerKeyword)
                || containsIgnoreCase(cluster.response().nickname(), lowerKeyword)) {
            return true;
        }
        for (CustomerStewardRelationItemResponse steward : cluster.response().stewards()) {
            if (containsIgnoreCase(steward.staffName(), lowerKeyword)
                    || containsIgnoreCase(steward.teamName(), lowerKeyword)) {
                return true;
            }
        }
        return false;
    }

    private CustomerStewardSummaryResponse fallbackSingleSteward(long tenantId, CurrentUser user, Customer customer) {
        Map<Long, UserRepository.StewardOptionRow> profiles = customer.getOwnerStaffId() == null
                ? Map.of()
                : loadStaffProfiles(tenantId, List.of(customer.getOwnerStaffId()));
        UserRepository.StewardOptionRow profile = profiles.get(customer.getOwnerStaffId());
        String staffName = profile != null && profile.displayName() != null && !profile.displayName().isBlank()
                ? profile.displayName()
                : "员工 #" + customer.getOwnerStaffId();
        Long teamId = profile != null ? profile.teamId() : customer.getTeamId();
        String teamName = profile != null ? profile.teamName() : null;
        Instant addedAt = customer.getFirstAddedAt() != null ? customer.getFirstAddedAt() : customer.getCreatedAt();
        CustomerStewardRelationItemResponse item = new CustomerStewardRelationItemResponse(
                customer.getOwnerStaffId(), staffName, teamId, teamName, true,
                customer.getId(), customer.getCustomerNo(), displayLabelOf(customer),
                List.of(RELATION_CUSTOMER_OWNER), addedAt);
        return new CustomerStewardSummaryResponse(customer.getId(), 1, 1, 0, List.of(),
                mobileSupport.maskFromCipher(customer.getMobileCipher()), List.of(item));
    }

    private Map<Long, UserRepository.StewardOptionRow> loadStaffProfiles(long tenantId, Collection<Long> staffIds) {
        if (staffIds == null || staffIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = staffIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, UserRepository.StewardOptionRow> map = new LinkedHashMap<>();
        for (UserRepository.StewardOptionRow row : userRepository.findStaffProfilesByIds(tenantId, ids)) {
            map.put(row.id(), row);
        }
        return map;
    }

    private boolean isStaffInScope(DataScope scope, CurrentUser user, Long staffUserId,
                                   UserRepository.StewardOptionRow profile) {
        if (staffUserId == null) {
            return false;
        }
        return switch (scope.type()) {
            case ALL -> true;
            case TEAM -> staffUserId == user.userId()
                    || (profile != null && profile.teamId() != null && scope.teamIds().contains(profile.teamId()));
            case SELF -> staffUserId == user.userId();
            default -> false;
        };
    }

    private static String firstWecomContactName(List<CustomerWecomFollowReader.WecomFollowFact> facts) {
        for (CustomerWecomFollowReader.WecomFollowFact fact : facts) {
            if (fact.contactName() != null && !fact.contactName().isBlank()) {
                return fact.contactName();
            }
        }
        return null;
    }

    private static String displayLabelOf(Customer customer) {
        if (customer.getDisplayName() != null && !customer.getDisplayName().isBlank()) {
            return customer.getDisplayName();
        }
        if (customer.getNickname() != null && !customer.getNickname().isBlank()) {
            return customer.getNickname();
        }
        return customer.getCustomerNo();
    }

    private static boolean containsIgnoreCase(String value, String lowerKeyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(lowerKeyword);
    }

    private static String customerToken(long customerId) {
        return "C:" + customerId;
    }

    private static String wecomToken(CustomerWecomFollowReader.WecomFollowFact fact) {
        return "W:" + fact.corpId() + "|" + fact.externalUserid();
    }

    private static String buildGroupKey(ClusterAccumulator acc) {
        List<String> seeds = new ArrayList<>();
        acc.customers.stream().map(Customer::getId).sorted().forEach(id -> seeds.add("C" + id));
        acc.wecomFollows.stream().map(CustomerWecomFollowReader.WecomFollowFact::id).sorted()
                .forEach(id -> seeds.add("W" + id));
        String canonical = String.join(",", seeds);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return "G-" + HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException ex) {
            return "G-" + Integer.toHexString(canonical.hashCode());
        }
    }

    private record GraphData(
            Map<Long, Customer> customersById,
            Map<Long, CustomerIdentity> identitiesById,
            Map<Long, CustomerWecomFollowReader.WecomFollowFact> wecomById) {
    }

    private record ClusterView(
            MultiStewardCustomerGroupResponse response,
            List<Customer> inScopeCustomers,
            Set<Long> inScopeCustomerIds,
            Set<String> inScopeMobileHashes) {
    }

    private static final class ClusterAccumulator {
        private final List<Customer> customers = new ArrayList<>();
        private final List<CustomerIdentity> identities = new ArrayList<>();
        private final List<CustomerWecomFollowReader.WecomFollowFact> wecomFollows = new ArrayList<>();
    }

    private static final class StewardBucket {
        private final long staffUserId;
        private final Set<String> relationTypes = new LinkedHashSet<>();
        private boolean inMyScope;
        private Customer visibleCustomer;
        private Instant addedAt;

        private StewardBucket(long staffUserId) {
            this.staffUserId = staffUserId;
        }

        private void recordAddedAt(Instant candidate) {
            if (candidate == null) {
                return;
            }
            if (addedAt == null || candidate.isBefore(addedAt)) {
                addedAt = candidate;
            }
        }
    }

    private static final class UnionFind {
        private final Map<String, String> parent = new HashMap<>();

        private void add(String x) {
            parent.putIfAbsent(x, x);
        }

        private String find(String x) {
            parent.putIfAbsent(x, x);
            String root = x;
            while (!root.equals(parent.get(root))) {
                root = parent.get(root);
            }
            String cur = x;
            while (!cur.equals(root)) {
                String next = parent.get(cur);
                parent.put(cur, root);
                cur = next;
            }
            return root;
        }

        private void union(String a, String b) {
            String ra = find(a);
            String rb = find(b);
            if (!ra.equals(rb)) {
                if (ra.compareTo(rb) < 0) {
                    parent.put(rb, ra);
                } else {
                    parent.put(ra, rb);
                }
            }
        }
    }
}
