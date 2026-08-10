package com.infinitesoft.puente_tienda.parser;

import lombok.Builder;
import lombok.Data;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extrae campos del cuerpo del correo usando plantilla con placeholders
 * {{nombrePagador}} / {{NOMBRE_PAGADOR}}, {{monto}}, {{referenciaCuenta}}.
 * También recorta el fragmento de plantilla para persistir en cuerpo_texto.
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
        /** Tramo del correo que coincide con la plantilla (o bloque de transferencia). */
        private String fragmento;
        private Long plantillaId;
        private String plantillaNombre;
        private String plantillaIcono;
    }

    private static final Pattern PLACEHOLDER = Pattern.compile(
            "\\{\\{\\s*(nombrePagador|NOMBRE_PAGADOR|monto|MONTO|referenciaCuenta|REFERENCIA_CUENTA|_DATE_|_TIME_)\\s*\\}\\}");

    private static final Pattern BLOQUE_TRANSFERENCIA = Pattern.compile(
            "(?i)(?:Bancolombia\\s*:\\s*)?.{0,80}?recibiste (?:una transferencia|un pago) de.{10,400}?(?:Dudas al\\s*[\\d.\\s]+\\.?|(?:de una y )?gratis\\.|codigo QR.{0,60})");

    public static ParsedPago parse(String cuerpo, String plantilla, Long metodoPagoId) {
        ParsedPago fromTpl = parseTemplateOnly(cuerpo, plantilla, metodoPagoId);
        if (fromTpl != null) {
            return fromTpl;
        }
        if (cuerpo == null || cuerpo.isBlank()) {
            return null;
        }
        return parseHeuristico(toPlainText(cuerpo), metodoPagoId);
    }

    /** Match estricto de una plantilla (sin heurística). Null si no coincide. */
    public static ParsedPago parseTemplateOnly(String cuerpo, String plantilla, Long metodoPagoId) {
        if (cuerpo == null || cuerpo.isBlank() || plantilla == null || plantilla.isBlank()) {
            return null;
        }
        String text = toPlainText(cuerpo);
        if (text.isBlank()) {
            return null;
        }
        String tpl = softenTemplate(toPlainText(plantilla));

        StringBuilder regex = new StringBuilder();
        Matcher m = PLACEHOLDER.matcher(tpl);
        Map<Integer, String> groupNames = new HashMap<>();
        int groupIdx = 1;
        int last = 0;
        while (m.find()) {
            regex.append(flexibleLiteral(tpl.substring(last, m.start())));
            String canon = canonicalize(m.group(1));
            switch (canon) {
                case "MONTO":
                    groupNames.put(groupIdx++, canon);
                    regex.append("(?:\\$\\s*)?([\\d.,]+)");
                    break;
                case "REFERENCIA_CUENTA":
                    groupNames.put(groupIdx++, canon);
                    regex.append("(\\d{4})");
                    break;
                case "_DATE_":
                    regex.append("(?:\\d{1,2}/\\d{1,2}/\\d{2,4})");
                    break;
                case "_TIME_":
                    regex.append("(?:\\d{1,2}:\\d{2}(?::\\d{2})?)");
                    break;
                case "NOMBRE_PAGADOR":
                default:
                    groupNames.put(groupIdx++, canon);
                    regex.append("(.+?)");
                    break;
            }
            last = m.end();
        }
        regex.append(flexibleLiteral(tpl.substring(last)));

        Pattern compiled = Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher match = compiled.matcher(text);
        if (!match.find()) {
            return null;
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
            return null;
        }
        return ParsedPago.builder()
                .monto(monto)
                .nombrePagador(nombre)
                .referenciaCuenta(ref)
                .metodoPagoId(metodoPagoId)
                .fragmento(compact(match.group(0)))
                .build();
    }

    /** Texto plano para parseo / cuerpo_texto: sin HTML, URLs de imágenes ni mojibake. */
    public static String toPlainText(String htmlOrText) {
        if (htmlOrText == null || htmlOrText.isBlank()) {
            return "";
        }
        String s = htmlOrText;
        s = s.replaceAll("(?is)<script[^>]*>.*?</script>", " ");
        s = s.replaceAll("(?is)<style[^>]*>.*?</style>", " ");
        s = s.replaceAll("(?is)<br\\s*/?>", "\n");
        s = s.replaceAll("(?is)</p>", "\n");
        s = s.replaceAll("(?is)</div>", "\n");
        s = s.replaceAll("(?is)<[^>]+>", " ");
        s = HtmlUtils.htmlUnescape(s);
        s = s.replaceAll("\\[[^\\]]*https?://[^\\]]+\\]", " ");
        s = s.replaceAll("(?i)\\[[^\\]]*(?:cid:|logo|icon)[^\\]]*\\]", " ");
        s = s.replaceAll("https?://\\S+", " ");
        s = fixMojibake(s);
        s = s.replace('\u00A0', ' ');
        s = s.replaceAll("[ \\t\\x0B\\f\\r]+", " ");
        s = s.replaceAll("\\n+", " ");
        s = s.replaceAll(" +", " ");
        return s.trim();
    }

    /**
     * Solo el tramo de la plantilla / transferencia. Si no hay match, un recorte corto
     * (nunca el dump completo del correo).
     */
    public static String resolverCuerpoTexto(String cuerpoLimpio, ParsedPago parsed) {
        if (parsed != null && parsed.getFragmento() != null && !parsed.getFragmento().isBlank()) {
            return parsed.getFragmento();
        }
        String bloque = extraerBloqueTransferencia(cuerpoLimpio);
        if (bloque != null && !bloque.isBlank()) {
            return bloque;
        }
        if (cuerpoLimpio == null || cuerpoLimpio.isBlank()) {
            return "";
        }
        String compact = compact(cuerpoLimpio);
        if (compact.length() <= 500) {
            return compact;
        }
        return compact.substring(0, 500).trim() + "…";
    }

    public static String extraerBloqueTransferencia(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = toPlainText(text);
        Matcher m = BLOQUE_TRANSFERENCIA.matcher(t);
        if (m.find()) {
            return compact(m.group());
        }
        String lower = t.toLowerCase();
        int idx = lower.indexOf("recibiste una transferencia");
        if (idx < 0) {
            idx = lower.indexOf("recibiste un pago");
        }
        if (idx < 0) {
            return null;
        }
        int start = idx;
        int ban = lower.lastIndexOf("bancolombia:", idx);
        if (ban >= 0 && idx - ban < 120) {
            start = ban;
        } else {
            start = Math.max(0, idx - 40);
        }
        int end = Math.min(t.length(), idx + 320);
        int dudas = lower.indexOf("dudas al", idx);
        if (dudas > idx && dudas < end + 40) {
            end = Math.min(t.length(), dudas + 40);
        }
        return compact(t.substring(start, end));
    }

    private static String softenTemplate(String tpl) {
        if (tpl == null) {
            return "";
        }
        return tpl
                .replaceAll("\\b\\d{1,2}/\\d{1,2}/\\d{2,4}\\b", "{{_DATE_}}")
                .replaceAll("\\b\\d{1,2}:\\d{2}(?::\\d{2})?\\b", "{{_TIME_}}");
    }

    private static String canonicalize(String raw) {
        String u = raw.toUpperCase();
        if ("_DATE_".equals(u) || "DATE".equals(u)) {
            return "_DATE_";
        }
        if ("_TIME_".equals(u) || "TIME".equals(u)) {
            return "_TIME_";
        }
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
                    .fragmento(firstNonBlank(extraerBloqueTransferencia(text), compact(m.group(0))))
                    .build();
        }
        Matcher m2 = Pattern.compile("(?i)\\$?\\s*([\\d]{1,3}(?:[.,]\\d{3})*(?:[.,]\\d{2})?)").matcher(text);
        if (!m2.find()) {
            return null;
        }
        return ParsedPago.builder()
                .monto(parseMonto(m2.group(1)))
                .metodoPagoId(metodoPagoId)
                .fragmento(firstNonBlank(extraerBloqueTransferencia(text), null))
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

    private static String fixMojibake(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        if (s.indexOf('\u00C3') < 0 && s.indexOf('\u00C2') < 0) {
            return s;
        }
        try {
            byte[] latin1 = s.getBytes(StandardCharsets.ISO_8859_1);
            String utf8 = new String(latin1, StandardCharsets.UTF_8);
            long badBefore = countMojibake(s);
            long badAfter = countMojibake(utf8);
            if (badAfter < badBefore) {
                return utf8;
            }
        } catch (Exception ignored) {
            // keep original
        }
        return s;
    }

    private static long countMojibake(String s) {
        long n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\u00C3' || c == '\u00C2') {
                n++;
            }
        }
        return n;
    }

    private static String compact(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("\\s+", " ").trim();
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b;
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
