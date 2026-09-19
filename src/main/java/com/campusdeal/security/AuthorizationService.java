package com.campusdeal.security;

import com.campusdeal.dto.UserDTO;
import com.campusdeal.entity.Coupon;
import com.campusdeal.entity.Merchant;
import com.campusdeal.exception.ForbiddenException;
import com.campusdeal.exception.UnauthorizedException;
import com.campusdeal.exception.ValidationException;
import com.campusdeal.mapper.CouponMapper;
import com.campusdeal.mapper.MerchantMapper;
import com.campusdeal.utils.UserHolder;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/** Central resource authorization used by both controllers and services. */
@Component
public class AuthorizationService {

    @Resource
    private MerchantMapper merchantMapper;
    @Resource
    private CouponMapper couponMapper;

    public UserDTO requireAuthenticated() {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            throw new UnauthorizedException();
        }
        return user;
    }

    public boolean isAdmin(UserDTO user) {
        return user != null && "ADMIN".equalsIgnoreCase(user.getRole());
    }

    public void requireAdmin() {
        UserDTO user = requireAuthenticated();
        if (!isAdmin(user)) {
            throw new ForbiddenException();
        }
    }

    public void requireMerchantOwner(Long merchantId) {
        if (merchantId == null) {
            throw new ValidationException("商户id不能为空");
        }
        UserDTO user = requireAuthenticated();
        if (isAdmin(user)) {
            return;
        }
        Merchant merchant = merchantMapper.selectById(merchantId);
        if (merchant == null || merchant.getOwnerUserId() == null
                || !merchant.getOwnerUserId().equals(user.getId())) {
            throw new ForbiddenException();
        }
    }

    public void requireCouponOwner(Long couponId) {
        if (couponId == null) {
            throw new ValidationException("优惠券id不能为空");
        }
        Coupon coupon = couponMapper.selectById(couponId);
        if (coupon == null) {
            throw new ValidationException("优惠券不存在");
        }
        requireMerchantOwner(coupon.getShopId());
    }

    public void requireSameUser(Long userId) {
        UserDTO user = requireAuthenticated();
        if (!isAdmin(user) && (userId == null || !user.getId().equals(userId))) {
            throw new ForbiddenException();
        }
    }
}
