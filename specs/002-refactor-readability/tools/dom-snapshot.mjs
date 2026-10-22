// 화면 DOM · 텍스트 스냅샷 — 리팩터링 전후 「보이는 것」이 같은지 비교한다(research R3).
// 사용: npx -y -p puppeteer-core@23.11.1 node specs/002-refactor-readability/tools/dom-snapshot.mjs <before|after>
// 전제: ./scripts/deploy.sh up 으로 http://127.0.0.1:8088 이 처음 시드된 상태
import {mkdirSync, rmSync, writeFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';

const BASE_URL = 'http://127.0.0.1:8088';
const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PASSWORD = 'fab1234!';
const ACCOUNTS = ['cmp.park', 'etch.lee', 'mnt.jung', 'manager.choi'];
const ROUTES = ['/dashboard', '/incidents', '/work-items', '/approvals', '/check-requests', '/reports', '/knowledge'];
const INCIDENTS = ['EV-1009', 'EV-1006', 'EV-1023'];
const REPORT_INCIDENT = 'EV-1021';
const KEPT_ATTRIBUTES = ['class', 'id', 'role', 'href', 'placeholder', 'title', 'type', 'name', 'value', 'disabled', 'checked'];

const label = process.argv[2];
if (!['before', 'after'].includes(label) && !label?.startsWith('before') && !label?.startsWith('after')) {
  console.error('인자: before | after');
  process.exit(2);
}
const outDir = join(dirname(fileURLToPath(import.meta.url)), '..', 'records', 'dom', label);

async function loadPuppeteer() {
  // npx -p 로 받은 puppeteer-core는 PATH의 node_modules/.bin 옆에 있다
  const binDir = process.env.PATH.split(':').find(entry => entry.endsWith('node_modules/.bin'));
  const modulePath = join(binDir, '..', 'puppeteer-core', 'lib', 'esm', 'puppeteer', 'puppeteer-core.js');
  return (await import(pathToFileURL(modulePath).href)).default;
}

// 시드 시각 · 생성 ID처럼 실행마다 바뀌는 값을 토큰으로 바꾼다
function normalize(text) {
  return text
    .replace(/\b([a-z]{2,5})_[a-z0-9]{12}\b/g, '<$1-id>')
    .replace(/\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:?\d{2})?/g, '<datetime>')
    .replace(/\d{4}\.\s?\d{1,2}\.\s?\d{1,2}\.?/g, '<date>')
    .replace(/(오전|오후)\s?\d{1,2}:\d{2}(:\d{2})?/g, '<time>')
    .replace(/\b\d{1,2}:\d{2}(:\d{2})?\b/g, '<time>')
    .replace(/\d+\s?(초|분|시간|일)\s?전/g, '<ago>');
}

async function capture(page, name) {
  await page.waitForNetworkIdle({idleTime: 800, timeout: 20000}).catch(() => {});
  await new Promise(resolve => setTimeout(resolve, 600));
  const snapshot = await page.evaluate(kept => {
    const serialize = (node, depth) => {
      const pad = ' '.repeat(depth);
      if (node.nodeType === Node.TEXT_NODE) return node.data ? `${pad}#text ${JSON.stringify(node.data)}\n` : '';
      if (node.nodeType !== Node.ELEMENT_NODE) return '';
      const attributes = [...node.attributes]
        .filter(attribute => kept.includes(attribute.name) || attribute.name.startsWith('aria-'))
        .map(attribute => `${attribute.name}=${JSON.stringify(attribute.value)}`)
        .sort()
        .join(' ');
      let out = `${pad}<${node.tagName.toLowerCase()}${attributes ? ' ' + attributes : ''}>\n`;
      for (const child of node.childNodes) out += serialize(child, depth + 1);
      return out;
    };
    return {text: document.body.innerText, dom: serialize(document.body, 0)};
  }, KEPT_ATTRIBUTES);
  const file = join(outDir, name.replace(/[/:]/g, '_'));
  writeFileSync(file + '.txt', normalize(snapshot.text));
  writeFileSync(file + '.html', normalize(snapshot.dom));
}

