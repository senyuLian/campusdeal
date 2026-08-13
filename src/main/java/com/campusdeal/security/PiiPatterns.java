package com.campusdeal.security;

import java.util.regex.Pattern;

/**
 * PII（个人身份信息）检测模式。
 */
public final class PiiPatterns {

    private PiiPatterns() {
    }

    /** 手机号：1[3-9] 开头，共 11 位 */
    public static final Pattern PHONE = Pattern.compile("1[3-9]\\d{9}");

    /** 身份证号：18 位（含校验位 X/x） */
    public static final Pattern ID_CARD = Pattern.compile("\\d{17}[\\dXx]");

    /** 邮箱 */
    public static final Pattern EMAIL = Pattern.compile("[\\w.-]+@[\\w.-]+\\.\\w+");

    /** 银行卡号：16-19 位数字 */
    public static final Pattern BANK_CARD = Pattern.compile("\\d{16,19}");

    /** IP 地址 */
    public static final Pattern IP_ADDRESS = Pattern.compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
}
