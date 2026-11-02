package com.tdtu.ibanking.payment.exception;

/**
 * Mã lỗi ổn định trả kèm {@code message} trong body lỗi. Client phân biệt các ca lỗi
 * bằng mã này thay vì dò câu chữ tiếng Việt, vốn có thể đổi bất cứ lúc nào.
 */
public final class ErrorCode {
    private ErrorCode() {}

    public static final String TUITION_NOT_FOUND = "TUITION_NOT_FOUND";
    public static final String TUITION_ALREADY_PAID = "TUITION_ALREADY_PAID";
    /** Khoản học phí đang được một giao dịch khác giữ (đang chờ OTP hoặc chờ đối soát). */
    public static final String TUITION_LOCKED = "TUITION_LOCKED";
    public static final String INSUFFICIENT_BALANCE = "INSUFFICIENT_BALANCE";
    public static final String OTP_INVALID = "OTP_INVALID";
    public static final String OTP_REQUEST_LIMIT = "OTP_REQUEST_LIMIT";
    public static final String OTP_ATTEMPT_LIMIT = "OTP_ATTEMPT_LIMIT";
    /** Giao dịch đã ở trạng thái FAILED, không xác thực lại được. */
    public static final String TRANSACTION_FAILED = "TRANSACTION_FAILED";
    public static final String TRANSACTION_NOT_FOUND = "TRANSACTION_NOT_FOUND";
    public static final String TRANSACTION_FORBIDDEN = "TRANSACTION_FORBIDDEN";
    /** Saga thất bại vì lý do khác (lỗi cấu hình, không tìm thấy khoản học phí...), đã hoàn tiền. */
    public static final String PAYMENT_FAILED = "PAYMENT_FAILED";
    public static final String SERVICE_BUSY = "SERVICE_BUSY";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
}
