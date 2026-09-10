package com.infinitesoft.puente_tienda.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertasEgresoSinVincularResponse {
    private int count;
    private List<AlertaEgresoSinVincularDto> items;
}
