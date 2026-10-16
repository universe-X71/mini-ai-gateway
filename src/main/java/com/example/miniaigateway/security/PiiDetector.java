package com.example.miniaigateway.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class PiiDetector {

    public enum PiiType {
        EMAIL, PHONE, ID_CARD, BANK_CARD
    }

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");

    private static final Pattern PHONE_PATTERN =
            Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");

    private static final Pattern ID_CARD_PATTERN =
            Pattern.compile("(?<!\\d)[1-9]\\d{5}(18|19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx](?!\\d)");

    private static final Pattern BANK_CARD_PATTERN =
            Pattern.compile("(?<!\\d)\\d{16,19}(?!\\d)");

    /**
     * 检测文本中是否包含 PII
     */
    public boolean containsPii(String text) {
        if (text == null || text.isBlank()) return false;
        return EMAIL_PATTERN.matcher(text).find()
                || PHONE_PATTERN.matcher(text).find()
                || ID_CARD_PATTERN.matcher(text).find()
                || BANK_CARD_PATTERN.matcher(text).find();
    }

    /**
     * 对文本做 PII 脱敏，返回脱敏后的文本
     */
    public String mask(String text) {
        if (text == null || text.isBlank()) return text;
        String result = text;
        result = maskByPattern(result, EMAIL_PATTERN, PiiType.EMAIL);
        result = maskByPattern(result, PHONE_PATTERN, PiiType.PHONE);
        result = maskByPattern(result, ID_CARD_PATTERN, PiiType.ID_CARD);
        result = maskByPattern(result, BANK_CARD_PATTERN, PiiType.BANK_CARD);
        return result;
    }

    private String maskByPattern(String text, Pattern pattern, PiiType type) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String raw = matcher.group();
            String masked = maskValue(raw, type);
            log.info("PII masked: type={}, rawLen={}, masked={}", type, raw.length(), masked);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(masked));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String maskValue(String raw, PiiType type) {
        int len = raw.length();
        return switch (type) {
            case EMAIL -> maskEmail(raw);
            case PHONE -> raw.substring(0, 3) + "****" + raw.substring(7);
            case ID_CARD -> raw.substring(0, 6) + "********" + raw.substring(14);
            case BANK_CARD -> raw.substring(0, 4) + "****".repeat(Math.max(0, len - 8)) + raw.substring(len - 4);
        };
    }

    private String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 1) return "***" + email.substring(at);
        return email.charAt(0) + "***" + email.substring(at);
    }
}