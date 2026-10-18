package com.example.onedrive.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Microsoft Graph API failure with HTTP status and Graph error code/message when present.
 */
public final class GraphException extends RuntimeException {

    private final int httpStatus;
    private final String graphCode;
    private final String graphMessage;

    public GraphException(int httpStatus, String url, String responseBody) {
        super(formatMessage(httpStatus, url, responseBody));
        this.httpStatus = httpStatus;
        String[] parsed = parseGraphError(responseBody);
        this.graphCode = parsed[0];
        this.graphMessage = parsed[1];
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String graphCode() {
        return graphCode;
    }

    public String graphMessage() {
        return graphMessage;
    }

    private static String formatMessage(int httpStatus, String url, String body) {
        String[] parsed = parseGraphError(body);
        String code = parsed[0];
        String msg = parsed[1];
        StringBuilder sb = new StringBuilder();
        sb.append("Graph HTTP ").append(httpStatus);
        if (url != null && !url.isBlank()) {
            sb.append(" for ").append(url);
        }
        if (code != null) {
            sb.append(": ").append(code);
            if (msg != null) {
                sb.append(" — ").append(msg);
            }
        } else if (body != null && !body.isBlank()) {
            sb.append(": ").append(truncate(body, 500));
        }
        return sb.toString();
    }

    /** Returns [code, message] (nullable elements). */
    private static String[] parseGraphError(String body) {
        String code = null;
        String message = null;
        if (body == null || body.isBlank()) {
            return new String[] {null, null};
        }
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (root.has("error") && root.get("error").isJsonObject()) {
                JsonObject err = root.getAsJsonObject("error");
                if (err.has("code") && !err.get("code").isJsonNull()) {
                    code = err.get("code").getAsString();
                }
                if (err.has("message") && !err.get("message").isJsonNull()) {
                    message = err.get("message").getAsString();
                }
            }
        } catch (Exception ignored) {
            // raw body kept in getMessage()
        }
        return new String[] {code, message};
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…";
    }
}
