package com.infinitesoft.puente_tienda.dto;

import lombok.Data;

@Data
public class AsignarConfirmacionRequest {
    private Long notificacionId;
    /**
     * Obligatorio si monto email ≠ monto esperado.
     * El FE debe mostrar modal (esperado / recibido / diferencia) antes de enviar true.
     */
    private Boolean confirmarMontoDistinto;
    /**
     * OF desde el que sale el efectivo de la devolución (sobrepago).
     * Obligatorio cuando recibido &gt; esperado y confirmarMontoDistinto=true.
     */
    private Integer origenFondosDevolucionId;
}
