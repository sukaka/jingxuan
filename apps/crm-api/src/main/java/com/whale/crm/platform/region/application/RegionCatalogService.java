package com.whale.crm.platform.region.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.platform.dictionary.api.dto.DictionaryOptionResponse;
import com.whale.crm.platform.region.api.dto.RegionNodeResponse;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 全国标准行政区划目录服务（单源真相：classpath:region/cn-regions.json，涵盖 34 省级行政区及下辖市/区县）。
 * 职责：
 * 1. 向前端级联选择器提供只读省/市/区树；
 * 2. 校验客户建档/编辑/筛选传入的省/市/区编码合法性与父子层级一致性；
 * 3. 与现有 cities 字典做双向兼容映射（省市区 -> city_code 同步；历史 city_code -> 省市补全）；
 * 4. 为有赞客户同步提供省/市/区名称标准化解析。
 */
@Service
public class RegionCatalogService {

    private static final String RESOURCE_PATH = "/region/cn-regions.json";
    private static final Set<String> MUNICIPALITY_CODES = Set.of("110000", "120000", "310000", "500000");
    private static final Set<String> SAR_CODES = Set.of("810000", "820000");
    private static final Set<String> GENERIC_CITY_PLACEHOLDERS = Set.of(
            "市辖区", "县", "省直辖县级行政区划", "自治区直辖县级行政区划", "直辖市");
    private static final List<RawRegionNode> CACHED_RAW_TREE = loadRawTree(new ObjectMapper());

    private final List<RegionNodeResponse> tree;
    private final Map<String, RegionMeta> byCode;
    private final Map<String, List<RegionMeta>> childrenByParent;
    private final List<RegionMeta> provinces;
    private final Map<String, RegionMeta> provinceByExactName;
    private final Map<String, RegionMeta> provinceByNormName;
    private final Map<String, List<RegionMeta>> citiesByNormName;

