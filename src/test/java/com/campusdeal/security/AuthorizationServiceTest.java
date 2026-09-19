package com.campusdeal.security;

import com.campusdeal.dto.UserDTO;
import com.campusdeal.entity.Merchant;
import com.campusdeal.mapper.CouponMapper;
import com.campusdeal.mapper.MerchantMapper;
import com.campusdeal.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthorizationServiceTest {

    @Mock MerchantMapper merchantMapper;
    @Mock CouponMapper couponMapper;
    @InjectMocks AuthorizationService authorizationService;

    @AfterEach
    void clearPrincipal() {
        UserHolder.removeUser();
    }

    @Test
    void anonymousCannotWrite() {
        assertThatThrownBy(() -> authorizationService.requireAuthenticated())
                .isInstanceOf(com.campusdeal.exception.UnauthorizedException.class);
    }

    @Test
    void ownerMayWriteAndUnrelatedUserMayNot() {
        Merchant merchant = new Merchant().setId(7L).setOwnerUserId(10L);
        when(merchantMapper.selectById(7L)).thenReturn(merchant);
        UserDTO owner = user(10L, "USER");
        UserHolder.saveUser(owner);
        authorizationService.requireMerchantOwner(7L);

        UserHolder.saveUser(user(11L, "USER"));
        assertThatThrownBy(() -> authorizationService.requireMerchantOwner(7L))
                .isInstanceOf(com.campusdeal.exception.ForbiddenException.class);
    }

    @Test
    void administratorMayWriteAnyMerchant() {
        UserHolder.saveUser(user(99L, "ADMIN"));
        authorizationService.requireMerchantOwner(999L);
    }

    private UserDTO user(long id, String role) {
        UserDTO dto = new UserDTO();
        dto.setId(id);
        dto.setRole(role);
        return dto;
    }
}
