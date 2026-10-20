-- Esquema do collections-service (docs/collections-api.md §2 e §11).
-- Sem chaves estrangeiras para outros serviços: owner_id e experience_id são referências por id.
-- Sem dados de demonstração.

CREATE TABLE collection (
	-- UUID versão 7 gerado pelo PostgreSQL 18 (ordenado pelo instante de criação).
	id         uuid        PRIMARY KEY DEFAULT uuidv7(),
	owner_id   uuid        NOT NULL,
	name       varchar(60) NOT NULL,
	created_at timestamptz NOT NULL DEFAULT now(),
	updated_at timestamptz NOT NULL DEFAULT now(),
	CONSTRAINT collection_name_length CHECK (char_length(name) BETWEEN 1 AND 60)
);

-- Nome único por dono, sem distinguir maiúsculas/minúsculas (também com pedidos simultâneos).
CREATE UNIQUE INDEX collection_owner_name_uq ON collection (owner_id, lower(name));
-- As minhas listas, mais recentes primeiro.
CREATE INDEX collection_owner_recent_idx ON collection (owner_id, created_at DESC, id DESC);

-- Um item por (lista, experiência): a chave primária impede duplicados com pedidos simultâneos.
-- Eliminar a lista elimina as suas referências (nunca as experiências, que vivem noutro serviço).
CREATE TABLE collection_item (
	collection_id uuid        NOT NULL REFERENCES collection (id) ON DELETE CASCADE,
	experience_id uuid        NOT NULL,
	added_at      timestamptz NOT NULL DEFAULT now(),
	CONSTRAINT collection_item_pk PRIMARY KEY (collection_id, experience_id)
);

-- Itens de uma lista, guardados mais recentemente primeiro.
CREATE INDEX collection_item_recent_idx ON collection_item (collection_id, added_at DESC, experience_id DESC);

-- Tentativas de criação (idempotência, docs/collections-api.md §5.2). A lista é inserida depois do registo, na
-- mesma transação, por isso a referência só é verificada no fim; quando a lista é eliminada fica null, para a
-- repetição da chave responder COLLECTION_DELETED em vez de recriar a lista.
CREATE TABLE collection_request (
	owner_id        uuid        NOT NULL,
	idempotency_key uuid        NOT NULL,
	request_hash    char(64)    NOT NULL,
	collection_id   uuid        REFERENCES collection (id) ON DELETE SET NULL DEFERRABLE INITIALLY DEFERRED,
	created_at      timestamptz NOT NULL DEFAULT now(),
	CONSTRAINT collection_request_pk PRIMARY KEY (owner_id, idempotency_key)
);

CREATE INDEX collection_request_created_idx ON collection_request (created_at);
CREATE INDEX collection_request_collection_idx ON collection_request (collection_id);
