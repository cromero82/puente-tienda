package com.infinitesoft.puente_tienda.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EgresoCandidatoAlertaDto {
    private Long id;
    private BigDecimal valor;
    private LocalDate fecha;
    private String descripcion;
    private Integer origenFondosId;
    private String proveedorNombre;
    private String personaNombre;
}
