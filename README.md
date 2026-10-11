# Hotel Paris — atualizações

Pacotes de atualização do sistema Hotel Paris (`.hpupdate`), assinados com a chave do dono.

O sistema instalado no hotel lê o `index.json` a cada 10 minutos. Quando encontra um pacote
que sai da versão instalada (`from`), baixa, confere a assinatura e instala sozinho.
Pacote sem a assinatura certa é recusado.

Aqui ficam só os pacotes do programa. Nenhum dado do hotel e nenhuma chave privada.
