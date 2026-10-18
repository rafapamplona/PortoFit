#!/bin/bash
# Gera, UMA vez, o par de chaves RSA com que o users-service assina os access tokens (JWT RS256).
#   secrets/users-jwt/private.pem  — chave privada PKCS#8 (só o users-service a recebe)
#   secrets/users-jwt/public.pem   — chave pública (pode ser distribuída aos outros serviços)
# A pasta secrets/ é ignorada pelo Git e não entra nas imagens Docker.
# Se as chaves já existirem, não faz nada: regenerá-las invalidaria todas as sessões em curso.
#
# Uso (na raiz do projeto, em Git Bash ou noutra shell com openssl):  bash scripts/generate-jwt-keys.sh
set -euo pipefail

DIR="$(cd "$(dirname "$0")/.." && pwd)/secrets/users-jwt"
PRIVATE="$DIR/private.pem"
PUBLIC="$DIR/public.pem"

if [ -e "$PRIVATE" ] || [ -e "$PUBLIC" ]; then
	echo "As chaves já existem em secrets/users-jwt/ — nada foi alterado."
	exit 0
fi

command -v openssl >/dev/null || { echo "É preciso o openssl (incluído no Git para Windows)." >&2; exit 1; }

umask 077
mkdir -p "$DIR"
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out "$PRIVATE" 2>/dev/null
openssl pkey -in "$PRIVATE" -pubout -out "$PUBLIC" 2>/dev/null
echo "Chaves criadas em secrets/users-jwt/ (RSA 3072 bits). Não as partilhes nem as adiciones ao Git."
