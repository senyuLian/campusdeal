package com.campusdeal.config;

import com.campusdeal.exception.ConflictException;
import com.campusdeal.exception.ForbiddenException;
import com.campusdeal.exception.RateLimitException;
import com.campusdeal.exception.UnauthorizedException;
import com.campusdeal.exception.ValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class WebExceptionAdviceTest {

    private final WebExceptionAdvice advice = new WebExceptionAdvice();

    @Test
    void mapsStableApiErrorsToExpectedHttpStatuses() {
        assertThat(advice.handleApiException(new ValidationException("bad")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(advice.handleApiException(new UnauthorizedException()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(advice.handleApiException(new ForbiddenException()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(advice.handleApiException(new ConflictException("conflict")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(advice.handleApiException(new RateLimitException("slow down")).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void unexpectedRuntimeErrorDoesNotExposeDetails() {
        var response = advice.handleRuntimeException(new RuntimeException("password=secret"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().getErrorMsg()).doesNotContain("secret");
    }
}
