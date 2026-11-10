import { sleep } from 'k6';
import { SharedArray } from 'k6/data';
import exec from 'k6/execution';
import http from 'k6/http';
import { Counter, Rate, Trend } from 'k6/metrics';
import sse from 'k6/x/sse';

const base = __ENV.RBGS_F16_BASE_URL;
if (!/^http:\/\/127\.0\.0\.1:[0-9]+$/.test(base)) throw new Error('Use the isolated loopback test API');
const accounts = new SharedArray('synthetic Preseason accounts', () => JSON.parse(open(__ENV.RBGS_F16_FIXTURES)));
if (accounts.length !== 200) throw new Error('Expected 200 distinct fixture accounts');

const profile = __ENV.RBGS_F16_PROFILE;
const profiles = {
    quick: [
        { target: 50, duration: '10s' }, { target: 100, duration: '10s' },
        { target: 200, duration: '20s' }, { target: 200, duration: '90s' },
        { target: 0, duration: '15s' },
    ],
    ramp: [
        { target: 20, duration: '1m' }, { target: 50, duration: '1m' }, { target: 100, duration: '1m' },
        { target: 200, duration: '2m' }, { target: 200, duration: '10m' },
        { target: 0, duration: '2m' },
    ],
    soak: [
        { target: 200, duration: '2m' }, { target: 200, duration: '30m' },
        { target: 0, duration: '2m' },
    ],
};
if (!Object.hasOwn(profiles, profile)) throw new Error('Select quick, ramp or soak');

const restDuration = new Trend('rbgs_rest_duration', true);
const restFailure = new Rate('rbgs_rest_failed');
const roomAccepted = new Counter('rbgs_accept_requests');
const streamReady = new Counter('rbgs_stream_ready');
const streamChanged = new Counter('rbgs_stream_changed');
const streamFailure = new Counter('rbgs_stream_failures');
const resynced = new Counter('rbgs_resynced');
const operations = ['soloq_status', 'soloq_enter', 'soloq_accept', 'premade_status',
    'premade_enter', 'premade_accept', 'heartbeat', 'leaderboard'];
const latencyThresholds = Object.fromEntries(operations.map(operation => [
    `rbgs_rest_duration{operation:${operation}}`, ['p(95)<500'],
]));

export const options = {
    scenarios: {
        preseason: {
            executor: 'ramping-vus',
            startVUs: 20,
            stages: profiles[profile],
            gracefulRampDown: '0s',
            gracefulStop: '5s',
        },
    },
    thresholds: {
        ...latencyThresholds,
        rbgs_rest_failed: ['rate<0.01'],
        rbgs_accept_requests: ['count>0'],
        'rbgs_accept_requests{queue:soloq}': ['count>0'],
        'rbgs_accept_requests{queue:premade}': ['count>0'],
        rbgs_stream_changed: ['count>0'],
        rbgs_resynced: ['count>0'],
    },
    systemTags: ['status', 'method', 'name', 'scenario', 'expected_response'],
    summaryTrendStats: ['count', 'avg', 'med', 'p(95)', 'p(99)', 'max'],
};

let lastResponded;
let lastHeartbeat = 0;
let lastRead = 0;
let lastRosterRead = 0;
let controlledReconnect = false;

function rest(method, path, operation, body, headers = {}) {
    const response = http.request(method, base + path, body, {
        headers,
        tags: { name: operation, operation },
        timeout: '10s',
    });
    const ok = response.status === 200;
    restDuration.add(response.timings.duration, { operation });
    restFailure.add(!ok, { operation });
    if (!ok && exec.vu.idInTest <= 2) console.warn(`${operation} status=${response.status} error_code=${response.error_code}`);
    return ok ? response : null;
}

function refresh(actor, path, event) {
    const now = Date.now();
    if (now - lastHeartbeat > 12000) {
        lastHeartbeat = now;
        rest('POST', '/api/v1/companion/heartbeat', 'heartbeat', null, { Authorization: `Bearer ${actor.deviceToken}` });
    }
    if (event === 'changed' && now - lastRead < 400) return false;
    lastRead = now;
    const response = rest('GET', path, `${actor.queue}_status`, null);
    if (!response) return false;
    const status = response.json();
    const csrf = http.cookieJar().cookiesForURL(base)['XSRF-TOKEN']?.[0];
    const headers = { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf };
    if (status.room?.status === 'PROPOSED' && status.room.id !== lastResponded) {
        const accepted = rest('POST', `${path}/rooms/${status.room.id}/response`, `${actor.queue}_accept`, '{"accept":true}', headers);
        if (accepted) {
            lastResponded = status.room.id;
            roomAccepted.add(1, { queue: actor.queue });
        }
    } else if (actor.admission && status.available && (!status.ticket || status.ticket.state === 'CANCELLED')) {
        rest('POST', path, `${actor.queue}_enter`, actor.admission, headers);
    }
    if (now - lastRosterRead > 25000 && exec.vu.idInTest % 4 === 0) {
        lastRosterRead = now;
        rest('GET', '/api/v1/leaderboard?region=EU&limit=20', 'leaderboard', null);
    }
    return true;
}

export default function () {
    const actor = accounts[exec.vu.idInTest - 1];
    const path = `/api/v1/me/${actor.queue === 'soloq' ? 'solo' : 'premade'}-queue`;
    http.cookieJar().set(base, 'JSESSIONID', actor.session);
    const response = sse.open(base + path + '/events', {
        headers: { Cookie: `JSESSIONID=${actor.session}`, Accept: 'text/event-stream' },
        tags: { name: `${actor.queue}_stream` },
    }, client => {
        client.on('event', event => {
            if (event.name === 'ready') {
                streamReady.add(1);
                if (refresh(actor, path, event.name) && exec.vu.idInTest === 1) {
                    if (!controlledReconnect) {
                        controlledReconnect = true;
                        client.close();
                    } else {
                        resynced.add(1);
                    }
                }
            } else if (event.name === 'changed' || event.name === 'ping') {
                if (event.name === 'changed') streamChanged.add(1);
                if (event.name === 'ping') sleep((exec.vu.idInTest % 37) / 10);
                refresh(actor, path, event.name);
            }
        });
    });
    if (response && response.status !== 200) streamFailure.add(1);
    sleep(0.25 + (exec.vu.idInTest % 10) / 20);
}

export function handleSummary(summary) {
    return { [__ENV.RBGS_F16_SUMMARY]: JSON.stringify(summary, null, 2) };
}
