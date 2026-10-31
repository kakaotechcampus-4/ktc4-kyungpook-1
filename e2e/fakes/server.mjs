// E2E 시험용 가짜 GitHub(OAuth·REST·GraphQL) + 가짜 LLM(OpenAI 호환) 한 프로세스.
// 토큰에 로그인을 넣어(gho_E2E_<login>) 사용자마다 id 와 주인만 다른 같은 저장소 데이터를 돌려준다.
// 제어 상태(로그인 대기열·실패 주입·요청 기록)가 프로세스에 하나뿐이라 시험은 한 번에 하나씩만 돌린다.
import { createServer } from 'node:http';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const PORT = Number(process.env.PORT ?? 9000);
const DEFAULT_LOGIN = 'e2e-user';
const INTERNAL_URL = 'http://fake:9000';
const PUBLIC_URL = 'http://localhost:9000';
const TOKEN_PREFIX = 'gho_E2E_';
const here = dirname(fileURLToPath(import.meta.url));
const data = JSON.parse(readFileSync(join(here, 'fixtures', 'sample-app.json'), 'utf8'));
const PIXEL = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==', 'base64');

// 저장소·리뷰·댓글 id 는 GitHub 에서 전역으로 유일하고 DB 도 전역 UNIQUE 라 사용자마다 달라야 서로의 행을 덮지 않는다
const parentSha = (key) => createHash('sha1').update(`sample-app/${key}-parent`).digest('hex');
function viewFor(login) {
  const offset = userId(login) * 10_000;
  const commits = data.commits.map((c) => ({ ...c, parents: [parentSha(c.key)] }));
  const shaByKey = new Map(data.commits.map((c) => [c.key, c.sha]));
  const pulls = data.pulls.map((p) => ({
    ...p,
    commits: p.commits.map((key) => shaByKey.get(key)),
    reviews: p.reviews.map((r) => ({ ...r, id: r.id + offset })),
    comments: p.comments.map((c) => ({ ...c, id: c.id + offset, review_id: c.review_id + offset })),
  }));
  const repo = { ...data.repo, owner: login, id: data.repo.id + userId(login) };
  return { repo, commits, pulls, issues: data.issues, headSha: commits[0].sha };
}

let requests = [];
let loginQueue = [];
let failures = [];

function reset() {
  requests = [];
  loginQueue = [];
  failures = [];
}

const userId = (login) => 1000 + (createHash('sha1').update(login).digest().readUInt32BE(0) % 1_000_000);

function readBody(req) {
  return new Promise((resolve) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
  });
}

function send(res, status, body, headers = {}) {
  const isJson = typeof body !== 'string' && !Buffer.isBuffer(body) && body !== undefined;
  const payload = body === undefined ? '' : isJson ? JSON.stringify(body) : body;
  res.writeHead(status, { ...(isJson ? { 'Content-Type': 'application/json' } : {}), ...headers });
  res.end(payload);
}

function loginOf(req) {
  const auth = req.headers.authorization ?? '';
  const match = /^Bearer gho_E2E_(.+)$/.exec(auth);
  return match ? match[1] : null;
}

function sampleCommit(commit, login) {
  const additions = commit.files.reduce((sum, file) => sum + file.additions, 0);
  const deletions = commit.files.reduce((sum, file) => sum + file.deletions, 0);
  return {
    sha: commit.sha,
    author: { login, type: 'User' },
    commit: { message: commit.message, author: { name: login, email: `${login}@example.com`, date: commit.date } },
    parents: commit.parents.map((sha) => ({ sha })),
    stats: { additions, deletions },
    files: commit.files,
  };
}

