package com.campusdeal.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 36000L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_MERCHANT_TTL = 30L;
    public static final String CACHE_MERCHANT_KEY = "cache:merchant:";

    public static final String LOCK_MERCHANT_KEY = "lock:merchant:";
    public static final Long LOCK_MERCHANT_TTL = 10L;

    public static final String FLASH_DEAL_STOCK_KEY = "flashdeal:stock:";
    public static final String FLASH_DEAL_ORDER_KEY = "flashdeal:order:";
    public static final String POST_LIKED_KEY = "post:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String MERCHANT_GEO_KEY = "merchant:geo:";
    public static final String USER_SIGN_KEY = "sign:";
}
