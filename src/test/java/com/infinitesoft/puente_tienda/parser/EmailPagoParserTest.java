package com.infinitesoft.puente_tienda.parser;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class EmailPagoParserTest {

    private static final String PLANTILLA =
            "Bancolombia: CARLOS, recibiste una transferencia de {{nombrePagador}} por {{monto}} "
                    + "en tu cuenta *{{referenciaCuenta}} conectada a la llave 86070384 el 31/07/26 a las 20:34. "
                    + "Con llaves es de una y gratis. Dudas al 018000912345";

    private static final String DUMP =
            "Logo Bancolombia [http://bancolombia-email-wsuite.s3.amazonaws.com/templates/605ce7f68622a5425353ea51/img/header-logo.png] "
                    + "yellow-icon [https://bancolombia-email-wsuite.s3.amazonaws.com/templates/66d9c32445bd2366ccb1b308/img/chulo.png] "
                    + "Â¡Listo! Todo saliÃ³ bien con tus movimientos Bancolombia: CARLOS, recibiste una transferencia de "
                    + "CARLOS ROMERO por $2,000.00 en tu cuenta *3861 conectada a la llave 86070384 el 08/08/26 a las 17:12. "
                    + "Con llaves es de una y gratis. Dudas al 018000912345. Icon 1 "
                    + "[https://bancolombia-email-wsuite.s3.amazonaws.com/templates/5fca95d6ce3ebf33a8d8c017/img/lamp.png] "
                    + "Para que enviar plata siempre sea un Ã©xito, estos consejos de seguridad: "
                    + "Vigilado Superintendencia Financiera de Colombia";

    @Test
    void extraeSoloFragmentoDePlantillaYCampos() {
        EmailPagoParser.ParsedPago parsed = EmailPagoParser.parse(DUMP, PLANTILLA, 2L);
        assertNotNull(parsed);
        assertEquals(new BigDecimal("2000.00"), parsed.getMonto());
        assertEquals("CARLOS ROMERO", parsed.getNombrePagador());
        assertEquals("3861", parsed.getReferenciaCuenta());
        assertNotNull(parsed.getFragmento());
        assertTrue(parsed.getFragmento().contains("recibiste una transferencia de CARLOS ROMERO"));
        assertTrue(parsed.getFragmento().contains("$2,000.00") || parsed.getFragmento().contains("2,000.00"));
        assertFalse(parsed.getFragmento().toLowerCase().contains("logo bancolombia"));
        assertFalse(parsed.getFragmento().toLowerCase().contains("vigilado"));
        assertFalse(parsed.getFragmento().contains("http"));

        String cuerpoTexto = EmailPagoParser.resolverCuerpoTexto(EmailPagoParser.toPlainText(DUMP), parsed);
        assertEquals(parsed.getFragmento(), cuerpoTexto);
        assertTrue(cuerpoTexto.length() < 400);
    }

    @Test
    void toPlainTextCorrigeMojibakeYQuitaUrls() {
        String plain = EmailPagoParser.toPlainText(DUMP);
        assertTrue(plain.contains("¡Listo!") || plain.contains("Listo!"));
        assertTrue(plain.contains("salió") || plain.contains("salio"));
        assertFalse(plain.contains("http://"));
        assertFalse(plain.contains("s3.amazonaws.com"));
    }

    @Test
    void sinPlantillaIgualRecortaBloqueTransferencia() {
        EmailPagoParser.ParsedPago parsed = EmailPagoParser.parse(DUMP, null, 2L);
        assertNotNull(parsed);
        assertEquals(new BigDecimal("2000.00"), parsed.getMonto());
        assertNotNull(parsed.getFragmento());
        assertTrue(parsed.getFragmento().toLowerCase().contains("recibiste una transferencia"));
        assertFalse(parsed.getFragmento().toLowerCase().contains("vigilado"));
    }

    private static final String PLANTILLA_QR =
            "Bancolombia: AUTOSERVICIO INFINITO, recibiste un pago de {{nombrePagador}} por {{monto}} "
                    + "en tu cuenta *{{referenciaCuenta}} conectado a la llave 0072617673 el 09/08/2026 a las 10:53. "
                    + "Con codigo QR es facil y de una. Dudas al 018000912345.";

    private static final String DUMP_QR =
            "Logo Bancolombia [http://example.com/logo.png] Â¡Listo! Todo saliÃ³ bien con tus movimientos "
                    + "Bancolombia: AUTOSERVICIO INFINITO, recibiste un pago de CARLOS ANDRES ROMERO PARRADO "
                    + "por $2,000.00 en tu cuenta *7980 conectado a la llave 0072617673 el 09/08/2026 a las 10:53. "
                    + "Con codigo QR es facil y de una. Dudas al 018000912345. "
                    + "Para que enviar plata siempre sea un Ã©xito Vigilado Superintendencia";

    @Test
    void extraePlantillaQrOficial() {
        EmailPagoParser.ParsedPago parsed = EmailPagoParser.parse(DUMP_QR, PLANTILLA_QR, 2L);
        assertNotNull(parsed);
        assertEquals(new BigDecimal("2000.00"), parsed.getMonto());
        assertEquals("CARLOS ANDRES ROMERO PARRADO", parsed.getNombrePagador());
        assertEquals("7980", parsed.getReferenciaCuenta());
        assertTrue(parsed.getFragmento().toLowerCase().contains("recibiste un pago de"));
        assertTrue(parsed.getFragmento().toLowerCase().contains("codigo qr"));
        assertFalse(parsed.getFragmento().toLowerCase().contains("vigilado"));
    }

    @Test
    void qrNoCoincideConPlantillaBreve() {
        EmailPagoParser.ParsedPago only = EmailPagoParser.parseTemplateOnly(DUMP_QR, PLANTILLA, 2L);
        assertNull(only);
        EmailPagoParser.ParsedPago qr = EmailPagoParser.parseTemplateOnly(DUMP_QR, PLANTILLA_QR, 2L);
        assertNotNull(qr);
        assertEquals("7980", qr.getReferenciaCuenta());
    }

    @Test
    void extraeCompraLuloConPlantillaEgreso() {
        String plantilla = "Realizaste una compra en {{nombrePagador}} por {{monto}}";
        String cuerpo = "Realizaste una compra en DROGUERIA FARMASTER N por $10,000.00";
        EmailPagoParser.ParsedPago parsed = EmailPagoParser.parseTemplateOnly(cuerpo, plantilla, 2L);
        assertNotNull(parsed);
        assertEquals(new BigDecimal("10000.00"), parsed.getMonto());
        assertEquals("DROGUERIA FARMASTER N", parsed.getNombrePagador());
    }
}