function handleRepoRoute(url, login, res) {
  const { repo, commits, pulls, issues, headSha } = viewFor(login);
  const [, , owner, name, ...rest] = url.pathname.split('/');
  if (owner !== repo.owner || name !== repo.name) return send(res, 404, { message: 'Not Found' });
  const page = Number(url.searchParams.get('page') ?? '1');
  const route = rest.join('/');

  if (route === 'pulls') {
    if (page > 1) return send(res, 200, []);
    return send(res, 200, pulls.map((pull) => ({
      number: pull.number, title: pull.title, body: pull.body, state: pull.state,
      user: { login }, base: { ref: pull.base }, head: { ref: pull.head },
      created_at: pull.created_at, merged_at: pull.merged_at,
    })));
  }
  const pullRoute = /^pulls\/(\d+)\/(commits|reviews|comments)$/.exec(route);
  if (pullRoute) {
    const pull = pulls.find((p) => p.number === Number(pullRoute[1]));
    if (!pull) return send(res, 404, { message: 'Not Found' });
    if (page > 1) return send(res, 200, []);
    if (pullRoute[2] === 'commits') return send(res, 200, pull.commits.map((sha) => ({ sha })));
    if (pullRoute[2] === 'reviews') {
      return send(res, 200, pull.reviews.map((r) => ({
        id: r.id, state: r.state, body: r.body, user: { login: r.user }, submitted_at: r.submitted_at,
      })));
    }
    return send(res, 200, pull.comments.map((c) => ({
      id: c.id, pull_request_review_id: c.review_id, body: c.body, path: c.path, line: c.line,
      user: { login: c.user }, created_at: c.created_at,
    })));
  }
  const branch = /^branches\/(.+)$/.exec(route);
  if (branch) {
    if (decodeURIComponent(branch[1]) !== repo.default_branch) return send(res, 404, { message: 'Branch not found' });
    return send(res, 200, { name: repo.default_branch, commit: { sha: headSha } });
  }
  if (route === 'commits') {
    if (page > 1) return send(res, 200, []);
    return send(res, 200, commits.map((c) => ({ sha: c.sha })));
  }
  const detail = /^commits\/([0-9a-f]+)$/.exec(route);
  if (detail) {
    const commit = commits.find((c) => c.sha === detail[1]);
    return commit ? send(res, 200, sampleCommit(commit, login)) : send(res, 404, { message: 'Not Found' });
  }
  if (route === 'issues') {
    if (page > 1) return send(res, 200, []);
    const items = issues.map((i) => ({
      number: i.number, title: i.title, body: i.body, state: i.state, user: { login: i.user },
      labels: i.labels.map((label) => ({ name: label })), created_at: i.created_at, closed_at: i.closed_at,
    }));
    // 이슈 목록에는 PR 도 섞여 오므로 AI 가 걸러내는지 같이 본다
    const pullEntry = { number: pulls[0].number, title: pulls[0].title, pull_request: { url: `${INTERNAL_URL}/pulls/1` } };
    return send(res, 200, [pullEntry, ...items]);
  }
  return send(res, 404, { message: 'Not Found' });
}

function handleGraphql(body, login, res) {
  const { query = '', variables = {} } = body;
  const { repo, commits, pulls, headSha } = viewFor(login);
  if (query.includes('repositoriesContributedTo')) {
    return send(res, 200, { data: { viewer: { repositoriesContributedTo: { pageInfo: { hasNextPage: false, endCursor: null }, nodes: [] } } } });
  }
  if (query.includes('fragment counts')) {
    const result = {};
    for (let i = 0; variables[`o${i}`] !== undefined; i++) {
      const hit = variables[`o${i}`] === repo.owner && variables[`n${i}`] === repo.name;
      result[`r${i}`] = hit ? {
        pullRequests: { totalCount: pulls.length },
        defaultBranchRef: { target: {
          oid: headSha,
          all: { totalCount: commits.length },
          mine: {
            nodes: commits.map((c) => ({ parents: { totalCount: c.parents.length } })),
            pageInfo: { hasNextPage: false, endCursor: null },
          },
        } },
      } : null;
    }
    return send(res, 200, { data: result });
  }
  if (/viewer\s*\{\s*id\s*\}/.test(query)) {
    return send(res, 200, { data: { viewer: { id: `E2E_VIEWER_${login}` } } });
  }
  return send(res, 400, { errors: [{ message: 'fake: 모르는 GraphQL 질의', type: 'UNKNOWN' }] });
}

function handleSearch(url, login, res) {
  const { repo, pulls } = viewFor(login);
  const q = url.searchParams.get('q') ?? '';
  const page = Number(url.searchParams.get('page') ?? '1');
  // 내가 만든 PR 만 있고 내가 리뷰한 남의 PR 은 없다
  const items = q.includes('author:') && !q.includes('reviewed-by:') && page === 1
    ? pulls.map(() => ({ repository_url: `${INTERNAL_URL}/repos/${repo.owner}/${repo.name}` }))
    : [];
  return send(res, 200, { total_count: items.length, incomplete_results: false, items });
}

function handleLlm(body, res) {
  const schema = body?.response_format?.json_schema?.name;
  let content;
  if (schema === 'ExperienceGroupingOutput') {
    // 빈 목록이면 AI 가 작업 단위마다 경험 하나로 살린다
    content = { experiences: [] };
  } else if (schema === 'GroupDescriptionsOutput') {
    const user = body.messages?.find((m) => m.role === 'user')?.content ?? '{}';
    const groups = JSON.parse(user).groups ?? [];
    content = {
      descriptions: groups.map((g) => ({
        group_key: g.group_key,
        title: `E2E 후보 ${g.group_key}`,
        reason: 'E2E 가짜 LLM 이 만든 설명입니다.',
      })),
    };
  } else {
    return send(res, 500, { error: { message: `fake: 모르는 출력 형식 ${schema}` } });
  }
  return send(res, 200, {
    id: 'chatcmpl-e2e', object: 'chat.completion', created: Math.floor(Date.now() / 1000), model: body.model ?? 'e2e-model',
    choices: [{ index: 0, message: { role: 'assistant', content: JSON.stringify(content), refusal: null }, finish_reason: 'stop' }],
    usage: { prompt_tokens: 10, completion_tokens: 10, total_tokens: 20 },
  });
}

