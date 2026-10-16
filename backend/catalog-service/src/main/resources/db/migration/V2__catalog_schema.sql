-- Esquema do catálogo: modalidades, locais e atividades oferecidas.

-- Normalização usada na pesquisa e nas restrições de unicidade: minúsculas e sem acentos.
-- Implementada só com funções nativas (sem extensões), para ser IMMUTABLE e utilizável em índices.
-- Nota: lower() segue a collation da base de dados (UTF-8); os caracteres acentuados tratados são
-- os do português e de línguas próximas.
CREATE FUNCTION portofit_normalize(input text) RETURNS text
	LANGUAGE sql
	IMMUTABLE
	PARALLEL SAFE
	RETURNS NULL ON NULL INPUT
AS $$
	SELECT translate(lower(input), 'áàâãäåéèêëíìîïóòôõöúùûüçñý', 'aaaaaaeeeeiiiiooooouuuucny')
$$;

CREATE TABLE sport (
	id          varchar(40)  PRIMARY KEY,
	name        varchar(60)  NOT NULL,
	description varchar(300),
	CONSTRAINT sport_id_format CHECK (id ~ '^[a-z0-9]+(-[a-z0-9]+)*$' AND char_length(id) >= 2),
	CONSTRAINT sport_name_length CHECK (char_length(btrim(name)) >= 2)
);

-- Nome único sem distinguir maiúsculas/minúsculas nem acentos.
CREATE UNIQUE INDEX sport_name_normalized_uq ON sport (portofit_normalize(name));

CREATE TABLE place (
	id          uuid          PRIMARY KEY,
	name        varchar(120)  NOT NULL,
	description varchar(1000),
	street      varchar(200)  NOT NULL,
	postal_code varchar(8)    NOT NULL,
	parish      varchar(80),
	city        varchar(80)   NOT NULL,
	latitude    numeric(8, 6) NOT NULL,
	longitude   numeric(9, 6) NOT NULL,
	phone       varchar(20),
	website     varchar(200),
	is_demo     boolean       NOT NULL DEFAULT false,
	created_at  timestamptz   NOT NULL DEFAULT now(),
	updated_at  timestamptz   NOT NULL DEFAULT now(),
	CONSTRAINT place_name_length CHECK (char_length(btrim(name)) >= 2),
	CONSTRAINT place_postal_code_format CHECK (postal_code ~ '^[0-9]{4}-[0-9]{3}$'),
	CONSTRAINT place_latitude_range CHECK (latitude BETWEEN -90 AND 90),
	CONSTRAINT place_longitude_range CHECK (longitude BETWEEN -180 AND 180),
	CONSTRAINT place_website_format CHECK (website IS NULL OR website ~ '^https?://')
);

CREATE TABLE activity_offering (
	id             uuid          PRIMARY KEY,
	place_id       uuid          NOT NULL REFERENCES place (id),
	sport_id       varchar(40)   NOT NULL REFERENCES sport (id),
	name           varchar(120)  NOT NULL,
	description    varchar(1000),
	level          varchar(20)   NOT NULL,
	schedule_notes varchar(300),
	created_at     timestamptz   NOT NULL DEFAULT now(),
	updated_at     timestamptz   NOT NULL DEFAULT now(),
	CONSTRAINT activity_offering_name_length CHECK (char_length(btrim(name)) >= 2),
	CONSTRAINT activity_offering_level_values CHECK (level IN ('BEGINNER', 'INTERMEDIATE', 'ADVANCED', 'ALL_LEVELS'))
);

-- Nome da atividade único dentro do mesmo local; também serve de índice por place_id.
CREATE UNIQUE INDEX activity_offering_place_name_uq ON activity_offering (place_id, portofit_normalize(name));

-- Filtro por modalidade (sport=...) e junção com sport.
CREATE INDEX activity_offering_sport_place_idx ON activity_offering (sport_id, place_id);
