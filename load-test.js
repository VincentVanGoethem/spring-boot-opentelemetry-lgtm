import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';

const errorRate = new Rate('errors');

// Set DURATION to run a flat load for a fixed time instead of the full staged scenario.
// Accepts any k6 duration string, e.g. '30s', '2m', '1h'.
// Example: k6 run -e DURATION=1m load-test.js
const DURATION = __ENV.DURATION;

export const options = {
  ...(DURATION
    ? { duration: DURATION, vus: 5 }
    : {
        stages: [
          { duration: '30s', target: 5 },   // ramp up
          { duration: '2m',  target: 5 },   // steady load
          { duration: '30s', target: 20 },  // spike
          { duration: '1m',  target: 20 },  // hold spike
          { duration: '30s', target: 0 },   // ramp down
        ],
      }),
  thresholds: {
    http_req_failed:   ['rate<0.05'],
    http_req_duration: ['p(95)<2000'],
    errors:            ['rate<0.05'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// Set MYSTERY_BOX_REQUESTS to the number of mystery box orders to place (0 = disabled).
// Example: k6 run -e MYSTERY_BOX_REQUESTS=5 load-test.js
const MYSTERY_BOX_REQUESTS = parseInt(__ENV.MYSTERY_BOX_REQUESTS || '0', 10);
let mysteryBoxRequestsRemaining = MYSTERY_BOX_REQUESTS;

const SKUS = ['RUBBER-DUCK', 'FOREVER-PEN', 'ALARM-CLOCK', 'UMBRELLA'];

// Returns a random int in [min, max]
function randInt(min, max) {
  return Math.floor(Math.random() * (max - min + 1)) + min;
}

// Builds a random order from regular items only
function randomOrder() {
  const lines = [];

  const shuffled = SKUS.slice().sort(() => Math.random() - 0.5);
  const count = randInt(1, 3);
  for (let i = 0; i < count; i++) {
    lines.push({ sku: shuffled[i], quantity: randInt(1, 3) });
  }

  return lines;
}

export default function () {
  const headers = { 'Content-Type': 'application/json' };

  // --- place a regular order ---
  const order = randomOrder();
  const createRes = http.post(
    `${BASE_URL}/orders`,
    JSON.stringify(order),
    { headers }
  );

  const created = check(createRes, {
    'order created (201)': (r) => r.status === 201,
  });
  errorRate.add(!created);

  // --- mystery box orders (opt-in via MYSTERY_BOX_REQUESTS) ---
  if (mysteryBoxRequestsRemaining > 0) {
    mysteryBoxRequestsRemaining--;

    // Valid mystery box order (quantity = 1)
    const validRes = http.post(
      `${BASE_URL}/orders`,
      JSON.stringify([{ sku: 'MYSTERY_BOX', quantity: 1 }]),
      { headers }
    );
    check(validRes, {
      'mystery box order created (201)': (r) => r.status === 201,
    });
    errorRate.add(validRes.status !== 201);

    // Invalid mystery box order (quantity > 1, should be rejected)
    const invalidRes = http.post(
      `${BASE_URL}/orders`,
      JSON.stringify([{ sku: 'MYSTERY_BOX', quantity: 2 }]),
      { headers }
    );
    check(invalidRes, {
      'mystery box quantity > 1 rejected (4xx)': (r) => r.status >= 400 && r.status < 500,
    });
  }

  // --- occasionally list all orders ---
  if (Math.random() < 0.3) {
    const listRes = http.get(`${BASE_URL}/api/v1/orders`);
    check(listRes, {
      'orders listed (200)': (r) => r.status === 200,
    });
  }

  sleep(randInt(1, 3));
}