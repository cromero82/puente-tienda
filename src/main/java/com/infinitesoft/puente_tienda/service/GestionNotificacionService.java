package com.infinitesoft.puente_tienda.service;

import com.infinitesoft.puente_tienda.dto.PlantillaNotificacionRequest;
import com.infinitesoft.puente_tienda.entities.NotificacionEmailPago;
import com.infinitesoft.puente_tienda.entities.PlantillaNotificacionPago;
import com.infinitesoft.puente_tienda.repositories.NotificacionEmailPagoRepository;
import com.infinitesoft.puente_tienda.repositories.PlantillaNotificacionPagoRepository;
import com.infinitesoft.puente_tienda.util.IconosPlantilla;
import com.infinitesoft.puente_tienda.util.LogMask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class GestionNotificacionService {

    private final NotificacionEmailPagoRepository notificacionRepo;
    private final PlantillaNotificacionPagoRepository plantillaRepo;
    private final MovimientoDesdeNotificacionService movimientoDesdeNotificacionService;

    @Transactional(readOnly = true)
    public List<NotificacionEmailPago> listar(String estadoVista, String q) {
        String query = q == null ? null : q.trim();
        if (estadoVista != null && "POR_IDENTIFICAR".equalsIgnoreCase(estadoVista.trim())) {
            return notificacionRepo.searchPorIdentificar(query);
        }
        String estado = (estadoVista == null || estadoVista.isBlank() || "TODAS".equalsIgnoreCase(estadoVista))
                ? null
                : estadoVista.trim().toUpperCase();
        return notificacionRepo.search(estado, query);
    }

    @Transactional
    public NotificacionEmailPago archivar(Long id) {
        NotificacionEmailPago n = notificacionRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("notificación no encontrada"));
        n.setEstadoVista("ARCHIVADA");
        NotificacionEmailPago saved = notificacionRepo.save(n);
        log.info("BD notificacion_email_pago ARCHIVADA id={} pagador={} ref={}",
                saved.getId(),
                LogMask.nombre(saved.getNombrePagador()),
                LogMask.referenciaCuenta(saved.getReferenciaCuenta()));
        return saved;
    }

    /**
     * Legaliza un movimiento bancario por identificar (vale, anticipo, personal, gasto).
     * Si la notificación ya generó ledger en la bolsa (p.ej. «Para ordenar»),
     * crea un TRASLADO {@code LEGALIZACION_NOTIFICACION} hacia el OF destino.
     * Archivar no borra esa responsabilidad.
     */
    @Transactional
    public NotificacionEmailPago legalizar(
            Long id,
            String clasificacion,
            String observacion,
            Integer origenFondosDestinoId
    ) {
        NotificacionEmailPago n = notificacionRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("notificación no encontrada"));
        String c = clasificacion == null ? "" : clasificacion.trim().toUpperCase();
        switch (c) {
            case "VALE_EMPLEADO":
            case "ANTICIPO_SALARIO":
            case "CUENTA_PERSONAL":
            case "GASTO_NEGOCIO":
            case "OTRO_LEGALIZADO":
                break;
            default:
                throw new IllegalArgumentException(
                        "clasificación inválida: " + clasificacion
                                + " (use VALE_EMPLEADO|ANTICIPO_SALARIO|CUENTA_PERSONAL|GASTO_NEGOCIO|OTRO_LEGALIZADO)");
        }

        PlantillaNotificacionPago plantilla = null;
        if (n.getPlantillaNotificacionId() != null) {
            plantilla = plantillaRepo.findById(n.getPlantillaNotificacionId()).orElse(null);
        }

        boolean movio = movimientoDesdeNotificacionService.legalizarEnLedger(
                n, plantilla, c, origenFondosDestinoId, observacion);

        n.setClasificacion(c);
        n.setClasificacionObservacion(observacion);
        n.setClasificadoEn(java.time.LocalDateTime.now());
        if (!"ARCHIVADA".equalsIgnoreCase(n.getEstadoVista())) {
            n.setEstadoVista("MOSTRADA");
        }
        NotificacionEmailPago saved = notificacionRepo.save(n);
        log.info("BD notificacion_email_pago LEGALIZADA id={} clasificacion={} ledgerMovido={}",
                saved.getId(), saved.getClasificacion(), movio);
        return saved;
    }

    @Transactional
    public void eliminar(Long id) {
        NotificacionEmailPago n = notificacionRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("notificación no encontrada"));
        log.info("BD notificacion_email_pago DELETE id={} estadoVista={} historialId={} pagador={}",
                n.getId(),
                n.getEstadoVista(),
                n.getHistorialReciboElectronicoId(),
                LogMask.nombre(n.getNombrePagador()));
        notificacionRepo.deleteById(id);
        log.info("BD notificacion_email_pago DELETE ok id={}", id);
    }

    @Transactional(readOnly = true)
    public List<PlantillaNotificacionPago> listarPlantillas() {
        return plantillaRepo.findAllByOrderByOrdenAscIdAsc();
    }

    @Transactional
    public PlantillaNotificacionPago crearPlantilla(PlantillaNotificacionRequest req) {
        String nombre = requireNombre(req.getNombre());
        if (plantillaRepo.findByNombreIgnoreCase(nombre).isPresent()) {
            throw new IllegalArgumentException("ya existe una plantilla con nombre " + nombre);
        }
        PlantillaNotificacionPago p = PlantillaNotificacionPago.builder()
                .nombre(nombre)
                .cuerpo(requireCuerpo(req.getCuerpo()))
                .icono(normalizeIcono(req.getIcono()))
                .activo(req.getActivo() == null || req.getActivo())
                .orden(req.getOrden() != null ? req.getOrden() : siguienteOrden())
                .naturaleza(normalizeNaturaleza(req.getNaturaleza()))
                .origenFondosOrigenId(req.getOrigenFondosOrigenId())
                .origenFondosDestinoId(req.getOrigenFondosDestinoId())
                .origenTipo(defaultOrigenTipo(req.getOrigenTipo()))
                .build();
        PlantillaNotificacionPago saved = plantillaRepo.save(p);
        log.info("BD plantilla_notificacion_pago INSERT id={} nombre={} icono={}",
                saved.getId(), saved.getNombre(), saved.getIcono());
        movimientoDesdeNotificacionService.registrarPendientesDePlantilla(saved);
        movimientoDesdeNotificacionService.vincularYContabilizarSinPlantilla(saved);
        return saved;
    }

    @Transactional
    public PlantillaNotificacionPago actualizarPlantilla(Long id, PlantillaNotificacionRequest req) {
        PlantillaNotificacionPago p = plantillaRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("plantilla no encontrada"));
        String nombre = requireNombre(req.getNombre());
        if (plantillaRepo.existsByNombreIgnoreCaseAndIdNot(nombre, id)) {
            throw new IllegalArgumentException("ya existe una plantilla con nombre " + nombre);
        }
        p.setNombre(nombre);
        p.setCuerpo(requireCuerpo(req.getCuerpo()));
        p.setIcono(normalizeIcono(req.getIcono()));
        if (req.getActivo() != null) {
            p.setActivo(req.getActivo());
        }
        if (req.getOrden() != null) {
            p.setOrden(req.getOrden());
        }
        p.setNaturaleza(normalizeNaturaleza(req.getNaturaleza()));
        p.setOrigenFondosOrigenId(req.getOrigenFondosOrigenId());
        p.setOrigenFondosDestinoId(req.getOrigenFondosDestinoId());
        p.setOrigenTipo(defaultOrigenTipo(req.getOrigenTipo()));
        PlantillaNotificacionPago saved = plantillaRepo.save(p);
        log.info("BD plantilla_notificacion_pago UPDATE id={} nombre={} icono={} cuerpoLen={}",
                saved.getId(), saved.getNombre(), saved.getIcono(),
                saved.getCuerpo() != null ? saved.getCuerpo().length() : 0);
        movimientoDesdeNotificacionService.registrarPendientesDePlantilla(saved);
        movimientoDesdeNotificacionService.vincularYContabilizarSinPlantilla(saved);
        return saved;
    }

    @Transactional
    public void eliminarPlantilla(Long id) {
        PlantillaNotificacionPago p = plantillaRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("plantilla no encontrada"));
        log.info("BD plantilla_notificacion_pago DELETE id={} nombre={}", p.getId(), p.getNombre());
        plantillaRepo.delete(p);
    }

    public Resource iconoResource(String filename) {
        if (!IconosPlantilla.isAllowed(filename)) {
            throw new IllegalArgumentException("icono no permitido");
        }
        ClassPathResource res = new ClassPathResource(IconosPlantilla.CLASSPATH_DIR + filename);
        if (!res.exists()) {
            throw new IllegalArgumentException("icono no encontrado");
        }
        return res;
    }

    private int siguienteOrden() {
        return plantillaRepo.findAllByOrderByOrdenAscIdAsc().stream()
                .mapToInt(p -> p.getOrden() != null ? p.getOrden() : 0)
                .max()
                .orElse(0) + 1;
    }

    private static String requireNombre(String nombre) {
        if (nombre == null || nombre.isBlank()) {
            throw new IllegalArgumentException("nombre de plantilla requerido");
        }
        return nombre.trim().toUpperCase();
    }

    private static String requireCuerpo(String cuerpo) {
        if (cuerpo == null || cuerpo.isBlank()) {
            throw new IllegalArgumentException("cuerpo de plantilla requerido");
        }
        return cuerpo.trim();
    }

    private static String normalizeIcono(String icono) {
        String v = icono == null || icono.isBlank() ? IconosPlantilla.defaultIcono() : icono.trim();
        if (!IconosPlantilla.isAllowed(v)) {
            throw new IllegalArgumentException("icono no permitido: " + v);
        }
        return v;
    }

    private static String normalizeNaturaleza(String naturaleza) {
        if (naturaleza == null || naturaleza.isBlank()) {
            return null;
        }
        String v = naturaleza.trim().toUpperCase();
        if (!"INGRESO".equals(v) && !"EGRESO".equals(v)) {
            throw new IllegalArgumentException("naturaleza debe ser INGRESO o EGRESO");
        }
        return v;
    }

    private static String defaultOrigenTipo(String origenTipo) {
        if (origenTipo == null || origenTipo.isBlank()) {
            return "MOVIMIENTO BANCO POR IDENTIFICAR";
        }
        return origenTipo.trim();
    }
}