    @Autowired
    public RegionCatalogService() {
        List<RawRegionNode> rawTree = CACHED_RAW_TREE;
        List<RegionNodeResponse> builtTree = new ArrayList<>(rawTree.size());
        Map<String, RegionMeta> codeIndex = new LinkedHashMap<>();
        Map<String, List<RegionMeta>> parentChildren = new HashMap<>();
        List<RegionMeta> provinceList = new ArrayList<>();
        Map<String, RegionMeta> exactProvinces = new HashMap<>();
        Map<String, RegionMeta> normProvinces = new HashMap<>();
        Map<String, List<RegionMeta>> normCities = new HashMap<>();

        for (RawRegionNode rawProvince : rawTree) {
            RegionMeta provinceMeta = new RegionMeta(rawProvince.code(), rawProvince.name(), 1, null, null);
            codeIndex.put(provinceMeta.code(), provinceMeta);
            provinceList.add(provinceMeta);
            exactProvinces.put(provinceMeta.name(), provinceMeta);
            normProvinces.put(normalizeProvinceName(provinceMeta.name()), provinceMeta);

            List<RawRegionNode> rawCities = rawProvince.children() == null ? List.of() : rawProvince.children();
            List<RegionNodeResponse> cityNodes = new ArrayList<>(rawCities.size());
            List<RegionMeta> cityMetas = new ArrayList<>(rawCities.size());

            for (RawRegionNode rawCity : rawCities) {
                RegionMeta cityMeta = new RegionMeta(
                        rawCity.code(), rawCity.name(), 2, provinceMeta.code(), null);
                codeIndex.put(cityMeta.code(), cityMeta);
                cityMetas.add(cityMeta);
                if (!GENERIC_CITY_PLACEHOLDERS.contains(cityMeta.name())) {
                    normCities.computeIfAbsent(normalizeCityName(cityMeta.name()), k -> new ArrayList<>())
                            .add(cityMeta);
                }

                List<RawRegionNode> rawDistricts = rawCity.children() == null ? List.of() : rawCity.children();
                List<RegionNodeResponse> districtNodes = new ArrayList<>(rawDistricts.size());
                List<RegionMeta> districtMetas = new ArrayList<>(rawDistricts.size());

                for (RawRegionNode rawDistrict : rawDistricts) {
                    RegionMeta districtMeta = new RegionMeta(
                            rawDistrict.code(), rawDistrict.name(), 3, cityMeta.code(), provinceMeta.code());
                    codeIndex.put(districtMeta.code(), districtMeta);
                    districtMetas.add(districtMeta);
                    districtNodes.add(new RegionNodeResponse(districtMeta.code(), districtMeta.name(), 3, List.of()));
                }
                parentChildren.put(cityMeta.code(), List.copyOf(districtMetas));
                cityNodes.add(new RegionNodeResponse(cityMeta.code(), cityMeta.name(), 2, districtNodes));
            }
            parentChildren.put(provinceMeta.code(), List.copyOf(cityMetas));
            builtTree.add(new RegionNodeResponse(provinceMeta.code(), provinceMeta.name(), 1, cityNodes));
        }

        this.tree = List.copyOf(builtTree);
        this.byCode = Map.copyOf(codeIndex);
        this.childrenByParent = Map.copyOf(parentChildren);
        this.provinces = List.copyOf(provinceList);
        this.provinceByExactName = Map.copyOf(exactProvinces);
        this.provinceByNormName = Map.copyOf(normProvinces);
        Map<String, List<RegionMeta>> immutableCities = new HashMap<>();
        for (Map.Entry<String, List<RegionMeta>> entry : normCities.entrySet()) {
            immutableCities.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        this.citiesByNormName = Map.copyOf(immutableCities);
    }

    public List<RegionNodeResponse> tree() {
        return List.copyOf(tree);
    }

    /**
     * 校验省/市/区编码及父子层级关系，支持只选省、省+市、省+市+区或全空。
     */
    public ResolvedRegion resolveAndValidate(String rawProvinceCode, String rawRegionCityCode,
                                             String rawDistrictCode) {
        String provinceCode = blankToNull(rawProvinceCode);
        String cityCode = blankToNull(rawRegionCityCode);
        String districtCode = blankToNull(rawDistrictCode);
        if (provinceCode == null && cityCode == null && districtCode == null) {
            return ResolvedRegion.EMPTY;
        }
        if (districtCode != null) {
            RegionMeta district = byCode.get(districtCode);
            if (district == null || district.level() != 3) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "所选区县行政区划编码无效");
            }
            RegionMeta city = byCode.get(district.parentCode());
            RegionMeta province = byCode.get(district.grandparentCode());
            if (city == null || province == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "所选省市区行政区划层级异常");
            }
            if (cityCode != null && !cityCode.equals(city.code())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "所选区县与城市行政区划不匹配");
            }
            if (provinceCode != null && !provinceCode.equals(province.code())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "所选区县与省份行政区划不匹配");
            }
            return new ResolvedRegion(province.code(), province.name(),
                    city.code(), city.name(), district.code(), district.name());
        }
        if (cityCode != null) {
            RegionMeta city = byCode.get(cityCode);
            if (city == null || city.level() != 2) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "所选城市行政区划编码无效");
            }
            RegionMeta province = byCode.get(city.parentCode());
            if (province == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "所选省市行政区划层级异常");
            }
            if (provinceCode != null && !provinceCode.equals(province.code())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "所选城市与省份行政区划不匹配");
            }
            return new ResolvedRegion(province.code(), province.name(), city.code(), city.name(), null, null);
        }
        RegionMeta province = byCode.get(provinceCode);
        if (province == null || province.level() != 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "所选省份行政区划编码无效");
        }
        return new ResolvedRegion(province.code(), province.name(), null, null, null, null);
    }

    /**
     * 按中文省/市/区名称解析标准行政区划（用于有赞客户拉取与历史城市字典补全）。
     * 无法命中时返回 ResolvedRegion.EMPTY，绝不写入脏编码。
     */
    public ResolvedRegion resolveByNames(String rawProvince, String rawCity, String rawDistrict) {
        String provInput = cleanInput(rawProvince);
        String cityInput = cleanInput(rawCity);
        String distInput = cleanInput(rawDistrict);
        if (provInput == null && cityInput == null && distInput == null) {
            return ResolvedRegion.EMPTY;
        }

        // 复合单串场景（如仅传入 "浙江省杭州市西湖区" 或 "浙江省 杭州市"）
        if (provInput == null && distInput == null && cityInput != null) {
            ResolvedRegion composite = tryResolveCompositeText(cityInput);
            if (!composite.isEmpty()) {
                return composite;
            }
        }

        RegionMeta province = matchProvince(provInput);
        RegionMeta city = null;
        RegionMeta district = null;

        // 1. 直辖市：若省份命中直辖市，自动定位市级节点，同时兼容部分来源把区名填在 city 字段
        if (province != null && MUNICIPALITY_CODES.contains(province.code())) {
            List<RegionMeta> mCities = childrenByParent.getOrDefault(province.code(), List.of());
            if (!mCities.isEmpty()) {
                city = mCities.get(0);
            }
            if (city != null) {
                if (distInput != null) {
                    district = matchChildByName(city.code(), distInput, true);
                }
                if (district == null && cityInput != null && !isPlaceholderCity(cityInput)
                        && !normalizeProvinceName(cityInput).equals(normalizeProvinceName(province.name()))) {
                    district = matchChildByName(city.code(), cityInput, true);
                }
            }
            return toResolved(province, city, district);
        }

        // 2. 港澳特别行政区
        if (province != null && SAR_CODES.contains(province.code())) {
            if (cityInput != null && !isPlaceholderCity(cityInput)) {
                city = matchChildByName(province.code(), cityInput, false);
            }
            if (city != null && distInput != null) {
                district = matchChildByName(city.code(), distInput, true);
            } else if (city == null && (distInput != null || cityInput != null)) {
                String candidateDist = distInput != null ? distInput : cityInput;
                for (RegionMeta area : childrenByParent.getOrDefault(province.code(), List.of())) {
                    RegionMeta matchedDist = matchChildByName(area.code(), candidateDist, true);
                    if (matchedDist != null) {
                        city = area;
                        district = matchedDist;
                        break;
                    }
                }
            }
            return toResolved(province, city, district);
        }

        // 3. 普通省份已命中
        if (province != null) {
            if (cityInput != null && !isPlaceholderCity(cityInput)) {
                city = matchChildByName(province.code(), cityInput, false);
                if (city == null) {
                    // 兼容省直辖县级市（如湖北仙桃市、河南济源市、海南琼海市、新疆石河子市）直接写在 city 字段
                    for (RegionMeta subCity : childrenByParent.getOrDefault(province.code(), List.of())) {
                        if (GENERIC_CITY_PLACEHOLDERS.contains(subCity.name())) {
                            RegionMeta directCounty = matchChildByName(subCity.code(), cityInput, true);
                            if (directCounty != null) {
                                return toResolved(province, subCity, directCounty);
                            }
                        }
                    }
                }
            }
            if (city != null && distInput != null) {
                district = matchChildByName(city.code(), distInput, true);
            } else if (city == null && distInput != null) {
                // 省份命中但城市为空/占位，尝试在省下各市唯一命中区县
                RegionMeta foundCity = null;
                RegionMeta foundDist = null;
                int hits = 0;
                for (RegionMeta subCity : childrenByParent.getOrDefault(province.code(), List.of())) {
                    RegionMeta cand = matchChildByName(subCity.code(), distInput, true);
                    if (cand != null) {
                        foundCity = subCity;
                        foundDist = cand;
                        hits++;
                    }
                }
                if (hits == 1) {
                    city = foundCity;
                    district = foundDist;
                }
            }
            return toResolved(province, city, district);
        }

        // 4. 未提供省份或省份未命中：从 cityInput 推断直辖市/港澳或地级市
        if (cityInput != null && !isPlaceholderCity(cityInput)) {
            RegionMeta provAsCity = matchProvince(cityInput);
            if (provAsCity != null) {
                if (MUNICIPALITY_CODES.contains(provAsCity.code())) {
                    List<RegionMeta> mCities = childrenByParent.getOrDefault(provAsCity.code(), List.of());
                    RegionMeta mCity = mCities.isEmpty() ? null : mCities.get(0);
                    RegionMeta mDist = mCity != null && distInput != null
                            ? matchChildByName(mCity.code(), distInput, true) : null;
                    return toResolved(provAsCity, mCity, mDist);
                }
                if (SAR_CODES.contains(provAsCity.code())) {
                    return resolveByNames(provAsCity.name(), null, distInput);
                }
            }
            List<RegionMeta> candidates = citiesByNormName.get(normalizeCityName(cityInput));
            if (candidates != null && !candidates.isEmpty()) {
                RegionMeta matchedCity = null;
                RegionMeta matchedDist = null;
                if (candidates.size() == 1) {
                    matchedCity = candidates.get(0);
                    if (distInput != null) {
                        matchedDist = matchChildByName(matchedCity.code(), distInput, true);
                    }
                } else if (distInput != null) {
                    for (RegionMeta candidate : candidates) {
                        RegionMeta d = matchChildByName(candidate.code(), distInput, true);
                        if (d != null) {
                            matchedCity = candidate;
                            matchedDist = d;
                            break;
                        }
                    }
                }
                if (matchedCity != null) {
                    RegionMeta parentProv = byCode.get(matchedCity.parentCode());
                    return toResolved(parentProv, matchedCity, matchedDist);
                }
            }
        }
        return ResolvedRegion.EMPTY;
    }

    /**
     * 将结构化行政区划反向映射到租户启用中的 cities 字典编码（如 杭州市 -> HZ，北京市 -> BJ，香港 -> HKG）。
     * 未命中返回 null（如非 49 城市清单的地级市，不伪造字典码）。
     */
    public String matchActiveCityDictionaryCode(ResolvedRegion region, List<DictionaryOptionResponse> activeCities) {
        if (region == null || region.isEmpty() || activeCities == null || activeCities.isEmpty()) {
            return null;
        }
        List<String> candidates = new ArrayList<>(2);
        if (region.regionCityName() != null && !GENERIC_CITY_PLACEHOLDERS.contains(region.regionCityName())) {
            candidates.add(region.regionCityName());
        }
        if (region.provinceName() != null
                && (MUNICIPALITY_CODES.contains(region.provinceCode())
                || SAR_CODES.contains(region.provinceCode())
                || region.regionCityName() == null)) {
            candidates.add(region.provinceName());
        }
        for (String candidate : candidates) {
            String normalizedCandidate = normalizeCityName(candidate);
            for (DictionaryOptionResponse option : activeCities) {
                if (option == null || blankToNull(option.optionCode()) == null
                        || blankToNull(option.optionLabel()) == null) {
                    continue;
                }
                String label = option.optionLabel().strip();
                if (candidate.equals(label) || normalizedCandidate.equals(normalizeCityName(label))) {
                    return option.optionCode().strip();
                }
            }
        }
        return null;
    }

    /**
     * 格式化省/市/区展示文本；直辖市自动去重（如 北京市 / 朝阳区）。
     */
    public static String formatRegionText(String provinceName, String regionCityName, String districtName) {
        String p = blankToNull(provinceName);
        String c = blankToNull(regionCityName);
        String d = blankToNull(districtName);
        if (p == null && c == null && d == null) {
            return null;
        }
        List<String> parts = new ArrayList<>(3);
        if (p != null) {
            parts.add(p);
        }
        if (c != null && !c.equals(p) && !GENERIC_CITY_PLACEHOLDERS.contains(c)) {
            parts.add(c);
        }
        if (d != null && !d.equals(c) && !d.equals(p)) {
            parts.add(d);
        }
        return parts.isEmpty() ? null : String.join(" / ", parts);
    }

    private ResolvedRegion tryResolveCompositeText(String text) {
        String compact = text.replaceAll("[\\s/,，-]+", "");
        if (compact.length() < 2) {
            return ResolvedRegion.EMPTY;
        }
        // 优先尝试“省前缀 + 剩余文本”
        List<RegionMeta> sortedProvinces = provinces.stream()
                .sorted(Comparator.comparingInt((RegionMeta m) -> m.name().length()).reversed())
                .toList();
        for (RegionMeta province : sortedProvinces) {
            String matchedPrefix = null;
            if (compact.startsWith(province.name()) && compact.length() > province.name().length()) {
                matchedPrefix = province.name();
            } else {
                String shortProv = normalizeProvinceName(province.name());
                if (shortProv.length() >= 2 && compact.startsWith(shortProv) && compact.length() > shortProv.length()) {
                    matchedPrefix = shortProv;
                }
            }
            if (matchedPrefix == null) {
                continue;
            }
            String rest = compact.substring(matchedPrefix.length());
            if (MUNICIPALITY_CODES.contains(province.code())) {
                List<RegionMeta> mCities = childrenByParent.getOrDefault(province.code(), List.of());
                RegionMeta mCity = mCities.isEmpty() ? null : mCities.get(0);
                RegionMeta mDist = mCity == null ? null : matchPrefixChild(mCity.code(), rest);
                if (mDist != null) {
                    return toResolved(province, mCity, mDist);
                }
                continue;
            }
            RegionMeta matchedCity = matchPrefixChild(province.code(), rest);
            if (matchedCity != null) {
                String cityRest = consumeChildPrefix(rest, matchedCity.name(), false);
                RegionMeta matchedDist = cityRest.isEmpty() ? null : matchPrefixChild(matchedCity.code(), cityRest);
                return toResolved(province, matchedCity, matchedDist);
            }
        }
        return ResolvedRegion.EMPTY;
    }

    private RegionMeta matchPrefixChild(String parentCode, String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        List<RegionMeta> children = childrenByParent.getOrDefault(parentCode, List.of());
        RegionMeta best = null;
        int bestLen = 0;
        for (RegionMeta child : children) {
            if (text.startsWith(child.name()) && child.name().length() > bestLen) {
                best = child;
                bestLen = child.name().length();
                continue;
            }
            String norm = child.level() == 2 ? normalizeCityName(child.name()) : normalizeDistrictName(child.name());
            if (norm.length() >= 2 && text.startsWith(norm) && norm.length() > bestLen) {
                best = child;
                bestLen = norm.length();
            }
        }
        return best;
    }

    private String consumeChildPrefix(String text, String childName, boolean isDistrict) {
        if (text.startsWith(childName)) {
            return text.substring(childName.length());
        }
        String norm = isDistrict ? normalizeDistrictName(childName) : normalizeCityName(childName);
        if (norm.length() >= 2 && text.startsWith(norm)) {
            return text.substring(norm.length());
        }
        return "";
    }

    private RegionMeta matchProvince(String raw) {
        if (raw == null) {
            return null;
        }
        RegionMeta exact = provinceByExactName.get(raw);
        if (exact != null) {
            return exact;
        }
        return provinceByNormName.get(normalizeProvinceName(raw));
    }

    private RegionMeta matchChildByName(String parentCode, String rawName, boolean isDistrict) {
        if (rawName == null) {
            return null;
        }
        List<RegionMeta> children = childrenByParent.getOrDefault(parentCode, List.of());
        for (RegionMeta child : children) {
            if (rawName.equals(child.name())) {
                return child;
            }
        }
        String normInput = isDistrict ? normalizeDistrictName(rawName) : normalizeCityName(rawName);
        for (RegionMeta child : children) {
            String normChild = isDistrict ? normalizeDistrictName(child.name()) : normalizeCityName(child.name());
            if (!normInput.isEmpty() && normInput.equals(normChild)) {
                return child;
            }
        }
        return null;
    }

    private static ResolvedRegion toResolved(RegionMeta province, RegionMeta city, RegionMeta district) {
        if (province == null && city == null && district == null) {
            return ResolvedRegion.EMPTY;
        }
        return new ResolvedRegion(
                province == null ? null : province.code(),
                province == null ? null : province.name(),
                city == null ? null : city.code(),
                city == null ? null : city.name(),
                district == null ? null : district.code(),
                district == null ? null : district.name());
    }

    private static boolean isPlaceholderCity(String value) {
        return GENERIC_CITY_PLACEHOLDERS.contains(value);
    }

    private static String cleanInput(String raw) {
        String trimmed = blankToNull(raw);
        if (trimmed == null || trimmed.length() > 64) {
            return null;
        }
        return trimmed;
    }

    private static String normalizeProvinceName(String raw) {
        String s = raw.strip();
        for (String suffix : List.of("壮族自治区", "回族自治区", "维吾尔自治区", "特别行政区", "自治区", "省", "市")) {
            if (s.length() > suffix.length() && s.endsWith(suffix)) {
                return s.substring(0, s.length() - suffix.length());
            }
        }
        return s;
    }

    private static String normalizeCityName(String raw) {
        String s = raw.strip();
        for (String suffix : List.of("特别行政区", "自治州", "地区", "盟", "市")) {
            if (s.length() > suffix.length() + 1 && s.endsWith(suffix)) {
                return s.substring(0, s.length() - suffix.length());
            }
        }
        if (s.length() > 2 && s.endsWith("市")) {
            return s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static String normalizeDistrictName(String raw) {
        String s = raw.strip();
        for (String suffix : List.of("自治县", "自治旗", "林区", "特区", "街道", "新区", "矿区", "区", "县", "市", "旗", "镇")) {
            if (s.length() > suffix.length() + 1 && s.endsWith(suffix)) {
                return s.substring(0, s.length() - suffix.length());
            }
        }
        return s;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static List<RawRegionNode> loadRawTree(ObjectMapper objectMapper) {
        try (InputStream in = RegionCatalogService.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                throw new IllegalStateException("缺少行政区划数据资源: " + RESOURCE_PATH);
            }
            return objectMapper.readValue(in, new TypeReference<>() { });
        } catch (IOException ex) {
            throw new IllegalStateException("加载行政区划数据失败: " + RESOURCE_PATH, ex);
        }
    }

    private record RawRegionNode(String code, String name, List<RawRegionNode> children) {
    }

    private record RegionMeta(String code, String name, int level, String parentCode, String grandparentCode) {
    }
}
