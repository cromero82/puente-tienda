package com.infinitesoft.puente_tienda.util;

import com.infinitesoft.puente_tienda.entities.PlantillaNotificacionPago;

/**
 * Vínculo de una notificación con una operación POS (ticket/egreso).
 * Distinto de {@code estado_vista} (ciclo de vista: PENDIENTE / MOSTRADA / ARCHIVADA).
 */
public final class VinculoOperacion {

    public static final String NO_APLICA = "NO_APLICA";
    public static final String PENDIENTE = "PENDIENTE";
    public static final String ASOCIADA = "ASOCIADA";

    private VinculoOperacion() {}

    /**
     * Sin plantilla, o plantilla que no es INGRESO ni EGRESO → {@link #NO_APLICA}.
     * Naturaleza vacía (legado) se trata como EGRESO, igual que el ledger.
     */
    public static String inicial(PlantillaNotificacionPago plantilla) {
        if (plantilla == null) {
            return NO_APLICA;
        }
        String nat = plantilla.getNaturaleza() != null
                ? plantilla.getNaturaleza().trim().toUpperCase()
                : "";
        if ("INGRESO".equals(nat) || "EGRESO".equals(nat) || nat.isEmpty()) {
            return PENDIENTE;
        }
        return NO_APLICA;
    }

    public static boolean esAsociada(String vinculo) {
        return ASOCIADA.equalsIgnoreCase(vinculo);
    }

    public static boolean esPendiente(String vinculo) {
        return PENDIENTE.equalsIgnoreCase(vinculo);
    }
}
