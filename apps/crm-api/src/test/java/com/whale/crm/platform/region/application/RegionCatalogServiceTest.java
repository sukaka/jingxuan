package com.whale.crm.platform.region.application;

import com.whale.crm.platform.dictionary.api.dto.DictionaryOptionResponse;
import com.whale.crm.platform.region.api.dto.RegionNodeResponse;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegionCatalogServiceTest {

    private final RegionCatalogService service = new RegionCatalogService();

    @Test
    void treeLoadsAll34ProvincialDivisionsWithUniqueCodes() {
        List<RegionNodeResponse> tree = service.tree();
        assertThat(tree).hasSize(34);
        Set<String> codes = new HashSet<>();
        int cityCount = 0;
        int districtCount = 0;
        for (RegionNodeResponse province : tree) {
            assertThat(province.level()).isEqualTo(1);
            assertThat(codes.add(province.code())).isTrue();
            for (RegionNodeResponse city : province.children()) {
                assertThat(city.level()).isEqualTo(2);
                assertThat(codes.add(city.code())).isTrue();
                cityCount++;
                for (RegionNodeResponse district : city.children()) {
                    assertThat(district.level()).isEqualTo(3);
                    assertThat(codes.add(district.code())).isTrue();
                    districtCount++;
                }
            }
        }
        assertThat(cityCount).isGreaterThan(350);
        assertThat(districtCount).isGreaterThan(3000);
    }

    @Test
    void resolveAndValidateSupportsOptionalPrefixFullHierarchyAndRejectsMismatches() {
        assertThat(service.resolveAndValidate(null, null, null)).isEqualTo(ResolvedRegion.EMPTY);
        assertThat(service.resolveAndValidate("  ", "", null)).isEqualTo(ResolvedRegion.EMPTY);

        ResolvedRegion provOnly = service.resolveAndValidate("330000", null, null);
        assertThat(provOnly.provinceName()).isEqualTo("浙江省");
        assertThat(provOnly.regionCityCode()).isNull();
        assertThat(provOnly.formattedText()).isEqualTo("浙江省");

        ResolvedRegion provCity = service.resolveAndValidate("330000", "330100", null);
        assertThat(provCity.provinceName()).isEqualTo("浙江省");
        assertThat(provCity.regionCityName()).isEqualTo("杭州市");
        assertThat(provCity.districtCode()).isNull();
        assertThat(provCity.formattedText()).isEqualTo("浙江省 / 杭州市");

        ResolvedRegion full = service.resolveAndValidate("330000", "330100", "330106");
        assertThat(full.districtName()).isEqualTo("西湖区");
        assertThat(full.formattedText()).isEqualTo("浙江省 / 杭州市 / 西湖区");

        ResolvedRegion bj = service.resolveAndValidate("110000", "110100", "110105");
        assertThat(bj.formattedText()).isEqualTo("北京市 / 朝阳区");

        ResolvedRegion chongqingCounty = service.resolveAndValidate("500000", "500100", "500229");
        assertThat(chongqingCounty.districtName()).isEqualTo("城口县");
        assertThat(chongqingCounty.formattedText()).isEqualTo("重庆市 / 城口县");

        ResolvedRegion dongguan = service.resolveAndValidate("440000", "441900", "441900003");
        assertThat(dongguan.regionCityName()).isEqualTo("东莞市");
        assertThat(dongguan.districtName()).isEqualTo("东城街道");

        assertThatThrownBy(() -> service.resolveAndValidate("330000", "440300", null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        assertThatThrownBy(() -> service.resolveAndValidate("330000", "330100", "110105"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        assertThatThrownBy(() -> service.resolveAndValidate("999999", null, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void resolveByNamesHandlesProvincesMunicipalitiesCompositeTextAndCityDictionaryMatching() {
        ResolvedRegion hz = service.resolveByNames("浙江省", "杭州市", "西湖区");
        assertThat(hz.provinceCode()).isEqualTo("330000");
        assertThat(hz.regionCityCode()).isEqualTo("330100");
        assertThat(hz.districtCode()).isEqualTo("330106");

        ResolvedRegion shPlaceholder = service.resolveByNames("上海市", "市辖区", "浦东新区");
        assertThat(shPlaceholder.provinceCode()).isEqualTo("310000");
        assertThat(shPlaceholder.regionCityCode()).isEqualTo("310100");
        assertThat(shPlaceholder.districtCode()).isEqualTo("310115");

        ResolvedRegion bjCityAsDist = service.resolveByNames("北京", "朝阳区", null);
        assertThat(bjCityAsDist.provinceCode()).isEqualTo("110000");
        assertThat(bjCityAsDist.regionCityCode()).isEqualTo("110100");
        assertThat(bjCityAsDist.districtCode()).isEqualTo("110105");

        ResolvedRegion composite = service.resolveByNames(null, "浙江省杭州市西湖区", null);
        assertThat(composite.districtCode()).isEqualTo("330106");

        ResolvedRegion hk = service.resolveByNames("香港", null, "中西区");
        assertThat(hk.provinceCode()).isEqualTo("810000");
        assertThat(hk.districtName()).isEqualTo("中西区");

        List<DictionaryOptionResponse> activeCities = List.of(
                new DictionaryOptionResponse("BJ", "北京", 1),
                new DictionaryOptionResponse("HZ", "杭州", 2),
                new DictionaryOptionResponse("HKG", "香港", 3));
        assertThat(service.matchActiveCityDictionaryCode(hz, activeCities)).isEqualTo("HZ");
        assertThat(service.matchActiveCityDictionaryCode(bjCityAsDist, activeCities)).isEqualTo("BJ");
        assertThat(service.matchActiveCityDictionaryCode(hk, activeCities)).isEqualTo("HKG");
        ResolvedRegion lishui = service.resolveAndValidate("330000", "331100", "331102");
        assertThat(service.matchActiveCityDictionaryCode(lishui, activeCities)).isNull();
    }
}
