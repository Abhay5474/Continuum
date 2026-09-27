package io.continuum.portal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/** Enforces {@link OperatorOnly}: 403 unless the session is the operator's. */
public class OperatorOnlyInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod m && requiresOperator(m) && !RequestScope.isOperator(request)) {
            throw new RequestScope.ForbiddenException(
                    "This setting applies to the whole engine, so only the operator can change it.");
        }
        return true;
    }

    static boolean requiresOperator(HandlerMethod m) {
        return m.hasMethodAnnotation(OperatorOnly.class) || m.getBeanType().isAnnotationPresent(OperatorOnly.class);
    }
}
