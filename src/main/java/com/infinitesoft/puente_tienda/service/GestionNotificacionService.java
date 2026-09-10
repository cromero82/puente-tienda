package com.infinitesoft.puente_tienda.service;

import com.infinitesoft.puente_tienda.dto.AlertaEgresoSinVincularDto;
import com.infinitesoft.puente_tienda.dto.AlertasEgresoSinVincularResponse;
import com.infinitesoft.puente_tienda.dto.EgresoCandidatoAlertaDto;
import com.infinitesoft.puente_tienda.dto.PlantillaNotificacionRequest;
import com.infinitesoft.puente_tienda.entities.NotificacionEmailPago;
import com.infinitesoft.puente_tienda.entities.PlantillaNotificacionPago;
import com.infinitesoft.puente_tienda.repositories.NotificacionEmailPagoRepository;
import com.infinitesoft.puente_tienda.repositories.PlantillaNotificacionPagoRepository;
import com.infinitesoft.puente_tienda.util.IconosPlantilla;
import com.infinitesoft.puente_tienda.util.LogMask;
import com.infinitesoft.puente_tienda.util.VinculoOperacion;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class GestionNotificacionService {

    private final NotificacionEmailPagoRepository notificacionRepo;
    private final PlantillaNotificacionPagoRepository plantillaRepo;
    private final MovimientoDesdeNotificacionService movimientoDesdeNotificacionService;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public List<NotificacionEmailPago> listar(
            String estadoVista,
            String q,
            Boolean provienePlantillaExtraccion,
            String vinculoOperacion
    ) {
        String query = q == null ? null : q.trim();
        List<NotificacionEmailPago> list;
        if (estadoVista != null && "POR_IDENTIFICAR".equalsIgnoreCase(estadoVista.trim())) {
            list = notificacionRepo.searchPorIdentificar(query);
        } else {
            String estado = (estadoVista == null || estadoVista.isBlank() || "TODAS".equalsIgnoreCase(estadoVista))
                    ? null
                    : estadoVista.trim().toUpperCase();
            list = notificacionRepo.search(estado, query);
        }
        if (provienePlantillaExtraccion != null) {
            list = list.stream()
                    .filter(n -> provienePlantillaExtraccion.equals(n.isProvienePlantillaExtraccion()))
                    .collect(java.util.stream.Collectors.toList());
        }
        if (vinculoOperacion != null && !vinculoOperacion.isBlank()
                && !"TODAS".equalsIgnoreCase(vinculoOperacion.trim())) {
            String vinculo = vinculoOperacion.trim().toUpperCase();
            list = list.stream()
                    .filter(n -> vinculo.equalsIgnoreCase(n.getVinculoOperacion()))
                    .collect(java.util.stream.Collectors.toList());
        }
        return list;
    }

    @Transactional(readOnly = true)
    public List<NotificacionEmailPago> candidatasEgreso(Long egresoId) {
        EgresoSnapshot egreso = cargarEgreso(egresoId);
        LocalDate fecha = egreso.fecha != null ? egreso.fecha : LocalDate.now();
        return notificacionRepo.findCandidatasEgreso(
                egreso.valor,
                fecha.minusDays(7),
                fecha.plusDays(7));
    }

    @Transactional
    public NotificacionEmailPago asociarEgreso(Long notificacionId, Long egresoId) {
        if (egresoId == null) {
            throw new IllegalArgumentException("egresoId requerido");
        }
        NotificacionEmailPago n = notificacionRepo.findById(notificacionId)
                .orElseThrow(() -> new IllegalArgumentException("notificación no encontrada"));
        if (VinculoOperacion.esAsociada(n.getVinculoOperacion())
                || n.getEgresoId() != null
                || n.getHistorialReciboElectronicoId() != null) {
            throw new IllegalArgumentException(
                    "La notificación #" + notificacionId + " ya está asociada a una operación.");
        }
        if (!VinculoOperacion.esPendiente(n.getVinculoOperacion())) {
            throw new IllegalArgumentException(
                    "Esta notificación no aplica para asociar a un egreso (vínculo "
                            + n.getVinculoOperacion() + ").");
        }
        if (n.getClasificacion() != null && !n.getClasificacion().isBlank()) {
            throw new IllegalArgumentException(
                    "La notificación ya fue legalizada; no se puede asociar a un egreso.");
        }
        PlantillaNotificacionPago plantilla = n.getPlantillaNotificacionId() != null
                ? plantillaRepo.findById(n.getPlantillaNotificacionId()).orElse(null)
                : null;
        if (plantilla == null || !movimientoDesdeNotificacionService.esPlantillaDeMovimiento(plantilla)) {
            throw new IllegalArgumentException(
                    "Solo notificaciones de plantilla EGRESO se pueden asociar a un egreso.");
        }
        EgresoSnapshot egreso = cargarEgreso(egresoId);
        if (egreso.notificacionId != null && !egreso.notificacionId.equals(notificacionId)) {
            throw new IllegalArgumentException(
                    "El egreso #" + egresoId + " ya tiene la notificación #" + egreso.notificacionId + ".");
        }
        if (egreso.fromMovimientoId != null && egreso.notificacionId == null) {
            throw new IllegalArgumentException(
                    "El egreso #" + egresoId + " ya fue formalizado desde un movimiento; no se asocia otro correo.");
        }

        notificacionRepo.findFirstByEgresoId(egresoId).ifPresent(otra -> {
            if (!otra.getId().equals(notificacionId)) {
                throw new IllegalArgumentException(
                        "El egreso #" + egresoId + " ya está ligado a la notificación #" + otra.getId() + ".");
            }
        });

        movimientoDesdeNotificacionService.anularParPorIdentificarYSellarEgreso(n.getId(), egresoId);

        n.setEgresoId(egresoId);
        n.setVinculoOperacion(VinculoOperacion.ASOCIADA);
        if (!"ARCHIVADA".equalsIgnoreCase(n.getEstadoVista())) {
            n.setEstadoVista("MOSTRADA");
        }
        NotificacionEmailPago saved = notificacionRepo.save(n);

        entityManager.createNativeQuery(
                        "UPDATE egreso SET notificacion_email_pago_id = :nid "
                                + "WHERE id = :eid AND notificacion_email_pago_id IS NULL")
                .setParameter("nid", saved.getId())
                .setParameter("eid", egresoId)
                .executeUpdate();
        entityManager.createNativeQuery(
                        "UPDATE egreso SET descripcion = CASE "
                                + "WHEN descripcion IS NULL OR BTRIM(descripcion) = '' THEN :tag "
                                + "WHEN descripcion LIKE :likeTag THEN descripcion "
                                + "ELSE descripcion || ' · ' || :tag END "
                                + "WHERE id = :eid")
                .setParameter("tag", "Notif #" + saved.getId())
                .setParameter("likeTag", "%Notif #" + saved.getId() + "%")
                .setParameter("eid", egresoId)
                .executeUpdate();

        log.info("BD notificacion_email_pago ASOCIADA egreso id={} egresoId={}", saved.getId(), egresoId);
        return saved;
    }

    @Transactional(readOnly = true)
    public AlertasEgresoSinVincularResponse listarAlertasEgresoSinVincular() {
        List<NotificacionEmailPago> pendientes = notificacionRepo.findPendientesEgresoSinMovimiento();
        List<AlertaEgresoSinVincularDto> items = new java.util.ArrayList<>();
        for (NotificacionEmailPago n : pendientes) {
            PlantillaNotificacionPago plantilla = n.getPlantillaNotificacionId() != null
                    ? plantillaRepo.findById(n.getPlantillaNotificacionId()).orElse(null)
                    : null;
            List<EgresoCandidatoAlertaDto> candidatos =
                    movimientoDesdeNotificacionService.listarEgresosCandidatos(n, plantilla);
            if (candidatos.isEmpty()) {
                continue;
            }
            items.add(AlertaEgresoSinVincularDto.builder()
                    .notificacion(n)
                    .origenFondosOrigenId(plantilla != null ? plantilla.getOrigenFondosOrigenId() : null)
                    .origenFondosDestinoId(plantilla != null ? plantilla.getOrigenFondosDestinoId() : null)
                    .candidatos(candidatos)
                    .build());
        }
        return AlertasEgresoSinVincularResponse.builder()
                .count(items.size())
                .items(items)
                .build();
    }

    /**
     * El usuario confirma que el correo no corresponde a un egreso ya registrado:
     * se crea entonces el traslado QR → Sin Clasificar.
     */
    @Transactional
    public NotificacionEmailPago enviarABolsa(Long notificacionId) {
        NotificacionEmailPago n = notificacionRepo.findById(notificacionId)
                .orElseThrow(() -> new IllegalArgumentException("notificación no encontrada"));
        if (VinculoOperacion.esAsociada(n.getVinculoOperacion()) || n.getEgresoId() != null) {
            throw new IllegalArgumentException(
                    "La notificación #" + notificacionId + " ya está asociada a un egreso.");
        }
        if (!VinculoOperacion.esPendiente(n.getVinculoOperacion())) {
            throw new IllegalArgumentException(
                    "Esta notificación no aplica para enviar a Sin Clasificar (vínculo "
                            + n.getVinculoOperacion() + ").");
        }
        if (n.getClasificacion() != null && !n.getClasificacion().isBlank()) {
            throw new IllegalArgumentException(
                    "La notificación ya fue legalizada.");
        }
        PlantillaNotificacionPago plantilla = n.getPlantillaNotificacionId() != null
                ? plantillaRepo.findById(n.getPlantillaNotificacionId()).orElse(null)
                : null;
        if (plantilla == null || !movimientoDesdeNotificacionService.esPlantillaDeMovimiento(plantilla)) {
            throw new IllegalArgumentException(
                    "Solo notificaciones de plantilla EGRESO se envían a Sin Clasificar.");
        }
        boolean creado = movimientoDesdeNotificacionService.registrarSiAplica(n, plantilla, true);
        if (!"ARCHIVADA".equalsIgnoreCase(n.getEstadoVista())) {
            n.setEstadoVista("MOSTRADA");
        }
        NotificacionEmailPago saved = notificacionRepo.save(n);
        log.info("BD notificacion_email_pago enviar-a-bolsa id={} movimientoCreado={}",
                saved.getId(), creado);
        return saved;
    }

    private EgresoSnapshot cargarEgreso(Long egresoId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(
                        "SELECT id, valor, fecha, notificacion_email_pago_id, from_movimiento_origen_fondos_id "
                                + "FROM egreso WHERE id = :id")
                .setParameter("id", egresoId)
                .getResultList();
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("Egreso no encontrado: " + egresoId);
        }
        Object[] row = rows.get(0);
        EgresoSnapshot s = new EgresoSnapshot();
        s.id = ((Number) row[0]).longValue();
        s.valor = row[1] instanceof BigDecimal ? (BigDecimal) row[1] : new BigDecimal(row[1].toString());
        if (row[2] instanceof java.sql.Date) {
            s.fecha = ((java.sql.Date) row[2]).toLocalDate();
        } else if (row[2] instanceof LocalDate) {
            s.fecha = (LocalDate) row[2];
        }
        s.notificacionId = row[3] != null ? ((Number) row[3]).longValue() : null;
        s.fromMovimientoId = row[4] != null ? ((Number) row[4]).longValue() : null;
        return s;
    }

    private static final class EgresoSnapshot {
        Long id;
        BigDecimal valor;
        LocalDate fecha;
        Long notificacionId;
        Long fromMovimientoId;
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
                .icono(normalizeIconoOptional(req.getIcono()))
                .metodoPagoId(req.getMetodoPagoId())
                .activo(req.getActivo() == null || req.getActivo())
                .orden(req.getOrden() != null ? req.getOrden() : siguienteOrden())
                .naturaleza(normalizeNaturaleza(req.getNaturaleza()))
                .origenFondosOrigenId(req.getOrigenFondosOrigenId())
                .origenFondosDestinoId(req.getOrigenFondosDestinoId())
                .origenTipo(defaultOrigenTipo(req.getOrigenTipo()))
                .build();
        PlantillaNotificacionPago saved = plantillaRepo.save(p);
        log.info("BD plantilla_notificacion_pago INSERT id={} nombre={} metodoPagoId={} icono={}",
                saved.getId(), saved.getNombre(), saved.getMetodoPagoId(), saved.getIcono());
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
        p.setIcono(normalizeIconoOptional(req.getIcono()));
        p.setMetodoPagoId(req.getMetodoPagoId());
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
        log.info("BD plantilla_notificacion_pago UPDATE id={} nombre={} metodoPagoId={} icono={} cuerpoLen={}",
                saved.getId(), saved.getNombre(), saved.getMetodoPagoId(), saved.getIcono(),
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

    private static String normalizeIconoOptional(String icono) {
        if (icono == null || icono.isBlank()) {
            return null;
        }
        String v = icono.trim();
        if (!IconosPlantilla.isAllowed(v)) {
            // Permitir null / legado no listado: no bloquear si el icono ya viene de metodo_pago
            return v;
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
