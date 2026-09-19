package com.campusdeal.social;

import com.campusdeal.exception.ValidationException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Opaque cursor codec for the feed snapshot score/member boundary. */
public final class FeedCursorCodec {
    private FeedCursorCodec() { }

    public static String encode(long snapshotMaxTime, long score, String member) {
        if (snapshotMaxTime < 0 || score < 0 || member == null || member.isBlank()) {
            throw new ValidationException("分页游标不合法");
        }
        String raw = snapshotMaxTime + ":" + score + ":" + member;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decode(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split(":", 3);
            if (parts.length != 3) throw new IllegalArgumentException();
            long snapshot = Long.parseLong(parts[0]);
            long score = Long.parseLong(parts[1]);
            if (snapshot < 0 || score < 0 || parts[2].isBlank()) throw new IllegalArgumentException();
            return new Cursor(snapshot, score, parts[2]);
        } catch (Exception e) {
            throw new ValidationException("分页游标不合法");
        }
    }

    public record Cursor(long snapshotMaxTime, long score, String member) { }
}
