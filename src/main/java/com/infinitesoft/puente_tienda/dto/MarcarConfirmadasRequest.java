package com.infinitesoft.puente_tienda.dto;

import lombok.Data;

import java.util.List;

@Data
public class MarcarConfirmadasRequest {
    /** IDs de historial_recibos_electronicos ya en CONFIRMADA (post countdown). */
    private List<Long> historialElectronicoIds;
}
