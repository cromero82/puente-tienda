package com.infinitesoft.puente_tienda.entities;

import lombok.*;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "ticket_sin_notificacion")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TicketSinNotificacion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "historial_recibo_id", nullable = false)
    private Long historialReciboId;

    @Column(name = "historial_recibo_electronico_id")
    private Long historialReciboElectronicoId;

    @Column(name = "numero_venta", length = 40)
    private String numeroVenta;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal valor;

    @Column(nullable = false)
    private LocalDateTime fecha;

    @Column(length = 200)
    private String persona;

    @Column(name = "marcado_en", nullable = false)
    private LocalDateTime marcadoEn;

    @PrePersist
    void onCreate() {
        if (marcadoEn == null) {
            marcadoEn = LocalDateTime.now();
        }
    }
}
