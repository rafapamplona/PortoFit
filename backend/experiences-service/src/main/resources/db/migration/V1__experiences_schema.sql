-- Esquema do experiences-service, etapa E1 (docs/experiences-api.md §2 e §16).
-- Sem chaves estrangeiras para outros serviços: autor, local e atividade são referências por id,
-- validadas pela API ao publicar. Sem dados de demonstração.

-- Normalização para a pesquisa (minúsculas e sem acentos), igual à do catálogo.
-- Só funções nativas, IMMUTABLE, utilizável em índices.
CREATE FUNCTION portofit_normalize(input text) RETURNS text
	LANGUAGE sql
	IMMUTABLE
	PARALLEL SAFE
	RETURNS NULL ON NULL INPUT
AS $$
	SELECT translate(lower(input), 'áàâãäåéèêëíìîïóòôõöúùûüçñý', 'aaaaaaeeeeiiiiooooouuuucny')
$$;

CREATE TABLE experience (
	-- UUID versão 7 gerado pela função nativa do PostgreSQL 18 (ordenado pelo instante de criação).
	id              uuid         PRIMARY KEY DEFAULT uuidv7(),
	-- Referências (ids estáveis de outros serviços).
	author_id       uuid         NOT NULL,
	place_id        uuid         NOT NULL,
	offering_id     uuid         NOT NULL,
	sport_id        varchar(40)  NOT NULL,
	-- Cópias de apresentação do momento da publicação (podem ficar desatualizadas).
	author_username varchar(30)  NOT NULL,
	place_name      varchar(120) NOT NULL,
	place_demo      boolean      NOT NULL,
	offering_name   varchar(120) NOT NULL,
	sport_name      varchar(60)  NOT NULL,
	-- Conteúdo: nulo só depois de eliminada.
	text            text,
	stars           smallint,
	created_at      timestamptz  NOT NULL DEFAULT now(),
	edited_at       timestamptz,
	deleted_at      timestamptz,
	CONSTRAINT experience_content CHECK (
		(deleted_at IS NULL AND text IS NOT NULL AND char_length(text) BETWEEN 1 AND 2000 AND stars BETWEEN 1 AND 5)
		OR (deleted_at IS NOT NULL AND text IS NULL AND stars IS NULL)
	)
);

-- Avaliação vigente por (pessoa, atividade) dentro dos locais pedidos (docs/experiences-api.md §7.4).
CREATE INDEX experience_current_rating_idx ON experience (place_id, author_id, offering_id, created_at DESC, id DESC)
	WHERE deleted_at IS NULL;

-- Listas por ordem recente: global, por local, atividade, modalidade e autor.
CREATE INDEX experience_recent_idx ON experience (created_at DESC, id DESC) WHERE deleted_at IS NULL;
CREATE INDEX experience_place_recent_idx ON experience (place_id, created_at DESC, id DESC) WHERE deleted_at IS NULL;
CREATE INDEX experience_offering_recent_idx ON experience (offering_id, created_at DESC, id DESC) WHERE deleted_at IS NULL;
CREATE INDEX experience_sport_recent_idx ON experience (sport_id, created_at DESC, id DESC) WHERE deleted_at IS NULL;
CREATE INDEX experience_author_recent_idx ON experience (author_id, created_at DESC, id DESC) WHERE deleted_at IS NULL;

-- Tentativas de publicação (idempotência, docs/experiences-api.md §5). A chave primária impede duas
-- publicações com a mesma chave do mesmo autor, também com pedidos simultâneos. A experiência é inserida
-- depois do registo, na mesma transação, por isso a verificação da referência é adiada para o fim.
CREATE TABLE publish_request (
	author_id       uuid        NOT NULL,
	idempotency_key uuid        NOT NULL,
	request_hash    char(64)    NOT NULL,
	experience_id   uuid        NOT NULL REFERENCES experience (id) DEFERRABLE INITIALLY DEFERRED,
	created_at      timestamptz NOT NULL DEFAULT now(),
	CONSTRAINT publish_request_pk PRIMARY KEY (author_id, idempotency_key)
);

CREATE INDEX publish_request_created_idx ON publish_request (created_at);
