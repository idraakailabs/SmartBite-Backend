package com.idraak.smartbite.common.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {

    @Test
    void usesRemoteAddrWithoutHeader() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.0.0.1");
        assertThat(new ClientIpResolver(1).resolve(req)).isEqualTo("10.0.0.1");
    }

    @Test
    void takesTheEntryAddedByTheTrustedProxyNotTheSpoofableLeftmost() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "6.6.6.6, 203.0.113.7");
        assertThat(new ClientIpResolver(1).resolve(req)).isEqualTo("203.0.113.7");
    }

    @Test
    void zeroTrustedProxiesIgnoresHeader() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.0.0.2");
        req.addHeader("X-Forwarded-For", "6.6.6.6");
        assertThat(new ClientIpResolver(0).resolve(req)).isEqualTo("10.0.0.2");
    }
}
