package com.campusdeal.social;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeedCursorCodecTest {

    @Test
    void opaqueCursorPreservesSnapshotAndEqualScoreTieBreak() {
        String cursor = FeedCursorCodec.encode(1000L, 900L, "post-42");
        var decoded = FeedCursorCodec.decode(cursor);

        assertThat(decoded.snapshotMaxTime()).isEqualTo(1000L);
        assertThat(decoded.score()).isEqualTo(900L);
        assertThat(decoded.member()).isEqualTo("post-42");
        assertThat(cursor).doesNotContain(":");
    }

    @Test
    void malformedCursorIsRejectedWithoutExposingInternalDetails() {
        assertThatThrownBy(() -> FeedCursorCodec.decode("../raw:cursor"))
                .isInstanceOf(com.campusdeal.exception.ValidationException.class);
    }
}
