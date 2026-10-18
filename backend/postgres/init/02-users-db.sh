#!/bin/bash
# Cria a base de dados do users-service e o seu utilizador, sem privilégios de administração.
#
# Corre sozinho na primeira inicialização do PostgreSQL (volume vazio). Como é idempotente, também
# serve para um volume que JÁ existe (onde os scripts de inicialização não voltam a correr):
#   docker compose exec -T postgres bash /docker-entrypoint-initdb.d/02-users-db.sh
# Não apaga nada. Se o utilizador já existir, só sincroniza a palavra-passe com o .env.
set -euo pipefail

: "${USERS_DB_USER:?USERS_DB_USER não definido}"
: "${USERS_DB_PASSWORD:?USERS_DB_PASSWORD não definido}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
	-v app_user="$USERS_DB_USER" \
	-v app_password="$USERS_DB_PASSWORD" <<-'EOSQL'
	SELECT format('CREATE ROLE %I WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS', :'app_user')
	WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'app_user') \gexec

	-- Garante os atributos e a palavra-passe atuais, também num utilizador que já existia.
	ALTER ROLE :"app_user" WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
		PASSWORD :'app_password';

	SELECT format('CREATE DATABASE users_db OWNER %I', :'app_user')
	WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'users_db') \gexec

	-- Só o dono (e administradores) se ligam a users_db; o utilizador do catálogo não tem acesso.
	REVOKE ALL ON DATABASE users_db FROM PUBLIC;
	REVOKE CONNECT ON DATABASE postgres FROM PUBLIC;
EOSQL

echo "users_db e o seu utilizador estão prontos."
