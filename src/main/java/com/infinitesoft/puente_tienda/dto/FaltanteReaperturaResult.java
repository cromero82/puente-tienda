package com.infinitesoft.puente_tienda.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Ticket reabierto tras faltante QR (sin CxC: el cajero lo abre con el modal Generar crédito).
 */
@Data
@Builder
public class FaltanteReaperturaResult {
    private Long ticketId;
    private Long reciboId;
    private Long sesionId;
    private Long metodoPagoId;
    private Long historialElectronicoId;
    private BigDecimal totalTicket;
    private BigDecimal montoRecibido;
    private BigDecimal faltante;
}
