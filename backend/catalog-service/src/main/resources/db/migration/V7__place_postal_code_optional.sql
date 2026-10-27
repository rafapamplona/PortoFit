-- Código postal opcional (docs/catalog-api.md §2.1; docs/locais-reais.md §11): um ponto de encontro de treino não tem
-- código postal, e inventá-lo não é aceitável. Só deixa de ser obrigatório; o formato continua verificado quando existe
-- (a restrição place_postal_code_format aceita NULL).
ALTER TABLE place ALTER COLUMN postal_code DROP NOT NULL;
