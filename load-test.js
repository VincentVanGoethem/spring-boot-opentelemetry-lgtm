import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';

const errorRate = new Rate('errors');

export const options = {
  stages: [
    { duration: '30s', target: 5 },   // ramp up
    { duration: '2m',  target: 5 },   // steady load
    { duration: '30s', target: 20 },  // spike
    { duration: '1m',  target: 20 },  // hold spike
    { duration: '30s', target: 0 },   // ramp down
  ],
  thresholds: {
    http_req_failed:   ['rate<0.05'],
    http_req_duration: ['p(95)<2000'],
    errors:            ['rate<0.05'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

const SKUS = ['RUBBER-DUCK', 'FOREVER-PEN', 'ALARM-CLOCK', 'UMBRELLA'];

// Returns a random int in [min, max]
function randInt(min, max) {
  return Math.floor(Math.random() * (max - min + 1)) + min;
}

// Builds a random order — mix of regular items, occasionally a mystery box
function randomOrder() {
  const lines = [];

  // 1–3 regular items
  const shuffled = SKUS.slice().sort(() => Math.random() - 0.5);
  const count = randInt(1, 3);
  for (let i = 0; i < count; i++) {
    lines.push({ sku: shuffled[i], quantity: randInt(1, 3) });
  }

  // 20 % chance of adding a mystery box (quantity must be 1)
  if (Math.random() < 0.2) {
    lines.push({ sku: 'MYSTERY_BOX', quantity: 1 });
  }

  return lines;
}

export default function () {
  const headers = { 'Content-Type': 'application/json' };

  // --- place an order ---
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

  // --- occasionally list all orders ---
  if (Math.random() < 0.3) {
    const listRes = http.get(`${BASE_URL}/api/v1/orders`);
    check(listRes, {
      'orders listed (200)': (r) => r.status === 200,
    });
  }

  sleep(randInt(1, 3));
}