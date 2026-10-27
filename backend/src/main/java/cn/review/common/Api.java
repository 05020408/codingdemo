package cn.review.common;

import jakarta.servlet.http.HttpServletRequest;

public record Api<T>(String code, String message, T data, String requestId) {
    public static <T> Api<T> ok(T value, HttpServletRequest request) {
        return new Api<>("OK", "成功", value, requestId(request));
    }
    public static <T> Api<T> accepted(T value, HttpServletRequest request) {
        return new Api<>("ACCEPTED", "已接受处理", value, requestId(request));
    }
    public static String requestId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute("requestId"));
    }
}