async function login(page, account) {
  await page.goto(BASE_URL + '/login', {waitUntil: 'networkidle0'});
  const status = await page.evaluate(async (username, password) => {
    const csrf = (await (await fetch('/api/auth/csrf')).json()).data.token;
    const response = await fetch('/api/auth/login', {
      method: 'POST', headers: {'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrf},
      body: JSON.stringify({username, password}),
    });
    return response.status;
  }, account, PASSWORD);
  if (status !== 200) throw new Error(`${account} 로그인 실패: ${status}`);
}

// 시드 직후 Agent가 기본 분석을 처리하는 동안은 화면이 계속 바뀐다 — 진행 중인 작업이 없어질 때까지 기다린다
async function waitUntilAgentIdle(page) {
  for (let attempt = 0; attempt < 120; attempt++) {
    const busy = await page.evaluate(async () => {
      const list = await (await fetch('/api/incidents?size=100')).json();
      return (list.data?.items ?? []).filter(item => ['QUEUED', 'RUNNING'].includes(item.activeJob?.status)).length;
    });
    if (busy === 0) return;
    await new Promise(resolve => setTimeout(resolve, 2000));
  }
  throw new Error('Agent 작업이 4분 안에 끝나지 않았다');
}

async function incidentIds(page) {
  return page.evaluate(async (displayIds, reportIncident) => {
    const ids = {};
    for (const displayId of [...displayIds, reportIncident]) {
      const page = await (await fetch('/api/incidents?q=' + displayId)).json();
      const found = page.data?.items?.find(item => item.displayId === displayId);
      if (found) ids[displayId] = found.incidentId;
    }
    let reportId = null;
    if (ids[reportIncident]) {
      const reports = await (await fetch(`/api/incidents/${ids[reportIncident]}/reports`)).json();
      const items = Array.isArray(reports.data) ? reports.data : reports.data?.items;
      reportId = items?.[0]?.reportId ?? null;
    }
    return {ids, reportId};
  }, INCIDENTS, REPORT_INCIDENT);
}

const puppeteer = await loadPuppeteer();
rmSync(outDir, {recursive: true, force: true});
mkdirSync(outDir, {recursive: true});
const browser = await puppeteer.launch({executablePath: CHROME, headless: true, defaultViewport: {width: 1440, height: 1000}});
try {
  const waitContext = await browser.createBrowserContext();
  const waitPage = await waitContext.newPage();
  await login(waitPage, 'manager.choi');
  await waitUntilAgentIdle(waitPage);
  await waitContext.close();

  const anonymous = await browser.createBrowserContext();
  const loginPage = await anonymous.newPage();
  await loginPage.goto(BASE_URL + '/login', {waitUntil: 'networkidle0'});
  await capture(loginPage, 'anonymous__login');
  await anonymous.close();

  for (const account of ACCOUNTS) {
    const context = await browser.createBrowserContext();
    const page = await context.newPage();
    await login(page, account);
    for (const route of ROUTES) {
      await page.goto(BASE_URL + route, {waitUntil: 'domcontentloaded'});
      await capture(page, `${account}__${route}`);
    }
    const {ids, reportId} = await incidentIds(page);
    for (const displayId of INCIDENTS) {
      if (!ids[displayId]) continue;
      await page.goto(`${BASE_URL}/incidents/${ids[displayId]}`, {waitUntil: 'domcontentloaded'});
      await capture(page, `${account}__incident-${displayId}`);
    }
    if (reportId) {
      await page.goto(`${BASE_URL}/reports/${reportId}`, {waitUntil: 'domcontentloaded'});
      await capture(page, `${account}__report-${REPORT_INCIDENT}`);
    }
    await context.close();
  }
} finally {
  await browser.close();
}
console.log('저장:', outDir);
