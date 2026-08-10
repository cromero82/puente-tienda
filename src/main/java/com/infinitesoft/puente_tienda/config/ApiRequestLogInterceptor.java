package com.infinitesoft.puente_tienda.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Component
@Slf4j
public class ApiRequestLogInterceptor implements HandlerInterceptor {

    private static final String ATTR_T0 = "_apiLogT0";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(ATTR_T0, System.currentTimeMillis());
        log.info("{} {} →", request.getMethod(), requestUri(request));
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception ex) {
        long ms = elapsedMs(request);
        int status = response.getStatus();
        String uri = requestUri(request);
        if (ex != null || status >= 400) {
            log.error("{} {} ← status={} ms={} ex={}",
                    request.getMethod(),
                    uri,
                    status,
                    ms,
                    ex != null ? ex.getClass().getSimpleName() + ": " + ex.getMessage() : "-");
        } else {
            log.info("{} {} ← status={} ms={}", request.getMethod(), uri, status, ms);
        }
    }

    private static String requestUri(HttpServletRequest request) {
        String qs = request.getQueryString();
        if (qs == null || qs.isBlank()) {
            return request.getRequestURI();
        }
        return request.getRequestURI() + "?" + qs;
    }

    private static long elapsedMs(HttpServletRequest request) {
        Object t0 = request.getAttribute(ATTR_T0);
        if (!(t0 instanceof Long)) {
            return -1;
        }
        return System.currentTimeMillis() - (Long) t0;
    }
}
