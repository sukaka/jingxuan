package com.whale.crm.platform.region.application;

/**
 * 校验并标准化后的客户行政区划三元组（省/市/区县均可空，允许仅选到省或省+市）。
 */
public record ResolvedRegion(
        String provinceCode,
        String provinceName,
        String regionCityCode,
        String regionCityName,
        String districtCode,
        String districtName) {

    public static final ResolvedRegion EMPTY = new ResolvedRegion(null, null, null, null, null, null);

    public boolean isEmpty() {
        return provinceCode == null && regionCityCode == null && districtCode == null;
    }

    public String formattedText() {
        return RegionCatalogService.formatRegionText(provinceName, regionCityName, districtName);
    }
}
