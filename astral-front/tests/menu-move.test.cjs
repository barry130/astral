const assert = require('node:assert/strict');
const { test } = require('node:test');
const { createSourceLoader } = require('./helpers/load-typescript.cjs');

const menu = (id, parentId = 0, sort = id - 1, children) => ({
  id, parentId, sort, name: `菜单${id}`, icon: '', path: `/test-${id}`, permission: '', type: 1,
  visible: 1, ...(children ? { children } : {}),
});
const response = (config, code = 200) => ({ config, status: 200, statusText: 'OK', headers: {},
  data: { code, msg: code === 200 ? '成功' : '模拟业务失败', data: null, timestamp: 1 } });

test('same-parent menu moves recompute destination index and never mutate or duplicate writes', () => {
  const { planMenuMove } = createSourceLoader()('components/admin/menu-move.ts');
  const original = [menu(1), menu(2), menu(3)];
  const snapshot = structuredClone(original);
  const plan = planMenuMove(original, 1, 3, 'before');
  assert.deepEqual(plan.tree.map((row) => row.id), [2, 1, 3]);
  assert.deepEqual(original, snapshot);
  assert.equal(new Set(plan.updates.map((row) => row.id)).size, plan.updates.length);
  assert.ok(plan.updates.every((row) => row.id > 0));
});

test('cross-parent menu moves preserve descendants and renumber only touched siblings', () => {
  const { planMenuMove } = createSourceLoader()('components/admin/menu-move.ts');
  const original = [menu(10, 0, 0, [menu(1, 10, 0, [menu(5, 1, 0)]), menu(2, 10, 1)]),
    menu(20, 0, 1, [menu(3, 20, 0), menu(4, 20, 1)])];
  const plan = planMenuMove(original, 1, 4, 'after');
  assert.deepEqual(plan.tree[0].children.map((row) => row.id), [2]);
  assert.deepEqual(plan.tree[1].children.map((row) => row.id), [3, 4, 1]);
  assert.equal(plan.tree[1].children[2].children[0].parentId, 1);
  assert.equal(plan.tree[1].children[2].parentId, 20);
  assert.equal(original[0].children[0].parentId, 10);
  assert.deepEqual(plan.updates.map((row) => row.id).sort((a, b) => a - b), [1, 2]);
});

test('menu plans reject cycles and virtual rows without mutating source data', () => {
  const { planMenuMove } = createSourceLoader()('components/admin/menu-move.ts');
  const original = [menu(1, 0, 0, [menu(2, 1, 0)]), menu(3, 0, 1)];
  const snapshot = structuredClone(original);
  assert.throws(() => planMenuMove(original, 1, 2), /子菜单/);
  assert.throws(() => planMenuMove(original, -1, 3), /只读/);
  assert.throws(() => planMenuMove(original, 1, -2), /只读/);
  assert.deepEqual(original, snapshot);
});

test('keyboard menu targets match the same real sibling order and never target virtual rows', () => {
  const { menuSiblingTargets } = createSourceLoader()('components/admin/menu-move.ts');
  const original = [menu(1), menu(-1), menu(2), menu(3)];
  assert.deepEqual(menuSiblingTargets(original, 2), { previousId: 1, nextId: 3 });
  assert.deepEqual(menuSiblingTargets(original, 1), { previousId: undefined, nextId: 2 });
});

test('menu persistence checks business failures, stops later writes, and reports partial persistence', async () => {
  const load = createSourceLoader();
  const client = load('api/client.ts').default;
  const { persistMenuOrder, MenuPartialSaveError } = load('api/menu.ts');
  const seen = [];
  client.defaults.adapter = async (config) => { seen.push(config.url); return response(config, seen.length === 2 ? 300 : 200); };
  await assert.rejects(persistMenuOrder([1, 2, 3].map((id) => ({ id, parentId: 0, sort: id - 1 }))),
    (error) => error instanceof MenuPartialSaveError && error.failedId === 2 &&
      error.completedIds.length === 1 && error.completedIds[0] === 1 && /部分生效/.test(error.message));
  assert.deepEqual(seen, ['/api/v1/admin/system/menu/1', '/api/v1/admin/system/menu/2']);
});

test('menu persistence dispatches each real axios write only after its predecessor confirms', async () => {
  const load = createSourceLoader();
  const client = load('api/client.ts').default;
  const { persistMenuOrder } = load('api/menu.ts');
  let started;
  const firstStarted = new Promise((resolve) => { started = resolve; });
  let release;
  const firstResponse = new Promise((resolve) => { release = resolve; });
  const seen = [];
  client.defaults.adapter = async (config) => {
    seen.push(config.url);
    if (seen.length === 1) { started(); await firstResponse; }
    return response(config);
  };
  const operation = persistMenuOrder([{ id: 1, parentId: 0, sort: 1 }, { id: 2, parentId: 0, sort: 0 }]);
  await firstStarted;
  assert.equal(seen.length, 1);
  release();
  assert.equal(await operation, 2);
  assert.equal(seen.length, 2);
});
