package com.keyloop.scheduler.api;

import com.keyloop.scheduler.shared.BusinessException;
import java.util.UUID;
import java.util.regex.Pattern;

final class ApiIds {
    private static final Pattern UUID_TEXT=Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private ApiIds() { }
    static UUID required(String value) {
        if(value==null || !UUID_TEXT.matcher(value).matches()) throw new BusinessException(400,"INVALID_REQUEST","Entity IDs must be canonical UUID strings");
        return UUID.fromString(value);
    }
}
