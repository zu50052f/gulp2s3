import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://app:8080';
const LOADTEST_RUN_ID = __ENV.LOADTEST_RUN_ID || 'current';
const REPORT_TABLE = __ENV.REPORT_TABLE || 'report_source_row';
const REPORT_OBJECT_PREFIX = __ENV.REPORT_OBJECT_PREFIX || 'reports/load-test';
const REPORT_ZIP_OBJECT_PREFIX = __ENV.REPORT_ZIP_OBJECT_PREFIX || REPORT_OBJECT_PREFIX;
const BLOB_OBJECT_PREFIX = __ENV.BLOB_OBJECT_PREFIX || 'blob/load-test';
const FILE_SIZE_BYTES = Number(__ENV.FILE_SIZE_BYTES || 2 * 1024 * 1024);
const BLOB_SETUP_MODE = __ENV.BLOB_SETUP_MODE || 'reuse-existing';
const BLOB_SEEDED_OBJECT_KEY = __ENV.BLOB_SEEDED_OBJECT_KEY || `${BLOB_OBJECT_PREFIX}/${LOADTEST_RUN_ID}-seed-file.bin`;
const REPORT_VUS = Number(__ENV.REPORT_VUS || 1);
const REPORT_DURATION = __ENV.REPORT_DURATION || '5m';
const REPORT_ZIP_VUS = Number(__ENV.REPORT_ZIP_VUS || 1);
const REPORT_ZIP_DURATION = __ENV.REPORT_ZIP_DURATION || REPORT_DURATION;
const REPORT_ZIP_START_TIME = __ENV.REPORT_ZIP_START_TIME || '30s';
const BLOB_VUS = Number(__ENV.BLOB_VUS || 1);
const BLOB_DURATION = __ENV.BLOB_DURATION || '5m';
const BLOB_START_TIME = __ENV.BLOB_START_TIME || '60s';
const REPORT_PAUSE_SECONDS = Number(__ENV.REPORT_PAUSE_SECONDS || 5);
const REPORT_ZIP_PAUSE_SECONDS = Number(__ENV.REPORT_ZIP_PAUSE_SECONDS || REPORT_PAUSE_SECONDS);
const BLOB_PAUSE_SECONDS = Number(__ENV.BLOB_PAUSE_SECONDS || 5);
const REPORT_JITTER_SECONDS = Number(__ENV.REPORT_JITTER_SECONDS || 2);
const REPORT_ZIP_JITTER_SECONDS = Number(__ENV.REPORT_ZIP_JITTER_SECONDS || 2);
const BLOB_JITTER_SECONDS = Number(__ENV.BLOB_JITTER_SECONDS || 2);
const REPORT_INITIAL_JITTER_SECONDS = Number(__ENV.REPORT_INITIAL_JITTER_SECONDS || 3);
const REPORT_ZIP_INITIAL_JITTER_SECONDS = Number(__ENV.REPORT_ZIP_INITIAL_JITTER_SECONDS || 3);
const BLOB_INITIAL_JITTER_SECONDS = Number(__ENV.BLOB_INITIAL_JITTER_SECONDS || 3);
const REPORT_TIMEOUT = __ENV.REPORT_TIMEOUT || '20m';
const REPORT_ZIP_TIMEOUT = __ENV.REPORT_ZIP_TIMEOUT || REPORT_TIMEOUT;
const BLOB_TIMEOUT = __ENV.BLOB_TIMEOUT || '20m';
const REPORT_REUSE_KEYS = (__ENV.REPORT_REUSE_KEYS || 'true').toLowerCase() !== 'false';

