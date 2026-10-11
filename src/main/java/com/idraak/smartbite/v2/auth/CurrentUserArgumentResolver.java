package com.idraak.smartbite.v2.auth;

import com.idraak.smartbite.v2.error.ApiException;
import com.idraak.smartbite.v2.error.ErrorCode;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Injects the {@link AuthenticatedUser} set by {@link FirebaseAuthFilter} into controller methods. */
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == AuthenticatedUser.class;
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest request,
                                  WebDataBinderFactory binderFactory) {
        Object user = request.getAttribute(AuthenticatedUser.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (user instanceof AuthenticatedUser u) return u;
        throw new ApiException(ErrorCode.UNAUTHENTICATED);
    }
}
