const path = require('path');
const dotenv = require('dotenv');

const envPath = process.env.VR_SYNC_ENV_FILE
  ? path.resolve(process.env.VR_SYNC_ENV_FILE)
  : path.join(__dirname, '.env');

dotenv.config({ path: envPath, override: true });

function obrigatoria(nome, { permitirVazia = false } = {}) {
  const valor = process.env[nome];
  if (valor === undefined) {
    if (permitirVazia) return '';
    throw new Error(`Configuracao obrigatoria ausente: ${nome}`);
  }
  if (!permitirVazia && valor.trim() === '') {
    throw new Error(`Configuracao obrigatoria ausente: ${nome}`);
  }
  return valor.trim();
}

function inteiro(nome, min, max) {
  const texto = obrigatoria(nome);
  const valor = Number(texto);
  if (!Number.isInteger(valor) || valor < min || valor > max) {
    throw new Error(`Configuracao invalida: ${nome} deve ser um inteiro entre ${min} e ${max}`);
  }
  return valor;
}

function segredo(nome, nomeBase64, { permitirVazia = false } = {}) {
  const codificado = process.env[nomeBase64];
  if (codificado && codificado.trim()) {
    try {
      return Buffer.from(codificado.trim(), 'base64').toString('utf8');
    } catch {
      throw new Error(`Configuracao invalida: ${nomeBase64}`);
    }
  }
  return obrigatoria(nome, { permitirVazia });
}

function urlApi() {
  const texto = obrigatoria('ENCARTE_API_URL').replace(/\/+$/, '');
  let url;
  try {
    url = new URL(texto);
  } catch {
    throw new Error('Configuracao invalida: ENCARTE_API_URL deve ser uma URL valida');
  }
  if (url.protocol !== 'https:' && !(url.protocol === 'http:' && ['localhost', '127.0.0.1'].includes(url.hostname))) {
    throw new Error('Configuracao invalida: ENCARTE_API_URL deve usar HTTPS');
  }
  return texto;
}

const config = {
  envPath,
  db: {
    host: obrigatoria('PGHOST'),
    port: inteiro('PGPORT', 1, 65535),
    database: obrigatoria('PGDATABASE'),
    user: obrigatoria('PGUSER'),
    password: segredo('PGPASSWORD', 'PGPASSWORD_B64', { permitirVazia: true }),
    max: 5,
    idleTimeoutMillis: 30000,
    connectionTimeoutMillis: 10000,
  },
  diasVendas: 90,
  estoquePartition: null,
  outputDir: path.join(__dirname, 'output'),
  logsDir: path.join(__dirname, 'logs'),
  lojaVrId: inteiro('VR_LOJA_ID', 1, 2147483647),
  api: {
    url: urlApi(),
    token: segredo('ENCARTE_API_TOKEN', 'ENCARTE_API_TOKEN_B64'),
    timeoutMs: 60000,
    maxTentativas: 3,
    loteRegistros: 500,
    loteBytes: 3 * 1024 * 1024,
  },
  codigoLoja: obrigatoria('ENCARTE_LOJA'),
};

module.exports = config;
