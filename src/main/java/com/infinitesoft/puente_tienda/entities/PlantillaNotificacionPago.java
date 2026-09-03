package com.infinitesoft.puente_tienda.entities;

import lombok.*;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "plantilla_notificacion_pago")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlantillaNotificacionPago {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String nombre;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String cuerpo;

    /** Legado; preferir metodo_pago.file vía metodoPagoId. */
    @Column(length = 120)
    private String icono;

    @Column(name = "metodo_pago_id")
    private Long metodoPagoId;

    @Column(nullable = false)
    @Builder.Default
    private Boolean activo = true;

    @Column(nullable = false)
    @Builder.Default
    private Integer orden = 0;

    /** INGRESO | EGRESO */
    @Column(length = 20)
    private String naturaleza;

    @Column(name = "origen_fondos_origen_id")
    private Integer origenFondosOrigenId;

    @Column(name = "origen_fondos_destino_id")
    private Integer origenFondosDestinoId;

    @Column(name = "origen_tipo", nullable = false, length = 80)
    @Builder.Default
    private String origenTipo = "MOVIMIENTO BANCO POR IDENTIFICAR";

    @Column(name = "creado_en")
    private LocalDateTime creadoEn;

    @Column(name = "actualizado_en")
    private LocalDateTime actualizadoEn;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (creadoEn == null) {
            creadoEn = now;
        }
        actualizadoEn = now;
        if (activo == null) {
            activo = true;
        }
        if (orden == null) {
            orden = 0;
        }
        if (origenTipo == null || origenTipo.isBlank()) {
            origenTipo = "MOVIMIENTO BANCO POR IDENTIFICAR";
        }
    }

    @PreUpdate
    void onUpdate() {
        actualizadoEn = LocalDateTime.now();
    }
}
