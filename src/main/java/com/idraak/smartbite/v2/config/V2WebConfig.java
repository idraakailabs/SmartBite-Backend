package com.idraak.smartbite.v2.config;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.idraak.smartbite.common.ratelimit.RateLimitSpec;
import com.idraak.smartbite.common.ratelimit.RateLimiter;
import com.idraak.smartbite.common.web.ClientIpResolver;
import com.idraak.smartbite.config.V1Properties;
import com.idraak.smartbite.v2.auth.CurrentUserArgumentResolver;
import com.idraak.smartbite.v2.auth.FakeIdTokenVerifier;
import com.idraak.smartbite.v2.auth.FirebaseAuthFilter;
import com.idraak.smartbite.v2.auth.FirebaseIdTokenVerifier;
import com.idraak.smartbite.v2.auth.IdTokenVerifier;
import com.idraak.smartbite.v2.web.AccessLogFilter;
import com.idraak.smartbite.v2.web.BodySizeLimitFilter;
import com.idraak.smartbite.v2.web.RequestIdFilter;
import com.idraak.smartbite.v2.web.UidHasher;
import com.idraak.smartbite.v2.web.V2RateLimits;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.List;

@Configuration
@EnableConfigurationProperties(V2Properties.class)
public class V2WebConfig implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserArgumentResolver());
    }

    @Bean
    public IdTokenVerifier idTokenVerifier(V2Properties props, ObjectProvider<FirebaseApp> firebaseApp, Clock clock) {
        if (props.auth().fakeTokensEnabled()) {
            return new FakeIdTokenVerifier(clock);
        }
        FirebaseApp app = firebaseApp.getIfAvailable();
        if (app == null) {
            throw new IllegalStateException("Real ID-token verification needs smartbite.v2.stores.mode=firestore "
                    + "(Firebase Admin SDK). Set SMARTBITE_STORES_MODE=firestore and FIREBASE_SERVICE_ACCOUNT_BASE64.");
        }
        return new FirebaseIdTokenVerifier(FirebaseAuth.getInstance(app));
    }

    @Bean
    public UidHasher uidHasher(V2Properties props) {
        return new UidHasher(props.logging().uidHashKey());
    }

    @Bean
    public V2RateLimits v2RateLimits(V2Properties props, Clock clock) {
        return new V2RateLimits(props.limits(), clock);
    }

    @Bean
    public FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        FilterRegistrationBean<RequestIdFilter> r = new FilterRegistrationBean<>(new RequestIdFilter());
        r.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return r;
    }

    @Bean
    public FilterRegistrationBean<AccessLogFilter> accessLogFilter(UidHasher hasher) {
        FilterRegistrationBean<AccessLogFilter> r = new FilterRegistrationBean<>(new AccessLogFilter(hasher));
        r.addUrlPatterns("/api/v2/*");
        r.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return r;
    }

    @Bean
    public FilterRegistrationBean<FirebaseAuthFilter> firebaseAuthFilter(IdTokenVerifier verifier, V2Properties props,
                                                                         V1Properties v1, ObjectMapper mapper, Clock clock) {
        RateLimiter preAuth = new RateLimiter(List.of(RateLimitSpec.perMinute(props.auth().preAuthPerIpPerMinute())), clock);
        FirebaseAuthFilter filter = new FirebaseAuthFilter(verifier, preAuth, new ClientIpResolver(v1.trustedProxies()), mapper);
        FilterRegistrationBean<FirebaseAuthFilter> r = new FilterRegistrationBean<>(filter);
        r.addUrlPatterns("/api/v2/*");
        r.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        return r;
    }

    @Bean
    public FilterRegistrationBean<BodySizeLimitFilter> bodySizeLimitFilter(V2Properties props, ObjectMapper mapper) {
        FilterRegistrationBean<BodySizeLimitFilter> r =
                new FilterRegistrationBean<>(new BodySizeLimitFilter(props.limits().maxBodyBytes(),
                        props.limits().maxHistoryBodyBytes(), mapper));
        r.addUrlPatterns("/api/v2/*");
        r.setOrder(Ordered.HIGHEST_PRECEDENCE + 3);
        return r;
    }
}
