-- Fotografias, etapa E2 (docs/experiences-api.md §12). No máximo uma por experiência.
-- O ficheiro vive no volume de fotografias, com o nome <id>.jpg; aqui ficam o dono, o estado e as dimensões.

CREATE TABLE media (
	-- UUID aleatório gerado pelo serviço (opaco: não revela a data nem a ordem).
	id            uuid        PRIMARY KEY,
	owner_id      uuid        NOT NULL,
	-- PENDING: enviada, ainda sem experiência (invisível); ATTACHED: servida; DELETED: à espera de limpeza.
	status        varchar(10) NOT NULL,
	experience_id uuid        REFERENCES experience (id),
	width         integer     NOT NULL,
	height        integer     NOT NULL,
	size_bytes    integer     NOT NULL,
	created_at    timestamptz NOT NULL DEFAULT now(),
	attached_at   timestamptz,
	deleted_at    timestamptz,
	CONSTRAINT media_status_values CHECK (status IN ('PENDING', 'ATTACHED', 'DELETED')),
	CONSTRAINT media_attached_has_experience CHECK (status <> 'ATTACHED' OR experience_id IS NOT NULL),
	CONSTRAINT media_dimensions CHECK (width > 0 AND height > 0 AND size_bytes > 0)
);

-- Limite de fotografias pendentes por pessoa e limpeza.
CREATE INDEX media_owner_pending_idx ON media (owner_id, created_at) WHERE status = 'PENDING';
CREATE INDEX media_cleanup_idx ON media (status, created_at);

-- Fotografia atual da experiência (nula sem fotografia e nas eliminadas).
ALTER TABLE experience ADD COLUMN photo_media_id uuid REFERENCES media (id);
CREATE UNIQUE INDEX experience_photo_uq ON experience (photo_media_id) WHERE photo_media_id IS NOT NULL;
