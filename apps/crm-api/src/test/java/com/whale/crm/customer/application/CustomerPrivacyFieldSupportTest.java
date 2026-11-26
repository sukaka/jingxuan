package com.whale.crm.customer.application;

import com.whale.crm.customer.domain.Customer;
import com.whale.crm.customer.privacy.domain.CustomerPrivacyField;
import com.whale.crm.platform.security.CryptoProperties;
import com.whale.crm.platform.security.crypto.AesGcmCipher;
import com.whale.crm.platform.security.crypto.BlindIndex;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerPrivacyFieldSupportTest {

    private CustomerPrivacyFieldSupport support;

    @BeforeEach
    void setUp() {
        String key = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        CryptoProperties props = new CryptoProperties(key, key);
        AesGcmCipher cipher = new AesGcmCipher(props);
        BlindIndex blindIndex = new BlindIndex(props);
        MobileSupport mobileSupport = new MobileSupport(cipher, blindIndex);
        support = new CustomerPrivacyFieldSupport(mobileSupport, cipher, blindIndex);
    }

    @Test
    void normalizesEncryptsAndMasksAllFourPrivacyFields() {
        String idCard = support.normalizeIdCard("11010519491231002x");
        assertThat(idCard).isEqualTo("11010519491231002X");
        String email = support.normalizeEmail("  Alice.Test@Example.COM ");
        assertThat(email).isEqualTo("alice.test@example.com");
        String wechat = support.normalizeWechatId(" wx_alice_8899 ");
        assertThat(wechat).isEqualTo("wx_alice_8899");

        Customer customer = new Customer();
        customer.setMobileCipher(support.encryptMobile("13911112222"));
        customer.setIdCardCipher(support.encrypt(idCard));
        customer.setEmailCipher(support.encrypt(email));
        customer.setWechatIdCipher(support.encrypt(wechat));

        assertThat(support.maskForCustomer(customer, CustomerPrivacyField.MOBILE)).isEqualTo("139****2222");
        assertThat(support.maskForCustomer(customer, CustomerPrivacyField.ID_CARD)).isEqualTo("110105********002X");
        assertThat(support.maskForCustomer(customer, CustomerPrivacyField.EMAIL)).isEqualTo("al***@example.com");
        assertThat(support.maskForCustomer(customer, CustomerPrivacyField.WECHAT_ID)).isEqualTo("wx****99");

        assertThat(support.decryptForAuthorizedReveal(customer, CustomerPrivacyField.MOBILE)).isEqualTo("13911112222");
        assertThat(support.decryptForAuthorizedReveal(customer, CustomerPrivacyField.ID_CARD)).isEqualTo("11010519491231002X");
        assertThat(support.decryptForAuthorizedReveal(customer, CustomerPrivacyField.EMAIL)).isEqualTo("alice.test@example.com");
        assertThat(support.decryptForAuthorizedReveal(customer, CustomerPrivacyField.WECHAT_ID)).isEqualTo("wx_alice_8899");
    }

    @Test
    void rejectsInvalidFormatsAndMaskedWildcards() {
        assertThatThrownBy(() -> support.normalizeMobile("139****2222"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> support.normalizeIdCard("110105194912310021"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> support.normalizeIdCard("110105********002X"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> support.normalizeEmail("bad-email"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> support.normalizeWechatId("wx****99"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void extractsExactBlindIndexHashesForKeywordSearch() {
        var emailHashes = support.extractSearchHashes("Alice.Test@Example.com");
        assertThat(emailHashes.emailHash()).isEqualTo(support.hashEmail("alice.test@example.com"));
        var idHashes = support.extractSearchHashes("11010519491231002x");
        assertThat(idHashes.idCardHash()).isEqualTo(support.hashIdCard("11010519491231002X"));
        var maskedHashes = support.extractSearchHashes("139****2222");
        assertThat(maskedHashes.mobileHash()).isNull();
        assertThat(maskedHashes.wechatIdHash()).isNull();
    }
}
