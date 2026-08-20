package com.infinitesoft.puente_tienda.util;

/**
 * Ofusca PII en logs (nombres, referencias de cuenta, emails, message-id).
 * No usar para persistencia ni respuestas API.
 */
public final class LogMask {

    private LogMask() {}

    public static String nombre(String raw) {
        if (raw == null || raw.isBlank()) {
            return "-";
        }
        String[] parts = raw.trim().split("\\s+");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                out.append(' ');
            }
            out.append(maskToken(parts[i]));
        }
        return out.toString();
    }

    public static String referenciaCuenta(String raw) {
        if (raw == null || raw.isBlank()) {
            return "-";
        }
        String s = raw.trim();
        if (s.length() <= 2) {
            return "**";
        }
        return s.substring(0, 2) + "**";
    }

    public static String email(String raw) {
        if (raw == null || raw.isBlank()) {
            return "-";
        }
        String s = raw.trim();
        int at = s.indexOf('@');
        if (at <= 0) {
            return maskToken(s);
        }
        String local = s.substring(0, at);
        String domain = s.substring(at);
        if (local.length() <= 1) {
            return "*" + domain;
        }
        return local.charAt(0) + "***" + domain;
    }

    public static String messageId(String raw) {
        if (raw == null || raw.isBlank()) {
            return "-";
        }
        String s = raw.trim();
        if (s.length() <= 8) {
            return "***";
        }
        return "***" + s.substring(s.length() - 8);
    }

    public static String asunto(String raw) {
        if (raw == null || raw.isBlank()) {
            return "-";
        }
        String s = raw.trim().replaceAll("\\s+", " ");
        if (s.length() <= 48) {
            return s;
        }
        return s.substring(0, 48) + "…";
    }

    /**
     * Cuerpo de correo para logs: sin HTML, una línea, recortado si es muy largo.
     */
    public static String textoPlano(String raw) {
        return textoPlano(raw, 1500);
    }

    public static String textoPlano(String raw, int maxChars) {
        if (raw == null || raw.isBlank()) {
            return "-";
        }
        int cap = maxChars < 80 ? 80 : maxChars;
        String s = raw.replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?i)<br\\s*/?>", " ")
                .replaceAll("(?i)</p>", " ")
                .replaceAll("(?i)</div>", " ")
                .replaceAll("<[^>]+>", " ")
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        if (s.isEmpty()) {
            return "-";
        }
        if (s.length() <= cap) {
            return s;
        }
        return s.substring(0, cap) + "… [+" + (s.length() - cap) + " chars]";
    }

    private static String maskToken(String token) {
        if (token == null || token.isEmpty()) {
            return "";
        }
        if (token.length() == 1) {
            return "*";
        }
        if (token.length() == 2) {
            return token.charAt(0) + "*";
        }
        return token.charAt(0) + "***" + token.charAt(token.length() - 1);
    }
}
