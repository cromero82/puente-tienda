package com.infinitesoft.puente_tienda.parser;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extrae campos del cuerpo del correo usando plantilla con placeholders
 * {{nombrePagador}} / {{NOMBRE_PAGADOR}}, {{monto}}, {{referenciaCuenta}}.
 */
public final class EmailPagoParser {

    private EmailPagoParser() {}

    @Data
    @Builder
    public static class ParsedPago {
        private BigDecimal monto;
        private String nombrePagador;
        private String referenciaCuenta;
        private Long metodoPagoId;
    }

    private static final Pattern PLACEHOLDER = Pattern.compile(
            "\\{\\{\\s*(nombrePagador|NOMBRE_PAGADOR|monto|MONTO|referenciaCuenta|REFERENCIA_CUENTA)\\s*\\}\\}");

    public static ParsedPago parse(String cuerpo, String plantilla, Long metodoPagoId) {
        if (cuerpo == null || cuerpo.isBlank()) {
            return null;
        }
        String text = normalize(cuerpo);
        if (plantilla == null || plantilla.isBlank()) {
            return parseHeuristico(text, metodoPagoId);
        }
        String tpl = normalize(plantilla);

        StringBuilder regex = new StringBuilder();
        Matcher m = PLACEHOLDER.matcher(tpl);
        Map<Integer, String> groupNames = new HashMap<>();
        int groupIdx = 1;
        int last = 0;
        while (m.find()) {
            regex.append(flexibleLiteral(tpl.substring(last, m.start())));
            String rawName = m.group(1);
            String canon = canonicalize(rawName);
            groupNames.put(groupIdx, canon);
            switch (canon) {
                case "MONTO":
                    regex.append("([\\d.,]+)");
                    break;
                case "REFERENCIA_CUENTA":
                    regex.append("(\\d{4})");
                    break;
                case "NOMBRE_PAGADOR":
                default:
                    regex.append("(.+?)");
                    break;
            }
            groupIdx++;
            last = m.end();
        }
        regex.append(flexibleLiteral(tpl.substring(last)));

        Pattern compiled = Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher match = compiled.matcher(text);
        if (!match.find()) {
            return parseHeuristico(text, metodoPagoId);
        }

        BigDecimal monto = null;
        String nombre = null;
        String ref = null;
        for (Map.Entry<Integer, String> e : groupNames.entrySet()) {
            String val = match.group(e.getKey()).trim();
            switch (e.getValue()) {
                case "MONTO":
                    monto = parseMonto(val);
                    break;
                case "NOMBRE_PAGADOR":
                    nombre = val;
                    break;
                case "REFERENCIA_CUENTA":
                    ref = val;
                    break;
                default:
                    break;
            }
        }
        if (monto == null) {
            return parseHeuristico(text, metodoPagoId);
        }
        return ParsedPago.builder()
                .monto(monto)
                .nombrePagador(nombre)
                .referenciaCuenta(ref)
                .metodoPagoId(metodoPagoId)
                .build();
    }

    private static String canonicalize(String raw) {
        String u = raw.toUpperCase();
        if (u.contains("MONTO")) {
            return "MONTO";
        }
        if (u.contains("REFERENCIA")) {
            return "REFERENCIA_CUENTA";
        }
        return "NOMBRE_PAGADOR";
    }

    private static ParsedPago parseHeuristico(String text, Long metodoPagoId) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Pattern p = Pattern.compile(
                "(?i)(?:transferencia|pago)\\s+(?:de\\s+([A-ZÁÉÍÓÚÑa-záéíóúñ .'-]{3,80})\\s+)?por\\s+\\$?\\s*([\\d.,]+).*?(?:cuenta\\s*\\*?|\\*)(\\d{4})",
                Pattern.DOTALL);
        Matcher m = p.matcher(text);
        if (m.find()) {
            String nombre = m.group(1) != null ? m.group(1).trim() : null;
            return ParsedPago.builder()
                    .nombrePagador(nombre)
                    .monto(parseMonto(m.group(2)))
                    .referenciaCuenta(m.group(3))
                    .metodoPagoId(metodoPagoId)
                    .build();
        }
        Matcher m2 = Pattern.compile("(?i)\\$?\\s*([\\d]{1,3}(?:[.,]\\d{3})*(?:[.,]\\d{2})?)").matcher(text);
        if (!m2.find()) {
            return null;
        }
        return ParsedPago.builder()
                .monto(parseMonto(m2.group(1)))
                .metodoPagoId(metodoPagoId)
                .build();
    }

    private static String flexibleLiteral(String lit) {
        if (lit == null || lit.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < lit.length()) {
            if (Character.isWhitespace(lit.charAt(i))) {
                out.append("\\s+");
                while (i < lit.length() && Character.isWhitespace(lit.charAt(i))) {
                    i++;
                }
            } else {
                out.append(Pattern.quote(String.valueOf(lit.charAt(i))));
                i++;
            }
        }
        return out.toString();
    }

    private static String normalize(String s) {
        return s.replace('\u00A0', ' ')
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll("\\n+", "\n")
                .trim();
    }

    static BigDecimal parseMonto(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().replace("$", "").replace(" ", "");
        if (s.matches(".*\\.\\d{3}(,\\d+)?$") || s.matches("^\\d{1,3}(\\.\\d{3})+(,\\d+)?$")) {
            s = s.replace(".", "").replace(",", ".");
        } else if (s.contains(",") && !s.contains(".")) {
            s = s.replace(",", ".");
        } else {
            s = s.replace(",", "");
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
