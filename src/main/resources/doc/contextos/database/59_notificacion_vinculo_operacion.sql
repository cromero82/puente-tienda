-- Ciclo de vista (estado_vista) vs vínculo con operación POS (ticket / egreso).
-- vinculo_operacion:
--   NO_APLICA  = sin plantilla, o plantilla que no es INGRESO ni EGRESO
--   PENDIENTE  = plantilla INGRESO/EGRESO aún sin ticket ni egreso
--   ASOCIADA   = ligada a HRE (ticket/abono) o a egreso
-- BD: controlneg_rmx_db

ALTER TABLE notificacion_email_pago
    ADD COLUMN IF NOT EXISTS vinculo_operacion VARCHAR(20);

ALTER TABLE notificacion_email_pago
    ADD COLUMN IF NOT EXISTS egreso_id BIGINT;

ALTER TABLE egreso
    ADD COLUMN IF NOT EXISTS notificacion_email_pago_id BIGINT;

UPDATE notificacion_email_pago n
SET vinculo_operacion = CASE
    WHEN n.historial_recibo_electronico_id IS NOT NULL THEN 'ASOCIADA'
    WHEN EXISTS (
        SELECT 1
        FROM movimiento_origen_fondos m
        JOIN egreso e ON e.from_movimiento_origen_fondos_id = m.id
        WHERE m.id_referencia = n.id
    ) THEN 'ASOCIADA'
    WHEN n.plantilla_notificacion_id IS NOT NULL
         AND EXISTS (
             SELECT 1
             FROM plantilla_notificacion_pago p
             WHERE p.id = n.plantilla_notificacion_id
               AND (
                   UPPER(BTRIM(COALESCE(p.naturaleza, ''))) IN ('INGRESO', 'EGRESO')
                   OR BTRIM(COALESCE(p.naturaleza, '')) = ''
               )
         ) THEN 'PENDIENTE'
    ELSE 'NO_APLICA'
END
WHERE n.vinculo_operacion IS NULL
   OR n.vinculo_operacion NOT IN ('NO_APLICA', 'PENDIENTE', 'ASOCIADA');

UPDATE notificacion_email_pago n
SET egreso_id = e.id
FROM movimiento_origen_fondos m
JOIN egreso e ON e.from_movimiento_origen_fondos_id = m.id
WHERE m.id_referencia = n.id
  AND n.egreso_id IS NULL;

UPDATE egreso e
SET notificacion_email_pago_id = n.id
FROM notificacion_email_pago n
WHERE n.egreso_id = e.id
  AND e.notificacion_email_pago_id IS NULL;

ALTER TABLE notificacion_email_pago
    ALTER COLUMN vinculo_operacion SET DEFAULT 'PENDIENTE';

UPDATE notificacion_email_pago
SET vinculo_operacion = 'PENDIENTE'
WHERE vinculo_operacion IS NULL;

ALTER TABLE notificacion_email_pago
    ALTER COLUMN vinculo_operacion SET NOT NULL;

ALTER TABLE notificacion_email_pago DROP CONSTRAINT IF EXISTS ck_nep_vinculo_operacion;
ALTER TABLE notificacion_email_pago
    ADD CONSTRAINT ck_nep_vinculo_operacion
    CHECK (vinculo_operacion::text = ANY (ARRAY[
        'NO_APLICA'::character varying,
        'PENDIENTE'::character varying,
        'ASOCIADA'::character varying
    ]::text[]));

CREATE UNIQUE INDEX IF NOT EXISTS uq_nep_egreso_id
    ON notificacion_email_pago (egreso_id)
    WHERE egreso_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_egreso_notificacion_email_pago
    ON egreso (notificacion_email_pago_id)
    WHERE notificacion_email_pago_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_nep_vinculo_operacion
    ON notificacion_email_pago (vinculo_operacion);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_nep_egreso'
    ) THEN
        ALTER TABLE notificacion_email_pago
            ADD CONSTRAINT fk_nep_egreso
            FOREIGN KEY (egreso_id) REFERENCES egreso (id) ON DELETE SET NULL;
    END IF;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE '59: skip FK notificacion→egreso: %', SQLERRM;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_egreso_notificacion_email'
    ) THEN
        ALTER TABLE egreso
            ADD CONSTRAINT fk_egreso_notificacion_email
            FOREIGN KEY (notificacion_email_pago_id)
            REFERENCES notificacion_email_pago (id) ON DELETE SET NULL;
    END IF;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE '59: skip FK egreso→notificacion: %', SQLERRM;
END $$;

COMMENT ON COLUMN notificacion_email_pago.estado_vista IS
    'Ciclo de vista en UI: PENDIENTE | MOSTRADA | ARCHIVADA.';
COMMENT ON COLUMN notificacion_email_pago.vinculo_operacion IS
    'Vínculo con operación POS: NO_APLICA | PENDIENTE | ASOCIADA (ticket vía HRE o egreso).';
COMMENT ON COLUMN notificacion_email_pago.egreso_id IS
    'Egreso asociado (formalizado o asociado a posteriori).';
COMMENT ON COLUMN egreso.notificacion_email_pago_id IS
    'Notificación de medio electrónico ligada a este egreso.';