export const options = {
  scenarios: {
    report_stream: {
      executor: 'constant-vus',
      exec: 'reportStream',
      vus: REPORT_VUS,
      duration: REPORT_DURATION,
      gracefulStop: '15s',
    },
    report_zip_stream: {
      executor: 'constant-vus',
      exec: 'reportZipStream',
      vus: REPORT_ZIP_VUS,
      duration: REPORT_ZIP_DURATION,
      startTime: REPORT_ZIP_START_TIME,
      gracefulStop: '15s',
    },
    blob_transfer: {
      executor: 'constant-vus',
      exec: 'blobTransfer',
      vus: BLOB_VUS,
      duration: BLOB_DURATION,
      startTime: BLOB_START_TIME,
      gracefulStop: '15s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    'http_req_duration{scenario:report_stream}': ['p(95)<1200000'],
    'http_req_duration{scenario:report_zip_stream}': ['p(95)<1200000'],
    'http_req_duration{scenario:blob_transfer}': ['p(95)<1200000'],
  },
};

function createBinaryPayload(sizeBytes) {
  const payload = new Uint8Array(sizeBytes);
  for (let index = 0; index < payload.length; index += 1) {
    payload[index] = index % 251;
  }
  return payload.buffer;
}

function uniqueObjectKey(prefix, suffix) {
  const vu = typeof __VU !== 'undefined' ? __VU : 'setup';
  const iter = typeof __ITER !== 'undefined' ? __ITER : Math.floor(Math.random() * 1_000_000);
  return `${prefix}/${LOADTEST_RUN_ID}-${Date.now()}-${vu}-${iter}-${suffix}`;
}

function reportObjectKey() {
  if (!REPORT_REUSE_KEYS) {
    return uniqueObjectKey(REPORT_OBJECT_PREFIX, 'report.csv');
  }

  const slot = typeof __VU !== 'undefined' && __VU > 0 ? __VU : 'shared';
  return `${REPORT_OBJECT_PREFIX}/slot-${slot}-${LOADTEST_RUN_ID}-report.csv`;
}

function reportZipObjectKey() {
  if (!REPORT_REUSE_KEYS) {
    return uniqueObjectKey(REPORT_ZIP_OBJECT_PREFIX, 'report.zip');
  }

  const slot = typeof __VU !== 'undefined' && __VU > 0 ? __VU : 'shared';
  return `${REPORT_ZIP_OBJECT_PREFIX}/slot-${slot}-${LOADTEST_RUN_ID}-report.zip`;
}

function waitForHealth() {
  const deadline = Date.now() + 120000;
  while (Date.now() < deadline) {
    const response = http.get(`${BASE_URL}/actuator/health`);
    if (response.status === 200) {
      return;
    }
    sleep(2);
  }
  throw new Error('Service did not become healthy before the timeout expired.');
}

function firstIteration() {
  return typeof __ITER !== 'undefined' && __ITER === 0;
}

function sleepWithJitter(baseSeconds, jitterSeconds) {
  const jitter = jitterSeconds > 0 ? Math.random() * jitterSeconds : 0;
  sleep(baseSeconds + jitter);
}

export function setup() {
  waitForHealth();

  if (BLOB_SETUP_MODE === 'reuse-existing') {
    const listResponse = http.get(`${BASE_URL}/api/files`, {
      timeout: '1m',
      tags: {
        setup: 'blob_lookup',
      },
    });

    check(listResponse, {
      'setup file lookup returned 200': (res) => res.status === 200,
    });

    const storedFile = listResponse.json().find((file) => file.objectKey === BLOB_SEEDED_OBJECT_KEY);
    if (listResponse.status !== 200 || !storedFile) {
      throw new Error(`Seeded file ${BLOB_SEEDED_OBJECT_KEY} was not found. Response: ${listResponse.body}`);
    }

    return {
      fileId: storedFile.id,
    };
  }

  const objectKey = uniqueObjectKey(BLOB_OBJECT_PREFIX, 'seed-file.bin');
  const uploadResponse = http.post(
    `${BASE_URL}/api/files`,
    {
      file: http.file(createBinaryPayload(FILE_SIZE_BYTES), 'load-test.bin', 'application/octet-stream'),
      objectKey,
    },
    {
      timeout: '5m',
      tags: {
        setup: 'blob_upload',
      },
    },
  );

  check(uploadResponse, {
    'setup upload returned 201': (res) => res.status === 201,
  });

  const fileId = uploadResponse.json('id');
  if (uploadResponse.status !== 201 || fileId == null) {
    throw new Error(`Setup upload failed with status ${uploadResponse.status}: ${uploadResponse.body}`);
  }

  return {
    fileId,
  };
}

export function reportStream() {
  if (firstIteration()) {
    sleepWithJitter(0, REPORT_INITIAL_JITTER_SECONDS);
  }

  const response = http.post(
    `${BASE_URL}/api/reports/${REPORT_TABLE}/transfer?objectKey=${encodeURIComponent(reportObjectKey())}`,
    null,
    {
      timeout: REPORT_TIMEOUT,
      tags: {
        endpoint: 'report-transfer',
      },
    },
  );

  check(response, {
    'report transfer returned 200': (res) => res.status === 200,
  });

  sleepWithJitter(REPORT_PAUSE_SECONDS, REPORT_JITTER_SECONDS);
}

export function reportZipStream() {
  if (firstIteration()) {
    sleepWithJitter(0, REPORT_ZIP_INITIAL_JITTER_SECONDS);
  }

  const response = http.post(
    `${BASE_URL}/api/reports/${REPORT_TABLE}/transfer?zip=true&objectKey=${encodeURIComponent(reportZipObjectKey())}`,
    null,
    {
      timeout: REPORT_ZIP_TIMEOUT,
      tags: {
        endpoint: 'report-transfer-zip',
      },
    },
  );

  check(response, {
    'zip report transfer returned 200': (res) => res.status === 200,
  });

  sleepWithJitter(REPORT_ZIP_PAUSE_SECONDS, REPORT_ZIP_JITTER_SECONDS);
}

export function blobTransfer(data) {
  if (firstIteration()) {
    sleepWithJitter(0, BLOB_INITIAL_JITTER_SECONDS);
  }

  const response = http.post(
    `${BASE_URL}/api/files/${data.fileId}/transfer`,
    null,
    {
      timeout: BLOB_TIMEOUT,
      tags: {
        endpoint: 'blob-transfer',
      },
    },
  );

  check(response, {
    'blob transfer returned 200': (res) => res.status === 200,
  });

  sleepWithJitter(BLOB_PAUSE_SECONDS, BLOB_JITTER_SECONDS);
}
