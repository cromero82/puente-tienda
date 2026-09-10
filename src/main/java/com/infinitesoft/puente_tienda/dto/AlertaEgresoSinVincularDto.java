package com.infinitesoft.puente_tienda.dto;

import com.infinitesoft.puente_tienda.entities.NotificacionEmailPago;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertaEgresoSinVincularDto {
    private NotificacionEmailPago notificacion;
    private Integer origenFondosOrigenId;
    private Integer origenFondosDestinoId;
    private List<EgresoCandidatoAlertaDto> candidatos;
}