async function handleControl(req, res, url) {
  const route = url.pathname.replace('/__control', '');
  if (route === '/health') return send(res, 200, { ok: true });
  if (route === '/reset' && req.method === 'POST') { reset(); return send(res, 200, { ok: true }); }
  if (route === '/requests' && req.method === 'GET') return send(res, 200, requests);
  if (route === '/next-user' && req.method === 'POST') {
    const { login } = JSON.parse((await readBody(req)) || '{}');
    if (!login) return send(res, 400, { message: 'login 이 필요하다' });
    loginQueue.push(login);
    return send(res, 200, { queued: loginQueue });
  }
  if (route === '/fail' && req.method === 'POST') {
    const { pattern, status = 500 } = JSON.parse((await readBody(req)) || '{}');
    if (!pattern) return send(res, 400, { message: 'pattern 이 필요하다' });
    failures.push({ pattern: new RegExp(pattern), status });
    return send(res, 200, { failures: failures.length });
  }
  if (route === '/fail' && req.method === 'DELETE') { failures = []; return send(res, 200, { ok: true }); }
  return send(res, 404, { message: 'fake: 모르는 제어 주소' });
}

const server = createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host ?? 'fake'}`);
  try {
    if (url.pathname.startsWith('/__control/')) return await handleControl(req, res, url);
    const raw = await readBody(req);
    requests.push({ method: req.method, path: url.pathname + url.search, authorization: req.headers.authorization ?? null });

    // LLM 은 키가 Authorization 에 실려 오므로 GitHub 토큰 검사 앞에서 가른다
    if (req.method === 'POST' && url.pathname.endsWith('/chat/completions')) return handleLlm(JSON.parse(raw || '{}'), res);

    if (url.pathname.startsWith('/avatars/')) return send(res, 200, PIXEL, { 'Content-Type': 'image/png' });

    const failure = failures.find((f) => f.pattern.test(url.pathname + url.search));
    if (failure) return send(res, failure.status, { message: 'fake: 시험이 일부러 실패시킨 요청' });

    // OAuth
    if (url.pathname === '/login/oauth/authorize') {
      const login = loginQueue.shift() ?? DEFAULT_LOGIN;
      const target = new URL(url.searchParams.get('redirect_uri'));
      target.searchParams.set('code', `code_${login}`);
      target.searchParams.set('state', url.searchParams.get('state') ?? '');
      return send(res, 302, '', { Location: target.toString() });
    }
    if (url.pathname === '/login/oauth/access_token') {
      const code = new URLSearchParams(raw).get('code') ?? '';
      if (!code.startsWith('code_')) return send(res, 200, { error: 'bad_verification_code' });
      return send(res, 200, { access_token: `${TOKEN_PREFIX}${code.slice(5)}`, token_type: 'bearer', scope: 'read:user' });
    }
    // 연결 해제는 토큰이 아니라 앱 id·secret 의 Basic 인증이다
    if (req.method === 'DELETE' && url.pathname.startsWith('/applications/')) return send(res, 204);

    // GitHub API: 로그인 때 준 토큰이어야 한다
    const login = loginOf(req);
    if (!login) return send(res, 401, { message: 'Bad credentials' });

    if (url.pathname === '/user') {
      return send(res, 200, { id: userId(login), login, avatar_url: `${PUBLIC_URL}/avatars/${login}.png` });
    }
    if (url.pathname === '/user/repos') {
      const { repo } = viewFor(login);
      const page = Number(url.searchParams.get('page') ?? '1');
      return send(res, 200, page > 1 ? [] : [{
        id: repo.id, name: repo.name, owner: { login: repo.owner }, private: false, language: repo.language,
        default_branch: repo.default_branch, created_at: repo.created_at, pushed_at: repo.pushed_at,
      }]);
    }
    if (url.pathname === '/graphql' && req.method === 'POST') return handleGraphql(JSON.parse(raw || '{}'), login, res);
    if (url.pathname === '/search/issues') return handleSearch(url, login, res);
    if (url.pathname === '/rate_limit') {
      return send(res, 200, { resources: { core: { limit: 5000, remaining: 4999, reset: Math.floor(Date.now() / 1000) + 3600 } } });
    }
    if (url.pathname.startsWith('/repos/')) return handleRepoRoute(url, login, res);
    return send(res, 404, { message: 'Not Found' });
  } catch (error) {
    console.error('fake 서버 오류', error);
    return send(res, 500, { message: String(error) });
  }
});

server.listen(PORT, '0.0.0.0', () => console.log(`fake 서버 시작: ${PORT}`));
