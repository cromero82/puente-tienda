package com.infinitesoft.puente_tienda.entities;

import lombok.*;

import javax.persistence.*;

@Entity
@Table(name = "metodo_pago")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MetodoPago {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String descripcion;

    private String sigla;

    @Column(name = "file", length = 300)
    private String file;

    @Column(name = "plantilla_notificacion_pago", columnDefinition = "TEXT")
    private String plantillaNotificacionPago;

    @Column(name = "permite_notificacion")
    @Builder.Default
    private Boolean permiteNotificacion = false;
}
