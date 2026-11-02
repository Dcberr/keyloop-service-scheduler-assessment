package com.keyloop.scheduler.shared;

import java.util.List;

public record Page<T>(List<T> items, int page, int size, long totalElements) {
    public Page { items = List.copyOf(items); }

    public static long offset(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BusinessException(400, "INVALID_REQUEST", "page must be nonnegative and size must be between 1 and 100");
        }
        return (long) page * size;
    }
}
