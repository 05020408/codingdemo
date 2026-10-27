package cn.review.common;

public class ApiException extends RuntimeException {
    private final int status;
    private final String code;
    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
    public int status() { return status; }
    public String code() { return code; }
    public static ApiException missing() { return new ApiException(404, "RESOURCE_NOT_FOUND", "内容不存在或已不可访问"); }
    public static ApiException conflict() { return new ApiException(409, "VERSION_CONFLICT", "内容已更新，请刷新后重试"); }
    public static String text(String value, String label, int min, int max) {
        String result = value == null ? "" : value.strip();
        int length = result.codePointCount(0, result.length());
        if (length < min || length > max) throw new ApiException(400, "VALIDATION_FAILED", label + "长度应为 " + min + " 至 " + max + " 个字符");
        return result;
    }
}
