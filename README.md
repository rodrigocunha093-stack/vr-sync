# VR Sync

Cliente Windows para sincronizar dados do banco VR (PostgreSQL local) com projetos externos via API REST.

## Arquitetura

```
┌──────────────┐     GET /api/sync/config      ┌──────────────────┐
│  VR Sync     │ ──────────────────────────────>│  Projeto Central │
│  (na loja)   │                                │  (Vercel/API)    │
│              │     POST /api/sync/dados       │                  │
│  PG local ───│ ──────────────────────────────>│  Supabase        │
└──────────────┘                                └──────────────────┘
```

**Pull-Push:** o servidor diz o que extrair (módulos + filtros), o cliente extrai do PG local e envia em lotes.

## Pré-requisitos

- Windows 10/11
- [Node.js 18+](https://nodejs.org/) e npm
- Acesso ao banco PostgreSQL do VR (rede local)
- URL HTTPS e token do projeto consumidor

## Instalação

1. Clone ou extraia em uma pasta permanente na máquina da loja
2. Copie `.env.example` para `.env` e preencha:
   - Dados do PostgreSQL local (host, porta, banco, usuário, senha)
   - ID numérico da loja no VR (`VR_LOJA_ID`)
   - Código da loja no projeto central (`ENCARTE_LOJA`)
   - URL da API e token de autenticação
3. Execute `instalar.bat` (como Administrador se quiser tarefa agendada)

## Uso

### Sync manual
```bash
npm run sync          # sincronizar módulos solicitados pelo servidor
npm run sync:tudo     # sincronização completa
npm run sync:vendas   # apenas vendas
```

### Modo vigia (recomendado)
```bash
npm run vigia         # consulta servidor a cada 15 min, sync a cada 6h
```

Para instalar como tarefa agendada: `instalar-vigia.bat`

### Verificação
```bash
npm run verify        # testa conexão com banco e API
```

### Explorador de dados
```bash
node explorar.js lojas              # listar lojas
node explorar.js produtos arroz     # buscar produtos
node explorar.js estoque 12345      # estoque de um produto
node explorar.js vendas 12345       # vendas recentes
node explorar.js tabelas            # listar tabelas com dados
node explorar.js sql "SELECT ..."   # query livre (só SELECT)
```

## Módulos extraídos

| Módulo | Fonte | Descrição |
|--------|-------|-----------|
| lojas | `public.loja` | Dados cadastrais da loja |
| mercadologico | `public.mercadologico` | Classificação mercadológica (5 níveis) |
| fornecedores | `public.fornecedor` | Fornecedores ativos |
| produtos | `notaentradaitem` + `produto` + `vendaitem` | Catálogo com EAN e status ativo |
| estoque | `public.estoqueMMYYYY` | Estoque atual (partição detectada automaticamente) |
| vendas | `pdv.venda` + `pdv.vendaitem` | Vendas diárias agregadas |
| ofertas | `public.oferta` | Ofertas cadastradas |
| compras | `notaentrada` + `notaentradaitem` | Notas de entrada com bonificação e verba |
| vendas_promocao | `pdv.vendapromocaoproduto` | Vendas em promoção com preço normal vs promo |
| estoque_historico | Partições `estoqueMMYYYY` (3 meses) | Série histórica de estoque |

## Configuração por loja

Cada instalação em loja tem seu próprio `.env`:

```env
# Exemplo: COMPREMAIS
PGHOST=192.168.1.118
PGPORT=8745
PGDATABASE=vr
PGUSER=postgres
PGPASSWORD=
VR_LOJA_ID=1
ENCARTE_LOJA=loja-cm-01
ENCARTE_API_URL=https://encarte-inteligente.vercel.app
ENCARTE_API_TOKEN=abc123
```

```env
# Exemplo: Rei da Economia
PGHOST=127.0.0.1
PGPORT=5433
PGDATABASE=reidaeconomia
PGUSER=postgres
PGPASSWORD=
VR_LOJA_ID=1
ENCARTE_LOJA=loja-01
ENCARTE_API_URL=https://encarte-inteligente.vercel.app
ENCARTE_API_TOKEN=abc123
```

## Estrutura

```
vr-sync/
├── config.js          # Lê .env, valida, exporta configuração
├── sync.js            # Extração e envio (Pull-Push)
├── vigia.js           # Watchdog: polling 15min + sync 6h
├── verificar.js       # Teste de conexão banco + API
├── explorar.js        # CLI de consultas ao banco VR
├── instalar.bat       # Instalação (npm install + verificação)
├── instalar-vigia.bat # Registra tarefa agendada do vigia
├── executar-sync.bat  # Wrapper para tarefa agendada
├── executar-vigia.bat # Wrapper para tarefa agendada
├── .env.example       # Template de configuração
├── .gitignore
├── package.json
└── README.md
```

## Segurança

- Credenciais ficam APENAS no `.env` local (nunca versionado)
- Suporte a senhas em Base64 (`PGPASSWORD_B64`, `ENCARTE_API_TOKEN_B64`)
- API exige HTTPS (exceto localhost para desenvolvimento)
- Token Bearer em todas as chamadas à API
- `explorar.js` só aceita SELECT (read-only)
