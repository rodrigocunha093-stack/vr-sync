# VR Sync - Sincronizador

Cliente Windows para sincronizar dados do banco VR (PostgreSQL) com o Encarte Inteligente.

## Pré-requisitos

- Windows 10/11 com PowerShell
- [Node.js 18+](https://nodejs.org/) e npm
- Conexão com o banco PostgreSQL do VR
- Conexão HTTPS com o Encarte Inteligente

## Instalação

1. Extraia o ZIP em uma pasta permanente.
2. Execute `instalar.bat` como Administrador.
3. O instalador solicitará:
   - Host, porta, banco, usuário e senha do PostgreSQL
   - ID numérico desta loja no VR (tabela `public.loja`)
   - Código único desta loja no Encarte Inteligente
   - URL HTTPS da API central
   - Token de autenticação da API
4. A configuração será testada antes de ser gravada.
5. Uma tarefa agendada diária às 02:00 será criada.

A instalação é copiada para `C:\ProgramData\VR Sync`.

## Comandos manuais

```bash
npm run verify       # testar banco e API
npm run sync         # sincronizar módulos padrão
npm run sync:tudo    # sincronização completa (--all)
```

## Tarefa agendada

- Nome: `VR Sync - Encarte Inteligente`
- Executa diariamente às 02:00 como SYSTEM.
- Logs em `C:\ProgramData\VR Sync\logs`.

## Reconfiguração

Para alterar os dados de conexão, remova a pasta `C:\ProgramData\VR Sync` e a tarefa agendada antes de reinstalar.

## Dados locais

- Arquivos JSON em `output/` contêm dados exportados do banco.
- O arquivo `.env` guarda credenciais e deve ser protegido.
- Os logs em `logs/` registram cada execução do sincronizador.
