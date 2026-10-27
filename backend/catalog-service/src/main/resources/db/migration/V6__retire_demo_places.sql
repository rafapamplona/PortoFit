-- Retirada dos locais e atividades FICTÍCIOS (docs/locais-reais.md §5; docs/experiences-api.md §15).
-- Retirar, nunca apagar: continuam a responder pelo id (com status RETIRED), as experiências que os referem continuam
-- válidas e nenhuma é passada para um local real. Deixam de aparecer na pesquisa pública e de aceitar publicações.
--
-- Corre uma vez em cada base. Numa base nova ainda não há dados fictícios (o carregador do perfil "demo" corre depois
-- das migrações) e nada muda: um ambiente de demonstração separado continua a ter os fictícios ativos. O script de
-- demonstração não escreve o status, por isso recarregá-lo não reativa o que aqui foi retirado.
UPDATE place SET status = 'RETIRED', updated_at = now()
WHERE is_demo AND status <> 'RETIRED';

UPDATE activity_offering SET status = 'RETIRED', updated_at = now()
WHERE place_id IN (SELECT id FROM place WHERE is_demo) AND status <> 'RETIRED';
