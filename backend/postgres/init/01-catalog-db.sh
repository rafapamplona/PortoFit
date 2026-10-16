#!/bin/bash
# Corre apenas na primeira inicialização do PostgreSQL (volume de dados vazio).
# Cria a base de dados do catalog-service e um utilizador próprio, sem privilégios de administração.
set -euo pipefail

: "${CATALOG_DB_USER:?CATALOG_DB_USER não definido}"
: "${CATALOG_DB_PASSWORD:?CATALOG_DB_PASSWORD não definido}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
	-v app_user="$CATALOG_DB_USER" \
	-v app_password="$CATALOG_DB_PASSWORD" <<-'EOSQL'
	CREATE ROLE :"app_user" WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
		PASSWORD :'app_password';

	-- O utilizador é dono da sua base de dados (e, por isso, do esquema public dela),
	-- o que lhe permite criar tabelas através das migrações Flyway.
	CREATE DATABASE catalog_db OWNER :"app_user";

	-- Nenhum outro utilizador (exceto administradores) se pode ligar a catalog_db,
	-- e utilizadores comuns não se ligam à base de administração.
	REVOKE ALL ON DATABASE catalog_db FROM PUBLIC;
	REVOKE CONNECT ON DATABASE postgres FROM PUBLIC;
EOSQL
