package com.campusdeal.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class WriteRequestValidationTest {

    private static jakarta.validation.ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void invalidFlashWindowAndCoordinatesAreRejected() {
        CouponWriteRequest coupon = new CouponWriteRequest();
        coupon.setShopId(1L);
        coupon.setTitle("flash");
        coupon.setPayValue(1L);
        coupon.setActualValue(1L);
        coupon.setType(1);
        coupon.setStock(-1);
        coupon.setBeginTime(LocalDateTime.of(2026, 1, 2, 10, 0));
        coupon.setEndTime(LocalDateTime.of(2026, 1, 2, 9, 0));
        assertThat(validator.validate(coupon)).isNotEmpty();

        MerchantWriteRequest merchant = new MerchantWriteRequest();
        merchant.setName("shop");
        merchant.setTypeId(1L);
        merchant.setX(181.0);
        merchant.setY(30.0);
        assertThat(validator.validate(merchant)).isNotEmpty();
    }

    @Test
    void validBoundedPostRequestPasses() {
        PostWriteRequest post = new PostWriteRequest();
        post.setShopId(1L);
        post.setTitle("title");
        post.setContent("content");
        assertThat(validator.validate(post)).isEmpty();
    }
}
